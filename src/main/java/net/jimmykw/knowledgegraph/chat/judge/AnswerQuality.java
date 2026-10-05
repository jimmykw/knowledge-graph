package net.jimmykw.knowledgegraph.chat.judge;

/**
 * Advisory verdict for one agent answer.
 *
 * @param feedback          why the answer is LOW, as rendered by the judge; null for OK and SKIPPED
 * @param evidenceTruncated true when some retrieved rows were not shown to the judge, so a low verdict may mean "unverified"
 */
public record AnswerQuality(QualityStatus status, String feedback, boolean evidenceTruncated) {

    public static AnswerQuality skipped() {
        return new AnswerQuality(QualityStatus.SKIPPED, null, false);
    }

    public static AnswerQuality ok(boolean evidenceTruncated) {
        return new AnswerQuality(QualityStatus.OK, null, evidenceTruncated);
    }

    public static AnswerQuality low(String feedback, boolean evidenceTruncated) {
        return new AnswerQuality(QualityStatus.LOW, feedback, evidenceTruncated);
    }
}
