# Multi-Turn Chat with History — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add conversation memory to the existing `POST /api/knowledge-graph/chat` endpoint so multi-turn follow-up questions work, backed by an embedded H2 database, using Spring AI 2.0's `MessageChatMemoryAdvisor` + `MessageWindowChatMemory`.

**Architecture:** Spring AI's `MessageChatMemoryAdvisor` (wired as a default advisor on `chatChatClient`) loads and persists conversation history automatically. An H2-backed `ChatMemoryRepository` (`H2ChatMemoryRepository`, via `JdbcTemplate`) supplies storage; `MessageWindowChatMemory` supplies the sliding window (default 20). The client passes an optional `conversationId` in the request body; the service resolves/generates it and passes it to the advisor via the request context (`ChatMemory.CONVERSATION_ID`). A `DELETE` endpoint clears a conversation. The existing manual `ToolCallingAdvisor` gets `.conversationHistoryEnabled(false)` so the memory advisor owns cross-request history (framework coordination, verified in 2.0.0 source).

> **CORRECTION (2026-08-01):** The last sentence above (and every later mention of `.conversationHistoryEnabled(false)`, e.g. Task 5) is wrong and was reverted. The framework disables the tool advisor's internal history only when a memory advisor is **downstream** of it (`a.getOrder() > configuredOrder`); here the memory advisor is at the default +200 order, *upstream* of the tool advisor (+300), so the flag must stay at its default `true`. With `false`, every tool round after the first was rebuilt as `[system, lastToolResponse]`, dropping the user question and prior tool calls — the model stopped querying entirely. `ChatService` ships without the flag. See the correction note in the spec's Key invariants for the full mechanism.

**Tech Stack:** Java 21, Spring Boot 4.0.7, Spring AI 2.0.0, spring-boot-starter-jdbc + H2, Vavr (`Try`/`Option`), Lombok (`val`/`@Slf4j`/`@RequiredArgsConstructor`). Package root: `net.jimmykw.knowledgegraph`.

## Global Constraints

Copied verbatim from the approved spec (`docs/superpowers/specs/2026-07-31-multi-turn-chat-history-design.md`):

- **No test scaffolding.** The repo has no `src/test`; the only verification is `./gradlew compileJava`. Do NOT add tests. Each task verifies with `./gradlew compileJava` (quiet: `./gradlew compileJava --quiet`).
- **Explicit bean wiring.** No `@Component`/`@Service`/`@Repository` scanning on classes — beans declared as `@Bean` methods in `@Configuration` classes (`AppConfig`, `ChatClientConfig`, and the new `ChatMemoryConfig`).
- **Vavr everywhere** for control flow: `Try.of(...)` for fallible ops, `Option.of(x)` for nullables. No `java.util.Optional`/`java.util.stream` in the pipeline. Convert to Java collections only at boundaries.
- **Lombok `val`** for all locals; `@RequiredArgsConstructor` + `private final` for service deps.
- **Lines ≤ 150 chars**; break at method-chain boundaries, not inside argument lists.
- **No comments** in code unless asked.
- **Never commit unless a task step explicitly says to commit.** Commit only the files a task touches.
- **Package layout:** new Java under `src/main/java/net/jimmykw/knowledgegraph/` — `chat/store/H2ChatMemoryRepository.java`, `config/ChatMemoryConfig.java`.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `build.gradle.kts` | Add `spring-boot-starter-jdbc` + H2 runtime dep. | 1 |
| `src/main/resources/application.yml` | H2 datasource, `spring.sql.init.mode`, `app.chat.history-window`. | 1 |
| `src/main/resources/schema.sql` (NEW) | `chat_messages` table + index DDL. | 1 |
| `.gitignore` | Ignore `data/` (H2 file DB). | 1 |
| `.../config/AppProperties.java` | Add `historyWindow` to `Chat` record (+ fallback). | 1 |
| `.../chat/store/H2ChatMemoryRepository.java` (NEW) | Implements `ChatMemoryRepository` via `JdbcTemplate`; resilient (Try-wrapped). | 2 |
| `.../config/ChatMemoryConfig.java` (NEW) | `@Bean ChatMemoryRepository` + `@Bean ChatMemory` (`MessageWindowChatMemory`). | 2 |
| `.../config/ChatClientConfig.java` | Inject `ChatMemory`; add `MessageChatMemoryAdvisor` as default advisor on `chatChatClient`. | 3 |
| `.../chat/ChatRequest.java` | Add optional `String conversationId`. | 4 |
| `.../chat/ChatResponse.java` | Add `String conversationId` as LAST component. | 4 |
| `.../api/ApiExceptionHandler.java` | Update 2 `ChatResponse(...)` call sites for the new arg. | 4 |
| `.../chat/ChatService.java` | Resolve `conversationId`, pass to advisor, `conversationHistoryEnabled(false)`, `clearConversation`, `buildResponse(... id)`. | 5 |
| `.../config/AppConfig.java` | Update `chatService` bean to inject `ChatMemory`. | 5 |
| `.../chat/ChatController.java` | Pass `conversationId` to `chat()`; add `DELETE` handler (204/404). | 6 |

