---
name: source-attribution
description: Use when the user asks where an entity or fact comes from, wants a
  citation, or asks which document mentions X — "where does X come from", "cite the
  source for X", "which document mentions X", "what is the source of X". Teaches
  joining entity nodes to their :Document node via the source_doc property and
  returning filename and upload timestamp.
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

- Entity nodes have a `source_doc` property holding the hash of the source
  `:Document` node.
- `:Document` nodes have: `hash`, `filename`, `uploadedAt`.
- Match entities on `name_norm` (trim + lowercase). Use `toLower($name)`.
- A single entity may originate from one document; multiple entities may share the
  same `source_doc`.

## Cypher patterns

### Source of a single entity

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (d:Document {hash: e.source_doc})
RETURN e.name AS entity, e.label AS label,
       d.filename AS sourceFile, d.uploadedAt AS uploadedAt
```

### All entities from a given document

```cypher
MATCH (d:Document {filename: $filename})
MATCH (e) WHERE e.source_doc = d.hash
RETURN e.name AS entity, e.label AS label, e.description AS description
ORDER BY e.label, e.name
```

### Documents that mention a set of related entities

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (e)-[r]-(neighbor)
MATCH (d:Document {hash: neighbor.source_doc})
RETURN DISTINCT d.filename AS sourceFile, d.uploadedAt AS uploadedAt
```

Use this when the user asks about the sources backing an entity's relationships.

## Answer shape

1. Name the entity and its label.
2. Cite the source `filename` and `uploadedAt` timestamp.
3. If the entity's `source_doc` has no matching `:Document` node, say the source is
   unknown rather than inventing one.
4. When listing multiple sources, group by document and note which entities each
   contributed.
5. Quote or paraphrase the entity `description` so the user can locate the relevant
   passage in the source PDF.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT. Never use
CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
