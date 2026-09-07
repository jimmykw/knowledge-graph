package net.jimmykw.knowledgegraph.chat.eval;

import java.util.Map;

import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class KnowledgeGraphJudgeEvaluator implements Evaluator {

    private final EvalJudge judge;

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        val result = judge.score(request);
        log.info("--- judge score --- grounded: {} | correct: {} | rationale: {}",
                result.grounded(), result.correct(), result.rationale());
        return new EvaluationResponse(result.grounded() && result.correct(),
                result.rationale(),
                Map.of("grounded", result.grounded(), "correct", result.correct()));
    }
}
