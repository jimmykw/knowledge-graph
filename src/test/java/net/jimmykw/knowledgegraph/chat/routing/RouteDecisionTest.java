package net.jimmykw.knowledgegraph.chat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.vavr.collection.HashMap;
import lombok.val;
import org.junit.jupiter.api.Test;

class RouteDecisionTest {

    @Test
    void blocksAtExactlyTheThreshold() {
        val d = RouteDecision.decide(HashMap.of(RouteIntent.GRAPH, 0.1, RouteIntent.OFF_TOPIC, 0.56, RouteIntent.UNANSWERABLE, 0.34), 0.9);
        assertThat(d.status()).isEqualTo(RouteStatus.BLOCKED);
        assertThat(d.intent()).isEqualTo(RouteIntent.OFF_TOPIC);
    }

    @Test
    void graphDominantNeverBlocks() {
        val d = RouteDecision.decide(HashMap.of(RouteIntent.GRAPH, 0.99, RouteIntent.CHITCHAT, 0.01), 0.9);
        assertThat(d.status()).isEqualTo(RouteStatus.ROUTED);
        assertThat(d.intent()).isEqualTo(RouteIntent.GRAPH);
    }

    @Test
    void lowConfidenceNonGraphRoutesToAgent() {
        val d = RouteDecision.decide(HashMap.of(RouteIntent.GRAPH, 0.3, RouteIntent.OFF_TOPIC, 0.7), 0.9);
        assertThat(d.status()).isEqualTo(RouteStatus.ROUTED);
        assertThat(d.graphProbability()).isEqualTo(0.3);
    }

    @Test
    void probeQuestionAtGraph081IsNotBlocked() {
        val d = RouteDecision.decide(HashMap.of(RouteIntent.GRAPH, 0.81, RouteIntent.UNANSWERABLE, 0.19), 0.9);
        assertThat(d.status()).isEqualTo(RouteStatus.ROUTED);
    }
}
