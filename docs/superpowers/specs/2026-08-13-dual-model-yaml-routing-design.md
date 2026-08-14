# Dual-Model YAML Routing — Knowledge Graph

**Date:** 2026-08-13
**Goal:** Route extraction (Pass 1/2) and chat to two independent LLM endpoints, each configurable purely from `application.yml`. Enable running a local Ollama model for extraction while keeping GLM 5.2 for chat — and make switching either slot to any provider a one-block YAML edit, no code or build change.

## Background & Problem

Today both code paths share one model:

- `ChatClientConfig.java:39` — `chatClient` (used by `ExtractionPrompter` for entity/relationship extraction).
- `ChatClientConfig.java:44` — `chatChatClient` (used by `ChatService` for chat, with tools + memory).

Both are built from the **same** auto-configured `ChatClient.Builder`, which Spring AI creates from the single `OpenAiChatModel` that `spring-ai-starter-model-openai` auto-configures from `spring.ai.openai.*` (`application.yml:23-34` → `glm-5.2` @ `https://opencode.ai/zen/go/v1`). So extraction and chat both hit GLM 5.2 today.

To run a local 30B model for extraction (Ollama's OpenAI-compatible endpoint at `http://localhost:11434/v1`) while keeping GLM 5.2 for chat, the two `ChatClient`s must be backed by two independent `OpenAiChatModel` instances, each constructed from its own config block.

## Decisions (locked)

1. **Symmetric `app.models.*` config** — two identical-shape slots, `app.models.extract` and `app.models.chat`, each carrying base-url/api-key/model/temperature/timeout/max-retries. Both slots fully portable across Ollama / GLM / OpenRouter via YAML only.
2. **Self-contained slots with per-slot `pool-size`** — `app.pool-size` is removed; extraction's parallelism moves to `app.models.extract.pool-size`. Chat needs no `pool-size` (single synchronous call). Switching extraction to Ollama is a one-block edit (model + pool-size together).
3. **Auto-config disabled via class exclusion** — `@SpringBootApplication(exclude = OpenAiChatAutoConfiguration.class)`. Deterministic; avoids a competing auto `openAiChatModel` bean and an ambiguous auto `ChatClient.Builder`.

## API Findings (Spring AI 2.0)

Verified against the resolved 2.0 jars on this machine:

- The auto-config class is `org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration` (listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` of `spring-ai-autoconfigure-model-openai-2.0.0.jar`). Its bean method is `openAiChatModel(...)` — the source of the shared model.
- `OpenAiChatModel.builder().options(opts).build()` self-constructs its synchronous and async HTTP clients from `opts.getBaseUrl()/getApiKey()/getTimeout()/getMaxRetries()` (`OpenAiChatModel.java:1373-1389`). So a model bean is just options-then-build — no manual `OpenAIOkHttpClient` wiring required.
- `OpenAiChatOptions.Builder` exposes `baseUrl(String)`, `apiKey(String)`, `model(String)`, `temperature(Double)`, `timeout(Duration)`, `maxRetries(Integer)` (confirmed via `javap` on the 2.0 classfiles). All six are settable per-bean.

## Design

### 1. Config — `AppProperties` + `application.yml`

Extend `AppProperties` (`src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java`) with a nested `models` block. **Remove the top-level `poolSize`** (migrated to the extract slot). The inner record is named `ChatModel` to avoid clashing with the existing `Chat` behavior record.

```java
@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, Chat chat, Models models) {

    public record Chat(int resultRowLimit, int maxPromptLength, int maxToolCallRounds,
                       long queryTimeoutMs, int historyWindow) { ... }                // unchanged

    public record Models(Extract extract, ChatModel chat) {
        public record Extract(String baseUrl, String apiKey, String model,
                              Double temperature, Duration timeout, Integer maxRetries,
                              Integer poolSize) { ...defaults... }
        public record ChatModel(String baseUrl, String apiKey, String model,
                                Double temperature, Duration timeout, Integer maxRetries) { ...defaults... }
    }

    public AppProperties {
        if (maxPages <= 0) { maxPages = 50; }
        if (chunkTokens <= 0) { chunkTokens = 1000; }
        if (chat == null) { chat = new Chat(0, 0, 0, 0L, 0); }
        if (models == null) { models = new Models(
            new Extract(null, null, null, 0.2, Duration.ofSeconds(600), 5, 4),
            new ChatModel(null, null, null, 0.2, Duration.ofSeconds(120), 5)); }
    }
}
```

- The chat slot has **no `poolSize`** — chat is a single synchronous call (`ChatService.java:40`); only extraction parallelizes.
- Compact constructors fill sane defaults: `poolSize=4`, `timeout=600s` (extract) / `120s` (chat), `maxRetries=5`, `temperature=0.2`, matching existing guard style (`AppProperties.java:29-42`).

`application.yml` becomes:

```yaml
app:
  max-pages: 100
  chunk-tokens: 3000
  # pool-size removed → app.models.extract.pool-size
  chat:
    result-row-limit: 200
    max-prompt-length: 2000
    max-tool-call-rounds: 10
    query-timeout-ms: 10000
    history-window: 20
  models:
    extract:
      base-url: http://localhost:11434/v1     # Ollama OpenAI-compatible endpoint
      api-key: ${EXTRACT_API_KEY:ollama}      # ignored by Ollama; set for hosted providers
      model: nemotron-3.5-lightning:30b-mlx
      temperature: 0.1
      timeout: 600s
      max-retries: 5
      pool-size: 2                            # local 30B can't run 8 in parallel
    chat:
      base-url: https://opencode.ai/zen/go/v1
      api-key: ${OPENAI_API_KEY}
      model: glm-5.2
      temperature: 0.2
      timeout: 120s
      max-retries: 5

spring:
  # entire spring.ai.* block deleted (auto chat model disabled via exclude)
  servlet: { multipart: { max-file-size: 10MB, max-request-size: 10MB } }
  datasource: { url: jdbc:h2:file:./data/chatdb, driver-class-name: org.h2.Driver }
  sql: { init: { mode: always } }
  neo4j:
    uri: bolt://localhost:7687
    authentication: { username: neo4j, password: ${NEO4J_PASSWORD:neo4j-admin} }
```

**Switching any slot to Ollama / GLM / OpenRouter = editing that one `app.models.<slot>` block.** `OPENAI_API_KEY` is reused for chat (no infra change); `EXTRACT_API_KEY` defaults to `ollama` for the local case.

### 2. Two model beans + two rebuilt `ChatClient`s — `ChatClientConfig`

Each model bean: `OpenAiChatModel.builder().options(opts).build()` self-constructs its HTTP client from the options (`OpenAiChatModel.java:1373-1389`), so no manual client wiring is needed.

```java
@Bean
OpenAiChatModel extractChatModel(AppProperties p) {
    val ex = p.models().extract();
    return OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                    .baseUrl(ex.baseUrl()).apiKey(ex.apiKey()).model(ex.model())
                    .temperature(ex.temperature()).timeout(ex.timeout()).maxRetries(ex.maxRetries())
                    .build())
            .build();
}

