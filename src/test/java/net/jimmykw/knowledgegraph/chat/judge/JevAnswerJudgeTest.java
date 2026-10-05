package net.jimmykw.knowledgegraph.chat.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.QueryEvidence;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.judge.JevFinding;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevJudgeInput;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Mapping and input-building tests with a mocked JevJudge; the HTTP behavior is covered in TypeSafeWireMockTest. */
class JevAnswerJudgeTest {

    private static JudgeInput input() {
        val rows = List.of(new QueryEvidence("MATCH (n) RETURN n.name", java.util.List.of(Map.of("n.name", "FORTRAN")), 1, false, null));
        return new JudgeInput("What did IBM create?", List.of(new UserMessage("hi"), new AssistantMessage("hello")), rows,
                "IBM created FORTRAN.");
    }

    private static JevFinding finding(JevFinding.Outcome outcome) {
        return new JevFinding(null, null, outcome, "detail");
    }

    private static JevAnswerJudge judgeReturning(JevVerdict verdict) {
        val jev = mock(JevJudge.class);
        when(jev.judge(any(JevJudgeInput.class))).thenReturn(verdict);
        return new JevAnswerJudge(jev, 8000);
    }

    @Test
    void passedVerdictIsOk() {
        val quality = judgeReturning(new JevVerdict(true, java.util.List.of(), null, "")).judge(input()).get();
        assertThat(quality.status()).isEqualTo(QualityStatus.OK);
        assertThat(quality.feedback()).isNull();
    }

    @Test
    void failedVerdictIsLowWithTheLibrariesFeedback() {
        val verdict = new JevVerdict(false, java.util.List.of(finding(JevFinding.Outcome.FAILED)), null, "- grounded: rated low");
        val quality = judgeReturning(verdict).judge(input()).get();
        assertThat(quality.status()).isEqualTo(QualityStatus.LOW);
        assertThat(quality.feedback()).isEqualTo("- grounded: rated low");
    }

    @Test
    void errorFindingIsSkippedEvenWhenTheVerdictPassed() {
        val verdict = new JevVerdict(true, java.util.List.of(finding(JevFinding.Outcome.ERROR)), null, "");
        assertThat(judgeReturning(verdict).judge(input()).get().status()).isEqualTo(QualityStatus.SKIPPED);
    }

    @Test
    void failedCallIsSkippedWithoutRetry() {
        val jev = mock(JevJudge.class);
        when(jev.judge(any(JevJudgeInput.class))).thenThrow(new TypeSafeApiConnectionException("down", null));
        assertThat(new JevAnswerJudge(jev, 8000).judge(input()).get().status()).isEqualTo(QualityStatus.SKIPPED);
        org.mockito.Mockito.verify(jev, org.mockito.Mockito.times(1)).judge(any(JevJudgeInput.class));
    }

    @Test
    void inputCarriesQuestionAnswerRowsAndConversationInTheLibrariesFields() {
        val evidence = EvidenceFormatter.format(input().evidence(), 8000);
        val jevInput = JevAnswerJudge.toInput(input(), evidence);
        assertThat(jevInput.question()).isEqualTo("What did IBM create?");
        assertThat(jevInput.answer()).isEqualTo("IBM created FORTRAN.");
        assertThat(jevInput.context()).containsExactly("[query 1] MATCH (n) RETURN n.name\n{\"n.name\":\"FORTRAN\"}");
        assertThat(jevInput.field(JevAnswerJudge.CONVERSATION_FIELD, String.class)).isEqualTo("user: hi\nassistant: hello");
    }

    @Test
    void emptyEvidenceHistoryAndAnswerAreRenderedExplicitly() {
        val empty = new JudgeInput("q", List.empty(), List.empty(), null);
        val jevInput = JevAnswerJudge.toInput(empty, EvidenceFormatter.format(empty.evidence(), 8000));
        assertThat(jevInput.context()).containsExactly(EvidenceFormatter.NO_ROWS);
        assertThat(jevInput.answer()).isEmpty();
        assertThat(jevInput.field(JevAnswerJudge.CONVERSATION_FIELD, String.class)).isEqualTo(JevAnswerJudge.NO_CONVERSATION);
    }

    @Test
    void evidenceTruncationIsReported() {
        val rows = List.of(new QueryEvidence("MATCH (n) RETURN n", java.util.List.of(Map.of("n", "x")), 1, true, null));
        val quality = judgeReturning(new JevVerdict(true, java.util.List.of(), null, ""))
                .judge(new JudgeInput("q", List.empty(), rows, "a")).get();
        assertThat(quality.evidenceTruncated()).isTrue();
    }
}
