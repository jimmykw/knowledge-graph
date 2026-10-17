# Spec: Entity Resolution

## Summary

Stop the graph from splitting one real-world entity across several nodes. Four parts:

1. Steer the extractor toward a preferred label list.
2. Ask the extractor for full names.
3. Add a resolution step after pass 1 that matches each new entity against the whole graph (and against the rest of
   the upload) before anything is written. Exact and suffix-stripped matches with the same label merge without an LLM
   call; other candidates go to the LLM.
4. Run a one-time Gradle job that merges the duplicates already in the graph.

Provenance moves to `(:Document)-[:MENTIONS]->(:Entity)` edges in the same change. Without it, every merge across
documents would lose that document's citation.

A read-only duplicate check on the live graph (17 Oct 2026; 19 documents, 3,061 entities) found:

- 117 names split across labels, covering 241 nodes.
- 8 nodes for the two Watsons (Sr. and Jr.).
- `Apple Inc.`, `Apple Computer` and `Apple` as separate nodes.
- `IBM` and `International Business Machines Corporation` as separate nodes.

## Goals

- One node per real-world entity, across labels and across documents.
- New uploads resolve against existing nodes, so fragmentation stops growing.
- Every document that mentions an entity is citable, with its own description.
- Known name variants are stored as aliases, so later uploads match them without an LLM call and chat finds them.
- The existing duplicates are cleaned up through a reviewable dry-run plan.
- Upload time grows by at most a few LLM calls per PDF.

## Non-Goals

