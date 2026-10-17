package net.jimmykw.knowledgegraph.api;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.val;
import net.jimmykw.knowledgegraph.exception.ChatException;
import net.jimmykw.knowledgegraph.exception.GraphEmptyException;
import net.jimmykw.knowledgegraph.exception.InvalidFileException;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import net.jimmykw.knowledgegraph.exception.MaxPagesExceededException;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void invalidPromptIs400WithChatResponseBody() {
        val response = handler.handleInvalidPrompt(new InvalidPromptException("Prompt must not be blank."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("Prompt must not be blank.");
        assertThat(response.getBody().answer()).isNull();
        assertThat(response.getBody().skillsExecuted()).isEmpty();
    }

    @Test
    void graphEmptyIs422WithChatResponseBody() {
        val response = handler.handleGraphEmpty(new GraphEmptyException("empty"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().error()).isEqualTo("empty");
    }

    @Test
    void chatExceptionIs422AndCarriesCypherAndError() {
        val response = handler.handleChatFailure(new ChatException("failed", "MATCH (n) RETURN n", "boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().cypher()).isEqualTo("MATCH (n) RETURN n");
        assertThat(response.getBody().error()).isEqualTo("boom");
    }

    @Test
    void invalidFileIs400() {
        val response = handler.handleInvalidFile(new InvalidFileException("Only PDF files are accepted."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400).containsEntry("message", "Only PDF files are accepted.");
    }

    @Test
    void missingPartIs400() {
        val response = handler.handleMissingPart(new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Missing required file part.");
    }

    @Test
    void maxUploadSizeIs413() {
        val response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("message", "Uploaded file exceeds the maximum allowed size.");
    }

    @Test
    void maxPagesIs413WithMessage() {
        val response = handler.handleMaxPages(new MaxPagesExceededException("too many pages"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("message", "too many pages");
    }

    @Test
    void neo4jUnavailableIs503() {
        val response = handler.handleNeo4jUnavailable(new Neo4jUnavailableException("down"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", 503).containsEntry("message", "down");
    }

    @Test
    void unexpectedIs500AndNullMessageBecomesEmpty() {
        val response = handler.handleUnexpected(new RuntimeException((String) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "").containsEntry("error", "Internal Server Error");
    }
}
