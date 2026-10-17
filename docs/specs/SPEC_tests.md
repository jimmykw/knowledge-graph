# Spec: Test coverage for the knowledge-graph project

## Summary
Add a layered automated test suite for the code that has little or no direct coverage today: ingestion and extraction, the Neo4j access layer, the HTTP layer, the agent tool loop, the full application flow, and the browser-side UI logic. The work ships in two phases, each ending with a green build. **Phase A** covers the build setup, Tiers 1-3 and two small production fixes. **Phase B** covers the app-level WireMock tests and the JS tests. Hermetic tests (no network, no database) run in `./gradlew test`. Tests that need a real Neo4j with APOC are opt-in and run only against a dedicated throwaway instance that is marked with a sentinel node. A JaCoCo report shows what is covered. When a new test exposes a bug, small, low-risk fixes go into the same change; larger findings are recorded and the test is `@Disabled` with a reason.

## Goals
- Pin the behavior of every untested production class listed in Background, including failure paths (`failedChunks`, 400/413/503 mapping, fail-open, retry-then-fail).
- Exercise the Cypher that builds the graph (`apoc.merge.node`, and `apoc.merge.relationship` with a map as its sixth argument) against a real Neo4j, because mocks cannot catch Cypher or APOC signature errors.
- Run the real HTTP -> services pipeline end to end with only the outside world faked: LLMs through WireMock, Neo4j through stubbed beans (Phase B).
- Add tests for the UI logic in `trace.js` and `app.js` with the Node built-in test runner (Phase B).
- Close the agent's read-side exfiltration path (`LOAD CSV`, `apoc.load.*` and related procedures) with its own error message.
- Reject unreadable PDFs with a 400 and no write, so a failed parse no longer blocks the file's hash.
- Add JaCoCo reports (no gate) so coverage can be measured, hermetic and merged with the ITs.
- Document the commands, the throwaway instance and its sentinel in `../../AGENTS.md`.

## Non-Goals
- No Testcontainers, no embedded Neo4j harness, no Playwright or other browser-driven tests.
- No coverage threshold and no build failure on low coverage.
- No change to the existing real-model evals (`chatEval`, `routingEval`, `judgeEval`) or to the existing Jev WireMock and config tests, apart from shared helpers.
- No behavior changes in production code beyond those listed under "Production changes".
- No server-side Neo4j hardening (procedure allowlist, `apoc.import.file.enabled`). It's out of the app's scope; the agent guard is the only defense this spec adds.
- Tests never connect to the real graph on `bolt://localhost:7687`.

## Background
- Baseline: 72 tests, all passing (`./gradlew test`). Existing coverage: routing, judge, Jev client wiring (`TypeSafeWireMockTest`, `TypeSafeConfigTest`), `ToolTrace`, `OpenCodeGoHeaders`, `EvidenceFormatter`, `GraphCatalog`, `H2ChatMemoryRepository` (one round-trip test), `GraphTools.WRITE_PATTERN` (one regex test), eval plumbing.
- Untested or thinly tested (main code):

| Area | Classes |
|------|---------|
| Pure logic | `graph/EntityNormalizer`, `graph/CanonicalIndex`, `util/JsonRepair`, `config/AppProperties` defaults |
| HTTP | `api/GraphController`, `chat/ChatController`, `api/ApiExceptionHandler` |
| Services | `ingest/PdfIngestionService`, `extract/ExtractionPrompter`, `extract/EntityExtractionService`, `extract/RelationshipExtractionService` |
| Neo4j | `graph/Neo4jGraphWriter`, `chat/CypherExecutor`, `chat/SchemaService`, `config/Neo4jConfig` (APOC probe) |
| Agent | `chat/GraphTools`, `chat/TracingToolCallingManager`, `chat/ChatService` (validation, `clearConversation`, error resolution, round cap) |
| Store | `chat/store/H2ChatMemoryRepository` (beyond the blocked flag) |
| Frontend | `static/js/trace.js` (248 lines), `static/js/app.js` (454 lines) |

