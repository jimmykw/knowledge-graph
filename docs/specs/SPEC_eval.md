# Spec: Adopt Spring AI Evaluation API in chatEval

> **Revision (2026-10-03):** the code has drifted from the sections below. Current behavior:
> `EvalJudge.score(EvaluationRequest)` takes one context `Document` per executed query (evidence)
> plus an "expected facts" document tagged `role=expected-facts`, which the judge uses for
> correctness only, never for grounding. Multi-turn cases pass both turns as the question. A judge
> parse failure throws instead of returning `grounded=false`. `GoldenCase` has `minExpectedMatches`.
> The fact-check is advisory and off by default (`-Deval.factcheck=true`); the judge client timeout is 300s with 1 client retry. A once-per-run preflight (Neo4j reachable + `:Document` present + one LLM ping) fails every case
> with a clear message. The judge model defaults to `app.models.chat` but can be overridden with
> `-Deval.judge.base-url / api-key / model`. The probe case also asserts read-only Cypher and `correct`.

## Summary
Refactor the chat eval harness's LLM-as-judge layer (`./gradlew chatEval`) onto the Spring AI 2.0
Model Evaluation API (`Evaluator` / `EvaluationRequest` / `EvaluationResponse`). The existing domain
judge is preserved by wrapping it in a custom `KnowledgeGraphJudgeEvaluator`; the framework's
`FactCheckingEvaluator` is added as an extra standardized groundedness check on the agent's final
answer. Deterministic field invariants, golden cases, and all main code stay untouched.

## Goals
- Express the existing judge through the standard `org.springframework.ai.evaluation.Evaluator` API.
- Add `FactCheckingEvaluator` (claim-vs-document) as an additional hallucination check.
- Zero new dependencies — evaluator classes already ship in `spring-ai-commons` /
  `spring-ai-client-chat` 2.0.0 (verified in the cached jars).
- Preserve current assertion semantics exactly (probe vs non-probe branching).
- Keep `./gradlew test` hermetic (eval stays gated behind `-Dchat.eval`).

## Non-Goals
- No `RelevancyEvaluator` adoption.
- No changes to `GoldenCases` / `GoldenCase` shape / `GoldenCasesTest`.
- No changes to `assertInvariants` (write-Cypher, skills, rowCount, entity checks).
- No changes under `../../src/main`.
- No new model config entries (fact-checker reuses `evalJudgeModel`).
- No multi-sample / majority-vote judging.

## Background
- Harness: `ChatEvalApplicationTest.java` — `@ParameterizedTest` over `GoldenCases.all()` (6 cases:
  5 QA incl. one multi-turn, 1 groundedness probe), driven through real HTTP by `ChatEvalClient`.
- Current judge: `EvalJudge.score(String question, List<String> expectedFacts, ChatResponse)`
  (EvalJudge.java:39) — one glm-5.2 call at temperature 0, `BeanOutputConverter<JudgeResult>`,
  parse-failure fallback `JudgeResult(false, false, "judge parse failed: …")`.
- `EvalJudgeConfig` builds `evalJudgeModel` from `AppProperties.models().chat()` (glm-5.2,
  OpenAI-compatible endpoint).
- Framework API (verified via bytecode): `EvaluationRequest(userText, List<Document> dataList,
  responseContent)`; `EvaluationResponse(pass, score, feedback, metadata)`;
  `FactCheckingEvaluator.Builder.evaluationPrompt(String)`; pass = model answer
  `equalsIgnoreCase("yes")`; prompt must carry `{document}`/`{claim}`.
- Original design: `../superpowers/specs/2026-08-20-chat-eval-design.md`.

## Design

### 1. `KnowledgeGraphJudgeEvaluator` (new, `chat/eval/`)
```java
public class KnowledgeGraphJudgeEvaluator implements Evaluator {
    private final EvalJudge judge;

    public EvaluationResponse evaluate(EvaluationRequest request) {
        val result = judge.score(request);
        return new EvaluationResponse(result.grounded() && result.correct(),
                result.rationale(),
                Map.of("grounded", result.grounded(), "correct", result.correct()));
    }
}
```
- `pass = grounded && correct`; `feedback = rationale`; metadata keys are `"grounded"` / `"correct"`.
- Judge parse failure flows through unchanged: `EvalJudge`'s existing fallback
  `JudgeResult(false, false, "judge parse failed: …")` → `pass=false`, both metadata false, feedback
  carries the raw content → case fails exactly as today.

### 2. `EvalJudge` reshaped to `EvaluationRequest`
- New signature: `JudgeResult score(EvaluationRequest request)`.
- question = `request.getUserText()`; context = joined text of `request.getDataList()` documents;
  answer = `request.getResponseContent()`.
- SYSTEM prompt unchanged — the test serializes the context `Document` with the same labeled sections
  (`Expected facts:` / `Cypher queries:` / `Rows:`) the prompt already references, so judge behavior
  doesn't drift.
- Keep the `Try`-wrapped `BeanOutputConverter` fallback.