@Bean
OpenAiChatModel chatChatModel(AppProperties p) {
    val ch = p.models().chat();
    return OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                    .baseUrl(ch.baseUrl()).apiKey(ch.apiKey()).model(ch.model())
                    .temperature(ch.temperature()).timeout(ch.timeout()).maxRetries(ch.maxRetries())
                    .build())
            .build();
}
```

The **existing** `chatClient` / `chatChatClient` beans are rebuilt from their own model via `ChatClient.builder(<model>)…` instead of the shared auto `ChatClient.Builder`:

```java
@Bean
ChatClient chatClient(OpenAiChatModel extractChatModel) {
    return ChatClient.builder(extractChatModel).build();
}

@Bean
ChatClient chatChatClient(OpenAiChatModel chatChatModel, ToolCallback skillsTool,
                          GraphTools graphTools, ChatMemory chatMemory) {
    return ChatClient.builder(chatChatModel)
            .defaultSystem(SYSTEM_PROMPT)
            .defaultTools(skillsTool, graphTools)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
}
```

- `chatChatClient` keeps `defaultSystem` + `defaultTools` + `MessageChatMemoryAdvisor` exactly as today (`ChatClientConfig.java:44-49`), only swapped to take a `ChatModel` rather than an auto `ChatClient.Builder`.
- **Bean names stay `chatClient` and `chatChatClient`** → `ExtractionPrompter` (`ExtractionPrompter.java:19`) and `ChatService` (`ChatService.java:21`) injection sites are untouched.
- The shared `ChatClient.Builder` parameter is dropped from both bean method signatures.

The `skillsTool()` and `extractionOutputConverter()` beans in `ChatClientConfig` are unchanged.

### 3. Pool-size migration

`EntityExtractionService.java:54` and `RelationshipExtractionService.java:56` change:

```java
this.executor = Executors.newFixedThreadPool(appProperties.poolSize());
```
to:
```java
this.executor = Executors.newFixedThreadPool(appProperties.models().extract().poolSize());
```

The `int poolSize` field, its compact-ctor guard (`AppProperties.java:36-38`), and the `app.pool-size` YAML key are removed.

### 4. Disable auto-config

`KnowledgeGraphApplication`:

```java
@SpringBootApplication(exclude = OpenAiChatAutoConfiguration.class)
```

with import `org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration`. This guarantees our two `OpenAiChatModel` beans are the only `ChatModel`s, and no ambiguous auto `ChatClient.Builder` loads. `@ConfigurationPropertiesScan` stays so `AppProperties` is still picked up.

### 5. Unchanged

- `ExtractionPrompter` (retry + JSON-repair logic) — only the underlying client it calls changes.
- `ChatService`, `ChatController`, `GraphController`, `AppConfig` wiring, prompts, tools, memory advisor, tool-round capping.
- The graph pipeline, Neo4j writer, APOC merge calls, `CanonicalIndex`, `EntityNormalizer`.
- Error handling (`ApiExceptionHandler`) — same exception types, HTTP paths.
- `Neo4jConfig`, `ChatMemoryConfig`.

### 6. Trade-offs / Gotchas

- **Local structured-JSON quality.** A 30B MLX model is weaker at strict JSON than GLM 5.2 → expect more `JsonRepair`/retry logs (`ExtractionPrompter.java:44-49`) and higher `failedChunks`. Keep `temperature ≤ 0.1` for extraction.
- **Latency.** Extract keeps `timeout: 600s` (already covers slow local inference). Chat lowered to `120s` — the tool layer already caps the read path via `app.chat.query-timeout-ms: 10000`.
- **Parallelism.** `app.models.extract.pool-size: 2` for a local 30B; also set Ollama's `OLLAMA_NUM_PARALLEL` accordingly if running multiple ingestion jobs.
- **Re-upload skip.** The `:Document {hash}` skip still applies — re-testing extraction against the same PDF requires deleting that node or changing bytes (unchanged gotcha).
- **Two `OpenAiChatModel` beans** means you cannot `@Autowired` a bare `ChatModel` elsewhere without a qualifier — no code does that today, so fine. If added later, use `@Qualifier("extractChatModel")` / `@Qualifier("chatChatModel")`.

## Verification

Only the compile checker is available (per `AGENTS.md`), but cover both static and manual:

1. **Compile:** `./gradlew compileJava` passes.
2. **Manual — Ollama extraction + GLM chat:**
   - `ollama pull nemotron-3.5-lightning:30b-mlx` and confirm `ollama show nemotron-3.5-lightning:30b-mlx`.
   - Confirm endpoint: `curl http://localhost:11434/v1/models`.
   - `./gradlew bootRun` (Neo4j + APOC running locally).
   - `POST /api/knowledge-graph` with `procurement.pdf` → `GraphSummaryResponse`. Confirm Pass 1/2 hit the local model (latency / JSON-repair logs) and `failedChunks` isn't excessive.
   - `POST /api/knowledge-graph/chat` → confirm chat still hits GLM 5.2 (tool rounds execute, answer quality retained).
