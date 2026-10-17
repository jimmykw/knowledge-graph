package net.jimmykw.knowledgegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lombok.val;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import net.jimmykw.knowledgegraph.testsupport.ExtractionJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

class ExtractionPrompterTest {

    private ChatClient client;
    private ExtractionPrompter prompter;

    @BeforeEach
    void setUp() {
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        prompter = new ExtractionPrompter(client, new BeanOutputConverter<>(ExtractionResult.class));
    }

    private void respondWith(Object... contents) {
        val stubbing = when(client.prompt(any(Prompt.class)).call().content());
        stubbing.thenReturn((String) contents[0], java.util.Arrays.stream(contents).skip(1).map(String.class::cast).toArray(String[]::new));
    }

    private static final String VALID = ExtractionJson.json(ExtractionJson.entities(ExtractionJson.entity("IBM", "Organization")));

    @Test
    void validJsonIsParsed() {
        respondWith(VALID);

        val result = prompter.callWithRetry(new Prompt("p"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.get().entities()).extracting(ExtractionRecords.ExtractedEntity::name).containsExactly("IBM");
    }

    @Test
    void fencedJsonWithTrailingCommaIsRepaired() {
        respondWith("```json\n{\"entities\": [{\"name\": \"IBM\", \"label\": \"Organization\", \"description\": \"d\",}],\n"
                + "\"relationships\": [],}\n```");

        val result = prompter.callWithRetry(new Prompt("p"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.get().entities()).hasSize(1);
        verify(client, times(1)).prompt(any(Prompt.class));
    }

    @Test
    void firstAttemptFailureIsRetriedOnce() {
        respondWith("not json at all", VALID);

        val result = prompter.callWithRetry(new Prompt("p"));

        assertThat(result.isSuccess()).isTrue();
        verify(client, times(2)).prompt(any(Prompt.class));
    }

    @Test
    void bothAttemptsFailingYieldsFailure() {
        respondWith("not json", "still not json");

        val result = prompter.callWithRetry(new Prompt("p"));

        assertThat(result.isFailure()).isTrue();
        verify(client, times(2)).prompt(any(Prompt.class));
    }

    @Test
    void blankContentYieldsFailureAfterRetry() {
        respondWith("   ", "");

        assertThat(prompter.callWithRetry(new Prompt("p")).isFailure()).isTrue();
    }

    @Test
    void transportErrorIsRetriedThenReported() {
        when(client.prompt(any(Prompt.class)).call().content()).thenThrow(new IllegalStateException("timeout"));

        val result = prompter.callWithRetry(new Prompt("p"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getCause()).hasMessage("timeout");
        verify(client, times(2)).prompt(any(Prompt.class));
    }

    @Test
    void getFormatDelegatesToTheConverter() {
        val converter = new BeanOutputConverter<>(ExtractionResult.class);
        assertThat(new ExtractionPrompter(client, converter).getFormat()).isEqualTo(converter.getFormat()).contains("entities");
    }
}
