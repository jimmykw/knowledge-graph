package net.jimmykw.knowledgegraph.chat.routing;

import java.time.Duration;
import java.util.Map;

import io.vavr.control.Option;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Calls OpenRouter's System One endpoint ({@code POST {base-url}/systemone}); shared by the intent gate and the answer judge. */
@Slf4j
public class RestSystemOneApi implements SystemOneApi {

    private final RestClient client;
    private final String model;
    private final String purpose;

    public RestSystemOneApi(String baseUrl, String apiKey, String model, Duration timeout, String purpose) {
        val factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model.contains("/") ? model : "typesafe/" + model;
        this.purpose = purpose;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> ask(String state, Map<String, Object> questions) {
        val body = Map.of("model", model, "state", state, "questions", questions);
        val response = client.post().uri("/systemone").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class);
        logUsage(response);
        return Option.of(response).map(parsed -> parsed.get("answers")).filter(Map.class::isInstance)
                .map(answers -> (Map<String, Object>) answers)
                .getOrElseThrow(() -> new IllegalStateException("System One response had no answers"));
    }

    private void logUsage(Map<?, ?> response) {
        Option.of(response).map(parsed -> parsed.get("usage")).filter(Map.class::isInstance)
                .forEach(usage -> log.debug("{}: System One usage {}", purpose, usage));
    }
}
