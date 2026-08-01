# Multi-Turn Chat with History — Design

**Date:** 2026-07-31
**Status:** Approved (brainstorming complete; revised after Spring AI 2.0 API verification)
**Approach:** Simple History via Spring AI's `MessageChatMemoryAdvisor` (windowing, no summarization)

## Goal

Make the existing `POST /api/knowledge-graph/chat` endpoint conversation-aware. A client may supply a `conversationId`; prior messages for that conversation are persisted in an embedded H2 database and re-injected into the LLM conversation by Spring AI's `MessageChatMemoryAdvisor`, enabling follow-up questions and multi-turn dialogue. The approach leverages Spring AI 2.0's built-in chat-memory machinery — `ChatMemoryRepository` (H2-backed) + `MessageWindowChatMemory` (free sliding window, default 20) + `MessageChatMemoryAdvisor` — rather than hand-rolling load/inject/save logic.

## Non-Goals

- Summarization / compression of long conversation history (Approach 2 — deferred).
- Streaming responses (SSE).
- Conversation listing, per-message deletion, titles, or metadata management.
- Test infrastructure (the repo has none; bootstrapping a test suite is a separate project).
- Multi-user authentication / authorization of conversations.

## Architecture & Data Flow

The chat endpoint becomes conversation-aware. Each request may carry an optional `conversationId`; Spring AI's `MessageChatMemoryAdvisor` (wired as a default advisor on the `chatChatClient`) loads prior messages for that conversation from an H2-backed `ChatMemoryRepository`, prepends them to the prompt, runs the existing agentic tool-calling flow, and saves the new user + assistant messages back to H2 — all automatically. `ChatService` only resolves/generates the `conversationId` and passes it to the advisor via the request context.

### Request flow (`POST /api/knowledge-graph/chat`)

1. Validate prompt (unchanged — blank/length checks, `InvalidPromptException` → 400).
2. Resolve `conversationId` — use the one in the request, or generate a UUID if absent/blank.
3. Pass `conversationId` to the request context via `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))`.
4. Build the `ChatClient` (already has `defaultSystem` + `defaultTools` + `MessageChatMemoryAdvisor` via `mutate()`; add the existing `ToolCallingAdvisor` wrapped in `TracingToolCallingManager`).
5. Run the agent (multi-round tool calling — unchanged). `MessageChatMemoryAdvisor.before` loads + prepends history; `.after` saves the new user + assistant messages.
6. Return `ChatResponse` with `conversationId` added.

### Delete flow (`DELETE /api/knowledge-graph/chat?conversationId=X`)

- `ChatService.clearConversation(id)`: if `findByConversationId(id)` is empty → returns `false`; else `deleteByConversationId(id)` → returns `true`.
- Controller maps `true` → 204, `false` → `ResponseEntity.notFound().build()` (404).

### Key invariants

- The internal tool-call rounds within a single request stay ephemeral (handled by `TracingToolCallingManager`) — only the final user prompt + final assistant answer are persisted across requests (the advisor adds the user message in `before` and the assistant response in `after`).
- A missing or new `conversationId` starts a fresh conversation; the generated ID is returned so the client can reuse it.
- History is bounded by `MessageWindowChatMemory`'s sliding window (default 20, configurable via `app.chat.history-window`) to stay within token limits. Window eviction snaps forward to a complete user/assistant turn and dedupes `SystemMessage`s.
- No history for a provided `conversationId` is treated as a new conversation — no 404 on the request path; the ID is valid whether or not it exists yet.
- `MessageChatMemoryAdvisor` coordinates with the `ToolCallingAdvisor` for history ownership. Spring AI's auto-registered `ToolCallingAdvisor` sets `conversationHistoryEnabled(false)` automatically when a downstream `MemoryAdvisor` is detected (verified in 2.0.0 source, `DefaultChatClient.autoRegisterToolCallingAdvisor`) — but the existing chat uses a **manual** `ToolCallingAdvisor` (to wire `TracingToolCallingManager` + the capped eligibility checker), which *suppresses* auto-registration. So `ChatService` must explicitly call `.conversationHistoryEnabled(false)` on the manual `ToolCallingAdvisor.Builder` to reproduce the framework's coordination. Tool results are still fed back across rounds either way (that is the core tool-call loop, not governed by this flag, which only decides who owns the cross-request conversation history).

  **CORRECTION (2026-08-01) — this invariant is wrong and caused a regression; do not follow it.** `autoRegisterToolCallingAdvisor` disables internal history only when a `MemoryAdvisor` has a **higher order than the tool advisor** — `anyMatch(a -> a instanceof MemoryAdvisor && a.getOrder() > configuredOrder)`, then `conversationHistoryEnabled(!hasDownstreamMemoryAdvisor)`. This app registers `MessageChatMemoryAdvisor` at the default order (`DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` = `HIGHEST_PRECEDENCE + 200`), which is *upstream* of the manual `ToolCallingAdvisor` (`HIGHEST_PRECEDENCE + 300`, outside the tool-call loop), so the framework's own coordination for this wiring is `true`, not `false`. The claim "tool results are still fed back across rounds either way" is also false: with the flag `false`, `ToolCallingAdvisor.doGetNextInstructionsForToolCall` rebuilds the prompt for every round after the first as `[systemMessage, lastToolResponseMessage]`, dropping the user question, prior assistant tool-call messages, and memory — the model then stops calling tools and answers context-free (observed live: skill loaded in round 1, then "I don't see a specific question from you yet", `cypherQueries: []`). **Fix shipped:** `ChatService` does not set the flag (default `true`); the tool loop carries the full conversation per round and the upstream memory advisor only adds cross-request history at the boundary. Only set `false` if a memory advisor is also moved downstream of the tool advisor.

