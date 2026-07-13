# Spec: Spring AI PDF → Neo4j Knowledge Graph (glm-5.2)

## Summary
A standalone Spring Boot + Spring AI application that accepts a PDF via a synchronous
multipart HTTP endpoint, extracts text from it, and builds a free-form knowledge graph
(entities + relationships, with LLM-chosen labels and relationship types) in a local Neo4j
instance. Entity/relationship extraction is performed by the **glm-5.2** model, reached
through Z.ai's OpenAI-compatible Chat Completions API via Spring AI's `OpenAiChatModel`.
A two-pass extraction (entities first, then relationships grounded against the canonical
entity list) and APOC-parameterized Cypher writes keep the graph clean and Cypher-injection-safe.

## Goals
- Accept a single PDF via `POST /api/knowledge-graph` (multipart) and return a graph summary synchronously.
- Parse PDF text with Spring AI `PagePdfDocumentReader` + `TokenTextSplitter`.
- Extract free-form entities (node labels) and relationships (rel types) decided by glm-5.2.
- Store a `:Document` provenance node keyed by SHA-256 and skip re-processing identical uploads.
- Deduplicate entities via Cypher MERGE on `(label, normalized name)` using APOC.
- Discover cross-chunk relationships via a second pass grounded per source chunk.
- Return a full summary: counts by label/type, failed-chunk count, processing time.
- Keep the app safe: localhost-only, parameterized APOC writes, page-count guardrail.

## Non-Goals
- No chunk persistence (`:Chunk` nodes are NOT stored).
- No authentication / authorization on the endpoint (localhost-only).
- No async job queue or SSE streaming.
- No configurable/fixed schema — labels and types are free-form, LLM-decided.
- No multi-document batching in one request.
- No warning about content leaving to an external LLM provider (deemed acceptable for a local tool).
- No Spring Data Neo4j ODM (incompatible with runtime-decided labels).

## Background
The repository (`/Users/handysmurf/dev/github/jimmykw/knowledge-graph`) is greenfield:
only `README.md` and an IntelliJ project exist; `git log` shows a single "first commit" on
branch `user/jimmykw/dev`. There are no existing classes, build files, or configuration to
preserve. The app is scaffolded from scratch.

Key external dependencies and versions:
- Spring Boot 4.0.x, Spring AI 2.0.0, Java 21, Gradle (Kotlin DSL for the build script only —
  no Kotlin in application code). Spring AI 2.0 **requires** Spring Boot 4.0.x/4.1.x
  (it does not run on 3.x).
- **Vavr** `io.vavr:vavr:1.0.1` (stable; 1.0.0 GA released Feb 2026) — used for functional
  error handling (`Try`, `Option`, `Either`) and immutable persistent collections
  (`List`, `Map`, `Set`, `Tuple2`) throughout the pipeline. See Design → Vavr usage.
- Spring AI BOM `org.springframework.ai:spring-ai-bom:2.0.0`; chat starter
  `org.springframework.ai:spring-ai-starter-model-openai` (artifact name changed in
  2.0 from the old `...-spring-boot-starter` form) pointed at Z.ai's OpenAI-compatible
  Chat Completions endpoint via `spring.ai.openai.base-url`; model id `glm-5.2`.
  API key via config/env.
- PDF reader module `org.springframework.ai:spring-ai-pdf-document-reader` (provides
  `PagePdfDocumentReader` + `PdfDocumentReaderConfig` in 2.0).
- Neo4j Java driver 5.x talking to a local instance (`bolt://localhost:7687`).
- Neo4j **APOC** library installed on the local instance (required for parameterized
  dynamic label/type writes — see Design → Persistence).

## Design

### Project layout
```
src/main/java/com/example/knowledgegraph/
  KnowledgeGraphApplication.java
  config/AppProperties.java
  config/ChatClientConfig.java
  config/Neo4jConfig.java
  api/GraphController.java
  api/GraphSummaryResponse.java          // Java record
  api/ApiExceptionHandler.java
  ingest/PdfIngestionService.java
  extract/ExtractionRecords.java         // Java records: ExtractionResult, ExtractedEntity, ExtractedRelationship
  extract/EntityExtractionService.java   // pass 1
  extract/RelationshipExtractionService.java // pass 2
  graph/Neo4jGraphWriter.java
  graph/EntityNormalizer.java
  graph/CanonicalIndex.java              // Vavr Map<Tuple2<String,String>, Long nodeId> + lookups
```
All application code is Java 21 (records, pattern matching, virtual threads available).
Vavr is used for control flow and immutable collections — no Kotlin.

