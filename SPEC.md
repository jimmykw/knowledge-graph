# Spec: Skill Support and Tool-Calling Refactor via spring-ai-agent-utils

## Summary

Replace the hand-rolled Cypher-generation -> execution -> synthesis retry loops in the chat flow with a single model-driven tool-calling invocation. Introduce `spring-ai-agent-utils` `SkillsTool` with 7 Markdown-defined skills that guide the model for common knowledge-graph question types. Expose 4 graph-interaction `@Tool` methods (`getGraphSchema`, `runReadCypher`, `findEntityByName`, `getEntityNeighborhood`) so the model can pull schema, execute read-only Cypher, and look up entities autonomously. A custom `ToolCallingManager` wrapper captures tool-call traces to populate the existing `ChatResponse` shape.

## Goals

- Add `SkillsTool` from `spring-ai-agent-utils` 0.10.0 to the chat `ChatClient`, loading 7 SKILL.md files from classpath
- Replace `ChatService.cypherLoop` + `synthesisLoop` with one `chatClient.prompt(prompt).tools(...).call()` call
- Expose 4 graph `@Tool` methods in a new `GraphTools` class
- Keep the existing `ChatResponse` shape, populated from tool-call traces via a custom `ToolCallingManager` wrapper
- Enforce read-only Cypher via regex blocklist + existing `executeRead` transaction
- Delete all dead code: `CypherGenerationService`, `AnswerSynthesisService`, `ChatRecords.CypherGeneration`, `BeanOutputConverter<CypherGeneration>`, loop-state records
- Verify glm-5.2 tool-calling support with a throwaway test before implementation

## Non-Goals

- Modifying the PDF ingestion / entity extraction / relationship extraction flow (`ExtractionPrompter`, `EntityExtractionService`, `RelationshipExtractionService`)
- Changing the `POST /api/knowledge-graph` ingestion endpoint or `GraphSummaryResponse`
- Changing the `GET /api/knowledge-graph/schema` endpoint
- Adding chat memory / conversation history (no `MessageChatMemoryAdvisor` in this phase)
- Adding MCP server/client support
- Skills with helper scripts (no `ShellTools` or `FileSystemTools` needed - all 7 skills are single-file)

## Background

The current chat flow (`ChatService.chat()`, ChatService.java:25) runs three sequential phases with hand-rolled retry loops:

1. **Schema load** - `SchemaService.readSchemaForChat()` returns `SchemaSnapshot(labels, relTypes)`
2. **Cypher loop** - `cypherLoop` (ChatService.java:48) iterates up to `app.chat.maxAttempts` (default 3), calling `CypherGenerationService.generate()` -> `CypherExecutor.execute()`. Failures feed `priorError` into the next attempt.
3. **Synthesis loop** - `synthesisLoop` (ChatService.java:86) iterates up to `maxAttempts`, calling `AnswerSynthesisService.synthesize()` with the successful Cypher + rows.

Key classes and their roles:
- `CypherGenerationService` (CypherGenerationService.java:21) - LLM call with a system prompt that constrains to read-only Cypher, uses `BeanOutputConverter<CypherGeneration>` to parse the response into a `cypher` string.
- `CypherExecutor` (CypherExecutor.java:28) - executes Cypher via `session.executeRead()`, caps rows at `resultRowLimit` (default 50), flattens nodes/relationships/paths to `List<Map<String, Object>>`.
- `AnswerSynthesisService` (AnswerSynthesisService.java:21) - LLM call that takes the question, Cypher, and rows, returns a natural-language answer.
- `ChatClient` (ChatClientConfig.java:15) - single shared bean, injected into both `ExtractionPrompter` and the chat services.
- `AppProperties.Chat` (AppProperties.java:8) - config: `resultRowLimit`, `maxPromptLength`, `maxAttempts`, `queryTimeoutMs`.