- Environment facts:
  - No Docker. Node v26.5.0 is installed (`node --test` is available). WireMock 3.13.2 is already a test dependency. PDFBox 3.0.7 is already on `testCompileClasspath` through `spring-ai-pdf-document-reader`, so no new dependency is needed.
  - Two local Neo4j 2026.06.0 Community instances run: **bolt `7687` / http 7474 is the real graph** (19 `:Document`, 3,065 nodes); **bolt `7688` / http 7475 is the throwaway** (empty, APOC 2026.06.0, credentials `neo4j` / `neo4j-admin`, the same defaults as `application.yml`).
  - `Neo4jGraphWriter.recordSchema` writes a shared singleton `:Schema {id}` node that the agent reads through `getGraphSchema`.
  - `Neo4jConfig.apocProbe` is an `ApplicationRunner` that connects at startup. Any `@SpringBootTest` that keeps the real `Driver` bean would connect to whatever `spring.neo4j.uri` says, which is the real graph by default.
  - The chat and extract models use `openai-java` over OkHttp, and `app.models.*.base-url` includes `/v1` (`https://openrouter.ai/api/v1`). A WireMock fake uses `base-url: http://localhost:<port>/v1` and stubs `POST /v1/chat/completions`. OkHttp uses HTTP/1.1 over cleartext, so the chat models work against WireMock without extra setup.
  - The Jev client built by the starter uses the JDK HTTP client. Against cleartext WireMock it attempts an HTTP/2 cleartext (h2c) upgrade, which fails. The existing `TypeSafeWireMockTest` works around this by forcing HTTP/1.1.
- **Unreadable-PDF bug:** `PdfIngestionService.readPages` (`PdfIngestionService.java:62`) turns any parse exception into an empty page list. `process` then still calls `writer.mergeDocument(hash, filename)` (`:57`). A corrupt, encrypted or non-PDF file sent as `x.pdf` is therefore recorded as a `:Document` with zero chunks and gets a 200. Every later upload of the same bytes returns `SKIPPED_DUPLICATE`.
- Guard probe (`GraphTools.WRITE_PATTERN`, case-insensitive `\b(CREATE|MERGE|SET\b|DELETE|REMOVE|DROP|CALL\s+apoc\.(merge|create))\b`):
  - **Gap:** `CALL apoc.load.json('http://x')` and `LOAD CSV FROM 'file:///etc/passwd'` are not blocked. `CypherExecutor` uses `executeRead`, which stops writes but not these reads.
  - **False positive:** `WHERE n.description CONTAINS 'create'` is blocked because the keyword appears inside a string literal.
  - None of the seven bundled skills use APOC procedures or `LOAD CSV` in their queries, so blocking them breaks no skill.

## Design

### Conventions
- Tests live in the package of the class under test, are named `<Class>Test` (ITs: `<Class>IT`), and follow `~/.claude/rules/java-code-style.md`: Lombok `val`, AssertJ only, descriptive lambda names, and Vavr in helpers where the production code uses it.
- Shared helpers go in `src/test/java/.../testsupport/`: `PdfFixtures`, `ExtractionJson`, `Neo4jItSupport` (Phase A), `FakeLlmServer` (Phase B).
- Hermetic tests must not open any socket except a WireMock port on `localhost`, and must not read credentials from the environment.

### Tier 1: pure logic and HTTP layer (hermetic, fast)

