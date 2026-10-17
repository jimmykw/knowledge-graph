package net.jimmykw.knowledgegraph.flow;

import static net.jimmykw.knowledgegraph.testsupport.FakeLlmServer.completion;
import static net.jimmykw.knowledgegraph.testsupport.FakeLlmServer.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.CypherExecutor.ExecutedResults;
import net.jimmykw.knowledgegraph.chat.judge.AnswerQuality;
import net.jimmykw.knowledgegraph.chat.routing.RouteDecision;
import net.jimmykw.knowledgegraph.chat.routing.RouteIntent;
import net.jimmykw.knowledgegraph.chat.routing.RouteStatus;
import net.jimmykw.knowledgegraph.chat.store.BlockedMessages;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;

class ChatFlowTest extends AppFlowTestBase {

    private static final String CHAT = "/api/knowledge-graph/chat";
    private static final String CYPHER = "MATCH (p:Person)-[:LEADS]->(o:Organization) RETURN p.name AS leader, o.name AS org";

    @Autowired
    private ChatMemory chatMemory;

    @BeforeEach
    void stubGraph() {
        when(writer.readSchema()).thenReturn(Option.some(new SchemaSnapshot(List.of("Person", "Organization"), List.of("LEADS"))));
        val rows = List.<Map<String, Object>>of(Map.of("leader", "Thomas Watson", "org", "IBM"), Map.of("leader", "Louis Gerstner", "org", "IBM"));
        when(cypherExecutor.execute(anyString())).thenReturn(Try.success(new ExecutedResults(rows, 2, false)));
    }

    private static String chatBody(String prompt, String conversationId) {
        return "{\"prompt\":\"%s\",\"conversationId\":\"%s\"}".formatted(prompt, conversationId);
    }

    private static String cypherCall(String cypher) {
        return toolCall("runReadCypher", JSON.writeValueAsString(Map.of("cypher", cypher)));
    }

    @Test
    void toolRoundsAreTracedAndTheFinalAnswerIsReturned() {
        LLM.stubAgentSequence(toolCall("Skill", "{\"command\":\"entity-lookup\"}"), toolCall("getGraphSchema", "{}"), cypherCall(CYPHER),
                completion("Thomas Watson and Louis Gerstner led IBM."));

        val reply = postJson(CHAT, chatBody("Who led IBM?", "conv-rounds"));

        assertThat(reply.status()).isEqualTo(200);
        val body = reply.json();
        assertThat(body.get("answer").asString()).isEqualTo("Thomas Watson and Louis Gerstner led IBM.");
        assertThat(body.get("conversationId").asString()).isEqualTo("conv-rounds");
        assertThat(strings(body.get("skillsExecuted"))).containsExactly("entity-lookup");
        assertThat(strings(body.get("cypherQueries"))).containsExactly(CYPHER);
        assertThat(body.get("cypher").asString()).isEqualTo(CYPHER);
        assertThat(body.get("rowCount").asInt()).isEqualTo(2);
        assertThat(body.get("evidence")).hasSize(1);
        assertThat(body.get("evidence").get(0).get("rows")).hasSize(2);
        assertThat(body.get("error").isNull()).isTrue();
        assertThat(LLM.agentRequestBodies()).hasSize(4);
    }

