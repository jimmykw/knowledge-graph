package net.jimmykw.knowledgegraph.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import net.jimmykw.knowledgegraph.chat.ChatRecords.CypherGeneration;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;
import net.jimmykw.knowledgegraph.util.JsonRepair;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class CypherGenerationService {

    private static final String SYSTEM_PROMPT = """
            You are a Cypher query generator for a Neo4j knowledge graph. Given a natural-language
            question, the graph schema (node labels and relationship types), and the standard
            node/relationship properties, emit a single read-only Cypher query that answers the
            question.

            Constraints:
            - Emit exactly one Cypher query. Do NOT use CREATE, MERGE, SET, DELETE, REMOVE, DROP,
              or CALL apoc.merge.*. The query must be read-only (MATCH, OPTIONAL MATCH, WITH,
              RETURN, UNION, ORDER BY, LIMIT, SKIP, COUNT, COLLECT, etc.).
            - The `cypher` field must contain only the Cypher query, with no explanations, prose,
              backticks, or markdown.
            - Use only the labels and relationship types listed in the schema.
            - If the question cannot be answered from the schema, return a query that yields zero
              rows rather than inventing labels or relationship types.

            Standard node and relationship properties:
            - Entity nodes have: name, label, description, name_norm, source_doc.
              Use `name` for display; match on `name_norm` (lowercase) for case-insensitive lookups.
            - Relationships have: description.
            - Document nodes have: hash, filename, uploadedAt.
            """;

    private final ChatClient chatClient;
    private final BeanOutputConverter<CypherGeneration> converter;

    public Try<CypherGeneration> generate(SchemaSnapshot schema, String prompt, Option<String> priorError) {
        return Try.of(() -> callOnce(schema, prompt, priorError));
    }

    private CypherGeneration callOnce(SchemaSnapshot schema, String prompt, Option<String> priorError) {
        val userText = buildUserPrompt(schema, prompt, priorError);
        val chatPrompt = new Prompt(java.util.List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userText)));
        val content = chatClient.prompt(chatPrompt).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM returned empty content for Cypher generation");
        }
        val parsed = Try.of(() -> converter.convert(content));
        if (parsed.isFailure()) {
            val repaired = Try.of(() -> converter.convert(JsonRepair.repair(content)));
            if (repaired.isSuccess()) {
                log.warn("Cypher generation response required JSON repair");
                return validate(repaired.get());
            }
            log.warn("Failed to parse Cypher generation response even after repair. Raw content:\n{}",
                    content, parsed.getCause());
        }
        return validate(parsed.get());
    }

    private CypherGeneration validate(CypherGeneration result) {
        if (result.cypher() == null || result.cypher().isBlank()) {
            throw new IllegalStateException("LLM returned an empty Cypher query");
        }
        return result;
    }

    private String buildUserPrompt(SchemaSnapshot schema, String prompt, Option<String> priorError) {
        val retryNote = priorError
                .map(error -> "\nPrevious attempt failed: " + error + "\nRegenerate, correcting the issue.\n")
                .getOrElse("");
        return """
                Graph schema:
                Labels: %s
                Relationship types: %s

                Question: %s
                %s
                %s
                """.formatted(schema.labels(), schema.relTypes(), prompt, retryNote, converter.getFormat());
    }
}