---

## Task 1: Wire H2 + the history-window property

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/schema.sql`
- Modify: `.gitignore`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AppProperties.Chat.historyWindow()` (int getter) used by Task 2's `MessageWindowChatMemory` build; H2 `chat_messages` table for Task 2's repository; `spring-boot-starter-jdbc` `JdbcTemplate` bean auto-configured by Spring Boot for Task 2.

- [ ] **Step 1: Add dependencies to `build.gradle.kts`**

In the `dependencies { ... }` block, add the two lines shown (place the `implementation` line right after the existing `spring-boot-starter-web` line, and the `runtimeOnly` line right after the `neo4j-java-driver` line):

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation(platform("org.springaicommunity:spring-ai-agent-utils-bom:0.10.0"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-pdf-document-reader")
    implementation("org.neo4j.driver:neo4j-java-driver")
    runtimeOnly("com.h2database:h2")
    implementation("io.vavr:vavr:1.0.1")
    implementation("org.springaicommunity:spring-ai-agent-utils")

    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok:1.18.46")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.46")
}
```

- [ ] **Step 2: Add H2 datasource + SQL init + history-window to `application.yml`**

Final `application.yml` (the chat block gains `history-window`, and a `spring.datasource` + `spring.sql.init` block is added under the existing `spring:` anchor):

```yaml
app:
  max-pages: 100
  chunk-tokens: 3000
  pool-size: 8
  chat:
    result-row-limit: 200
    max-prompt-length: 2000
    max-tool-call-rounds: 10
    query-timeout-ms: 10000
    history-window: 20

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
  ai:
    model:
      chat: openai
    openai:
      base-url: https://opencode.ai/zen/go/v1
      api-key: ${OPENAI_API_KEY:sk-sfPo9bafX4pOKwIu1hVQQz9w0FDrxXDdhEjIeRTsMvtF7DcR2qd3XhwsxHxUqSGe}
      timeout: 240s
      max-retries: 5
      chat:
        model: glm-5.2
        temperature: 0.2
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

- [ ] **Step 3: Create `src/main/resources/schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36)  NOT NULL,
    seq             INTEGER     NOT NULL,
    message_type    VARCHAR(16) NOT NULL,
    content         CLOB        NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conv ON chat_messages (conversation_id, seq);
```

- [ ] **Step 4: Ignore the H2 file-DB directory in `.gitignore`**

Append to the end of `.gitignore`:

```
# H2 file database
data/
```

- [ ] **Step 5: Add `historyWindow` to `AppProperties.Chat`**

Replace the whole `AppProperties.java` with:

```java
package net.jimmykw.knowledgegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, int poolSize, Chat chat) {

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
    }
}
```

- [ ] **Step 6: Verify compile**