| Class | Cases |
|-------|-------|
| `EntityNormalizer` | null -> `""`, trim, lowercase with `Locale.ROOT` (e.g. `"İBM"`), idempotent |
| `CanonicalIndex` | `put` of an existing (normalized name, label) is a no-op and keeps the first id; `lookup` exact; name-only fallback when the label differs; miss -> none; `entities()` keeps insertion order; `size()` |
| `JsonRepair` | null/blank passthrough; strips ```` ``` ```` and ```` ```json ```` fences; quotes an unquoted string value; removes trailing commas before `}` and `]`; valid JSON is unchanged; documented limits (input it cannot fix stays unparseable) |
| `AppProperties` | every compact-constructor default (`maxPages` 50, `chunkTokens` 1000, chat, models, routing 0.9, judge 8000) |
| `ApiExceptionHandler` | every `@ExceptionHandler`: `InvalidPrompt` 400 and `GraphEmpty`/`Chat` 422 with a `ChatResponse` body; `InvalidFile`, `MissingServletRequestPart` 400; `MaxUploadSize` and `MaxPages` 413; `Neo4jUnavailable` 503; generic 500 with a null message -> `""` |
| `GraphTools` | blank/null Cypher -> error JSON; write attempt -> write error JSON, executor not called; external/dynamic attempt -> external error JSON, executor not called; executor failure -> error JSON; success -> `{rows,count,truncated}`; `getGraphSchema` success and failure; JSON serialization failure fallback |
| `ChatController` | `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())`: POST returns the `ChatResponse`; DELETE 204 and 404; GET schema; invalid prompt -> 400 body |
| `GraphController` | same MockMvc setup, multipart: missing part 400; empty file 400; non-PDF 400; PDF accepted by content type or by `.pdf` name; `SKIPPED_DUPLICATE` returns zeroed counts and runs no extraction; processed response sums `failedChunks` of both passes; `InvalidFileException` from ingestion -> 400; `MaxPagesExceededException` -> 413; `Neo4jUnavailableException` -> 503 |
| `ChatService` | `validate`: null/blank/over-limit -> `InvalidPromptException`; blank conversation id -> new UUID, padded id is trimmed; `clearConversation` true/false; `resolveError` precedence (tool error over max rounds); `cappedChecker` stops at `max-tool-call-rounds` and marks the trace |
| `ToolTrace` | evidence per query, `last*` accessors, blank Cypher not added to `cypherQueries`, error recorded |
| `TracingToolCallingManager` | with a stub delegate: skill call recorded; `runReadCypher` rows/count/truncated/error recorded; error JSON from the tool recorded as an error; unparsable response tolerated; `incrementRoundCount` per execution |
| `H2ChatMemoryRepository` | per-conversation isolation, message order by `seq`, `deleteByConversationId`, `findConversationIds`, append after append |
| `Neo4jConfig.apocProbe` | mocked `Driver`: success -> no throw; `ServiceUnavailableException`/`TransientException` -> warn, no throw; "apoc"/"procedure" error -> `Neo4jUnavailableException`; other error -> warn, no throw |

### Tier 2: ingestion and extraction (hermetic, fakes)
`PdfFixtures` builds PDFs with PDFBox: N text pages, a blank page, a password-encrypted PDF, and arbitrary bytes.

| Class | Cases |
|-------|-------|
| `PdfIngestionService` | processed: SHA-256 hex hash, chunks produced, `mergeDocument(hash, filename)` called. **Duplicate:** `findDocumentId` present -> `SKIPPED_DUPLICATE`, empty chunks, no merge, PDF bytes never parsed (pass garbage bytes). **Unreadable:** garbage bytes and an encrypted PDF -> `InvalidFileException`, no `mergeDocument`. Page limit: `MaxPagesExceededException`, no merge. Blank/image-only PDF that parses -> processed with zero chunks. Small `chunkTokens` -> multiple chunks. `getBytes` failure -> `IllegalStateException`. |
| `ExtractionPrompter` | fake `ChatClient`: valid JSON; invalid, then repaired; first attempt fails, second succeeds; both fail -> `Failure`; blank content; `getFormat` delegates |
| `EntityExtractionService` | fake prompter + mocked `Neo4jGraphWriter`: empty chunks -> empty result, no writes; dedupe by normalized name + label; blank name/label rejected; `Failure` chunk counted in `failedChunks` and the other chunks still processed; `countsByLabel`; `recordSchema(labels, empty)`; canonical index ids from `mergeEntities` indexes; missing id -> `IllegalStateException`; `shutdown()` terminates the pool |
| `RelationshipExtractionService` | skipped when there are no chunks or the canonical index is empty; endpoint resolved by exact name + label and by the name-only fallback; unresolved endpoint dropped (counted, not thrown); dedupe by (source id, target id, type); blank fields rejected; counts by type; `recordSchema(empty, types)`; `failedChunks` |

### Tier 3: Neo4j access
**Mocked `Driver` (hermetic):** `Neo4jGraphWriter` parameter shapes (`idx`, `name`, `nameNorm`, `label`, `description`, `srcId`/`tgtId`); empty input short-circuits; `recordSchema` is a no-op on empty sets; `findDocumentId` some/none; `readSchema` with null lists; error wrapping (`ServiceUnavailable`/`Transient` -> `Neo4jUnavailableException`, runtime errors passed through). `CypherExecutor`: row cap sets `truncated`; node/relationship/path/list/map flattening; `executeRead` with the configured timeout; failure classification.

