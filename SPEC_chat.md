# Spec: Knowledge Graph Chat (Text-to-Cypher Q&A)

## Summary
Add a synchronous, single-turn `POST /api/knowledge-graph/chat` endpoint that takes a natural-language prompt, uses glm-5.2 to generate a **read-only Cypher query** against the existing knowledge graph, executes it, and synthesizes a natural-language answer from the results — returning the answer, the generated Cypher, and the raw result rows for transparency (mirroring Neo4j LLM Graph Builder's "Chat with Data" / graph mode). A `:Schema` node, maintained by the existing ingestion pipeline, supplies the live graph's labels and relationship types to the Cypher-generation prompt — resolving the free-form-labels problem without manual config. A companion `GET /api/knowledge-graph/schema` exposes the same schema.

## Goals
- `POST /api/knowledge-graph/chat` (JSON `{ "prompt": string }`) → `ChatResponse { answer, cypher, results, truncated, rowCount, error }`.
- Two-step LLM flow: (1) generate Cypher from `{prompt, schema}`, (2) synthesize answer from `{prompt, cypher, results, truncated}`.
- Retry-on-error loop for **both** LLM calls: 1 initial + 2 retries, feeding the prior failure (Neo4j error or parse failure) back into the next attempt.
- Read-only guarantee via Neo4j driver `session.executeRead(...)` (server rejects writes).
- Bounded result payload: read at most `app.chat.result-row-limit` rows (default 50), flat-JSON serialized, with `truncated` + `rowCount`.
- `:Schema` node maintained by `Neo4jGraphWriter` during ingestion (labels + rel types), read by chat and by `GET /schema`.
- `GET /api/knowledge-graph/schema` → `{ "labels": [...], "relTypes": [...] }`.
- HTTP semantics: empty graph → 422; retry exhaustion → 422; Neo4j down → 503; bad prompt → 400.

## Non-Goals
- No multi-turn conversation / server-side history (single-turn, stateless).
- No vector / embedding / fulltext fallback (the app stores no chunk text or embeddings).
- No Cypher-parameterization of the LLM query (the whole query string is LLM-generated; safety comes from read transactions, not APOC).
- No streaming / SSE (synchronous, like the existing endpoint).
- No auth (localhost-only, `server.address: 127.0.0.1`, unchanged).
- No schema for property keys stored in the `:Schema` node (labels + rel types only; a fixed property-hint goes in the prompt).
- No multi-query generation (single Cypher per prompt).

## Background
The app currently has one endpoint, `POST /api/knowledge-graph` (multipart PDF), wired in `GraphController.java:30`. Ingestion (`PdfIngestionService`) → pass-1 entities (`EntityExtractionService`) → pass-2 relationships (`RelationshipExtractionService`). All Neo4j access goes through `Neo4jGraphWriter` (`graph/Neo4jGraphWriter.java`), which writes via APOC (`apoc.merge.node`, `apoc.merge.relationship`) with labels/types **parameterized** — the injection-safety mechanism for ingestion. LLM access is via Spring AI `ChatClient` (bean in `ChatClientConfig.java:14`) + `BeanOutputConverter<ExtractionResult>` (in `ExtractionPrompter`), with a 1-retry-on-failure helper. Beans are declared explicitly in `AppConfig.java` (no component scanning). Vavr (`Try`, `Option`, `List`, `HashMap`) is used throughout; exceptions are reserved for hard-fail HTTP paths mapped by `ApiExceptionHandler` (400/413/503/500). `AppProperties` (`config/AppProperties.java`) binds `app.*` from `application.yml`.

The graph schema is **free-form**: entity labels and relationship types are chosen by the LLM at ingestion time. Entity nodes carry `name`, `label`, `description`, `name_norm`, `source_doc`; relationships carry `description`; `:Document` nodes carry `hash`, `filename`, `uploadedAt`. There is no `:Chunk` persistence and no vector index. These constraints drive the design: a static config schema would go stale whenever a new PDF introduces new labels, so the schema is instead maintained by ingestion itself.

## Design

### Project layout (new files)
```
src/main/java/com/example/knowledgegraph/
  chat/ChatController.java              // POST /api/knowledge-graph/chat, GET /api/knowledge-graph/schema
  chat/ChatRequest.java                  // record(String prompt)
  chat/ChatResponse.java                 // record(String answer, String cypher, List<Map<String,Object>> results,
                                         //        boolean truncated, int rowCount, String error)
  chat/ChatService.java                  // orchestrates the two-step + retry flow
  chat/CypherGenerationService.java     // LLM call 1 (Cypher) + retry-on-error loop
  chat/AnswerSynthesisService.java       // LLM call 2 (answer) + retry
  chat/CypherExecutor.java               // session.executeRead, read-cap N, flatten rows
  chat/SchemaService.java                // read :Schema node (backs chat + GET /schema)
  chat/ChatRecords.java                  // record CypherGeneration(String cypher)
  exception/ChatException.java           // → 422 (retry exhaustion / synthesis failure)
  exception/GraphEmptyException.java     // → 422 (no :Schema node / zero labels)
  exception/InvalidPromptException.java  // → 400 (blank/over-long prompt)
```
Modified existing files: `graph/Neo4jGraphWriter.java` (schema maintenance + `readSchema()`), `config/AppProperties.java` (add `chat`), `config/AppConfig.java` (wire chat beans), `api/ApiExceptionHandler.java` (422/400 handlers), `src/main/resources/application.yml` (new `app.chat.*`).

### Configuration (`AppProperties`, `application.yml`)
Add a nested chat config:
```yaml
app:
  max-pages: 50
  chunk-tokens: 1000
  pool-size: 4
  chat:
    result-row-limit: 50      # max rows read from LLM Cypher results
    max-prompt-length: 2000   # over this → 400
    max-attempts: 3           # 1 initial + 2 retries, for BOTH LLM calls
    query-timeout-ms: 10000   # per Cypher execution
```
```java
@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, int poolSize, Chat chat) {
    public record Chat(int resultRowLimit, int maxPromptLength, int maxAttempts, long queryTimeoutMs) {}
    // compact constructors with the same <= 0 → default guards
}
```

### API contract
`POST /api/knowledge-graph/chat` (JSON):
```java
public record ChatRequest(String prompt) {}
```
`200` → `ChatResponse`:
```java
public record ChatResponse(
    String answer,                       // null only if synthesis failed (but with retry x2 → 422 instead)
    String cypher,                       // the final generated Cypher (last attempt on failure)
    List<Map<String, Object>> results,   // flat-JSON rows, read-capped; null if empty-graph short-circuit
    boolean truncated,                   // true if rowCount == result-row-limit and more rows existed
    int rowCount,
    String error                         // null on success; populated on 422 paths
) {}
```
`GET /api/knowledge-graph/schema` `200` → `{ "labels": [...], "relTypes": [...] }`.

Status mapping:
- `400` — blank prompt or `prompt.length() > max-prompt-length` (`InvalidPromptException`).
- `422` — empty graph, no `:Schema` node, or zero labels (`GraphEmptyException`); retry exhaustion on Cypher gen or synthesis (`ChatException`).
- `503` — Neo4j down (existing `Neo4jUnavailableException`).
- `500` — unexpected (existing generic handler).

### `:Schema` node (maintained by ingestion)
A single node `:Schema { id: "default", labels: [String], relTypes: [String], updatedAt: String }`, maintained by `Neo4jGraphWriter`:
```cypher
MERGE (s:Schema {id: $id})
SET s.labels = apoc.coll.union(coalesce(s.labels, []), [$label]),
    s.relTypes = apoc.coll.union(coalesce(s.relTypes, []), [$type]),
    s.updatedAt = $updatedAt
RETURN s
```
- `mergeDocument(...)` records label `"Document"`.
- `mergeEntity(...)` records `$label` (entity label).
- `mergeRelationship(...)` records `$type` (rel type).
- `:Schema` itself is **never** recorded as a label (it's internal metadata).
- `readSchema()` returns `Option<SchemaSnapshot>` (`SchemaSnapshot(List<String> labels, List<String> relTypes)`); `None` if the node is absent.

### Cypher-generation prompt (LLM call 1)
System + user prompt assembled by `CypherGenerationService`:
- **Schema block**: `Labels: [...]` and `Relationship types: [...]` from `SchemaService`.
- **Standard-properties hint** (fixed text, since the schema node stores labels only):
  > Entity nodes have properties: `name`, `label`, `description`, `name_norm`, `source_doc`. Relationships have: `description`. `Document` nodes have: `hash`, `filename`, `uploadedAt`. Use `name` for display; match on `name_norm` (lowercase) for case-insensitive lookups.
- **Constraints**: emit a single read-only Cypher query; no `CREATE/MERGE/SET/DELETE/REMOVE/DROP`; no `CALL apoc.merge.*`; return only the query.
- Structured output via `new BeanOutputConverter<>(CypherGeneration.class)` → `record CypherGeneration(String cypher)`. Reuse the JSON-repair path from `ExtractionPrompter.repairJson`.

### Retry-on-error loop (both LLM calls)
`ChatService` drives a `foldLeft` over `attempt ∈ [1..maxAttempts]`:
1. Build prompt (Cypher-gen includes the schema; on retries, append "Previous attempt failed: `<error>`. Regenerate.").
2. `CypherGenerationService.generate(prompt)` → `Try<CypherGeneration>` (parse failure → `Failure`).
3. On success → `CypherExecutor.execute(cypher)` → `Try<ExecutedResults>`; a Neo4j error (syntax, write-rejected by `executeRead`, timeout) → `Failure` with the server message as the error.
4. On failure → record the error, feed it into the next attempt's prompt.
5. On exhaustion (`maxAttempts` reached) → throw `ChatException` carrying the last Cypher + last error → `ApiExceptionHandler` → `422` with `ChatResponse{ answer=null, cypher=last, results=null, error="Could not generate valid Cypher after N attempts: <last error>" }`.

### `CypherExecutor` (read-only execution + row cap)
```java
public ExecutedResults execute(String cypher) {
    return Try.withResources(() -> driver.session())
        .of(session -> session.executeRead(tx -> {
            val result = tx.run(cypher);
            val rows = new ArrayList<Map<String,Object>>();
            int count = 0; boolean truncated = false;
            while (result.hasNext()) {
                if (count >= limit) { truncated = true; break; }
                rows.add(flatten(result.next()));
                count++;
            }
            return new ExecutedResults(rows, count, truncated);
        })).getOrElseThrow(this::wrapNeo4jException);
}
```
- `session.executeRead` is the read-only guard: the server rejects any write with an exception that the retry loop surfaces.
- `flatten(Record)` converts each value: `Node` → `{ "__type":"node", "label":..., "name":..., "description":..., ...props }`; `Relationship` → `{ "__type":"rel", "type":..., "description":..., "source":<name>, "target":<name> }`; `Path` → list of node/rel maps; scalars pass through. Internal Neo4j ids are **not** exposed.
- Per-execution timeout via `TransactionConfig.builder().withTimeout(Duration.ofMillis(queryTimeoutMs))`.

### Answer synthesis (LLM call 2)
`AnswerSynthesisService.synthesize(prompt, cypher, results, truncated)`:
- Prompt: the user's original prompt + the executed Cypher + the serialized (capped) results + a note when `truncated=true` ("results were truncated to N rows; answer based on the visible subset").
- Returns plain-text answer via `chatClient.prompt(...).call().content()`.
- Same retry semantics (1 + 2 retries on empty/blank content or exception) → `ChatException` → `422` on exhaustion.

### `ChatService` orchestration
```
validate(prompt)                       // → InvalidPromptException (400) if blank / too long
schema = schemaService.readSchema()     // → GraphEmptyException (422) if None / empty
(cypher, executed) = cypherLoop(schema, prompt)   // generate → executeRead → retry
answer = synthesisLoop(prompt, cypher, executed)  // → retry → 422 on exhaustion
return ChatResponse(answer, cypher, executed.rows(), executed.truncated(), executed.count(), null)
```
Synchronous, single Tomcat thread, no executor pool (unlike ingestion's bounded parallelism).

### Wiring (`AppConfig.java`)
Add explicit `@Bean`s (no component scanning, per repo convention): `SchemaService`, `CypherExecutor`, `CypherGenerationService`, `AnswerSynthesisService`, `ChatService`, `ChatController`. Add a `BeanOutputConverter<CypherGeneration>` bean in `ChatClientConfig`.

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| Blank prompt | `400` `InvalidPromptException` |
| `prompt.length() > max-prompt-length` | `400` |
| No `:Schema` node / zero labels (nothing ingested) | `422` `GraphEmptyException`, no LLM calls |
| LLM emits a write (`CREATE/MERGE/SET/...`) | `executeRead` rejects → error fed back → retry; 422 after 3 attempts |
| LLM emits invalid Cypher syntax | Neo4j error fed back → retry; 422 after 3 attempts |
| LLM references a nonexistent label | Query returns 0 rows → synthesis answers "no information found"; not a failure |
| Results exceed `result-row-limit` | `truncated=true`, `rowCount=limit`, synthesis told results were truncated |
| Cypher execution times out | `query-timeout-ms` → error → retry; 422 after 3 attempts |
| Synthesis LLM call fails (blank/exception) | Retry x2; 422 on exhaustion |
| Neo4j down | `503` `Neo4jUnavailableException` (existing handler) |
| New PDF introduces new label after startup | `:Schema` node updated by ingestion at write time → next chat sees it (no restart needed) |
| `:Document` queried ("what docs are uploaded?") | Works — `Document` is recorded in the schema |
| `:Schema` label leaked into generated Cypher | Harmless — it's a read; not presented in the schema block, so the LLM shouldn't reference it |

## Security Considerations
- **Read-only enforcement is the central security property.** The LLM-generated Cypher is a full query string; APOC parameterization (the ingestion safety mechanism) does not apply. The guard is `session.executeRead(...)`, which the Neo4j server enforces — any write throws inside the transaction. The retry loop treats that throw as a regenerable error, so a "malicious" prompt that coaxes a write simply exhausts retries → 422.
- Endpoint binds to `127.0.0.1` (`server.address`); no auth, unchanged.
- Prompt and results are sent to the external LLM (Z.ai glm-5.2) — accepted by design, consistent with the existing ingestion path.
- Internal Neo4j node/rel ids are never serialized into the response (only display properties).
- A `query-timeout-ms` cap prevents a pathological Cypher from holding resources.

## Backward Compatibility
- **Ingestion pipeline**: `Neo4jGraphWriter.mergeDocument/mergeEntity/mergeRelationship` gain additive schema-node writes. Existing `POST /api/knowledge-graph` behavior, response shape, and the duplicate-skip path are unchanged; only three extra MERGE-on-`:Schema` statements run per write. No schema migration needed (the `:Schema` node is created on first write).
- `AppProperties` gains a new `chat` sub-record; existing `maxPages/chunkTokens/poolSize` keep their defaults and compact-constructor guards. Backward-compatible (new field; `@ConfigurationPropertiesScan` picks it up).
- `ApiExceptionHandler` gains handlers; existing 400/413/503/500 paths unchanged.
- No changes to `CanonicalIndex`, `EntityNormalizer`, `ExtractionRecords`, or the extraction services.

## Alternatives Considered
| Decision | Alternative | Why Not |
|----------|-------------|--------|
| Schema source | yml config / per-request introspection / startup cache | Manual upkeep / stale on new labels / stale until restart — `:Schema` node updated at write time is always current with no manual step |
| Schema content | Property keys + sample values per label | Bigger ingestion change and larger prompt; entity props are predictable, so a fixed hint suffices |
| Read-only guard | Keyword/AST deny-list / dedicated read-only Neo4j user | String parsing is bypassable; separate user needs out-of-app Neo4j role setup. `executeRead` is server-enforced and sufficient |
| Query count | Multiple Cypher / fulltext fallback | Harder to present/validate; no chunk text or embeddings stored, so fallback needs new persistence (out of scope) |
| LLM flow | One-shot (no synthesis call) | Answer written before seeing real data → hallucination risk; two-step grounds the answer in results |
| Retry exhaustion | 200 + error field / 500 | 422 signals "understood but unfulfillable" distinctly from server error; caller still receives the last Cypher |
| Synthesis failure | 200 answer=null / 500 | Symmetry with Cypher-gen retry (x2 then 422); 500 would discard useful retrieved data |
| Empty graph | 200 friendly message / proceed with empty schema | 422 is explicit and avoids wasting two LLM calls |
| Result cap | Append `LIMIT` / byte-truncate / no cap | Appending `LIMIT` breaks UNION/subquery forms; byte-truncation lets wide queries blow up first; read-cap never modifies the query and bounds the payload |

## Open Questions
- None remaining after the interview.

## Task Breakdown (ordered by dependency)
1. Add `app.chat.*` to `application.yml` and the `Chat` sub-record + guards to `AppProperties` (`maxAttempts`, `resultRowLimit`, `maxPromptLength`, `queryTimeoutMs`).
2. Extend `Neo4jGraphWriter`: add `recordSchemaLabel(label)` / `recordSchemaRelType(type)` using `apoc.coll.union` on a single `:Schema {id:"default"}` node; call them from `mergeDocument` (records `"Document"`), `mergeEntity` (records `$label`), `mergeRelationship` (records `$type`). Add `readSchema()` → `Option<SchemaSnapshot>`; never record `:Schema` itself.
3. Add exceptions: `InvalidPromptException` (→400), `GraphEmptyException` (→422), `ChatException` (→422, carries last Cypher + error). Add handlers in `ApiExceptionHandler` (each logs, per repo convention).
4. Add `ChatRecords.CypherGeneration(String cypher)` + `BeanOutputConverter<CypherGeneration>` bean in `ChatClientConfig`.
5. Implement `SchemaService` (reads `:Schema` via writer, returns `SchemaSnapshot` or throws `GraphEmptyException`).
6. Implement `CypherExecutor` (`session.executeRead`, per-exec `TransactionConfig` timeout, read-cap N, `flatten` Node/Rel/Path → flat maps, returns `ExecutedResults(rows,count,truncated)`); wrap Neo4j errors via the existing `wrapNeo4jException` pattern.
7. Implement `CypherGenerationService` (builds schema+hint prompt, calls `ChatClient` with `BeanOutputConverter<CypherGeneration>`, reuses `ExtractionPrompter`-style JSON repair, returns `Try<CypherGeneration>`).
8. Implement `AnswerSynthesisService` (prompt = user prompt + cypher + serialized results + truncated note; plain-text answer; returns `Try<String>`).
9. Implement `ChatService` (`validate` → `schemaService.readSchema` → `foldLeft` over attempts for Cypher gen+execute → `foldLeft` over attempts for synthesis → assemble `ChatResponse`; throw `ChatException` on exhaustion).
10. Implement `ChatController` with `POST /api/knowledge-graph/chat` and `GET /api/knowledge-graph/schema`; declare beans in `AppConfig`.
11. Verify: with a local Neo4j + APOC, ingest `procurement.pdf`, then chat ("What organizations are mentioned?"), confirm answer + Cypher + results; test empty-graph 422, write-attempt retry→422, truncation, and `GET /schema`. Compile with `./gradlew compileJava`.
