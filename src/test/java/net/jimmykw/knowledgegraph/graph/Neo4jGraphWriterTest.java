package net.jimmykw.knowledgegraph.graph;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import io.vavr.control.Either;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityProbe;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewNode;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NodeUpdate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
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
        runReturns(resultOf(record(Map.of("id", Values.value("id-7")))));

        assertThat(writer.findDocumentId("abc")).contains("id-7");
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
                record(Map.of("idx", Values.value(0), "id", Values.value("id-10"))),
                record(Map.of("idx", Values.value(1), "id", Values.value("id-11")))));

        val ids = writer.mergeEntities(List.of(new ExtractedEntity("  IBM ", "Organization", null), entity("Watson", "Person")), "hash-1");

        assertThat(ids.toJavaMap()).containsEntry(0, "id-10").containsEntry(1, "id-11");
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
        assertThatThrownBy(() -> writer.mergeRelationships(List.of(new ResolvedRelationship("id-1", "id-2", "T`", "", "a", "b"))))
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
        runReturns(resultOf(record(Map.of("idx", Values.value(0), "id", Values.value("id-55")))));

        val ids = writer.mergeRelationships(List.of(new ResolvedRelationship("id-1", "id-2", "USES", null, "A", "B")));

        assertThat(ids.toJavaMap()).containsEntry(0, "id-55");
        val query = ArgumentCaptor.forClass(String.class);
        val params = ArgumentCaptor.<Map<String, Object>>captor();
        verify(session).run(query.capture(), params.capture());
        assertThat(query.getValue()).contains("apoc.merge.relationship(a, r.type, {description: r.description}, {}, b, {})");
        assertThat((java.util.List<Map<String, Object>>) params.getValue().get("rels")).singleElement().satisfies(rel ->
                assertThat(rel).containsEntry("idx", 0).containsEntry("srcId", "id-1").containsEntry("tgtId", "id-2")
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
        runReturns(resultOf(record(Map.of("id", Values.value("id-3")))));

        assertThat(writer.mergeDocument("h", null)).isEqualTo("id-3");

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

    @Test
    void ensureSchemaRunsEveryStatementEvenWhenOneFailsThenWaitsForIndexes() {
        when(session.run(anyString())).thenReturn(mock(Result.class));
        when(session.run(Neo4jGraphWriter.SCHEMA_STATEMENTS.head())).thenThrow(new ClientException("Neo.ClientError.Schema", "dupes"));

        writer.ensureSchema();

        Neo4jGraphWriter.SCHEMA_STATEMENTS.forEach(statement -> verify(session).run(statement));
        verify(session).run("CALL db.awaitIndexes(300)");
    }

    @Test
    void findCandidatesSendsProbesAndGroupsRowsByIdx() {
        runReturns(resultOf(
                record(candidateRow(0, "n-1", "IBM", true)),
                record(candidateRow(0, "n-2", "IBM Research", false)),
                record(candidateRow(2, "n-3", "Watson", true))));

        val candidates = writer.findCandidates(List.of(
                new EntityProbe(0, "IBM Corp.", "ibm corp.", "ibm", "Organization", Option.some("ibm corp.")),
                new EntityProbe(2, "&", "&", "&", "Concept", Option.none())), 5, 1.5);

        assertThat(candidates.get(0).map(List::size)).contains(2);
        assertThat(candidates.get(0).flatMap(List::headOption).map(Candidate::exact)).contains(true);
        assertThat(candidates.get(2).flatMap(List::headOption).map(Candidate::aliases)).contains(List.of("Watson", "alias"));
        val params = capturedParams();
        assertThat(params).containsEntry("limit", 5).containsEntry("minScore", 1.5);
        assertThat((java.util.List<Map<String, Object>>) params.get("probes")).satisfiesExactly(
                first -> assertThat(first).containsEntry("idx", 0).containsEntry("nameNorm", "ibm corp.").containsEntry("matchKey", "ibm")
                        .containsEntry("query", "ibm corp."),
                second -> assertThat(second).containsEntry("query", null));
    }

    @Test
    void findCandidatesOfNoProbesDoesNotTouchNeo4j() {
        assertThat(writer.findCandidates(List.empty(), 5, 1.0)).isEmpty();

        verifyNoInteractions(driver);
    }

    private static Map<String, Value> candidateRow(int idx, String id, String name, boolean exact) {
        return Map.of("idx", Values.value(idx), "id", Values.value(id), "name", Values.value(name), "label", Values.value("Organization"),
                "description", Values.value("d"), "aliases", Values.value(java.util.List.of(name, "alias")),
                "neighbours", Values.value(java.util.List.of("-[USES]-> X")), "exact", Values.value(exact));
    }

    @Test
    void applyResolutionCreatesUpdatesAndLinksMentionsInOneTransaction() {
        val tx = mock(TransactionContext.class);
        when(session.executeWrite(any())).thenAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0).execute(tx));
        val created = resultOf(record(Map.of("key", Values.value(0), "id", Values.value("new-0"))));
        when(tx.run(anyString(), anyMap())).thenAnswer(invocation -> invocation.<String>getArgument(0).contains("apoc.create.node")
                ? created
                : mock(Result.class));
        val plan = new ResolutionPlan(
                List.of(new NewNode(0, "Apple Inc.", "Organization", "maker", List.of("Apple Inc.", "Apple"))),
                List.of(new NodeUpdate("old-1", "Watson Jr.", "Technology", "Person", List.of("Watson Jr.", "Watson"))),
                List.of(new Mention(0, "Apple", "d0", Either.left(0)), new Mention(1, "Watson", "d1", Either.right("old-1"))));

        val ids = writer.applyResolution(plan, "hash-1");

        assertThat(ids.toJavaMap()).containsEntry(0, "new-0").containsEntry(1, "old-1");
        val queries = ArgumentCaptor.forClass(String.class);
        val params = ArgumentCaptor.<Map<String, Object>>captor();
        verify(tx, org.mockito.Mockito.times(3)).run(queries.capture(), params.capture());
        assertThat(queries.getAllValues()).satisfiesExactly(
                create -> assertThat(create).contains("apoc.create.node(['Entity', n.label]").doesNotContain("Organization"),
                update -> assertThat(update).contains("apoc.create.removeLabels").contains("apoc.create.addLabels"),
                mentions -> assertThat(mentions).contains("MERGE (d)-[m:MENTIONS]->(e)"));
        assertThat((java.util.List<Map<String, Object>>) params.getAllValues().get(0).get("nodes")).singleElement().satisfies(node ->
                assertThat(node).containsEntry("nameNorm", "apple inc.").containsEntry("matchKey", "apple")
                        .containsEntry("aliasesNorm", java.util.List.of("apple inc.", "apple")).containsEntry("aliasesText", "Apple Inc. | Apple"));
        assertThat((java.util.List<Map<String, Object>>) params.getAllValues().get(1).get("updates")).singleElement().satisfies(update ->
                assertThat(update).containsEntry("id", "old-1").containsEntry("removeLabels", java.util.List.of("Technology"))
                        .containsEntry("label", "Person").containsEntry("nameNorm", "watson jr."));
        assertThat((java.util.List<Map<String, Object>>) params.getAllValues().get(2).get("mentions")).extracting(mention -> mention.get("id"))
                .containsExactly("new-0", "old-1");
        assertThat(params.getAllValues().get(2)).containsEntry("hash", "hash-1");
    }

    @Test
    void applyResolutionKeepsTheLabelWhenItDoesNotChangeAndSkipsEmptyParts() {
        val tx = mock(TransactionContext.class);
        when(session.executeWrite(any())).thenAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0).execute(tx));
        when(tx.run(anyString(), anyMap())).thenReturn(mock(Result.class));
        val plan = new ResolutionPlan(List.empty(), List.of(new NodeUpdate("old-1", "IBM", "Organization", "Organization", List.of("IBM"))),
                List.of(new Mention(0, "IBM", "d", Either.right("old-1"))));

        writer.applyResolution(plan, "h");

        val params = ArgumentCaptor.<Map<String, Object>>captor();
        verify(tx, org.mockito.Mockito.times(2)).run(anyString(), params.capture());
        assertThat((java.util.List<Map<String, Object>>) params.getAllValues().get(0).get("updates")).singleElement()
                .satisfies(update -> assertThat(update).containsEntry("removeLabels", java.util.List.of()));
    }

    @Test
    void applyResolutionRefusesUnsafeLabelsWithoutOpeningASession() {
        val plan = new ResolutionPlan(List.of(new NewNode(0, "x", "A`B", "", List.of("x"))), List.empty(),
                List.of(new Mention(0, "x", "", Either.left(0))));

        assertThatThrownBy(() -> writer.applyResolution(plan, "h")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(driver);
    }
}
