---
name: multi-hop-reasoning
description: Use when the user asks a chained relational question that requires
  traversing two or more hops to answer — "who does X work with through Y", "what
  depends on X that Y also depends on", "find all Z that X affects and what those Z
  connect to". Teaches multi-hop Cypher with intermediate nodes and aggregating
  across the chain.
---

# Multi-Hop Reasoning

## When to use

Invoke this skill when the answer requires joining information across two or more
relationship hops — a single-hop neighborhood lookup is not enough. Common phrasings:

- "Who does X work with through Y?"
- "What depends on X that Y also depends on?"
- "Find everything X affects and what those things connect to."
- "Which entities link X to Z?"

Do NOT invoke this skill for a simple 1-hop neighborhood ("what's related to X?")
— that is the `neighborhood-exploration` skill. Do NOT invoke it for a direct
two-entity path ("how are X and Y connected?") — that is the `path-finding` skill.

## Graph conventions

- Entity nodes match on `name_norm` (trim + lowercase). Use `toLower($name)`.
- Relationships carry a `description` property — surface it to justify each hop.
- Relationship types and node labels are dynamic; never hard-code them.
- Use the schema (via `getGraphSchema`) to pick real relationship types when the
  question implies a specific type.

## Cypher patterns

### Two-hop with a named start

```cypher
MATCH (start) WHERE start.name_norm = toLower($name)
MATCH (start)-[r1]-(mid)-[r2]-(target)
WHERE id(target) <> id(start)
RETURN start.name AS start, type(r1) AS firstHop, mid.name AS via, mid.label AS viaLabel,
       type(r2) AS secondHop, target.name AS target, target.label AS targetLabel
ORDER BY firstHop, secondHop
LIMIT 50
```

### Two entities sharing a common neighbor

```cypher
MATCH (a) WHERE a.name_norm = toLower($nameA)
MATCH (b) WHERE b.name_norm = toLower($nameB)
MATCH (a)-[r1]-(mid)-[r2]-(b)
WHERE id(a) <> id(b)
RETURN mid.name AS sharedNode, mid.label AS sharedLabel,
       type(r1) AS aToMid, type(r2) AS bToMid,
       r1.description AS aToMidDesc, r2.description AS bToMidDesc
```

### Three-hop chain with aggregation

```cypher
MATCH (start) WHERE start.name_norm = toLower($name)
MATCH (start)-[r1]-(m1)-[r2]-(m2)-[r3]-(target)
RETURN target.name AS target, target.label AS targetLabel,
       collect(DISTINCT m1.name) AS firstHopNodes,
       collect(DISTINCT m2.name) AS secondHopNodes
ORDER BY targetLabel
LIMIT 20
```

## Answer shape

1. Restate the chain the user asked about so the reasoning is explicit.
2. Walk through each hop: name the intermediate node and the relationship type (and
   `description` where it clarifies the link).
3. Group results by the final target so multiple paths to the same target are
   visible together.
4. If a hop has many neighbors, note the count and surface the most relevant ones
   rather than dumping the whole list.
5. If any hop returns zero rows, apply the `empty-result-recovery` strategy before
   answering.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT, collect,
count. Never use CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
