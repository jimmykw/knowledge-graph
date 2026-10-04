package net.jimmykw.knowledgegraph.chat.judge;

import io.vavr.control.Option;

public interface AnswerJudge {

    /** @return none when the judge is disabled; SKIPPED when it is enabled but could not score */
    Option<AnswerQuality> judge(JudgeInput input);
}