Graph conventions (Neo4jGraphWriter.java:49-55):
- Entity nodes: `name`, `name_norm` (trim + lowercase), `label`, `description`, `source_doc` (hash)
- `:Document` nodes: `hash`, `filename`, `uploadedAt`
- Relationships: `description` property
- Dynamic labels/rel-types via APOC `apoc.merge.node` / `apoc.merge.relationship`

## Design

### 1. Dependency

Add to `build.gradle.kts`:

```kotlin
implementation(platform("org.springaicommunity:spring-ai-agent-utils-bom:0.10.0"))
implementation("org.springaicommunity:spring-ai-agent-utils")
```

### 2. Separate Chat ChatClient Bean

Add a qualified `chatChatClient` bean in `ChatClientConfig` that registers `SkillsTool` + `GraphTools` as default tools and sets the unified system prompt + `maxToolCallRounds`. The existing `chatClient` bean stays clean for `ExtractionPrompter`.

```java
@Bean
ChatClient chatChatClient(ChatClient.Builder builder, SkillsTool skillsTool, GraphTools graphTools,
                           AppProperties appProperties) {
    return builder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultTools(skillsTool, graphTools)
        .defaultOptions(ToolCallingChatOptions.builder()
            .maxToolCallRounds(appProperties.chat().maxToolCallRounds())
            .build())
        .build();
}
```

Update `AppConfig` to inject `chatChatClient` (by qualifier) into `ChatService` instead of the individual services.

### 3. GraphTools Class

New class `net.jimmykw.knowledgegraph.chat.GraphTools` with `@Tool`-annotated methods. Dependencies: `SchemaService`, `CypherExecutor`, `Driver` (for `findEntityByName` and `getEntityNeighborhood` direct queries). Registered as a bean in `AppConfig`.

```java
@Slf4j
@RequiredArgsConstructor
public class GraphTools {
    private final SchemaService schemaService;
    private final CypherExecutor cypherExecutor;
    private final Driver driver;

    @Tool(description = "Get the knowledge graph schema: node labels and relationship types")
    public String getGraphSchema() { ... }

    @Tool(description = "Execute a read-only Cypher query against the knowledge graph. "
        + "Returns JSON rows. Write operations (CREATE, MERGE, SET, DELETE, DROP, apoc.merge) are blocked.")
    public String runReadCypher(String cypher) { ... }

    @Tool(description = "Find an entity by name (case-insensitive). Returns name, label, description. "
        + "Falls back to fuzzy substring match if exact match fails.")
    public String findEntityByName(String name) { ... }

    @Tool(description = "Get the k-hop neighborhood of an entity (default 1-hop). "
        + "Returns related entities grouped by relationship type.")
    public String getEntityNeighborhood(String name, int depth) { ... }
}
```

**Read-only guard** in `runReadCypher`: regex blocklist before execution.

```java
private static final Pattern WRITE_PATTERN = Pattern.compile(
    "\\b(CREATE|MERGE|SET\\b|DELETE|REMOVE|DROP|CALL\\s+apoc\\.(merge|create))\\b",
    Pattern.CASE_INSENSITIVE);

// In runReadCypher:
if (WRITE_PATTERN.matcher(cypher).find()) {
    return "Error: write operations are not allowed. Only read-only Cypher is permitted.";
}
```

**Error handling** in `runReadCypher`: catch `Neo4jUnavailableException` and return error string to model (do not throw). Set `ChatResponse.error` via the trace accumulator.

### 4. SkillsTool Configuration

```java
@Bean
SkillsTool skillsTool() {
    return SkillsTool.builder()
        .addSkillsResource(new ClassPathResource(".claude/skills"))
        .build();
}
```

Skills loaded from `src/main/resources/.claude/skills/` (packaged in the JAR).

### 5. Seven Skills

