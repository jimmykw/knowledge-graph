package net.jimmykw.knowledgegraph.util;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/** One LLM call whose reply is parsed into {@code T}, with a {@link JsonRepair} fallback and one retry. */
@Slf4j
@RequiredArgsConstructor
public class JsonPrompter<T> {

    private final ChatClient chatClient;
    private final BeanOutputConverter<T> converter;

    public String getFormat() {
        return converter.getFormat();
    }

    public Try<T> callWithRetry(Prompt prompt) {
        return Try.of(() -> callOnce(prompt, 1))
                .recoverWith(throwable -> {
                    log.warn("First LLM attempt failed; retrying", throwable);
                    return Try.of(() -> callOnce(prompt, 2));
                });
    }

    private T callOnce(Prompt prompt, int attempt) {
        log.debug("LLM call attempt {}", attempt);
        val content = chatClient.prompt(prompt).call().content();
        log.debug("LLM response received (attempt {}, {} chars)", attempt,
                content == null ? 0 : content.length());
        if (content == null || content.isBlank()) {
            log.warn("LLM returned empty or blank content on attempt {}", attempt);
        }
        val parsed = Try.of(() -> converter.convert(content));
        if (parsed.isFailure()) {
            val repaired = Try.of(() -> converter.convert(JsonRepair.repair(content)));
            if (repaired.isSuccess()) {
                log.warn("LLM response required JSON repair (attempt {})", attempt);
                return repaired.get();
            }
            log.warn("Failed to parse LLM response even after repair (attempt {}). Raw content:\n{}",
                    attempt, content, parsed.getCause());
        }
        return parsed.get();
    }
}
