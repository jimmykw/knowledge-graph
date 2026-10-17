package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import lombok.val;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

class CypherExecutorTest {

    private Session session;
    private TransactionContext tx;
    private CypherExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        val driver = mock(Driver.class);
        session = mock(Session.class);
        tx = mock(TransactionContext.class);
        when(driver.session()).thenReturn(session);
        when(session.executeRead(any(TransactionCallback.class), any(TransactionConfig.class))).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0).execute(tx));
        executor = new CypherExecutor(driver, 3, 1234L);
    }

    private static Value valueOf(Object object) {
        val value = mock(Value.class);
        when(value.isNull()).thenReturn(object == null);
        when(value.asObject()).thenReturn(object);
        return value;
    }

    private static void stubColumn(Record record, String key, Value value) {
        when(record.get(key)).thenReturn(value);
    }

    private static Record record(Map<String, Object> columns) {
        val record = mock(Record.class);
        when(record.keys()).thenReturn(List.copyOf(columns.keySet()));
        columns.forEach((key, object) -> stubColumn(record, key, valueOf(object)));
        return record;
    }

    private void returnRows(Record... records) {
        val result = mock(Result.class);
        Iterator<Record> iterator = List.of(records).iterator();
        when(result.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(result.next()).thenAnswer(invocation -> iterator.next());
        when(tx.run(org.mockito.ArgumentMatchers.anyString())).thenReturn(result);
    }

    private static Node node(String elementId, List<String> labels, Map<String, String> props) {
        val node = mock(Node.class);
        when(node.elementId()).thenReturn(elementId);
        when(node.labels()).thenReturn(labels);
        when(node.keys()).thenReturn(props.keySet());
        props.forEach((key, value) -> {
            when(node.get(key)).thenReturn(Values.value(value));
            when(node.containsKey(key)).thenReturn(true);
        });
        return node;
    }

    private static Relationship relationship(String type, String startId, String endId) {
        val rel = mock(Relationship.class);
        when(rel.type()).thenReturn(type);
        when(rel.startNodeElementId()).thenReturn(startId);
        when(rel.endNodeElementId()).thenReturn(endId);
        when(rel.keys()).thenReturn(List.of("description"));
        when(rel.get("description")).thenReturn(Values.value("d"));
        return rel;
    }

    @Test
    void rowsAreReturnedUnderTheCapWithoutTruncation() {
        returnRows(record(Map.of("n", "IBM")), record(Map.of("n", "HP")));

        val result = executor.execute("MATCH (n) RETURN n.name AS n").get();

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.rows()).containsExactly(Map.of("n", "IBM"), Map.of("n", "HP"));
    }

    @Test
    void rowCapSetsTruncatedAndStopsReading() {
        returnRows(record(Map.of("n", 1)), record(Map.of("n", 2)), record(Map.of("n", 3)), record(Map.of("n", 4)), record(Map.of("n", 5)));

        val result = executor.execute("MATCH (n) RETURN n").get();

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.rows()).hasSize(3);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exactlyTheCapIsNotTruncated() {
        returnRows(record(Map.of("n", 1)), record(Map.of("n", 2)), record(Map.of("n", 3)));

        assertThat(executor.execute("Q").get().truncated()).isFalse();
    }

    @Test
    void queryRunsInAReadTransactionWithTheConfiguredTimeout() {
        returnRows();

        executor.execute("Q");

        val config = ArgumentCaptor.forClass(TransactionConfig.class);
        verify(session).executeRead(any(TransactionCallback.class), config.capture());
        assertThat(config.getValue().timeout()).isEqualTo(Duration.ofMillis(1234L));
        verify(session).close();
    }

    @Test
    void nullValuesStayNull() {
        val columns = new java.util.LinkedHashMap<String, Object>();
        columns.put("n", null);
        returnRows(record(columns));

        assertThat(executor.execute("Q").get().rows().get(0)).containsEntry("n", null);
    }

    @Test
    void nodesAreFlattenedWithTypeLabelAndProperties() {
        returnRows(record(Map.of("n", node("1", List.of("Person", "Extra"), Map.of("name", "Watson")))));

        val row = executor.execute("Q").get().rows().get(0);

        assertThat((Map<String, Object>) row.get("n")).containsEntry("__type", "node")
                .containsEntry("label", "Person").containsEntry("name", "Watson");
    }

    @Test
    void listsAndMapsAreFlattenedRecursively() {
        returnRows(record(Map.of("xs", List.of(1, Map.of("k", node("1", List.of("A"), Map.of("name", "x")))))));

        val xs = (List<Object>) executor.execute("Q").get().rows().get(0).get("xs");

        assertThat(xs.get(0)).isEqualTo(1);
        assertThat((Map<String, Object>) ((Map<String, Object>) xs.get(1)).get("k")).containsEntry("__type", "node");
    }

    @Test
    void pathsAreFlattenedToAlternatingNodesAndRelationshipsWithEndpointNames() {
        val watson = node("1", List.of("Person"), Map.of("name", "Watson"));
        val ibm = node("2", List.of("Organization"), Map.of("name", "IBM"));
        val rel = relationship("WORKS_FOR", "1", "2");
        val path = mock(Path.class);
        when(path.nodes()).thenReturn(List.of(watson, ibm));
        when(path.relationships()).thenReturn(List.of(rel));
        returnRows(record(Map.of("p", path)));

        val flattened = (List<Map<String, Object>>) executor.execute("Q").get().rows().get(0).get("p");

        assertThat(flattened).hasSize(3);
        assertThat(flattened.get(0)).containsEntry("name", "Watson");
        assertThat(flattened.get(1)).containsEntry("__type", "rel").containsEntry("type", "WORKS_FOR").containsEntry("source", "Watson")
                .containsEntry("target", "IBM").containsEntry("description", "d");
        assertThat(flattened.get(2)).containsEntry("name", "IBM");
    }

    @Test
    void standaloneRelationshipsHaveNoEndpointNames() {
        returnRows(record(Map.of("r", relationship("USES", "1", "2"))));

        val row = (Map<String, Object>) executor.execute("Q").get().rows().get(0).get("r");

        assertThat(row).containsEntry("__type", "rel").containsEntry("type", "USES").doesNotContainKeys("source", "target");
    }

    @Test
    void connectivityFailuresBecomeNeo4jUnavailable() {
        when(session.executeRead(any(TransactionCallback.class), any(TransactionConfig.class))).thenThrow(new ServiceUnavailableException("down"));

        val result = executor.execute("Q");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getCause()).isInstanceOf(Neo4jUnavailableException.class).hasMessageContaining("down");
    }

    @Test
    void transientFailuresBecomeNeo4jUnavailable() {
        when(session.executeRead(any(TransactionCallback.class), any(TransactionConfig.class)))
                .thenThrow(new TransientException("Neo.TransientError.Transaction.Terminated", "timed out"));

        assertThat(executor.execute("Q").getCause()).isInstanceOf(Neo4jUnavailableException.class);
    }

    @Test
    void queryErrorsArePassedThroughForTheAgentToSee() {
        val failure = new ClientException("Neo.ClientError.Statement.SyntaxError", "Invalid input");
        when(session.executeRead(any(TransactionCallback.class), any(TransactionConfig.class))).thenThrow(failure);

        assertThat(executor.execute("Q").getCause()).isSameAs(failure);
    }
}
