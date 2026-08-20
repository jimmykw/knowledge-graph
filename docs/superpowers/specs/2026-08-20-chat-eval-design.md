# Chat Q&A Eval Harness — Design

**Date:** 2026-08-20
**Status:** Approved (brainstorming complete)
**Approach:** Real-model end-to-end eval of `POST /api/knowledge-graph/chat` against the assumed-loaded HistoryOfIBM graph, scored by deterministic field invariants **plus** an LLM-as-judge for answer groundedness/correctness.

## Goal

Give the project a repeatable, on-demand way to measure whether the chat agent
answers natural-language questions over the knowledge graph correctly. The agent
is a Spring AI `ChatClient` (glm-5.2) that calls `getGraphSchema` /
`runReadCypher` (read-only, write-blocked) plus a `skills` tool, with H2-backed
multi-turn memory. A golden set of (question → expectations) is run against the
real endpoint; each case is scored on tool behavior invariants *and* on whether
the final answer is grounded and correct, the latter decided by a second
glm-5.2 call (the judge) at temperature 0.

## Constraints (from clarifying questions)

- **What's in the loop:** the real glm-5.2 model via the live
  `https://opencode.ai/zen/go/v1` endpoint (`OPENAI_API_KEY`). No stubbed model
  — this measures actual agent reasoning, Cypher generation, and answer
  synthesis. Non-deterministic, slow, costs tokens.
- **Fixture world:** the real HistoryOfIBM.pdf knowledge graph is **assumed
  already loaded** in the local Neo4j at `bolt://localhost:7687` (the same graph
  the captured `.idea/httpRequests` runs target). No fixture seeding, no
  snapshot/restore in this cut. Golden assertions are grounded on stable
  landmark entities that the extraction pipeline reliably captures (FORTRAN,
  System/360, SAGE, Universal Product Code, OS/2, Selectric, magnetic stripe),
  which are robust to per-run phrasing variance.
- **Approach:** HTTP end-to-end + deterministic field assertions + LLM-as-judge
  (user-chosen option C). The judge is in addition to, not instead of, the field
  invariants.

## Architecture & Components

New test sources under `src/test/` (the repo currently has none — this bootstraps
the test tree). Package layout chosen so the read-only Cypher assertion reuses
the production write-block regex without drift:

- `src/test/java/net/jimmykw/knowledgegraph/chat/ChatEvalApplicationTest.java`
  — package `...chat` (same package as `GraphTools`, so it can read the
  package-private `writePattern()` accessor). `@SpringBootTest(webEnvironment =
  RANDOM_PORT)` + `@EnabledIfSystemProperty(named = "chat.eval", matches =
  "true")`. A `@ParameterizedTest` iterates `GoldenCases.all()`.