### Configuration (`AppProperties`, bound to `application.yml`)
```yaml
app:
  max-pages: 50                 # reject with 413 above this
  chunk-tokens: 1000            # TokenTextSplitter chunk size (Spring AI 2.0 default is 800)
  pool-size: 4                  # bounded parallel LLM calls
spring:
  ai:
    model:
      chat: openai              # Spring AI 2.0 top-level model toggle (default openai)
    openai:
      base-url: ${ZAI_BASE_URL}          # Z.ai OpenAI-compatible endpoint (configured, not hardcoded)
      api-key: ${ZAI_API_KEY}
      chat:
        model: glm-5.2
        temperature: 0.2                 # low temp for deterministic extraction
server:
  address: 127.0.0.1                     # localhost-only
spring.neo4j:
  uri: bolt://localhost:7687
  authentication:
    username: neo4j
    password: ${NEO4J_PASSWORD}
```

### API contract
`POST /api/knowledge-graph` (multipart/form-data), part `file` = PDF.

Response `200` — `GraphSummaryResponse` (Java record):
```java
public record GraphSummaryResponse(
    String documentId,            // internal id (SHA-256)
    String documentHash,          // SHA-256 of bytes
    String status,                // PROCESSED | SKIPPED_DUPLICATE
    int nodeCount,
    int relationshipCount,
    Map<String, Integer> countsByLabel,
    Map<String, Integer> countsByType,
    int failedChunks,
    long processingTimeMs
) {}
```
The `countsByLabel`/`countsByType` maps are converted from the internal Vavr `Map` to plain
`java.util.Map` at the controller boundary for JSON serialization.
- `413` if PDF page count > `app.max-pages` (checked before any LLM call).
- `400` on missing/empty/non-PDF part.
- `200` with `status=SKIPPED_DUPLICATE` and zeroed counts when the hash already exists.

### Ingestion (`PdfIngestionService`)
1. Read bytes, compute `SHA-256`.
2. Check Neo4j for an existing `:Document {hash}`; if present, return `SKIPPED_DUPLICATE`.
3. Load with `PagePdfDocumentReader` (configured via `PdfDocumentReaderConfig.builder()`
   with `pagesPerDocument(1)`); reject if pages > `max-pages` (throws
   `MaxPagesExceededException` → `413`).
4. Split with `TokenTextSplitter.builder().withChunkSize(chunk-tokens).build()` (no
   overlap). Note: in Spring AI 2.0, chunks at or below the chunk size are no longer
   split at punctuation — small/short pages stay intact, which is fine here.
5. Keep chunks in memory (passed to both passes); do NOT persist `:Chunk` nodes.

### Structured output (Spring AI 2.0 `BeanOutputConverter`)
Java records (Java 21):
```java
public record ExtractionResult(
    List<ExtractedEntity> entities,
    List<ExtractedRelationship> relationships
) {}
public record ExtractedEntity(String name, String label, String description) {}
public record ExtractedRelationship(
    String sourceName, String sourceLabel,
    String targetName, String targetLabel,
    String type, String description
) {}
```
A `new BeanOutputConverter<>(ExtractionResult.class)` formats the schema into the prompt
and parses the JSON response back into the record. On parse failure or missing fields,
retry the call **once**; if still failing, the chunk is skipped and counted in `failedChunks`.

Spring AI 2.0 additionally supports OpenAI's native Structured Outputs via
`OpenAiChatOptions.responseFormat(new ResponseFormat(JSON_SCHEMA, jsonSchema))`, where
`jsonSchema` can be produced by `outputConverter.getJsonSchema()`. This is **optional
hardening**: Z.ai's glm-5.2 OpenAI-compatibility for strict `JSON_SCHEMA` mode is not
guaranteed, so the primary path is `BeanOutputConverter` as a prompt-schema generator +
parser (model-agnostic). If Z.ai supports `response-format.type=JSON_OBJECT`, enabling it
is a low-risk improvement to output validity.

