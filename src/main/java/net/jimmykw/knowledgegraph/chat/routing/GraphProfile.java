package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.List;

/** What the graph holds, summarised for the classifier. */
public record GraphProfile(List<String> documents, List<String> entityTypes, List<String> topEntities) {

    public static GraphProfile empty() {
        return new GraphProfile(List.empty(), List.empty(), List.empty());
    }

    public boolean isEmpty() {
        return documents.isEmpty() && entityTypes.isEmpty() && topEntities.isEmpty();
    }
}
