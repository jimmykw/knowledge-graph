# Spec: Chat Quality Gate (JevJudge)

> **Superseded in part.** The Jev client layer described below (`RestSystemOneApi`, `RestSystemOneClient`, `SystemOneApi`, the `app.judge` timeout/base-url/api-key/model keys, `min-score` and the 0..1 `grounded`/`relevance` fields) was replaced by the spring-ai-typesafe library; see `SPEC_spring-ai-typesafe.md` for the current design. The goals, intents and fail-open behavior here still hold.

## Summary
Add an optional, off-by-default **answer judge** after the chat agent finishes. `ChatService.runAgent()` sends the
conversation, the rows the agent retrieved (`ToolTrace.evidence()`) and its final answer to the TypeSafe **Jev**
model (same OpenRouter System One endpoint as the intent gate) as two `score` questions: **groundedness** (is every
claim supported by the retrieved rows) and **relevance** (does the answer address what was asked). The scores are
returned synchronously in a new `quality` field on `ChatResponse` and always shown in the UI. The judge is
advisory: it never changes, retries, hides or blocks an answer, and any failure leaves the answer untouched.

## Goals
- Give every agent answer a runtime groundedness and relevance signal, without a second agent run.
- Reuse the evidence already collected per answer (`QueryEvidence`), including the "agent retrieved nothing" case.
- Zero behavior change when `app.judge.enabled=false` (the default).
- Never delay an answer by more than the judge timeout (3s) and never fail a chat call because of the judge.
- Make the signal measurable: a labeled opt-in `judgeEval` that shows how well scores separate good from bad answers.

## Non-Goals
- Retrying the agent, rewriting, annotating, or withholding low-scoring answers (flag only).
- Scoring blocked / direct-model turns from the intent gate (no graph evidence exists for them).
- Replacing or changing the `chatEval` LLM judge (`EvalJudge`, `KnowledgeGraphJudgeEvaluator`, `FactCheckingEvaluator`).
- Judging completeness (rows containing more relevant facts than the answer used), or checking expected facts.
- Persisting scores in conversation memory; changes to ingestion or the schema endpoint.

## Background
- `ChatService.chat()` (`chat/ChatService.java`): validate -> route (`QuestionRouter`) -> `runAgent()` -> `buildResponse()`.
  `runAgent()` owns a per-call `ToolTrace` (`chat/ToolTrace.java`) that records every `runReadCypher` call as a
  `QueryEvidence(cypher, rows, count, truncated, error)`; `ChatResponse.evidence()` already exposes them.
- `ChatResponse` has 11 components; construction sites: `ChatService.buildResponse`, `ChatService.blockedResponse`
  and two in `ApiExceptionHandler`. The UI reads it in `static/js/app.js` `buildTrace()` and renders it in
  `static/js/trace.js` / `index.html`.
- Offline judging exists only in tests: `chat/eval/EvalJudge` (main chat model, temperature 0, ~11 judge-side LLM
  calls per `chatEval` run) wrapped by `KnowledgeGraphJudgeEvaluator`. It uses expected facts for correctness,
  which are not available at runtime, so the runtime judge scores groundedness and relevance only.
- Intent gate (see `SPEC_routing.md`): `RestSystemOneClient` posts to `{base-url}/systemone` and today parses only
  a `choice` answer into `Map<RouteIntent, Double>`; `AppProperties.Routing` holds its config; `AppConfig` wires
  beans explicitly; the router stores no state. Jev spike numbers: 0.16-0.40s and about $0.00002 per call at ~450
  input tokens; evidence-sized inputs will cost and take more.
- `app.chat.result-row-limit` is 200, and the agent may run several queries, so unbounded evidence can be large.

## Design