### 3. Context packing (in the test)
```java
val request = new EvaluationRequest(question,
        java.util.List.of(judgeContextDocument(goldenCase, response)),
        response.answer());
```
- `question` = `followupPrompt` if present else `prompt` (unchanged from current behavior).
- One labeled `Document` carrying expected facts + `cypherQueries` + `results` — the same content as
  today's judge userText block.

### 4. `FactCheckingEvaluator` bean with strict prompt
```java
@Bean
FactCheckingEvaluator factCheckingEvaluator(OpenAiChatModel evalJudgeModel) {
    return FactCheckingEvaluator.builder(ChatClient.builder(evalJudgeModel))
            .evaluationPrompt(STRICT_FACTCHECK_PROMPT)  // {document}/{claim}, "exactly one word: yes or no"
            .build();
}
```
Custom prompt mitigates glm-5.2 chattiness (pass parses only an exact `yes`).

### 5. Test changes (`ChatEvalApplicationTest`)
- Inject `KnowledgeGraphJudgeEvaluator` + `FactCheckingEvaluator` instead of `EvalJudge`.
- `assertJudge`: evaluate via request; assert `grounded` / `correct` from `getMetadata()` with
  unchanged probe/non-probe branching; log `getFeedback()` as rationale.
- New `assertFactCheck`: skipped for the probe; document = `cypherQueries` + `results` (+ truncation
  note when `response.truncated()`); claim = `response.answer()`; hard
  `assertThat(result.isPass()).isTrue()` with feedback in the message.
- Per-case order: invariants → judge → fact-check. Multi-turn: final turn only.

### 6. Docs
- `2026-08-20-chat-eval-design.md`: add a superseded-in-part status note pointing to `SPEC_eval.md`.
- `../../AGENTS.md`: chatEval bullet gains the fact-check stage and updated judge-side call count
  (~11/run: 6 judge + 5 fact-check).

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| Judge JSON parse failure | `pass=false`, metadata grounded/correct=false, feedback carries raw content → case fails (unchanged) |
| Judge/fact-check transport error | Exception propagates → case fails with the underlying error (unchanged from current judge behavior) |
| `truncated=true` | Fact-check document carries a "rows truncated to N; judge only visible rows" note |
| Probe case 6 | Judge asserts grounded only (via metadata); fact-check skipped (rows may legitimately be empty) |
| Multi-turn case 5 | Only the followup response is judged + fact-checked |
| Chatty fact-check answer ("Yes, because…") | Fails by design of the strict prompt; actionable flake signal per the original design philosophy |
| Empty rows on non-probe | Impossible at fact-check time — `assertInvariants` (cypherQueries non-empty, rowCount ≥ 1) runs first |

## Security Considerations
Test-only change; no new endpoints or secrets. Judge + fact-check send question / answer / rows to the
external LLM — already accepted by the original design. Reuses the existing `app.models.chat` config
(env var `OPENAI_API_KEY`).

## Backward Compatibility
- `./gradlew test` unchanged (eval auto-skips when `chat.eval` is unset); `GoldenCasesTest` unaffected.
- `chatEval` gradle task unchanged.
- No production behavior change.
- Cost: +5 LLM calls per full run (fact-check on cases 1–5); roughly one extra chat round per
  non-probe case in runtime.

## Alternatives Considered
| Alternative | Why Not |
|-------------|---------|
| Full replacement with `RelevancyEvaluator` + `FactCheckingEvaluator` | 2× judge calls for the same dimensions, loses the combined structured verdict + rationale; probe semantics fit neither evaluator |
| `RelevancyEvaluator` for the `correct` dimension | Expected-facts-as-context stretches RAG relevancy semantics; the custom judge already covers it in one call |
| Keep as-is | Forgoes framework alignment (the stated goal) |
| Multiple metadata-keyed `Document`s | No consumer reads them separately; adds reassembly complexity |
| Default `FactCheckingEvaluator` prompt | Chattiness risks parse failures; strict single-word prompt is cheap insurance |
| Soft-log fact-check | Makes the check decorative; flakes are actionable signals per the original design philosophy |
| Separate `app.models.eval` config for the fact-checker | No need for a distinct model; `evalJudgeModel` reuse matches the original judge design |

## Open Questions
None.

## Task Breakdown
1. Refactor `EvalJudge.score` to take `EvaluationRequest` (keep SYSTEM prompt, converter, `Try`
   fallback).
2. Add `KnowledgeGraphJudgeEvaluator implements Evaluator` (pass = grounded && correct, metadata per
   dimension).
3. `EvalJudgeConfig`: add `KnowledgeGraphJudgeEvaluator` bean + `FactCheckingEvaluator` bean with the
   strict single-word prompt over `ChatClient.builder(evalJudgeModel)`.
4. Test: swap injection; build `EvaluationRequest` via the `judgeContextDocument` helper; `assertJudge`
   reads metadata with unchanged branching.
5. Test: add `assertFactCheck` (skip probe, truncation note, hard assert with feedback) after
   `assertJudge`.
6. Docs: supersede note in `2026-08-20-chat-eval-design.md`; update the `../../AGENTS.md` chatEval bullet.
7. Verify: `./gradlew compileTestJava`, `./gradlew test`; full `./gradlew chatEval` remains a manual
   gate (needs Neo4j + APOC + LLM).