**Real Neo4j (opt-in, `./gradlew neo4jIt`):** system property `neo4j.it=true` (same pattern as `chatEval`), `@EnabledIfSystemProperty`.
- Target: `NEO4J_IT_URI` (default `bolt://localhost:7688`), `NEO4J_IT_USER` (`neo4j`), `NEO4J_IT_PASSWORD` (`neo4j-admin`).
- **Sentinel guard.** `Neo4jItSupport` checks the target once per class, before any write. It fails, never skips, unless both of these hold:
  1. the URI port is not 7687;
  2. `MATCH (s:KgTestInstance {id: 'kg-throwaway'}) RETURN count(s)` returns exactly 1.

  It also asserts `apoc.version()`. The sentinel is created once, by hand, on 7688 (the `../../AGENTS.md` setup step). No test ever creates it.
- **Isolation.** `@BeforeEach` runs `MATCH (n) WHERE NOT n:KgTestInstance DETACH DELETE n` (only after the guard has passed), so every test starts from an empty graph plus the sentinel. Tests use realistic labels and types (`Person`, `USES`, plain SHA-256 hashes). There is no label prefixing and no `:Schema` snapshot or restore. ITs run single-threaded (`maxParallelForks = 1`).

| Area | Cases |
|------|-------|
| `Neo4jGraphWriter` | `mergeDocument` idempotent, `findDocumentId`; `mergeEntities` creates nodes with the dynamic label and `name_norm`, and a second merge returns the same ids; `mergeRelationships` with `apoc.merge.relationship` (sixth argument is a map: regression test for the known bug) and idempotence; `recordSchema` unions, `readSchema` returns it; `readTopEntityTypes`, `readTopEntityNames`, `readDocumentNames` ordering and limits |
| Injection safety | an entity named `'}) DETACH DELETE (n) //` and a label containing a backtick and a space are stored or rejected literally, never executed; the other nodes, and the sentinel, are untouched |
| `CypherExecutor` | real rows, `truncated` at the cap, query timeout (`CALL apoc.util.sleep`), a write query fails inside the read transaction, real node/relationship/path flattening |
| `SchemaService`, `Neo4jConfig` | schema read; probe against a real APOC |

### Tier 4 (Phase B): application level (hermetic `@SpringBootTest`)
- Test config: `webEnvironment = RANDOM_PORT`, in-memory H2, `spring.neo4j.uri` set to an unreachable address, and **`@MockitoBean Driver`** so the startup probe and every bean that needs a driver never connect. `Neo4jGraphWriter` and `CypherExecutor` are also `@MockitoBean`s with stubs. API keys are dummies.
- `FakeLlmServer` (WireMock) serves OpenAI-style `POST /v1/chat/completions` (extraction and chat models; `base-url: http://localhost:<port>/v1`), including scripted `tool_calls` rounds, and `POST /v1/systemone` for the Jev client.
- Scenarios:
  - **Upload:** generated PDF -> Pass 1 and Pass 2 responses -> the writer receives the expected entities and relationships -> `GraphSummaryResponse` counts. Also: duplicate; non-PDF 400; garbage bytes named `x.pdf` -> 400 with no `mergeDocument`; page limit 413; writer throws `Neo4jUnavailableException` -> 503; one failing chunk -> `failedChunks`.
  - **Chat:** round 1 `getGraphSchema`, round 2 `runReadCypher`, round 3 answer -> the response has `cypherQueries`, `evidence`, `rowCount` and skills. A second request with the same `conversationId` sends the earlier turns to the model, and every tool round's request still contains the user question (regression test for the `conversationHistoryEnabled` gotcha in `../../AGENTS.md`). The round cap returns `Exceeded max tool call rounds`. A write attempt and a `LOAD CSV` attempt return their respective error JSON to the model. `DELETE` clears the conversation.
  - **Routing and judge:** with `app.routing.enabled`, a chit-chat prompt is answered by the tool-free client and stored as blocked; with the judge on, `quality` is set. If the starter's JDK client cannot talk to cleartext WireMock inside a full context, these two cases use stubbed `QuestionRouter` / `AnswerJudge` beans instead (`TypeSafeWireMockTest` already covers the Jev client).

