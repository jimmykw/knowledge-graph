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
            final answer, and the Cypher queries plus rows the agent retrieved. Decide:
            - grounded: true if the answer states only facts supported by the returned rows and
              invents no entities, relationships, numbers, or dates the rows did not contain; false
              otherwise.
            - correct: true if the answer addresses the question and, when expected facts are listed,
              mentions at least one of them; false otherwise.
            Return ONLY JSON matching the requested schema.
            """;

    private final ChatClient evalJudgeClient;
    private final BeanOutputConverter<JudgeResult> converter;

    public JudgeResult score(EvaluationRequest request) {
        val format = converter.getFormat();
        val prompt = new Prompt(java.util.List.of(
                new SystemMessage(SYSTEM),
                new UserMessage(userText(request) + "\n" + format)));
        val content = evalJudgeClient.prompt(prompt).call().content();
        log.debug("Raw judge response: {}", content);
        return Try.of(() -> converter.convert(content))
                .onFailure(cause -> log.warn("Judge parse failed: {}", cause.getMessage()))
                .getOrElse(() -> new JudgeResult(false, false, "judge parse failed: " + content));
    }

    private static String userText(EvaluationRequest request) {
        val context = io.vavr.collection.List.ofAll(request.getDataList())
                .map(Document::getText)
                .mkString("\n");
        return """
                Question: %s
                Context:
                %s
                Agent answer: %s
                """.formatted(request.getUserText(), context, request.getResponseContent());
    }
}
