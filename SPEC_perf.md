# Spec: Ingestion Pipeline Performance — Batched Neo4j Writes + Config Tuning

## Summary

Speed up `POST /api/knowledge-graph` by attacking the two measured bottlenecks in the extraction pipeline: (1) the LLM phase — 2 calls per chunk with only 4 in flight — is tuned via config (`chunk-tokens: 1000 → 3000`, `pool-size: 4 → 8`), cutting call count ~3× and doubling parallelism; (2) the Neo4j write phase — currently 2 serial round trips per entity/relationship — is replaced by one batched `UNWIND` + APOC merge query per pass and once-per-pass schema recording, reducing `2(E+R)` round trips to ~4 per document. Extraction semantics, the API contract, and the graph data model are unchanged.

## Goals

- Batch Pass 1 entity writes into a single `UNWIND $entities` + `apoc.merge.node` query in `Neo4jGraphWriter`.
- Batch Pass 2 relationship writes into a single `UNWIND $rels` + `apoc.merge.relationship` query.
- Record `:Schema` labels/rel types once per pass (distinct sets) instead of once per item.
- Raise `app.pool-size` from 4 to 8 and `app.chunk-tokens` from 1000 to 3000 in `application.yml`.
- Preserve the funnel logging (candidate → valid → created) and add a write-phase timing line per pass so the LLM vs. Neo4j split is visible in logs.
- Keep all invariants: APOC-parameterized labels/types (no string interpolation), 6th arg of `apoc.merge.relationship` a map, skip-and-continue per-chunk LLM failure, two-pass canonicalization.

## Non-Goals

- Merging Pass 1 + Pass 2 into a single LLM call per chunk (rejected by user; changes canonicalization design — see Alternatives).
- Changing `GraphSummaryResponse`, the endpoint contract, or the graph data model (node/relationship properties, merge keys).
- Parallelizing the DB writes themselves (unnecessary once batched).
- Reducing the Pass 2 prompt size (full canonical entity list is still injected per chunk).
- Adding tests (repo has no test infrastructure; verification is `./gradlew compileJava`).

## Background

`GraphController.buildGraph` runs three sequential phases: ingest, Pass 1 (entities), Pass 2 (relationships). Both passes log their LLM phase separately (`Pass N: LLM calls completed in Xms`) from total pass time, which makes the two bottlenecks directly measurable.

**Bottleneck 1 — LLM calls (dominant).** For C chunks, the pipeline makes 2C LLM calls (`EntityExtractionService.extract`, EntityExtractionService.java:65-70; `RelationshipExtractionService.extract`, RelationshipExtractionService.java:76-81), each pool capped by `Executors.newFixedThreadPool(appProperties.poolSize())` with `pool-size: 4` (application.yml:4). Wall time ≈ `(2C / poolSize) × per-call latency`. With `chunk-tokens: 1000` (application.yml:3), a typical PDF yields many chunks.

**Bottleneck 2 — chatty serial writes.** After the LLM phase, each entity is written individually via `Neo4jGraphWriter.mergeEntity` (Neo4jGraphWriter.java:49), which opens a session for `apoc.merge.node` and then a **second** session for `recordSchemaLabel` (Neo4jGraphWriter.java:106) — a per-item `MERGE` of the same `:Schema` node that is almost always a no-op. Relationships do the same via `mergeRelationship` (Neo4jGraphWriter.java:70) + `recordSchemaRelType` (Neo4jGraphWriter.java:116). With E entities + R relationships that is `2(E+R)` serial round trips inside `foldLeft` loops (EntityExtractionService.java:92-100, RelationshipExtractionService.java:103-112).

Current write-loop shape (Pass 1; Pass 2 is analogous):

```java
val acc = allEntities.foldLeft(
        Tuple.of(CanonicalIndex.empty(), HashMap.<String, Integer>empty(), 0),
        (accumulator, entity) -> mergeEntity(accumulator, entity, hash)); // one writer call per entity
```

## Design

### 1. Batched entity writes (`Neo4jGraphWriter`)

Replace `mergeEntity(...)` with a batch method that returns ids correlated by input position:

```java
private static final String MERGE_ENTITIES_QUERY = """
        UNWIND $entities AS e
        CALL apoc.merge.node([e.label], {name_norm: e.nameNorm},
            {name: e.name, label: e.label, description: e.description, source_doc: $hash}, {})
        YIELD node
        RETURN e.idx AS idx, id(node) AS id
        """;

public Map<Integer, Long> mergeEntities(List<ExtractedEntity> entities, String hash) // vavr Map: idx → node id
```

