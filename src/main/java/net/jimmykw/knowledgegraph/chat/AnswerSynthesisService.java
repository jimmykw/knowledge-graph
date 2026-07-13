package net.jimmykw.knowledgegraph.chat;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import net.jimmykw.knowledgegraph.chat.CypherExecutor.ExecutedResults;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class AnswerSynthesisService {

    private static final String SYSTEM_PROMPT = """
            You are an answer-synthesis assistant for a knowledge graph. You are given a user's
            question, the Cypher query that was executed against the graph, and the query results
            (a list of flat JSON rows). Write a concise, grounded natural-language answer to the
            question based ONLY on the provided results.

            Rules:
            - If the results are empty, say that no information was found in the knowledge graph.
            - Do not invent facts beyond the results. Reference entity names and relationship
              descriptions as they appear in the rows.
            - Prefer plain prose over bullet lists unless the question asks for an enumeration.
            """;

    private final ChatClient chatClient;

    public Try<String> synthesize(String prompt, String cypher, ExecutedResults executed, Option<String> priorError) {
        return Try.of(() -> callOnce(prompt, cypher, executed, priorError));
    }

    private String callOnce(String prompt, String cypher, ExecutedResults executed, Option<String> priorError) {
        val userText = buildUserPrompt(prompt, cypher, executed, priorError);
        val chatPrompt = new Prompt(java.util.List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userText)));
        val content = chatClient.prompt(chatPrompt).call().content();
        log.debug("Synthesis response received ({} chars)", content == null ? 0 : content.length());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM returned an empty answer");
        }
        return content;
    }

    private String buildUserPrompt(String prompt, String cypher, ExecutedResults executed, Option<String> priorError) {
        val retryNote = priorError
                .map(error -> "\nPrevious attempt failed: " + error + "\nRegenerate, correcting the issue.\n")
                .getOrElse("");
        val truncatedNote = executed.truncated()
                ? "\nNote: the results were truncated to " + executed.count()
                        + " rows. Base your answer on the visible subset only.\n"
                : "";
        return """
                Question: %s

                Cypher query executed:
                %s
                %s%s
                Results (%d row(s)):
                %s
                """.formatted(prompt, cypher, retryNote, truncatedNote, executed.count(),
                serialize(executed.rows()));
    }

    private String serialize(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "(no rows)";
        }
        val builder = new StringBuilder();
        for (int index = 0; index < rows.size(); index++) {
            builder.append("Row ").append(index + 1).append(": ").append(rows.get(index)).append("\n");
        }
        return builder.toString().trim();
    }
}