### Tier 5 (Phase B): frontend (Node built-in runner)
- Tests live in `src/test/js/*.test.js` and run with `node --test`. `src/test/js/alpine-stub.js` provides `document.addEventListener('alpine:init', cb)` and captures `Alpine.data(name, factory)`. Scripts are evaluated with `vm`, so no npm packages are needed. `window.hljs` is left undefined, so the grammar registration and highlighting fallbacks are exercised.
- Gradle task `jsTest` (type `Exec`, `onlyIf` node is on the `PATH`) is part of `check`, not of `test`.
- `trace.js`: `summary` (total rows across queries, singular/plural), `queryMeta`/`queryMetaEmpty`, `shownIndex` (most rows, later query wins ties, picked tab), `queriesWithRows`, old responses without `evidence`, `qualityChip`/`qualityTitle`/`qualityIsLow`, `routeBadge`, `columnList`, `cappedRows` (50), `renderCell` escaping (`<script>` and quotes escaped, objects rendered as JSON), `kgEscape`.
- `app.js` (`chatApp`): `buildTrace` mapping (including `evidence`), `chatErrorMessage` per HTTP status (including the new upload 400 message), plus the other pure helpers found when reading the file.

### Entity resolution (added with `SPEC_entity_resolution.md`)
Node ids are `elementId` strings throughout (`CanonicalIndex`, `ResolvedRelationship`, writer return values), so fixtures use ids like `"id-7"` / `"4:db:1"`.

| Class | Cases |
|-------|-------|
| `EntityNormalizer.matchKey`, `PreferredLabels`, `FulltextQueries` | suffix / punctuation / leading "the" stripping and the empty fallback; rank order, custom labels just above Concept, tie keeps the current label; Lucene escaping, lowercased operators, no query for symbol-only names |
| `ResolutionClassifier`, `ResolutionPlanner` | every classification row of the spec (sole exact same-label merge, cross-label / several exact / fuzzy-only to the LLM, in-batch groups); one update per target node, best label, aliases union, counts |
| `ResolutionPrompter` | stubbed `ChatClient`: refs only (no element ids) in the prompt; valid match with canonical name; unknown canonical name kept out; null match -> new; unoffered or missing ref -> unresolved; group linking; unparseable reply fails the batch |
| `EntityResolutionService` | mocked writer/prompter: probes, exact merge without LLM, LLM decisions, failed / throwing / incomplete batch -> unresolved, batching, schema ensured once, the lock serializes concurrent uploads and is released on failure |
| `GraphController`, `UploadFlowTest`, `app.js` | resolution flag routes pass 1 through the resolver; flow: all-new upload, exact merge with no resolution call, cross-label LLM merge, failing resolution call -> unresolved; UI message shows merged / unresolved counts |
| Cleanup (`Clustering`, `ClusterPrompter`, `CleanupPlanner`, `CleanupReports`) | exact vs fuzzy clusters and the size cap; untrusted groups go to review; automatic vs LLM entries, survivor and label rules; plan.json round trip and markdown |
| `EntityResolutionWriterIT`, `ResolveGraphIT` (real Neo4j) | schema objects online; create/update/`MENTIONS` in one transaction with label swap and alias union; exact, alias, match-key and full-text candidates with neighbours; top types/names ignore `:Entity` / `MENTIONS`; two uploads resolving against each other; plan migrates idempotently and merges nothing; apply merges nodes, relationships and mentions and removes self-loops; apply refuses without backup flag or plan and when a planned node changed or disappeared |

### Production changes (small fixes only, Phase A)
1. **External/dynamic Cypher guard.** `GraphTools` gets a second pattern, checked after `WRITE_PATTERN`:
   ```java
   private static final Pattern EXTERNAL_PATTERN = Pattern.compile(
           "\\bLOAD\\s+CSV\\b|\\bCALL\\s+apoc\\.(load|import|export|cypher|periodic)\\.",
           Pattern.CASE_INSENSITIVE);
   // -> errorJson("Error: loading external data and dynamic Cypher procedures are not allowed. "
   //        + "Query the graph with plain read-only Cypher.")
   ```
   A package-private accessor mirrors `writePattern()`, and new cases go in `GraphToolsWritePatternTest`. The separate message keeps the model from retrying the same query as a "read". This is a blocklist and remains incomplete by nature; the spec doesn't claim otherwise.
