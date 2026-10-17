package net.jimmykw.knowledgegraph.resolve.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executors;

import io.vavr.Tuple;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter.ClusterMerge;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter.ClusterOutcome;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.GraphEntity;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanNode;
import org.junit.jupiter.api.Test;

class CleanupPlannerTest {

    private static GraphEntity node(String id, String name, String label, int degree) {
        return new GraphEntity(id, name, label, java.util.List.of("Entity", label), "", java.util.List.of(name), degree);
    }

    private static CleanupRecords.CleanupPlan plan(List<GraphEntity> entities, CleanupPlanner.ClusterDecider decider,
                                                   List<io.vavr.Tuple2<String, String>> fuzzy) {
        val store = mock(GraphCleanupStore.class);
        when(store.readEntities()).thenReturn(entities);
        when(store.fulltextPairs(any(), anyInt(), anyDouble())).thenReturn(fuzzy);
        when(store.neighbours(any())).thenReturn(HashMap.empty());
        val executor = Executors.newFixedThreadPool(2);
        return Try.of(() -> new CleanupPlanner(store, decider, executor, 5, 1.0, 20).plan()).andFinally(executor::shutdownNow).get();
    }

    @Test
    void sameLabelExactClustersAreMergedWithoutTheLlm() {
        val entities = List.of(node("a", "Apple", "Organization", 1), node("b", "Apple Inc.", "Organization", 4),
                node("c", "ibm", "Organization", 0), node("d", "IBM", "Organization", 0), node("e", "Lonely", "Person", 0));
        CleanupPlanner.ClusterDecider decider = (cluster, neighbours) -> {
            throw new AssertionError("no LLM expected");
        };

        val plan = plan(entities, decider, List.empty());

        assertThat(plan.entries()).extracting(PlanEntry::canonicalName, PlanEntry::reason, PlanEntry::survivorId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Apple Inc.", "match_key", "b"),
                        org.assertj.core.groups.Tuple.tuple("ibm", "exact", "c"));
        assertThat(plan.needsReview()).isEmpty();
    }

    @Test
    void mixedLabelOrFuzzyClustersGoToTheLlmAndUnsureMembersToReview() {
        val entities = List.of(node("a", "Watson", "Person", 3), node("b", "Watson", "Technology", 1), node("c", "IBM", "Organization", 9),
                node("d", "IBM Research", "Organization", 1));
        CleanupPlanner.ClusterDecider decider = (cluster, neighbours) -> Try.success(cluster.exists(member -> member.name().equals("IBM"))
                ? new ClusterOutcome(List.of(new ClusterMerge(cluster, Option.none())), List.empty())
                : new ClusterOutcome(List.empty(), cluster));

        val plan = plan(entities, decider, List.of(Tuple.of("d", "c")));

        assertThat(plan.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.reason()).isEqualTo("llm");
            assertThat(entry.survivorId()).isEqualTo("c");
            assertThat(entry.canonicalName()).isEqualTo("IBM");
        });
        assertThat(plan.needsReview()).singleElement().satisfies(item -> {
            assertThat(item.note()).isEqualTo("LLM not confident");
            assertThat(item.nodes()).extracting(PlanNode::elementId).containsExactly("a", "b");
        });
    }

    @Test
    void aFailedLlmCallSendsTheWholeClusterToReview() {
        val entities = List.of(node("a", "Watson", "Person", 3), node("b", "Watson", "Technology", 1));

        val plan = plan(entities, (cluster, neighbours) -> Try.failure(new IllegalStateException("timeout")), List.empty());

        assertThat(plan.entries()).isEmpty();
        assertThat(plan.needsReview()).singleElement().satisfies(item -> assertThat(item.note()).isEqualTo("LLM call failed"));
    }

    @Test
    void entryPicksTheBestConnectedSurvivorTheBestLabelAndUnionsAliases() {
        val entry = CleanupPlanner.entry(List.of(node("a", "Apple Watch", "Technology", 1), node("b", "Apple Watch", "Product", 5),
                node("c", "apple watch", "Concept", 5)), Option.some("Apple Watch"), "llm");

        assertThat(entry.survivorId()).isEqualTo("b");
        assertThat(entry.nodes()).extracting(PlanNode::elementId).containsExactly("b", "c", "a");
        assertThat(entry.label()).isEqualTo("Product");
        assertThat(entry.aliases()).containsExactly("Apple Watch", "apple watch");
        assertThat(entry.nodes().get(0).fingerprint()).isEqualTo("Apple Watch|Entity,Product|Apple Watch");
    }

    @Test
    void anLlmEntryWithoutCanonicalNameKeepsTheSurvivorName() {
        val entry = CleanupPlanner.entry(List.of(node("a", "IBM", "Organization", 9), node("b", "International Business Machines Corp.",
                "Organization", 1)), Option.none(), "llm");

        assertThat(entry.canonicalName()).isEqualTo("IBM");
    }
}
