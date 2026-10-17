package net.jimmykw.knowledgegraph.resolve.cleanup;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter.ClusterMerge;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter.ClusterOutcome;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.GraphEntity;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;
import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** {@code resolveGraph} plan and apply against a real Neo4j holding a graph written the pre-resolution way. */
@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class ResolveGraphIT extends Neo4jItSupport {

    /** Merges every member named IBM (any label); leaves a bare Watson for review. */
    private static final CleanupPlanner.ClusterDecider DECIDER = (cluster, neighbours) -> Try.success(new ClusterOutcome(
            List.of(cluster.filter(member -> member.name().equalsIgnoreCase("IBM"))).filter(members -> members.size() >= 2)
                    .map(members -> new ClusterMerge(members, Option.some("IBM"))),
            cluster.filter(member -> member.name().equals("Watson"))));

    @TempDir
    Path reports;

    private Neo4jGraphWriter writer;
    private GraphCleanupStore store;
    private ExecutorService executor;
    private ResolveGraph resolveGraph;

    @BeforeEach
    void legacyGraph() {
        writer = new Neo4jGraphWriter(driver);
        store = new GraphCleanupStore(driver, writer);
        executor = Executors.newFixedThreadPool(2);
        resolveGraph = new ResolveGraph(store, new CleanupPlanner(store, DECIDER, executor, 5, 100.0, 20), new CleanupReports(reports));

        writer.mergeDocument("h1", "first.pdf");
        writer.mergeDocument("h2", "second.pdf");
        val first = legacy("h1", entity("Apple", "Organization"), entity("Steve Jobs", "Person"), entity("IBM", "Organization"),
                entity("Watson", "Person"));
        val second = legacy("h2", entity("Apple Inc.", "Organization"), entity("IBM", "Company"), entity("Watson", "Technology"));
        writer.mergeRelationships(List.of(
                new ResolvedRelationship(first.get(1), first.get(0), "FOUNDED", "", "Steve Jobs", "Apple"),
                new ResolvedRelationship(first.get(1), second.get(0), "LED", "", "Steve Jobs", "Apple Inc."),
                new ResolvedRelationship(first.get(2), second.get(1), "ACQUIRED", "", "IBM", "IBM")));
    }

    @AfterEach
    void stopPool() {
        executor.shutdownNow();
    }

    private List<String> legacy(String hash, ExtractedEntity... entities) {
        val ids = writer.mergeEntities(List.of(entities), hash);
        return List.range(0, entities.length).map(idx -> ids.get(idx).getOrElse(""));
    }

    private static PlanEntry entryNamed(io.vavr.collection.Seq<PlanEntry> entries, String name) {
        return entries.find(entry -> entry.canonicalName().equals(name)).getOrElseThrow(() -> new AssertionError("no entry " + name));
    }

    @Test
    void planMigratesIdempotentlyAndMergesNothing() {
        val plan = resolveGraph.plan();

        assertThat(countNodes("MATCH (e:Entity) RETURN count(e)")).isEqualTo(7);
        assertThat(countNodes("MATCH (e:Entity) WHERE e.match_key IS NULL OR e.aliases IS NULL OR e.aliases_text IS NULL RETURN count(e)"))
                .isZero();
        assertThat(countNodes("MATCH (:Document)-[m:MENTIONS]->(:Entity) RETURN count(m)")).isEqualTo(7);
        assertThat(countNodes("MATCH (e:Entity {name: 'Apple Inc.'}) WHERE e.match_key = 'apple' RETURN count(e)")).isEqualTo(1);
        assertThat(store.migrate().labelled()).isZero();
        assertThat(store.migrate().mentions()).isZero();
        assertThat(countNodes("MATCH (:Document)-[m:MENTIONS]->(:Entity) RETURN count(m)")).isEqualTo(7);

        val entries = List.ofAll(plan.entries());
        assertThat(entries).hasSize(2);
        val apple = entryNamed(entries, "Apple Inc.");
        assertThat(apple.reason()).isEqualTo("match_key");
        assertThat(apple.aliases()).contains("Apple", "Apple Inc.");
        val ibm = entryNamed(entries, "IBM");
        assertThat(ibm.reason()).isEqualTo("llm");
        assertThat(ibm.label()).isEqualTo("Organization");
        assertThat(plan.needsReview()).singleElement().satisfies(item -> assertThat(item.nodes()).hasSize(2));

        assertThat(Files.exists(reports.resolve("plan.json"))).isTrue();
        assertThat(Try.of(() -> Files.readString(reports.resolve("plan.md"))).get())
                .contains("| Apple Inc. | Organization |", "## Needs review", "Watson [Person]", "LLM not confident");
        assertThat(countNodes("MATCH (e:Entity) RETURN count(e)")).isEqualTo(7);
    }

    @Test
    void applyMergesNodesRelationshipsAndMentionsAndRemovesSelfLoops() {
        resolveGraph.plan();

        val outcomes = resolveGraph.apply(true);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.ok()).isTrue());
        assertThat(countNodes("MATCH (e:Entity) RETURN count(e)")).isEqualTo(5);
        assertThat(countNodes("MATCH (e:Entity:Organization {name: 'Apple Inc.', label: 'Organization'}) "
                + "WHERE 'apple' IN e.aliases_norm AND 'apple inc.' IN e.aliases_norm AND e.match_key = 'apple' RETURN count(e)")).isEqualTo(1);
        assertThat(countNodes("MATCH (:Person {name: 'Steve Jobs'})-[r]->(:Organization {name: 'Apple Inc.'}) RETURN count(r)")).isEqualTo(2);
        assertThat(countNodes("MATCH (d:Document)-[:MENTIONS]->(:Organization {name: 'Apple Inc.'}) RETURN count(DISTINCT d)")).isEqualTo(2);
        assertThat(countNodes("MATCH (e:Entity {name: 'IBM'}) WHERE NOT e:Company AND e:Organization RETURN count(e)")).isEqualTo(1);
        assertThat(countNodes("MATCH (n)-[r]->(n) RETURN count(r)")).isZero();
        assertThat(countNodes("MATCH (e:Entity {name: 'Watson'}) RETURN count(e)")).isEqualTo(2);
        assertThat(Try.of(() -> Files.readString(reports.resolve("apply.md"))).get())
                .contains("2 merged, 0 failed", "self-loops removed: IBM -[ACQUIRED]-> IBM");
    }

    @Test
    void applyRefusesWithoutBackupConfirmationOrPlan() {
        assertThatThrownBy(() -> resolveGraph.apply(false)).hasMessageContaining("-Dresolve.backupConfirmed=true");
        assertThatThrownBy(() -> resolveGraph.apply(true)).hasMessageContaining("Run plan mode first");
    }

    @Test
    void applyFailsBeforeChangingAnythingWhenAPlannedNodeChanged() {
        resolveGraph.plan();
        Try.withResources(() -> driver.session()).of(session -> session.run(
                "MATCH (e:Entity {name: 'Apple'}) SET e.name = 'Apple Computer'").consume()).get();

        assertThatThrownBy(() -> resolveGraph.apply(true)).hasMessageContaining("changed: Apple");
        assertThat(countNodes("MATCH (e:Entity) RETURN count(e)")).isEqualTo(7);
    }

    @Test
    void applyFailsWhenAPlannedNodeIsGone() {
        resolveGraph.plan();
        Try.withResources(() -> driver.session()).of(session -> session.run(
                "MATCH (e:Entity {name: 'Apple Inc.'}) DETACH DELETE e").consume()).get();

        assertThatThrownBy(() -> resolveGraph.apply(true)).hasMessageContaining("missing: Apple Inc.");
    }

    @Test
    void readEntitiesLeavesMentionsOutOfTheDegree() {
        store.migrate();

        val jobs = store.readEntities().find(entity -> entity.name().equals("Steve Jobs")).map(GraphEntity::degree);

        assertThat(jobs).contains(2);
        assertThat(EntityNormalizer.matchKey("Apple Inc.")).isEqualTo("apple");
    }
}
