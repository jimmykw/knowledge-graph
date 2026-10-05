package net.jimmykw.knowledgegraph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import lombok.val;
import net.jimmykw.knowledgegraph.chat.judge.JevAnswerJudge;
import net.jimmykw.knowledgegraph.chat.judge.NoOpAnswerJudge;
import net.jimmykw.knowledgegraph.chat.routing.NoOpQuestionRouter;
import net.jimmykw.knowledgegraph.chat.routing.TypeSafeQuestionRouter;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The starter turns the real application.yml into one TypeSafeClient; the app refuses to start a Jev feature without it. */
class TypeSafeConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class));

    @Test
    void applicationYmlConfiguresOneSharedClientForOpenRouter() {
        runner.withPropertyValues("OPENAI_API_KEY=test-key").run(context -> {
            assertThat(context).hasSingleBean(TypeSafeClient.class);
            val client = context.getBean(TypeSafeClient.class);
            assertThat(client.defaultModel()).isEqualTo("typesafe/jev-1.13");
            assertThat(client.timeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(client.retryPolicy().maxRetries()).isEqualTo(1);
            assertThat(client.retryPolicy().totalTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void applicationYmlBindsTheFeatureFlagsAndThresholds() {
        runner.withPropertyValues("OPENAI_API_KEY=test-key").withUserConfiguration(PropertiesOnly.class).run(context -> {
            val app = context.getBean(AppProperties.class);
            assertThat(app.routing().enabled()).isTrue();
            assertThat(app.routing().blockThreshold()).isEqualTo(0.9);
            assertThat(app.judge().enabled()).isTrue();
            assertThat(app.judge().maxEvidenceChars()).isEqualTo(8000);
        });
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(AppProperties.class)
    @org.springframework.context.annotation.Configuration
    static class PropertiesOnly {
    }

    @Test
    void noApiKeyMeansNoClient() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(TypeSafeClient.class));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TypeSafeClient> provider(TypeSafeClient client) {
        val provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private static AppProperties properties(boolean routing, boolean judge) {
        return new AppProperties(0, 0, null, null, new AppProperties.Routing(routing, 0), new AppProperties.Judge(judge, 0));
    }

    @Test
    void enabledFeaturesFailFastWithoutAClient() {
        val config = new AppConfig();
        val writer = mock(Neo4jGraphWriter.class);
        assertThatThrownBy(() -> config.questionRouter(properties(true, false), provider(null), writer))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.routing.enabled").hasMessageContaining("api-key");
        assertThatThrownBy(() -> config.answerJudge(properties(false, true), provider(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.judge.enabled");
    }

    @Test
    void disabledFeaturesNeedNoClient() {
        val config = new AppConfig();
        assertThat(config.questionRouter(properties(false, false), provider(null), mock(Neo4jGraphWriter.class)))
                .isInstanceOf(NoOpQuestionRouter.class);
        assertThat(config.answerJudge(properties(false, false), provider(null))).isInstanceOf(NoOpAnswerJudge.class);
    }

    @Test
    void enabledFeaturesBuildTheJevImplementationsFromTheSharedClient() {
        val config = new AppConfig();
        val client = TypeSafeClient.builder().baseUrl("http://localhost:1").apiKey("test-key").build();
        assertThat(config.questionRouter(properties(true, true), provider(client), mock(Neo4jGraphWriter.class)))
                .isInstanceOf(TypeSafeQuestionRouter.class);
        assertThat(config.answerJudge(properties(true, true), provider(client))).isInstanceOf(JevAnswerJudge.class);
    }
}
