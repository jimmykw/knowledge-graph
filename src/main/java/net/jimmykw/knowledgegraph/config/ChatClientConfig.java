package net.jimmykw.knowledgegraph.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    BeanOutputConverter<ExtractionResult> extractionOutputConverter() {
        return new BeanOutputConverter<>(ExtractionResult.class);
    }
}