| Skill | Directory | Trigger |
|---|---|---|
| `entity-lookup` | `.claude/skills/entity-lookup/SKILL.md` | "tell me about X", "find X" |
| `neighborhood-exploration` | `.claude/skills/neighborhood-exploration/SKILL.md` | "what's related to X", "X's connections" |
| `path-finding` | `.claude/skills/path-finding/SKILL.md` | "how are X and Y related", "connection between" |
| `source-attribution` | `.claude/skills/source-attribution/SKILL.md` | "where does X come from", "cite the source" |
| `graph-statistics` | `.claude/skills/graph-statistics/SKILL.md` | "how many X", "summarize the graph" |
| `empty-result-recovery` | `.claude/skills/empty-result-recovery/SKILL.md` | Fires after a 0-row query result |
| `multi-hop-reasoning` | `.claude/skills/multi-hop-reasoning/SKILL.md` | Chained relational questions |

Each SKILL.md has YAML frontmatter (`name`, `description`) and a body with Cypher patterns, graph conventions, answer shape, and read-only constraint. All single-file (no `reference.md` or scripts).

### 6. Tool Trace Capture via Custom ToolCallingManager

Wrap the default `ToolCallingManager` to record every tool execution into a mutable `ToolTrace` accumulator:

```java
public class TracingToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate;
    private final ToolTrace trace;

    // delegate all methods, but after executeToolCalls(), record:
    // - tool name, input arguments, output, success/failure
    // - for runReadCypher: the cypher string, row count, truncated flag
}
```

`ChatService` reads `trace` after the model call to populate `ChatResponse`:
- `cypher` - last `runReadCypher` invocation's Cypher string
- `rows` - last `runReadCypher` result rows
- `count` - last `runReadCypher` row count
- `truncated` - last `runReadCypher` truncated flag
- `error` - first error from any tool call (Neo4j-unavailable, etc.)

### 7. Refactored ChatService

```java
public ChatResponse chat(String prompt) {
    validate(prompt);
    val trace = new ToolTrace();
    val tracingManager = new TracingToolCallingManager(defaultManager, trace);
    val answer = chatClient.prompt(prompt)
        .toolCallingManager(tracingManager)
        .call()
        .content();
    return buildResponse(answer, trace);
}
```

`validate()` stays (checks blank + `maxPromptLength`). `buildResponse()` maps the trace to `ChatResponse`.

### 8. Unified System Prompt

Single system prompt on `chatChatClient` combining:
- Read-only Cypher constraint (from `CypherGenerationService.SYSTEM_PROMPT`)
- Graph property conventions (`name_norm`, `source_doc`, `:Document`)
- Answer-grounding rules (from `AnswerSynthesisService.SYSTEM_PROMPT`)
- Instruction to use skills for matching question types and graph tools for data access

### 9. AppProperties Changes

```java
public record Chat(int resultRowLimit, int maxPromptLength, int maxToolCallRounds, long queryTimeoutMs) {
    public Chat {
        if (resultRowLimit <= 0) resultRowLimit = 50;
        if (maxPromptLength <= 0) maxPromptLength = 2000;
        if (maxToolCallRounds <= 0) maxToolCallRounds = 10;
        if (queryTimeoutMs <= 0) queryTimeoutMs = 10000;
    }
}
```

`application.yml`: `max-attempts` -> `max-tool-call-rounds: 10`

### 10. Dead Code Deletion

| File / Symbol | Action |
|---|---|
| `CypherGenerationService.java` | Delete |
| `AnswerSynthesisService.java` | Delete |
| `ChatRecords.java` (contains `CypherGeneration` record) | Delete |
| `BeanOutputConverter<CypherGeneration>` bean (ChatClientConfig.java:25) | Delete |
| `BeanOutputConverter<ExtractionResult>` bean | Keep (extraction still uses it) |
| `ChatService.CypherLoopState`, `CypherLoopOutcome`, `SynthesisState` | Delete |
| `AppConfig` beans for `cypherGenerationService`, `answerSynthesisService` | Delete |
| `AppConfig` bean for `chatService` | Update to inject `chatChatClient` + `GraphTools` |

`CypherExecutor` stays (used by `GraphTools.runReadCypher`). `SchemaService` stays (used by `GraphTools.getGraphSchema` + the `/schema` endpoint).

