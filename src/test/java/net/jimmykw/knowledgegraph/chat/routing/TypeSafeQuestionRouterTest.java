package net.jimmykw.knowledgegraph.chat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class TypeSafeQuestionRouterTest {

    private static final io.vavr.collection.Map<RouteIntent, Double> CHITCHAT =
            HashMap.of(RouteIntent.GRAPH, 0.0, RouteIntent.CHITCHAT, 1.0);

    @Test
    void retriesOnceOnTransportErrorThenSucceeds() {
        val calls = new AtomicInteger();
        val router = new TypeSafeQuestionRouter(state -> {
            if (calls.incrementAndGet() == 1) {
                throw new ResourceAccessException("timeout");
            }
            return CHITCHAT;
        }, 0.9);
        val decision = router.route("thanks", List.empty()).get();
        assertThat(calls).hasValue(2);
        assertThat(decision.status()).isEqualTo(RouteStatus.BLOCKED);
    }

    @Test
    void doesNotRetryOnAuthError() {
        val calls = new AtomicInteger();
        val router = new TypeSafeQuestionRouter(state -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "no", null, null, null);
        }, 0.9);
        assertThat(router.route("hi", List.empty()).get().status()).isEqualTo(RouteStatus.SKIPPED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void doubleFailureIsSkipped() {
        val calls = new AtomicInteger();
        val router = new TypeSafeQuestionRouter(state -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("down");
        }, 0.9);
        val decision = router.route("hi", List.empty()).get();
        assertThat(calls).hasValue(2);
        assertThat(decision.status()).isEqualTo(RouteStatus.SKIPPED);
        assertThat(decision.intent()).isNull();
    }

    @Test
    void stateIncludesHistoryAndNewMessage() {
        val state = TypeSafeQuestionRouter.buildState("which of those?",
                List.of(new UserMessage("What did IBM create?"), new AssistantMessage("FORTRAN")));
        assertThat(state).isEqualTo("Conversation so far:\nuser: What did IBM create?\nassistant: FORTRAN\n\nNew message: which of those?");
        assertThat(TypeSafeQuestionRouter.buildState("hi", List.empty())).isEqualTo("New message: hi");
    }

    @Test
    void parsesSystemOneResponse() {
        val answers = java.util.Map.<String, Object>of("intent",
                java.util.Map.of("probabilities", java.util.Map.of("GRAPH", 0.2, "OFF_TOPIC", 0.8)));
        assertThat(RestSystemOneClient.parse(answers).get(RouteIntent.OFF_TOPIC).get()).isEqualTo(0.8);
    }
}
