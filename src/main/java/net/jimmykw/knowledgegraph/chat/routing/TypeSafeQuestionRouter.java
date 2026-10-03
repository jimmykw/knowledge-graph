package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.client.ResourceAccessException;

/** Classifies the prompt with Jev; applies the block threshold, retries once on transport errors, fails open. */
@Slf4j
@RequiredArgsConstructor
public class TypeSafeQuestionRouter implements QuestionRouter {

    private static final int MAX_TRIES = 2;

    private final SystemOneClient client;
    private final double blockThreshold;

    @Override
    public Option<RouteDecision> route(String prompt, List<Message> history) {
        val state = buildState(prompt, history);
        val decision = attempt(state, 1)
                .map(probabilities -> RouteDecision.decide(probabilities, blockThreshold))
                .onSuccess(routed -> log.info("Routing: {} intent={} graphP={}", routed.status(), routed.intent(), routed.graphProbability()))
                .onFailure(error -> log.warn("Routing: classification failed, running agent ({})", error.toString()))
                .getOrElse(RouteDecision.skipped());
        return Option.of(decision);
    }

    static String buildState(String prompt, List<Message> history) {
        val transcript = history.map(message -> message.getMessageType().name().toLowerCase() + ": " + message.getText()).mkString("\n");
        val conversation = transcript.isEmpty() ? "" : "Conversation so far:\n" + transcript + "\n\n";
        return conversation + "New message: " + prompt;
    }

    private Try<io.vavr.collection.Map<RouteIntent, Double>> attempt(String state, int tryNumber) {
        val result = Try.of(() -> client.classify(state));
        return result.isFailure() && tryNumber < MAX_TRIES && isTransport(result.getCause())
                ? attempt(state, tryNumber + 1)
                : result;
    }

    private static boolean isTransport(Throwable cause) {
        return cause instanceof ResourceAccessException;
    }
}
