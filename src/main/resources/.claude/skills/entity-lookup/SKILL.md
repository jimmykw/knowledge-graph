---
name: entity-lookup
description: Use when the user asks about a specific entity by name — "tell me about X",
  "what is X", "who is X", "find X", "find information on X", "what does the graph say
  about X". Teaches case-insensitive matching against the name_norm property
  (trim + lowercase) with a CONTAINS fuzzy fallback, and the answer shape: canonical
  name, label, description, then related entities. Do NOT use for verb-directed
  questions ("what did X invent", "who does X manage", "what does X create") — those
  belong to the neighborhood-exploration skill.
---

# Entity Lookup

## When to use

Invoke this skill when the user's question centers on a named entity — a person,
organization, concept, place, or any proper noun that likely exists as a node in
the knowledge graph. Common phrasings:

- "Tell me about X"
- "What is X?"
- "Who is X?"
- "Find information on X"
- "What does the graph say about X?"

## Graph conventions

Entity nodes have these properties:

- `name` — display name (original casing from the source PDF)
- `name_norm` — normalized key: **trim + lowercase**. This is the match target.
- `label` — the Neo4j label (dynamic, e.g. Person, Organization, Concept)
- `description` — short description extracted from the source
- `source_doc` — hash of the source `:Document` node

## Cypher patterns

### Exact case-insensitive match (preferred)

```cypher
MATCH (e)
WHERE e.name_norm = toLower($name) AND e.name IS NOT NULL
RETURN e.name AS name, e.label AS label, e.description AS description
LIMIT 1
```

Always parameterize the name as `$name`. Never string-interpolate.

### Fuzzy fallback (when exact returns nothing)

Broaden to a substring match on `name_norm`:

```cypher
MATCH (e)
WHERE e.name_norm CONTAINS toLower($name) AND e.name IS NOT NULL
RETURN e.name AS name, e.label AS label, e.description AS description
ORDER BY size(e.name_norm)
LIMIT 5
```

Ordering by `size(e.name_norm)` surfaces the closest (shortest) matches first.

### Drop the label filter first

If the user's question implies a label ("who is X" implies Person) but the exact
match returns nothing, drop the label constraint and match on `name_norm` alone.
The label guess may be wrong — the entity could exist under a different label.

## Answer shape

1. Lead with the canonical `name` (original casing) and `label`.
2. Quote or paraphrase the `description` directly — do not invent details beyond it.
3. If the description is empty or sparse, say so explicitly rather than fabricating.
4. If multiple fuzzy matches returned, list the top candidates and ask the user
   to confirm which one they meant.
5. If no match at all, say the entity was not found in the knowledge graph.

## Read-only constraint

All Cypher emitted under this skill must be read-only: MATCH, OPTIONAL MATCH, WITH,
WHERE, RETURN, ORDER BY, LIMIT. Never use CREATE, MERGE, SET, DELETE, REMOVE, DROP,
or CALL apoc.merge.*.
