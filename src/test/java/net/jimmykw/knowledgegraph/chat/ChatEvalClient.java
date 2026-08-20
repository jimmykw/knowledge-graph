package net.jimmykw.knowledgegraph.chat;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class ChatEvalClient {

    /** Golden cases run a real multi-round tool loop; the default 10s read timeout kills them mid-flow. */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    private final RestClient rest;

    public static ChatEvalClient at(int port) {
        val baseUrl = "http://localhost:" + port;
        val requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(READ_TIMEOUT);
        val rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        return new ChatEvalClient(rest);
    }

    public ChatResponse ask(String prompt, String conversationId) {
        val request = new ChatRequest(prompt, conversationId);
        return rest.post()
                .uri("/api/knowledge-graph/chat")
                .body(request)
                .retrieve()
                .body(ChatResponse.class);
    }
}
