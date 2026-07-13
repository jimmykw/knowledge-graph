package net.jimmykw.knowledgegraph.chat;

public final class ChatRecords {

    private ChatRecords() {
    }

    public record CypherGeneration(String cypher) {
    }
}