### 1. Types (`chat/judge/`)
```java
public enum QualityStatus { OK, LOW, SKIPPED }

/** Scores are null when status is SKIPPED. */
public record AnswerQuality(Double grounded, Double relevance, QualityStatus status, boolean evidenceTruncated) {
    public static AnswerQuality skipped() { return new AnswerQuality(null, null, QualityStatus.SKIPPED, false); }
}

public record JudgeInput(String question, List<Message> history, List<QueryEvidence> evidence, String answer) {}

public interface AnswerJudge {
    /** @return none when the judge is disabled; SKIPPED when enabled but it could not score */
    Option<AnswerQuality> judge(JudgeInput input);
}
```
- `NoOpAnswerJudge` (enabled=false): returns `Option.none()`.
- `JevAnswerJudge` (enabled=true): builds the state, calls Jev once, applies `min-score`, owns fail-open.
- Status rule (pure function, unit-tested): `LOW` when `grounded < minScore || relevance < minScore`, else `OK`.

### 2. Shared System One transport
`RestSystemOneClient` is routing-specific. Extract a small `SystemOneApi` (`chat/routing/` or a new shared
package) that posts `{model, state, questions}` to `{base-url}/systemone` with the bearer key and timeout and
returns the parsed `answers` map; `RestSystemOneClient` (intent) and a new `JevAnswerJudge` client both use it.
Auth, URL building and `usage` debug logging live once. Routing behavior and tests are unchanged.