- `$entities` is a `java.util.List<java.util.Map<String,Object>>` built by `entityParam(entity, idx)` helpers via `zipWithIndex()`; each map carries `idx`, `name`, `nameNorm`, `label`, `description`. `hash` is a separate top-level param.
- Merge key is unchanged: label list + `{name_norm}` ident, on-match props `{}` — so nodes shared with prior documents are reused untouched, exactly as before.
- Empty batch guard returns `HashMap.empty()` without opening a session.
- `idx` correlation avoids relying on `UNWIND` row order, which Cypher does not guarantee.

### 2. Batched relationship writes (`Neo4jGraphWriter`)

```java
private static final String MERGE_RELATIONSHIPS_QUERY = """
        UNWIND $rels AS r
        MATCH (a), (b) WHERE id(a) = r.srcId AND id(b) = r.tgtId
        CALL apoc.merge.relationship(a, r.type, {description: r.description}, {}, b, {})
        YIELD rel
        RETURN r.idx AS idx, id(rel) AS id
        """;

public Map<Integer, Long> mergeRelationships(List<ResolvedRelationship> relationships)
```

- The 6th argument to `apoc.merge.relationship` remains the `{}` map (per AGENTS.md gotcha).
- Endpoint ids come from the `CanonicalIndex` built in Pass 1, so relationships can only be written after Pass 1's ids are known — the phase ordering is unchanged.

### 3. Once-per-pass schema recording (`Neo4jGraphWriter`)

```java
public void recordSchema(Set<String> labels, Set<String> relTypes)
```

One `MERGE (s:Schema {id})` + `apoc.coll.union` for both sets; skipped when both are empty. Pass 1 calls it with `(distinctLabels, ∅)`, Pass 2 with `(∅, distinctRelTypes)`, `mergeDocument` with `({"Document"}, ∅)`. Replaces the per-item `recordSchemaLabel`/`recordSchemaRelType` calls, which are deleted along with the old per-item merge methods (only callers were the two extraction services — verified by grep).

### 4. Pass 1 rewrite (`EntityExtractionService`)

The interleaved dedupe+write+count `foldLeft` splits into pure in-memory steps followed by one write:

1. **Dedupe first** — `dedupeEntity` folds `allEntities` into `(HashSet<Tuple2<nameNorm,label>>, List<ExtractedEntity>)`, keeping first occurrence (prepend + `reverse()`), logging `Skipped duplicate` at debug. Same dedupe key `CanonicalIndex.put` uses, so semantics match.
2. **Write batch** — `writer.mergeEntities(uniqueEntities, hash)`.
3. **Counts + schema** — `countsByLabel` folded in memory; `recordSchema(counts.keySet(), HashSet.empty())`.
4. **Index build** — `CanonicalIndex` folded from `uniqueEntities.zipWithIndex()` using `nodeId(idsByIndex, pair)`, which `getOrElseThrow`s `IllegalStateException` if an id is missing (should be impossible — APOC always yields a node).
5. Per-item `Created node` info logs are retained (emitted after the batch), plus a new `Pass 1: N entit(ies) written to Neo4j in Xms` timing line.

### 5. Pass 2 rewrite (`RelationshipExtractionService`)

1. **Resolve + dedupe** — `resolveRelationship` uses an `Option` pipeline (`lookup(source).flatMap(src -> lookup(target).map(tgt -> Tuple.of(src, tgt)))`) instead of `isEmpty()` + `.get()`; missing endpoints hit `getOrElse` which logs `dropped` at warn and keeps the accumulator. `addResolved` dedupes on `Tuple3<sourceId, targetId, type>` and prepends a new `ResolvedRelationship` record.
2. New record in `ExtractionRecords`:

```java
public record ResolvedRelationship(long sourceId, long targetId, String type,
                                   String description, String sourceName, String targetName) {}
```

(`sourceName`/`targetName` retained for the per-item `Created relationship` logs.)
3. **Write batch** — `writer.mergeRelationships(uniqueRelationships)`; counts and `recordSchema(HashSet.empty(), counts.keySet())`; `Pass 2: N relationship(s) written to Neo4j in Xms` timing line.

### 6. Config tuning (`application.yml`)

| Key | Old | New | Effect |
|-----|-----|-----|--------|
| `app.pool-size` | 4 | 8 | 2× LLM concurrency (both passes share the pattern; each service builds its own pool) |
| `app.chunk-tokens` | 1000 | 3000 | ~3× fewer chunks → ~3× fewer LLM calls |

