# AGENTS.md

Compact guide for OpenCode sessions working in this repo. Read this before editing.

## Build & run

- **Build/compile:** `./gradlew compileJava` (quiet: `./gradlew compileJava --quiet`). Java 21 toolchain, Gradle Kotlin DSL.
- **Run the app:** `./gradlew bootRun`. Requires a local Neo4j with the **APOC** plugin installed and reachable at `bolt://localhost:7687`; an APOC probe at startup throws `Neo4jUnavailableException` (→ 503) if APOC is missing.
- **No test, lint, or typecheck tasks exist.** There is no `src/test` directory. The only verification is `./gradlew compileJava`.
- **No CI workflows** (`.github` absent). Branch: `user/jimmykw/dev`.
- The LLM endpoint and key are set in `src/main/resources/application.yml` via `OPENAI_API_KEY` / `NEO4J_PASSWORD` env vars (defaults are inlined). Model is `glm-5.2` through an OpenAI-compatible base URL.

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
- **Per-chunk LLM failure is skip-and-continue**, counted in `failedChunks`, not a thrown exception. Only page-limit (>50 → 413) and Neo4j-down (→ 503) are hard-fail HTTP paths via `ApiExceptionHandler`.
- **Relationship endpoint resolution is name+label, normalized** (`EntityNormalizer.normalize` = trim + lowercase ROOT). The LLM must emit endpoint names matching the canonical list; mismatches cause silent drops (logged at `warn`), not failures.

## Logging

`logging.level.com.example.knowledgegraph: DEBUG` is on by default in `application.yml`. The extraction services emit a funnel of INFO logs (candidate count → valid/rejected → created/not-created) plus per-item `Created node`/`Created relationship` (info), `Rejected`/`dropped` (warn), and `Skipped duplicate` (debug). When debugging "no relationships created", read the funnel logs in order — a gap between two INFO lines means an exception escaped the fold.

`ApiExceptionHandler` logs every handled exception (`@Slf4j`). If you add a new exception type, give it a handler that logs — the generic `Exception` handler logs with stack trace at `error`.

## Style

Java style rules live in `~/.claude/rules/java-code-style.md` (loaded via `opencode.json` `instructions`). Key points an agent gets wrong:
- Lombok `val` for all locals (never `var` or explicit types).
- `@UtilityClass` + **explicit `static`** on public methods.
- Records with `@With` for immutable data; `@RequiredArgsConstructor` + `private final` for service deps (no `@Autowired`).
- `Try.of(...)`/`Try.withResources(...)` over try/catch; `Option.of(x)` over null checks in functional pipelines.
- AssertJ (`assertThat`) only — but no tests exist yet.
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
- `SPEC_chat.md` documents the original design intent but has drifted from the code in places (e.g. it still shows the buggy `apoc.merge.relationship(..., true)` call). Trust the code over `SPEC_chat.md` when they conflict.
