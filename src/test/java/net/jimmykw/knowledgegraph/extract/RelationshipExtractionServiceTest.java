package net.jimmykw.knowledgegraph.extract;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.relationship;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.relationships;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.graph.CanonicalIndex;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

class RelationshipExtractionServiceTest {

    private static final CanonicalIndex INDEX = CanonicalIndex.empty()
            .put(entity("IBM", "Organization"), "id-1")
            .put(entity("Watson", "Person"), "id-2")
            .put(entity("Mainframe", "Technology"), "id-3");

    private ExtractionPrompter prompter;
    private Neo4jGraphWriter writer;
    private RelationshipExtractionService service;

    @BeforeEach
    void setUp() {
        prompter = mock(ExtractionPrompter.class);
        when(prompter.getFormat()).thenReturn("FORMAT");
        writer = mock(Neo4jGraphWriter.class);
        when(writer.mergeRelationships(any())).thenAnswer(invocation -> {
            val unique = invocation.<List<ResolvedRelationship>>getArgument(0);
            return unique.zipWithIndex().foldLeft(HashMap.<Integer, String>empty(), (acc, pair) -> acc.put(pair._2, "rel-" + pair._2));
        });
        service = new RelationshipExtractionService(prompter, writer, new AppProperties(0, 0, null, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private void chunkResponds(String chunkText, Try<ExtractionResult> result) {
        val matchesChunk = org.mockito.ArgumentMatchers.argThat((Prompt prompt) -> prompt != null && prompt.getContents().contains(chunkText));
        when(prompter.callWithRetry(matchesChunk)).thenReturn(result);
    }

    private static List<Document> chunks(String... texts) {
        return List.of(texts).map(Document::new);
    }

    private List<ResolvedRelationship> written() {
        val captor = ArgumentCaptor.<List<ResolvedRelationship>>captor();
        verify(writer).mergeRelationships(captor.capture());
        return captor.getValue();
    }

    @Test
    void skippedWhenThereAreNoChunks() {
        val result = service.extract(List.empty(), INDEX, "h");

        assertThat(result.relationshipCount()).isZero();
        verifyNoInteractions(prompter, writer);
    }

    @Test
    void skippedWhenTheCanonicalIndexIsEmpty() {
        val result = service.extract(chunks("chunk-a"), CanonicalIndex.empty(), "h");

        assertThat(result.relationshipCount()).isZero();
        assertThat(result.failedChunks()).isZero();
        verifyNoInteractions(prompter, writer);
    }

    @Test
    void endpointsResolveByExactNameAndLabel() {
        chunkResponds("chunk-a", Try.success(relationships(relationship("Watson", "Person", "WORKS_FOR", "ibm", "Organization"))));

        val result = service.extract(chunks("chunk-a"), INDEX, "h");

        assertThat(result.relationshipCount()).isEqualTo(1);
        assertThat(written()).containsExactly(new ResolvedRelationship("id-2", "id-1", "WORKS_FOR", "WORKS_FOR description", "Watson", "ibm"));
    }

    @Test
    void endpointsResolveByNameWhenTheLabelDiffers() {
        chunkResponds("chunk-a", Try.success(relationships(relationship("Watson", "Human", "WORKS_FOR", "IBM", "Company"))));

        val result = service.extract(chunks("chunk-a"), INDEX, "h");

        assertThat(result.relationshipCount()).isEqualTo(1);
        assertThat(written().head().sourceId()).isEqualTo("id-2");
        assertThat(written().head().targetId()).isEqualTo("id-1");
    }

    @Test
    void unresolvedEndpointIsDroppedWithoutFailing() {
        chunkResponds("chunk-a", Try.success(relationships(
                relationship("Nobody", "Person", "WORKS_FOR", "IBM", "Organization"),
                relationship("Watson", "Person", "WORKS_FOR", "Nowhere", "Organization"),
                relationship("Watson", "Person", "BUILT", "Mainframe", "Technology"))));

        val result = service.extract(chunks("chunk-a"), INDEX, "h");

        assertThat(result.relationshipCount()).isEqualTo(1);
        assertThat(written()).extracting(ResolvedRelationship::type).containsExactly("BUILT");
    }

    @Test
    void duplicatesBySourceTargetAndTypeAreCollapsedAcrossChunks() {
        val duplicate = relationship("Watson", "Person", "WORKS_FOR", "IBM", "Organization");
        chunkResponds("chunk-a", Try.success(relationships(duplicate)));
        chunkResponds("chunk-b", Try.success(relationships(duplicate, relationship("Watson", "Person", "FOUNDED", "IBM", "Organization"))));

        val result = service.extract(chunks("chunk-a", "chunk-b"), INDEX, "h");

        assertThat(result.relationshipCount()).isEqualTo(2);
        assertThat(result.countsByType().toJavaMap()).containsEntry("WORKS_FOR", 1).containsEntry("FOUNDED", 1);
    }

    @Test
    void relationshipsWithBlankFieldsAreRejected() {
        chunkResponds("chunk-a", Try.success(relationships(
                relationship("", "Person", "X", "IBM", "Organization"),
                relationship("Watson", " ", "X", "IBM", "Organization"),
                relationship("Watson", "Person", "X", "", "Organization"),
                relationship("Watson", "Person", "X", "IBM", null),
                relationship("Watson", "Person", " ", "IBM", "Organization"),
                relationship("Watson", "Person", "USES", "Mainframe", "Technology"))));

        val result = service.extract(chunks("chunk-a"), INDEX, "h");

        assertThat(result.relationshipCount()).isEqualTo(1);
        assertThat(result.countsByType().toJavaMap()).containsOnlyKeys("USES");
    }

    @Test
    void relationshipTypesThatCouldBreakOutOfApocBackticksAreRejected() {
        chunkResponds("chunk-a", Try.success(relationships(
                relationship("Watson", "Person", "R`]->(b) DETACH DELETE a //", "IBM", "Organization"),
                relationship("Watson", "Person", "USES", "Mainframe", "Technology"))));

        val result = service.extract(chunks("chunk-a"), INDEX, "h");

        assertThat(result.countsByType().toJavaMap()).containsOnlyKeys("USES");
    }

    @Test
    void schemaIsRecordedWithRelationshipTypesOnly() {
        chunkResponds("chunk-a", Try.success(relationships(relationship("Watson", "Person", "USES", "Mainframe", "Technology"))));

        service.extract(chunks("chunk-a"), INDEX, "h");

        verify(writer).recordSchema(HashSet.empty(), HashSet.of("USES"));
    }

    @Test
    void failedChunksAreCountedAndTheOthersStillProcessed() {
        chunkResponds("chunk-a", Try.failure(new IllegalStateException("llm down")));
        chunkResponds("chunk-b", Try.success(relationships(relationship("Watson", "Person", "USES", "Mainframe", "Technology"))));

        val result = service.extract(chunks("chunk-a", "chunk-b"), INDEX, "h");

        assertThat(result.failedChunks()).isEqualTo(1);
        assertThat(result.relationshipCount()).isEqualTo(1);
    }

    @Test
    void canonicalEntitiesAreInjectedIntoThePrompt() {
        chunkResponds("chunk-a", Try.success(relationships()));

        service.extract(chunks("chunk-a"), INDEX, "h");

        val captor = ArgumentCaptor.forClass(Prompt.class);
        verify(prompter).callWithRetry(captor.capture());
        assertThat(captor.getValue().getContents()).contains("- IBM [Organization]", "- Watson [Person]", "chunk-a");
    }
}
