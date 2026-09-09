package net.jimmykw.knowledgegraph.chat;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import net.jimmykw.knowledgegraph.chat.eval.TransportRetry;

import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class ChatEvalClient {

    /** Golden cases run a real multi-round tool loop against a remote model (~100s per round). */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(15);

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
        // Only network-level failures are retried; an HTTP 4xx/5xx from the app is a real result.
        return TransportRetry.call("chat request", () -> rest.post()
                .uri("/api/knowledge-graph/chat")
                .body(request)
                .retrieve()
                .body(ChatResponse.class), ResourceAccessException.class::isInstance);
    }
}