## H2 Schema & Data Access

### New dependencies (`build.gradle.kts`)

- `implementation("org.springframework.boot:spring-boot-starter-jdbc")` — provides `DataSource` auto-config + `JdbcTemplate` (leaner than `spring-boot-starter-data-jdbc`; no Spring Data repository proxies needed since we implement `ChatMemoryRepository` directly with `JdbcTemplate`).
- `runtimeOnly("com.h2database:h2")`.

### Spring AI chat-memory mechanism

Spring AI 2.0 provides three pieces we adopt instead of hand-rolling history:

- **`ChatMemoryRepository`** (`org.springframework.ai.chat.memory`) — a 4-method interface: `findConversationIds()`, `findByConversationId(String)`, `saveAll(String, List<Message>)` (replace-all semantics), `deleteByConversationId(String)`. We implement this with H2 via `JdbcTemplate`.
- **`MessageWindowChatMemory`** (`org.springframework.ai.chat.memory`) — a `ChatMemory` implementation built on a `ChatMemoryRepository` that provides the sliding window (default `maxMessages = 20`), turn-boundary eviction, and `SystemMessage` dedup. We build it with our H2 repo + `app.chat.history-window`.
- **`MessageChatMemoryAdvisor`** (`org.springframework.ai.chat.client.advisor`) — the advisor that, in `before`, loads memory and prepends it to the prompt and, in `after`, stores the new assistant response. Wired as a default advisor on `chatChatClient`.

### Data model

Single `chat_messages` table, created via `src/main/resources/schema.sql` (Spring Boot auto-applies to embedded DBs). Stores the ordered `List<Message>` that `ChatMemoryRepository.saveAll` replaces wholesale; `seq` preserves order:

```sql
CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36)  NOT NULL,
    seq             INTEGER     NOT NULL,   -- position within the conversation's message list
    message_type    VARCHAR(16)  NOT NULL,   -- 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL'
    content         CLOB         NOT NULL,   -- message text
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conv ON chat_messages (conversation_id, seq);
```

Only `USER` and `ASSISTANT` messages are persisted in practice (the advisor adds the user message in `before` and the assistant response in `after`; the system message is re-injected fresh each request from `defaultSystem`, not stored). The `message_type` column is kept general so the schema stays correct even if Spring AI stores other message types in future.

### `H2ChatMemoryRepository` (implements `ChatMemoryRepository`)

Plain class (NOT a Spring Data repository interface) using an injected `JdbcTemplate`. Registered as a `@Bean`. Matches the project's explicit-wiring style and needs no `@EnableJdbcRepositories`:

- `List<String> findConversationIds()` — `SELECT DISTINCT conversation_id FROM chat_messages`.
- `List<Message> findByConversationId(String id)` — `SELECT message_type, content FROM chat_messages WHERE conversation_id = ? ORDER BY seq`, map each row to the corresponding Spring AI `Message` subtype (`UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResponseMessage`) via `MessageType` enum.
- `void saveAll(String id, List<Message> messages)` — `DELETE FROM chat_messages WHERE conversation_id = ?`, then batch-insert rows with `seq = list index`.
- `void deleteByConversationId(String id)` — `DELETE FROM chat_messages WHERE conversation_id = ?`.