3. **YAML-only round-trip:** edit `app.models.extract` back to the GLM endpoint (`base-url: https://opencode.ai/zen/go/v1`, `model: glm-5.2`, `api-key: ${OPENAI_API_KEY}`, `pool-size: 8`) and re-run ingestion → confirm extraction switches with **no code change, no rebuild-logic change**, purely via YAML.

## Files Touched

| File | Change |
|---|---|
| `src/main/resources/application.yml` | Delete `spring.ai.*`; add `app.models.{extract,chat}` blocks; remove `app.pool-size`. |
| `src/main/java/.../config/AppProperties.java` | Add `Models` record (with `Extract` + `ChatModel` sub-records); remove `int poolSize` field + guard; add `models` compact-ctor default. |
| `src/main/java/.../config/ChatClientConfig.java` | Add `extractChatModel` + `chatChatModel` `OpenAiChatModel` beans; rebuild `chatClient`/`chatChatClient` from their model; drop shared `ChatClient.Builder` param. |
| `src/main/java/.../extract/EntityExtractionService.java` | `poolSize()` → `models().extract().poolSize()` (line 54). |
| `src/main/java/.../extract/RelationshipExtractionService.java` | `poolSize()` → `models().extract().poolSize()` (line 56). |
| `src/main/java/.../KnowledgeGraphApplication.java` | `@SpringBootApplication(exclude = OpenAiChatAutoConfiguration.class)` + import. |