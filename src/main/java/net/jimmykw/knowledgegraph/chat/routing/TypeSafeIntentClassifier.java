package net.jimmykw.knowledgegraph.chat.routing;

import java.util.function.Supplier;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;

/** Asks Jev (through the shared {@link TypeSafeClient}) one {@code intent} Choice question and returns the per-intent probabilities. */
@RequiredArgsConstructor
public class TypeSafeIntentClassifier implements IntentClassifier {

    static final String QUESTION = "intent";

    private static final String GRAPH_HINT = " The listed documents, entity types and entities only sample the graph: it also covers the "
            + "products, technologies, people, companies and events within those subjects. A question about any of those is GRAPH, "
            + "even if short, vague or worded like general knowledge (e.g. a bare topic such as 'hard disks').";

    private static final String INSTRUCTIONS = "Classify ONLY the text after 'New message:'. The assistant answers questions from a "
            + "knowledge graph built from the loaded documents. Earlier conversation is context for follow-ups only: an earlier "
            + "off-topic or chitchat exchange must NOT change the class of a new message, which is judged on its own subject.";

    private final TypeSafeClient client;
    private final Supplier<GraphProfile> graphProfile;

    @Override
    public Map<RouteIntent, Double> classify(String state) {
        val answer = client.systemOne(state, java.util.Map.of(QUESTION, question())).choice(QUESTION);
        return toIntents(HashMap.ofAll(answer.probabilities()));
    }

    private Choice question() {
        return Choice.builder()
                .instructions(instructions())
                .option(RouteIntent.GRAPH.name(), "Asks about entities, relationships, facts, counts or sources in the documents, including "
                        + "follow-ups that refer to earlier answers (e.g. 'which of those...').")
                .option(RouteIntent.CHITCHAT.name(), "Greetings, thanks, or meta questions about the assistant.")
                .option(RouteIntent.OFF_TOPIC.name(), "Unrelated to the documents: general-knowledge trivia (sports results, geography, history, "
                        + "science), weather, coding help, recipes. If the question is about a company, product or person that the documents "
                        + "could cover, it is GRAPH instead.")
                .option(RouteIntent.UNANSWERABLE.name(), "Not answerable by any graph by kind: real-time data, opinions, predictions, "
                        + "calculations, or requests to write or modify data. Not a factual premise check.")
                .build();
    }

    private String instructions() {
        val profile = graphProfile.get();
        return profile.isEmpty() ? INSTRUCTIONS : INSTRUCTIONS + describe(profile) + GRAPH_HINT;
    }

    private static String describe(GraphProfile profile) {
        return section(" Loaded documents: ", profile.documents()) + section(" Entity types in the graph: ", profile.entityTypes())
                + section(" Most-connected entities: ", profile.topEntities());
    }

    private static String section(String heading, List<String> items) {
        return items.isEmpty() ? "" : heading + items.mkString("; ") + ".";
    }

    static Map<RouteIntent, Double> toIntents(Map<String, Double> raw) {
        val intents = raw.map((name, probability) -> io.vavr.Tuple.of(intent(name), probability));
        if (intents.isEmpty()) {
            throw new IllegalStateException("System One response had no usable intent probabilities");
        }
        return intents;
    }

    private static RouteIntent intent(String name) {
        return Try.of(() -> RouteIntent.valueOf(name)).getOrElseThrow(() -> new IllegalStateException("Unknown intent: " + name));
    }
}
