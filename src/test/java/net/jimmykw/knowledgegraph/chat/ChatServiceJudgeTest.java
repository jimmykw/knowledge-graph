package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.judge.AnswerJudge;
import net.jimmykw.knowledgegraph.chat.judge.AnswerQuality;
import net.jimmykw.knowledgegraph.chat.judge.JudgeInput;
import net.jimmykw.knowledgegraph.chat.judge.QualityStatus;
import net.jimmykw.knowledgegraph.chat.routing.QuestionRouter;
import net.jimmykw.knowledgegraph.chat.routing.RouteDecision;
import net.jimmykw.knowledgegraph.chat.routing.RouteIntent;
import net.jimmykw.knowledgegraph.chat.routing.RouteStatus;
import net.jimmykw.knowledgegraph.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ChatServiceJudgeTest {

    private ChatClient agent;
    private ChatClient direct;
    private ChatMemory memory;

    @BeforeEach
    void setUp() {
        agent = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(agent.mutate().defaultAdvisors(any(org.springframework.ai.chat.client.advisor.api.Advisor.class)).build()
                .prompt(any(String.class)).advisors(any(java.util.function.Consumer.class)).call().content()).thenReturn("agent answer");
        direct = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(direct.prompt().messages(any(java.util.List.class)).user(any(String.class)).call().content()).thenReturn("direct answer");
        memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
    }

    private ChatService service(Option<RouteDecision> route, AnswerJudge judge) {
        QuestionRouter router = (prompt, history) -> route;
        return new ChatService(agent, direct, new AppProperties(0, 0, null, null, null, null), memory, router, judge);
    }

    @Test
    void agentTurnCarriesQualityAndSeesHistoryBeforeTheTurn() {
        memory.add("c1", java.util.List.of(new UserMessage("earlier q"), new AssistantMessage("earlier a")));
        val seen = new AtomicReference<JudgeInput>();
        val quality = AnswerQuality.scored(0.9, 0.8, 0.7, false);
        val response = service(Option.none(), input -> {
            seen.set(input);
            return Option.of(quality);
        }).chat("What did IBM create?", "c1");
        assertThat(response.answer()).isEqualTo("agent answer");
        assertThat(response.quality()).isEqualTo(quality);
        assertThat(seen.get().question()).isEqualTo("What did IBM create?");
        assertThat(seen.get().answer()).isEqualTo("agent answer");
        assertThat(seen.get().history()).extracting(message -> message.getText()).containsExactly("earlier q", "earlier a");
    }

    @Test
    void blockedTurnsAreNotJudged() {
        val judged = new AtomicReference<Boolean>(false);
        val blocked = new RouteDecision(RouteIntent.OFF_TOPIC, 0.0, RouteStatus.BLOCKED);
        val response = service(Option.of(blocked), input -> {
            judged.set(true);
            return Option.of(AnswerQuality.skipped());
        }).chat("Who won the World Cup?", "c2");
        assertThat(judged.get()).isFalse();
        assertThat(response.quality()).isNull();
        assertThat(response.answer()).isEqualTo("direct answer");
    }

    @Test
    void skippedOrDisabledJudgeNeverChangesTheAnswer() {
        val skipped = service(Option.none(), input -> Option.of(AnswerQuality.skipped())).chat("q", "c3");
        assertThat(skipped.answer()).isEqualTo("agent answer");
        assertThat(skipped.quality().status()).isEqualTo(QualityStatus.SKIPPED);
        val disabled = service(Option.none(), input -> Option.none()).chat("q", "c4");
        assertThat(disabled.answer()).isEqualTo("agent answer");
        assertThat(disabled.quality()).isNull();
    }

    @Test
    void aThrowingJudgeNeverFailsTheChatCall() {
        val response = service(Option.none(), input -> {
            throw new IllegalStateException("boom");
        }).chat("q", "c5");
        assertThat(response.answer()).isEqualTo("agent answer");
        assertThat(response.quality()).isNull();
    }
}
