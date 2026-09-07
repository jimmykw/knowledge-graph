package net.jimmykw.knowledgegraph.config;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

import io.vavr.control.Option;
import lombok.experimental.UtilityClass;
import lombok.val;
import okhttp3.Interceptor;

/**
 * OpenCode Go (opencode.ai/zen/go) rejects requests without an {@code x-opencode-session} header
 * and asks clients to send a stable session id per conversation plus their own user agent
 * (https://opencode.ai/docs/go/#where-can-i-use-it). Chat calls run scoped to the conversation id
 * via {@link #withSession}; extraction and eval-judge calls fall back to a process-wide id.
 */
@UtilityClass
public class OpenCodeGoHeaders {

    private static final String SESSION_HEADER = "x-opencode-session";
    private static final String USER_AGENT = "knowledge-graph/1.0";

    private static final String PROCESS_SESSION_ID = UUID.randomUUID().toString();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    /** Adds the session and user-agent headers to every request of the customized model. */
    public static OpenAiHttpClientBuilderCustomizer httpClientCustomizer() {
        return builder -> builder.interceptor(sessionInterceptor());
    }

    static Interceptor sessionInterceptor() {
        return chain -> {
            val request = chain.request().newBuilder()
                    .header(SESSION_HEADER, currentSessionId())
                    .header("User-Agent", USER_AGENT)
                    .build();
            return chain.proceed(request);
        };
    }

    /** Runs the action with the given conversation id as the OpenCode Go session id. */
    public static <T> T withSession(String sessionId, Supplier<T> action) {
        SESSION_ID.set(sessionId);
        try {
            return action.get();
        } finally {
            SESSION_ID.remove();
        }
    }

    static String currentSessionId() {
        return Option.of(SESSION_ID.get()).getOrElse(PROCESS_SESSION_ID);
    }
}
