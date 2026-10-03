package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.Map;

/** Sends the single {@code intent} Choice question and returns the per-intent probabilities. */
public interface SystemOneClient {

    Map<RouteIntent, Double> classify(String state);
}
