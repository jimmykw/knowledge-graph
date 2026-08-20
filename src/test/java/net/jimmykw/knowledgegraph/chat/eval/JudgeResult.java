package net.jimmykw.knowledgegraph.chat.eval;

public record JudgeResult(boolean grounded, boolean correct, String rationale) {
    public JudgeResult {
        if (rationale == null) {
            rationale = "no rationale provided by judge";
        }
    }
}
