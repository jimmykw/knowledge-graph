package net.jimmykw.knowledgegraph.exception;

public class ChatException extends KnowledgeGraphException {

    private final String cypher;
    private final String error;

    public ChatException(String message, String cypher, String error) {
        super(message);
        this.cypher = cypher;
        this.error = error;
    }

    public String cypher() {
        return cypher;
    }

    public String error() {
        return error;
    }
}
