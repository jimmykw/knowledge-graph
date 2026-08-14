# Dual-Model YAML Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route extraction (Pass 1/2) and chat to two independent OpenAI-compatible LLM endpoints, each configurable purely from `application.yml`, so a local Ollama model can run extraction while GLM 5.2 stays on chat.

**Architecture:** Replace Spring AI's single auto-configured `OpenAiChatModel` (which backs both `ChatClient`s today) with two manual `OpenAiChatModel` beans built from a symmetric `app.models.{extract,chat}` config block. Rebuild the existing `chatClient` / `chatChatClient` `ChatClient` beans from their own model bean. Disable `OpenAiChatAutoConfiguration` via `@SpringBootApplication(exclude=...)`. Migrate `app.pool-size` into `app.models.extract.pool-size`.

**Tech Stack:** Spring Boot 4.0.7, Spring AI 2.0.0 (`spring-ai-starter-model-openai`), Vavr, Lombok, Java 21, Gradle Kotlin DSL.

## Global Constraints

These apply to every task; copy exact values verbatim.

- **No tests exist** in this repo (per `AGENTS.md`): no `src/test`, no test/lint/typecheck tasks. The ONLY build verification is `./gradlew compileJava`. Do NOT create test files or a test framework — use compile + the manual functional check in Task 4 as the verification cycle.
- **Do not commit** unless a task's "Commit" step explicitly says so. Never commit on partial states.
- **Java style** (from `AGENTS.md` + `~/.claude/rules/java-code-style.md`): Lombok `val` for all locals (never `var`/explicit types); `@UtilityClass` + explicit `static` on public methods where applicable; records for immutable data; `@RequiredArgsConstructor` + `private final` for service deps; keep lines ≤ 150 chars; break at method-chain boundaries, NOT inside argument lists; NO comments unless asked.
- **Vavr everywhere** for control flow/collections — do not introduce `java.util.Optional`/`java.util.stream` in the pipeline.
- **Bean names** `chatClient` and `chatChatClient` MUST stay unchanged — `ExtractionPrompter.java:19` and `ChatService.java:21` inject them by name.
- **Auto-config FQCN** to exclude: `org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration` (confirmed on the 2.0.0 classpath).
- **`OpenAiChatModel.builder().options(opts).build()`** self-constructs its HTTP client from the options (`baseUrl`/`apiKey`/`timeout`/`maxRetries`); no manual client wiring.
- **`OpenAiChatOptions.Builder`** setters used: `baseUrl(String)`, `apiKey(String)`, `model(String)`, `temperature(Double)`, `timeout(Duration)`, `maxRetries(Integer)`.
- The working tree may have an uncommitted `application.yml` WIP edit (toggle of openrouter/opencode base-url comment). Task 4 rewrites `application.yml` entirely and supersedes that edit — that's expected, do not preserve the WIP toggle.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java` | Bind `app.*` config | Add `Models` record (with `Extract` + `ChatModel` sub-records); (Task 3) remove `int poolSize` |
| `src/main/java/net/jimmykw/knowledgegraph/config/ChatClientConfig.java` | Declare `ChatClient` (and now `OpenAiChatModel`) beans | Add two manual model beans; rebuild `chatClient`/`chatChatClient` from them |
| `src/main/java/net/jimmykw/knowledgegraph/extract/EntityExtractionService.java` | Pass 1 extraction | Pool source → `models().extract().poolSize()` |
| `src/main/java/net/jimmykw/knowledgegraph/extract/RelationshipExtractionService.java` | Pass 2 extraction | Pool source → `models().extract().poolSize()` |
| `src/main/java/net/jimmykw/knowledgegraph/KnowledgeGraphApplication.java` | Boot entrypoint | `exclude = OpenAiChatAutoConfiguration.class` |
| `src/main/resources/application.yml` | Runtime config | (Task 3) drop `app.pool-size`; (Task 4) delete `spring.ai.*`, add `app.models.*` |