### 3. Questions and state
One request, two questions of type `score`: `grounded` ("Every factual claim in the assistant's answer is
supported by the retrieved rows; an answer that states facts when no rows were retrieved is not grounded; an
honest 'nothing was found' is grounded") and `relevant` ("The answer addresses the user's latest question,
taking earlier turns into account"). The state string:
```
Conversation so far:
user: ...
assistant: ...

Question: <prompt>

Retrieved rows:
[query 1] <cypher>
<rows, bounded>
[query 2] ...
(no rows were retrieved)           <- when evidence is empty, or every query returned 0 rows / an error

Answer: <answer>
```
- History is the **full stored history** (`chatMemory.get`, up to `app.chat.history-window` = 20 messages, both
  roles, including earlier blocked exchanges), read before the agent call so the current turn is not duplicated.
- Query errors from `QueryEvidence.error` are included in the rows section so the judge sees why rows are missing.
- The judge treats everything after the instructions as data; the question text, rows and answer are delimited
  and never placed in `instructions`.

### 4. Evidence budget
`EvidenceFormatter` (`@UtilityClass`, pure, unit-tested) renders evidence within `app.judge.max-evidence-chars`
(default 8000), split evenly across queries with the unused share of short queries redistributed. Rows are
serialized compactly (JSON per row), kept in order until a query's share is exhausted, then replaced by
`... N more rows not shown`. `AnswerQuality.evidenceTruncated` is true when anything was dropped, or when any
`QueryEvidence.truncated` is set, so a low score can be read as "possibly unsupported" rather than "wrong".

### 5. `ChatService` flow
```java
private ChatResponse runAgent(String prompt, String id, Option<RouteDecision> route) {
    val history = List.ofAll(chatMemory.get(id));          // before the agent call
    ... existing tool loop ...
    val quality = judge.judge(new JudgeInput(prompt, history, trace.evidence(), answer));
    return buildResponse(answer, trace, id, route.getOrNull(), quality.getOrNull());
}
```
- Only agent turns are judged. `blockedResponse` passes `quality = null`.
- Blank answers or answers when `trace.error()` is set are still judged (the empty-evidence rule covers them);
  a null/blank answer is judged as an empty answer rather than skipped.
- The judge runs after the tool loop and before the response is built; it is outside the
  `OpenCodeGoHeaders.withSession` scope because the System One call is not an OpenAI-compatible request.

### 6. Failure handling (fail open, no retry)
In `JevAnswerJudge`: one attempt, per-call timeout `app.judge.timeout` (default **3s**), no retry. Any failure
(timeout, I/O, 4xx/5xx, malformed or missing answer, score outside 0..1) logs `warn` (never the prompt, rows
or answer), returns `AnswerQuality.skipped()`, and the answer ships unchanged. Worst-case added latency equals
the timeout.

### 7. Configuration
```java
public record Judge(boolean enabled, double minScore, Duration timeout, String baseUrl, String apiKey, String model,
                    int maxEvidenceChars) {
    // defaults: enabled=false, minScore=0.7, timeout=3s, baseUrl=https://openrouter.ai/api/v1, model=jev-1.13,
    //           maxEvidenceChars=8000
}
```
```yaml
app:
  judge:
    enabled: false
    min-score: 0.7
    timeout: 3s
    base-url: https://openrouter.ai/api/v1
    api-key: ${OPENAI_API_KEY}
    model: jev-1.13        # pinned, as for routing
    max-evidence-chars: 8000
```
Own flag, independent of `app.routing.enabled`. Own base-url/key/model entries exist so the judge can later point
elsewhere, but default to the routing values. The key comes from the environment, never inlined.

### 8. Beans
`AnswerJudge` bean in `AppConfig`: `JevAnswerJudge` when `app.judge.enabled`, otherwise `NoOpAnswerJudge`.
`ChatService` takes `AnswerJudge` through its `@RequiredArgsConstructor`.

### 9. API and UI
- `ChatResponse` gains a 12th component `AnswerQuality quality` (null when the judge is disabled or the turn was
  blocked). JSON gains a nullable `quality` property.
- `trace.js`: default trace gets `quality: null`; `app.js buildTrace` copies `body.quality`.
- `index.html`: when `quality` is present, show a chip on every answer: `grounded 92% · relevant 88%`
  (`SKIPPED` shows `quality check unavailable`). The chip uses the warning style when `status === 'LOW'` and
  names the failing dimension(s); a truncated-evidence note is added to its tooltip. Colors follow the readable
  neutral/fixed-color conventions already used for the route badge (no `--pico-secondary-background`).

### 10. Testing
**Hermetic (`./gradlew test`)**
- `AnswerQualityTest`: status rule at the boundary (`minScore` exactly is OK), either dimension low -> LOW.
- `EvidenceFormatterTest`: budget split, redistribution, `... N more rows` marker, `evidenceTruncated`, empty
  evidence and error-only evidence render the "no rows" statement.
- `JevAnswerJudgeTest` with a stubbed `SystemOneApi`: SKIPPED on timeout / 4xx / missing answer / out-of-range
  score, no retry (one call), state contains history, question, rows and answer in the documented layout.
- `ChatServiceJudgeTest` with a stub `AnswerJudge`: agent turns carry `quality`, blocked turns carry null,
  judge SKIPPED or none never changes the answer.
**Opt-in `judgeEval` (real API, `-Djudge.eval=true`, new Gradle task like `routingEval`)**
- About 20 labeled cases (question, rows, answer, history) with expected verdicts: grounded/relevant answers,
  fabricated facts, off-question answers, answers from empty rows (fabricated vs. honest "nothing found"), and
  follow-ups seeded with history. Include the six `GoldenCases` as known-good answers built from fixed rows.
- Reported: score distribution for good vs bad cases and the separation between them, false-low count on the
  known-good cases (a hard assertion is **zero LOW** on those), and the best `min-score`. Used to tune the default.
- `chatEval` is unaffected.

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| Judge disabled | `NoOpAnswerJudge`; no Jev call; `quality=null`; behavior identical to today |
| Jev timeout / error / malformed answer | `SKIPPED`, warn log, answer unchanged, at most `timeout` extra latency |
| Agent retrieved nothing and answers with facts | Judge told "no rows retrieved"; low groundedness expected -> `LOW` |
| Agent retrieved nothing and says nothing was found | Grounded; relevance judged normally; `OK` possible |
| Large row sets or many queries | Bounded by `max-evidence-chars`; `evidenceTruncated=true`; marker in the state |
| `QueryEvidence.truncated` (server-side 200-row cap) | Also sets `evidenceTruncated` |
| Follow-up ("which of those...") | Full stored history in the state provides the referent |
| Blocked / direct-model turn | Not judged; `quality=null` |
| Max tool rounds exceeded or `trace.error()` set | Still judged; error text is part of the evidence section |
| Blank answer | Judged as an empty answer (relevance low), not skipped |
| Answer or rows contain prompt-injection text aimed at the judge | Worst case is an inflated or deflated advisory score; nothing is blocked or executed |
| Judge enabled, routing disabled (or vice versa) | Independent; each uses its own flag and config |
| Two flags on, blocked turn in history | Full stored history includes the blocked exchange as context for follow-ups |

## Security Considerations
- **Data egress:** with the judge on, every agent answer sends the conversation (up to 20 messages, both roles),
  the retrieved graph rows (up to the char budget) and the answer to OpenRouter and the TypeSafe-hosted model.
  Rows and answers are more sensitive than the prompts sent by the intent gate, hence its own off-by-default
  flag. Document this in `../../AGENTS.md` and the README; review both providers' data-handling terms before enabling.
- The OpenRouter key is read from the environment and never logged. Logs never contain prompt, row or answer text.
- Untrusted text (graph rows derived from documents, the model's answer) is delimited in `state` and never placed
  in `instructions`, but a classifier can still be steered. The judge is advisory UX only; read-only Cypher
  enforcement stays in `GraphTools` / `CypherExecutor` and answers are never altered by the score.
- Jev is a beta model; pin `jev-1.13` so tuned thresholds stay valid.

## Backward Compatibility
- Default config changes nothing at runtime.
- `ChatResponse` gains a 12th component: update `ChatService.buildResponse`, `blockedResponse`, both
  `ApiExceptionHandler` sites and any test constructors. JSON gains a nullable `quality` property.
- `AppProperties` gains a `Judge` component; update direct constructor callers (tests).
- `ChatService` gains an `AnswerJudge` constructor argument (`AppConfig`, `ChatServiceRoutingTest`).
- `RestSystemOneClient` is refactored onto a shared `SystemOneApi`; its public behavior is unchanged.
- No new dependency; no schema change.

## Alternatives Considered
| Alternative | Why Not |
|-------------|---------|
| Retry the agent on a low score | Doubles the tool loop on exactly the answers that are already expensive; decided to flag only first and measure |
| Replace or caveat the answer text | Changes the user-visible answer on the strength of a beta classifier's score |
| Hide / refuse low-scoring answers | Risks refusing correct answers; gate is advisory |
| Asynchronous or sampled scoring | More moving parts (second endpoint, polling) or no user-visible signal; sync cost is bounded by a 3s timeout |
| Judge with the main chat model | Spends the same latency and tokens the judge should be cheaper than, and gives no typed probability |
| Replace the `chatEval` LLM judge with Jev | Would grade the agent with an unvalidated beta classifier; left untouched, can be revisited after `judgeEval` |
| Retry once like the router | Doubles the wait for an advisory signal; one attempt with a 3s cap |
| Single flag shared with routing | Would force sending graph rows to enable the cheap intent classifier |
| Completeness scoring | Needs the judge to compare against all rows, long inputs and unreliable for a classifier |

## Open Questions
- ~~`score` response shape~~ **Resolved by the Task 1 spike (2026-10-03).** See "Spike findings" below.
- Default `min-score` (0.7) is a starting point; tune it with `judgeEval`.
- Cost and latency of an evidence-sized input (up to ~8k chars) are not yet measured; the 3s timeout may need to
  rise if typical calls exceed it.
- The decision to send the **full** stored history (not the previous exchange only) increases egress and lets
  earlier answers bias the score; revisit after `judgeEval` if relevance scoring looks skewed by long histories.
- Pricing and rate limits for `jev-1.13` on OpenRouter are still unconfirmed; this adds one call per agent answer.

## Spike findings (Task 1, done 2026-10-03)
- A `score` question is an **ordinal rating scale**: `{type: "score", instructions, criteria: [...]}`; `criteria` is a
  required array of **at least two levels ordered worst to best** (index 0 = lowest). Omitting it is a 400.
- The answer is `answers.<name> = {type, score, legend, probabilities, confidence}`. `score` is the expected level
  index (0..n-1, **not** normalized) and `probabilities` is keyed by level index. The judge normalizes:
  `score / (levels - 1)` -> 0..1 where 1 is best. A one-level scale is degenerate and must not be used.
- Both questions use three levels (worst, middle, best). Final wording: grounded = {unsupported/fabricated or facts with
  no rows; partly supported; fully supported or honestly "nothing found"}; relevant = {about something else; partly
  addresses; fully addresses or honestly states the information was not found}. The "honestly nothing found" clause in
  relevance matters: without it an honest empty answer scored 0.25.
- Calibration on 6 hand-made cases (normalized grounded / relevant): good 1.00/0.99; fabricated 0.46/0.52;
  off-question 0.00/0.00; no rows + facts 0.00/0.88; no rows + honest 1.00/1.00 (after the wording fix);
  follow-up 0.97/0.75. Latency 0.18-0.26s; an 8k-char evidence call took 0.22s, 2.3k input tokens, about $0.0001.
- Decision: use `score` (no `choice` fallback needed). The 3s timeout is comfortable.

## Implementation results (2026-10-03)
- All 11 tasks implemented. `./gradlew test` passes; the judge adds 23 hermetic tests.
- `judgeEval` (20 cases, threshold 0.7): good answers min 0.94 / mean 0.98; bad answers max 0.47 / mean 0.09;
  separation 0.47; 0 of 10 good answers flagged LOW; 10 of 10 bad answers flagged LOW. `min-score` stays 0.7.
- A first run flagged 3 of the 6 golden answers LOW. The judge was right: the fixtures had answers that claimed more than
  their name-only rows contained (an entity not in any row, a description, project-to-partner links). The fixtures were made
  realistic (multi-column rows with relationship and description columns, history that mentions the referent) and the
  threshold was not changed. Lesson: groundedness is strict about claims that go beyond the rows, which is the intent.
- Not verified: the UI chip has not been viewed in a browser, and the full app has not been run with `app.judge.enabled=true`.

## Task Breakdown
1. **Spike.** Raw call to `POST {base-url}/systemone` with two `score` questions and an evidence-sized state;
   record the answer shape, range, calibration on 5-6 hand-made cases, latency and cost. Choose `score` vs the
   two-option `choice` fallback and record the finding here.
2. Extract `SystemOneApi` from `RestSystemOneClient`; keep the routing tests green.
3. Add `QualityStatus`, `AnswerQuality`, `JudgeInput`, `AnswerJudge`, `NoOpAnswerJudge` and the status rule + test.
4. Add `EvidenceFormatter` + test.
5. Add `AppProperties.Judge` and yml defaults; update constructor callers.
6. Implement `JevAnswerJudge` (questions, state builder, timeout, fail-open) + `JevAnswerJudgeTest`.
7. Wire the `AnswerJudge` bean in `AppConfig`; inject it into `ChatService`.
8. Update `ChatService.runAgent()` to judge and attach `quality`; add the `quality` component to `ChatResponse`
   and fix all callers; add `ChatServiceJudgeTest`.
9. UI: `trace.js` default, `app.js buildTrace`, `index.html` chip, CSS (readable in light and dark).
10. `judgeEval` Gradle task, labeled cases, `JudgeEvalTest` (zero LOW on known-good cases); run it and tune `min-score`.
11. Docs: `../../AGENTS.md` (flag, egress warning, eval command) and README.
