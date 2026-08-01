package net.jimmykw.knowledgegraph.chat;

import java.util.UUID;

import io.vavr.control.Option;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatChatClient;
    private final AppProperties appProperties;
    private final ChatMemory chatMemory;

    public ChatResponse chat(String prompt, String conversationId) {
        validate(prompt);
        val resolvedConversationId = resolveConversationId(conversationId);
        val trace = new ToolTrace();
        val maxRounds = appProperties.chat().maxToolCallRounds();
        val tracingManager = new TracingToolCallingManager(ToolCallingManager.builder().build(), trace);
        // Keep conversationHistoryEnabled at its default (true): the memory advisor runs upstream of
        // this advisor (order +200 < +300), outside the tool-call loop, so the loop must carry the
        // full conversation itself. Setting false collapses every round after the first to
        // [system, lastToolResponse], dropping the user's question and prior tool calls.
        val advisor = ToolCallingAdvisor.builder()
                .toolCallingManager(tracingManager)
                .toolExecutionEligibilityChecker(cappedChecker(trace, maxRounds))
                .build();
        val client = chatChatClient.mutate().defaultAdvisors(advisor).build();
        val answer = client.prompt(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, resolvedConversationId))
                .call().content();
        log.info("Chat: completed with {} tool round(s){}", trace.roundCount(),
                trace.maxRoundsExceeded() ? " (max rounds exceeded)" : "");
        return buildResponse(answer, trace, resolvedConversationId);
    }

    public boolean clearConversation(String conversationId) {
        val existing = chatMemory.get(conversationId);
        if (existing.isEmpty()) {
            return false;
        }
        chatMemory.clear(conversationId);
        return true;
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

    private static String resolveConversationId(String conversationId) {
        return Option.of(conversationId)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .getOrElse(() -> UUID.randomUUID().toString());
    }

    private static ChatResponse buildResponse(String answer, ToolTrace trace, String conversationId) {
        return new ChatResponse(answer, trace.lastCypher(), trace.lastRows(),
                trace.lastTruncated(), trace.lastCount(), resolveError(trace), trace.skillsExecuted(),
                trace.cypherQueries(), conversationId);
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