Run: `./gradlew compileJava --quiet`
Expected: builds with no output (success). If it fails on the new `historyWindow` field, the only other reader of `AppProperties.Chat` is `CypherExecutor`'s bean (`appProperties.chat().resultRowLimit()`) and `ChatService` (`maxToolCallRounds()`); neither constructs `Chat` directly, so adding a field is safe.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yml src/main/resources/schema.sql .gitignore src/main/java/net/jimmykw/knowledgegraph/config/AppProperties.java
git commit -m "Wire H2 datasource and chat history-window property"
```

---

## Task 2: H2-backed ChatMemoryRepository + ChatMemory beans

**Files:**
- Create: `src/main/java/net/jimmykw/knowledgegraph/chat/store/H2ChatMemoryRepository.java`
- Create: `src/main/java/net/jimmykw/knowledgegraph/config/ChatMemoryConfig.java`

**Interfaces:**
- Consumes: `org.springframework.ai.chat.memory.ChatMemoryRepository` (Spring AI interface, 4 methods: `findConversationIds()` / `findByConversationId(String)` / `saveAll(String, List<Message>)` / `deleteByConversationId(String)`); `org.springframework.jdbc.core.JdbcTemplate` (auto-configured by `spring-boot-starter-jdbc` from Task 1); `AppProperties.chat().historyWindow()` (Task 1).
- Produces: a `ChatMemoryRepository` bean and a `ChatMemory` bean (`MessageWindowChatMemory`) — both consumed by Task 3 (the advisor wiring) and Task 5 (`ChatService.clearConversation`).

- [ ] **Step 1: Create `H2ChatMemoryRepository.java`**

```java
package net.jimmykw.knowledgegraph.chat.store;

import java.util.List;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@RequiredArgsConstructor
public class H2ChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<String> findConversationIds() {
        return Try.of(() -> jdbcTemplate.queryForList(
                        "SELECT DISTINCT conversation_id FROM chat_messages", String.class))
                .onFailure(ex -> log.warn("Failed to load conversation ids", ex))
                .getOrElse(List.of());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return Try.of(() -> jdbcTemplate.query(
                        "SELECT message_type, content FROM chat_messages WHERE conversation_id = ? ORDER BY seq",
                        (rs, rowNum) -> toMessage(rs.getString("message_type"), rs.getString("content")),
                        conversationId))
                .onFailure(ex -> log.warn("Failed to load conversation history for {}", conversationId, ex))
                .getOrElse(List.of());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Try.run(() -> {
                    jdbcTemplate.update("DELETE FROM chat_messages WHERE conversation_id = ?", conversationId);
                    for (int seq = 0; seq < messages.size(); seq++) {
                        val message = messages.get(seq);
                        jdbcTemplate.update(
                                "INSERT INTO chat_messages (conversation_id, seq, message_type, content) VALUES (?, ?, ?, ?)",
                                conversationId, seq, message.getMessageType().name(), message.getText());
                    }
                })
                .onFailure(ex -> log.error("Failed to persist conversation history for {}", conversationId, ex));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Try.of(() -> jdbcTemplate.update(
                        "DELETE FROM chat_messages WHERE conversation_id = ?", conversationId))
                .onFailure(ex -> log.warn("Failed to delete conversation history for {}", conversationId, ex));
    }

    private static Message toMessage(String messageType, String content) {
        val type = MessageType.valueOf(messageType);
        return switch (type) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case TOOL -> new UserMessage(content);
        };
    }
}
```

Note: `Message.getText()` returns the message content; `MessageType.valueOf(name)` matches the `.name()` form stored on save (`USER`/`ASSISTANT`/`SYSTEM`/`TOOL`). Only `USER`/`ASSISTANT` are persisted in practice (the advisor sends user + assistant messages), but all four are mapped defensively.

- [ ] **Step 2: Create `ChatMemoryConfig.java`**

```java
package net.jimmykw.knowledgegraph.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.jimmykw.knowledgegraph.chat.store.H2ChatMemoryRepository;
import net.jimmykw.knowledgegraph.config.AppProperties;

import lombok.val;

@Configuration
public class ChatMemoryConfig {