- `src/test/java/net/jimmykw/knowledgegraph/chat/ChatEvalClient.java` — thin
  `TestRestTemplate` wrapper that posts a `ChatRequest` JSON to
  `/api/knowledge-graph/chat` and returns the deserialized `ChatResponse`.
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/GoldenCase.java` — record
  `(String prompt, String followupPrompt, String conversationId, List<String> expectedSkills,
  List<String> expectedEntities, int minRowCount, boolean groundednessProbe)`. When
  `followupPrompt` is non-null, the test issues `prompt` then `followupPrompt` against the same
  `conversationId` and asserts against the **followup** `ChatResponse`. `expectedSkills` empty =
  "any skill loaded" (pass if `skillsExecuted` non-empty); non-empty = pass if it contains ≥1 entry.
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/GoldenCases.java` —
  plain factory exposing `static List<GoldenCase> all()` (called directly by the
  parameterized test; no Spring bean needed).
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/EvalJudge.java` —
  `JudgeResult score(String question, List<String> expectedFacts, ChatResponse)`.
  Builds a judge prompt, calls the `evalJudgeClient` once at temperature 0, parses
  the reply with a `BeanOutputConverter<JudgeResult>`.
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/EvalJudgeConfig.java` —
  `@TestConfiguration` declaring `@Bean ChatClient evalJudgeClient(OpenAiChatModel
  chatChatModel)` (reuses the existing chat model bean; per-request
  `OpenAiChatOptions.temperature(0.0)` overrides the baked-in 0.2).
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/JudgeResult.java` — record
  `(boolean grounded, boolean correct, int score, String rationale)`.

The eval reuses the full main `application.yml`: the same model endpoint/key, the
same Neo4j, the same `.claude/skills` classpath resource (skills load identically
in the test context), and the same `MessageChatMemoryAdvisor` wiring. A test
`src/test/resources/application.yml` overrides only the H2 datasource to
`jdbc:h2:file:./data/chatdb-eval` so eval runs do not clobber the dev chat
history at `./data/chatdb`, and keeps `spring.sql.init.mode: always` so
`schema.sql` creates the `chat_messages` table in the eval DB.

## Golden Case Set

Drawn from the captured `.idea/httpRequests/http-requests-log.http` questions.
`expectedEntities` are matched case-insensitively as substrings of `answer`; a
case passes the entity check if **at least one** expected landmark appears (the
stable-landmarks approach tolerates run-to-run extraction phrasing variance).
`expectedSkills` empty = "any skill loaded"; non-empty = "contains ≥1 of these".
All `minRowCount` values are **generous starting floors** to calibrate from the
first real run — raise or lower to match observed output without weakening the
invariant's intent.

| # | Prompt (turn b = followup) | convId | expectedSkills | expectedEntities (≥1 in answer) | minRowCount | probe |
|---|---|---|---|---|---|---|
| 1 | "What did IBM create?" | new | `neighborhood-exploration` | FORTRAN, System/360, SAGE, Universal Product Code, OS/2, Selectric | 30 | no |
| 2 | "What did IBM invent?" | new | `neighborhood-exploration` | FORTRAN, magnetic stripe, RISC architecture, UPC | 5 | no |
| 3 | "Tell me how Microsoft affected OS/2." | new | _(any)_ | Microsoft, OS/2 | 1 | no |
| 4 | "Tell me any information about hard disks." | new | _(any)_ | "hard disk" (or "disk") | 1 | no |
| 5 | (a) "What did IBM create?" → (b) "which of those were collaborations with other companies?" | shared, unique per run | _(any)_ | (b) NSFNet, University of Michigan, MCI, Prodigy, Sears | (b) 1 | no |
| 6 | "When did IBM acquire Google?" | new | _(any)_ | — | 0 | **yes** |

Case 6 is the groundedness probe: the graph contains no IBM-acquires-Google
fact. The agent must **not** fabricate a date. The judge must return
`grounded=false` if it invents one; `score==0` then fails the case. `minRowCount=0`
is allowed here (the agent may legitimately find nothing).

Multi-turn case 5 uses a run-unique `conversationId` (`"eval-multiturn-" + UUID`)
so it never collides with prior runs' persisted history; both turns are issued
against the same id so the `MessageChatMemoryAdvisor` carries the first answer
into the second prompt.

## Scoring (per case)

Scoring branches on the `groundednessProbe` flag. The judge's `grounded` field
means "the answer states only facts supported by the tool results — no
fabrication." So for the probe (case 6), a **good** agent that reports "no
record of IBM acquiring Google" returns `grounded=true`; a **fabricating** agent
that invents a date returns `grounded=false`. The probe's pass condition is
therefore `grounded==true` (the non-fabricating outcome), **not** the inverse.

**Non-probe cases (1–5):** pass iff **all** field invariants hold **and**
`judge.score == 1`.

Deterministic field invariants (AssertJ on `ChatResponse`):

- `error == null`
- `cypherQueries` is non-empty (the agent actually queried the graph)
- **every** entry in `cypherQueries` does **not** match
  `GraphTools.writePattern()` — i.e. the agent never attempted a write op
  (reuses the production write-block regex, no drift)
- `skillsExecuted` is non-empty; if `expectedSkills` is non-empty, it must
  contain ≥1 of them
- `rowCount >= minRowCount`
- `answer` (case-insensitive) contains at least one `expectedEntities` substring
  (skipped when `expectedEntities` is empty)

**Probe case (6):** pass iff `error == null` **and** `judge.grounded == true`
(the agent's answer is supported by — consistent with — the tool results, i.e.
it did not invent an IBM-acquires-Google fact). The cypher/rowCount/skill/entity
invariants are **not** applied to the probe: the agent may legitimately query
and find nothing (`rowCount == 0`) or decline to query and say so; either is
acceptable as long as it does not fabricate.

Judge (`EvalJudge`): a single glm-5.2 call, temperature 0, with
`BeanOutputConverter<JudgeResult>` parsing
`{ "grounded": bool, "correct": bool, "score": int, "rationale": str }`. The
judge prompt supplies the question, the expected landmark facts (empty for the
probe), the agent's `answer`, and a compact serialization of `cypherQueries` +
`results`. `score` is `1` iff `grounded && correct`; `0` otherwise. For the
probe, only `grounded` is consulted (there are no expected facts to be
"correct" about, so `correct` is not meaningful there).

Each case is a `@ParameterizedTest` argument. On failure the assertion message
includes the failed invariant name and the judge `rationale`. Cases are
independent; one failing case does not abort the others.

## Runnability

- `@EnabledIfSystemProperty(named = "chat.eval", matches = "true")` is evaluated
  before the Spring context boots, so `./gradlew test` (property unset) **skips
  every eval case without booting Spring, touching Neo4j, or spending tokens.**
  This preserves the repo's compile-only verification convention: a plain
  `./gradlew test` passes trivially.
- New Gradle task `chatEval` (group `verification`) runs the `Test` task with
  `systemProperty("chat.eval", "true")`. It still needs `OPENAI_API_KEY` in the
  environment and Neo4j reachable at `bolt://localhost:7687` at runtime; without
  them, the disabled-guard-then-runtime-failure is the user's signal that the
  stack isn't up.