### Vavr usage
The pipeline is written in a functional style with Vavr; exceptions are reserved for the
two hard-fail paths (page limit → `413`, Neo4j down → `503`) which the
`ApiExceptionHandler` maps to HTTP statuses. Everything else is `Try`/`Option`/`Either`.

- **LLM call per chunk** returns `Try<ExtractionResult>`. The one-retry is
  `Try.of(this::callLlm).recoverWith(t -> Try.of(this::callLlm))` — a `Failure` after the
  retry is the skip signal, not a thrown exception.
- **Canonical index** (`CanonicalIndex`) wraps a Vavr
  `HashMap<Tuple2<String, String>, Long>` keyed by `(nameNorm, label) → Neo4j node id`.
  `put` returns a new immutable map (no mutation); `lookup(name, label)` returns
  `Option<Long>`.
- **Relationship endpoint resolution** uses `Option`:
  ```java
  Option<Long> src = canonicalIndex.lookup(r.sourceName(), r.sourceLabel());
  Option<Long> tgt = canonicalIndex.lookup(r.targetName(), r.targetLabel());
  Try<ResolvedRel> resolved = src.flatMap(s -> tgt.map(t -> new ResolvedRel(s, t, r)))
      .toTry(() -> new UnresolvedEndpointException(r));
  ```
  Unresolved endpoints (`None`) → the `Try` is a `Failure` → dropped and not counted as a
  failed chunk (only LLM/parse failures count toward `failedChunks`).
- **Counts** are accumulated as Vavr `HashMap<String, Integer>` via
  `counts.put(label, counts.getOrElse(label, 0) + 1)`; folded over the `Try`-success
  results with `foldLeft`. Converted to `java.util.Map` for the response at the boundary.
- **Chunk list** held as `io.vavr.collection.List<Document>` (immutable) shared by both passes.
- **Bounded parallelism** uses `CompletableFuture<Try<...>>` submitted to a fixed
  `ExecutorService` of size `app.pool-size`; results collected via `CompletableFuture.allOf`
  and mapped to `List<Try<...>>` for the fold. Vavr `Future` is intentionally avoided to keep
  Spring/JDK concurrency primitives primary.

