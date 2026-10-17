package net.jimmykw.knowledgegraph.exception;

public class InvalidFileException extends KnowledgeGraphException {

    public InvalidFileException(String message) {
        super(message);
    }

    public InvalidFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
