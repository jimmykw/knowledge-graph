---
name: source-attribution
description: Use when the user asks where an entity or fact comes from, wants a
  citation, or asks which document mentions X — "where does X come from", "cite the
  source for X", "which document mentions X", "what is the source of X". Teaches
  following (:Document)-[:MENTIONS]->(entity) edges to every document that mentions
  an entity (with that document's own description), falling back to the source_doc
  property, and returning filename and upload timestamp.
---

# Source Attribution

## When to use

Invoke this skill when the user wants to know the provenance of an entity or fact —
which source document it was extracted from. Common phrasings:

- "Where does X come from?"
- "Cite the source for X."
- "Which document mentions X?"
- "What is the source of X?"
- "Where is X described?"

## Graph conventions

- `(:Document)-[m:MENTIONS]->(e)` links every document that mentions an entity to it.
  `m.name` is the name as that document wrote it and `m.description` that document's
  description, so one entity can cite several documents, each with its own wording.
- `:Document` nodes have: `hash`, `filename`, `uploadedAt`.
- Entity nodes also keep `source_doc`, the hash of the first document that mentioned
  them. Use it only as a fallback for older nodes that have no `MENTIONS` edge.
- Match entities on `name_norm` (trim + lowercase) or any of their other names in
  `aliases_norm`. Use `toLower($name)`.

## Cypher patterns

### Every document that mentions an entity

```cypher
MATCH (e) WHERE (e.name_norm = toLower($name) OR toLower($name) IN e.aliases_norm)
OPTIONAL MATCH (d:Document)-[m:MENTIONS]->(e)
OPTIONAL MATCH (fallback:Document {hash: e.source_doc})
WITH e, collect(DISTINCT {file: d.filename, uploadedAt: d.uploadedAt, nameInDoc: m.name,
                          description: m.description}) AS mentions, fallback
RETURN e.name AS entity, e.label AS label,
       [x IN mentions WHERE x.file IS NOT NULL] AS documents,
       fallback.filename AS fallbackSourceFile
```

When `documents` is empty, cite `fallbackSourceFile` (the `source_doc` document).

### All entities a given document mentions

```cypher
MATCH (d:Document {filename: $filename})-[m:MENTIONS]->(e)
RETURN e.name AS entity, e.label AS label, m.name AS nameInDoc, m.description AS description
ORDER BY e.label, e.name
```

### Documents behind an entity's relationships

```cypher
MATCH (e) WHERE (e.name_norm = toLower($name) OR toLower($name) IN e.aliases_norm)
MATCH (e)-[r]-(neighbor) WHERE NOT neighbor:Document
MATCH (d:Document)-[:MENTIONS]->(neighbor)
RETURN DISTINCT d.filename AS sourceFile, d.uploadedAt AS uploadedAt
```

Use this when the user asks about the sources backing an entity's relationships.

## Answer shape

1. Name the entity and its label.
2. Cite every document that mentions it, with `filename` and `uploadedAt`, and quote or
   paraphrase that document's `description` from the `MENTIONS` edge so the user can
   find the passage.
3. If a document wrote the entity under another name (`nameInDoc`), say so.
4. If there are no `MENTIONS` edges and the `source_doc` document is missing too, say the
   source is unknown rather than inventing one.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT. Never use
CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