### Persistence configuration (`application.yml`)

```yaml
app:
  chat:
    history-window: 20
spring:
  datasource:
    url: jdbc:h2:file:./data/chatdb
    driver-class-name: org.h2.Driver
  sql:
    init:
      mode: always
```

- H2 runs in file mode at `./data/chatdb` so it survives restarts.
- `./data/` is added to `.gitignore`.
- `mode: always` plus `CREATE TABLE IF NOT EXISTS` keeps initialization idempotent.

### Bean wiring (new `ChatMemoryConfig`)

- `@Bean ChatMemoryRepository h2ChatMemoryRepository(JdbcTemplate jdbcTemplate)` — returns `new H2ChatMemoryRepository(jdbcTemplate)`.
- `@Bean ChatMemory chatMemory(ChatMemoryRepository repo, AppProperties props)` — `MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(props.chat().historyWindow()).build()`. Declaring an explicit `ChatMemory` bean overrides Spring AI's auto-configured in-memory one (if present on the classpath) via `@ConditionalOnMissingBean`; if the chat-memory autoconfigure starter is absent, this is the sole definition — either way, explicit is safe and matches the project's no-auto-wiring-surprises convention.

`MessageChatMemoryAdvisor` is wired in `ChatClientConfig.chatChatClient` as a default advisor (see Changes table).

### Token awareness

With default window = 20 messages (10 user/assistant turns), the advisor-prepended history fits comfortably within `glm-5.2`'s context window alongside the system prompt, tool definitions, and tool-call rounds. No client-side token counting.

## Changes to Existing Files

| File | Change |
|---|---|
| `build.gradle.kts` | Add `spring-boot-starter-jdbc` + H2 runtime dep. |
| `application.yml` | Add H2 datasource, `spring.sql.init.mode`, `app.chat.history-window`. |
| `AppProperties.Chat` record | Add `int historyWindow`. |
| `ChatRequest` | Add optional `String conversationId` (nullable). |
| `ChatResponse` | Add `String conversationId` as the last record component (so existing constructor call sites in `ChatService` / `ApiExceptionHandler` only append one `null`/value arg). |
| `ApiExceptionHandler` | Update the three `new ChatResponse(...)` call sites (`chatError`, `handleChatFailure`, and `ChatService.buildResponse`) to append the new `conversationId` arg (`null` for error responses; the resolved id for the success path). |
| `ChatClientConfig` | Inject `ChatMemory` into `chatChatClient`; add `MessageChatMemoryAdvisor.builder(chatMemory).build()` as a default advisor alongside the existing system prompt + tools. |
| `ChatService` | Add `private final ChatMemoryRepository chatMemoryRepository`. In `chat()`: resolve/generate `conversationId`, pass it via `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))` on the request (the advisor loads + saves automatically — no manual history load/inject/save). On the manual `ToolCallingAdvisor.Builder`, add `.conversationHistoryEnabled(false)` to coordinate with the memory advisor (see Key invariants). Add `boolean clearConversation(String id)`: `findByConversationId(id).isEmpty()` → `false`; else `deleteByConversationId(id)` → `true`. `buildResponse` receives the `conversationId` to set on the response. |
| `ChatController` | Add `DELETE /api/knowledge-graph/chat?conversationId=X` handler returning `204` (on `true`) or `ResponseEntity.notFound().build()` (on `false`). Pass `request.conversationId()` into `chatService.chat(...)`. |
| `.gitignore` | Add `./data/`. |

### New files

- `chat/store/H2ChatMemoryRepository.java` — implements `ChatMemoryRepository` via `JdbcTemplate` (4 methods above).
- `config/ChatMemoryConfig.java` — `@Bean ChatMemoryRepository` + `@Bean ChatMemory` (wired with `historyWindow`).
- `src/main/resources/schema.sql` — `chat_messages` table + index DDL.

### Removed/changed from the earlier draft

- `ChatMessageRecord`, the `ConversationRepository` Spring Data interface, `@EnableJdbcRepositories`, and the `cypher` column are all dropped — replaced by `ChatMemoryRepository` + `JdbcTemplate` and Spring AI's `Message` model.

### Source package layout