    @Bean
    ChatMemoryRepository h2ChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return new H2ChatMemoryRepository(jdbcTemplate);
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AppProperties appProperties) {
        val window = appProperties.chat().historyWindow();
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(window)
                .build();
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew compileJava --quiet`
Expected: success.

**Verified API notes (from Spring AI 2.0.0 source):** `Message.getText()` (declared on `AbstractMessage`, inherited by `UserMessage`/`AssistantMessage`/`SystemMessage`) returns the message text — so `message.getText()` in `saveAll` is correct. `ChatMemoryRepository` and `MessageWindowChatMemory` live in `org.springframework.ai.chat.memory` and are pulled in transitively by `spring-ai-starter-model-openai`; if compile reports either missing, add `implementation("org.springframework.ai:spring-ai-model")` to `build.gradle.kts` (the BOM will pick the version).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/chat/store/H2ChatMemoryRepository.java src/main/java/net/jimmykw/knowledgegraph/config/ChatMemoryConfig.java
git commit -m "Add H2-backed ChatMemoryRepository and ChatMemory beans"
```

---

## Task 3: Wire MessageChatMemoryAdvisor into the chat ChatClient

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/config/ChatClientConfig.java`

**Interfaces:**
- Consumes: `ChatMemory` bean (Task 2); `org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor`.
- Produces: a `chatChatClient` bean whose default advisors include the memory advisor, used by `ChatService` (Task 5) via `mutate()`.

- [ ] **Step 1: Add the memory advisor to `chatChatClient`**

Replace the `chatChatClient` bean method in `ChatClientConfig.java` with:

```java
    @Bean
    ChatClient chatChatClient(ChatClient.Builder builder, ToolCallback skillsTool, GraphTools graphTools, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(skillsTool, graphTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
```

And add these imports at the top of `ChatClientConfig.java`:

```java
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
```

(The rest of the file — `chatClient`, `skillsTool`, `extractionOutputConverter` beans and `SYSTEM_PROMPT` — stays unchanged.)

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileJava --quiet`
Expected: success.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/config/ChatClientConfig.java
git commit -m "Wire MessageChatMemoryAdvisor into chat ChatClient"
```

---

## Task 4: Add conversationId to request/response records + update call sites

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/chat/ChatRequest.java`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/chat/ChatResponse.java`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/api/ApiExceptionHandler.java`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/chat/ChatService.java` (only the `buildResponse` helper, to append `null` — keeps compile green; the real id is wired in Task 5)

**Interfaces:**
- Consumes: nothing.
- Produces: `ChatRequest.conversationId()` and `ChatResponse.conversationId()` consumed by Task 5 (`ChatService`) and Task 6 (`ChatController`).

**Note:** Adding a component to `ChatResponse` breaks every `new ChatResponse(...)` call. There are three: `ChatService.buildResponse`, `ApiExceptionHandler.chatError`, `ApiExceptionHandler.handleChatFailure`. This task updates all three to append `null` for the new last component; Task 5 then gives `ChatService.buildResponse` the real conversationId.

- [ ] **Step 1: Add `conversationId` to `ChatRequest`**

Replace `ChatRequest.java` with:

```java
package net.jimmykw.knowledgegraph.chat;

public record ChatRequest(String prompt, String conversationId) {
}
```

- [ ] **Step 2: Add `conversationId` as the LAST component of `ChatResponse`**

Replace `ChatResponse.java` with:

```java
package net.jimmykw.knowledgegraph.chat;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String answer,
        String cypher,
        List<Map<String, Object>> results,
        boolean truncated,
        int rowCount,
        String error,
        List<String> skillsExecuted,
        List<String> cypherQueries,
        String conversationId) {
}
```

- [ ] **Step 3: Update the two `ApiExceptionHandler` call sites**

In `ApiExceptionHandler.java`:

`handleChatFailure` (the `ChatException` handler) — append `null`:

```java
    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ChatResponse> handleChatFailure(ChatException ex) {
        log.warn("Chat request failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ChatResponse(null, ex.cypher(), null, false, 0, ex.error(), List.of(), List.of(), null));
    }
```

`chatError` (private helper) — append `null`:

```java
    private static ChatResponse chatError(String message) {
        return new ChatResponse(null, null, null, false, 0, message, List.of(), List.of(), null);
    }
```

(`handleInvalidPrompt` and `handleGraphEmpty` both call `chatError(...)`, so they inherit the fix.)

- [ ] **Step 4: Update `ChatService.buildResponse` to append `null` (transient; real id wired in Task 5)**

In `ChatService.java`, change only the `buildResponse` helper:

```java
    private static ChatResponse buildResponse(String answer, ToolTrace trace) {
        return new ChatResponse(answer, trace.lastCypher(), trace.lastRows(),
                trace.lastTruncated(), trace.lastCount(), resolveError(trace), trace.skillsExecuted(),
                trace.cypherQueries(), null);
    }
```

(Do NOT change `chat(String prompt)` or anything else in this file yet — that's Task 5. Only the `new ChatResponse(...)` call gains the trailing `null`.)

- [ ] **Step 5: Verify compile**

Run: `./gradlew compileJava --quiet`
Expected: success. If it fails, search for any other `new ChatResponse(` call site and append `null` — there should be only the three covered above.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/chat/ChatRequest.java src/main/java/net/jimmykw/knowledgegraph/chat/ChatResponse.java src/main/java/net/jimmykw/knowledgegraph/api/ApiExceptionHandler.java src/main/java/net/jimmykw/knowledgegraph/chat/ChatService.java
git commit -m "Add conversationId to chat request/response and update call sites"
```

---

## Task 5: Wire conversationId through ChatService (+ AppConfig bean)

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/chat/ChatService.java`
- Modify: `src/main/java/net/jimmykw/knowledgegraph/config/AppConfig.java`

**Interfaces:**
- Consumes: `ChatMemory` bean (Task 2, for `clearConversation`); `ChatMemory.CONVERSATION_ID` constant; `ChatResponse.conversationId()` (Task 4); `ChatClient.chatChatClient` with the memory advisor (Task 3).
- Produces: `ChatService.chat(String prompt, String conversationId)` and `ChatService.clearConversation(String conversationId): boolean` — both consumed by Task 6 (`ChatController`).

**Verified facts used here (Spring AI 2.0.0 source):**
- `ToolCallingAdvisor.Builder.conversationHistoryEnabled(boolean)` is public; javadoc: "If false, you need a ChatMemory Advisor registered next in the chain." This mirrors `DefaultChatClient.autoRegisterToolCallingAdvisor`'s coordination when a memory advisor is present. Tool results still loop back across rounds regardless.
- The advisor reads the conversation id from the request context key `ChatMemory.CONVERSATION_ID` (`"chat_memory_conversation_id"`), set per-request via `.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, id))`.

- [ ] **Step 1: Rewrite `ChatService.java`**

Replace the whole file with:

```java
package net.jimmykw.knowledgegraph.chat;

import java.util.UUID;

import io.vavr.control.Option;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatChatClient;
    private final AppProperties appProperties;
    private final ChatMemory chatMemory;

    public ChatResponse chat(String prompt, String conversationId) {
        validate(prompt);
        val resolvedConversationId = resolveConversationId(conversationId);
        val trace = new ToolTrace();
        val maxRounds = appProperties.chat().maxToolCallRounds();
        val tracingManager = new TracingToolCallingManager(ToolCallingManager.builder().build(), trace);
        val advisor = ToolCallingAdvisor.builder()
                .toolCallingManager(tracingManager)
                .toolExecutionEligibilityChecker(cappedChecker(trace, maxRounds))
                .conversationHistoryEnabled(false)
                .build();
        val client = chatChatClient.mutate().defaultAdvisors(advisor).build();
        val answer = client.prompt(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, resolvedConversationId))
                .call().content();
        log.info("Chat: completed with {} tool round(s){}", trace.roundCount(),
                trace.maxRoundsExceeded() ? " (max rounds exceeded)" : "");
        return buildResponse(answer, trace, resolvedConversationId);
    }

    public boolean clearConversation(String conversationId) {
        val existing = chatMemory.get(conversationId);
        if (existing.isEmpty()) {
            return false;
        }
        chatMemory.clear(conversationId);
        return true;
    }

    private void validate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidPromptException("Prompt must not be blank.");
        }
        val maxLength = appProperties.chat().maxPromptLength();
        if (prompt.length() > maxLength) {
            throw new InvalidPromptException(
                    "Prompt exceeds the maximum length of " + maxLength + " characters.");
        }
    }

    private static String resolveConversationId(String conversationId) {
        return Option.of(conversationId)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .getOrElse(UUID.randomUUID().toString);
    }

    private static ChatResponse buildResponse(String answer, ToolTrace trace, String conversationId) {
        return new ChatResponse(answer, trace.lastCypher(), trace.lastRows(),
                trace.lastTruncated(), trace.lastCount(), resolveError(trace), trace.skillsExecuted(),
                trace.cypherQueries(), conversationId);
    }

    private static String resolveError(ToolTrace trace) {
        if (trace.error() != null) {
            return trace.error();
        }
        if (trace.maxRoundsExceeded()) {
            return "Exceeded max tool call rounds";
        }
        return null;
    }

    private static ToolExecutionEligibilityChecker cappedChecker(ToolTrace trace, int maxRounds) {
        return response -> {
            if (!response.hasToolCalls()) {
                return false;
            }
            if (trace.roundCount() >= maxRounds) {
                trace.markMaxRoundsExceeded();
                return false;
            }
            return true;
        };
    }
}
```

- [ ] **Step 2: Update the `chatService` bean in `AppConfig.java` to inject `ChatMemory`**

In `AppConfig.java`, change the `chatService` bean to add the `ChatMemory` parameter, and add the import:

```java
    @Bean
    ChatService chatService(ChatClient chatChatClient, AppProperties appProperties, ChatMemory chatMemory) {
        return new ChatService(chatChatClient, appProperties, chatMemory);
    }
