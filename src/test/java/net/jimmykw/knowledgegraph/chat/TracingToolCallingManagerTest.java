package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

class TracingToolCallingManagerTest {

    private ToolCallingManager delegate;
    private ToolTrace trace;
    private TracingToolCallingManager manager;

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallingManager.class);
        trace = new ToolTrace();
        manager = new TracingToolCallingManager(delegate, trace);
    }

    private void execute(ToolCall call, String responseData) {
        val assistant = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        val toolResponse = ToolResponseMessage.builder().responses(List.of(new ToolResponse(call.id(), call.name(), responseData))).build();
        val result = ToolExecutionResult.builder().conversationHistory(List.<Message>of(assistant, toolResponse)).build();
        when(delegate.executeToolCalls(any(), any())).thenReturn(result);
        manager.executeToolCalls(new Prompt("q"), new ChatResponse(List.of(new Generation(assistant))));
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall("id-" + name, "function", name, arguments);
    }

    @Test
    void skillCallIsRecordedByName() {
        execute(call("Skill", "{\"command\":\"entity-lookup\"}"), "loaded");

        assertThat(trace.skillsExecuted()).containsExactly("entity-lookup");
        assertThat(trace.toolCalls()).hasSize(1);
        assertThat(trace.roundCount()).isEqualTo(1);
    }

    @Test
    void skillCallWithoutCommandRecordsNoSkillName() {
        execute(call("Skill", "{}"), "loaded");

        assertThat(trace.skillsExecuted()).isEmpty();
        assertThat(trace.toolCalls()).hasSize(1);
    }

    @Test
    void runReadCypherRowsCountAndTruncatedAreRecorded() {
        execute(call("runReadCypher", "{\"cypher\":\"MATCH (n) RETURN n.name AS name\"}"),
                "{\"rows\":[{\"name\":\"IBM\"},{\"name\":\"HP\"}],\"count\":2,\"truncated\":true}");

        assertThat(trace.cypherQueries()).containsExactly("MATCH (n) RETURN n.name AS name");
        assertThat(trace.lastCount()).isEqualTo(2);
        assertThat(trace.lastTruncated()).isTrue();
        assertThat(trace.lastRows()).hasSize(2).first().satisfies(row -> assertThat(row).containsEntry("name", "IBM"));
        assertThat(trace.error()).isNull();
        assertThat(trace.evidence()).hasSize(1);
    }

    @Test
    void countDefaultsToRowCountWhenMissing() {
        execute(call("runReadCypher", "{\"cypher\":\"Q\"}"), "{\"rows\":[{\"a\":1}]}");

        assertThat(trace.lastCount()).isEqualTo(1);
        assertThat(trace.lastTruncated()).isFalse();
    }

    @Test
    void errorJsonFromTheToolIsRecordedAsAnError() {
        execute(call("runReadCypher", "{\"cypher\":\"CREATE (n)\"}"), "{\"error\":\"write operations are not allowed\"}");

        assertThat(trace.error()).isEqualTo("write operations are not allowed");
        assertThat(trace.evidence().get(0).error()).isEqualTo("write operations are not allowed");
        assertThat(trace.lastCount()).isZero();
        assertThat(trace.toolCalls().get(0).error()).isEqualTo("write operations are not allowed");
    }

    @Test
    void laterSuccessfulQueryClearsAnEarlierError() {
        execute(call("runReadCypher", "{\"cypher\":\"bad\"}"), "{\"error\":\"nope\"}");
        execute(call("runReadCypher", "{\"cypher\":\"good\"}"), "{\"rows\":[],\"count\":0,\"truncated\":false}");

        assertThat(trace.error()).isNull();
        assertThat(trace.evidence()).hasSize(2);
        assertThat(trace.roundCount()).isEqualTo(2);
    }

    @Test
    void unparsableResponseIsTolerated() {
        execute(call("runReadCypher", "{\"cypher\":\"Q\"}"), "this is not json");

        assertThat(trace.error()).isNull();
        assertThat(trace.lastCount()).isZero();
        assertThat(trace.evidence()).hasSize(1);
    }

    @Test
    void blankResponseAndBlankArgumentsAreTolerated() {
        execute(call("runReadCypher", ""), "");

        assertThat(trace.evidence()).hasSize(1);
        assertThat(trace.evidence().get(0).cypher()).isNull();
        assertThat(trace.cypherQueries()).isEmpty();
    }

    @Test
    void otherToolsAreRecordedAsPlainToolCalls() {
        execute(call("getGraphSchema", "{}"), "{\"labels\":[]}");

        assertThat(trace.toolCalls()).extracting(ToolTrace.ToolCallEntry::name).containsExactly("getGraphSchema");
        assertThat(trace.evidence()).isEmpty();
    }

    @Test
    void roundCountIncrementsPerExecution() {
        execute(call("getGraphSchema", "{}"), "{}");
        execute(call("getGraphSchema", "{}"), "{}");

        assertThat(trace.roundCount()).isEqualTo(2);
    }
}
