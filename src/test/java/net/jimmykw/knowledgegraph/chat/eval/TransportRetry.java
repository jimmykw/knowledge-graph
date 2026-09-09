package net.jimmykw.knowledgegraph.chat.eval;

import java.util.function.Predicate;
import java.util.function.Supplier;

import io.vavr.control.Try;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Retries calls that fail on transient transport errors (e.g. "Error reading response" from the LLM
 * provider) so they do not count as quality failures of the agent under test.
 */
@Slf4j
@UtilityClass
public class TransportRetry {

    public static final int MAX_TRIES = 3;

    public static <T> T call(String what, Supplier<T> action) {
        return call(what, action, cause -> true);
    }

    public static <T> T call(String what, Supplier<T> action, Predicate<Throwable> retryable) {
        return tryOnce(what, action, retryable, 1).get();
    }

    private static <T> Try<T> tryOnce(String what, Supplier<T> action, Predicate<Throwable> retryable, int attempt) {
        val result = Try.ofSupplier(action);
        return result.isFailure() && attempt < MAX_TRIES && retryable.test(result.getCause())
                ? retryAfterLog(what, action, retryable, attempt, result.getCause())
                : result;
    }

    private static <T> Try<T> retryAfterLog(String what, Supplier<T> action, Predicate<Throwable> retryable,
                                            int attempt, Throwable cause) {
        log.warn("{} failed on try {}/{} ({}); retrying", what, attempt, MAX_TRIES, cause.getMessage());
        return tryOnce(what, action, retryable, attempt + 1);
    }
}
