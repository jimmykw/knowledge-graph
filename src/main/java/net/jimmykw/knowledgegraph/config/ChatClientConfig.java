package net.jimmykw.knowledgegraph.config;

import org.springaicommunity.agent.tools.SkillsTool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import net.jimmykw.knowledgegraph.chat.GraphTools;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are a question-answering assistant backed by a Neo4j knowledge graph. Answer
            the user's question by using the provided graph tools and skills. Always ground
            your answer in data retrieved from the graph — do not invent entities, labels,
            relationships, or facts that the tools did not return.

            Available tools:
            - getGraphSchema: returns the node labels and relationship types that exist. Call
              this first when you are unsure what is in the graph.
            - runReadCypher: runs a read-only Cypher query and returns JSON rows.
            - findEntityByName: case-insensitive entity lookup with a fuzzy fallback.
            - getEntityNeighborhood: returns the k-hop neighborhood of an entity.
            - skills: loads Markdown skills that teach Cypher patterns and answer shapes for
              common question types (entity lookup, neighborhood exploration, path finding,
              source attribution, graph statistics, empty-result recovery, multi-hop
              reasoning). Invoke the skill whose description matches the user's question
              before writing Cypher.

            Cypher rules:
            - Emit read-only Cypher only. Never use CREATE, MERGE, SET, DELETE, REMOVE, DROP,
              or CALL apoc.merge.*/apoc.create.*. The runReadCypher tool blocks these.
            - Use only the labels and relationship types reported by getGraphSchema. If the
              schema is empty, tell the user to ingest a document first.
            - Match entities on name_norm = toLower($name) for case-insensitive lookups.
              Never string-interpolate names; always use parameters ($name, $depth).

            Graph conventions:
            - Entity nodes have: name (display), name_norm (trim + lowercase), label,
              description, source_doc (hash of the source :Document node).
            - Relationships have: description.
            - :Document nodes have: hash, filename, uploadedAt.

            Answer rules:
            - If a query returns no rows, apply the empty-result-recovery skill (broaden the
              match, drop a label filter, fall back to a CONTAINS search) before answering.
            - If no information is found after recovery, say so plainly rather than
              fabricating.
            - Reference entity names and relationship descriptions as they appear in the
              results. Prefer plain prose unless the question asks for an enumeration.
            - If a tool returns an error (e.g. Neo4j is unavailable), include that in your
              answer so the user understands the gap.
            """;

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    ChatClient chatChatClient(ChatClient.Builder builder, ToolCallback skillsTool, GraphTools graphTools) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(skillsTool, graphTools)
                .build();
    }

    @Bean
    ToolCallback skillsTool() {
        return SkillsTool.builder()
                .addSkillsResource(new ClassPathResource(".claude/skills"))
                .build();
    }

    @Bean
    BeanOutputConverter<ExtractionResult> extractionOutputConverter() {
        return new BeanOutputConverter<>(ExtractionResult.class);
    }
}
