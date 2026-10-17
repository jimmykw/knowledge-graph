package net.jimmykw.knowledgegraph.flow;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entities;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.json;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.relationship;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.relationships;
import static net.jimmykw.knowledgegraph.testsupport.FakeLlmServer.completion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.testsupport.PdfFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UploadFlowTest extends AppFlowTestBase {

    private static final String PASS_1 = "Extract the entities";
    private static final String PASS_2 = "Canonical entities already discovered";

    @BeforeEach
    void stubWriter() {
        when(writer.findDocumentId(anyString())).thenReturn(Option.none());
        when(writer.mergeDocument(anyString(), anyString())).thenReturn(5L);
        when(writer.mergeEntities(any(), anyString())).thenAnswer(invocation -> invocation.<List<ExtractedEntity>>getArgument(0)
                .zipWithIndex().foldLeft(HashMap.<Integer, Long>empty(), (acc, pair) -> acc.put(pair._2, 100L + pair._2)));
        when(writer.mergeRelationships(any())).thenAnswer(invocation -> invocation.<List<ResolvedRelationship>>getArgument(0)
                .zipWithIndex().foldLeft(HashMap.<Integer, Long>empty(), (acc, pair) -> acc.put(pair._2, 500L + pair._2)));
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

        val entityCaptor = ArgumentCaptor.<List<ExtractedEntity>>captor();
        verify(writer).mergeEntities(entityCaptor.capture(), anyString());
        assertThat(entityCaptor.getValue()).extracting(ExtractedEntity::name).containsExactly("IBM", "Thomas Watson");
        val relCaptor = ArgumentCaptor.<List<ResolvedRelationship>>captor();
        verify(writer).mergeRelationships(relCaptor.capture());
        assertThat(relCaptor.getValue()).singleElement().satisfies(rel -> {
            assertThat(rel.type()).isEqualTo("LEADS");
            assertThat(rel.sourceId()).isEqualTo(101L);
            assertThat(rel.targetId()).isEqualTo(100L);
        });
    }

    @Test
    void duplicateUploadMakesNoLlmCallsAndNoWrites() {
        when(writer.findDocumentId(anyString())).thenReturn(Option.some(9L));

        val reply = upload("history.pdf", "application/pdf", PdfFixtures.textPages(1));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("status").asString()).isEqualTo("SKIPPED_DUPLICATE");
        assertThat(reply.json().get("nodeCount").asInt()).isZero();
        assertThat(LLM.requestCount()).isZero();
        verify(writer, never()).mergeEntities(any(), anyString());
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
