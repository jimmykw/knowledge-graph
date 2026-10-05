package net.jimmykw.knowledgegraph.chat.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class TypeSafeQuestionRouterTest {

    private static final io.vavr.collection.Map<RouteIntent, Double> CHITCHAT =
            HashMap.of(RouteIntent.GRAPH, 0.0, RouteIntent.CHITCHAT, 1.0);

    @Test
    void blocksWhenNonGraphMassReachesTheThreshold() {
        val router = new TypeSafeQuestionRouter(state -> CHITCHAT, 0.9);
        val decision = router.route("thanks", List.empty()).get();
        assertThat(decision.status()).isEqualTo(RouteStatus.BLOCKED);
        assertThat(decision.intent()).isEqualTo(RouteIntent.CHITCHAT);
    }

    @Test
    void routesWhenGraphMassIsAboveTheComplement() {
        val router = new TypeSafeQuestionRouter(state -> HashMap.of(RouteIntent.GRAPH, 0.5, RouteIntent.OFF_TOPIC, 0.5), 0.9);
        assertThat(router.route("hard disks", List.empty()).get().status()).isEqualTo(RouteStatus.ROUTED);
    }

    @Test
    void classifierFailureIsSkippedWithoutAnyRouterRetry() {
        val calls = new AtomicInteger();
        val router = new TypeSafeQuestionRouter(state -> {
            calls.incrementAndGet();
            throw new TypeSafeApiTimeoutException("timed out", java.time.Duration.ofSeconds(3), null);
        }, 0.9);
        val decision = router.route("hi", List.empty()).get();
        assertThat(calls).hasValue(1);
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
    void mapsProbabilityNamesToIntents() {
        val intents = TypeSafeIntentClassifier.toIntents(HashMap.of("GRAPH", 0.2, "OFF_TOPIC", 0.8));
        assertThat(intents.get(RouteIntent.OFF_TOPIC).get()).isEqualTo(0.8);
    }

    @Test
    void unknownOrMissingIntentsAreRejected() {
        assertThatThrownBy(() -> TypeSafeIntentClassifier.toIntents(HashMap.of("WEATHER", 1.0))).hasMessageContaining("Unknown intent");
        assertThatThrownBy(() -> TypeSafeIntentClassifier.toIntents(HashMap.empty())).hasMessageContaining("no usable intent");
    }
}
