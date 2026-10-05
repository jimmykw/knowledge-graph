# Spec: Migrate Jev calls to spring-ai-typesafe

## Summary
Replace the hand-written System One layer (`RestSystemOneApi`, `RestSystemOneClient`, `SystemOneApi`, manual map parsing) with the
[spring-ai-typesafe](https://spring-ai-community.github.io/spring-ai-typesafe/latest/) library. The intent gate calls `TypeSafeClient`
with a typed `Choice`. The answer judge becomes a `JevJudge` with two `Score` criteria. One `TypeSafeClient` bean comes from the Spring Boot
starter and is configured entirely through `spring.ai.typesafe.*`. Behavior of the gate is unchanged; the judge's result shape and strictness change.

## Goals
- Use the library as its docs describe: starter-configured `TypeSafeClient`, typed questions/answers, `JevJudge` for judging, library retry policy and exception hierarchy.
- Delete the custom HTTP client, untyped map building and untyped response parsing.
- Keep the gate rule (block when non-GRAPH probability mass is at least `block-threshold`) and keep both features fail-open.
- Fail fast at startup when a feature is enabled but no TypeSafe client exists.

## Non-Goals
- No `JevGuardrailAdvisor`, `JevSelfRefineAdvisor`, `JevEvaluator`, `JevConfidenceGate`, RAG or tool-index features.
- No change to when the gate or judge run in `ChatService`, to the direct-chat fallback, or to the agent tool loop.
- No change to what data leaves the app (same prompt, history, graph profile, rows, answer go to OpenRouter/TypeSafe).
- The judge stays advisory: it never edits, retries or hides an answer.

## Background
- `chat/routing/RestSystemOneApi.java`: `RestClient` POST to `{base-url}/systemone`, returns `Map<String,Object>`. Used by both features.
- `chat/routing/RestSystemOneClient.java`: builds the `intent` Choice as a map (instructions plus graph profile from `GraphCatalog`), parses `probabilities`.
- `chat/routing/TypeSafeQuestionRouter.java`: `MAX_TRIES = 2`, retries only on Spring `ResourceAccessException`, then `RouteDecision.decide` (0.9 threshold), fail-open `SKIPPED`.
- `chat/judge/JevAnswerJudge.java`: two ordinal `score` questions, normalizes expected level index to 0..1, `AnswerQuality.scored(..., minScore)`.
- `config/AppConfig.java` (~lines 80-96): builds two `RestSystemOneApi` instances (routing 5s, judge 3s) from `app.routing.*` / `app.judge.*`.
- `config/AppProperties.java`: `Routing` and `Judge` records hold base-url, api-key, model, timeout (and judge `minScore`).
- UI: `static/js/trace.js` `qualityChip()/qualityTitle()` read `quality.grounded`, `quality.relevance`, `quality.minScore`.
- Spike results (branch `spike/typesafe-sdk`, `TypeSafeSdkSpikeTest`):
  - The SDK appends `/v1/systemone`, so the base URL must be `https://openrouter.ai/api` (`/api/v1` gives a 404 at `/api/v1/v1/systemone`).
  - `typesafe/jev-1.13` works with the existing `OPENAI_API_KEY`. `Choice`/`Score` give the same answers as the current REST call.
  - Default retry policy is 2 retries, 30s total; against an unreachable host with a 1s timeout it took 4.3s, so it must be configured.
  - `JevJudge` throws `TypeSafeApiException` subclasses on auth, timeout and connection failures; it does not turn them into ERROR findings.
  - A thin-evidence answer scored `grounded=FAILED` at minimum level 2 (feedback: `grounded: rated "Some claims supported" (0.83), needs to reach 2.00 ...`).
  - Spring AI must be 2.0.1 or later (repo was on 2.0.0). `./gradlew test` passes on 2.0.1.

## Design

### Dependencies
```kotlin
implementation(platform("org.springaicommunity:typesafe-bom:0.4.0"))
implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))   // was 2.0.0
implementation("org.springaicommunity:spring-ai-starter-typesafe")   // TypeSafeClient auto-config
implementation("org.springaicommunity:typesafe-spring-ai")           // JevJudge
testImplementation("org.wiremock:wiremock-standalone:3.13.2")
```

### Configuration
One shared client; the starter reads `spring.ai.typesafe.*`:
```yaml
spring:
  ai:
    typesafe:
      api-key: ${OPENAI_API_KEY}
      base-url: https://openrouter.ai/api   # SDK appends /v1/systemone
      model: typesafe/jev-1.13              # pinned, not jev-latest
      timeout: 3s                           # per attempt
      retry:
        max-retries: 1
        total-timeout: 5s                   # includes the next attempt's HTTP timeout
app:
  routing: { enabled: true, block-threshold: 0.9 }
  judge:   { enabled: true, max-evidence-chars: 8000 }
```
Removed: `app.routing.{timeout,base-url,api-key,model}`, `app.judge.{min-score,timeout,base-url,api-key,model}`. Update `AppProperties.Routing`
and `Judge` records and their compact-constructor defaults accordingly. Note `totalTimeout` counts the next attempt's timeout, so a retry only
happens when the first attempt failed quickly (under about 2s here); after a 3s timeout the call ends.

### Router
```java
public interface IntentClassifier {                       // replaces SystemOneClient
    Map<RouteIntent, Double> classify(String state);      // throws on failure
}

@RequiredArgsConstructor
public class TypeSafeIntentClassifier implements IntentClassifier {
    private final TypeSafeClient client;
    private final Supplier<GraphProfile> graphProfile;     // GraphCatalog, unchanged

    public Map<RouteIntent, Double> classify(String state) {
        val choice = Choice.builder().instructions(instructions()).option("GRAPH", ...).option("CHITCHAT", ...)
                .option("OFF_TOPIC", ...).option("UNANSWERABLE", ...).build();   // same criteria text as today's CRITERIA map
        val answer = client.systemOne(state, java.util.Map.of("intent", choice)).choice("intent");
        return toIntents(answer.probabilities());          // RouteIntent.valueOf; unknown name => IllegalStateException
    }
}
```
- Intents are exactly today's `RouteIntent` values; criteria text and graph-profile instructions are moved verbatim.
- `TypeSafeQuestionRouter` takes an `IntentClassifier`; its own retry (`MAX_TRIES`, `isTransport`) is removed because the SDK retries.
- `RouteDecision.decide` and the 0.9 threshold are untouched. No `JevConfidenceGate`: it decides on one answer's confidence, not on non-GRAPH probability mass.
- Any `Throwable` from `classify` becomes `RouteDecision.skipped()` (fail open); log `requestId()` when the cause is a `TypeSafeApiException`.

### Judge
```java
JevJudge judge = JevJudge.builder(client)
    .score("grounded", Score.of("Rate how well `supporting_context` supports `assistant_answer`.", L0, L1, L2), 2)
    .score("relevant", Score.of("Rate how well `assistant_answer` addresses `user_question`, using `conversation` for context.", L0, L1, L2), 2)
    .failOnInconclusive(true)      // INCONCLUSIVE counts as LOW
    .build();                      // minConfidence stays at the library default 0.6
```
- Level wording (L0/L1/L2) is today's three levels from `JevAnswerJudge`.
- Input: `JevJudgeInput.builder().question(input.question()).answer(input.answer()).context(evidenceText).field("conversation", transcript).build()`.
  `evidenceText` is `EvidenceFormatter.format(...).text()` (still bounded by `max-evidence-chars`); the transcript is built as in today's `buildState`.
- Result mapping (new `AnswerQuality`, status plus feedback):

| Verdict | `status` | `feedback` |
|---------|----------|------------|
| `passed()` | `OK` | `null` |
| not passed (any FAILED or INCONCLUSIVE finding) | `LOW` | `verdict.feedback()` |
| any finding with outcome `ERROR` | `SKIPPED` | `null` |
| judge call throws | `SKIPPED` | `null` |

```java
public record AnswerQuality(QualityStatus status, String feedback, boolean evidenceTruncated) { skipped(), ok(truncated), low(feedback, truncated) }
```
`grounded`, `relevance` and `minScore` are removed. `AnswerJudge` and `JudgeInput` keep their signatures so `ChatService` is unchanged.

### UI
`trace.js`: `qualityChip()` shows `quality checked` for OK, `quality low` for LOW, `quality check unavailable` for SKIPPED; `qualityTitle()` shows `quality.feedback`
for LOW. `qualityIsLow()` is unchanged. Bump the `app.css` cache-bust query if styles change.

### Wiring and startup
`AppConfig` takes `ObjectProvider<TypeSafeClient>`. When `app.routing.enabled` or `app.judge.enabled` is true and the provider has no bean, throw
`IllegalStateException("app.routing/judge is enabled but spring.ai.typesafe.api-key is not set")`. When both flags are false no client is required and the app starts as today.
The router bean builds `TypeSafeIntentClassifier` with the existing `warmed(new GraphCatalog(...))`; the judge bean builds the `JevJudge` once.

### Tests
- WireMock stubs `POST /v1/systemone` for the real `TypeSafeClient`:
  - request path, model and bearer auth header; `choice`/`score` response parsing;
  - 5xx then 200 retried once (route succeeds); timeout and 401/404/5xx map to `SKIPPED` for both router and judge.
- Pure unit tests: `RouteDecision` (unchanged), `TypeSafeQuestionRouter` with a lambda `IntentClassifier`, judge result mapping (OK/LOW/INCONCLUSIVE/ERROR/throws) from hand-built `JevVerdict`s, `AppConfig` fail-fast, `AnswerQuality` factories.
- Rewrite `JevAnswerJudgeTest`, `TypeSafeQuestionRouterTest`, `ChatServiceJudgeTest`, `ChatServiceRoutingTest` as needed; update `RoutingEvalTest` and `JudgeEvalTest` to build a `TypeSafeClient` from env.
- Delete tests for removed classes.

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| Flags on, no api-key | App fails to start with a clear message |
| Flags off, no api-key | App starts; no client created |
| Jev timeout or connection error | SDK retries at most once within 5s total, then `SKIPPED` |
| 401/403 | No retry; `SKIPPED`; warning log with request id |
| 429 or 5xx | Retried once (statuses 408/429/5xx); then `SKIPPED` |
| Unknown intent name in response | `IllegalStateException`; route `SKIPPED` |
| Missing answer or wrong answer type | `TypeSafeMissingAnswerException`/`TypeSafeAnswerTypeException`; `SKIPPED` |
| Judge finding `ERROR` | Whole result `SKIPPED` |
| Judge criterion `INCONCLUSIVE` | Verdict not passed; `LOW` with the library's feedback |
| No rows retrieved | `EvidenceFormatter.NO_ROWS` as context; judge decides |
| Evidence truncated | `evidenceTruncated=true` on `AnswerQuality`, as today |
| Blocked or direct-model turn | Judge not run; `quality` stays null |

## Security Considerations
- Data egress is unchanged: prompts, history, graph profile, retrieved rows and answers still go to OpenRouter and TypeSafe when the flags are on. Update `AGENTS.md` to say the client is the SDK.
- The API key moves to `spring.ai.typesafe.api-key` (still `${OPENAI_API_KEY}`); never log it. Tests use WireMock and a dummy key.
- Log request ids, not request bodies.

## Backward Compatibility
- Config keys listed under "Configuration" are removed; deployments overriding them must move to `spring.ai.typesafe.*`.
- `ChatResponse.quality` JSON changes: `grounded`, `relevance`, `minScore` are gone and `feedback` is added. The bundled UI is updated; external clients reading the old fields break.
- The judge is stricter (minimum level 2, INCONCLUSIVE is LOW): more answers may show LOW.
- Spring AI moves from 2.0.0 to 2.0.1; the SDK and starter are 0.4.0 (pre-1.0).
- Gate behavior and routing JSON (`route`) are unchanged.

## Alternatives Considered
| Alternative | Why Not |
|-------------|---------|
| Keep `RestSystemOneApi` | Works and is small, but you asked for the library's documented approach |
| `JevConfidenceGate` for routing | Decides on one answer's confidence, not on non-GRAPH probability mass; adds CONFIRM/ESCALATE outcomes the gate does not have |
| Two clients (own timeouts) | Chosen instead: one shared client for simplicity; settings are global |
| `JevEvaluator` in `ChatService` | Collapses grounded/relevance into one score and does not return the per-criterion feedback |
| `JevGuardrailAdvisor` / `JevSelfRefineAdvisor` | Different features; the self-refine advisor retries, which breaks the advisory-only rule |

## Open Questions
None. The four questions from the interview were resolved during implementation:
- Starter dependencies: the starter brings `typesafe-java-sdk` transitively but not `typesafe-spring-ai`, so both are declared. WireMock is `org.wiremock:wiremock-standalone:3.13.2`.
- Auth header: `TypeSafeWireMockTest` asserts the SDK sends `Authorization: Bearer <key>` to `POST /v1/systemone` with `model` and typed `questions` in the body.
- Starter at runtime: `TypeSafeConfigTest` loads the real `application.yml` through `TypeSafeAutoConfiguration` and checks model, 3s timeout, 1 retry and 5s total.
- `conversation` field: `judgeEval` (below) shows the judge using it without tuning.

## Implementation Results
Implemented on branch `spike/typesafe-sdk`. `./gradlew test`: 72 tests, 0 failures.
- `TypeSafeWireMockTest` (11): request shape; 429 and 503 each retried once then routed; 401 not retried and failed open; timeout failed open inside the 5s budget; judge OK, LOW, INCONCLUSIVE (LOW), 5xx (SKIPPED), missing criterion answer (SKIPPED).
- `routingEval`: 0 false blocks on graph prompts, 15/15 non-graph prompts blocked at 0.9 (the previous run was 13/15).
- `judgeEval`: 6/6 golden answers OK, 10/10 bad answers LOW, 1/10 good answers LOW (the non-golden "subset answer" case: relevance was inconclusive at 0.56, under the 0.60 minimum). The hard gate passed without tuning, so the criteria wording and level 2 minimum are unchanged.
- Test detail: WireMock serves cleartext HTTP/1.1, so the test client forces `HttpClient.Version.HTTP_1_1` and sets its own read timeout; production talks HTTPS to OpenRouter through the starter's client.
- Not run: the full app against Neo4j with `app.routing.enabled` and `app.judge.enabled` true, and the UI chip in a browser.

## Task Breakdown
1. Bump the Spring AI BOM to 2.0.1; add `typesafe-bom`, the starter, `typesafe-spring-ai` and WireMock; `./gradlew test` stays green.
2. Add `spring.ai.typesafe.*` to `application.yml`; trim `AppProperties.Routing/Judge` and their defaults.
3. Add `IntentClassifier` and `TypeSafeIntentClassifier` (criteria text moved verbatim); point `TypeSafeQuestionRouter` at it and remove its retry; unit and WireMock tests.
4. New `AnswerQuality` (status, feedback, evidenceTruncated); rewrite `JevAnswerJudge` on `JevJudge` with the result mapping above; unit and WireMock tests.
5. Rewire `AppConfig` (shared client, `ObjectProvider`, fail-fast); delete `RestSystemOneApi`, `RestSystemOneClient`, `SystemOneApi`, `SystemOneClient` and their tests.
6. Update `trace.js` chip/title and cache-bust; adjust `ChatServiceJudgeTest`/`ChatServiceRoutingTest`.
7. Update `RoutingEvalTest` and `JudgeEvalTest` to the SDK client; run `./gradlew test`, then `routingEval` and `judgeEval`; if the golden gate fails, tune the Score level wording and state fields first (minimum level stays 2 unless that fails).
8. Update `AGENTS.md`, `docs/specs/SPEC_routing.md`, `docs/specs/SPEC_judge.md`, the architecture artifact, and remove the spike test and `typesafeSpike` Gradle task.
