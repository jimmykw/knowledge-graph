package net.jimmykw.knowledgegraph.chat.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class EvalJudge {

    private static final String SYSTEM = """
            You are a strict eval grader for a knowledge-graph question-answering agent. The agent
            answers questions by querying a Neo4j knowledge graph with read-only Cypher and then
            synthesizing an answer from the returned rows. You receive the user question, the
            expected landmark facts a correct answer should mention (possibly empty), the agent's
            final answer, and the Cypher queries plus rows the agent retrieved. The expected facts
            are a correctness reference ONLY — they are not evidence. Decide:
            - grounded: true if the answer states only facts supported by the returned rows (the
              "Retrieved evidence" section) and invents no entities, relationships, numbers, or
              dates the rows did not contain; a fact that appears only in the expected facts does
              not make a claim grounded; false otherwise.
            - correct: true if the answer addresses the question and, when expected facts are listed,
              mentions at least one of them; false otherwise.
            Keep the rationale under 60 words. Return ONLY JSON matching the requested schema.
            """;

    /**
     * False-premise probes ("When did IBM acquire Google?") are graded on fabrication of the
     * asked event, not on the strict all-facts-from-rows rubric — a good probe answer refutes
     * the premise, which the strict rubric punishes whenever the agent adds clearly-labeled
     * general-knowledge context.
     */
    private static final String PROBE_SYSTEM = """
            You are a strict eval grader for a knowledge-graph question-answering agent, grading a
            false-premise probe: the user question presupposes an event the knowledge graph does not
            record. The agent answers by querying a Neo4j knowledge graph with read-only Cypher. You
            receive the user question, the agent's final answer, and the Cypher queries plus rows the
            agent retrieved. Decide:
            - grounded: false if the answer accepts the false premise, invents a date or details for
              the asked event, fabricates graph entities or relationships, or presents out-of-graph
              claims as if they came from the graph. true otherwise — refuting the premise is the
              goal, and facts the rows did not return do not break grounding when the answer
              explicitly flags them as general knowledge rather than graph facts.
            - correct: true if the answer addresses the question; refuting the false premise or
              stating the graph has no record of the event counts as addressing it.
            Keep the rationale under 60 words. Return ONLY JSON matching the requested schema.
            """;

    private final ChatClient evalJudgeClient;
    private final BeanOutputConverter<JudgeResult> converter;

    public JudgeResult score(EvaluationRequest request) {
        return score(request, SYSTEM);
    }

    public JudgeResult scoreProbe(EvaluationRequest request) {
        return score(request, PROBE_SYSTEM);
    }

    private JudgeResult score(EvaluationRequest request, String systemPrompt) {
        val format = converter.getFormat();
        val prompt = new Prompt(java.util.List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userText(request) + "\n" + format)));
        val content = TransportRetry.call("judge LLM call", () -> evalJudgeClient.prompt(prompt).call().content());
        log.debug("Raw judge response: {}", content);
        // A judge that cannot be parsed is an infrastructure fault, not a quality verdict: fail loudly
        // instead of reporting grounded=false/correct=false for the agent under test.
        return Try.of(() -> converter.convert(content))
                .getOrElseThrow(cause -> new IllegalStateException("Judge response was not parseable: " + content, cause));
    }

    /** Metadata key marking a context document as expected facts rather than retrieved evidence. */
    public static final String EXPECTED_FACTS_ROLE = "expected-facts";

    private static boolean isExpectedFacts(Document document) {
        return EXPECTED_FACTS_ROLE.equals(document.getMetadata().get("role"));
    }

    private static String userText(EvaluationRequest request) {
        val documents = io.vavr.collection.List.ofAll(request.getDataList());
        val expected = documents.filter(EvalJudge::isExpectedFacts).map(Document::getText).mkString("\n");
        val evidence = documents.reject(EvalJudge::isExpectedFacts).map(Document::getText).mkString("\n");
        return """
                Question: %s
                Expected facts (correctness reference only, NOT evidence):
                %s
                Retrieved evidence:
                %s
                Agent answer: %s
                """.formatted(request.getUserText(), expected, evidence, request.getResponseContent());
    }
}
