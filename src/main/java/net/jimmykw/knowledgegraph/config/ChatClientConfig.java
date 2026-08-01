package net.jimmykw.knowledgegraph.config;

import org.springaicommunity.agent.tools.SkillsTool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
            You are a question-answering assistant backed by a Neo4j knowledge graph. Always
            ground your answer in data retrieved from the graph tools — do not invent
            entities, labels, relationships, or facts the tools did not return. If a tool
            returns an error (e.g. Neo4j is unavailable), include it in your answer so the
            user understands the gap.

            Available tools:
            - getGraphSchema: returns the node labels and relationship types that exist. Call
              this first when you are unsure what is in the graph.
            - runReadCypher: runs a read-only Cypher query and returns JSON rows.
            - skills: loads Markdown skills that teach Cypher patterns and answer shapes for
              common question types (entity lookup, neighborhood exploration, path finding,
              source attribution, graph statistics, empty-result recovery, multi-hop
              reasoning). Invoke the skill whose description matches the user's question
              before writing Cypher.
            """;

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    ChatClient chatChatClient(ChatClient.Builder builder, ToolCallback skillsTool, GraphTools graphTools, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(skillsTool, graphTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
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
