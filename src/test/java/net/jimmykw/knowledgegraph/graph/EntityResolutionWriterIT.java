package net.jimmykw.knowledgegraph.graph;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService.ExtractedEntities;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.resolve.EntityResolutionService;
import net.jimmykw.knowledgegraph.resolve.ResolutionPrompter;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityProbe;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewNode;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NodeUpdate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionPlan;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;
import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The resolution schema, candidate query and write against a real Neo4j + APOC. */
@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class EntityResolutionWriterIT extends Neo4jItSupport {

    private Neo4jGraphWriter writer;

    @BeforeEach
    void createWriterAndSchema() {
        writer = new Neo4jGraphWriter(driver);
        writer.ensureSchema();
    }

    private static List<String> strings(String cypher) {
        return Try.withResources(() -> driver.session())
                .of(session -> List.ofAll(session.run(cypher).list()).map(row -> row.get(0).asString()))
                .get();
    }

    private static EntityProbe probe(int idx, String name, String label) {
        return new EntityProbe(idx, name, EntityNormalizer.normalize(name), EntityNormalizer.matchKey(name), label,
                FulltextQueries.forName(name));
    }

    /** Creates nodes through the real write path; returns their element ids in order. */
    private List<String> create(String hash, NewNode... nodes) {
        writer.mergeDocument(hash, hash + ".pdf");
        val plan = new ResolutionPlan(List.of(nodes), List.empty(),
                List.of(nodes).map(node -> new Mention(node.key(), node.name(), node.description(), Either.left(node.key()))));
        val ids = writer.applyResolution(plan, hash);
        return List.of(nodes).map(node -> ids.get(node.key()).getOrElse(""));
    }

    private static NewNode node(int key, String name, String label, String... aliases) {
        return new NewNode(key, name, label, name + " description", List.of(aliases).prepend(name));
    }

    @Test
    void ensureSchemaCreatesTheConstraintAndIndexesAndIsIdempotent() {
        writer.ensureSchema();

        assertThat(strings("SHOW CONSTRAINTS YIELD name RETURN name")).contains("document_hash");
        assertThat(strings("SHOW INDEXES YIELD name, state WHERE state = 'ONLINE' RETURN name"))
                .contains("entity_name_norm", "entity_match_key", "entity_names");
    }

    @Test
    void applyResolutionCreatesEntityNodesWithAliasesAndMentions() {
        val ids = create("h1", node(0, "Apple Inc.", "Organization", "Apple"));

        assertThat(ids.head()).isNotBlank();
        assertThat(countNodes("MATCH (e:Entity:Organization {name: 'Apple Inc.', name_norm: 'apple inc.', match_key: 'apple', "
                + "label: 'Organization', source_doc: 'h1', aliases_text: 'Apple Inc. | Apple'}) "
                + "WHERE e.aliases_norm = ['apple inc.', 'apple'] RETURN count(e)")).isEqualTo(1);
        assertThat(countNodes("MATCH (:Document {hash: 'h1'})-[m:MENTIONS {name: 'Apple Inc.', description: 'Apple Inc. description'}]->"
                + "(:Entity) RETURN count(m)")).isEqualTo(1);
    }

    @Test
    void updatesUnionAliasesSwapTheLabelAndLinkAnotherDocument() {
        val watson = create("h1", node(0, "Watson", "Technology")).head();
        writer.mergeDocument("h2", "h2.pdf");
        val plan = new ResolutionPlan(List.empty(),
                List.of(new NodeUpdate(watson, "Thomas J. Watson Jr.", "Technology", "Person", List.of("Thomas J. Watson Jr.", "Watson Jr."))),
                List.of(new Mention(0, "Watson Jr.", "the son", Either.right(watson))));

        val ids = writer.applyResolution(plan, "h2");

        assertThat(ids.get(0)).contains(watson);
        assertThat(countNodes("MATCH (e:Entity:Person {name: 'Thomas J. Watson Jr.', label: 'Person', match_key: 'thomas j watson jr', "
                + "source_doc: 'h1', description: 'Watson description'}) WHERE NOT e:Technology "
                + "AND e.aliases = ['Watson', 'Thomas J. Watson Jr.', 'Watson Jr.'] "
                + "AND e.aliases_norm = ['watson', 'thomas j. watson jr.', 'watson jr.'] "
                + "AND e.aliases_text = 'Watson | Thomas J. Watson Jr. | Watson Jr.' RETURN count(e)")).isEqualTo(1);
        assertThat(countNodes("MATCH (d:Document)-[m:MENTIONS]->(:Person) RETURN count(DISTINCT d)")).isEqualTo(2);
        assertThat(countNodes("MATCH (:Document {hash: 'h2'})-[m:MENTIONS {description: 'the son'}]->() RETURN count(m)")).isEqualTo(1);
    }

    @Test
    void theWriteIsOneTransaction() {
        writer.mergeDocument("h1", "a.pdf");
        // The create runs, then the mention for a key that was never created fails inside the same transaction.
        val plan = new ResolutionPlan(List.of(node(0, "Orphan", "Concept")), List.empty(),
                List.of(new Mention(0, "Orphan", "", Either.left(0)), new Mention(1, "Ghost", "", Either.left(7))));

        assertThatThrownBy(() -> writer.applyResolution(plan, "h1")).isInstanceOf(IllegalStateException.class);
        assertThat(countNodes("MATCH (n:Entity) RETURN count(n)")).isZero();
    }

    @Test
    void candidatesMatchOnNameAliasAndMatchKeyAndCarryNeighbours() {
        val ids = create("h1", node(0, "Apple", "Organization"), node(1, "IBM", "Organization", "International Business Machines"),
                node(2, "Steve Jobs", "Person"));
        writer.mergeRelationships(List.of(new ResolvedRelationship(ids.get(2), ids.get(0), "FOUNDED", "", "Steve Jobs", "Apple")));

        val candidates = writer.findCandidates(List.of(probe(0, "Apple Inc.", "Organization"),
                probe(1, "international business machines", "Company"), probe(2, "apple", "Product"), probe(3, "Nobody", "Person")), 5, 100.0);

        assertThat(candidates.get(0).getOrElse(List.empty())).singleElement().satisfies(candidate -> {
            assertThat(candidate.elementId()).isEqualTo(ids.get(0));
            assertThat(candidate.exact()).isTrue();
            assertThat(candidate.neighbours()).containsExactly("<-[FOUNDED]- Steve Jobs");
        });
        assertThat(candidates.get(1).getOrElse(List.empty())).extracting(Candidate::name).containsExactly("IBM");
        assertThat(candidates.get(2).getOrElse(List.empty())).extracting(Candidate::label).containsExactly("Organization");
        assertThat(candidates.get(3)).isEmpty();
    }

    @Test
    void fullTextCandidatesAreFuzzyCappedAndIgnoreSpecialCharacters() {
        create("h1", node(0, "IBM Research", "Organization"), node(1, "AS/400", "Product"), node(2, "C++", "Technology"),
                node(3, "IBM Research Lab", "Organization"), node(4, "IBM Global Services", "Organization"));

        val candidates = writer.findCandidates(List.of(probe(0, "IBM Research Division", "Organization"), probe(1, "AS/400", "Product"),
                probe(2, "C++", "Technology"), probe(3, "+/-", "Concept")), 2, 0.01);

        assertThat(candidates.get(0).getOrElse(List.empty())).hasSize(2).allSatisfy(candidate -> assertThat(candidate.exact()).isFalse())
                .extracting(Candidate::name).contains("IBM Research");
        assertThat(candidates.get(1).getOrElse(List.empty())).extracting(Candidate::name).contains("AS/400");
        assertThat(candidates.get(2).getOrElse(List.empty())).extracting(Candidate::name).contains("C++");
        assertThat(candidates.get(3)).isEmpty();
    }

    @Test
    void aHighScoreFloorDropsFullTextCandidates() {
        create("h1", node(0, "IBM Research", "Organization"));

        assertThat(writer.findCandidates(List.of(probe(0, "IBM Research Division", "Organization")), 5, 1000.0)).isEmpty();
    }

    @Test
    void neighboursLeaveOutDocuments() {
        create("h1", node(0, "Apple", "Organization"));

        assertThat(writer.findCandidates(List.of(probe(0, "Apple", "Organization")), 5, 100.0).get(0).getOrElse(List.empty()))
                .singleElement().satisfies(candidate -> assertThat(candidate.neighbours()).isEmpty());
    }

    @Test
    void topEntityTypesIgnoreTheEntityLabelAndTopNamesIgnoreMentions() {
        val ids = create("h1", node(0, "Hub", "Person"), node(1, "Leaf", "Person"), node(2, "Popular", "Organization"));
        writer.mergeRelationships(List.of(new ResolvedRelationship(ids.get(0), ids.get(1), "KNOWS", "", "Hub", "Leaf")));
        List.range(2, 6).forEach(index -> create("h" + index, node(0, "Mentioned " + index, "Concept")));
        Try.withResources(() -> driver.session()).of(session -> session.run(
                "MATCH (d:Document), (p:Entity {name: 'Popular'}) MERGE (d)-[:MENTIONS]->(p)").consume()).get();

        assertThat(writer.readTopEntityTypes(10)).doesNotContain("Entity").contains("Person", "Organization");
        assertThat(writer.readTopEntityNames(2)).containsExactlyInAnyOrder("Hub", "Leaf");
    }

    @Test
    void resolvingTwoUploadsMergesTheSecondIntoTheFirstsNodes() {
        val prompter = mock(ResolutionPrompter.class);
        when(prompter.decide(any(), any())).thenAnswer(invocation -> Try.success(Resolved.empty()));
        val service = new EntityResolutionService(writer, prompter,
                new AppProperties(0, 0, null, null, null, null, new AppProperties.Resolution(true, 5, 100.0, 20)));

        writer.mergeDocument("h1", "first.pdf");
        val first = service.resolve(new ExtractedEntities(List.of(entity("Apple", "Organization"), entity("Steve Jobs", "Person")), 0), "h1");
        writer.mergeDocument("h2", "second.pdf");
        val second = service.resolve(new ExtractedEntities(List.of(entity("Apple Inc.", "Organization")), 0), "h2");
        service.shutdown();

        assertThat(first.entitiesCreated()).isEqualTo(2);
        assertThat(second.entitiesMerged()).isEqualTo(1);
        assertThat(second.canonicalIndex().lookup("Apple Inc.", "Organization")).isEqualTo(first.canonicalIndex().lookup("Apple", "Organization"));
        assertThat(countNodes("MATCH (e:Entity:Organization) WHERE 'apple inc.' IN e.aliases_norm AND e.name = 'Apple' RETURN count(e)"))
                .isEqualTo(1);
        assertThat(countNodes("MATCH (d:Document)-[:MENTIONS]->(:Organization) RETURN count(d)")).isEqualTo(2);
        assertThat(writer.readSchema().map(schema -> schema.relTypes())).contains(java.util.List.of("MENTIONS"));
    }

    @Test
    void anLlmDecisionAcrossLabelsSwapsToTheBetterLabel() {
        val watch = create("h1", node(0, "Apple Watch", "Technology")).head();
        val prompter = mock(ResolutionPrompter.class);
        val service = new EntityResolutionService(writer, prompter,
                new AppProperties(0, 0, null, null, null, null, new AppProperties.Resolution(true, 5, 100.0, 20)));
        when(prompter.decide(any(), any())).thenAnswer(invocation -> {
            val candidate = invocation.<List<net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Question>>getArgument(0).head();
            val target = ((net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityQuestion) candidate).candidates().head();
            return Try.success(new Resolved(List.of(new MergeExisting(0, target, Option.none(), "llm")), List.empty(), HashSet.empty()));
        });

        writer.mergeDocument("h2", "b.pdf");
        val result = service.resolve(new ExtractedEntities(List.of(entity("Apple Watch", "Product")), 0), "h2");
        service.shutdown();

        assertThat(result.canonicalIndex().lookup("Apple Watch", "Product")).contains(watch);
        assertThat(countNodes("MATCH (e:Entity:Product {label: 'Product'}) WHERE NOT e:Technology RETURN count(e)")).isEqualTo(1);
    }
}
