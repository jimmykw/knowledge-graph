package net.jimmykw.knowledgegraph.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import net.jimmykw.knowledgegraph.chat.ChatResponse;
import net.jimmykw.knowledgegraph.exception.ChatException;
import net.jimmykw.knowledgegraph.exception.GraphEmptyException;
import net.jimmykw.knowledgegraph.exception.InvalidFileException;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import net.jimmykw.knowledgegraph.exception.MaxPagesExceededException;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidPromptException.class)
    public ResponseEntity<ChatResponse> handleInvalidPrompt(InvalidPromptException ex) {
        log.warn("Invalid chat prompt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(chatError(ex.getMessage()));
    }

    @ExceptionHandler(GraphEmptyException.class)
    public ResponseEntity<ChatResponse> handleGraphEmpty(GraphEmptyException ex) {
        log.warn("Knowledge graph empty: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(chatError(ex.getMessage()));
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ChatResponse> handleChatFailure(ChatException ex) {
        log.warn("Chat request failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ChatResponse(null, ex.cypher(), null, false, 0, ex.error()));
    }

    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidFile(InvalidFileException ex) {
        log.warn("Invalid file upload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException ex) {
        log.warn("Missing file part: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "Missing required file part."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeded max size: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(body(HttpStatus.CONTENT_TOO_LARGE, "Uploaded file exceeds the maximum allowed size."));
    }

    @ExceptionHandler(MaxPagesExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxPages(MaxPagesExceededException ex) {
        log.warn("PDF exceeded max pages: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(body(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage()));
    }

    @ExceptionHandler(Neo4jUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleNeo4jUnavailable(Neo4jUnavailableException ex) {
        log.error("Neo4j unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unexpected error while building knowledge graph", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage()));
    }

    private static Map<String, Object> body(HttpStatus status, String message) {
        return Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message);
    }

    private static ChatResponse chatError(String message) {
        return new ChatResponse(null, null, null, false, 0, message);
    }
}
