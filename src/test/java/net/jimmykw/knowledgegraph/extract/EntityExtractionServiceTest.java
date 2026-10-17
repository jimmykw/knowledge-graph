package net.jimmykw.knowledgegraph.extract;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entities;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

class EntityExtractionServiceTest {

    private ExtractionPrompter prompter;
    private Neo4jGraphWriter writer;
    private EntityExtractionService service;

    @BeforeEach
    void setUp() {
        prompter = mock(ExtractionPrompter.class);
        when(prompter.getFormat()).thenReturn("FORMAT");
        writer = mock(Neo4jGraphWriter.class);
        when(writer.mergeEntities(any(), anyString())).thenAnswer(invocation -> {
            val unique = invocation.<List<ExtractedEntity>>getArgument(0);
            return unique.zipWithIndex().foldLeft(HashMap.<Integer, Long>empty(), (acc, pair) -> acc.put(pair._2, 100L + pair._2));
        });
        service = new EntityExtractionService(prompter, writer, new AppProperties(0, 0, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** Answers by chunk text, because chunks run in parallel and arrive in any order. */
    private void chunkResponds(String chunkText, Try<ExtractionResult> result) {
        val matchesChunk = org.mockito.ArgumentMatchers.argThat((Prompt prompt) -> prompt != null && prompt.getContents().contains(chunkText));
        when(prompter.callWithRetry(matchesChunk)).thenReturn(result);
    }

    private static List<Document> chunks(String... texts) {
        return List.of(texts).map(Document::new);
    }

    @Test
    void emptyChunksYieldEmptyResultAndNoWrites() {
        val result = service.extract(List.empty(), "h");

        assertThat(result.nodeCount()).isZero();
        assertThat(result.failedChunks()).isZero();
        assertThat(result.canonicalIndex().size()).isZero();
        verifyNoInteractions(prompter, writer);
    }

    @Test
    void duplicatesByNormalizedNameAndLabelAreMergedAcrossChunks() {
        chunkResponds("chunk-a", Try.success(entities(entity("IBM", "Organization"), entity("Watson", "Person"))));
        chunkResponds("chunk-b", Try.success(entities(entity("  ibm ", "Organization"), entity("IBM", "Company"))));

        val result = service.extract(chunks("chunk-a", "chunk-b"), "h");

        assertThat(result.nodeCount()).isEqualTo(3);
        assertThat(result.countsByLabel().toJavaMap()).containsEntry("Organization", 1).containsEntry("Person", 1).containsEntry("Company", 1);
        assertThat(result.canonicalIndex().size()).isEqualTo(3);
    }

    @Test
    void blankOrNullNameAndLabelAreRejected() {
        chunkResponds("chunk-a", Try.success(entities(entity("", "Person"), entity("   ", "Person"), entity("IBM", ""), entity("X", null),
                entity(null, "Person"), entity("Good", "Person"))));

        val result = service.extract(chunks("chunk-a"), "h");

        assertThat(result.nodeCount()).isEqualTo(1);
        assertThat(result.canonicalIndex().entities()).extracting(ExtractedEntity::name).containsExactly("Good");
    }

    @Test
    void labelsThatCouldBreakOutOfApocBackticksAreRejected() {
        chunkResponds("chunk-a", Try.success(entities(entity("Evil", "X`) DETACH DELETE n //"), entity("Good", "Person"))));

        val result = service.extract(chunks("chunk-a"), "h");

        assertThat(result.canonicalIndex().entities()).extracting(ExtractedEntity::name).containsExactly("Good");
    }

    @Test
    void failedChunkIsCountedAndTheOtherChunksStillProcessed() {
        chunkResponds("chunk-a", Try.failure(new IllegalStateException("llm down")));
        chunkResponds("chunk-b", Try.success(entities(entity("IBM", "Organization"))));

        val result = service.extract(chunks("chunk-a", "chunk-b"), "h");

        assertThat(result.failedChunks()).isEqualTo(1);
        assertThat(result.nodeCount()).isEqualTo(1);
    }

    @Test
    void schemaIsRecordedOnceWithLabelsOnly() {
        chunkResponds("chunk-a", Try.success(entities(entity("IBM", "Organization"), entity("Watson", "Person"))));

        service.extract(chunks("chunk-a"), "h");

        verify(writer).recordSchema(HashSet.of("Organization", "Person"), HashSet.empty());
    }

    @Test
    void canonicalIndexUsesTheIdsReturnedByTheWriter() {
        chunkResponds("chunk-a", Try.success(entities(entity("IBM", "Organization"), entity("Watson", "Person"))));

        val result = service.extract(chunks("chunk-a"), "hash-1");

        assertThat(result.canonicalIndex().lookup("IBM", "Organization")).contains(100L);
        assertThat(result.canonicalIndex().lookup("Watson", "Person")).contains(101L);
        val captured = ArgumentCaptor.<List<ExtractedEntity>>captor();
        verify(writer).mergeEntities(captured.capture(), eq("hash-1"));
        assertThat(captured.getValue()).extracting(ExtractedEntity::name).containsExactly("IBM", "Watson");
    }

    @Test
    void missingIdFromTheWriterIsAnIllegalState() {
        chunkResponds("chunk-a", Try.success(entities(entity("IBM", "Organization"), entity("Watson", "Person"))));
        org.mockito.Mockito.doReturn(HashMap.of(0, 100L)).when(writer).mergeEntities(any(), anyString());

        assertThatThrownBy(() -> service.extract(chunks("chunk-a"), "h"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Watson");
    }

    @Test
    void allChunksFailingWritesAnEmptyBatch() {
        chunkResponds("chunk-a", Try.failure(new IllegalStateException("x")));

        val result = service.extract(chunks("chunk-a"), "h");

        assertThat(result.failedChunks()).isEqualTo(1);
        assertThat(result.nodeCount()).isZero();
        verify(writer).mergeEntities(List.empty(), "h");
        verify(writer).recordSchema(HashSet.empty(), HashSet.empty());
    }

    @Test
    void shutdownTerminatesThePool() {
        service.shutdown();

        assertThatThrownBy(() -> service.extract(chunks("chunk-a"), "h")).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }
}
