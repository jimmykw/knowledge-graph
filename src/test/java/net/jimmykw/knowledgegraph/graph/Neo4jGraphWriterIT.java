package net.jimmykw.knowledgegraph.graph;

import static net.jimmykw.knowledgegraph.testsupport.ExtractionJson.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.control.Try;
import lombok.val;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class Neo4jGraphWriterIT extends Neo4jItSupport {

    private Neo4jGraphWriter writer;

    @BeforeEach
    void createWriter() {
        writer = new Neo4jGraphWriter(driver);
    }

    private static void pause() {
        Try.run(() -> Thread.sleep(5)).get();
    }

    @Test
    void mergeDocumentIsIdempotentAndFindableByHash() {
        val first = writer.mergeDocument("hash-1", "a.pdf");
        val second = writer.mergeDocument("hash-1", "a.pdf");

        assertThat(second).isEqualTo(first);
        assertThat(writer.findDocumentId("hash-1")).contains(first);
        assertThat(writer.findDocumentId("unknown")).isEmpty();
        assertThat(countNodes("MATCH (d:Document) RETURN count(d)")).isEqualTo(1);
    }

    @Test
    void mergeEntitiesCreatesNodesWithTheDynamicLabelAndNormalizedName() {
        val ids = writer.mergeEntities(List.of(entity("  Thomas Watson ", "Person"), entity("IBM", "Organization")), "hash-1");

        assertThat(ids.size()).isEqualTo(2);
        assertThat(countNodes("MATCH (n:Person {name_norm: 'thomas watson', source_doc: 'hash-1'}) RETURN count(n)")).isEqualTo(1);
        assertThat(countNodes("MATCH (n:Organization {name_norm: 'ibm', label: 'Organization'}) RETURN count(n)")).isEqualTo(1);
    }

    @Test
    void mergingTheSameEntityAgainReturnsTheSameIdAndCreatesNoDuplicate() {
        val first = writer.mergeEntities(List.of(entity("IBM", "Organization")), "hash-1");
        val second = writer.mergeEntities(List.of(entity("ibm", "Organization")), "hash-2");

        assertThat(second.get(0)).isEqualTo(first.get(0));
        assertThat(countNodes("MATCH (n:Organization) RETURN count(n)")).isEqualTo(1);
    }

    @Test
    void mergeRelationshipsUsesApocWithAMapSixthArgumentAndIsIdempotent() {
        val ids = writer.mergeEntities(List.of(entity("Watson", "Person"), entity("IBM", "Organization")), "h");
        val relationship = new ResolvedRelationship(ids.get(0).get(), ids.get(1).get(), "WORKS_FOR", "ran the company", "Watson", "IBM");

        val first = writer.mergeRelationships(List.of(relationship));
        val second = writer.mergeRelationships(List.of(relationship));

        assertThat(first.get(0).isDefined()).isTrue();
        assertThat(second.get(0)).isEqualTo(first.get(0));
        assertThat(countNodes("MATCH (:Person)-[r:WORKS_FOR {description: 'ran the company'}]->(:Organization) RETURN count(r)")).isEqualTo(1);
    }

    @Test
    void recordSchemaUnionsAcrossCallsAndReadSchemaReturnsIt() {
        assertThat(writer.readSchema()).isEmpty();

        writer.recordSchema(HashSet.of("Person"), HashSet.empty());
        writer.recordSchema(HashSet.of("Person", "Organization"), HashSet.of("USES"));

        val snapshot = writer.readSchema().get();
        assertThat(snapshot.labels()).containsExactlyInAnyOrder("Person", "Organization");
        assertThat(snapshot.relTypes()).containsExactly("USES");
        assertThat(countNodes("MATCH (s:Schema) RETURN count(s)")).isEqualTo(1);
    }

    @Test
    void readTopEntityTypesOrdersByCountAndExcludesBookkeepingNodes() {
        writer.mergeDocument("hash-1", "a.pdf");
        writer.recordSchema(HashSet.of("Person"), HashSet.empty());
        writer.mergeEntities(List.of(entity("A", "Person"), entity("B", "Person"), entity("C", "Person"), entity("D", "Organization"),
                entity("E", "Organization"), entity("F", "Technology")), "hash-1");

        assertThat(writer.readTopEntityTypes(2)).containsExactly("Person", "Organization");
        assertThat(writer.readTopEntityTypes(10)).doesNotContain("Document", "Schema");
    }

    @Test
    void readTopEntityNamesOrdersByDegree() {
        val ids = writer.mergeEntities(List.of(entity("Hub", "Concept"), entity("Leaf1", "Concept"), entity("Leaf2", "Concept"),
                entity("Lonely", "Concept")), "h");
        writer.mergeRelationships(List.of(
                new ResolvedRelationship(ids.get(0).get(), ids.get(1).get(), "LINKS", "", "Hub", "Leaf1"),
                new ResolvedRelationship(ids.get(0).get(), ids.get(2).get(), "LINKS", "", "Hub", "Leaf2")));

        val names = writer.readTopEntityNames(4);

        assertThat(names.head()).isEqualTo("Hub");
        assertThat(names.last()).isEqualTo("Lonely");
        assertThat(writer.readTopEntityNames(1)).containsExactly("Hub");
    }

    @Test
    void readDocumentNamesAreOldestFirst() {
        writer.mergeDocument("h1", "first.pdf");
        pause();
        writer.mergeDocument("h2", "second.pdf");
        pause();
        writer.mergeDocument("h3", "third.pdf");

        assertThat(writer.readDocumentNames()).containsExactly("first.pdf", "second.pdf", "third.pdf");
    }

    @Test
    void cypherFragmentsInEntityNamesAreStoredLiterallyAndNeverExecuted() {
        writer.mergeEntities(List.of(entity("Bystander", "Person")), "h");
        val hostileName = "'}) DETACH DELETE (n) //";

        writer.mergeEntities(List.of(entity(hostileName, "Person")), "h");

        assertThat(countNodes("MATCH (n:Person) WHERE n.name = $name RETURN count(n)", java.util.Map.of("name", hostileName))).isEqualTo(1);
        assertThat(countNodes("MATCH (n:Person {name: 'Bystander'}) RETURN count(n)")).isEqualTo(1);
        assertThat(countNodes("MATCH (s:KgTestInstance) RETURN count(s)")).isEqualTo(1);
    }

    @Test
    void hostileLabelsAreRefusedBeforeAnyQueryRunsSoApocCannotBeInjected() {
        writer.mergeEntities(List.of(entity("Bystander", "Person")), "h");
        // APOC wraps the label in backticks without escaping; this one would delete every :Person if it ever reached it.
        val hostileLabel = "X`) WITH n MATCH (m:Person) DETACH DELETE m //";

        assertThatThrownBy(() -> writer.mergeEntities(List.of(entity("Victim", hostileLabel)), "h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe label refused");

        assertThat(countNodes("MATCH (n:Person {name: 'Bystander'}) RETURN count(n)")).isEqualTo(1);
        assertThat(countNodes("MATCH (n {name: 'Victim'}) RETURN count(n)")).isZero();
        assertThat(countNodes("MATCH (s:KgTestInstance) RETURN count(s)")).isEqualTo(1);
    }

    @Test
    void hostileRelationshipTypesAreRefusedBeforeAnyQueryRuns() {
        val ids = writer.mergeEntities(List.of(entity("A", "Person"), entity("B", "Person")), "h");
        val hostile = new ResolvedRelationship(ids.get(0).get(), ids.get(1).get(), "R`]->(b) DETACH DELETE a //", "", "A", "B");

        assertThatThrownBy(() -> writer.mergeRelationships(List.of(hostile)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe relationship type refused");

        assertThat(countNodes("MATCH (n:Person) RETURN count(n)")).isEqualTo(2);
    }

    @Test
    void unusualButHarmlessLabelsAreStillStoredLiterally() {
        writer.mergeEntities(List.of(entity("Odd", "Research Lab / R&D-\u00e9")), "h");

        assertThat(countNodes("MATCH (n) WHERE n.name = 'Odd' AND $label IN labels(n) RETURN count(n)",
                java.util.Map.of("label", "Research Lab / R&D-\u00e9"))).isEqualTo(1);
    }
}