### 11. Tool-Calling Probe (Pre-Implementation)

Throwaway test class `src/test/java/.../ToolCallingProbeTest.java`:
- Constructs a `ChatClient` with one trivial `@Tool` method (`getCurrentTime`)
- Sends "What time is it?"
- Asserts the response contains a time (implying the tool was called)
- Run manually, not in CI
- Deleted after verification

If the probe fails, the spec is blocked - fall back to a non-tool approach (inject SKILL.md content into the prompt directly, keep the existing loops).

## Edge Cases

| Case | Expected Behavior |
|---|---|
| glm-5.2 doesn't support tool calling | Probe fails. Spec is blocked. Fall back: inject skill content into prompt manually, keep existing loops. |
| Model calls `runReadCypher` with write Cypher | Regex blocklist returns error string to model. Model should retry with read-only Cypher. |
| Neo4j is down during a tool call | `runReadCypher` returns error string to model. `ChatResponse.error` is set. HTTP 200 (not 503). Model includes the error in its answer. |
| Model exceeds `maxToolCallRounds` | Spring AI returns the response as-is. `ChatResponse.answer` may be incomplete. `error` set to "Exceeded max tool call rounds". |
| Model never calls any tools | Returns a direct answer. `cypher`/`rows`/`count` are null/empty in `ChatResponse`. |
| `findEntityByName` exact match fails | Fuzzy `CONTAINS` fallback. If still no match, returns "not found" to model. |
| Empty graph (no `:Document` ingested) | `getGraphSchema` returns empty labels/relTypes. `SchemaService.readSchemaForChat` currently throws `GraphEmptyException` -> need to handle this in `GraphTools.getGraphSchema` by returning an empty-schema string instead of throwing. |
| `runReadCypher` returns 0 rows | Model should invoke `empty-result-recovery` skill for guidance. |
| Prompt is blank or too long | `ChatService.validate()` throws `InvalidPromptException` -> 400 (unchanged). |
| Model calls `runReadCypher` multiple times | `ChatResponse` populated from the LAST invocation's trace. Earlier invocations are in the trace but not surfaced in the response. |

## Security Considerations

- **Cypher injection**: `runReadCypher` exposes raw Cypher execution. The regex blocklist prevents write operations. `session.executeRead()` enforces read-only at the transaction level. APOC write procedures (`apoc.merge.*`, `apoc.create.*`) are blocked by the regex.
- **No label/type string interpolation**: Graph tools use parameterized Cypher (`$name`, `$depth`) - consistent with the APOC convention for dynamic writes. `findEntityByName` and `getEntityNeighborhood` use `toLower($name)` parameterization, never string concatenation.
- **Skills are read-only Markdown**: No `ShellTools` or `FileSystemTools` registered. Skills cannot execute code or access the filesystem.
- **LLM API key**: Unchanged - already in `application.yml` via `OPENAI_API_KEY` env var.

## Backward Compatibility

| Caller / Contract | Impact |
|---|---|
| `POST /api/knowledge-graph/chat` | Response shape unchanged (`ChatResponse`). Internal behavior changes from 3-phase loop to single tool-calling call. Callers see no difference. |
| `POST /api/knowledge-graph` (ingestion) | No change. Extraction flow untouched. |
| `GET /api/knowledge-graph/schema` | No change. |
| `application.yml` | `app.chat.max-attempts` -> `app.chat.max-tool-call-rounds`. Existing deployments need this config key renamed. |
| `AppProperties.Chat` record | Field rename: `maxAttempts` -> `maxToolCallRounds`. Any code referencing `appProperties.chat().maxAttempts()` must update. |

## Alternatives Considered

