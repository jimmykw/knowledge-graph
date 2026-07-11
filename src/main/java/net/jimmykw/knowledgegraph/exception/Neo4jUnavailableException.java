package net.jimmykw.knowledgegraph.exception;

public class Neo4jUnavailableException extends KnowledgeGraphException {

    public Neo4jUnavailableException(String message) {
        super(message);
    }

    public Neo4jUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
