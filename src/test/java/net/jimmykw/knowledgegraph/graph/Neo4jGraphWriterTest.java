package net.jimmykw.knowledgegraph.graph;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;

class Neo4jGraphWriterTest {

    private Driver driver;
    private Session session;
    private Neo4jGraphWriter writer;

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        writer = new Neo4jGraphWriter(driver);
    }

    private void runReturns(Result result) {
        when(session.run(anyString(), anyMap())).thenReturn(result);
    }

    private static Record record(Map<String, Value> values) {
        val record = mock(Record.class);
        values.forEach((key, value) -> when(record.get(key)).thenReturn(value));
        return record;
    }

    private static Result resultOf(Record... records) {
        val result = mock(Result.class);
        when(result.list()).thenReturn(java.util.List.of(records));
        when(result.hasNext()).thenReturn(records.length > 0);
        if (records.length > 0) {
            when(result.next()).thenReturn(records[0]);
            when(result.single()).thenReturn(records[0]);
        }
        return result;
    }

    private Map<String, Object> capturedParams() {
        val captor = ArgumentCaptor.<Map<String, Object>>captor();
        verify(session).run(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void findDocumentIdReturnsSomeWhenPresent() {
        runReturns(resultOf(record(Map.of("id", Values.value(7L)))));

        assertThat(writer.findDocumentId("abc")).contains(7L);
        assertThat(capturedParams()).containsEntry("hash", "abc");
    }

    @Test
    void findDocumentIdReturnsNoneWhenAbsent() {
        runReturns(resultOf());

        assertThat(writer.findDocumentId("abc")).isEmpty();
    }

    @Test
    void mergeEntitiesSendsIndexedNormalizedParametersAndMapsIdsByIdx() {
        runReturns(resultOf(
                record(Map.of("idx", Values.value(0), "id", Values.value(10L))),
                record(Map.of("idx", Values.value(1), "id", Values.value(11L)))));

        val ids = writer.mergeEntities(List.of(new ExtractedEntity("  IBM ", "Organization", null), entity("Watson", "Person")), "hash-1");

        assertThat(ids.toJavaMap()).containsEntry(0, 10L).containsEntry(1, 11L);
        val params = capturedParams();
        assertThat(params).containsEntry("hash", "hash-1");
        assertThat((java.util.List<Map<String, Object>>) params.get("entities")).satisfiesExactly(
                first -> assertThat(first).containsEntry("idx", 0).containsEntry("name", "  IBM ").containsEntry("nameNorm", "ibm")
                        .containsEntry("label", "Organization").containsEntry("description", ""),
                second -> assertThat(second).containsEntry("idx", 1).containsEntry("nameNorm", "watson").containsEntry("label", "Person"));
    }

    @Test
    void mergeEntitiesUsesApocWithTheLabelAsAParameter() {
        runReturns(resultOf());

        writer.mergeEntities(List.of(entity("IBM", "Organization")), "h");

        val query = ArgumentCaptor.forClass(String.class);
        verify(session).run(query.capture(), anyMap());
        assertThat(query.getValue()).contains("apoc.merge.node([e.label]").doesNotContain("Organization");
    }

    @Test
    void unsafeLabelsAndTypesAreRefusedWithoutOpeningASession() {
        assertThatThrownBy(() -> writer.mergeEntities(List.of(entity("x", "A`B")), "h")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.mergeRelationships(List.of(new ResolvedRelationship(1L, 2L, "T`", "", "a", "b"))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(driver);
    }

    @Test
    void mergeEntitiesWithNoEntitiesDoesNotTouchNeo4j() {
        assertThat(writer.mergeEntities(List.empty(), "h")).isEmpty();

        verifyNoInteractions(driver);
    }

    @Test
    void mergeRelationshipsSendsEndpointIdsAndPassesAMapAsTheSixthApocArgument() {
        runReturns(resultOf(record(Map.of("idx", Values.value(0), "id", Values.value(55L)))));

        val ids = writer.mergeRelationships(List.of(new ResolvedRelationship(1L, 2L, "USES", null, "A", "B")));

        assertThat(ids.toJavaMap()).containsEntry(0, 55L);
        val query = ArgumentCaptor.forClass(String.class);
        val params = ArgumentCaptor.<Map<String, Object>>captor();
        verify(session).run(query.capture(), params.capture());
        assertThat(query.getValue()).contains("apoc.merge.relationship(a, r.type, {description: r.description}, {}, b, {})");
        assertThat((java.util.List<Map<String, Object>>) params.getValue().get("rels")).singleElement().satisfies(rel ->
                assertThat(rel).containsEntry("idx", 0).containsEntry("srcId", 1L).containsEntry("tgtId", 2L)
                        .containsEntry("type", "USES").containsEntry("description", ""));
    }

    @Test
    void mergeRelationshipsWithNoRelationshipsDoesNotTouchNeo4j() {
        assertThat(writer.mergeRelationships(List.empty())).isEmpty();

        verifyNoInteractions(driver);
    }

    @Test
    void recordSchemaIsANoOpWhenBothSetsAreEmpty() {
        writer.recordSchema(HashSet.empty(), HashSet.empty());

        verifyNoInteractions(driver);
    }

    @Test
    void recordSchemaSendsBothLists() {
        runReturns(resultOf());

        writer.recordSchema(HashSet.of("Person"), HashSet.of("USES"));

        val params = capturedParams();
        assertThat(params).containsEntry("id", Neo4jGraphWriter.SCHEMA_ID).containsEntry("labels", java.util.List.of("Person"))
                .containsEntry("relTypes", java.util.List.of("USES")).containsKey("updatedAt");
    }

    @Test
    void mergeDocumentRecordsTheDocumentLabelInTheSchema() {
        runReturns(resultOf(record(Map.of("id", Values.value(3L)))));

        assertThat(writer.mergeDocument("h", null)).isEqualTo(3L);

        val params = ArgumentCaptor.<Map<String, Object>>captor();
        verify(session, org.mockito.Mockito.times(2)).run(anyString(), params.capture());
        assertThat(params.getAllValues().get(0)).containsEntry("hash", "h").containsEntry("filename", "");
        assertThat(params.getAllValues().get(1)).containsEntry("labels", java.util.List.of("Document"));
    }

    @Test
    void readSchemaReturnsNoneWhenNoSchemaNodeExists() {
        runReturns(resultOf());

        assertThat(writer.readSchema()).isEmpty();
    }

    @Test
    void readSchemaTreatsNullListsAsEmpty() {
        runReturns(resultOf(record(Map.of("labels", Values.NULL, "relTypes", Values.NULL))));

        val snapshot = writer.readSchema();

        assertThat(snapshot).isNotEmpty();
        assertThat(snapshot.get().labels()).isEmpty();
        assertThat(snapshot.get().relTypes()).isEmpty();
    }

    @Test
    void readSchemaReturnsStoredLists() {
        runReturns(resultOf(record(Map.of(
                "labels", Values.value(java.util.List.of("Person", "Organization")), "relTypes", Values.value(java.util.List.of("USES"))))));

        val snapshot = writer.readSchema().get();

        assertThat(snapshot.labels()).containsExactly("Person", "Organization");
        assertThat(snapshot.relTypes()).containsExactly("USES");
    }

    @Test
    void serviceUnavailableIsWrappedAsNeo4jUnavailable() {
        when(session.run(anyString(), anyMap())).thenThrow(new ServiceUnavailableException("no route"));

        assertThatThrownBy(() -> writer.findDocumentId("h"))
                .isInstanceOf(Neo4jUnavailableException.class)
                .hasMessageContaining("Neo4j is unavailable")
                .hasCauseInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void transientErrorIsWrappedAsNeo4jUnavailable() {
        when(session.run(anyString(), anyMap())).thenThrow(new TransientException("Neo.TransientError.General", "busy"));

        assertThatThrownBy(() -> writer.findDocumentId("h")).isInstanceOf(Neo4jUnavailableException.class);
    }

    @Test
    void otherRuntimeErrorsPassThroughUnchanged() {
        val failure = new ClientException("Neo.ClientError.Statement.SyntaxError", "bad");
        when(session.run(anyString(), anyMap())).thenThrow(failure);

        assertThatThrownBy(() -> writer.findDocumentId("h")).isSameAs(failure);
    }

    @Test
    void sessionIsClosedAfterUse() {
        runReturns(resultOf());

        writer.findDocumentId("h");

        verify(session).close();
        verify(session, never()).beginTransaction();
    }
}
