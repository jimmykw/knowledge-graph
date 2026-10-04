package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.judge.NoOpAnswerJudge;
import net.jimmykw.knowledgegraph.chat.routing.QuestionRouter;
import net.jimmykw.knowledgegraph.chat.routing.RouteDecision;
import net.jimmykw.knowledgegraph.chat.routing.RouteIntent;
import net.jimmykw.knowledgegraph.chat.routing.RouteStatus;
import net.jimmykw.knowledgegraph.chat.store.BlockedMessages;
import net.jimmykw.knowledgegraph.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

class ChatServiceRoutingTest {

    private ChatClient client;
    private ChatClient direct;
    private ChatMemory memory;

    @BeforeEach
    void setUp() {
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.mutate().defaultAdvisors(any(org.springframework.ai.chat.client.advisor.api.Advisor.class)).build()
                .prompt(any(String.class)).advisors(any(java.util.function.Consumer.class)).call().content()).thenReturn("agent answer");
        direct = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(direct.prompt().messages(any(java.util.List.class)).user(any(String.class)).call().content()).thenReturn("direct answer");
        memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
    }

    private ChatService service(Option<RouteDecision> decision) {
        QuestionRouter router = (prompt, history) -> decision;
        return new ChatService(client, direct, new AppProperties(0, 0, null, null, null, null), memory, router, new NoOpAnswerJudge());
    }

    @Test
    void blockedReturnsCannedReplyWithoutAgentAndStoresBothMessages() {
        val decision = new RouteDecision(RouteIntent.CHITCHAT, 0.0, RouteStatus.BLOCKED);
        val untouched = mock(ChatClient.class);
        QuestionRouter router = (prompt, history) -> Option.of(decision);
        val service = new ChatService(untouched, direct, new AppProperties(0, 0, null, null, null, null), memory, router, new NoOpAnswerJudge());
        val response = service.chat("thanks!", "c1");
        assertThat(response.answer()).isEqualTo("direct answer");
        assertThat(response.route()).isEqualTo(decision);
        assertThat(response.rowCount()).isZero();
        assertThat(response.evidence()).isEmpty();
        verifyNoInteractions(untouched);
        assertThat(memory.get("c1")).hasSize(2).allMatch(BlockedMessages::isBlocked);
    }

    @Test
    void skippedRunsAgentAndAttachesRoute() {
        val decision = RouteDecision.skipped();
        val response = service(Option.of(decision)).chat("What did IBM create?", "c2");
        assertThat(response.answer()).isEqualTo("agent answer");
        assertThat(response.route()).isEqualTo(decision);
    }

    @Test
    void noRouterDecisionRunsAgentWithNullRoute() {
        val response = service(Option.none()).chat("What did IBM create?", "c3");
        assertThat(response.answer()).isEqualTo("agent answer");
        assertThat(response.route()).isNull();
    }
}
