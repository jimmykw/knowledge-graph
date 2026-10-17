package net.jimmykw.knowledgegraph.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.util.stream.Collectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.vavr.collection.List;
import lombok.val;
import tools.jackson.databind.json.JsonMapper;

/** An OpenAI-style {@code POST /v1/chat/completions} endpoint for the extraction and chat models. */
public class FakeLlmServer {

    public static final String COMPLETIONS = "/v1/chat/completions";

    private static final JsonMapper JSON = JsonMapper.shared();

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    public FakeLlmServer start() {
        server.start();
        return this;
    }

    public void reset() {
        server.resetAll();
    }

    /** What {@code app.models.*.base-url} should be. */
    public String baseUrl() {
        return server.baseUrl() + "/v1";
    }

    public static String completion(String content) {
        return response(JSON.writeValueAsString(content), "", "stop");
    }

    public static String toolCall(String name, String argumentsJson) {
        val call = "{\"id\":\"call_%s\",\"type\":\"function\",\"function\":{\"name\":\"%s\",\"arguments\":%s}}"
                .formatted(name, name, JSON.writeValueAsString(argumentsJson));
        return response("null", ",\"tool_calls\":[" + call + "]", "tool_calls");
    }

    private static String response(String contentJson, String extraMessageFields, String finishReason) {
        return ("{\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fake\",\"choices\":[{\"index\":0,"
                + "\"finish_reason\":\"%s\",\"message\":{\"role\":\"assistant\",\"content\":%s%s}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                .formatted(finishReason, contentJson, extraMessageFields);
    }

    /** Answers every request whose body contains all the given fragments. A lower number wins when several stubs match. */
    public void stubBodyContaining(int priority, String responseJson, String... fragments) {
        val mapping = post(urlEqualTo(COMPLETIONS)).atPriority(priority);
        List.of(fragments).forEach(fragment -> mapping.withRequestBody(containing(fragment)));
        server.stubFor(mapping.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(responseJson)));
    }

    public void stubBodyContainingFails(int priority, int status, String... fragments) {
        val mapping = post(urlEqualTo(COMPLETIONS)).atPriority(priority);
        List.of(fragments).forEach(fragment -> mapping.withRequestBody(containing(fragment)));
        server.stubFor(mapping.willReturn(aResponse().withStatus(status).withBody("{\"error\":{\"message\":\"boom\"}}")));
    }

    /**
     * Agent requests carry a {@code tools} array; extraction and direct-model requests do not.
     * Replies come in order, the last one repeats.
     */
    public void stubAgentSequence(String... responseJsons) {
        val scenario = "agent";
        List.of(responseJsons).zipWithIndex().forEach(pair -> {
            val index = pair._2;
            val last = index == responseJsons.length - 1;
            val required = index == 0 ? Scenario.STARTED : "step-" + index;
            val mapping = post(urlEqualTo(COMPLETIONS)).atPriority(5).withRequestBody(matchingJsonPath("$.tools"))
                    .inScenario(scenario).whenScenarioStateIs(required);
            if (!last) {
                mapping.willSetStateTo("step-" + (index + 1));
            }
            server.stubFor(mapping.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(pair._1)));
        });
    }

    public List<String> requestBodies() {
        return List.ofAll(server.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).stream().map(request -> request.getBodyAsString())
                .collect(Collectors.toList()));
    }

    public List<String> agentRequestBodies() {
        return requestBodies().filter(body -> body.contains("\"tools\""));
    }

    public int requestCount() {
        return requestBodies().size();
    }

    public void stop() {
        server.stop();
    }
}