- Merging relationship duplicates or changing the relationship merge key (recommendation #4; only 3 extra edges measured).
- Shrinking the pass 2 canonical list (recommendation #3).
- A new chat tool for fuzzy entity lookup. Chat matches aliases through Cypher only.
- Rule-based person-name parsing (Sr./Jr./initials). The LLM handles person variants.
- Persisting text chunks.
- Re-extracting the existing 19 PDFs.
- Multi-instance deployment. The concurrency guard is in-process.

## Background

| Where | Current behaviour | Problem |
|---|---|---|
| `EntityExtractionService.SYSTEM_PROMPT` | Free-form labels with a few examples | 29 labels in use; `Location`/`Place`, `Work`/`Book`/`Publication`, `People`/`Person` |
| `EntityExtractionService.dedupeEntity` | Dedupe within one upload on `(name_norm, label)` | Same name with two labels in one PDF gives two nodes |
| `Neo4jGraphWriter.MERGE_ENTITIES_QUERY` (`Neo4jGraphWriter.java:30`) | `apoc.merge.node([label], {name_norm}, onCreate{name, label, description, source_doc}, {})` | Identity includes the label. On a match, the new document's `source_doc` and description are dropped |
| `EntityNormalizer.normalize` | trim + lowercase | `Apple` ≠ `Apple Inc.` |
| `CanonicalIndex` (`CanonicalIndex.java:37`) | `(name_norm, label) → long id`; name-only fallback scans the map | Uses deprecated `id()`; the fallback picks an arbitrary node |
| `Neo4jGraphWriter.readTopEntityTypes` (`:65`) | `UNWIND labels(n)` | A new shared label would top the list |
| `Neo4jGraphWriter.readTopEntityNames` (`:74`) | Degree counts all edges | `MENTIONS` edges would inflate the degree |
| Skills (`src/main/resources/.claude/skills/*/SKILL.md`) | 36 `name_norm` references; `source-attribution` joins on `source_doc` | Don't know about aliases or `MENTIONS` |
| `GraphController.buildGraph` (`GraphController.java:32`) | ingest → pass 1 → pass 2 | No resolution step |

## Design

### Feature flag

`app.resolution.enabled` follows the routing and judge flags: `true` in the checked-in `application.yml`, off when the
property is absent. Off keeps today's `apoc.merge.node` path unchanged, with no `:Entity` label, no `MENTIONS` edges
and no resolution. The prompt changes (preferred labels, full names) and the `elementId` switch apply in both modes.

```java
public record Resolution(boolean enabled, int candidateLimit, double minFulltextScore, int batchSize) {
    public Resolution {
        if (candidateLimit <= 0) { candidateLimit = 5; }
        if (minFulltextScore <= 0) { minFulltextScore = 1.0; }   // tuned against the live graph in task 9
        if (batchSize <= 0) { batchSize = 20; }
    }
}
```

`AppProperties` gains `Resolution resolution`, defaulting to `new Resolution(false, 0, 0, 0)`.

### Preferred labels and full names (pass 1 prompt)

`EntityExtractionService.SYSTEM_PROMPT` lists the preferred labels and allows a new one only when none fits. The same
list, in rank order, is the survivor rule for merges. It lives in one place, `graph/PreferredLabels.java`
(`@UtilityClass`):

```java
public static final List<String> RANKED = List.of(
        "Person", "Organization", "Location", "Event", "Legislation", "Award",
        "Publication", "Work", "Product", "Technology", "Concept");

/** Lower is better. Preferred labels by position; custom labels just above Concept. */
public static int rank(String label) { ... }
```

The prompt also says to emit the fullest unambiguous name as it appears in the text: "Thomas J. Watson Jr.", never
"Watson" alone when the text says which Watson.

### Data model (flag on)

```
(:Entity:<Label> {
    name,            // canonical display name
    name_norm,       // EntityNormalizer.normalize(name)
    match_key,       // EntityNormalizer.matchKey(name), see below
    aliases,         // every name form seen, including name
    aliases_norm,    // normalized forms, for chat lookups
    aliases_text,    // aliases joined with " | ", for the full-text index
    label,           // same as the Neo4j label (kept for existing queries)
    description,     // first description seen
    source_doc       // hash of the first document (kept for backward compatibility)
})
(:Document)-[:MENTIONS {name: <name as written>, description: <that document's description>}]->(:Entity)
```

Schema, created at startup when the flag is on (idempotent `IF NOT EXISTS`, new `Neo4jGraphWriter.ensureSchema()`
called from `Neo4jConfig`'s startup probe):

| Object | Purpose |
|---|---|
| `CREATE CONSTRAINT document_hash IF NOT EXISTS FOR (d:Document) REQUIRE d.hash IS UNIQUE` | No duplicate documents from concurrent uploads |
| `CREATE INDEX entity_name_norm IF NOT EXISTS FOR (e:Entity) ON (e.name_norm)` | Exact lookups (chat and resolver) |
| `CREATE INDEX entity_match_key IF NOT EXISTS FOR (e:Entity) ON (e.match_key)` | Suffix-stripped lookups |
| `CREATE FULLTEXT INDEX entity_names IF NOT EXISTS FOR (e:Entity) ON EACH [e.name, e.aliases_text]` | Fuzzy candidates |

`aliases_text` is a joined string, so the full-text index doesn't depend on list-property support.

`MENTIONS` is added to the `:Schema` relationship types through `recordSchema`. `readTopEntityTypes` ignores the
`Entity` label, and `readTopEntityNames` leaves `MENTIONS` edges out of the degree.

### Match key

`EntityNormalizer.matchKey(String name)`:

1. Apply `normalize`.
2. Replace punctuation with spaces.
3. Remove a leading `the`.
4. Repeatedly remove a trailing corporate suffix: `inc`, `incorporated`, `corp`, `corporation`, `ltd`, `limited`,
   `co`, `llc`, `plc`.
5. Collapse whitespace.

For example, `Apple Inc.` becomes `apple` and `International Business Machines Corporation` becomes
`international business machines`. The key never strips to empty: if stripping leaves nothing, the normalized name is
used.

### Upload flow

```
GraphController.buildGraph
  ingest
  pass 1  EntityExtractionService.extract  → List<ExtractedEntity> (deduped on (name_norm, label), not written)
  resolve EntityResolutionService.resolve(entities, hash)   ← new; holds the resolution lock
            candidates → classify → LLM batches → ResolutionPlan → writer.applyResolution
          → CanonicalIndex (written names → elementId)
  pass 2  RelationshipExtractionService.extract (unchanged apart from elementId)
```

With the flag on, `EntityExtractionService.extract` stops writing and returns the deduped entities plus the
failed-chunk count. `EntityResolutionService` writes them and builds the `CanonicalIndex`. With the flag off, pass 1
writes as today. A thin `EntityWriteStrategy` (or an `if` in `GraphController`) chooses between the two.

#### 1. Candidates (one batched read query)

For each new entity (`idx`, `name`, `nameNorm`, `matchKey`, `label`), using `UNWIND`:

- **Exact:** `e.name_norm = nameNorm OR nameNorm IN e.aliases_norm OR e.match_key = matchKey`.
- **Fuzzy:** `db.index.fulltext.queryNodes('entity_names', <escaped name>)`, keeping the top `candidateLimit` with
  `score >= minFulltextScore`. Lucene special characters in the name are escaped.

Each candidate returns its `elementId`, name, label, description, aliases, and up to 5 neighbour edges
(`type`, other node's name). The neighbours help the LLM tell homonyms apart.

#### 2. Classification

| Situation | Decision |
|---|---|
| No candidates, and no other entity in the batch with the same `match_key` | **New** |
| Exactly one exact/alias/match-key candidate with the **same label** | **Existing**, no LLM call; the written name is added to aliases |
| An exact candidate with a **different label** | LLM |
| Several exact candidates with the same label (old duplicates) | LLM |
| Fuzzy candidates only | LLM |
| No graph candidates, but other entities in the batch share the `match_key` with a different label | LLM, as an in-batch group |

#### 3. LLM decisions

Decisions are grouped into batches of up to `batchSize` and run in parallel on the extraction executor, with the same
`ChatClient`, model and retry settings as extraction. New class `ResolutionPrompter`, following the
`ExtractionPrompter` pattern: `BeanOutputConverter`, `JsonRepair` fallback, one retry. The prompt sends short
references (`n1` for new entities, `c1` for candidates) rather than database ids:

```json
{"decisions": [
  {"entity": "n3", "match": "c2", "canonicalName": "Thomas J. Watson Jr."},
  {"entity": "n4", "match": null}
]}
```

- `match` must be one of the candidate references offered for that entity, or `null` for a new node. Anything else
  counts as **unresolved**.
- `canonicalName` is the fullest unambiguous form of the name. It must equal (after normalization) the entity's name,
  the candidate's name, or one of the candidate's aliases. Otherwise the candidate's current name is kept.
- In-batch groups use the same shape. Group members that the LLM matches to each other become one new node.
- If a batch fails or can't be parsed, every entity in it is **unresolved**: created as new, counted in the response,
  and logged at `warn`.

#### 4. Survivor label

When an entity merges into a node with a different label, the label with the better `PreferredLabels.rank` wins. On
the existing node the Neo4j label is swapped with `apoc.create.removeLabels` / `apoc.create.addLabels`, and the
`label` property is updated. Both labels pass `CypherIdentifiers.isSafe` first.

#### 5. Write (`Neo4jGraphWriter.applyResolution`)

One write transaction, two `UNWIND` queries:

- **New:** `apoc.create.node(['Entity', label], {name, name_norm, match_key, aliases: [name], aliases_norm,
  aliases_text, label, description, source_doc})`.
- **Existing:** match by `elementId`. Set `name`/`name_norm`/`match_key` from `canonicalName`, union in the written
  name, recompute `aliases_text`, and change the label if needed. `description` and `source_doc` stay as they are.
- **Both:** `MERGE (d:Document {hash})-[m:MENTIONS]->(e) SET m.name = <written name>, m.description = <description>`.

Returns `idx → elementId`. `CanonicalIndex` is built from the **written** names, because pass 2 sees and emits the
names as written in this document.

#### Concurrency

`EntityResolutionService` holds one `ReentrantLock` from step 1 through step 5. Extraction (passes 1 and 2) still runs
in parallel across uploads; only resolution and the entity write wait. The lock covers only this app instance, which
is enough because the app binds to `127.0.0.1` and runs as one process.

### `elementId` switch (both modes)

`CanonicalIndex`, `ResolvedRelationship.sourceId/targetId`, `mergeEntities`, `mergeRelationships`, `findDocumentId`
and `mergeDocument` move from `long id()` to `String elementId()`. `MERGE_RELATIONSHIPS_QUERY` matches with
`elementId(a) = r.srcId`. `GraphSummaryResponse.documentId` is already a string. This removes the deprecated `id()`
(recommendation #7). The resolver needs stable ids, so this is part of this work rather than separate.

### Response

`GraphSummaryResponse` gains three `int` fields, all zero when the flag is off or the upload is a duplicate:

| Field | Meaning |
|---|---|
| `entitiesCreated` | New nodes created |
| `entitiesMerged` | Entities resolved to an existing node (with or without an LLM call) |
| `unresolvedEntities` | Entities created as new because their LLM batch failed |

`nodeCount` keeps its meaning (entities written by this upload) and equals `entitiesCreated + entitiesMerged`.
`app.js` (`:326`) adds "N merged into existing" to the upload message, and the unresolved count when it is above zero,
in the same style as `failedChunks`.

Logging follows the funnel style: `Resolution: N entities → X exact, Y LLM, Z new`, then
`Resolution: X merged, Y created, Z unresolved in Nms`, plus per-entity `Merged 'IBM Corp.' into 'IBM' (id=…)` at
`info`.

### Chat

- The `GraphTools.runReadCypher` description: match entities with
  `e.name_norm = toLower($name) OR toLower($name) IN e.aliases_norm`.
- `entity-lookup`, `neighborhood-exploration`, `multi-hop-reasoning`, `path-finding`: same alias-aware match, plus
  `WHERE NOT b:Document` (or a typed relationship pattern) where `MENTIONS` edges would add noise.
- `source-attribution`: use `(d:Document)-[m:MENTIONS]->(e)` and return every document with `m.description`. Keep
  `source_doc` as a fallback for nodes from before the cleanup.
- The chat eval (`./gradlew chatEval`) runs before and after the cleanup as the quality gate.

### One-time cleanup: `./gradlew resolveGraph`

A `JavaExec` task, not a `Test` task, because it writes to the real graph. Its main class is
`net.jimmykw.knowledgegraph.resolve.ResolveGraphCommand` (main source set). It starts the Spring context with
`spring.main.web-application-type=none`, `app.routing.enabled=false`, `app.judge.enabled=false` and
`app.resolution.enabled=true`, and forwards `-Dresolve.*`.

| Mode | Command | Does |
|---|---|---|
| Plan (default) | `./gradlew resolveGraph` | Migrate (idempotent), then write the merge plan to `build/reports/resolve/plan.json` and `plan.md`. No merges |
| Apply | `./gradlew resolveGraph -Dresolve.apply=true` | Read `plan.json` and apply it. Fails if the plan is missing or any node in it has changed or disappeared |

Before applying, take a dump: `neo4j-admin database dump`. The apply mode prints this reminder and requires
`-Dresolve.backupConfirmed=true`.

**Migration step.** Plan mode runs this before planning. It is idempotent and makes no merges:

1. Add `:Entity` to every node with `name_norm` that isn't `:Document`, `:Schema` or `:KgTestInstance`.
2. Set `match_key`, plus `aliases`/`aliases_norm`/`aliases_text` as `[name]` where missing.
3. Create a `MENTIONS` edge from `source_doc`'s document, with the node's description.
4. Create the schema objects.

**Planning.**

- Cluster candidates graph-wide with the same signals as upload: shared `name_norm`/`match_key` across labels, plus
  full-text neighbours.
- Same-label exact/match-key groups go into the plan with no LLM call.
- Everything else goes to the LLM in cluster mode. The prompt is the same, with each member's neighbour edges, and
  asks which members are the same entity and for the canonical name.
- When the LLM is not confident, for example a bare `Watson` whose neighbours don't point to Sr. or Jr., the node is
  left alone and listed under **Needs review** in `plan.md`.

Each plan entry records the survivor `elementId`, the members merged into it, the canonical name, the label, the union
of aliases and the reason (`exact`, `match_key`, `llm`).

**Apply.** For each entry:

1. `apoc.refactor.mergeNodes([survivor] + members, {properties: 'discard', mergeRels: true})`.
2. Set the canonical name, label and aliases.
3. Delete any self-loops the merge created (`(e)-[r]->(e)`).

Each entry runs in its own transaction, so one failure doesn't roll back the rest. Failures are listed in
`build/reports/resolve/apply.md`.

## Edge Cases

| Case | Expected Behavior |
|---|---|
| New `Watson` [Person], existing `Watson` [Technology] | Exact name, different label, so the LLM decides using descriptions and neighbours; usually a new node |
| `Apple Inc.` [Organization] uploaded, `Apple` [Organization] exists | Same match key and label: merges with no LLM call; `Apple Inc.` added to aliases |
| `Apple Watch` [Technology] uploaded, `Apple Watch` [Product] exists | LLM confirms; survivor label `Product` (better rank) |
| Name that strips to nothing (`Inc.`) | Match key falls back to the normalized name |
| Name with Lucene special characters (`AS/400`, `C++`) | Escaped before the full-text query |
| LLM returns a candidate reference it wasn't offered | That entity is unresolved: created as new and counted |
| LLM `canonicalName` not among the known names | Candidate's current name kept; merge still applied |
| Resolution batch times out | Entities created as new, `unresolvedEntities` counts them, upload continues |
| Two uploads resolve at the same time | Second waits on the lock, then sees the first upload's nodes as candidates |
| Several existing duplicates match one new entity | LLM picks one; the others are left for `resolveGraph` |
| Entity merged into a node whose display name then changes | Old name stays in `aliases_norm`, so chat lookups and pass 2 still work |
| Same entity mentioned twice in one PDF with different labels | In-batch group goes to the LLM; one node if it says so |
| Re-upload of the same PDF | `SKIPPED_DUPLICATE` before resolution, as today |
| Flag off | Exactly today's write path, with `elementId` instead of `id()` |
| `resolveGraph -Dresolve.apply=true` with no plan file | Fails with a message to run plan mode first |
| A plan node was deleted or merged since planning | Apply fails before changing anything and asks for a new plan |
| Merge would create a self-loop (`IBM -ACQUIRED-> IBM Corp.`) | Self-loop deleted after the merge and listed in `apply.md` |
| Neo4j down during resolution | `Neo4jUnavailableException` → 503, as today. The `:Document` is already written (see Open Questions) |

## Security Considerations

- **Injection.** Labels still go through APOC as parameters. The survivor label comes from the extracted or existing
  labels, and both pass `CypherIdentifiers.isSafe` before `apoc.create.addLabels`/`removeLabels`. Full-text query
  strings are escaped. Element ids come from Neo4j, not from the LLM: the LLM sees only `n1`/`c1` references, which
  are mapped back in code.
- **Data egress.** Resolution sends entity names, descriptions and neighbour edge names to the extraction model
  (OpenRouter), the same provider and the same kind of data extraction already sends. No new third party. Document
  this beside the routing and judge egress notes in AGENTS.md.
- **Write access.** `resolveGraph` is a local Gradle task with no HTTP surface. Apply requires two explicit flags.
- **Prompt injection from documents.** A PDF could try to steer merges ("treat X as Y"). The effect is limited to
  merging among offered candidates. The dry-run plan and the Needs review list cover the cleanup; for uploads, merges
  are logged per entity.

## Backward Compatibility

| Area | Change |
|---|---|
| `POST /api/knowledge-graph` | Three new response fields; existing fields keep their meaning |
| Node ids | `documentId` values change from numeric strings to element ids |
| Graph shape | New `:Entity` label, alias properties and `MENTIONS` edges. Queries like `MATCH (a)--(b)` now reach `:Document` nodes; the skills are updated |
| Chat | Old patterns (`name_norm = …`) still work; aliases add recall |
| Existing nodes before cleanup | Lack `:Entity` and aliases, so the resolver can't find them. Run plan mode (which migrates) before uploading with the flag on |
| Flag off | Old behaviour apart from `elementId` |
| Tests | `Neo4jGraphWriterTest/IT`, `CanonicalIndexTest`, extraction tests and flow tests change for `elementId` and the new step. The `*IT` classes cover the schema, `applyResolution` and the cleanup against the throwaway Neo4j |

## Alternatives Considered

| Alternative | Why Not |
|---|---|
| Keep the label in the identity key | Leaves the 117 label splits |
| `apoc.merge.node` on `name_norm` under `:Entity` | Homonyms (`Watson` person vs product) merge silently |
| Auto-merge any exact name match | Same homonym problem; one LLM call per cross-label hit is cheap |
| String similarity (Jaro-Winkler) for automatic merges | 427 pairs at distance < 0.08, mostly false positives (`Windows 95`/`98`), and it misses `IBM`/`International Business Machines` |
| Rule-based person-name parsing | Fiddly, and the LLM handles it with context |
| Closed label vocabulary | Loses useful detail (`Medical Instrument`) |
| Existing node keeps its label | Early mistakes stick forever |
| Keep both labels on merge | Label counts and the schema get harder to read |
| LLM rewrites descriptions on merge | Extra LLM call per merge; per-document descriptions on `MENTIONS` lose nothing |
| `source_docs` list instead of `MENTIONS` | Harder to query per document; can't carry a per-document description |
| Fail the upload when resolution fails | One flaky call would fail a whole PDF |
| Serialize whole uploads | A second upload waits minutes |
| Admin HTTP endpoint for cleanup | Adds a write endpoint to the app |
| Re-extract all 19 PDFs | Full extraction cost; cleanup reaches most of the benefit |
| New `findEntity` chat tool | More prompt surface; alias-aware Cypher covers the measured cases |

## Open Questions

- **`minFulltextScore` default.** Lucene scores aren't normalized. Tune the threshold against the live graph during
  task 9 (the plan run) and set the default from that. Starts at `1.0`.
- **Retry after failure.** If Neo4j goes down during resolution, the `:Document` already exists and a retry returns
  `SKIPPED_DUPLICATE` (recommendation #5, out of scope here). Resolution makes this failure window slightly longer.

## Task Breakdown

1. **`elementId` switch.** `CanonicalIndex`, `ResolvedRelationship`, `Neo4jGraphWriter` queries and return types,
   extraction services, tests. No behaviour change.
2. **Prompt changes.** `PreferredLabels` (list and `rank`), and the pass 1 `SYSTEM_PROMPT` with preferred labels and
   full-name rule. Unit tests for `rank`.
3. **`EntityNormalizer.matchKey`** with unit tests (suffixes, punctuation, leading "the", empty fallback).
4. **Flag and schema.** `AppProperties.Resolution`, `application.yml`, `Neo4jGraphWriter.ensureSchema()` called at
   startup when the flag is on. Exclude `Entity` in `readTopEntityTypes` and `MENTIONS` in `readTopEntityNames`.
   Schema `*IT`.
5. **Candidate query.** `Neo4jGraphWriter.findCandidates(List<EntityProbe>)` (batched exact + full-text + neighbours)
   with `*IT`.
6. **Classification and `ResolutionPrompter`.** Pure classification function and LLM batch prompter with
   validation. Unit tests with a stubbed `ChatClient`, covering invalid references, bad `canonicalName` and failed
   batches.
7. **`applyResolution` writer and `EntityResolutionService`.** Lock, plan, write, `MENTIONS`, label swap,
   `CanonicalIndex`. Wire into `GraphController` behind the flag; pass 1 stops writing when the flag is on. Writer
   `*IT`, service unit tests.
8. **Response and UI.** `GraphSummaryResponse` fields, `app.js` message, `UploadFlowTest` (`FakeLlmServer` resolution
   stubs) and the JS test.
9. **`resolveGraph` plan mode.** `ResolveGraphCommand`, Gradle `JavaExec` task, migration, clustering,
   `plan.json`/`plan.md` with Needs review. `*IT` on the throwaway Neo4j. Run against a copy of the real graph and tune
   `minFulltextScore`.
10. **`resolveGraph` apply mode.** Plan validation, per-entry `mergeNodes`, self-loop removal, `apply.md`. `*IT`.
11. **Chat updates.** `runReadCypher` description and the five skills, then run `chatEval` before and after cleanup.
12. **Docs.** AGENTS.md (flag, `resolveGraph`, egress note, `elementId`), and an updated `SPEC_tests.md`
    if needed.
