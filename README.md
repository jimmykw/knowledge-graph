# knowledge-graph


## Optional intent gate

Set `app.routing.enabled=true` to classify each chat message (GRAPH / CHITCHAT / OFF_TOPIC / UNANSWERABLE) with TypeSafe Jev
(`typesafe/jev-1.13` through OpenRouter's System One endpoint) before the agent runs. Confident non-graph messages get a canned
reply and skip the tool loop; classifier failures fall through to the agent. **Privacy:** when enabled, a summary of the graph (loaded document filenames, entity types and the 15 most-connected entity names), the prompt and up to the last
20 conversation messages (including answers built from graph rows) are sent to OpenRouter/TypeSafe. Off by default.
Evaluate with `./gradlew routingEval`.


## Optional answer judge

Set `app.judge.enabled=true` to score each chat answer (grounded in the retrieved rows? relevant to the question?) with TypeSafe
Jev through OpenRouter's System One endpoint. The scores appear as a chip under the answer and in the `quality` field of the chat
response; low scores are flagged but the answer is never changed. **Privacy:** when enabled, the conversation history (up to the
last 20 messages), the graph rows the agent retrieved and the answer are sent to OpenRouter/TypeSafe on every answer. Off by
default, independent of `app.routing.enabled`. Evaluate with `./gradlew judgeEval`.
