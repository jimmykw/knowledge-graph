# Spec: Typed Intent Gate for Chat (Spring AI TypeSafe)

## Summary
Add an optional, off-by-default **intent gate** in front of the chat agent. Before `ChatService.chat()` starts
the tool loop, it sends the new prompt plus the conversation history to the TypeSafe **Jev** model
(`typesafe/jev-1.13`, served by OpenRouter's System One endpoint `https://openrouter.ai/api/v1/systemone` and
billed to the existing OpenRouter account) as one typed `Choice` question (`GRAPH` / `CHITCHAT` / `OFF_TOPIC` / `UNANSWERABLE`). If the answer is
non-`GRAPH` with confidence at or above a configurable threshold (default 0.9), the call short-circuits with a
canned reply and no tool loop. Anything else, including any TypeSafe failure, goes to the agent exactly as today.
The decision is returned to callers in a new `route` field and shown as a badge in the UI.

## Goals
- Skip the up-to-10-round tool loop for messages that can never be answered from the graph.
- Use typed answers with confidence instead of parsing model text.
- Zero behavior change when `app.routing.enabled=false` (the default).
- Never refuse a real graph question (favour false negatives over false positives).
- Make the decision observable (response field, UI badge, logs) and measurable (labeled eval).

## Non-Goals
- Skill pre-selection, question-shape tuning, extraction scoring (other ideas from the TypeSafe review).
- `JevJudge`, `JevGuardrailAdvisor`, `JevSelfRefineAdvisor`, `JevToolIndex`, and RAG filters.
- Factual premise checking (e.g. "IBM acquired Google"); the agent verifies premises against the graph.
- Replacing `validate()` or the write-Cypher protection in `GraphTools`; the gate is not a security control.
- Changes to ingestion (`POST /api/knowledge-graph`) or the schema endpoint.

## Background
- `ChatService.chat()` (`chat/ChatService.java:26`): `validate` -> resolve conversation id -> `ToolCallingAdvisor`
  loop (capped by `maxToolCallRounds`, default 10) -> `buildResponse`. There is no pre-routing; the model picks
  skills itself via `SkillsTool` (`ChatClientConfig.java:84`).
- Memory: `MessageChatMemoryAdvisor` (order +200) on `chatChatClient` writes turns to `ChatMemory`
  (`MessageWindowChatMemory`, H2-backed, `app.chat.history-window` = 20 messages). It only writes when the agent
  runs, so a short-circuited turn leaves nothing in memory unless `ChatService` writes it.
- `ChatResponse` (`chat/ChatResponse.java`) has 10 components; its only production construction site is
  `ChatService.buildResponse`. The UI (`static/index.html`, `static/js/trace.js`) renders the trace from it.
- `AppProperties` (`config/AppProperties.java`) is a record with compact-constructor defaults; beans are wired in
  `AppConfig.java` / `ChatClientConfig.java` (no component scanning).
- TypeSafe (docs overview, v0.4.0): `org.springaicommunity:spring-ai-starter-typesafe`, Java 17+, Spring Boot 4.x,
  Spring AI 2.0.1+. `typeSafeClient.systemOne(state, Map<String, question>)` returns a response with
  `choiceValue(name)` and `choice(name).confidence()`. The starter's documented auth is `TYPESAFE_API_KEY` /
  `spring.ai.typesafe.api-key`; its base URL is not documented. Exact builder/config property names must be
  verified in Task 1; this spec does not assume them.
- OpenRouter hosts Jev (beta, since 2026-09-18) as `typesafe/jev-1.13`, `typesafe/jev-latest` (`~` alias) and
  `typesafe/jev-router`. It is called at `POST https://openrouter.ai/api/v1/systemone` with
  `Authorization: Bearer <OpenRouter key>` and a body of `model`, `state`, `questions` (types: noul, choice,
  score); answers carry probabilities and `usage.cost`. It does **not** work through OpenAI-compatible chat
  completions, so it cannot be an `OpenAiChatModel` bean. Source: openrouter.ai/docs/guides/community/typesafe-sdk.
- This feature pins `jev-1.13` (not `jev-latest`) so classification behavior and the tuned threshold stay stable;
  `jev-latest` and `jev-router` are out of scope.

## Design

### 1. Types (`chat/routing/`)
```java
public enum RouteIntent { GRAPH, CHITCHAT, OFF_TOPIC, UNANSWERABLE }
public enum RouteStatus { ROUTED, BLOCKED, SKIPPED }

/** @param intent null when status is SKIPPED; @param graphProbability Jev's probability for GRAPH (0.0 when SKIPPED) */
public record RouteDecision(RouteIntent intent, double graphProbability, RouteStatus status) {}

public interface QuestionRouter {
    /** @return none when routing is disabled; a SKIPPED decision when it is enabled but could not classify */
    Option<RouteDecision> route(String prompt, io.vavr.collection.List<Message> history);
}
```
- `NoOpQuestionRouter` (enabled=false): returns `Option.none()`.
- `TypeSafeQuestionRouter` (enabled=true): calls TypeSafe, applies the threshold, owns retry and fail-open.

