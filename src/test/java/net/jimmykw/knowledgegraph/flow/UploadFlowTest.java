package net.jimmykw.knowledgegraph.flow;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entities;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.json;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.relationship;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.relationships;
import static net.jimmykw.knowledgegraph.testsupport.FakeLlmServer.completion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityProbe;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewNode;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionPlan;
import net.jimmykw.knowledgegraph.testsupport.PdfFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UploadFlowTest extends AppFlowTestBase {

    private static final String PASS_1 = "Extract the entities";
    private static final String PASS_2 = "Canonical entities already discovered";
    private static final String RESOLUTION = "Decide for each new entity";

    @BeforeEach
    void stubWriter() {
        when(writer.findDocumentId(anyString())).thenReturn(Option.none());
        when(writer.mergeDocument(anyString(), anyString())).thenReturn("4:doc:5");
        when(writer.findCandidates(any(), anyInt(), anyDouble())).thenReturn(HashMap.empty());
        when(writer.applyResolution(any(), anyString())).thenAnswer(invocation -> invocation.<ResolutionPlan>getArgument(0).mentions()
                .toMap(Mention::idx, mention -> mention.target().fold(key -> "4:new:" + key, id -> id)));
        when(writer.mergeRelationships(any())).thenAnswer(invocation -> invocation.<List<ResolvedRelationship>>getArgument(0)
                .zipWithIndex().foldLeft(HashMap.<Integer, String>empty(), (acc, pair) -> acc.put(pair._2, "4:rel:" + pair._2)));
    }

    /** Offers {@code candidate} for the probe whose name is {@code name}. */
    private void graphHas(String name, Candidate candidate) {
        when(writer.findCandidates(any(), anyInt(), anyDouble())).thenAnswer(invocation -> invocation.<List<EntityProbe>>getArgument(0)
                .filter(probe -> probe.name().equals(name))
                .toMap(EntityProbe::idx, probe -> List.of(candidate.withIdx(probe.idx()))));
    }

    private static Candidate node(String id, String name, String label) {
        return new Candidate(0, id, name, label, name + " node", List.of(name), List.empty(), true);
    }

    private ResolvedRelationship writtenRelationship() {
        val relCaptor = ArgumentCaptor.<List<ResolvedRelationship>>captor();
        verify(writer).mergeRelationships(relCaptor.capture());
        return relCaptor.getValue().head();
    }

    private void stubHappyExtraction() {
        LLM.stubBodyContaining(5, completion(json(entities(entity("IBM", "Organization"), entity("Thomas Watson", "Person")))), PASS_1);
        LLM.stubBodyContaining(5, completion(json(relationships(relationship("Thomas Watson", "Person", "LEADS", "IBM", "Organization")))), PASS_2);
    }

    @Test
    void pdfIsExtractedInTwoPassesAndWrittenToTheGraph() {
        stubHappyExtraction();

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(200);
        val body = reply.json();
        assertThat(body.get("status").asString()).isEqualTo("PROCESSED");
        assertThat(body.get("nodeCount").asInt()).isEqualTo(2);
        assertThat(body.get("relationshipCount").asInt()).isEqualTo(1);
        assertThat(body.get("countsByLabel").get("Person").asInt()).isEqualTo(1);
        assertThat(body.get("countsByType").get("LEADS").asInt()).isEqualTo(1);
        assertThat(body.get("failedChunks").asInt()).isZero();
        assertThat(body.get("entitiesCreated").asInt()).isEqualTo(2);
        assertThat(body.get("entitiesMerged").asInt()).isZero();
        assertThat(body.get("unresolvedEntities").asInt()).isZero();

        val planCaptor = ArgumentCaptor.forClass(ResolutionPlan.class);
        verify(writer).applyResolution(planCaptor.capture(), anyString());
        assertThat(planCaptor.getValue().newNodes()).extracting(NewNode::name).containsExactly("IBM", "Thomas Watson");
        verify(writer, never()).mergeEntities(any(), anyString());
        val rel = writtenRelationship();
        assertThat(rel.type()).isEqualTo("LEADS");
        assertThat(rel.sourceId()).isEqualTo("4:new:1");
        assertThat(rel.targetId()).isEqualTo("4:new:0");
        assertThat(LLM.requestBodies().filter(requestBody -> requestBody.contains(RESOLUTION))).isEmpty();
    }

    @Test
    void anExactSameLabelMatchMergesIntoTheExistingNodeWithoutAResolutionCall() {
        stubHappyExtraction();
        graphHas("IBM", node("4:db:1", "International Business Machines", "Organization"));

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("entitiesMerged").asInt()).isEqualTo(1);
        assertThat(reply.json().get("entitiesCreated").asInt()).isEqualTo(1);
        assertThat(reply.json().get("nodeCount").asInt()).isEqualTo(2);
        assertThat(writtenRelationship().targetId()).isEqualTo("4:db:1");
        assertThat(LLM.requestBodies().filter(requestBody -> requestBody.contains(RESOLUTION))).isEmpty();
    }

    @Test
    void aCrossLabelMatchIsDecidedByTheResolutionModel() {
        stubHappyExtraction();
        graphHas("Thomas Watson", node("4:db:7", "Thomas J. Watson Sr.", "Technology"));
        LLM.stubBodyContaining(5, completion("{\"decisions\": [{\"entity\": \"n1\", \"match\": \"c1\", "
                + "\"canonicalName\": \"Thomas J. Watson Sr.\"}]}"), RESOLUTION);

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("entitiesMerged").asInt()).isEqualTo(1);
        val planCaptor = ArgumentCaptor.forClass(ResolutionPlan.class);
        verify(writer).applyResolution(planCaptor.capture(), anyString());
        assertThat(planCaptor.getValue().updates()).singleElement().satisfies(update -> {
            assertThat(update.name()).isEqualTo("Thomas J. Watson Sr.");
            assertThat(update.label()).isEqualTo("Person");
            assertThat(update.oldLabel()).isEqualTo("Technology");
        });
        assertThat(writtenRelationship().sourceId()).isEqualTo("4:db:7");
        assertThat(LLM.requestBodies().filter(requestBody -> requestBody.contains(RESOLUTION)))
                .singleElement().satisfies(requestBody -> assertThat(requestBody).doesNotContain("4:db:7"));
    }

    @Test
    void aFailingResolutionCallCreatesTheEntityAsNewAndTheUploadSucceeds() {
        stubHappyExtraction();
        graphHas("Thomas Watson", node("4:db:7", "Thomas J. Watson Sr.", "Technology"));
        LLM.stubBodyContainingFails(5, 500, RESOLUTION);

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("unresolvedEntities").asInt()).isEqualTo(1);
        assertThat(reply.json().get("entitiesCreated").asInt()).isEqualTo(2);
        assertThat(reply.json().get("relationshipCount").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateUploadMakesNoLlmCallsAndNoWrites() {
        when(writer.findDocumentId(anyString())).thenReturn(Option.some("4:doc:9"));

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("status").asString()).isEqualTo("SKIPPED_DUPLICATE");
        assertThat(reply.json().get("nodeCount").asInt()).isZero();
        assertThat(LLM.requestCount()).isZero();
        verify(writer, never()).applyResolution(any(), anyString());
    }

    @Test
    void nonPdfIs400() {
        val reply = upload("notes.txt", "text/plain", "hello".getBytes());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.json().get("message").asString()).isEqualTo("Only PDF files are accepted.");
    }

    @Test
    void garbageBytesNamedPdfAre400AndNothingIsRecorded() {
        val reply = upload("x.pdf", "application/pdf", PdfFixtures.garbage());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.json().get("message").asString()).contains("could not be read");
        verify(writer, never()).mergeDocument(anyString(), anyString());
        assertThat(LLM.requestCount()).isZero();
    }

    @Test
    void passwordProtectedPdfIs400() {
        assertThat(upload("secret.pdf", "application/pdf", PdfFixtures.encrypted("pw")).status()).isEqualTo(400);
        verify(writer, never()).mergeDocument(anyString(), anyString());
    }

    @Test
    void pdfOverThePageLimitIs413() {
        val reply = upload("big.pdf", "application/pdf", PdfFixtures.textPages(4));

        assertThat(reply.status()).isEqualTo(413);
        verify(writer, never()).mergeDocument(anyString(), anyString());
    }

    @Test
    void neo4jBeingDownIs503() {
        when(writer.mergeDocument(anyString(), anyString())).thenThrow(new Neo4jUnavailableException("Neo4j is unavailable: down"));

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.json().get("message").asString()).contains("unavailable");
    }

    @Test
    void oneFailingChunkIsCountedAndTheOtherChunkStillWritten() {
        LLM.stubBodyContainingFails(1, 500, PASS_1, "Marker1");
        stubHappyExtraction();

        val reply = upload("two.pdf", "application/pdf", PdfFixtures.textPages(2));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("failedChunks").asInt()).isEqualTo(1);
        assertThat(reply.json().get("nodeCount").asInt()).isEqualTo(2);
    }
}
