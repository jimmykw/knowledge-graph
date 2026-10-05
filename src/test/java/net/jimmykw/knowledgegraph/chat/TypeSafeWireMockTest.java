package net.jimmykw.knowledgegraph.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.judge.JevAnswerJudge;
import net.jimmykw.knowledgegraph.chat.judge.JudgeInput;
import net.jimmykw.knowledgegraph.chat.judge.QualityStatus;
import net.jimmykw.knowledgegraph.chat.routing.GraphProfile;
import net.jimmykw.knowledgegraph.chat.routing.RouteIntent;
import net.jimmykw.knowledgegraph.chat.routing.RouteStatus;
import net.jimmykw.knowledgegraph.chat.routing.TypeSafeIntentClassifier;
import net.jimmykw.knowledgegraph.chat.routing.TypeSafeQuestionRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The real TypeSafeClient, JevJudge and router against a stubbed System One endpoint (POST /v1/systemone). */
class TypeSafeWireMockTest {

    private static final String PATH = "/v1/systemone";
    private static final String MODEL = "typesafe/jev-1.13";
    private static final String KEY = "test-key";

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    /** Same shape as the application.yml settings: one retry, 5s total; the per-attempt timeout is shortened for the timeout test. */
    private TypeSafeClient client(Duration timeout) {
        val retry = RetryPolicy.builder().maxRetries(1).initialBackoff(Duration.ofMillis(10)).maxBackoff(Duration.ofMillis(50))
                .totalTimeout(Duration.ofSeconds(5)).build();
        // WireMock serves cleartext HTTP/1.1; the JDK client's default h2c upgrade attempt makes it drop the connection.
        val factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        val http1 = RestClient.builder().requestFactory(factory);
        return TypeSafeClient.builder().baseUrl(server.baseUrl()).apiKey(KEY).defaultModel(MODEL).timeout(timeout).retryPolicy(retry)
                .restClientBuilder(http1).build();
    }

    private TypeSafeQuestionRouter router(TypeSafeClient client) {
        return new TypeSafeQuestionRouter(new TypeSafeIntentClassifier(client,
                () -> new GraphProfile(List.of("HistoryOfIBM"), List.of("Organization"), List.of("IBM"))), 0.9);
    }

    private static String choice(String choice, double graph, double chitchat) {
        return "{\"model\":\"" + MODEL + "\",\"answers\":{\"intent\":{\"type\":\"choice\",\"choice\":\"" + choice + "\",\"probabilities\":"
                + "{\"GRAPH\":" + graph + ",\"CHITCHAT\":" + chitchat + ",\"OFF_TOPIC\":0,\"UNANSWERABLE\":0},\"confidence\":0.9}}}";
    }

    private static String score(double expected, double level0, double level1, double level2) {
        return "{\"type\":\"score\",\"score\":" + expected + ",\"legend\":{\"0\":\"low\",\"1\":\"mid\",\"2\":\"high\"},\"probabilities\":"
                + "{\"0\":" + level0 + ",\"1\":" + level1 + ",\"2\":" + level2 + "},\"confidence\":0.9}";
    }

    private static String scores(String grounded, String relevant) {
        return "{\"model\":\"" + MODEL + "\",\"answers\":{\"grounded\":" + grounded + ",\"relevant\":" + relevant + "}}";
    }

    private static JudgeInput judgeInput() {
        return new JudgeInput("What did IBM create?", List.empty(), List.empty(), "IBM created FORTRAN.");
    }

    private JevAnswerJudge judge(TypeSafeClient client) {
        return new JevAnswerJudge(JevAnswerJudge.buildJudge(client), 8000);
    }