2. **Unreadable PDF -> 400.** `readPages` returns `Try<List<Document>>` instead of recovering to an empty list. In `process`, a failure becomes `InvalidFileException("PDF could not be read (corrupt, encrypted or not a PDF).")` before the page check and before `mergeDocument`, so nothing is written and the hash stays free. The parse error is still logged at `warn`. A PDF that parses but has no text (image-only, blank) still returns 200 with zero counts.
   ```java
   val pages = readPages(bytes).getOrElseThrow(e -> new InvalidFileException(UNREADABLE_PDF, e));
   ```
   `InvalidFileException` has only a `(String)` constructor today, so add `(String, Throwable)` (and to `KnowledgeGraphException` if needed).
3. Minimal visibility changes (package-private), only where a Tier 1 or 2 test needs an existing private helper. No new production dependencies.

**Reported, not fixed** (`@Disabled` test with a reason, plus an entry in Findings): the string-literal false positive (`CONTAINS 'create'` is blocked), because a scan that understands string literals is a bigger change.

### Coverage and Gradle
- `jacoco` plugin, with a `toolVersion` that supports Java 21. `test` is `finalizedBy(jacocoTestReport)` (HTML + XML, hermetic data only). No `jacocoTestCoverageVerification`.
- `neo4jIt` writes its own `build/jacoco/neo4jIt.exec`. A `jacocoMergedReport` task (type `JacocoReport`, run on demand) combines `test.exec` and `neo4jIt.exec` when both exist, writing to `build/reports/jacoco/merged/`.
- New tasks, modelled on `chatEval`: `neo4jIt` (`-Dneo4j.it=true`, forwards `NEO4J_IT_*`, `includeTestsMatching("*IT")`, `maxParallelForks = 1`), `jacocoMergedReport` and `jsTest` (Phase B).
- `../../AGENTS.md` gains: how to run each suite; the throwaway instance (bolt 7688) and the one-time step `CREATE (:KgTestInstance {id: 'kg-throwaway'})` on 7688 only; the rule that tests never target 7687; and the corrected gotcha (unreadable or encrypted PDF -> 400, image-only -> 200 with `nodeCount=0`).

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| `neo4jIt` tests run without the property | Skipped; `./gradlew test` unaffected |
| `NEO4J_IT_URI` points at 7687 | Fails immediately with a clear message; nothing is wiped or written |
| Target has no `:KgTestInstance` sentinel (e.g. a wrong port forward, or a fresh instance not yet set up) | Fails immediately: "target is not the marked throwaway instance; see AGENTS.md"; nothing is wiped |
| A test fails halfway | The next `@BeforeEach` wipe gives a clean start; the sentinel survives |
| Throwaway instance is down | `neo4jIt` fails with "cannot connect to bolt://localhost:7688"; hermetic tests unaffected |
| `jacocoMergedReport` run without `neo4jIt` data | Reports from `test.exec` only, without failing |
| `node` not installed | `jsTest` is skipped with a log line; `check` still passes |
| LLM returns invalid JSON on both attempts | `Failure` for that chunk, counted in `failedChunks`; the other chunks are processed |
| LLM never stops calling tools | The loop ends at `max-tool-call-rounds` with `Exceeded max tool call rounds` |
| Duplicate PDF | `SKIPPED_DUPLICATE`, no parse, no LLM calls, no writes |
| Garbage bytes uploaded as `x.pdf` | 400 `InvalidFileException`, no `:Document`; a later valid upload with different bytes is unaffected |
| Encrypted PDF | 400 (same message); previously 200 with 0 nodes |
| Image-only/blank PDF that parses | 200, `nodeCount=0`, `:Document` recorded (unchanged) |
| Agent sends `LOAD CSV` / `CALL apoc.load.json` | External-data error JSON; executor not called |
| Agent query has `CONTAINS 'create'` | Still blocked as a write (reported finding, `@Disabled` test) |
| Unresolvable relationship endpoint | Dropped with a warning, not an error |

