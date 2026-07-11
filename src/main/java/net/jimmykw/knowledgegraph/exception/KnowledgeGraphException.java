package net.jimmykw.knowledgegraph.exception;

public class KnowledgeGraphException extends RuntimeException {

    public KnowledgeGraphException(String message) {
        super(message);
    }

    public KnowledgeGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