    @Test
    void classifierSendsTheDocumentedRequestAndBlocksChitchat() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(choice("CHITCHAT", 0.02, 0.98))));
        val decision = router(client(Duration.ofSeconds(3))).route("thanks", List.empty()).get();
        assertThat(decision.status()).isEqualTo(RouteStatus.BLOCKED);
        assertThat(decision.intent()).isEqualTo(RouteIntent.CHITCHAT);
        server.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                .withRequestBody(matchingJsonPath("$.state", containing("New message: thanks")))
                .withRequestBody(matchingJsonPath("$.questions.intent.type", equalTo("choice")))
                .withRequestBody(matchingJsonPath("$.questions.intent.instructions", containing("HistoryOfIBM"))));
    }

    @Test
    void graphQuestionIsRouted() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(choice("GRAPH", 0.97, 0.03))));
        assertThat(router(client(Duration.ofSeconds(3))).route("What did IBM create?", List.empty()).get().status())
                .isEqualTo(RouteStatus.ROUTED);
    }

    @Test
    void rateLimitIsRetriedOnceThenRouted() {
        server.stubFor(post(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"slow\"}}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs("second")
                .willReturn(okJson(choice("GRAPH", 0.97, 0.03))));
        assertThat(router(client(Duration.ofSeconds(3))).route("hi", List.empty()).get().status()).isEqualTo(RouteStatus.ROUTED);
        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void serverErrorIsRetriedOnceThenRouted() {
        server.stubFor(post(urlEqualTo(PATH)).inScenario("retry5xx").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"down\"}}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(PATH)).inScenario("retry5xx").whenScenarioStateIs("second")
                .willReturn(okJson(choice("GRAPH", 0.97, 0.03))));
        assertThat(router(client(Duration.ofSeconds(3))).route("hi", List.empty()).get().status()).isEqualTo(RouteStatus.ROUTED);
        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void authErrorIsNotRetriedAndFailsOpen() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"bad key\"}}")));
        assertThat(router(client(Duration.ofSeconds(3))).route("hi", List.empty()).get().status()).isEqualTo(RouteStatus.SKIPPED);
        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void timeoutFailsOpenWithinTheBudget() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(choice("GRAPH", 1.0, 0.0)).withFixedDelay(1500)));
        val started = System.nanoTime();
        val decision = router(client(Duration.ofMillis(200))).route("hi", List.empty()).get();
        assertThat(decision.status()).isEqualTo(RouteStatus.SKIPPED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void groundedAndRelevantAnswersAreOk() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(scores(score(2.0, 0, 0, 1), score(2.0, 0, 0, 1)))));
        val quality = judge(client(Duration.ofSeconds(3))).judge(judgeInput()).get();
        assertThat(quality.status()).isEqualTo(QualityStatus.OK);
        server.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.questions.grounded.type", equalTo("score")))
                .withRequestBody(matchingJsonPath("$.questions.relevant.type", equalTo("score")))
                .withRequestBody(matchingJsonPath("$.state.supporting_context"))
                .withRequestBody(matchingJsonPath("$.state.conversation")));
    }

    @Test
    void unsupportedAnswerIsLowWithFeedback() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(scores(score(1.0, 0, 1, 0), score(2.0, 0, 0, 1)))));
        val quality = judge(client(Duration.ofSeconds(3))).judge(judgeInput()).get();
        assertThat(quality.status()).isEqualTo(QualityStatus.LOW);
        assertThat(quality.feedback()).contains("grounded");
    }

    @Test
    void inconclusiveCriterionIsLow() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(scores(score(1.55, 0, 0.45, 0.55), score(2.0, 0, 0, 1)))));
        assertThat(judge(client(Duration.ofSeconds(3))).judge(judgeInput()).get().status()).isEqualTo(QualityStatus.LOW);
    }

    @Test
    void serverErrorFailsOpenAsSkipped() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"boom\"}}")));
        assertThat(judge(client(Duration.ofSeconds(3))).judge(judgeInput()).get().status()).isEqualTo(QualityStatus.SKIPPED);
    }

    @Test
    void missingCriterionAnswerFailsOpenAsSkipped() {
        val onlyGrounded = "{\"model\":\"" + MODEL + "\",\"answers\":{\"grounded\":" + score(2.0, 0, 0, 1) + "}}";
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(onlyGrounded)));
        assertThat(judge(client(Duration.ofSeconds(3))).judge(judgeInput()).get().status()).isEqualTo(QualityStatus.SKIPPED);
    }
}
