---
name: empty-result-recovery
description: Fires after a Cypher query returns zero rows. Use when the model's first
  query yields no results and it must decide how to recover — broaden the match,
  drop a label filter, fall back to fuzzy name_norm CONTAINS, or report "not found"
  honestly. Teaches a graded recovery sequence so the model does not fabricate
  answers.
---

# Empty Result Recovery

## When to use

Invoke this skill immediately after a `runReadCypher` call returns zero rows. It
guides the recovery strategy so the model does not give up or invent facts.

## Recovery sequence

Follow these steps in order. Stop as soon as a step returns rows.

### 1. Drop the label filter

If the query constrained a specific label that may be wrong, retry matching on
`name_norm` alone without the label:

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
RETURN e.name AS name, e.label AS label, e.description AS description
```

### 2. Fall back to fuzzy substring match

If the exact `name_norm` match returns nothing, broaden to a `CONTAINS` match and
order by closeness:

```cypher
MATCH (e) WHERE e.name_norm CONTAINS toLower($name)
RETURN e.name AS name, e.label AS label, e.description AS description
ORDER BY size(e.name_norm)
LIMIT 5
```

### 3. Inspect the schema

If the name match still fails, call `getGraphSchema` to confirm the labels and
relationship types you assumed actually exist. You may have queried a label that is
not present.

### 4. Try a relationship-only query

If the question is about relationships but the entity name is uncertain, query the
relationship types directly:

```cypher
MATCH ()-[r]->()
RETURN type(r) AS relType, count(r) AS count
ORDER BY count DESC
LIMIT 10
```

### 5. Report honestly

If every step returns zero rows, tell the user clearly that no matching information
was found in the knowledge graph. Suggest alternative spellings or a broader term.
**Never fabricate entities, labels, or relationships that the queries did not
return.**

## Answer shape

- If a recovery step finds results, answer using those results and note that the
  initial match was fuzzy.
- If multiple fuzzy candidates appear, list them and ask the user to confirm which
  one they meant.
- If nothing is found, say so plainly and offer the closest candidates (if any) as
  suggestions.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT. Never use
CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
