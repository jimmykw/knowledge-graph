package net.jimmykw.knowledgegraph.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import lombok.val;

class TransportRetryTest {

    @Test
    void retriesTransientFailureThenReturnsResult() {
        val calls = new AtomicInteger();
        val result = TransportRetry.call("flaky", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("Error reading response");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterMaxTries() {
        val calls = new AtomicInteger();
        assertThatThrownBy(() -> TransportRetry.call("always fails", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(calls).hasValue(TransportRetry.MAX_TRIES);
    }

    @Test
    void doesNotRetryWhenPredicateRejects() {
        val calls = new AtomicInteger();
        assertThatThrownBy(() -> TransportRetry.call("non-retryable", () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("real failure");
        }, IllegalStateException.class::isInstance)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
    }
}
