package net.jimmykw.knowledgegraph.chat.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.config.OpenCodeGoHeaders;

import lombok.val;

@TestConfiguration
public class EvalJudgeConfig {

    private static final String STRICT_FACTCHECK_PROMPT = """
            Evaluate whether the claim below is supported by the document below.
            Do not use outside knowledge — judge only against the document.
            Respond with exactly one word: "yes" if the claim is supported, "no" if it is not.

            Document:
            {document}

            Claim:
            {claim}
            """;

    @Bean
    OpenAiChatModel evalJudgeModel(AppProperties properties) {
        val ch = properties.models().chat();
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(ch.baseUrl()).apiKey(ch.apiKey()).model(ch.model())
                        .temperature(0.0).timeout(ch.timeout()).maxRetries(ch.maxRetries())
                        .build())
                .httpClientBuilderCustomizer(OpenCodeGoHeaders.httpClientCustomizer())
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

    @Bean
    KnowledgeGraphJudgeEvaluator knowledgeGraphJudgeEvaluator(EvalJudge evalJudge) {
        return new KnowledgeGraphJudgeEvaluator(evalJudge);
    }

    @Bean
    FactCheckingEvaluator factCheckingEvaluator(OpenAiChatModel evalJudgeModel) {
        return FactCheckingEvaluator.builder(ChatClient.builder(evalJudgeModel))
                .evaluationPrompt(STRICT_FACTCHECK_PROMPT)
                .build();
    }
}