## Security Considerations
- The real graph is protected by three layers. ITs default to port 7688 and refuse 7687. `Neo4jItSupport` refuses to wipe any database without the `:KgTestInstance` sentinel, which the real graph never has and no test creates. Every app-level test replaces the `Driver` bean, so no startup probe connects.
- No hermetic test uses a real API key; keys are dummies, and LLM calls go to WireMock on localhost. `OPENAI_API_KEY` is read only by the existing opt-in evals.
- `EXTERNAL_PATTERN` closes a read-side exfiltration and SSRF path (`apoc.load.*`, `LOAD CSV`, and dynamic Cypher through `apoc.cypher.*` / `apoc.periodic.*`, which could otherwise smuggle the write keywords past the guard). Injection tests assert that entity names and labels containing Cypher fragments are stored literally.
- Unreadable uploads no longer create `:Document` nodes, which removes a way to block a hash permanently with a crafted file.
- The IT password is the repo's existing dev default. It is read from the environment with that default and is never logged.

## Backward Compatibility
- Agent Cypher that uses `LOAD CSV` or the blocked APOC namespaces now gets the external-data error. No bundled skill does this.
- `POST /api/knowledge-graph` returns **400** instead of 200 for corrupt, encrypted or non-PDF bytes, and no longer records a `:Document` for them. Existing `:Document` nodes created by earlier failed parses stay. To retry such a file, delete its node by hash (noted in `../../AGENTS.md`).
- The UI shows the 400 through the existing `chatErrorMessage` / upload error path.
- Build changes: JaCoCo plugin, three new Gradle tasks, and `test` now produces a coverage report (adds a few seconds). No new runtime dependencies.
- `./gradlew test` stays hermetic and keeps working with no Neo4j, no Node and no network.

## Alternatives Considered
| Alternative | Why Not |
|-------------|---------|
| Testcontainers Neo4j | No Docker on this machine |
| Embedded Neo4j harness + APOC | Heavy, version-sensitive (the server is 2026.06), APOC loading uncertain |
| Mock the `Driver` only | Cannot catch Cypher or APOC signature errors, the bug class already recorded in `../../AGENTS.md` |
| Run ITs on the real graph with unique labels | One cleanup bug could damage the data `chatEval` depends on |
| Per-run label prefixes + `:Schema` snapshot on the throwaway | More code and unrealistic labels; with a sentinel-guarded dedicated instance, a full wipe is simpler and safer |
| Port + `:Document` hash check as the wipe guard | An empty or test-only real database would pass it; a sentinel is a positive proof |
| Add `LOAD CSV`/APOC to the `WRITE_PATTERN` alternation | The "write operations" message is wrong for reads and invites the model to retry |
| Neo4j-side procedure allowlist | Correct long term, but it's server config outside the app and the repo (non-goal) |
| Keep unreadable PDFs at 200, just skip `mergeDocument` | The caller gets no signal that the file was unusable |
| Treat encrypted PDFs separately at 200 | Both cases yield no text; one clear 400 message covers them |
| Mockito `ChatClient` for the app-level tests | Skips the OpenAI wire format, structured-output conversion and the real tool-calling loop |
| Playwright for the UI | Large dependency and browser download in a project with no JS build |
| One change for all 11 tasks | Too large to review; Phase A delivers the high-value safety coverage first |
| JaCoCo gate | A threshold would fail on config classes and real-model evals that are intentionally not unit-tested |

## Open Questions
- (Phase B) Does the starter-built Jev client work against cleartext WireMock inside a full context? If not, use the stubbed router/judge fallback described in Tier 4.

