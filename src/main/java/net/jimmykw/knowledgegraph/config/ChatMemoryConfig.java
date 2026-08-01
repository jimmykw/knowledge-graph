package net.jimmykw.knowledgegraph.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.jimmykw.knowledgegraph.chat.store.H2ChatMemoryRepository;

import lombok.val;

@Configuration
public class ChatMemoryConfig {

    @Bean
    ChatMemoryRepository h2ChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return new H2ChatMemoryRepository(jdbcTemplate);
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AppProperties appProperties) {
        val window = appProperties.chat().historyWindow();
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(window)
                .build();
    }
}