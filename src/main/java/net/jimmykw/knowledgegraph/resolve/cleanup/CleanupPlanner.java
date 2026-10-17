package net.jimmykw.knowledgegraph.resolve.cleanup;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.graph.FulltextQueries;
import net.jimmykw.knowledgegraph.graph.PreferredLabels;
import net.jimmykw.knowledgegraph.resolve.ResolutionPlanner;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter.ClusterOutcome;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.CleanupPlan;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.GraphEntity;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanNode;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ReviewItem;
import net.jimmykw.knowledgegraph.resolve.cleanup.Clustering.Cluster;

import io.vavr.Function1;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Plans the one-time duplicate cleanup. Candidate clusters come from the upload signals graph-wide: shared name / alias / match
 * key, plus full-text neighbours. A cluster of one label joined only by exact keys is merged without the LLM; every other
 * cluster goes to the LLM, and what it is not sure about is left alone and listed for review.
 */
@Slf4j
@RequiredArgsConstructor
public class CleanupPlanner {

    /** Decides one mixed or fuzzy cluster. */
    public interface ClusterDecider {
        Try<ClusterOutcome> decide(List<GraphEntity> cluster, Map<String, List<String>> neighbours);
    }

    private final GraphCleanupStore store;
    private final ClusterDecider decider;
    private final ExecutorService executor;
    private final int candidateLimit;
    private final double minFulltextScore;
    private final int maxClusterSize;

    public CleanupPlan plan() {
        val entities = store.readEntities();
        val byId = entities.toMap(GraphEntity::elementId, entity -> entity);
        val exactEdges = Clustering.edgesByKey(Clustering.invert(entities.map(entity -> Tuple.of(entity.elementId(), keys(entity)))));
        val fuzzyEdges = store.fulltextPairs(entities.flatMap(entity -> FulltextQueries.forName(entity.name())
                .map(query -> Tuple.of(entity.elementId(), query))), candidateLimit, minFulltextScore);
        val clusters = Clustering.cluster(entities.map(GraphEntity::elementId), exactEdges, fuzzyEdges, maxClusterSize)
                .map(cluster -> Tuple.of(cluster, cluster.ids().flatMap(byId::get)));
        val partition = clusters.partition(pair -> isAutomatic(pair._1, pair._2));
        log.info("Planning: {} entities, {} candidate cluster(s): {} automatic, {} for the LLM", entities.size(), clusters.size(),
                partition._1.size(), partition._2.size());

        val automatic = partition._1.map(pair -> entry(pair._2, Option.none(), automaticReason(pair._2)));
        val decided = decide(partition._2.map(Tuple2::_2));
        return new CleanupPlan(Instant.now().toString(), minFulltextScore, automatic.appendAll(decided._1).toJavaList(),
                decided._2.toJavaList());
    }

    /** Name, every alias and the stored match key, each reduced to its match key. */
    private static List<String> keys(GraphEntity entity) {
        return List.ofAll(entity.aliases()).prepend(entity.name()).map(EntityNormalizer::matchKey).filter(key -> !key.isEmpty());
    }

    private static boolean isAutomatic(Cluster cluster, List<GraphEntity> members) {
        return !cluster.fuzzy() && members.map(GraphEntity::label).distinct().size() == 1;
    }

    private static String automaticReason(List<GraphEntity> members) {
        return members.map(member -> EntityNormalizer.normalize(member.name())).distinct().size() == 1 ? "exact" : "match_key";
    }

    private Tuple2<List<PlanEntry>, List<ReviewItem>> decide(List<List<GraphEntity>> clusters) {
        if (clusters.isEmpty()) {
            return Tuple.of(List.empty(), List.empty());
        }
        val neighbours = store.neighbours(clusters.flatMap(cluster -> cluster.map(GraphEntity::elementId)));
        val futures = clusters.map(cluster -> CompletableFuture.supplyAsync(() -> decideCluster(cluster, neighbours), executor));
        CompletableFuture.allOf(futures.toJavaArray(CompletableFuture[]::new)).join();
        val results = futures.map(CompletableFuture::join);
        return Tuple.of(results.flatMap(Tuple2::_1), results.flatMap(Tuple2::_2));
    }

    private Tuple2<List<PlanEntry>, List<ReviewItem>> decideCluster(List<GraphEntity> cluster, Map<String, List<String>> neighbours) {
        return Try.of(() -> decider.decide(cluster, neighbours))
                .flatMap(Function1.identity())
                .map(outcome -> Tuple.of(outcome.merges().map(merge -> entry(merge.members(), merge.canonicalName(), "llm")),
                        outcome.unsure().isEmpty()
                                ? List.<ReviewItem>empty()
                                : List.of(new ReviewItem(outcome.unsure().map(CleanupPlanner::planNode).toJavaList(), "LLM not confident"))))
                .onFailure(failure -> log.warn("Planning: LLM failed for cluster '{}' — {}", cluster.head().name(), failure.toString()))
                .getOrElse(() -> Tuple.of(List.empty(),
                        List.of(new ReviewItem(cluster.map(CleanupPlanner::planNode).toJavaList(), "LLM call failed"))));
    }

    /**
     * The survivor is the best-connected member (then the better label, then the first). The best-ranked label wins, the name
     * is the canonical one (or the fullest for automatic merges, the survivor's when the LLM gave none), aliases are the union.
     */
    static PlanEntry entry(List<GraphEntity> members, Option<String> canonicalName, String reason) {
        val ordered = members.sortBy(member -> Tuple.of(-member.degree(), PreferredLabels.rank(member.label())));
        val survivor = ordered.head();
        val label = ordered.tail().map(GraphEntity::label).foldLeft(survivor.label(), PreferredLabels::better);
        val name = canonicalName.getOrElse(() -> reason.equals("llm") ? survivor.name() : ResolutionPlanner.fullestName(members.map(GraphEntity::name)));
        val aliases = ordered.flatMap(member -> List.ofAll(member.aliases()).prepend(member.name())).prepend(name).distinct();
        return new PlanEntry(survivor.elementId(), ordered.map(CleanupPlanner::planNode).toJavaList(), name, label, aliases.toJavaList(), reason);
    }

    static PlanNode planNode(GraphEntity entity) {
        return new PlanNode(entity.elementId(), entity.name(), entity.label(),
                GraphCleanupStore.fingerprint(entity.name(), entity.labels(), entity.aliases()));
    }
}
