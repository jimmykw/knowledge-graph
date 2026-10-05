package net.jimmykw.knowledgegraph.chat.judge;

import java.util.List;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevJudgeInput;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springaicommunity.typesafe.question.Score;
import org.springframework.ai.chat.messages.Message;

/**
 * Judges an agent answer with a {@link JevJudge}: advisory and fail-open. Two ordinal {@code Score} criteria (groundedness, relevance) must
 * both reach the top level; an inconclusive criterion counts as LOW, a criterion Jev could not answer or a failed call leaves the answer
 * SKIPPED.
 */
@Slf4j
@RequiredArgsConstructor
public class JevAnswerJudge implements AnswerJudge {

    static final String GROUNDED = "grounded";
    static final String RELEVANT = "relevant";
    static final String CONVERSATION_FIELD = "conversation";
    static final String NO_CONVERSATION = "(no earlier conversation)";

    private static final int TOP_LEVEL = 2;

    private final JevJudge judge;
    private final int maxEvidenceChars;

    public static JevJudge buildJudge(TypeSafeClient client) {
        return JevJudge.builder(client)
                .score(GROUNDED, Score.of("Rate how well the retrieved rows in `supporting_context` support the assistant's answer in "
                        + "`assistant_answer`.",
                        "The answer states facts that are missing from or contradicted by the rows, or states facts when no rows were retrieved",
                        "Some claims are supported by the rows, others are not",
                        "Every factual claim is supported by the rows, or the answer honestly says nothing was found"), TOP_LEVEL)
                .score(RELEVANT, Score.of("Rate how well `assistant_answer` addresses the user's latest question in `user_question`, using "
                        + "the earlier turns in `conversation` for context.",
                        "The answer is about something other than the question",
                        "The answer partly addresses the question",
                        "The answer fully addresses the question, or honestly states that the requested information was not found"), TOP_LEVEL)
                .failOnInconclusive(true)
                .build();
    }

    @Override
    public Option<AnswerQuality> judge(JudgeInput input) {
        val evidence = EvidenceFormatter.format(input.evidence(), maxEvidenceChars);
        val jevInput = toInput(input, evidence);
        val quality = Try.of(() -> judge.judge(jevInput))
                .map(verdict -> toQuality(verdict, evidence.truncated()))
                .onSuccess(scored -> log.info("Judge: {}", scored.status()))
                .onFailure(error -> log.warn("Judge: scoring failed, answer left unscored ({}{})", error.toString(), requestId(error)))
                .getOrElse(AnswerQuality.skipped());
        return Option.of(quality);
    }

    static JevJudgeInput toInput(JudgeInput input, EvidenceFormatter.Formatted evidence) {
        return JevJudgeInput.builder()
                .question(input.question())
                .answer(Option.of(input.answer()).getOrElse(""))
                .context(List.of(evidence.text()))
                .field(CONVERSATION_FIELD, transcript(input.history()))
                .build();
    }

    static AnswerQuality toQuality(JevVerdict verdict, boolean evidenceTruncated) {
        if (!verdict.errors().isEmpty()) {
            return AnswerQuality.skipped();
        }
        return verdict.passed() ? AnswerQuality.ok(evidenceTruncated) : AnswerQuality.low(verdict.feedback(), evidenceTruncated);
    }

    private static String transcript(io.vavr.collection.List<Message> history) {
        val text = history.map(message -> message.getMessageType().name().toLowerCase() + ": " + message.getText()).mkString("\n");
        return text.isEmpty() ? NO_CONVERSATION : text;
    }

    private static String requestId(Throwable error) {
        return error instanceof TypeSafeApiException api && api.requestId() != null ? ", requestId=" + api.requestId() : "";
    }
}
