# AGENTS.md

Compact guide for OpenCode sessions working in this repo. Read this before editing.

## Build & run

- **Build/compile:** `./gradlew compileJava` (quiet: `./gradlew compileJava --quiet`). Java 21 toolchain, Gradle Kotlin DSL.
- **Run the app:** `./gradlew bootRun`. Requires a local Neo4j with the **APOC** plugin installed and reachable at `bolt://localhost:7687`; an APOC probe at startup throws `Neo4jUnavailableException` (→ 503) if APOC is missing.
- **Tests:** `src/test` exists. Hermetic unit tests run under `./gradlew test`; the real-model chat eval is guarded by `@EnabledIfSystemProperty("chat.eval")` and is skipped unless that property is set. Use `./gradlew compileJava` for the fastest main-only sanity check and `./gradlew compileTestJava` to compile tests.
- **Chat eval (on-demand):** `./gradlew chatEval` runs the real-model eval against the loaded HistoryOfIBM graph. Requires Neo4j + APOC reachable at `bolt://localhost:7687`. `OPENAI_API_KEY` is optional — application.yml inlines a default key (also feeding `spring.ai.openai.api-key`, without which Spring AI's auto-configured OpenAI beans hard-fail context startup). Non-deterministic; treat as a quality gate, not a CI gate. The LLM-as-judge runs through Spring AI's `Evaluator` API (`KnowledgeGraphJudgeEvaluator` wraps the domain judge; `pass = grounded && correct`, per-dimension in metadata), and a `FactCheckingEvaluator` pass adds an extra groundedness check on non-probe cases (~11 judge-side LLM calls/run: 6 judge + 5 fact-check). Judge/fact-check context is built from `ChatResponse.evidence()` — every `runReadCypher` call's rows across all turns, one document per query (never just the last query: agents often end with a `LIMIT 1` lookup, which previously caused false rowCount-floor and groundedness failures). Invariants aggregate over turns: skills/cypher are unioned (a follow-up turn may skip the Skill tool), and `minRowCount` checks the largest single query result. Probe cases are judged with a probe-specific rubric (`evaluateProbe`): grounded = does not fabricate the asked event — refutations with explicitly-labeled general-knowledge context pass (the strict all-facts-from-rows rubric fails refutations non-deterministically). `ChatEvalClient` uses a 15-min read timeout; a full tool loop against the remote model can exceed 5 min. A preflight (Neo4j + loaded `:Document`s + one LLM ping) fails fast with a clear message before any case runs. The judge defaults to the chat model; the advisory `FactCheckingEvaluator` pass is off by default (`-Deval.factcheck=true` enables it; it never fails a case); the judge client timeout is 300s (`eval.judge.timeout`). Set `-Deval.judge.base-url`/`api-key`/`model` (forwarded by the `chatEval` task) to grade with a different one. See `docs/specs/SPEC_eval.md`.
- **Intent gate (optional; `enabled: true` in the checked-in `application.yml`, off when the property is absent):** `app.routing.enabled=true` sends each chat prompt plus the recent conversation history to TypeSafe Jev (`typesafe/jev-1.13` via OpenRouter `POST /api/v1/systemone`, `RestSystemOneClient`; same `OPENAI_API_KEY`). Non-`GRAPH` probability mass >= `block-threshold` (0.9) skips the agent/tool loop and answers from the tool-free `directChatClient` (general knowledge, not from the graph; `route` on the response and a UI badge say so; falls back to the canned `RoutingReplies` text if that call fails); any failure fails open (`SKIPPED`). **Data egress:** with it on, a graph profile in the classifier instructions (loaded `:Document` filenames, top entity labels, and the 15 most-connected entity names; loaded at startup and refreshed every 60s by `GraphCatalog`), prompts and history (incl. assistant answers built from graph rows) go to third parties. Result is in `ChatResponse.route`. Eval: `OPENAI_API_KEY=... ./gradlew routingEval` (hard gate: no graph/golden prompt BLOCKED; last run 0 false blocks, 13/15 non-graph blocked at 0.9; `-Drouting.threshold=` to tune). See `docs/specs/SPEC_routing.md`.
- **Answer judge (optional; `enabled: true` in the checked-in `application.yml`, off when the property is absent):** `app.judge.enabled=true` makes `ChatService.runAgent()` score each agent answer with TypeSafe Jev (`typesafe/jev-1.13`, OpenRouter System One via the shared `RestSystemOneApi`; same `OPENAI_API_KEY`) as two ordinal `score` questions (groundedness, relevance; `criteria` run worst to best and the answer's `score` is the expected level index, normalized to 0..1 in `JevAnswerJudge`). The result is `ChatResponse.quality` (`OK`/`LOW`/`SKIPPED`, `min-score` 0.7) and a chip in the UI. Advisory only: it never changes, retries or hides an answer; one attempt, 3s timeout, any failure => `SKIPPED`. Blocked/direct-model turns are not judged. **Data egress:** with it on, the full stored conversation history (up to 20 messages), the retrieved graph rows (up to `max-evidence-chars`, default 8000) and the answer go to OpenRouter and TypeSafe on every agent answer; it has its own flag, separate from routing. Eval: `OPENAI_API_KEY=... ./gradlew judgeEval` (hard gate: the six golden answers are never LOW or SKIPPED; reports good/bad separation; `-Djudge.threshold=` to tune). See `docs/specs/SPEC_judge.md`.
- **No CI workflows** (`.github` absent). Branch: `user/jimmykw/dev`.
- The LLM endpoint and key are set in `src/main/resources/application.yml` via `OPENAI_API_KEY` / `NEO4J_PASSWORD` env vars (defaults are inlined). Extract and chat models both use `stealth/space-bunny-alpha` through OpenRouter (OpenAI-compatible base URL); other providers/models are left as commented alternatives in `application.yml`.

## Architecture

Single Spring Boot 4.0 + Spring AI 2.0 app. One synchronous endpoint:

`POST /api/knowledge-graph` (multipart, part `file` = PDF) → `GraphSummaryResponse` (counts by label/type, failedChunks, timing).

Flow (wired in `GraphController`, beans declared explicitly in `AppConfig.java` — **no `@Component` scanning**):
1. `PdfIngestionService` — SHA-256 hash, skip duplicate `:Document`, page guard, `PagePdfDocumentReader` + `TokenTextSplitter`. Chunks stay in memory (no `:Chunk` persistence).
2. `EntityExtractionService` (Pass 1) — per-chunk parallel LLM calls, dedupe, write entities via one batched `UNWIND` + `apoc.merge.node` query, build `CanonicalIndex` from returned ids (correlated by `idx`).
3. `RelationshipExtractionService` (Pass 2) — per-chunk parallel LLM calls with the canonical entity list injected into the prompt; resolve endpoints via `CanonicalIndex.lookup`, drop on `None`, dedupe, write via one batched `UNWIND` + `apoc.merge.relationship` query. Schema labels/rel types are recorded once per pass via `recordSchema`, not per write.

Entry point: `KnowledgeGraphApplication.java` (has `@ConfigurationPropertiesScan` so `AppProperties` is picked up).

## Critical conventions

- **Dynamic Neo4j labels/relationship types are NEVER string-interpolated.** Cypher can't parameterize labels, so all dynamic writes go through **APOC procedures** (`apoc.merge.node`, `apoc.merge.relationship`) with the label/type passed as a parameter. This is the injection-safety mechanism — do not replace APOC calls with string-built Cypher.
- **APOC `apoc.merge.relationship` signature:** `apoc.merge.relationship(from, type, props, onMatchProps, to, onCreateProps)`. The 6th argument is a **map** (`{}`). Passing `true` (a boolean) throws a Cypher type error and silently kills the whole `foldLeft` because exceptions escape the merge loop. This was a real bug — keep the 6th arg a map.
- **Vavr everywhere** for control flow and collections (`io.vavr`): `Try` for fallible ops, `Option` for nullables, `io.vavr.collection.List`/`HashMap`/`HashSet`, `foldLeft` for reductions. Do not introduce `java.util.Optional` or `java.util.stream` in the pipeline. Convert Vavr `Map` → `java.util.Map` only at the controller response boundary (`.toJavaMap()`).
- **Per-chunk LLM failure is skip-and-continue**, counted in `failedChunks`, not a thrown exception. Only page-limit (> `app.max-pages` → 413; 100 in `application.yml`, falls back to 50 if unset) and Neo4j-down (→ 503) are hard-fail HTTP paths via `ApiExceptionHandler`.
- **Relationship endpoint resolution is name+label, normalized** (`EntityNormalizer.normalize` = trim + lowercase ROOT). The LLM must emit endpoint names matching the canonical list; mismatches cause silent drops (logged at `warn`), not failures.

## Logging

`logging.level.net.jimmykw.knowledgegraph: DEBUG` is on by default in `application.yml`. The extraction services emit a funnel of INFO logs (candidate count → valid/rejected → created/not-created) plus per-item `Created node`/`Created relationship` (info), `Rejected`/`dropped` (warn), and `Skipped duplicate` (debug). When debugging "no relationships created", read the funnel logs in order — a gap between two INFO lines means an exception escaped the fold.

`ApiExceptionHandler` logs every handled exception (`@Slf4j`). If you add a new exception type, give it a handler that logs — the generic `Exception` handler logs with stack trace at `error`.

## Style

Java style rules live in `~/.claude/rules/java-code-style.md` (loaded via `opencode.json` `instructions`). Key points an agent gets wrong:
- Lombok `val` for all locals (never `var` or explicit types).
- `@UtilityClass` + **explicit `static`** on public methods.
- Records with `@With` for immutable data; `@RequiredArgsConstructor` + `private final` for service deps (no `@Autowired`).
- `Try.of(...)`/`Try.withResources(...)` over try/catch; `Option.of(x)` over null checks in functional pipelines.
- AssertJ (`assertThat`) only.
- Keep lines ≤ 150 chars; break at method-chain boundaries, not inside argument lists.
- Extract named helpers when a scoped block (lambda/switch arm/catch) exceeds ~8-10 lines.

## API testing

Sample request (IntelliJ HTTP client format, also in `.idea/httpRequests/`):
```
POST http://localhost:8080/api/knowledge-graph
Content-Type: multipart/form-data; boundary=WebAppBoundary

--WebAppBoundary
Content-Disposition: form-data; name="file"; filename="procurement.pdf"

< /Users/handysmurf/dev/github/jimmykw/knowledge-graph/procurement.pdf
--WebAppBoundary--
```
A `procurement.pdf` sample file is committed at the repo root for testing.

## Gotchas

- Re-uploading the same PDF returns `SKIPPED_DUPLICATE` with zeroed counts and makes **no LLM calls**. To re-test extraction against the same file, delete the `:Document {hash}` node in Neo4j first, or change the bytes.
- Encrypted/image-only PDFs yield empty text → `200` with `nodeCount=0` (not an error).
- `docs/specs/SPEC_chat.md` documents the original design intent but has drifted from the code in places (e.g. it still shows the buggy `apoc.merge.relationship(..., true)` call). Trust the code over `docs/specs/SPEC_chat.md` when they conflict.
- The opencode.ai/zen/go endpoint **400s any request missing `x-opencode-session`**. Every `OpenAiChatModel` bean carries `OpenCodeGoHeaders.httpClientCustomizer()` (OkHttp interceptor adding the session header + `knowledge-graph/1.0` UA); `ChatService.chat()` scopes the whole tool loop in `OpenCodeGoHeaders.withSession(conversationId, …)` (ThreadLocal — safe because the sync OpenAI client runs interceptors on the calling thread). Extraction/judge calls fall back to a process-wide id. New `OpenAiChatModel` beans must add the same customizer.
- Never set `.conversationHistoryEnabled(false)` on the manual `ToolCallingAdvisor` in `ChatService` while `MessageChatMemoryAdvisor` is at its default order (+200 — upstream of the tool advisor at +300, i.e. outside the tool-call loop). With history disabled, Spring AI rebuilds every tool round after the first as `[system, lastToolResponse]`, dropping the user question and prior tool calls — the model stops querying and answers context-free. The framework only disables internal history when a memory advisor is **downstream** of the tool advisor (`DefaultChatClient.autoRegisterToolCallingAdvisor`: `conversationHistoryEnabled(!hasDownstreamMemoryAdvisor)`). This was a real regression — see the correction notes in `docs/superpowers/specs/2026-07-31-multi-turn-chat-history-design.md`.
