package net.jimmykw.knowledgegraph.chat.routing;

import java.util.LinkedHashMap;

import java.util.function.Supplier;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties.Routing;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Calls OpenRouter's System One endpoint ({@code POST {base-url}/systemone}) with one Choice question. */
@Slf4j
public class RestSystemOneClient implements SystemOneClient {

    private static final String GRAPH_HINT = " The listed documents, entity types and entities only sample the graph: it also covers the "
            + "products, technologies, people, companies and events within those subjects. A question about any of those is GRAPH, "
            + "even if short, vague or worded like general knowledge (e.g. a bare topic such as 'hard disks').";

    static final String QUESTION = "intent";
    private static final String INSTRUCTIONS = "Classify ONLY the text after 'New message:'. The assistant answers questions from a "
            + "knowledge graph built from the loaded documents. Earlier conversation is context for follow-ups only: an earlier "
            + "off-topic or chitchat exchange must NOT change the class of a new message, which is judged on its own subject.";
    private static final java.util.Map<String, String> CRITERIA = criteria();

    private final RestClient client;
    private final String model;
    private final Supplier<GraphProfile> graphProfile;

    public RestSystemOneClient(Routing routing, Supplier<GraphProfile> graphProfile) {
        this.graphProfile = graphProfile;
        val factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(routing.timeout());
        factory.setReadTimeout(routing.timeout());
        this.client = RestClient.builder()
                .baseUrl(routing.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + routing.apiKey())
                .build();
        this.model = routing.model().contains("/") ? routing.model() : "typesafe/" + routing.model();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<RouteIntent, Double> classify(String state) {
        val body = java.util.Map.of("model", model, "state", state,
                "questions", java.util.Map.of(QUESTION, java.util.Map.of("type", "choice",
                        "instructions", instructions(), "criteria", CRITERIA)));
        val response = client.post().uri("/systemone").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(java.util.Map.class);
        logCost(response);
        return parse(response);
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

    @SuppressWarnings("unchecked")
    static Map<RouteIntent, Double> parse(java.util.Map<String, Object> response) {
        return Option.of(response)
                .map(body -> body.get("answers")).filter(java.util.Map.class::isInstance)
                .map(answers -> ((java.util.Map<String, Object>) answers).get(QUESTION)).filter(java.util.Map.class::isInstance)
                .map(answer -> ((java.util.Map<String, Object>) answer).get("probabilities")).filter(java.util.Map.class::isInstance)
                .map(raw -> HashMap.ofAll((java.util.Map<String, Object>) raw))
                .map(RestSystemOneClient::toIntents)
                .filter(intents -> !intents.isEmpty())
                .getOrElseThrow(() -> new IllegalStateException("System One response had no usable intent probabilities"));
    }

    private static Map<RouteIntent, Double> toIntents(Map<String, Object> raw) {
        return raw.map((name, probability) -> io.vavr.Tuple.of(
                        Try.of(() -> RouteIntent.valueOf(name)).getOrElseThrow(() -> new IllegalStateException("Unknown intent: " + name)),
                        ((Number) probability).doubleValue()));
    }

    private static void logCost(java.util.Map<?, ?> response) {
        Option.of(response).map(body -> body.get("usage")).filter(java.util.Map.class::isInstance)
                .forEach(usage -> log.debug("Routing: System One usage {}", usage));
    }

    private static java.util.Map<String, String> criteria() {
        val map = new LinkedHashMap<String, String>();
        map.put("GRAPH", "Asks about entities, relationships, facts, counts or sources in the documents, including follow-ups "
                + "that refer to earlier answers (e.g. 'which of those...').");
        map.put("CHITCHAT", "Greetings, thanks, or meta questions about the assistant.");
        map.put("OFF_TOPIC", "Unrelated to the documents: general-knowledge trivia (sports results, geography, history, science), weather, coding help, "
                + "recipes. If the question is about a company, product or person that the documents could cover, it is GRAPH instead.");
        map.put("UNANSWERABLE", "Not answerable by any graph by kind: real-time data, opinions, predictions, calculations, "
                + "or requests to write or modify data. Not a factual premise check.");
        return map;
    }
}
