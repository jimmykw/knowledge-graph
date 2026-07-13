---
name: graph-statistics
description: Use when the user asks for counts, totals, or a high-level summary of
  the knowledge graph — "how many entities", "how many X are there", "summarize the
  graph", "what's in the graph", "list all labels". Teaches count queries, label
  distribution, and relationship-type tallies.
---

# Graph Statistics

## When to use

Invoke this skill when the user wants aggregate counts or an overview of the graph
rather than a specific entity. Common phrasings:

- "How many entities are in the graph?"
- "How many Person nodes are there?"
- "Summarize the graph."
- "What's in the knowledge graph?"
- "List all the labels / relationship types."

## Graph conventions

- Entity nodes have a `label` property (the Neo4j label is dynamic, but the
  human-readable label is also stored as the `label` property).
- `:Document` nodes track ingested PDFs (`hash`, `filename`, `uploadedAt`).
- The schema (labels and relationship types) can be retrieved via the `getGraphSchema`
  tool — use it before running count queries so you only reference real labels.

## Cypher patterns

### Total entity count

```cypher
MATCH (e) WHERE e.name IS NOT NULL
RETURN count(DISTINCT e) AS entityCount
```

The `e.name IS NOT NULL` filter excludes `:Document` and `:Schema` metadata nodes
from the entity tally.

### Count by label

```cypher
MATCH (e) WHERE e.name IS NOT NULL
RETURN e.label AS label, count(e) AS count
ORDER BY count DESC
```

### Count by relationship type

```cypher
MATCH ()-[r]->()
RETURN type(r) AS relType, count(r) AS count
ORDER BY count DESC
```

### Document inventory

```cypher
MATCH (d:Document)
RETURN d.filename AS filename, d.uploadedAt AS uploadedAt
ORDER BY d.uploadedAt DESC
```

### Count relationships for a specific entity

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (e)-[r]-()
RETURN type(r) AS relType, count(r) AS count
ORDER BY count DESC
```

## Answer shape

1. Lead with the headline number the user asked for ("There are 42 entities").
2. When summarizing the graph, give the total entity count, then the label
   distribution, then the relationship-type distribution.
3. Use a compact list or table for distributions — the user wants scannability.
4. If the graph is empty (zero entities), say so and suggest ingesting a document.
5. Do not invent counts — report exactly what the query returned.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT, count.
Never use CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
