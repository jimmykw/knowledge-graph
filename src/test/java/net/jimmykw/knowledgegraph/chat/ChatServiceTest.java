package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.judge.NoOpAnswerJudge;
import net.jimmykw.knowledgegraph.chat.routing.QuestionRouter;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatServiceTest {

    private ChatClient client;
    private ChatMemory memory;
    private ChatService service;

    @BeforeEach
    void setUp() {
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.mutate().defaultAdvisors(any(Advisor.class)).build().prompt(any(String.class)).advisors(any(java.util.function.Consumer.class))
                .call().content()).thenReturn("agent answer");
        memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        val props = new AppProperties(0, 0, new AppProperties.Chat(0, 10, 2, 0L, 0), null, null, null);
        QuestionRouter router = (prompt, history) -> Option.none();
        service = new ChatService(client, mock(ChatClient.class), props, memory, router, new NoOpAnswerJudge());
    }

    @Test
    void nullBlankAndOverlongPromptsAreRejected() {
        assertThatThrownBy(() -> service.chat(null, "c")).isInstanceOf(InvalidPromptException.class).hasMessage("Prompt must not be blank.");
        assertThatThrownBy(() -> service.chat("  ", "c")).isInstanceOf(InvalidPromptException.class);
        assertThatThrownBy(() -> service.chat("x".repeat(11), "c"))
                .isInstanceOf(InvalidPromptException.class)
                .hasMessage("Prompt exceeds the maximum length of 10 characters.");
    }

    @Test
    void promptAtTheLimitIsAccepted() {
        assertThat(service.chat("x".repeat(10), "c").answer()).isEqualTo("agent answer");
    }

    @Test
    void blankConversationIdGetsAGeneratedUuid() {
        val response = service.chat("hello", "   ");

        assertThat(response.conversationId()).isNotBlank();
        assertThat(java.util.UUID.fromString(response.conversationId())).isNotNull();
    }

    @Test
    void nullConversationIdGetsAGeneratedUuid() {
        assertThat(service.chat("hello", null).conversationId()).isNotBlank();
    }

    @Test
    void paddedConversationIdIsTrimmed() {
        assertThat(service.chat("hello", "  c1  ").conversationId()).isEqualTo("c1");
    }

    @Test
    void clearConversationReportsWhetherAnythingWasCleared() {
        memory.add("c1", List.of(new UserMessage("q"), new AssistantMessage("a")));

        assertThat(service.clearConversation("c1")).isTrue();
        assertThat(memory.get("c1")).isEmpty();
        assertThat(service.clearConversation("c1")).isFalse();
        assertThat(service.clearConversation("never")).isFalse();
    }

    @Test
    void resolveErrorPrefersToolErrorOverMaxRounds() {
        val trace = new ToolTrace();
        trace.markMaxRoundsExceeded();
        assertThat(ChatService.resolveError(trace)).isEqualTo("Exceeded max tool call rounds");

        trace.setError("syntax error");
        assertThat(ChatService.resolveError(trace)).isEqualTo("syntax error");
    }

    @Test
    void resolveErrorIsNullWhenNothingWentWrong() {
        assertThat(ChatService.resolveError(new ToolTrace())).isNull();
    }

    @Test
    void cappedCheckerStopsAtMaxRoundsAndMarksTheTrace() {
        val trace = new ToolTrace();
        val checker = ChatService.cappedChecker(trace, 2);
        val withTools = chatResponseWithToolCall();

        assertThat(checker.apply(withTools)).isTrue();
        trace.incrementRoundCount();
        trace.incrementRoundCount();
        assertThat(trace.maxRoundsExceeded()).isFalse();

        assertThat(checker.apply(withTools)).isFalse();
        assertThat(trace.maxRoundsExceeded()).isTrue();
    }

    @Test
    void cappedCheckerIgnoresResponsesWithoutToolCalls() {
        val trace = new ToolTrace();
        val checker = ChatService.cappedChecker(trace, 1);
        val plain = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));

        assertThat(checker.apply(plain)).isFalse();
        assertThat(trace.maxRoundsExceeded()).isFalse();
    }

    private static ChatResponse chatResponseWithToolCall() {
        val message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id1", "function", "runReadCypher", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
