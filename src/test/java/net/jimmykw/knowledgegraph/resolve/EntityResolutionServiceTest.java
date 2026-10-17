package net.jimmykw.knowledgegraph.resolve;

import static net.jimmykw.knowledgegraph.resolve.ResolutionFixtures.exact;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService.ExtractedEntities;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityProbe;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.GroupQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Question;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionPlan;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EntityResolutionServiceTest {

    private Neo4jGraphWriter writer;
    private ResolutionPrompter prompter;
    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        writer = mock(Neo4jGraphWriter.class);
        prompter = mock(ResolutionPrompter.class);
        when(writer.findCandidates(any(), anyInt(), anyDouble())).thenReturn(HashMap.empty());
        when(writer.applyResolution(any(), anyString())).thenAnswer(invocation -> ids(invocation.getArgument(0)));
        service = new EntityResolutionService(writer, prompter,
                new AppProperties(0, 0, null, null, null, null, new AppProperties.Resolution(true, 7, 2.5, 2)));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** New nodes get {@code new-<key>}; existing ones keep their elementId. */
    private static Map<Integer, String> ids(ResolutionPlan plan) {
        return plan.mentions().toMap(Mention::idx, mention -> mention.target().fold(key -> "new-" + key, id -> id));
    }

    private ResolutionPlan writtenPlan() {
        val captor = ArgumentCaptor.forClass(ResolutionPlan.class);
        verify(writer).applyResolution(captor.capture(), eq("hash"));
        return captor.getValue();
    }

    private static ExtractedEntities extracted(ExtractedEntity... entities) {
        return new ExtractedEntities(List.of(entities), 1);
    }

    private void candidates(Map<Integer, List<Candidate>> byIdx) {
        when(writer.findCandidates(any(), anyInt(), anyDouble())).thenReturn(byIdx);
    }

    @Test
    void noEntitiesTouchesNothing() {
        val result = service.resolve(new ExtractedEntities(List.empty(), 2), "hash");

        assertThat(result.nodeCount()).isZero();
        assertThat(result.failedChunks()).isEqualTo(2);
        verifyNoInteractions(writer, prompter);
    }

    @Test
    void probesCarryNormalizedNamesMatchKeysAndEscapedQueriesAndTheConfiguredLimits() {
        service.resolve(extracted(entity("Apple Inc.", "Organization"), entity("C++", "Technology")), "hash");

        val captor = ArgumentCaptor.<List<EntityProbe>>captor();
        verify(writer).findCandidates(captor.capture(), eq(7), eq(2.5));
        assertThat(captor.getValue()).containsExactly(
                new EntityProbe(0, "Apple Inc.", "apple inc.", "apple", "Organization", Option.some("apple inc.")),
                new EntityProbe(1, "C++", "c++", "c", "Technology", Option.some("c\\+\\+")));
    }

    @Test
    void newEntitiesAreCreatedWithMentionsAndIndexedByTheirWrittenNames() {
        val result = service.resolve(extracted(entity("IBM", "Organization"), entity("Watson", "Person")), "hash");

        assertThat(writtenPlan().newNodes()).hasSize(2);
        assertThat(result.entitiesCreated()).isEqualTo(2);
        assertThat(result.entitiesMerged()).isZero();
        assertThat(result.nodeCount()).isEqualTo(2);
        assertThat(result.failedChunks()).isEqualTo(1);
        assertThat(result.canonicalIndex().lookup("Watson", "Person")).contains("new-1");
        verify(writer).recordSchema(HashSet.of("Organization", "Person"), HashSet.of("MENTIONS"));
        verifyNoInteractions(prompter);
    }

    @Test
    void anExactSameLabelMatchMergesWithoutTheLlm() {
        val apple = exact(0, "4:db:1", "Apple", "Organization");
        candidates(HashMap.of(0, List.of(apple)));

        val result = service.resolve(extracted(entity("Apple Inc.", "Organization")), "hash");

        assertThat(writtenPlan().updates()).singleElement().satisfies(update -> {
            assertThat(update.elementId()).isEqualTo("4:db:1");
            assertThat(update.name()).isEqualTo("Apple");
            assertThat(update.aliases()).containsExactly("Apple", "Apple Inc.");
        });
        assertThat(result.entitiesMerged()).isEqualTo(1);
        assertThat(result.entitiesCreated()).isZero();
        assertThat(result.canonicalIndex().lookup("Apple Inc.", "Organization")).contains("4:db:1");
        verifyNoInteractions(prompter);
    }

    @Test
    void ambiguousEntitiesGoToTheLlmAndItsDecisionsAreWritten() {
        val watson = exact(0, "4:db:2", "Watson", "Technology");
        candidates(HashMap.of(0, List.of(watson)));
        when(prompter.decide(any(), any())).thenReturn(Try.success(
                new Resolved(List.of(new MergeExisting(0, watson, Option.none(), "llm")), List.empty(), HashSet.empty())));

        val result = service.resolve(extracted(entity("Watson", "Product")), "hash");

        verify(prompter).decide(List.of(new EntityQuestion(0, List.of(watson))), List.of(entity("Watson", "Product")));
        assertThat(writtenPlan().updates()).singleElement().satisfies(update -> assertThat(update.label()).isEqualTo("Product"));
        assertThat(result.entitiesMerged()).isEqualTo(1);
        assertThat(result.countsByLabel().toJavaMap()).containsEntry("Product", 1);
    }

    @Test
    void aFailedBatchCreatesItsEntitiesAsNewAndCountsThemUnresolved() {
        candidates(HashMap.of(0, List.of(exact(0, "4:db:2", "Watson", "Technology"))));
        when(prompter.decide(any(), any())).thenReturn(Try.failure(new IllegalStateException("timeout")));

        val result = service.resolve(extracted(entity("Watson", "Person"), entity("IBM", "Organization")), "hash");

        assertThat(result.unresolvedEntities()).isEqualTo(1);
        assertThat(result.entitiesCreated()).isEqualTo(2);
        assertThat(writtenPlan().newNodes()).hasSize(2);
    }

    @Test
    void aBatchThatThrowsIsAlsoUnresolved() {
        candidates(HashMap.of(0, List.of(exact(0, "4:db:2", "Watson", "Technology"))));
        when(prompter.decide(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.resolve(extracted(entity("Watson", "Person")), "hash").unresolvedEntities()).isEqualTo(1);
    }

    @Test
    void questionsArePackedIntoBatchesOfAtMostBatchSizeEntities() {
        val one = new EntityQuestion(0, List.empty());
        val two = new EntityQuestion(1, List.empty());
        val group = new GroupQuestion(List.of(2, 3, 4));
        val four = new EntityQuestion(5, List.empty());

        val batches = EntityResolutionService.batches(List.<Question>of(one, two, group, four), 2);

        assertThat(batches).containsExactly(List.of(one, two), List.of(group), List.of(four));
    }

    @Test
    void theLlmIsCalledOncePerBatch() {
        candidates(HashMap.of(0, List.of(exact(0, "a", "A", "X")), 1, List.of(exact(1, "b", "B", "X")), 2, List.of(exact(2, "c", "C", "X"))));
        when(prompter.decide(any(), any())).thenReturn(Try.success(Resolved.empty()));

        service.resolve(extracted(entity("A", "Person"), entity("B", "Person"), entity("C", "Person")), "hash");

        verify(prompter, times(2)).decide(any(), any());
    }

    @Test
    void entitiesABatchResultLeavesOutAreCreatedAsNewAndCountedUnresolved() {
        candidates(HashMap.of(0, List.of(exact(0, "a", "A", "X"))));
        when(prompter.decide(any(), any())).thenReturn(Try.success(Resolved.empty()));

        val result = service.resolve(extracted(entity("A", "Person")), "hash");

        assertThat(result.unresolvedEntities()).isEqualTo(1);
        assertThat(result.canonicalIndex().lookup("A", "Person")).contains("new-0");
    }

    @Test
    void theSchemaIsEnsuredOnlyOnce() {
        service.resolve(extracted(entity("IBM", "Organization")), "hash");
        service.resolve(extracted(entity("IBM", "Organization")), "hash");

        verify(writer, times(1)).ensureSchema();
    }

    @Test
    void concurrentUploadsResolveOneAtATime() {
        val inFlight = new AtomicInteger();
        val maxInFlight = new AtomicInteger();
        when(writer.findCandidates(any(), anyInt(), anyDouble())).thenAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Try.run(() -> Thread.sleep(100)).get();
            inFlight.decrementAndGet();
            return HashMap.empty();
        });

        val first = CompletableFuture.runAsync(() -> service.resolve(extracted(entity("A", "Person")), "hash"));
        val second = CompletableFuture.runAsync(() -> service.resolve(extracted(entity("B", "Person")), "hash"));
        CompletableFuture.allOf(first, second).join();

        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void theLockIsReleasedWhenResolutionFails() {
        when(writer.findCandidates(any(), anyInt(), anyDouble())).thenThrow(new IllegalStateException("neo4j down"));

        assertThat(Try.of(() -> service.resolve(extracted(entity("A", "Person")), "hash")).isFailure()).isTrue();

        org.mockito.Mockito.doReturn(HashMap.empty()).when(writer).findCandidates(any(), anyInt(), anyDouble());
        assertThat(CompletableFuture.supplyAsync(() -> service.resolve(extracted(entity("A", "Person")), "hash")).join().nodeCount())
                .isEqualTo(1);
        verify(writer, never()).mergeEntities(any(), anyString());
    }
}
