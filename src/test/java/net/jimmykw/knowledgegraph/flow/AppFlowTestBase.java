package net.jimmykw.knowledgegraph.flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.CypherExecutor;
import net.jimmykw.knowledgegraph.chat.judge.AnswerJudge;
import net.jimmykw.knowledgegraph.chat.routing.QuestionRouter;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.testsupport.FakeLlmServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real HTTP -> controllers -> services pipeline on a random port, with only the outside world faked: the LLMs through
 * WireMock, Neo4j through mocked beans. The real graph is never contacted (the Driver is a mock and the URI is unreachable).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:flow-test;DB_CLOSE_DELAY=-1",
        "spring.neo4j.uri=bolt://127.0.0.1:1",
        "spring.neo4j.authentication.password=dummy",
        "spring.ai.openai.api-key=dummy",
        "spring.ai.typesafe.api-key=dummy",
        "app.models.extract.api-key=dummy",
        "app.models.extract.max-retries=0",
        "app.models.extract.pool-size=2",
        "app.models.chat.api-key=dummy",
        "app.models.chat.max-retries=0",
        "app.max-pages=3",
        "app.chunk-tokens=1000",
        "app.chat.max-tool-call-rounds=3",
        "app.routing.enabled=false",
        "app.judge.enabled=false",
        "app.resolution.enabled=true",
        "server.address=127.0.0.1"})
public abstract class AppFlowTestBase {

    protected static final FakeLlmServer LLM = new FakeLlmServer().start();
    protected static final JsonMapper JSON = JsonMapper.shared();

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final String BOUNDARY = "----flowtestboundary";

    @MockitoBean
    protected Driver driver;
    @MockitoBean
    protected Neo4jGraphWriter writer;
    @MockitoBean
    protected CypherExecutor cypherExecutor;
    @MockitoBean
    protected QuestionRouter router;
    @MockitoBean
    protected AnswerJudge judge;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void fakeLlm(DynamicPropertyRegistry registry) {
        registry.add("app.models.extract.base-url", LLM::baseUrl);
        registry.add("app.models.chat.base-url", LLM::baseUrl);
    }

    @AfterAll
    static void keepServerForOtherClasses() {
        // The WireMock server is shared by every flow test class (they share one Spring context); it dies with the JVM.
    }

    @BeforeEach
    void resetFakes() {
        LLM.reset();
        when(router.route(any(), any())).thenReturn(Option.none());
        when(judge.judge(any())).thenReturn(Option.none());
    }

    protected record Reply(int status, String body) {
        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    protected Reply postJson(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    protected Reply delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    protected Reply get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    protected Reply upload(String filename, String contentType, byte[] bytes) {
        val head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: "
                + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        val tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        val body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
        return send(HttpRequest.newBuilder(uri("/api/knowledge-graph")).header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static Reply send(HttpRequest.Builder request) {
        return Try.of(() -> HTTP.send(request.timeout(java.time.Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString()))
                .map(response -> new Reply(response.statusCode(), response.body()))
                .get();
    }

    protected static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