Task dependency order is fixed: Tasks 1→4. Each task ends with a green `./gradlew compileJava`.

---

### Task 1: Add `Models` block to `AppProperties`

Add the symmetric `app.models.extract` / `app.models.chat` config schema. Keep top-level `poolSize` for now (extraction services still read it; the migration is Task 3) so this task compiles standalone.

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java` (full rewrite — adds `java.time.Duration` import, `Models` nested record, `models` record param, compact-ctor default)

**Interfaces:**
- Produces: `AppProperties.models()` → `Models`; `Models.extract()` → `Models.Extract` with `baseUrl()/apiKey()/model()/temperature()/timeout()/maxRetries()/poolSize()`; `Models.chat()` → `Models.ChatModel` with `baseUrl()/apiKey()/model()/temperature()/timeout()/maxRetries()`. Task 2 consumes `models().extract()`/`.chat()`; Task 3 consumes `models().extract().poolSize()`.

- [ ] **Step 1: Replace `AppProperties.java` with the full new content**

```java
package net.jimmykw.knowledgegraph.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, int poolSize, Chat chat, Models models) {

    public record Chat(int resultRowLimit, int maxPromptLength, int maxToolCallRounds, long queryTimeoutMs,
                       int historyWindow) {
        public Chat {
            if (resultRowLimit <= 0) {
                resultRowLimit = 50;
            }
            if (maxPromptLength <= 0) {
                maxPromptLength = 2000;
            }
            if (maxToolCallRounds <= 0) {
                maxToolCallRounds = 10;
            }
            if (queryTimeoutMs <= 0) {
                queryTimeoutMs = 10000;
            }
            if (historyWindow <= 0) {
                historyWindow = 20;
            }
        }
    }

    public record Models(Extract extract, ChatModel chat) {

        public record Extract(String baseUrl, String apiKey, String model,
                              Double temperature, Duration timeout, Integer maxRetries, Integer poolSize) {
            public Extract {
                if (temperature == null) {
                    temperature = 0.2;
                }
                if (timeout == null) {
                    timeout = Duration.ofSeconds(600);
                }
                if (maxRetries == null || maxRetries < 0) {
                    maxRetries = 5;
                }
                if (poolSize == null || poolSize <= 0) {
                    poolSize = 4;
                }
            }
        }

        public record ChatModel(String baseUrl, String apiKey, String model,
                                Double temperature, Duration timeout, Integer maxRetries) {
            public ChatModel {
                if (temperature == null) {
                    temperature = 0.2;
                }
                if (timeout == null) {
                    timeout = Duration.ofSeconds(120);
                }
                if (maxRetries == null || maxRetries < 0) {
                    maxRetries = 5;
                }
            }
        }

        public Models {
            if (extract == null) {
                extract = new Extract(null, null, null, null, null, null, null);
            }
            if (chat == null) {
                chat = new ChatModel(null, null, null, null, null, null);
            }
        }
    }

    public AppProperties {
        if (maxPages <= 0) {
            maxPages = 50;
        }
        if (chunkTokens <= 0) {
            chunkTokens = 1000;
        }
        if (poolSize <= 0) {
            poolSize = 4;
        }
        if (chat == null) {
            chat = new Chat(0, 0, 0, 0L, 0);
        }
        if (models == null) {
            models = new Models(null, null);
        }
    }
}
```

- [ ] **Step 2: Compile-verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (nothing yet reads `models()`; auto `ChatClient.Builder` still loads, so `ChatClientConfig` compiles unchanged).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java
git commit -m "Add app.models.{extract,chat} config schema to AppProperties"
```

---

### Task 2: Build two manual `OpenAiChatModel` beans and rebuild the `ChatClient`s

