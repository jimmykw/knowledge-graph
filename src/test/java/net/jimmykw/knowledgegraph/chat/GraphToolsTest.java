package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.CypherExecutor.ExecutedResults;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GraphToolsTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    private SchemaService schemaService;
    private CypherExecutor executor;
    private GraphTools tools;

    @BeforeEach
    void setUp() {
        schemaService = mock(SchemaService.class);
        executor = mock(CypherExecutor.class);
        tools = new GraphTools(schemaService, executor);
    }

    private static String errorOf(String json) {
        return JSON.readTree(json).get("error").asString();
    }

    @Test
    void blankAndNullCypherReturnErrorWithoutExecuting() {
        assertThat(errorOf(tools.runReadCypher(null))).isEqualTo("Cypher query must not be blank.");
        assertThat(errorOf(tools.runReadCypher("   "))).isEqualTo("Cypher query must not be blank.");
        verifyNoInteractions(executor);
    }

    @Test
    void writeAttemptReturnsWriteErrorWithoutExecuting() {
        assertThat(errorOf(tools.runReadCypher("MATCH (n) DETACH DELETE n"))).contains("write operations are not allowed");
        verifyNoInteractions(executor);
    }

    @Test
    void externalAttemptsReturnExternalErrorWithoutExecuting() {
        val loadCsv = errorOf(tools.runReadCypher("LOAD CSV FROM 'file:///etc/passwd' AS row RETURN row"));
        val loadJson = errorOf(tools.runReadCypher("CALL apoc.load.json('http://169.254.169.254/') YIELD value RETURN value"));

        assertThat(loadCsv).contains("loading external data").doesNotContain("write operations");
        assertThat(loadJson).contains("loading external data");
        verifyNoInteractions(executor);
    }

    @Test
    void writeMessageTakesPrecedenceOverExternalMessage() {
        val error = errorOf(tools.runReadCypher("CALL apoc.periodic.iterate('MATCH (n) RETURN n', 'CREATE (m)', {})"));

        assertThat(error).contains("write operations are not allowed");
    }

    @Test
    void executorFailureBecomesErrorJson() {
        when(executor.execute(anyString())).thenReturn(Try.failure(new IllegalStateException("syntax error")));

        assertThat(errorOf(tools.runReadCypher("MATCH (n) RETURN n"))).isEqualTo("syntax error");
    }

    @Test
    void executorFailureWithNullMessageBecomesEmptyError() {
        when(executor.execute(anyString())).thenReturn(Try.failure(new RuntimeException()));

        assertThat(errorOf(tools.runReadCypher("MATCH (n) RETURN n"))).isEmpty();
    }

    @Test
    void successReturnsRowsCountAndTruncated() {
        val rows = List.<Map<String, Object>>of(Map.of("name", "IBM"));
        when(executor.execute("MATCH (n) RETURN n.name AS name")).thenReturn(Try.success(new ExecutedResults(rows, 1, true)));

        val tree = JSON.readTree(tools.runReadCypher("MATCH (n) RETURN n.name AS name"));

        assertThat(tree.get("rows").get(0).get("name").asString()).isEqualTo("IBM");
        assertThat(tree.get("count").asInt()).isEqualTo(1);
        assertThat(tree.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void unserializableRowsFallBackToSerializationError() {
        val rows = List.<Map<String, Object>>of(Map.of("bad", new Object() {
            public String getValue() {
                throw new IllegalStateException("cannot serialize");
            }
        }));
        when(executor.execute(anyString())).thenReturn(Try.success(new ExecutedResults(rows, 1, false)));

        assertThat(errorOf(tools.runReadCypher("MATCH (n) RETURN n"))).isEqualTo("Failed to serialize result to JSON");
    }

    @Test
    void getGraphSchemaReturnsLabelsAndRelTypes() {
        when(schemaService.readSchemaRaw()).thenReturn(new SchemaSnapshot(List.of("Person"), List.of("USES")));

        val tree = JSON.readTree(tools.getGraphSchema());

        assertThat(tree.get("labels").get(0).asString()).isEqualTo("Person");
        assertThat(tree.get("relTypes").get(0).asString()).isEqualTo("USES");
    }

    @Test
    void getGraphSchemaFailureBecomesErrorJson() {
        when(schemaService.readSchemaRaw()).thenThrow(new IllegalStateException("neo4j gone"));

        assertThat(errorOf(tools.getGraphSchema())).isEqualTo("neo4j gone");
    }
}