### 2. Classification question
One `Choice` question `intent` with the four options and a short description of each, so the classifier
knows the graph is a knowledge graph built from the loaded documents:
- `GRAPH`: asks about entities, relationships, facts, counts or sources in the documents, including follow-ups
  that refer to earlier answers ("which of those...").
- `CHITCHAT`: greetings, thanks, meta questions about the assistant.
- `OFF_TOPIC`: unrelated to the documents (weather, coding help, general trivia).
- `UNANSWERABLE`: not answerable by any graph by kind (real-time data, opinions, predictions, calculations,
  requests to write or modify data). Not a factual premise check.

Decision rule (pure function, unit-tested). It uses the answer's `probabilities` map, **not** the single
`confidence` number: block when the combined probability of all non-`GRAPH` options is at least the threshold.
The spike showed `confidence` is stricter than the top probability (weather question: top probability 0.56,
`confidence` 0.41), so a 0.9 cutoff on `confidence` would almost never block anything non-graph.
```java
static RouteDecision decide(Map<RouteIntent, Double> probabilities, double blockThreshold) {
    val graph = probabilities.getOrDefault(RouteIntent.GRAPH, 0.0);
    val top = argmax(probabilities);                       // most probable intent, for the reply and the badge
    return 1.0 - graph >= blockThreshold
            ? new RouteDecision(top, graph, BLOCKED)       // non-graph mass >= 0.9, i.e. P(GRAPH) <= 0.1
            : new RouteDecision(top, graph, ROUTED);
}
```
The blocked intent is the most probable non-`GRAPH` option (picks the canned reply).

### 3. State sent to TypeSafe
`ChatService` reads `chatMemory.get(conversationId)` (the full `historyWindow`, both roles, including earlier
canned replies) and passes it with the new prompt. The state string is:
```
Conversation so far:
user: ...
assistant: ...

New message: <prompt>
```
This is a deliberate accuracy-over-privacy choice: assistant messages contain rows retrieved from the loaded
documents, so graph content is sent to the third party whenever routing is enabled (see Security).

### 4. `ChatService.chat()` flow
```java
validate(prompt);
val id = resolveConversationId(conversationId);
val route = router.route(prompt, List.ofAll(chatMemory.get(id)));
return route.filter(decision -> decision.status() == BLOCKED)
        .map(decision -> blockedResponse(prompt, id, decision))
        .getOrElse(() -> runAgent(prompt, id, route));   // existing loop; route attached to ChatResponse
```
- `blockedResponse`: stores `UserMessage(prompt)` and `AssistantMessage(reply)` via `chatMemory.add(id, ...)`
  (both messages stored, no tagging), then returns a `ChatResponse` with `answer = reply`, empty
  cypher/skills/evidence, `rowCount=0`, `error=null`, `route=decision`.
- Canned replies live in a `RoutingReplies` utility class keyed by `RouteIntent`
  (CHITCHAT, OFF_TOPIC, UNANSWERABLE); `GRAPH` has none.
- `buildResponse` gains the `route` argument (`Option<RouteDecision>.getOrNull()`).

