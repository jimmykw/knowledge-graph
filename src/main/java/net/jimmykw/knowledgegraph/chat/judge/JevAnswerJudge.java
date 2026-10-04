package net.jimmykw.knowledgegraph.chat.judge;

import java.util.List;
import java.util.Map;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.routing.SystemOneApi;
import org.springframework.ai.chat.messages.Message;

/**
 * Scores an agent answer with Jev: one attempt, advisory, fail-open. Both questions are ordinal {@code score} scales whose
 * {@code criteria} run worst to best; the answer's {@code score} is the expected level index, normalized here to 0..1.
 */
@Slf4j
@RequiredArgsConstructor
public class JevAnswerJudge implements AnswerJudge {

    static final String GROUNDED = "grounded";
    static final String RELEVANT = "relevant";

    private static final double EPSILON = 1e-9;
    private static final Map<String, Object> QUESTIONS = Map.of(
            GROUNDED, scale("Rate how well the retrieved rows support the assistant's answer.",
                    "The answer states facts that are missing from or contradicted by the rows, or states facts when no rows were retrieved",
                    "Some claims are supported by the rows, others are not",
                    "Every factual claim is supported by the rows, or the answer honestly says nothing was found"),
            RELEVANT, scale("Rate how well the answer addresses the user's latest question, using earlier turns for context.",
                    "The answer is about something other than the question",
                    "The answer partly addresses the question",
                    "The answer fully addresses the question, or honestly states that the requested information was not found"));

    private final SystemOneApi api;
    private final double minScore;
    private final int maxEvidenceChars;

    @Override
    public Option<AnswerQuality> judge(JudgeInput input) {
        val evidence = EvidenceFormatter.format(input.evidence(), maxEvidenceChars);
        val state = buildState(input, evidence);
        val quality = Try.of(() -> api.ask(state, QUESTIONS))
                .map(answers -> AnswerQuality.scored(level(answers, GROUNDED), level(answers, RELEVANT), minScore, evidence.truncated()))
                .onSuccess(scored -> log.info("Judge: {} grounded={} relevance={}", scored.status(), scored.grounded(), scored.relevance()))
                .onFailure(error -> log.warn("Judge: scoring failed, answer left unscored ({})", error.toString()))
                .getOrElse(AnswerQuality.skipped());
        return Option.of(quality);
    }

    static String buildState(JudgeInput input, EvidenceFormatter.Formatted evidence) {
        val transcript = input.history().map(message -> role(message) + ": " + message.getText()).mkString("\n");
        val conversation = transcript.isEmpty() ? "" : "Conversation so far:\n" + transcript + "\n\n";
        return conversation + "Question: " + input.question() + "\n\nRetrieved rows:\n" + evidence.text()
                + "\n\nAnswer: " + Option.of(input.answer()).getOrElse("");
    }

    private static String role(Message message) {
        return message.getMessageType().name().toLowerCase();
    }

    /** Normalizes a System One score (expected level index 0..n-1) to 0..1; rejects shapes that are not a usable scale. */
    @SuppressWarnings("unchecked")
    static double level(Map<String, Object> answers, String name) {
        val answer = Option.of(answers.get(name)).filter(Map.class::isInstance).map(raw -> (Map<String, Object>) raw)
                .getOrElseThrow(() -> new IllegalStateException("Missing answer: " + name));
        val levels = Option.of(answer.get("legend")).filter(Map.class::isInstance).map(raw -> ((Map<String, Object>) raw).size())
                .filter(size -> size >= 2).getOrElseThrow(() -> new IllegalStateException("Answer has no usable scale: " + name));
        val score = Option.of(answer.get("score")).filter(Number.class::isInstance).map(raw -> ((Number) raw).doubleValue())
                .getOrElseThrow(() -> new IllegalStateException("Answer has no score: " + name));
        val normalized = score / (levels - 1);
        if (normalized < -EPSILON || normalized > 1 + EPSILON) {
            throw new IllegalStateException("Score out of range for " + name + ": " + score);
        }
        return Math.min(1.0, Math.max(0.0, normalized));
    }

    private static Map<String, Object> scale(String instructions, String... levels) {
        return Map.of("type", "score", "instructions", instructions, "criteria", List.of(levels));
    }
}