Combined LLM-phase estimate: ~6× wall-time reduction, subject to provider rate limits (watch for 429s in `Pass N: chunk i/j failed` logs). `AppProperties` fallback defaults (AppProperties.java:29-33) are intentionally left at 1000/4 — only the deployed config changes.

### 7. Docs (`AGENTS.md`)

Architecture bullets 2–3 rewritten to describe the batched `UNWIND` design and once-per-pass `recordSchema`. The APOC-signature and no-string-interpolation conventions still apply verbatim.

## Edge Cases

| Case | Expected Behavior |
|------|-------------------|
| Empty valid-entity or valid-relationship batch | Writer returns `HashMap.empty()` without a Neo4j round trip; pass completes with count 0 |
| Duplicate entities within one run's extraction results | Deduped on `(name_norm, label)` before write; first occurrence wins; logged at debug |
| Entity already exists in Neo4j from a prior document | `apoc.merge.node` matches it (on-match props `{}` = no update); id returned and indexed; counted as created — same as before |
| Duplicate relationship (same src, tgt, type) | Deduped in memory before write; never reaches Neo4j |
| Relationship endpoint missing from canonical index | Dropped with `warn` log; counted in "not created" — unchanged |
| Neo4j fails mid-pass | Entire batch statement fails atomically; exception propagates → 503. **Behavior change:** previously a partial prefix of items could be persisted before the failure |
| APOC yields no row for a batch item | `nodeId`/`relId` throw `IllegalStateException` (fail-fast; unreachable in practice) |
| Both schema sets empty | `recordSchema` is a no-op; no empty `:Schema` node created |
| LLM chunk failure | Skip-and-continue, counted in `failedChunks` — unchanged |

## Security Considerations

- No change to the injection-safety model: dynamic labels/relationship types are still passed as **parameters** to `apoc.merge.node`/`apoc.merge.relationship`, never string-interpolated. Batching moves the parameters into a list-of-maps but keeps them parameterized.
- No new endpoints, auth surface, or file-handling changes.

## Backward Compatibility

- **API:** `POST /api/knowledge-graph` and `GraphSummaryResponse` unchanged.
- **Data model:** identical node/relationship properties, merge keys, and `:Schema` shape; existing graphs need no migration.
- **Behavioral:** one deliberate change — a mid-pass Neo4j failure is now all-or-nothing for that pass instead of potentially persisting a partial prefix. It surfaces as a 503 either way.
- **Config:** only `application.yml` values change; `AppProperties` fallback defaults are untouched, so other environments relying on defaults see no change.

## Alternatives Considered

| Alternative | Why Not |
|-------------|---------|
| Single-pass extraction (entities + relationships in one LLM call per chunk) | Halves LLM calls but changes the two-pass canonicalization design — Pass 2's global canonical list is what keeps relationship endpoints consistent across chunks. Rejected by user; viable future step if LLM cost still dominates |
| Parallelize DB writes across threads | Pointless once writes are a single statement per pass |
| Per-item writes in one shared session/transaction | Saves session overhead but still N round trips; batching via `UNWIND` is strictly better |
| Raise `pool-size` beyond 8 | Diminishing returns vs. provider rate-limit risk; 8 chosen as a safe default, easily tuned in yml |
| Keep per-item `recordSchemaLabel`/`recordSchemaRelType` | Pure waste: 2 extra round trips per item that almost always no-op |

## Open Questions

- Optimal `chunk-tokens` for extraction recall on `glm-5.2` is unmeasured — 3000 is a starting point; if entity counts drop noticeably on the sample PDF, revisit.
- Whether the provider rate limit tolerates `pool-size: 8` sustained — confirm via absence of 429s in logs during a full-document run.

## Task Breakdown

1. Add `ResolvedRelationship` record to `ExtractionRecords`.
2. Rewrite `Neo4jGraphWriter`: batched `mergeEntities`/`mergeRelationships`, `recordSchema(labels, relTypes)`, param-builder helpers; delete per-item merge + schema methods; repoint `mergeDocument` at `recordSchema`.
3. Rewrite Pass 1 write phase in `EntityExtractionService` (dedupe → batch write → counts/schema → index build) with `dedupeEntity` + `nodeId` helpers.
4. Rewrite Pass 2 write phase in `RelationshipExtractionService` (resolve/dedupe → batch write → counts/schema) with `resolveRelationship` + `addResolved` + `relId` helpers.
5. Update `application.yml`: `chunk-tokens: 3000`, `pool-size: 8`.
6. Update `AGENTS.md` architecture bullets.
7. Verify `./gradlew compileJava`; smoke-test with `procurement.pdf` (delete its `:Document {hash}` node first) and compare `LLM calls completed` vs `written to Neo4j` timings.
