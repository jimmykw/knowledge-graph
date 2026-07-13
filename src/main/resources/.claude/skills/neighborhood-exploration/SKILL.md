---
name: neighborhood-exploration
description: Use when the user asks what is related to, connected to, or associated
  with a named entity — "what's related to X", "who is connected to X", "X's
  connections", "what does X depend on", "what is associated with X", "what does X
  interact with". Teaches k-hop Cypher patterns from name_norm and grouping the
  answer by relationship type.
---

# Neighborhood Exploration

## When to use

Invoke this skill when the user wants to explore the neighborhood of a known entity
— its direct relationships, connections, or associations. Common phrasings:

- "What's related to X?"
- "Who is connected to X?"
- "What are X's connections?"
- "What does X depend on?"
- "What is associated with X?"
- "What does X interact with?"

Do NOT invoke this skill for path-finding between two named entities ("how are X
and Y connected?") — that is a different skill.

## Graph conventions

- Entity nodes match on `name_norm` (trim + lowercase). Use `toLower($name)`.
- Relationships carry a `description` property — include it in results.
- Relationship types are dynamic; never hard-code a type unless the schema lists it.
- Node labels are dynamic; use the schema's label list.

## Cypher patterns

### 1-hop neighborhood (direct relationships)

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (e)-[r]-(neighbor)
RETURN type(r) AS relType, r.description AS relDesc,
       neighbor.name AS neighborName, neighbor.label AS neighborLabel,
       neighbor.description AS neighborDesc
ORDER BY relType, neighborLabel
```

### 2-hop neighborhood (broader exploration)

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (e)-[r1]-(m)-[r2]-(neighbor)
WHERE id(neighbor) <> id(e)
RETURN type(r1) AS firstHop, m.name AS viaNode, m.label AS viaLabel,
       type(r2) AS secondHop,
       neighbor.name AS neighborName, neighbor.label AS neighborLabel,
       neighbor.description AS neighborDesc
ORDER BY firstHop, secondHop
LIMIT 50
```

### Directional variant

If the user's question implies direction ("what does X depend on", "who reports
to X"), use directed relationships:

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (e)-[r]->(neighbor)
RETURN type(r) AS relType, r.description AS relDesc,
       neighbor.name AS neighborName, neighbor.label AS neighborLabel
```

Reverse the arrow `(e)<-[r]-(neighbor)` for incoming relationships.

## Answer shape

1. Start by confirming the entity was found (name + label).
2. Group neighbors by **relationship type** — this is the most scannable layout.
3. Under each relationship type, list neighbors with their `label` and a short
   paraphrase of the relationship `description`.
4. If the result set is large, note that it was truncated and suggest the user
   narrow by label or relationship type.
5. If the `name_norm` exact match fails, fall back to a fuzzy CONTAINS match on
   `name_norm` before reporting "not found".

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT. Never
use CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
