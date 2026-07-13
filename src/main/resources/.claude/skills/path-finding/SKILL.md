---
name: path-finding
description: Use when the user asks how two named entities are connected, related,
  or linked — "how are X and Y related", "what is the connection between X and Y",
  "is there a path from X to Y", "link X to Y". Teaches variable-length Cypher path
  patterns (shortestPath, variable-length relationships) between two name_norm nodes
  and rendering the path as a readable chain.
---

# Path Finding

## When to use

Invoke this skill when the user wants to understand the relationship or connection
between two distinct named entities. Common phrasings:

- "How are X and Y related?"
- "What is the connection between X and Y?"
- "Is there a path from X to Y?"
- "Link X to Y."
- "Can X reach Y?"

Do NOT invoke this skill for single-entity neighborhood exploration ("what's related
to X?") — that is the `neighborhood-exploration` skill.

## Graph conventions

- Entity nodes match on `name_norm` (trim + lowercase). Use `toLower($name)`.
- Relationships carry a `description` property — include it to explain each hop.
- Relationship types and node labels are dynamic; never hard-code them.
- Paths can be long; cap the variable-length depth to keep results bounded.

## Cypher patterns

### Shortest path between two entities

```cypher
MATCH (start) WHERE start.name_norm = toLower($startName)
MATCH (end) WHERE end.name_norm = toLower($endName)
MATCH path = shortestPath((start)-[*..5]-(end))
RETURN [node IN nodes(path) | node.name] AS nodeNames,
       [r IN relationships(path) | type(r)] AS relTypes,
       [r IN relationships(path) | r.description] AS relDescriptions
```

`shortestPath` returns a single path. The `..5` cap bounds the search depth — lower
it (e.g. `..3`) if the graph is large.

### All simple paths up to a depth

```cypher
MATCH (start) WHERE start.name_norm = toLower($startName)
MATCH (end) WHERE end.name_norm = toLower($endName)
MATCH path = (start)-[*..4]-(end)
RETURN [node IN nodes(path) | node.name] AS nodeNames,
       [r IN relationships(path) | type(r)] AS relTypes
LIMIT 10
```

Use this when the user wants to see multiple possible connections, not just the
shortest.

### Directed path (when direction matters)

```cypher
MATCH path = (start)-[*..5]->(end)
```

Use the directed form when the user asks about a flow or dependency ("does X depend
on Y", "how does X lead to Y").

## Answer shape

1. Start by confirming both entities were found.
2. Render the path as a readable chain: `X -[REL_TYPE]-> M -[REL_TYPE]-> Y`.
3. Include the relationship `description` for each hop so the user understands why
   each link exists.
4. If multiple paths exist, present the shortest first, then alternatives.
5. If no path exists within the depth cap, say so explicitly and suggest widening
   the scope or trying specific labels.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, LIMIT. Never use CREATE,
MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
