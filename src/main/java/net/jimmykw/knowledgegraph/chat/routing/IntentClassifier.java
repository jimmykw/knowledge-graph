package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.Map;

/** Classifies a conversation state into per-intent probabilities; throws when the classification could not be obtained. */
public interface IntentClassifier {

    Map<RouteIntent, Double> classify(String state);
}
