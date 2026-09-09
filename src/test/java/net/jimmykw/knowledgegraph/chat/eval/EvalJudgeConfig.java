package net.jimmykw.knowledgegraph.chat.eval;

import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.config.OpenCodeGoHeaders;

import lombok.val;

@TestConfiguration
public class EvalJudgeConfig {

    /** Judge replies can take over a minute; a long timeout plus few client retries avoids timeout-retry pile-ups. */
    private static final int JUDGE_CLIENT_RETRIES = 1;

    private static final String STRICT_FACTCHECK_PROMPT = """
            Evaluate whether the claim below is supported by the document below.
            Do not use outside knowledge — judge only against the document.
            Respond with exactly one word: "yes" if the claim is supported, "no" if it is not.

            Document:
            {document}

            Claim:
            {claim}
            """;

    /**
     * The judge should not be the model under test. Override {@code eval.judge.base-url / api-key / model}
     * (system properties or env) to grade with a different model; each falls back to the chat model.
     */
    @Bean
    OpenAiChatModel evalJudgeModel(AppProperties properties,
                                   @Value("${eval.judge.base-url:}") String judgeBaseUrl,
                                   @Value("${eval.judge.api-key:}") String judgeApiKey,
                                   @Value("${eval.judge.model:}") String judgeModel,
                                   @Value("${eval.judge.timeout:300s}") Duration judgeTimeout) {
        val ch = properties.models().chat();
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(orDefault(judgeBaseUrl, ch.baseUrl()))
                        .apiKey(orDefault(judgeApiKey, ch.apiKey()))
                        .model(orDefault(judgeModel, ch.model()))
                        .temperature(0.0).timeout(judgeTimeout).maxRetries(JUDGE_CLIENT_RETRIES)
                        .build())
                .httpClientBuilderCustomizer(OpenCodeGoHeaders.httpClientCustomizer())
                .build();
    }

    private static String orDefault(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
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