All new Java files live under `src/main/java/net/jimmykw/knowledgegraph/` — `chat/store/` for `H2ChatMemoryRepository`, `config/` for `ChatMemoryConfig` — matching the existing flat package layout.

## Error Handling & Edge Cases

| Case | Handling |
|---|---|
| No history for a provided `conversationId` | Treated as a new conversation — `findByConversationId` returns empty, the advisor prepends nothing, proceeds, and `saveAll` creates rows on first turn. No 404 on the request path. |
| H2 read failure mid-request | `H2ChatMemoryRepository.findByConversationId` wraps its query in `Try.of(...)` and returns an empty `List` on failure after logging `warn` (history silence > hard-failing a working chat). Mirrors the extraction pipeline's skip-and-continue convention. |
| H2 write failure after successful chat | `H2ChatMemoryRepository.saveAll` wraps its delete+insert in `Try.of(...)` and no-ops on failure after logging `error`. The successful answer is still returned to the client; a transient DB hiccup shouldn't discard a completed chat. |
| Conversation exceeds window | `MessageWindowChatMemory` evicts oldest non-system messages, snapping forward to a complete user/assistant turn (built-in). No hard fail; older context drops. |
| Empty / whitespace `conversationId` | Normalize to absent via `Option.of(s).map(String::trim).filter(s -> !s.isBlank())` → generate a new UUID. |
| `DELETE` on never-existing conversation | `findByConversationId(id)` empty → `clearConversation` returns `false` → controller returns `ResponseEntity.notFound().build()` (real 404, not `GraphEmptyException` — `GraphEmptyException` is mapped to 422 by `ApiExceptionHandler`, so a direct 404 ResponseEntity is used instead for correct semantics). |
| Concurrent same-`conversationId` requests | No locking. H2 single-process; `saveAll` replaces the whole conversation each turn, so interleaved writes use last-writer semantics on the window. Documented as a known limitation; single-user interactive use is the target. |

No new exception classes — the existing `KnowledgeGraphException` hierarchy covers it. H2 failures are absorbed inside `H2ChatMemoryRepository` (resilience-by-design), so they never reach `ApiExceptionHandler` in normal operation; anything that does escape surfaces through the generic `Exception` handler (logs at `error`), so no new handler mappings are required.

## Testing Approach

The repo has **no tests today** and `AGENTS.md` is explicit: the only verification is `./gradlew compileJava`. Bootstrapping an entire test suite (Gradle test config, JUnit platform, Mockito extension wiring, H2 test profile, `TestConstants`, etc.) is a separate project and out of scope for this feature.

- **Verification:** `./gradlew compileJava` (consistent with existing repo convention).
- **Testability:** Code is written to be testable — `H2ChatMemoryRepository` against the `ChatMemoryRepository` interface (4 methods) means a future in-memory fake or `@JdbcTest` slice slots in cleanly; `ChatService.clearConversation` is a pure-logic method over the interface. Tests can be added later without refactoring.
- **Follow-up:** "Add test infrastructure" is flagged as a candidate follow-up task for a future session.

## Open Questions

None — all design decisions resolved during brainstorming and the post-verification revision.

## References

- Brainstorming Q&A: chose "Better user experience" → "Multi-turn chat with history" → "H2/file storage" → "Client provides session ID in request body".
- Spring AI 2.0 API verification (from cached `spring-ai-*-2.0.0-sources.jar`): `.messages()` + `.user()` are additive; `defaultSystem` is always prepended by `DefaultChatClientUtils.toChatClientRequest`; `MessageChatMemoryAdvisor` is the sole memory advisor in 2.0.0 (`PromptChatMemoryAdvisor` does not exist); `ChatMemoryRepository` (4 methods) + `MessageWindowChatMemory` (windowing, default 20, built-in turn eviction) are the canonical persistence extension points; auto-config backs off when an explicit `ChatMemory` bean is provided. This evidence drove the shift from the manual `.messages()` approach to the advisor approach.
- Existing chat flow: `ChatService`, `ChatController`, `TracingToolCallingManager`, `GraphTools`, `CypherExecutor`, `ChatClientConfig`.
- Project conventions: Vavr `Try`/`Option`, `@RequiredArgsConstructor` + `private final`, explicit `@Bean` wiring, APOC for dynamic Neo4j writes (unchanged by this feature). Package root: `net.jimmykw.knowledgegraph`.