## Findings (to be filled during implementation)
- `WRITE_PATTERN` false positive on keywords inside string literals (`CONTAINS 'create'`): reported, `@Disabled` test.
- Unreadable PDF recorded as a `:Document` and the hash blocked: **fixed** in Phase A (production change 2). The bug was slightly different from the Background description: `PagePdfDocumentReader` parses in its constructor, which sat *outside* the `Try`, so garbage and encrypted bytes escaped as a `RuntimeException` (a 500 through the generic handler) rather than an empty page list. Both construction and `get()` are now inside the `Try`. A password-protected PDF needs a non-empty *user* password in the fixture; an owner-password-only PDF still opens and parses.
- `EXTERNAL_PATTERN` added to `GraphTools` (production change 1); `apoc.periodic.iterate('... CREATE ...')` gets the write message because `WRITE_PATTERN` is checked first.
- **Phase B routing/judge:** the app-level tests use mocked `QuestionRouter` / `AnswerJudge` beans (the spec's fallback); the real Jev client against cleartext WireMock inside a full context was not attempted (the open question stays open; `TypeSafeWireMockTest` covers the client).
- **Phase B history regression has teeth:** with `.conversationHistoryEnabled(false)` temporarily set in `ChatService`, `ChatFlowTest.everyToolRoundCarriesTheQuestionAndALaterTurnCarriesEarlierTurns` fails (and, without the 30s request timeout in the test client, the run hangs).
- PDF text extraction pads whitespace between words ("Page    1 mentions"), so fixtures carry single-token markers (`Marker1`) for body matching.
- UI: the zero-node upload summary said "(PDF may be image-only or encrypted)"; encrypted PDFs now get a 400, so the text is "(PDF may be image-only)".
- Package-private for tests: `ChatService.resolveError` and `ChatService.cappedChecker`, `GraphTools.externalPattern()`.
- `neo4jIt` needs `outputs.upToDateWhen { false }`, otherwise a changed `NEO4J_IT_URI` is not an input and Gradle skips the run. `jacocoMergedReport` uses `mustRunAfter` so it reports without triggering the tests.
- **Label/relationship-type injection through APOC (found by `Neo4jGraphWriterIT`, fixed).** The "dynamic labels are never interpolated" convention does not hold inside APOC: `apoc.merge.node` / `apoc.merge.relationship` build Cypher from the label/type wrapped in backticks without escaping. A label such as ``X`) WITH n MATCH (m:Person) DETACH DELETE m //`` really deleted existing `:Person` nodes. Entity *names* are safe (they travel as parameters). Fix: `CypherIdentifiers.isSafe` (non-blank, no backtick, no control characters) is applied in `EntityExtractionService`/`RelationshipExtractionService` validation (rejected with a warning like other invalid items) and as a fail-fast `IllegalArgumentException` in `Neo4jGraphWriter.mergeEntities`/`mergeRelationships` before any query runs. Odd but harmless labels (spaces, `/`, `&`, accents) are still stored literally.
- **Test-infrastructure finding:** `@EnabledIfSystemProperty` is not `@Inherited`; without it on each IT class, `./gradlew chatEval` (which has no test filter) ran the ITs and wiped the throwaway instance. Each IT class carries the annotation itself.
- A query timeout surfaces from `CypherExecutor` as a `ClientException` ("transaction ... has not completed within the timeout"), passed through to the agent, not as `Neo4jUnavailableException`.

## Task Breakdown

### Phase A
1. Build: JaCoCo (hermetic report), the `neo4jIt` task with its own `.exec`, `jacocoMergedReport`, and the `testsupport` package skeleton.
2. Tier 1 pure logic: `EntityNormalizer`, `CanonicalIndex`, `JsonRepair`, `AppProperties`.
3. Tier 1 HTTP: `ApiExceptionHandler`, `ChatController`, `GraphController` with MockMvc.
4. Tier 1 agent and store: `GraphTools`, `ChatService`, `ToolTrace`, `TracingToolCallingManager`, `H2ChatMemoryRepository`, `Neo4jConfig.apocProbe`.
5. `GraphTools.EXTERNAL_PATTERN` with its own message and tests; add the `@Disabled` string-literal false-positive test.
6. `PdfFixtures`; `PdfIngestionService` tests, including the unreadable/encrypted -> `InvalidFileException` fix (test first, then `readPages` -> `Try`); `GraphController` 400 case.
7. Tier 2 extraction: `ExtractionPrompter`, `EntityExtractionService`, `RelationshipExtractionService`.
8. Tier 3 mocked: `Neo4jGraphWriter` and `CypherExecutor` with a mocked `Driver`.
9. Tier 3 real Neo4j: `Neo4jItSupport` (port + sentinel guard, wipe); create the sentinel on 7688; writer, injection, executor and schema ITs; run `neo4jIt` and `jacocoMergedReport`.
10. `../../AGENTS.md` (suites, throwaway + sentinel setup, never 7687, corrected PDF gotcha) and Findings; final `./gradlew test` + `neo4jIt` green.

### Phase B
11. `FakeLlmServer` and test context config; upload scenarios.
12. Chat scenarios (tool rounds, history regression, round cap, guard errors, DELETE), then routing/judge scenarios (or the stub fallback).
13. Tier 5: `jsTest` task, Alpine stub, `trace.js` tests, `app.js` tests; run `jsTest`.
14. Update Findings and `../../AGENTS.md`; final `./gradlew check` (with Node) and a JaCoCo summary.
