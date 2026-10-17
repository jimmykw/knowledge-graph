package net.jimmykw.knowledgegraph.resolve.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import io.vavr.Tuple;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.resolve.cleanup.Clustering.Cluster;
import org.junit.jupiter.api.Test;

class ClusteringTest {

    private static final List<String> IDS = List.of("a", "b", "c", "d", "e", "f");

    @Test
    void exactEdgesJoinTransitivelyAndSingletonsAreDropped() {
        val clusters = Clustering.cluster(IDS, List.of(Tuple.of("a", "b"), Tuple.of("b", "c")), List.empty(), 2);

        assertThat(clusters).containsExactly(new Cluster(List.of("a", "b", "c"), false));
    }

    @Test
    void fuzzyEdgesMarkTheClusterAndStopAtTheSizeCap() {
        val fuzzy = List.of(Tuple.of("a", "b"), Tuple.of("b", "c"), Tuple.of("c", "d"), Tuple.of("e", "f"));

        val clusters = Clustering.cluster(IDS, List.empty(), fuzzy, 2);

        assertThat(clusters).containsExactly(new Cluster(List.of("a", "b"), true), new Cluster(List.of("c", "d"), true),
                new Cluster(List.of("e", "f"), true));
    }

    @Test
    void aFuzzyEdgeIntoAnExactClusterMarksItFuzzy() {
        val clusters = Clustering.cluster(IDS, List.of(Tuple.of("a", "b")), List.of(Tuple.of("c", "a")), 5);

        assertThat(clusters).containsExactly(new Cluster(List.of("a", "b", "c"), true));
    }

    @Test
    void edgesToUnknownIdsAreIgnored() {
        assertThat(Clustering.cluster(IDS, List.of(Tuple.of("a", "zzz")), List.of(Tuple.of("yyy", "b")), 5)).isEmpty();
    }

    @Test
    void edgesByKeyChainEveryIdSharingAKey() {
        val idsByKey = Clustering.invert(List.of(Tuple.of("a", List.of("apple", "apple")), Tuple.of("b", List.of("apple", "ibm")),
                Tuple.of("c", List.of("ibm"))));

        assertThat(idsByKey).isEqualTo(HashMap.of("apple", List.of("a", "b"), "ibm", List.of("b", "c")));
        assertThat(Clustering.edgesByKey(idsByKey)).containsExactlyInAnyOrder(Tuple.of("a", "b"), Tuple.of("b", "c"));
    }
}
