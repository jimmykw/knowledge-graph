---
name: neighborhood-exploration
description: Use for verb-directed questions about a named entity first — "what did X
  invent", "what does X create", "who does X manage", "what does X supply/produce",
  "what does X depend on" — where a specific verb must be reconciled against the
  defined relationship types. Also covers generic neighborhood questions ("what's
  related to X", "who is connected to X", "X's connections", "what is associated with
  X", "what does X interact with"). Teaches mapping the user's verb to a defined
  relationship type via getGraphSchema and running a type-filtered query through
  runReadCypher, plus k-hop patterns when no verb is implied.
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
- "What did X invent?" / "What did X create?" / "Who does X manage?"

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
MATCH (e) WHERE e.name_norm = toLower($name) AND e.name IS NOT NULL
OPTIONAL MATCH (e)-[r]-(neighbor) WHERE id(neighbor) <> id(e)
RETURN e.name AS entityName, e.label AS entityLabel,
       type(r) AS relType, r.description AS relDesc,
       neighbor.name AS neighborName, neighbor.label AS neighborLabel,
       neighbor.description AS neighborDesc
ORDER BY relType, neighborLabel
```

`OPTIONAL MATCH` returns the entity row even when it has no neighbors (so you can
confirm the entity was found), and `id(neighbor) <> id(e)` excludes self-loops.
The `e.name IS NOT NULL` filter excludes `:Document` and `:Schema` metadata nodes
from the match.

### 2-hop neighborhood (broader exploration)

```cypher
MATCH (e) WHERE e.name_norm = toLower($name) AND e.name IS NOT NULL
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

### Verb-directed lookup ("what did X invent?")

When the user's question names a specific verb ("invent", "manage", "create",
"supply"), treat the verb as naming an **activity category**, not a literal
relationship type. The schema's `relTypes` are stored as past- or
present-tense verbs (`INVENTED`, `CREATED`, `CREATES`, `DEVELOPED`, …) and a
single user verb must expand to every schema type whose verb belongs to the same
semantic family — including synonyms, near-synonyms, and either tense form.

Procedure — follow every step in order:

1. Call `getGraphSchema` and read the full `relTypes` array it returns. The
   list is large; do not skim. You will reason over every entry below.
2. Expand the user's verb to a **verb family**: collect every synonym and
   near-synonym the question's verb entails. For "invent"/"create" the family
   includes *invent, create, conceive, pioneer, design, develop, engineer,
   commercialize, launch, establish, initiate, introduce, build, produce* and
   related verbs — and you must use the **tense form the schema stores**
   (past `INVENTED` or present `INVENTS`), not the user's bare verb. Do this
   expansion from your own vocabulary before consulting the schema, so you do
   not anchor on the user's literal wording.
3. Scan the schema's `relTypes` and select every entry whose verb is in (or is
   a morphological variant of) that family. Include **all** matches — past AND
   present forms, synonyms AND near-synonyms. Do NOT stop at the first few
   stem-matches of the user's verb; that under-expands the answer.
4. Inline only the schema-returned strings — copy them verbatim, exactly as
   `getGraphSchema` cased them. (`type(r)` returns a string, so this is a
   value comparison, not a dynamic pattern; the literal list is safe.)
5. Run one filtered query with every matched type in an `IN` list:

```cypher
MATCH (e) WHERE e.name_norm = toLower($name)
MATCH (e)-[r]->(neighbor)
WHERE type(r) IN [/* every schema verb in the user-verb's synonym family, verbatim */]
RETURN type(r) AS relType, r.description AS relDesc,
       neighbor.name AS neighborName, neighbor.label AS neighborLabel,
       neighbor.description AS neighborDesc
ORDER BY relType, neighborLabel
```

The `IN` list will often have 10+ entries for a single user verb — that is
correct, not a mistake. Bias toward including a candidate when uncertain;
the query returns zero rows for absent types, so over-inclusion is cheap and
under-inclusion silently misses facts.

If no schema-returned type belongs to the verb family at all, do NOT hard-code
a guess. Run the outgoing neighborhood without a type filter (the directional
pattern above) and group results by `type(r)` so the user can see which defined
relationships cover their intent. Surface the full `relTypes` list from
`getGraphSchema` in the answer so the user can rephrase.

## Answer shape

1. Start by confirming the entity was found (name + label).
2. Group neighbors by **relationship type** — this is the most scannable layout.
3. Under each relationship type, list neighbors with their `label` and a short
   paraphrase of the relationship `description`.
4. If the result set is large, note that it was truncated and suggest the user
   narrow by label or relationship type.
5. If the `name_norm` exact match fails, fall back to a fuzzy CONTAINS match on
   `name_norm` before reporting "not found".
6. For verb-directed questions ("what did X invent?"), state the verb family
   you expanded to and list the defined relationship types the query matched
   (the list will often be long). Group results by type so each type's
   neighbors are visible. If none matched and you returned the full
   neighborhood, list the available relationship types so the user can rephrase.

## Read-only constraint

All Cypher must be read-only: MATCH, WHERE, WITH, RETURN, ORDER BY, LIMIT. Never
use CREATE, MERGE, SET, DELETE, REMOVE, DROP, or CALL apoc.merge.*.
