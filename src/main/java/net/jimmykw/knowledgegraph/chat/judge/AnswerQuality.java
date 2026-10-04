package net.jimmykw.knowledgegraph.chat.judge;

import lombok.val;

/**
 * Advisory scores for one agent answer, each 0.0 (worst) to 1.0 (best).
 *
 * @param grounded         how well the retrieved rows support the answer; null when SKIPPED
 * @param relevance        how well the answer addresses the question; null when SKIPPED
 * @param evidenceTruncated true when some retrieved rows were not shown to the judge, so a low score may mean "unverified"
 */
public record AnswerQuality(Double grounded, Double relevance, QualityStatus status, boolean evidenceTruncated, double minScore) {

    public static AnswerQuality skipped() {
        return new AnswerQuality(null, null, QualityStatus.SKIPPED, false, 0.0);
    }

    /** LOW when either dimension is below {@code minScore}; a score exactly at the threshold is OK. */
    public static AnswerQuality scored(double grounded, double relevance, double minScore, boolean evidenceTruncated) {
        val low = grounded < minScore || relevance < minScore;
        return new AnswerQuality(grounded, relevance, low ? QualityStatus.LOW : QualityStatus.OK, evidenceTruncated, minScore);
    }
}
