# knowledge-graph


## Optional intent gate

Set `app.routing.enabled=true` to classify each chat message (GRAPH / CHITCHAT / OFF_TOPIC / UNANSWERABLE) with TypeSafe Jev
(`typesafe/jev-1.13` through OpenRouter's System One endpoint) before the agent runs. Confident non-graph messages get a canned
reply and skip the tool loop; classifier failures fall through to the agent. **Privacy:** when enabled, a summary of the graph (loaded document filenames, entity types and the 15 most-connected entity names), the prompt and up to the last
20 conversation messages (including answers built from graph rows) are sent to OpenRouter/TypeSafe. Off by default.
Evaluate with `./gradlew routingEval`.