```kotlin
tasks.register<org.gradle.api.tasks.testing.Test>("chatEval") {
    group = "verification"
    systemProperty("chat.eval", "true")
}
```

### Non-determinism — honest expectations

This is an **on-demand quality gate**, not a CI hard-gate. Real-model + judge
calls can flake. Mitigations baked in: judge at temperature 0; entity checks use
stable landmarks + ≥1-of-N matching; `rowCount` thresholds are generous floors.
A flaky failure means either a real chat regression or an over-strict assertion
to relax — both are actionable signals, not noise to be retried silently. Cases
are independent; one failing case does not abort the others (JUnit reports per
parameter).

## Changes to Existing Files

| File | Change |
|---|---|
| `GraphTools.java` | Add package-private `static Pattern writePattern() { return WRITE_PATTERN; }` so the eval asserts read-only-ness against the **same** regex the production write-block uses (no drift). `WRITE_PATTERN` itself stays `private`. |
| `build.gradle.kts` | Register the `chatEval` `Test` task (snippet above). No new dependencies — `spring-boot-starter-test` (JUnit 5 + AssertJ + `TestRestTemplate`) is already on `testImplementation`. |
| `AGENTS.md` | The "No test, lint, or typecheck tasks exist" / "only verification is `./gradlew compileJava`" lines go stale. Update Build & run: `src/test` now exists; `./gradlew test` runs (eval auto-skipped when `chat.eval` unset); `./gradlew chatEval` runs the eval on-demand (needs live endpoint + key + Neo4j). Keep compileJava as the fast sanity check. |

## New Files

- `src/test/java/net/jimmykw/knowledgegraph/chat/ChatEvalApplicationTest.java`
- `src/test/java/net/jimmykw/knowledgegraph/chat/ChatEvalClient.java`
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/GoldenCase.java`
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/GoldenCases.java`
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/EvalJudge.java`
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/EvalJudgeConfig.java`
- `src/test/java/net/jimmykw/knowledgegraph/chat/eval/JudgeResult.java`
- `src/test/resources/application.yml` (H2 eval DB override only)

## Testing & Verification

1. `./gradlew compileJava` — main still compiles (the only production change is
   the `writePattern()` accessor on `GraphTools`).
2. `./gradlew compileTestJava` — new test sources compile against main.
3. `./gradlew test` — eval cases skip (no `chat.eval` property); the suite passes
   trivially and touches nothing external.
4. `./gradlew chatEval` with the HistoryOfIBM graph loaded + `OPENAI_API_KEY` set
   + Neo4j up — runs the 6 golden cases; AssertJ reports per-case pass/fail with
   judge rationale on failures.

## Non-Goals / Follow-ups

- No fixture seeding or snapshot/restore (graph assumed loaded). Upgradable to
  snapshot+restore if reproducibility-without-preloading becomes a need.
- No stubbed-model contract layer (option B). Could be added later as a separate,
  always-on test set that guards agent-wiring regressions without the live stack.
- No streaming/SSE, no conversation-listing tests.
- The judge is single-shot at temperature 0; no majority-vote / multi-sample
  judging in this cut (a follow-up if variance proves too high).