### Pass 1 — entity extraction (per chunk, bounded parallel)
- For each chunk, prompt glm-5.2 to return an `ExtractionResult` wrapped in `Try`. Pass 1
  keeps **entities** only; any relationships emitted in pass 1 are discarded (relationships
  are pass 2's job, so they can reference the canonical entity set).
- Runs on a fixed thread pool of `app.pool-size` via `CompletableFuture<Try<…>>`.
- For each successful `Try`, entities are written immediately via APOC MERGE (below) and
  merged into the immutable `CanonicalIndex` (Vavr `HashMap` rebuilt per insert, or batched
  then reduced via `foldLeft`).
- `Failure` results → `failedChunks++`, skip, continue.

### Pass 2 — relationship extraction (per chunk, bounded parallel)
- After pass 1 completes, the `CanonicalIndex` (name + label → node id, deduplicated) is
  frozen (immutable Vavr map; pass 2 only reads).
- For each chunk, prompt glm-5.2 with **(a) that chunk's text** and **(b) the full canonical
  entity list** (rendered from the index), and ask it to return relationships grounded in
  that chunk's text. Supplying the canonical list lets the model link an entity originally
  found in chunk *k* to one found in chunk *j* whenever the current chunk's text supports it.
- Each relationship's `sourceName/sourceLabel` and `targetName/targetLabel` are resolved to
  canonical node ids via `CanonicalIndex.lookup` → `Option<Long>`; `None` on either endpoint
  → the relationship is dropped (not counted in `failedChunks`).
- Relationships are written via APOC MERGE; duplicates across chunks collapse by MERGE.

### Deduplication / normalization (`EntityNormalizer`)
- `normalize(name) = name.trim().toLowerCase(ROOT)`; applied as the MERGE key `name_norm`.
- The canonical key is a Vavr `Tuple2<String, String>` of `(nameNorm, label)`.
- Relationships resolved against the normalized key, not raw names.

### Persistence (`Neo4jGraphWriter`, APOC-parameterized)
Cypher cannot parameterize labels/relationship types, so all dynamic writes go through APOC,
which accepts labels/types as parameters → no string interpolation → injection-safe.

`:Document` node:
```cypher
MERGE (d:Document {hash: $hash})
SET d.filename = $filename, d.uploadedAt = $uploadedAt
RETURN id(d) AS id
```
Entity (dynamic label `$label`):
```cypher
CALL apoc.merge.node(
  [$label],
  {name_norm: $nameNorm},
  {name: $name, label: $label, description: $description, source_doc: $hash},
  {}
) YIELD node RETURN id(node) AS id
```
Relationship (dynamic type `$type`, resolved node ids):
```cypher
MATCH (a),(b) WHERE id(a) = $srcId AND id(b) = $tgtId
CALL apoc.merge.relationship(a, $type, {description: $description}, {}, b, true) YIELD rel
RETURN id(rel) AS id
```
All label/type values are passed as **parameters**, never interpolated.

### Failure handling
- Per-chunk LLM/parse failure: the `Try<ExtractionResult>` is `Failure` after the one retry
  (see Vavr usage) → skip the chunk, `failedChunks++`, continue. Response is `200` with a
  partial graph and `failedChunks > 0`.
- Unresolved relationship endpoints (`Option.None`): relationship dropped, **not** counted in
  `failedChunks` (distinct from LLM failure).
- Duplicate hash: `200` `SKIPPED_DUPLICATE`, no LLM calls, no writes.
- Page count > `max-pages`: throw `MaxPagesExceededException` → `ApiExceptionHandler` → `413`.
- Neo4j unreachable at request time: throw `Neo4jUnavailableException` → `503` (fails the
  whole request before processing). The APOC startup probe also throws this if APOC is missing.

## Edge Cases
| Case | Expected Behavior |
|------|-------------------|
| Same PDF uploaded twice | `SKIPPED_DUPLICATE`, zero LLM calls |
| Pages > `max-pages` | `413` before any LLM call |
| Encrypted / image-only PDF | `PagePdfDocumentReader` yields empty/no text → 0 entities → `200` with `nodeCount=0`, `failedChunks` counts affected chunks |
| Chunk LLM call fails / unparseable (after 1 retry) | `Try.Failure` → skip chunk, `failedChunks++`, continue |
| LLM emits entity name with different casing across chunks | Normalized key collapses to one node |
| LLM emits relationship endpoint not in canonical set | `Option.None` → relationship dropped (not counted in `failedChunks`) |
| Two entities never co-occur in any chunk's text | No relationship created (documented limitation of text-grounded pass-2 batching) |
| LLM label/type contains backticks / odd chars | Safe: APOC passes as parameter; no Cypher injection |
| Neo4j down | `503`, no partial writes (write phase starts after extraction) |
| Duplicate relationship emitted by multiple chunks | APOC MERGE collapses to one edge |

## Security Considerations
- Endpoint binds to `localhost` only (e.g. `server.address: 127.0.0.1`); no auth.
- PDF content is sent to Z.ai's external API — accepted by design; no user-facing warning.
- **Cypher injection is the main risk** since labels/types come from the LLM. Mitigated
  entirely by using APOC procedures with label/type passed as parameters; no label/type is
  ever string-interpolated into a query. Entity `name`/`description` are also bound as
  parameters, never interpolated.
- APOC must be present on the Neo4j instance; startup check (e.g. a probe query) should fail
  fast with a clear error if APOC is missing.

## Backward Compatibility
Greenfield — no existing callers or schema to preserve. No migration required.

## Alternatives Considered
| Decision | Alternative | Why Not |
|----------|-------------|--------|
| LLM transport | Local proxy / Ollama | User chose Z.ai hosted glm-5.2 directly |
| API shape | Async 202 + polling / SSE | User chose synchronous summary |
| Schema | Predefined / configurable | User chose free-form LLM-decided labels & types |
| Chunk persistence | Persist `:Chunk` nodes | User chose entities/relationships only |
| Structured output | Raw JSON+Jackson / tool-calling / 2.0 native `JSON_SCHEMA` response-format | `BeanOutputConverter` integrates with Spring AI cleanly and is model-agnostic; native `JSON_SCHEMA` is OpenAI-specific and Z.ai/glm-5.2 support is uncertain (kept as optional hardening) |
| Dedup | LLM canonical IDs / no dedup | MERGE on normalized name+label needs no LLM coordination |
| Cross-chunk | Intra-chunk only / overlapping chunks | Second pass needed for cross-chunk; overlapping rejected to keep chunking simple |
| Second-pass context | Full text in one call / entities-only | Full text risks context-window overflow; entities-only is ungrounded |
| Concurrency | Sequential / fully parallel | Bounded pool balances speed vs Z.ai rate limits |
| Properties | Names/labels only / nodes-only | Descriptions on both nodes and edges enrich the graph |
| Dynamic writes | String interpolation + sanitization / allowlist | APOC is fully parameterized and injection-safe without fragility or schema restriction |
| Error handling style | Checked exceptions / Spring MVC exception-only | Vavr `Try`/`Option` keeps the per-chunk skip-and-continue flow declarative (fold over successes); exceptions reserved for hard-fail HTTP paths (413/503) |

## Open Questions
- The pass-2 "batched by source chunk" approach cannot link two entities that never co-occur
  in any chunk's text. This is a fundamental limit of text-grounded extraction and is accepted;
  a future enhancement could add an LLM-only relationship-inference pass over the canonical
  entity list (at the cost of grounding).

## Task Breakdown
1. Scaffold Gradle (Kotlin DSL build script) project: Spring Boot 4.0.x + Spring AI 2.0.0 BOM
   (`spring-ai-bom:2.0.0`) + `spring-ai-starter-model-openai` (OpenAI), Java 21,
   `spring-ai-pdf-document-reader`, Neo4j driver 5.x, Vavr (`io.vavr:vavr`), `AppProperties`
   with `max-pages`, `chunk-tokens`, `pool-size`.
2. Configure `application.yml`: `spring.ai.model.chat=openai`, Z.ai `base-url`/`api-key`,
   `spring.ai.openai.chat.model=glm-5.2`, Neo4j bolt URI/creds, `server.address=127.0.0.1`.
   Add APOC presence startup probe (throw `Neo4jUnavailableException` on missing APOC).
3. Implement `GraphController` + `GraphSummaryResponse` (Java record) + `ApiExceptionHandler`
   (`400` missing file, `413` max pages, `503` Neo4j down). Convert Vavr `Map` counts to
   `java.util.Map` at the controller boundary.
4. Implement `PdfIngestionService`: hash, duplicate-hash check against `:Document`,
   `PagePdfDocumentReader` + `PdfDocumentReaderConfig`, page-count guard (throw
   `MaxPagesExceededException`), `TokenTextSplitter` (no overlap), return Vavr `List<Document>`.
5. Define `ExtractionRecords` (Java records) + wire `BeanOutputConverter<ExtractionResult>`
   into a `ChatClient` bean; one-retry-on-parse-failure helper returning `Try<ExtractionResult>`.
6. Implement `EntityExtractionService` (pass 1): bounded-parallel `CompletableFuture<Try<…>>`,
   fold successes via Vavr `foldLeft`, write entities via APOC MERGE, build `CanonicalIndex`;
   `Try.Failure` → `failedChunks++`.
7. Implement `EntityNormalizer` (`trim + toLowerCase(ROOT)`) + `CanonicalIndex`
   (Vavr `HashMap<Tuple2<String,String>, Long>`, `lookup` → `Option<Long>`).
8. Implement `Neo4jGraphWriter`: APOC-parameterized `:Document`, entity, and relationship
   MERGE queries (all labels/types as parameters); accumulate counts as Vavr `HashMap`.
9. Implement `RelationshipExtractionService` (pass 2): bounded-parallel per-chunk, pass chunk
   text + canonical entity list, resolve endpoints via `Option` (drop on `None`),
   APOC MERGE relationships; `Try.Failure` → `failedChunks++`.
10. Wire the pipeline in the controller: ingest → pass 1 → pass 2 → assemble summary
    (counts, `failedChunks`, `processingTimeMs`) → return. Convert Vavr types to plain Java
    at the response boundary.
11. Verify against a local Neo4j with APOC: upload a sample PDF, confirm dedup, duplicate-skip,
    page guard, failed-chunk resilience, and graph queries over extracted labels/types.
