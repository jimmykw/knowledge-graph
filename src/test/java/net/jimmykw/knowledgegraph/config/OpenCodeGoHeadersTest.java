package net.jimmykw.knowledgegraph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.vavr.control.Try;
import lombok.SneakyThrows;
import lombok.val;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

class OpenCodeGoHeadersTest {

    private static final Request REQUEST = new Request.Builder()
            .url("https://opencode.ai/zen/go/v1/chat/completions")
            .build();

    @Test
    void sendsConversationIdAsSessionHeader() {
        val chain = chainStub();
        OpenCodeGoHeaders.withSession("conv-123", () -> intercept(chain));

        assertThat(capturedRequest(chain).header("x-opencode-session")).isEqualTo("conv-123");
        assertThat(capturedRequest(chain).header("User-Agent")).isEqualTo("knowledge-graph/1.0");
    }

    @Test
    void fallsBackToStableProcessSessionIdOutsideWithSession() {
        val first = OpenCodeGoHeaders.currentSessionId();
        val second = OpenCodeGoHeaders.currentSessionId();

        assertThat(first).isNotBlank().isEqualTo(second);

        val chain = chainStub();
        intercept(chain);
        assertThat(capturedRequest(chain).header("x-opencode-session")).isEqualTo(first);
    }

    @Test
    void clearsSessionAfterWithSession() {
        OpenCodeGoHeaders.withSession("conv-123", () -> OpenCodeGoHeaders.currentSessionId());

        assertThat(OpenCodeGoHeaders.currentSessionId()).isNotEqualTo("conv-123");
    }

    @SneakyThrows
    private static Interceptor.Chain chainStub() {
        val chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(REQUEST);
        when(chain.proceed(any())).thenReturn(okResponse());
        return chain;
    }

    private static Response intercept(Interceptor.Chain chain) {
        return Try.of(() -> OpenCodeGoHeaders.sessionInterceptor().intercept(chain)).get();
    }

    private static Request capturedRequest(Interceptor.Chain chain) {
        val captor = ArgumentCaptor.forClass(Request.class);
        Try.run(() -> verify(chain).proceed(captor.capture())).get();
        return captor.getValue();
    }

    private static Response okResponse() {
        return new Response.Builder()
                .request(REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build();
    }
}
