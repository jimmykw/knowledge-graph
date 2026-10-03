package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.Map;
import io.vavr.collection.Stream;
import lombok.val;

/**
 * @param intent           null when status is SKIPPED
 * @param graphProbability the classifier's probability for GRAPH (0.0 when SKIPPED)
 */
public record RouteDecision(RouteIntent intent, double graphProbability, RouteStatus status) {

    private static final double EPSILON = 1e-9;

    public static RouteDecision skipped() {
        return new RouteDecision(null, 0.0, RouteStatus.SKIPPED);
    }

    /** Blocks when the combined probability of all non-GRAPH intents is at least {@code blockThreshold}. */
    public static RouteDecision decide(Map<RouteIntent, Double> probabilities, double blockThreshold) {
        val graph = probabilities.get(RouteIntent.GRAPH).getOrElse(0.0);
        val blocked = 1.0 - graph >= blockThreshold - EPSILON;
        val candidates = blocked ? probabilities.filterKeys(intent -> intent != RouteIntent.GRAPH) : probabilities;
        val top = Stream.ofAll(candidates).maxBy(entry -> entry._2).map(entry -> entry._1).getOrElse(RouteIntent.GRAPH);
        return new RouteDecision(top, graph, blocked ? RouteStatus.BLOCKED : RouteStatus.ROUTED);
    }
}