### 5. Failure handling (fail-open, retry once)
In `TypeSafeQuestionRouter`:
- Up to 2 tries; retry only on transport errors (timeouts, I/O), not on 4xx/auth errors (same predicate style
  as the eval's `TransportRetry`).
- Per-call timeout `app.routing.timeout` (default 5s). Worst case added latency is about 2x timeout.
- After the final failure: log `warn` (no prompt text), return `RouteDecision(null, 0.0, SKIPPED)`; the agent
  runs as today.
- Missing/invalid answer or unknown choice value: treated the same as a failure (SKIPPED).

### 6. Configuration
`AppProperties` gains a `Routing routing` component (compact-constructor defaults, same pattern as `Chat`):
```java
public record Routing(boolean enabled, double blockThreshold, Duration timeout, String baseUrl, String apiKey, String model) {
    // defaults: enabled=false, blockThreshold=0.9, timeout=5s,
    //           baseUrl=https://openrouter.ai/api/v1, model=jev-1.13
}
```
```yaml
app:
  routing:
    enabled: false
    block-threshold: 0.9
    timeout: 5s
    base-url: https://openrouter.ai/api/v1
    api-key: ${OPENAI_API_KEY}      # the existing OpenRouter key, same as app.models.chat
    model: jev-1.13                 # sent as typesafe/jev-1.13; pinned, not jev-latest
```
- The key is the same OpenRouter key already used by `app.models.chat`; it comes from the environment, never
  inlined in `application.yml`.
- Wiring (decided in Task 1): preferred is the starter's `TypeSafeClient` configured through
  `spring.ai.typesafe.*` pointed at OpenRouter; fallback is a small `RestClient` call to
  `{base-url}/systemone` with the typed `state`/`questions` body, if the starter cannot target OpenRouter.
  Either way the `QuestionRouter` interface and decision logic are unchanged.
- Cost: each routed turn is billed to the OpenRouter account (`usage.cost` in the response); log it at debug.

### 7. Beans
Declared explicitly in `AppConfig`: `QuestionRouter` returns `TypeSafeQuestionRouter` when
`app.routing.enabled` is true, otherwise `NoOpQuestionRouter`. `ChatService` takes `QuestionRouter` through its
`@RequiredArgsConstructor`.

### 8. API and UI
- `ChatResponse` gains an 11th component `RouteDecision route` (null when routing is disabled).
- `trace.js`: default trace object gets `route: null`. `index.html`: show a small badge when
  `route.status === 'BLOCKED'` (intent + confidence) or `'SKIPPED'`; no badge for ordinary `ROUTED/GRAPH`.

### 9. Testing
**Hermetic (`./gradlew test`)**
- `RouteDecisionTest`: threshold boundary (0.9 exactly blocks), `GRAPH` never blocks, low-confidence non-graph
  routes to the agent.
- `ChatServiceRoutingTest` with a stub `QuestionRouter`: BLOCKED returns the canned reply and never invokes the
  agent; both messages are stored in memory; SKIPPED and `none()` run the agent; `route` is attached.
- `TypeSafeQuestionRouterTest` with a stubbed client: retry once on transport error then succeed; no retry on
  auth error; double failure yields SKIPPED.

**Opt-in `routingEval` (real API, `-Drouting.eval=true`, new Gradle task like `chatEval`)**
- Labeled prompts (about 20, 5 per intent) with expected intent, including the follow-up "which of those were
  collaborations with other companies?" seeded with history, and the six `GoldenCases` prompts.
- Hard assertions: every golden-case prompt, including "When did IBM acquire Google?", is **not BLOCKED**.
- Reported metrics: recall of blocks on non-graph prompts and false-block count; used to tune `block-threshold`.
- `chatEval` is unaffected (routing defaults to off).

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| Routing disabled | `NoOpQuestionRouter`; no TypeSafe call; `route=null`; behavior identical to today |
| Follow-up with no graph entity | History in the state makes it `GRAPH`; if still ambiguous, confidence is low and it goes to the agent |
| Non-graph intent below threshold | `ROUTED`; agent runs |
| TypeSafe timeout or transport error | One retry, then `SKIPPED`; agent runs; warn log |
| TypeSafe auth/4xx error | No retry; `SKIPPED`; warn log |
| False-premise question ("IBM acquired Google") | Classified `GRAPH`/unblocked; the agent's probe behavior applies |
| Blocked turn, later graph follow-up | Canned reply is in memory and in the agent's context (accepted trade-off of "store both") |
| Empty history (new conversation) | State contains only the new message |
| Prompt injection aimed at the classifier | Worst case is `GRAPH` (normal path) or a wrong block of the sender's own turn; no data exposure |
| Starter on classpath, key missing, routing off | Must not fail app startup (verify in Task 1; same class of issue as `spring.ai.openai.api-key`) |

## Security Considerations
- **Data egress:** with routing enabled, the prompt and the full recent history (both roles, up to 20 messages,
  including assistant answers built from graph rows) go to a third-party hosted API. Hence off by default;
  document this in `AGENTS.md` and the README.
- The OpenRouter key is read from the environment and never logged; prompt text is not logged at INFO. The
  existing key is reused, so it now also authorizes System One calls (same account billing and rate limits).
- Jev is in beta; the data goes to OpenRouter and to the TypeSafe-hosted model behind it. Review both providers'
  data-handling terms before enabling.
- The gate is advisory UX and cost control only. Read-only Cypher enforcement stays in `GraphTools`/`CypherExecutor`.
- Blocked replies are static strings, so nothing user-controlled is echoed back.

## Backward Compatibility
- Default config changes nothing at runtime.
- `ChatResponse` gains a component: all `new ChatResponse(...)` callers must be updated (main code has one,
  `ChatService.buildResponse`; check tests). JSON gains a nullable `route` property.
- `AppProperties` gains a component: update direct constructor callers, if any.
- Adds a dependency (`spring-ai-starter-typesafe` 0.4.0, community, pre-1.0) even when the feature is off,
  unless Task 1 selects the `RestClient` fallback (no new dependency).

## Alternatives Considered
| Alternative | Why Not |
|-------------|---------|
| `CallAdvisor` on `chatChatClient` | Advisor ordering vs `MessageChatMemoryAdvisor` (+200) and the tool advisor (+300) is easy to get wrong (see AGENTS.md); an explicit step in `ChatService` is simpler and visible in the response |
| First-turn-only gating | Cheaper and more private, but lets off-topic follow-ups through |
| Block on a lower threshold (0.5) | Wrongly refuses real questions; the priority is never refusing a graph question |
| Premise checking in the gate | It has no graph access, so it would refuse questions that are true in the loaded documents |
| Fail-closed on TypeSafe errors | Makes an optional feature an availability dependency |
| Do not store blocked turns | Cleaner agent context, but the transcript and UI history lose the exchange |
| Jev as an `OpenAiChatModel` / chat completions | Not supported: Jev only works through the System One endpoint |
| `typesafe/jev-latest` or `jev-router` | `latest` changes behavior under a tuned threshold; the router picks chat models and is not a classifier |
| Classify with the existing chat model | No third-party egress, but spends the same latency and tokens this feature aims to save and gives no typed confidence |

## Open Questions
- Can the 0.4.0 starter target OpenRouter's `/api/v1/systemone` (base URL and key properties), or do we use the
  `RestClient` fallback? Also: `Choice` builder, response accessors, autoconfiguration behavior without a key.
  Resolved by the Task 1 spike.
- Pricing/rate limits for `jev-1.13` on OpenRouter (not found in the pages checked); one extra call per turn.
- `jev-1.13` is a beta model and may be retired or renamed; pinning protects behavior but not availability
  (fail-open covers outages).
- Whether the full-history egress choice is acceptable once the data-handling terms of the hosted service are
  reviewed; the threshold of 0.9 is a starting point to be tuned with `routingEval`.

## Task Breakdown
1. Spike. **Part A (raw endpoint) is done, 2026-10-03:** with the existing `OPENAI_API_KEY` (OpenRouter), a
   one-`Choice` call to `POST https://openrouter.ai/api/v1/systemone` with `model=jev-1.13` works.
   - Request: `{model, state, questions: {intent: {type: "choice", instructions, criteria: {OPTION: "description"}}}}`.
   - Response: `answers.intent = {type, choice, probabilities: {OPTION: p}, confidence}`; plus `model`, `usage`
     (`input_tokens`, `output_tokens`, `cost`). `jev-1.13` resolves to `typesafe/jev-1.13-20260917` (dated snapshot).
   - Latency 0.16-0.40s per call; cost about $0.00002 per call (about 450 input tokens with a short history).
   - Results (5 prompts): "What did IBM invent?" GRAPH 0.99; follow-up "which of those were collaborations..."
     with history GRAPH 1.0; probe "When did IBM acquire Google?" GRAPH 0.81 (not blocked, as required);
     "Thanks, that was helpful!" CHITCHAT 1.0; "weather in Paris tomorrow" OFF_TOPIC 0.56 / UNANSWERABLE 0.44.
   - Finding: the answer has a separate `confidence` that is lower than the top probability (0.75 vs 0.81 and
     0.41 vs 0.56), hence the probability-mass rule in Design section 2. OFF_TOPIC and UNANSWERABLE overlap
     (weather is both); harmless because both block, but their canned replies should not conflict.
   - Only 5 prompts: the threshold and criteria wording still need the Task 8 eval.

   **Part B (decided, 2026-10-03): `RestClient` fallback.** `RestSystemOneClient` calls `{base-url}/systemone` directly (the
   proven Part A call); no `spring-ai-starter-typesafe` dependency, no startup/autoconfig risk with routing off. The
   `QuestionRouter` interface is unchanged if the starter is adopted later. Original task text: add `spring-ai-starter-typesafe:0.4.0` and confirm whether it can target OpenRouter, its
   config properties, and that startup works with routing off and no key. Choose starter vs `RestClient`
   fallback (the raw call above already works, so the fallback is proven); record findings here.
2. Add `RouteIntent`, `RouteStatus`, `RouteDecision`, `QuestionRouter`, `NoOpQuestionRouter`,
   `RoutingReplies` + `RouteDecision.decide` rule and its unit test.
3. Add `AppProperties.Routing` + yml defaults; update constructor callers.
4. Implement `TypeSafeQuestionRouter` (question, state builder, timeout, retry-once, fail-open) + tests.
5. Wire beans in `AppConfig`; inject `QuestionRouter` into `ChatService`.
6. Update `ChatService.chat()`: route, `blockedResponse` with memory writes, attach `route`; add `route` to
   `ChatResponse` and fix all callers; `ChatServiceRoutingTest`.
7. UI: `trace.js` default + `index.html` badge.
8. `routingEval` Gradle task, labeled prompt set, and `RoutingEvalTest` (including golden prompts never blocked).
9. Docs: `AGENTS.md` (flag, egress warning, eval command) and README; run the eval, tune `block-threshold`.