Add `extractChatModel` and `chatChatModel` `OpenAiChatModel` beans constructed from `app.models.*`, then rebuild the existing `chatClient` / `chatChatClient` `ChatClient` beans from their own model bean. Bean names and the chat client's system prompt + tools + memory advisor stay identical so `ExtractionPrompter`/`ChatService` injection is untouched. The auto `OpenAiChatModel`/`ChatClient.Builder` are still present this task (now unused); they get disabled in Task 4.

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/config/ChatClientConfig.java` (full rewrite)

**Interfaces:**
- Consumes: `AppProperties.models().extract()` / `.chat()` (from Task 1); existing `ToolCallback skillsTool`, `GraphTools graphTools`, `ChatMemory chatMemory`.
- Produces: beans named `extractChatModel`, `chatChatModel` (type `OpenAiChatModel`); beans `chatClient`, `chatChatClient` rebuilt from them — same names/signatures as before.

- [ ] **Step 1: Replace `ChatClientConfig.java` with the full new content**

```java
package net.jimmykw.knowledgegraph.config;

import org.springaicommunity.agent.tools.SkillsTool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import net.jimmykw.knowledgegraph.chat.GraphTools;
import net.jimmykw.knowledgegraph.config.AppProperties.Models.ChatModel;
import net.jimmykw.knowledgegraph.config.AppProperties.Models.Extract;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;

