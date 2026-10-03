package net.jimmykw.knowledgegraph.chat.routing;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RoutingReplies {

    public static String forIntent(RouteIntent intent) {
        return switch (intent) {
            case CHITCHAT -> "Hello! I answer questions about the documents loaded into the knowledge graph "
                    + "- ask me about its entities, relationships, or facts.";
            case OFF_TOPIC, UNANSWERABLE -> "I can only answer questions about the documents loaded into the knowledge graph "
                    + "(entities, relationships, facts and their sources). That question is outside what the graph can answer.";
            case GRAPH -> throw new IllegalArgumentException("GRAPH has no canned reply");
        };
    }
}
