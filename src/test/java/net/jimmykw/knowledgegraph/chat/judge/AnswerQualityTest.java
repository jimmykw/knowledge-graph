package net.jimmykw.knowledgegraph.chat.judge;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.val;
import org.junit.jupiter.api.Test;

class AnswerQualityTest {

    @Test
    void scoreExactlyAtThresholdIsOk() {
        assertThat(AnswerQuality.scored(0.7, 0.7, 0.7, false).status()).isEqualTo(QualityStatus.OK);
    }

    @Test
    void eitherDimensionBelowThresholdIsLow() {
        assertThat(AnswerQuality.scored(0.69, 1.0, 0.7, false).status()).isEqualTo(QualityStatus.LOW);
        assertThat(AnswerQuality.scored(1.0, 0.1, 0.7, false).status()).isEqualTo(QualityStatus.LOW);
    }

    @Test
    void skippedHasNoScores() {
        val skipped = AnswerQuality.skipped();
        assertThat(skipped.status()).isEqualTo(QualityStatus.SKIPPED);
        assertThat(skipped.grounded()).isNull();
        assertThat(skipped.relevance()).isNull();
    }

    @Test
    void carriesTruncationFlag() {
        assertThat(AnswerQuality.scored(1.0, 1.0, 0.7, true).evidenceTruncated()).isTrue();
    }
}