```

Add the import near the other `org.springframework.ai...` imports in `AppConfig.java`:

```java
import org.springframework.ai.chat.memory.ChatMemory;
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew compileJava --quiet`
Expected: success. Common failure: `AppProperties.Chat` constructs a 5-arg `Chat` only via the compact constructor / `@ConfigurationProperties` binding — no other `new Chat(...)` call sites exist, so Task 1's change is self-contained.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/chat/ChatService.java src/main/java/net/jimmykw/knowledgegraph/config/AppConfig.java
git commit -m "Wire conversationId and memory-advisor coordination through ChatService"
```

---

## Task 6: ChatController — pass conversationId + DELETE endpoint

**Files:**
- Modify: `src/main/java/net/jimmykw/knowledgegraph/chat/ChatController.java`

**Interfaces:**
- Consumes: `ChatService.chat(String, String)` and `ChatService.clearConversation(String): boolean` (Task 5); `ChatRequest.conversationId()` (Task 4).
- Produces: the public HTTP contract — `POST /api/knowledge-graph/chat` now accepts an optional `conversationId` and returns it in `ChatResponse`; `DELETE /api/knowledge-graph/chat?conversationId=X` returns 204 (cleared) or 404 (never existed).

- [ ] **Step 1: Update `ChatController.java`**

