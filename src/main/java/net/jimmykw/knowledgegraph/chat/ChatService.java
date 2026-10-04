package net.jimmykw.knowledgegraph.chat;

import java.util.UUID;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.judge.AnswerJudge;
import net.jimmykw.knowledgegraph.chat.judge.AnswerQuality;
import net.jimmykw.knowledgegraph.chat.judge.JudgeInput;
import net.jimmykw.knowledgegraph.chat.routing.QuestionRouter;
import net.jimmykw.knowledgegraph.chat.store.BlockedMessages;
import net.jimmykw.knowledgegraph.chat.routing.RouteDecision;
import net.jimmykw.knowledgegraph.chat.routing.RouteStatus;
import net.jimmykw.knowledgegraph.chat.routing.RoutingReplies;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.config.OpenCodeGoHeaders;
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
    private final ChatClient directChatClient;
    private final AppProperties appProperties;
    private final ChatMemory chatMemory;
    private final QuestionRouter router;
    private final AnswerJudge judge;

    public ChatResponse chat(String prompt, String conversationId) {
        validate(prompt);
        val resolvedConversationId = resolveConversationId(conversationId);
        val route = router.route(prompt, routingHistory(resolvedConversationId));
        return route.filter(decision -> decision.status() == RouteStatus.BLOCKED)
                .map(decision -> blockedResponse(prompt, resolvedConversationId, decision))
                .getOrElse(() -> runAgent(prompt, resolvedConversationId, route));
    }

    private ChatResponse blockedResponse(String prompt, String conversationId, RouteDecision decision) {
        log.info("Chat: blocked by intent gate ({}), answering directly without the graph", decision.intent());
        val answer = Try.of(() -> askDirectly(prompt, conversationId))
                .filter(text -> text != null && !text.isBlank())
                .onFailure(e -> log.warn("Chat: direct answer failed, using canned reply ({})", e.toString()))
                .getOrElse(() -> cannedReply(prompt, conversationId, decision));
        return new ChatResponse(answer, null, null, false, 0, null, java.util.List.of(), java.util.List.of(), conversationId,
                java.util.List.of(), decision, null);
    }

    /** Tool-free model call over the stored history; the turn is stored flagged as blocked. */
    private String askDirectly(String prompt, String conversationId) {
        val history = chatMemory.get(conversationId);
        val answer = OpenCodeGoHeaders.withSession(conversationId,
                () -> directChatClient.prompt().messages(history).user(prompt).call().content());
        storeBlockedTurn(prompt, answer, conversationId);
        return answer;
    }

    private String cannedReply(String prompt, String conversationId, RouteDecision decision) {
        val reply = RoutingReplies.forIntent(decision.intent());
        storeBlockedTurn(prompt, reply, conversationId);
        return reply;
    }

    private void storeBlockedTurn(String prompt, String reply, String conversationId) {
        chatMemory.add(conversationId, java.util.List.of(BlockedMessages.user(prompt), BlockedMessages.assistant(reply)));
    }

    /** History for the classifier: earlier blocked exchanges are left out so they cannot bias the next message. */
    private List<org.springframework.ai.chat.messages.Message> routingHistory(String conversationId) {
        return List.ofAll(chatMemory.get(conversationId)).reject(BlockedMessages::isBlocked);
    }

    private ChatResponse runAgent(String prompt, String resolvedConversationId, Option<RouteDecision> route) {
        val history = List.ofAll(chatMemory.get(resolvedConversationId));
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
        // Scope the whole tool-call loop to the conversation id so every LLM request carries it
        // as the OpenCode Go session header (provider rejects requests without one).
        val answer = OpenCodeGoHeaders.withSession(resolvedConversationId,
                () -> client.prompt(prompt)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, resolvedConversationId))
                        .call().content());
        if (answer == null || answer.isBlank()) {
            log.warn("Chat: model returned blank answer after {} tool round(s)", trace.roundCount());
        }
        log.info("Chat: completed with {} tool round(s){}", trace.roundCount(),
                trace.maxRoundsExceeded() ? " (max rounds exceeded)" : "");
        val quality = judgeAnswer(prompt, history, trace, answer);
        return buildResponse(answer, trace, resolvedConversationId, route.getOrNull(), quality.getOrNull());
    }

    /** Advisory: any judge failure leaves the answer unscored and never fails the chat call. */
    private Option<AnswerQuality> judgeAnswer(String prompt, List<org.springframework.ai.chat.messages.Message> history,
                                              ToolTrace trace, String answer) {
        return Try.of(() -> judge.judge(new JudgeInput(prompt, history, List.ofAll(trace.evidence()), answer)))
                .onFailure(error -> log.warn("Chat: answer judge failed ({})", error.toString()))
                .getOrElse(Option::none);
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

    private static ChatResponse buildResponse(String answer, ToolTrace trace, String conversationId, RouteDecision route,
                                         AnswerQuality quality) {
        return new ChatResponse(answer, trace.lastCypher(), trace.lastRows(),
                trace.lastTruncated(), trace.lastCount(), resolveError(trace), trace.skillsExecuted(),
                trace.cypherQueries(), conversationId, trace.evidence(), route, quality);
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