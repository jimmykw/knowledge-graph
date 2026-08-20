package net.jimmykw.knowledgegraph.chat.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import net.jimmykw.knowledgegraph.config.AppProperties;

import lombok.val;

@TestConfiguration
public class EvalJudgeConfig {

    @Bean
    OpenAiChatModel evalJudgeModel(AppProperties properties) {
        val ch = properties.models().chat();
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(ch.baseUrl()).apiKey(ch.apiKey()).model(ch.model())
                        .temperature(0.0).timeout(ch.timeout()).maxRetries(ch.maxRetries())
                        .build())
                .build();
    }

    @Bean
    ChatClient evalJudgeClient(OpenAiChatModel evalJudgeModel) {
        return ChatClient.builder(evalJudgeModel).build();
    }

    @Bean
    BeanOutputConverter<JudgeResult> judgeConverter() {
        return new BeanOutputConverter<>(JudgeResult.class);
    }

    @Bean
    EvalJudge evalJudge(ChatClient evalJudgeClient, BeanOutputConverter<JudgeResult> judgeConverter) {
        return new EvalJudge(evalJudgeClient, judgeConverter);
    }
}