Replace the whole file with:

```java
package net.jimmykw.knowledgegraph.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final SchemaService schemaService;

    @PostMapping("/api/knowledge-graph/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.prompt(), request.conversationId());
    }

    @DeleteMapping("/api/knowledge-graph/chat")
    public ResponseEntity<Void> deleteConversation(@RequestParam String conversationId) {
        return chatService.clearConversation(conversationId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/knowledge-graph/schema")
    public SchemaSnapshot schema() {
        return schemaService.readSchemaRaw();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileJava --quiet`
Expected: success.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/jimmykw/knowledgegraph/chat/ChatController.java
git commit -m "Add conversationId to chat endpoint and DELETE conversation handler"
```

---

## Done

All six tasks done. The feature is complete:
- `POST /api/knowledge-graph/chat` accepts `{"prompt": "...", "conversationId": "..."}` (conversationId optional); returns the conversationId in the response; prior turns load automatically via the memory advisor.
- `DELETE /api/knowledge-graph/chat?conversationId=X` clears a conversation (204) or 404s if it never existed.
- History persists in `./data/chatdb` (H2 file DB), survives restarts, windowed to `app.chat.history-window` (default 20).

Final full build check:

```bash
./gradlew compileJava --quiet
```

End-to-end smoke test (requires a running Neo4j with APOC + the `OPENAI_API_KEY`/`NEO4J_PASSWORD` env vars; see `AGENTS.md`):

```bash
# Start the app
./gradlew bootRun

# First turn (new conversation; note the returned conversationId)
curl -s -X POST http://localhost:8080/api/knowledge-graph/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"What entities are in the graph?"}' | jq

# Follow-up using the returned conversationId
curl -s -X POST http://localhost:8080/api/knowledge-graph/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Tell me more about the first one","conversationId":"<id-from-first-call>"}' | jq

# Clear the conversation
curl -i -X DELETE "http://localhost:8080/api/knowledge-graph/chat?conversationId=<id-from-first-call>"
```