import lombok.val;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are a question-answering assistant backed by a Neo4j knowledge graph. Always
            ground your answer in data retrieved from the graph tools — do not invent
            entities, labels, relationships, or facts the tools did not return. If a tool
            returns an error (e.g. Neo4j is unavailable), include it in your answer so the
            user understands the gap.

            Available tools:
            - getGraphSchema: returns the node labels and relationship types that exist. Call
              this first when you are unsure what is in the graph.
            - runReadCypher: runs a read-only Cypher query and returns JSON rows.
            - skills: loads Markdown skills that teach Cypher patterns and answer shapes for
              common question types (entity lookup, neighborhood exploration, path finding,
              source attribution, graph statistics, empty-result recovery, multi-hop
              reasoning). Invoke the skill whose description matches the user's question
              before writing Cypher.
            """;

    @Bean
    OpenAiChatModel extractChatModel(AppProperties appProperties) {
        val ex = appProperties.models().extract();
        return OpenAiChatModel.builder()
                .options(buildOptions(ex.baseUrl(), ex.apiKey(), ex.model(),
                        ex.temperature(), ex.timeout(), ex.maxRetries()))
                .build();
    }

    @Bean
    OpenAiChatModel chatChatModel(AppProperties appProperties) {
        val ch = appProperties.models().chat();
        return OpenAiChatModel.builder()
                .options(buildOptions(ch.baseUrl(), ch.apiKey(), ch.model(),
                        ch.temperature(), ch.timeout(), ch.maxRetries()))
                .build();
    }

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

    @Bean
    ToolCallback skillsTool() {
        return SkillsTool.builder()
                .addSkillsResource(new ClassPathResource(".claude/skills"))
                .build();
    }

    @Bean
    BeanOutputConverter<ExtractionResult> extractionOutputConverter() {
        return new BeanOutputConverter<>(ExtractionResult.class);
    }

    private static OpenAiChatOptions buildOptions(String baseUrl, String apiKey, String model,
                                                  Double temperature, java.time.Duration timeout,
                                                  Integer maxRetries) {
        return OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(temperature)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }
}
```

- [ ] **Step 2: Compile-verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. The auto `OpenAiChatModel` and auto `ChatClient.Builder` are still registered (now unused) — no conflict because nothing injects a bare `ChatModel` or `ChatClient.Builder` anymore.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/config/ChatClientConfig.java
git commit -m "Build dual OpenAiChatModel beans and rebuild ChatClients from them"
```

---

### Task 3: Migrate extraction pool-size to `app.models.extract.pool-size`

Switch both extraction services' thread-pool source to `appProperties.models().extract().poolSize()`, then remove the now-unused top-level `poolSize` from `AppProperties` and drop `app.pool-size` from YAML.

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/extract/EntityExtractionService.java:54`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/extract/RelationshipExtractionService.java:56`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java` (remove `int poolSize` record param + its guard)
- Modify: `src/main/resources/application.yml:4` (remove `app.pool-size`)

**Interfaces:**
- Consumes: `AppProperties.models().extract().poolSize()` (from Task 1).
- Produces: none (internal change).

- [ ] **Step 1: Update `EntityExtractionService.java` pool source**

In `src/main/java/net/jimmykw/knowledgegraph/extract/EntityExtractionService.java`, change line 54.

Old:
```java
        this.executor = Executors.newFixedThreadPool(appProperties.poolSize());
```
New:
```java
        this.executor = Executors.newFixedThreadPool(appProperties.models().extract().poolSize());
```

- [ ] **Step 2: Update `RelationshipExtractionService.java` pool source**

In `src/main/java/net/jimmykw/knowledgegraph/extract/RelationshipExtractionService.java`, change line 56.

Old:
```java
        this.executor = Executors.newFixedThreadPool(appProperties.poolSize());
```
New:
```java
        this.executor = Executors.newFixedThreadPool(appProperties.models().extract().poolSize());
```

- [ ] **Step 3: Remove `poolSize` from `AppProperties`**

Change the record signature (remove `int poolSize` as the 3rd param) and remove its compact-ctor guard. The `poolSize` record param was: `public record AppProperties(int maxPages, int chunkTokens, int poolSize, Chat chat, Models models) {` → becomes `public record AppProperties(int maxPages, int chunkTokens, Chat chat, Models models) {`. Delete these three lines from the compact constructor body:

```java
        if (poolSize <= 0) {
            poolSize = 4;
        }
```

The rest of `AppProperties.java` (the `Chat`, `Models`, `Extract`, `ChatModel` records and the remaining `maxPages`/`chunkTokens`/`chat`/`models` guards) stays as set in Task 1.

- [ ] **Step 4: Remove `app.pool-size` from `application.yml`**

Delete line 4 of `src/main/resources/application.yml` (`  pool-size: 8`) and its preceding line 3 is `  chunk-tokens: 3000` which stays. After this edit the top of `app:` should be:

```yaml
app:
  max-pages: 100
  chunk-tokens: 3000
  chat:
```

(Do NOT add the `app.models.*` block yet — that's Task 4. With no `app.models.*` keys present, `AppProperties` falls back to its compact-ctor defaults from Task 1, so the app still binds and runs against placeholder endpoints. `spring.ai.openai.*` is still active this task, so chat/extraction keep working via the auto model... except Task 2 stopped using it. Note: until Task 4 lands, extraction/chat hit whatever the `AppProperties` defaults resolve to, which are null `baseUrl`/`apiKey`/`model` — do NOT run the app between Task 3 and Task 4. Compile-only is fine.)

- [ ] **Step 5: Compile-verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. No references to `appProperties.poolSize()` remain (grep to confirm — see Step 6).

- [ ] **Step 6: Verify no stale top-level `poolSize()` references**

Run: `rg -n "appProperties\.poolSize\(\)" src/main/java`
Expected: NO matches. (The two new `models().extract().poolSize()` calls don't match this pattern, so a clean result confirms the migration is complete.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/extract/EntityExtractionService.java \
        src/main/java/net/jimmykw/knowledgegraph/extract/RelationshipExtractionService.java \
        src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java \
        src/main/resources/application.yml
git commit -m "Migrate extraction pool-size to app.models.extract.pool-size"
```

---

### Task 4: Disable OpenAI auto-config and finalize `application.yml`

Exclude `OpenAiChatAutoConfiguration` so only the two manual model beans exist, then replace `spring.ai.openai.*` with the real `app.models.{extract,chat}` block (Ollama for extraction, GLM 5.2 for chat). This is the task that makes the app runnable in the target dual-model setup.

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/KnowledgeGraphApplication.java`
- Modify: `src/main/resources/application.yml` (full rewrite of the `spring:` + `app:` sections)

**Interfaces:**
- Consumes: the two `OpenAiChatModel` beans (Task 2) and `AppProperties.models()` (Tasks 1 & 3).

- [ ] **Step 1: Exclude auto-config on `KnowledgeGraphApplication.java`**

Old:
```java
package net.jimmykw.knowledgegraph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeGraphApplication.class, args);
    }
}
```
New:
```java
package net.jimmykw.knowledgegraph;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = OpenAiChatAutoConfiguration.class)
@ConfigurationPropertiesScan
public class KnowledgeGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeGraphApplication.class, args);
    }
}
```

- [ ] **Step 2: Rewrite `application.yml`**

Replace the entire contents of `src/main/resources/application.yml` with:

```yaml
app:
  max-pages: 100
  chunk-tokens: 3000
  chat:
    result-row-limit: 200
    max-prompt-length: 2000
    max-tool-call-rounds: 10
    query-timeout-ms: 10000
    history-window: 20
  models:
    extract:
      base-url: http://localhost:11434/v1
      api-key: ${EXTRACT_API_KEY:ollama}
      model: nemotron-3.5-lightning:30b-mlx
      temperature: 0.1
      timeout: 600s
      max-retries: 5
      pool-size: 2
    chat:
      base-url: https://opencode.ai/zen/go/v1
      api-key: ${OPENAI_API_KEY}
      model: glm-5.2
      temperature: 0.2
      timeout: 120s
      max-retries: 5

spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
  datasource:
    url: jdbc:h2:file:./data/chatdb
    driver-class-name: org.h2.Driver
  sql:
    init:
      mode: always
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: ${NEO4J_PASSWORD:neo4j-admin}

server:
  address: 127.0.0.1
  port: 8080

logging:
  level:
    net.jimmykw.knowledgegraph: DEBUG
```

Notes:
- The entire `spring.ai.*` block is gone. `OPENAI_API_KEY` is reused for chat; `EXTRACT_API_KEY` defaults to `ollama` (ignored by Ollama, set it for hosted providers).
- The `logging.level.net.jimmykw.knowledgegraph` key is preserved exactly as in the original `application.yml` (the real package root).

- [ ] **Step 3: Compile-verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/KnowledgeGraphApplication.java \
        src/main/resources/application.yml
git commit -m "Disable OpenAI auto-config and route models via app.models.*"
```

- [ ] **Step 5 (manual): Functional verification — Ollama extraction + GLM chat**

Run:
```
ollama pull nemotron-3.5-lightning:30b-mlx
ollama show  nemotron-3.5-lightning:30b-mlx
curl http://localhost:11434/v1/models
```
Then with Neo4j (+ APOC) running locally:
```
./gradlew bootRun
```
- Ingest: `POST /api/knowledge-graph` with `procurement.pdf` (IntelliJ HTTP client sample in `.idea/httpRequests/` or `AGENTS.md`). Expect a `GraphSummaryResponse` with nonzero `nodeCount`/`relationshipCount`. In the logs, Pass 1/Pass 2 "LLM calls completed in {}ms" should show local-model latency (seconds, not the GLM sub-second), and you may see `LLM response required JSON repair` warnings — acceptable for a 30B local model. If `failedChunks` equals the total chunk count, the model isn't producing parseable JSON: lower `app.models.extract.temperature` to `0.0`, or switch to a stronger local/hosted model via YAML only.
- Chat: `POST /api/knowledge-graph/chat` with `{ "prompt": "What entities are in the graph?" }`. Confirm tool rounds execute (`getGraphSchema`/`runReadCypher` in the trace) and the answer is grounded — this proves chat still hits GLM 5.2.
- Re-upload gotcha: re-POSTing the same PDF returns `SKIPPED_DUPLICATE` with zeroed counts (no LLM calls). To re-test, delete the `:Document {hash}` node in Neo4j (or change the file bytes) — unchanged behavior, just confirming it still applies.

- [ ] **Step 6 (manual): YAML-only round-trip**

Edit ONLY `src/main/resources/application.yml` `app.models.extract` to point back at GLM (no code change):
```yaml
    extract:
      base-url: https://opencode.ai/zen/go/v1
      api-key: ${OPENAI_API_KEY}
      model: glm-5.2
      temperature: 0.2
      timeout: 600s
      max-retries: 5
      pool-size: 8
```
Re-run `./gradlew bootRun` and re-ingest (after deleting the `:Document {hash}` node). Confirm Pass 1/2 now hit GLM (sub-second latency, far fewer/no JSON-repair warnings). This proves switching a slot is a YAML-only operation. Revert the YAML back to the Ollama block (or leave GLM — your call) before finishing.

---

## Self-Review

**1. Spec coverage** — vs `docs/superpowers/specs/2026-08-13-dual-model-yaml-routing-design.md`:
- §1 Config (`AppProperties` + `application.yml`) → Tasks 1, 3 (AppProperties poolSize removal), 4 (YAML). ✓
- §2 Two model beans + rebuilt `ChatClient`s → Task 2. ✓
- §3 Pool-size migration (2 services + AppProperties + YAML) → Task 3. ✓
- §4 Disable auto-config (`KnowledgeGraphApplication`) → Task 4. ✓
- §5 Unchanged (ExtractionPrompter/ChatService/etc.) → intentionally untouched, no task needed. ✓
- §6 Trade-offs/gotchas → captured in Task 4 manual verification notes (JSON-repair, re-upload skip, parallelism). ✓
- §7 Verification (compile + manual + YAML round-trip) → Tasks 1–4 compile steps + Task 4 Steps 5–6. ✓

**2. Placeholder scan** — No TBD/TODO/"implement later". Every code step has full code. The `application.yml` rewrite (Task 4 Step 2) is a complete file, not a sketch. ✓

**3. Type consistency** —
- `AppProperties.models()` returns `Models`; `Models.extract()` returns `Models.Extract`; `Models.chat()` returns `Models.ChatModel`. Used consistently in Task 2 (`chatChatModel` reads `models().chat()` → `ChatModel` with 6 fields, no `poolSize`) and Task 3 (`models().extract().poolSize()` → `Extract` has `poolSize`). ✓
- `BuildOptions` helper takes `(String, String, String, Double, Duration, Integer)` — matches both records' fields (`Extract` extra `poolSize` is not passed to options, correct since `OpenAiChatOptions` has no pool-size setter). ✓
- Bean names `chatClient`/`chatChatClient` preserved (Task 2) → match `ExtractionPrompter.java:19` / `ChatService.java:21` inject-by-name. ✓
- `OpenAiChatModel` bean names `extractChatModel`/`chatChatModel` injected into the `ChatClient` beans by parameter name (Spring matches param name to bean name) — `chatClient(OpenAiChatModel extractChatModel)` and `chatChatClient(OpenAiChatModel chatChatModel, ...)`. ✓
- `Duration` import: Task 1 adds `java.time.Duration` to AppProperties; Task 2's `buildOptions` uses fully-qualified `java.time.Duration` (no import needed) — consistent. ✓
- Task 3 removes `int poolSize` from the record param list shifting `Chat` to position 3 and `Models` to position 4 — but `@ConfigurationProperties` binding is by property name (`app.chat.*`, `app.models.*`), not constructor position, so the binding is unaffected. The compact-ctor guard for `poolSize` is removed; remaining guards (`maxPages`/`chunkTokens`/`chat`/`models`) order-independent. ✓

No issues found.