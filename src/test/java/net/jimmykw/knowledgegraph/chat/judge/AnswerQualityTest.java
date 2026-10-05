package net.jimmykw.knowledgegraph.chat.judge;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.val;
import org.junit.jupiter.api.Test;

class AnswerQualityTest {

    @Test
    void okHasNoFeedback() {
        val ok = AnswerQuality.ok(false);
        assertThat(ok.status()).isEqualTo(QualityStatus.OK);
        assertThat(ok.feedback()).isNull();
    }

    @Test
    void lowCarriesTheJudgesFeedback() {
        val low = AnswerQuality.low("grounded: rated \"Some claims supported\"", false);
        assertThat(low.status()).isEqualTo(QualityStatus.LOW);
        assertThat(low.feedback()).contains("Some claims supported");
    }

    @Test
    void skippedHasNoFeedbackAndNoTruncation() {
        val skipped = AnswerQuality.skipped();
        assertThat(skipped.status()).isEqualTo(QualityStatus.SKIPPED);
        assertThat(skipped.feedback()).isNull();
        assertThat(skipped.evidenceTruncated()).isFalse();
    }

    @Test
    void carriesTruncationFlag() {
        assertThat(AnswerQuality.ok(true).evidenceTruncated()).isTrue();
        assertThat(AnswerQuality.low("x", true).evidenceTruncated()).isTrue();
    }
}
