package net.jimmykw.knowledgegraph.chat.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.QueryEvidence;
import net.jimmykw.knowledgegraph.chat.routing.SystemOneApi;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.client.ResourceAccessException;

class JevAnswerJudgeTest {

    private static Map<String, Object> scale(double score, int levels) {
        val legend = new java.util.LinkedHashMap<String, Object>();
        java.util.stream.IntStream.range(0, levels).forEach(index -> legend.put(String.valueOf(index), "level " + index));
        return Map.of("type", "score", "score", score, "legend", legend);
    }

    private static Map<String, Object> answers(double grounded, double relevant) {
        return Map.of("grounded", scale(grounded, 3), "relevant", scale(relevant, 3));
    }

    private static JudgeInput input() {
        val rows = List.of(new QueryEvidence("MATCH (n) RETURN n.name", java.util.List.of(Map.of("n.name", "FORTRAN")), 1, false, null));
        return new JudgeInput("What did IBM create?", List.of(new UserMessage("hi"), new AssistantMessage("hello")), rows,
                "IBM created FORTRAN.");
    }

    private static JevAnswerJudge judge(SystemOneApi api) {
        return new JevAnswerJudge(api, 0.7, 8000);
    }

    @Test
    void normalizesScoresToZeroToOneAndAppliesThreshold() {
        val quality = judge((state, questions) -> answers(2.0, 1.0)).judge(input()).get();
        assertThat(quality.grounded()).isEqualTo(1.0);
        assertThat(quality.relevance()).isEqualTo(0.5);
        assertThat(quality.status()).isEqualTo(QualityStatus.LOW);
    }

    @Test
    void highScoresAreOk() {
        assertThat(judge((state, questions) -> answers(1.9, 2.0)).judge(input()).get().status()).isEqualTo(QualityStatus.OK);
    }

    @Test
    void stateHoldsHistoryQuestionRowsAndAnswerInDocumentedLayout() {
        val captured = new AtomicReference<String>();
        val questionNames = new AtomicReference<java.util.Set<String>>();
        judge((state, questions) -> {
            captured.set(state);
            questionNames.set(questions.keySet());
            return answers(2, 2);
        }).judge(input());
        assertThat(captured.get()).isEqualTo("Conversation so far:\nuser: hi\nassistant: hello\n\nQuestion: What did IBM create?\n\n"
                + "Retrieved rows:\n[query 1] MATCH (n) RETURN n.name\n{\"n.name\":\"FORTRAN\"}\n\nAnswer: IBM created FORTRAN.");
        assertThat(questionNames.get()).containsExactlyInAnyOrder("grounded", "relevant");
    }

    @Test
    void emptyEvidenceAndHistoryRenderNoRowsStatement() {
        val captured = new AtomicReference<String>();
        judge((state, questions) -> {
            captured.set(state);
            return answers(2, 2);
        }).judge(new JudgeInput("q", List.empty(), List.empty(), "a"));
        assertThat(captured.get()).isEqualTo("Question: q\n\nRetrieved rows:\n(no rows were retrieved)\n\nAnswer: a");
    }

    @Test
    void failuresAreSkippedWithoutRetry() {
        val calls = new AtomicInteger();
        val quality = judge((state, questions) -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("timeout");
        }).judge(input()).get();
        assertThat(calls).hasValue(1);
        assertThat(quality.status()).isEqualTo(QualityStatus.SKIPPED);
    }

    @Test
    void missingOrMalformedAnswersAreSkipped() {
        assertThat(judge((state, questions) -> Map.of("grounded", scale(2, 3))).judge(input()).get().status())
                .isEqualTo(QualityStatus.SKIPPED);
        assertThat(judge((state, questions) -> Map.of("grounded", scale(2, 1), "relevant", scale(1, 3))).judge(input()).get().status())
                .as("a one-level scale is not a usable scale").isEqualTo(QualityStatus.SKIPPED);
    }

    @Test
    void outOfRangeScoreIsSkipped() {
        assertThat(judge((state, questions) -> answers(3.5, 2)).judge(input()).get().status()).isEqualTo(QualityStatus.SKIPPED);
        assertThat(judge((state, questions) -> answers(-0.5, 2)).judge(input()).get().status()).isEqualTo(QualityStatus.SKIPPED);
    }

    @Test
    void nullAnswerIsJudgedAsEmpty() {
        val captured = new AtomicReference<String>();
        judge((state, questions) -> {
            captured.set(state);
            return answers(0, 0);
        }).judge(new JudgeInput("q", List.empty(), List.empty(), null));
        assertThat(captured.get()).endsWith("Answer: ");
    }

    @Test
    void evidenceTruncationIsReported() {
        val rows = List.of(new QueryEvidence("MATCH (n) RETURN n", java.util.List.of(Map.of("n", "x")), 1, true, null));
        val quality = judge((state, questions) -> answers(2, 2)).judge(new JudgeInput("q", List.empty(), rows, "a")).get();
        assertThat(quality.evidenceTruncated()).isTrue();
    }
}
