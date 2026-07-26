package net.jimmykw.knowledgegraph.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatChatClient;
    private final AppProperties appProperties;

    public ChatResponse chat(String prompt) {
        validate(prompt);
        val trace = new ToolTrace();
        val maxRounds = appProperties.chat().maxToolCallRounds();
        val tracingManager = new TracingToolCallingManager(ToolCallingManager.builder().build(), trace);
        val advisor = ToolCallingAdvisor.builder()
                .toolCallingManager(tracingManager)
                .toolExecutionEligibilityChecker(cappedChecker(trace, maxRounds))
                .build();
        val client = chatChatClient.mutate().defaultAdvisors(advisor).build();
        val answer = client.prompt(prompt).call().content();
        log.info("Chat: completed with {} tool round(s){}", trace.roundCount(),
                trace.maxRoundsExceeded() ? " (max rounds exceeded)" : "");
        return buildResponse(answer, trace);
    }

    private void validate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidPromptException("Prompt must not be blank.");
        }
        val maxLength = appProperties.chat().maxPromptLength();
        if (prompt.length() > maxLength) {
            throw new InvalidPromptException(
                    "Prompt exceeds the maximum length of " + maxLength + " characters.");
        }
    }

    private static ChatResponse buildResponse(String answer, ToolTrace trace) {
        return new ChatResponse(answer, trace.lastCypher(), trace.lastRows(),
                trace.lastTruncated(), trace.lastCount(), resolveError(trace), trace.skillsExecuted(),
                trace.cypherQueries());
    }

    private static String resolveError(ToolTrace trace) {
        if (trace.error() != null) {
            return trace.error();
        }
        if (trace.maxRoundsExceeded()) {
            return "Exceeded max tool call rounds";
        }
        return null;
    }

    private static ToolExecutionEligibilityChecker cappedChecker(ToolTrace trace, int maxRounds) {
        return response -> {
            if (!response.hasToolCalls()) {
                return false;
            }
            if (trace.roundCount() >= maxRounds) {
                trace.markMaxRoundsExceeded();
                return false;
            }
            return true;
        };
    }
}
