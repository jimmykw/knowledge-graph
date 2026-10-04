package net.jimmykw.knowledgegraph.chat.judge;

import io.vavr.control.Option;

public class NoOpAnswerJudge implements AnswerJudge {

    @Override
    public Option<AnswerQuality> judge(JudgeInput input) {
        return Option.none();
    }
}