    @Test
    void everyToolRoundCarriesTheQuestionAndALaterTurnCarriesEarlierTurns() {
        LLM.stubAgentSequence(completion("first answer text"));
        postJson(CHAT, chatBody("first question text", "conv-history"));
        LLM.reset();
        LLM.stubAgentSequence(toolCall("getGraphSchema", "{}"), cypherCall(CYPHER), completion("second answer text"));

        val reply = postJson(CHAT, chatBody("second question text", "conv-history"));

        assertThat(reply.status()).isEqualTo(200);
        val bodies = LLM.agentRequestBodies();
        assertThat(bodies).hasSize(3);
        // Regression: with conversationHistoryEnabled(false) every round after the first dropped the user's question.
        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("second question text"));
        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("first question text").contains("first answer text"));
        assertThat(bodies.get(2)).contains("getGraphSchema").contains("runReadCypher");
    }

    @Test
    void roundCapStopsALoopingModel() {
        LLM.stubAgentSequence(toolCall("getGraphSchema", "{}"));

        val reply = postJson(CHAT, chatBody("loop forever", "conv-cap"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("error").asString()).isEqualTo("Exceeded max tool call rounds");
        assertThat(LLM.agentRequestBodies()).hasSize(4);
    }

    @Test
    void writeAttemptIsReportedBackToTheModelAndNeverExecuted() {
        LLM.stubAgentSequence(cypherCall("MATCH (n) DETACH DELETE n"), completion("I could not do that."));

        val reply = postJson(CHAT, chatBody("delete everything", "conv-write"));

        assertThat(reply.json().get("error").asString()).contains("write operations are not allowed");
        assertThat(LLM.agentRequestBodies().get(1)).contains("write operations are not allowed");
        verify(cypherExecutor, never()).execute(anyString());
    }

    @Test
    void loadCsvAttemptIsReportedBackWithTheExternalDataError() {
        LLM.stubAgentSequence(cypherCall("LOAD CSV FROM 'file:///etc/passwd' AS row RETURN row"), completion("Not possible."));

        val reply = postJson(CHAT, chatBody("read a file", "conv-csv"));

        assertThat(reply.json().get("error").asString()).contains("loading external data");
        assertThat(LLM.agentRequestBodies().get(1)).contains("loading external data");
        verify(cypherExecutor, never()).execute(anyString());
    }

    @Test
    void deleteClearsTheConversation() {
        LLM.stubAgentSequence(completion("hello"));
        postJson(CHAT, chatBody("hi there", "conv-delete"));

        assertThat(delete(CHAT + "?conversationId=conv-delete").status()).isEqualTo(204);
        assertThat(delete(CHAT + "?conversationId=conv-delete").status()).isEqualTo(404);
        assertThat(chatMemory.get("conv-delete")).isEmpty();
    }

    @Test
    void blankPromptIs400() {
        val reply = postJson(CHAT, "{\"prompt\":\"  \"}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.json().get("error").asString()).isEqualTo("Prompt must not be blank.");
    }

    @Test
    void schemaEndpointReturnsTheRecordedSchema() {
        val reply = get("/api/knowledge-graph/schema");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(strings(reply.json().get("labels"))).containsExactly("Person", "Organization");
    }

    @Test
    void blockedPromptIsAnsweredByTheToolFreeClientAndStoredAsBlocked() {
        when(router.route(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Option.of(new RouteDecision(RouteIntent.CHITCHAT, 0.01, RouteStatus.BLOCKED)));
        LLM.stubBodyContaining(5, completion("Hello! Glad to help."), "outside the scope");

        val reply = postJson(CHAT, chatBody("thanks, you are great", "conv-blocked"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("answer").asString()).isEqualTo("Hello! Glad to help.");
        assertThat(reply.json().get("route").get("status").asString()).isEqualTo("BLOCKED");
        assertThat(LLM.agentRequestBodies()).isEmpty();
        assertThat(chatMemory.get("conv-blocked")).hasSize(2).allMatch(BlockedMessages::isBlocked);
    }

    @Test
    void judgeVerdictIsAttachedToTheResponse() {
        when(judge.judge(org.mockito.ArgumentMatchers.any())).thenReturn(Option.of(AnswerQuality.low("not grounded in the rows", false)));
        LLM.stubAgentSequence(completion("An answer."));

        val reply = postJson(CHAT, chatBody("Who led IBM?", "conv-judge"));

        assertThat(reply.json().get("quality").get("status").asString()).isEqualTo("LOW");
        assertThat(reply.json().get("quality").get("feedback").asString()).isEqualTo("not grounded in the rows");
    }
}