| Alternative | Why Not |
|---|---|
| Skills on shared `ChatClient` | Extraction prompts would include skill tool definitions - noise on every extraction call, potential confusion. |
| Keep retry loops, skills additive | User chose full replacement. Keeps dead retry logic that fights with the model's own iteration. |
| AST-based Cypher guard | Requires a Cypher parser dependency (neo4j-cypher-frontend or similar). Overkill - regex blocklist + `executeRead` covers the attack surface. |
| `ToolContext` accumulator for traces | User chose custom `ToolCallingManager` wrapper. More idiomatic, doesn't require passing context through every call. |
| Raw HTTP probe for tool-calling verification | Tests the API but not the Spring AI integration. Throwaway test class tests the full stack. |
| Minimal system prompt, rely on skills | Skills are invoked by semantic matching - not guaranteed. The system prompt ensures baseline constraints (read-only, grounding) are always present. |
| Ship only 2 skills in v1 | User chose all 7. Full coverage from day one; each skill is a small Markdown file with low maintenance cost. |

## Open Questions

- Does `ToolCallingChatOptions.maxToolCallRounds` exist in Spring AI 2.0.0? The docs reference `ToolCallingManager` but the exact config API for capping rounds needs verification during implementation. If not available, a custom counter in the `TracingToolCallingManager` can enforce the limit.
- Does `ChatClient.Builder` expose `.toolCallingManager()` for injecting a custom `ToolCallingManager`? If not, the trace capture may need to move to an advisor or a wrapper around the `ChatModel`. Verify during the probe phase.
- Should `ChatResponse` include ALL `runReadCypher` invocations (not just the last) for debugging? Currently spec'd as last-only. Could add a `List<CypherExecution>` field if needed.

## Task Breakdown

1. **Add dependency** - Add `spring-ai-agent-utils-bom` platform and `spring-ai-agent-utils` to `build.gradle.kts`. Verify JAR resolves.
2. **Write tool-calling probe test** - Create `src/test/java/.../ToolCallingProbeTest.java` with a trivial `@Tool`. Run it. Verify glm-5.2 returns tool-call responses. **Block here if it fails.**
3. **Create 7 SKILL.md files** - `entity-lookup`, `neighborhood-exploration`, `path-finding`, `source-attribution`, `graph-statistics`, `empty-result-recovery`, `multi-hop-reasoning` in `src/main/resources/.claude/skills/`.
4. **Create `GraphTools` class** - `@Tool` methods: `getGraphSchema`, `runReadCypher` (with regex guard), `findEntityByName`, `getEntityNeighborhood`. Add `@Bean` in `AppConfig`.
5. **Create `TracingToolCallingManager`** - Wraps default `ToolCallingManager`, records tool executions into `ToolTrace`.
6. **Create `ToolTrace` record** - Mutable accumulator: list of tool calls (name, input, output, error), last Cypher execution details.
7. **Write unified system prompt** - On `chatChatClient` builder. Combine read-only constraint + graph conventions + grounding rules.
8. **Add `chatChatClient` bean** - In `ChatClientConfig`: `SkillsTool` + `GraphTools` as default tools, unified system prompt, `maxToolCallRounds`.
9. **Add `SkillsTool` bean** - In `ChatClientConfig`: `addSkillsResource(new ClassPathResource(".claude/skills"))`.
10. **Update `AppProperties.Chat`** - Replace `maxAttempts` with `maxToolCallRounds` (default 10). Update `application.yml`.
11. **Refactor `ChatService`** - Replace `chat()` with single tool-calling call + trace-based response building. Delete loop methods, state records.
12. **Delete dead code** - `CypherGenerationService.java`, `AnswerSynthesisService.java`, `ChatRecords.java`, `BeanOutputConverter<CypherGeneration>` bean, `AppConfig` beans for deleted services.
13. **Update `AppConfig`** - Wire `chatChatClient` + `GraphTools` into `ChatService`. Remove dead service beans.
14. **Handle `GraphEmptyException` in `GraphTools.getGraphSchema`** - Return empty-schema string instead of throwing, so the model can respond gracefully.
15. **Compile and verify** - `./gradlew compileJava`. Manual test via `POST /api/knowledge-graph/chat` with the `procurement.pdf` graph.
16. **Delete probe test** - Remove `ToolCallingProbeTest.java` after verification.
