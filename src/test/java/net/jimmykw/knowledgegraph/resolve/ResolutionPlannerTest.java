package net.jimmykw.knowledgegraph.resolve;

import static net.jimmykw.knowledgegraph.resolve.ResolutionFixtures.exact;
import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewNode;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NodeUpdate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;
import org.junit.jupiter.api.Test;

class ResolutionPlannerTest {

    @Test
    void newGroupsBecomeNodesWithTheFullestNameTheBestLabelAndEveryNameAsAlias() {
        val entities = List.of(entity("IBM", "Company"), entity("IBM Corp.", "Organization"), entity("Lonely", "Person"));
        val resolved = new Resolved(List.empty(), List.of(new NewGroup(List.of(0, 1), Option.none()), new NewGroup(List.of(2), Option.none())),
                HashSet.empty());

        val planned = ResolutionPlanner.plan(entities, resolved);

        assertThat(planned.plan().newNodes()).containsExactly(
                new NewNode(0, "IBM Corp.", "Organization", "IBM description", List.of("IBM Corp.", "IBM")),
                new NewNode(1, "Lonely", "Person", "Lonely description", List.of("Lonely")));
        assertThat(planned.plan().mentions()).containsExactly(
                new Mention(0, "IBM", "IBM description", Either.left(0)),
                new Mention(1, "IBM Corp.", "IBM Corp. description", Either.left(0)),
                new Mention(2, "Lonely", "Lonely description", Either.left(1)));
        assertThat(planned.created()).isEqualTo(2);
        assertThat(planned.merged()).isZero();
        assertThat(planned.countsByLabel().toJavaMap()).containsEntry("Organization", 1).containsEntry("Person", 1);
    }

    @Test
    void aCanonicalNameFromTheLlmNamesTheNewNode() {
        val resolved = new Resolved(List.empty(), List.of(new NewGroup(List.of(0, 1), Option.some("IBM"))), HashSet.empty());

        val planned = ResolutionPlanner.plan(List.of(entity("IBM", "Organization"), entity("IBM Corp.", "Organization")), resolved);

        assertThat(planned.plan().newNodes()).extracting(NewNode::name).containsExactly("IBM");
    }

    @Test
    void mergesIntoOneNodeBecomeOneUpdateWithTheBestLabelAndAllNames() {
        val watson = exact(0, "n-1", "Watson", "Technology");
        val entities = List.of(entity("Watson Jr.", "Person"), entity("Watson", "Product"));
        val resolved = new Resolved(List.of(
                new MergeExisting(0, watson, Option.some("Thomas J. Watson Jr."), "llm"),
                new MergeExisting(1, watson, Option.none(), "exact")), List.empty(), HashSet.empty());

        val planned = ResolutionPlanner.plan(entities, resolved);

        assertThat(planned.plan().updates()).containsExactly(
                new NodeUpdate("n-1", "Thomas J. Watson Jr.", "Technology", "Person", List.of("Thomas J. Watson Jr.", "Watson Jr.", "Watson")));
        assertThat(planned.plan().mentions()).extracting(Mention::target).containsExactly(Either.right("n-1"), Either.right("n-1"));
        assertThat(planned.merged()).isEqualTo(2);
        assertThat(planned.created()).isZero();
        assertThat(planned.countsByLabel().toJavaMap()).containsExactly(java.util.Map.entry("Person", 2));
    }

    @Test
    void aMergeWithoutCanonicalNameKeepsTheNodeNameAndLabelWhenNotBetter() {
        val apple = exact(0, "n-1", "Apple", "Organization");

        val planned = ResolutionPlanner.plan(List.of(entity("Apple Inc.", "Company")),
                new Resolved(List.of(new MergeExisting(0, apple, Option.none(), "match_key")), List.empty(), HashSet.empty()));

        assertThat(planned.plan().updates()).containsExactly(new NodeUpdate("n-1", "Apple", "Organization", "Organization",
                List.of("Apple", "Apple Inc.")));
    }

    @Test
    void fullestNameKeepsTheFirstOnATie() {
        assertThat(ResolutionPlanner.fullestName(List.of("ab", "cd", "a"))).isEqualTo("ab");
    }
}
