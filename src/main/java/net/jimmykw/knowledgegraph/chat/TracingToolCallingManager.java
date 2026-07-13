package net.jimmykw.knowledgegraph.chat;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@RequiredArgsConstructor
public class TracingToolCallingManager implements ToolCallingManager {

    private static final JsonMapper JSON = JsonMapper.shared();

    private static final String RUN_READ_CYPHER = "runReadCypher";

    private static final String SKILL_TOOL = "Skill";

    private static final String COMMAND_FIELD = "command";

    private final ToolCallingManager delegate;
    private final ToolTrace trace;

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        val result = delegate.executeToolCalls(prompt, chatResponse);
        recordToolCalls(chatResponse, result);
        trace.incrementRoundCount();
        return result;
    }

    private void recordToolCalls(ChatResponse chatResponse, ToolExecutionResult result) {
        val callsByToolCallId = collectToolCalls(chatResponse);
        if (callsByToolCallId.isEmpty()) {
            return;
        }
        val responsesByToolCallId = collectToolResponses(result);
        for (val entry : callsByToolCallId.entrySet()) {
            val toolCall = entry.getValue();
            val response = responsesByToolCallId.getOrDefault(entry.getKey(), "");
            recordSingleToolCall(toolCall, response);
        }
    }

    private Map<String, AssistantMessage.ToolCall> collectToolCalls(ChatResponse chatResponse) {
        val map = new java.util.LinkedHashMap<String, AssistantMessage.ToolCall>();
        chatResponse.getResults().stream()
                .map(Generation::getOutput)
                .filter(output -> !output.getToolCalls().isEmpty())
                .flatMap(output -> output.getToolCalls().stream())
                .forEach(toolCall -> map.put(toolCall.id(), toolCall));
        return map;
    }

    private Map<String, String> collectToolResponses(ToolExecutionResult result) {
        val map = new java.util.LinkedHashMap<String, String>();
        for (val message : result.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (val response : toolResponseMessage.getResponses()) {
                    map.put(response.id(), response.responseData());
                }
            }
        }
        return map;
    }

    private void recordSingleToolCall(AssistantMessage.ToolCall toolCall, String response) {
        val arguments = toolCall.arguments() == null ? "" : toolCall.arguments();
        if (RUN_READ_CYPHER.equals(toolCall.name())) {
            recordRunReadCypher(arguments, response);
        } else if (SKILL_TOOL.equals(toolCall.name())) {
            recordSkill(arguments, response);
        } else {
            trace.recordToolCall(toolCall.name(), arguments, response, null);
        }
    }

    private void recordSkill(String arguments, String response) {
        val skillName = extractCommand(arguments);
        if (skillName != null) {
            trace.recordSkill(skillName);
        }
        trace.recordToolCall(SKILL_TOOL, arguments, response, null);
    }

    private void recordRunReadCypher(String arguments, String response) {
        val cypher = extractCypher(arguments);
        val parsed = parseReadCypherResponse(response);
        if (parsed.error() != null) {
            trace.recordToolCall(RUN_READ_CYPHER, arguments, response, parsed.error());
            trace.setError(parsed.error());
            trace.recordRunReadCypher(cypher, List.of(), 0, false);
            return;
        }
        trace.recordToolCall(RUN_READ_CYPHER, arguments, response, null);
        trace.recordRunReadCypher(cypher, parsed.rows(), parsed.count(), parsed.truncated());
    }

    private static String extractCypher(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        return Try.of(() -> {
            val node = JSON.readTree(arguments);
            val cypherNode = node.get("cypher");
            return cypherNode == null || cypherNode.isNull() ? null : cypherNode.asString();
        }).getOrNull();
    }

    private static String extractCommand(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        return Try.of(() -> {
            val node = JSON.readTree(arguments);
            val commandNode = node.get(COMMAND_FIELD);
            return commandNode == null || commandNode.isNull() ? null : commandNode.asString();
        }).getOrNull();
    }

    private static ReadCypherResult parseReadCypherResponse(String response) {
        if (response == null || response.isBlank()) {
            return new ReadCypherResult(List.of(), 0, false, null);
        }
        return Try.of(() -> {
            val tree = JSON.readTree(response);
            val errorNode = tree.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                return new ReadCypherResult(List.of(), 0, false, errorNode.asString());
            }
            val rows = rowsToList(tree.get("rows"));
            val countNode = tree.get("count");
            val count = countNode == null || countNode.isNull() ? rows.size() : countNode.asInt();
            val truncatedNode = tree.get("truncated");
            val truncated = truncatedNode != null && !truncatedNode.isNull() && truncatedNode.asBoolean();
            return new ReadCypherResult(rows, count, truncated, null);
        }).recover(Throwable.class, failure -> {
            log.debug("Tool trace JSON parse failed: {}", failure.getMessage());
            return new ReadCypherResult(List.of(), 0, false, null);
        }).get();
    }

    private static List<Map<String, Object>> rowsToList(JsonNode rowsNode) {
        if (rowsNode == null || rowsNode.isNull() || !rowsNode.isArray()) {
            return List.of();
        }
        return JSON.treeToValue(rowsNode, new TypeReference<>() {
        });
    }

    private record ReadCypherResult(List<Map<String, Object>> rows, int count, boolean truncated, String error) {
    }
}
