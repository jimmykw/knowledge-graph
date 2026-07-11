package net.jimmykw.knowledgegraph.extract;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class ExtractionPrompter {

    private final ChatClient chatClient;
    private final BeanOutputConverter<ExtractionResult> converter;

    public String getFormat() {
        return converter.getFormat();
    }

    public Try<ExtractionResult> callWithRetry(Prompt prompt) {
        return Try.of(() -> callOnce(prompt, 1))
                .recoverWith(throwable -> {
                    log.warn("First LLM extraction attempt failed; retrying", throwable);
                    return Try.of(() -> callOnce(prompt, 2));
                });
    }

    private ExtractionResult callOnce(Prompt prompt, int attempt) {
        log.debug("LLM call attempt {}", attempt);
        val content = chatClient.prompt(prompt).call().content();
        log.debug("LLM response received (attempt {}, {} chars)", attempt,
                content == null ? 0 : content.length());
        if (content == null || content.isBlank()) {
            log.warn("LLM returned empty or blank content on attempt {}", attempt);
        }
        val parsed = Try.of(() -> converter.convert(content));
        if (parsed.isFailure()) {
            val repaired = Try.of(() -> converter.convert(repairJson(content)));
            if (repaired.isSuccess()) {
                log.warn("LLM response required JSON repair (attempt {})", attempt);
                return repaired.get();
            }
            log.warn("Failed to parse LLM response even after repair (attempt {}). Raw content:\n{}",
                    attempt, content, parsed.getCause());
        }
        return parsed.get();
    }

    static String repairJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        var repaired = raw;
        if (repaired.contains("```")) {
            repaired = repaired.replaceAll("(?s)```(?:json)?\\s*", "");
        }
        repaired = repaired.replaceAll("(\"[^\"]+\"\\s*:\\s*)([A-Za-z][^\"]*\")", "$1 \"$2");
        repaired = repaired.replaceAll(",\\s*([}\\]])", "$1");
        return repaired;
    }
}
