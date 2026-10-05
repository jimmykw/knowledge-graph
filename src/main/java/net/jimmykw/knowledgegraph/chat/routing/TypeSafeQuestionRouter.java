package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springframework.ai.chat.messages.Message;

/** Classifies the prompt with Jev and applies the block threshold; the SDK retries transport errors, any failure fails open. */
@Slf4j
@RequiredArgsConstructor
public class TypeSafeQuestionRouter implements QuestionRouter {

    private final IntentClassifier classifier;
    private final double blockThreshold;

    @Override
    public Option<RouteDecision> route(String prompt, List<Message> history) {
        val state = buildState(prompt, history);
        val decision = Try.of(() -> classifier.classify(state))
                .map(probabilities -> RouteDecision.decide(probabilities, blockThreshold))
                .onSuccess(routed -> log.info("Routing: {} intent={} graphP={}", routed.status(), routed.intent(), routed.graphProbability()))
                .onFailure(error -> log.warn("Routing: classification failed, running agent ({}{})", error.toString(), requestId(error)))
                .getOrElse(RouteDecision.skipped());
        return Option.of(decision);
    }

    static String buildState(String prompt, List<Message> history) {
        val transcript = history.map(message -> message.getMessageType().name().toLowerCase() + ": " + message.getText()).mkString("\n");
        val conversation = transcript.isEmpty() ? "" : "Conversation so far:\n" + transcript + "\n\n";
        return conversation + "New message: " + prompt;
    }

    private static String requestId(Throwable error) {
        return error instanceof TypeSafeApiException api && api.requestId() != null ? ", requestId=" + api.requestId() : "";
    }
}
