package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import lombok.val;

class GraphToolsWritePatternTest {

    @Test
    void writePatternMatchesWriteOperations() {
        val pattern = GraphTools.writePattern();
        assertThat(pattern.matcher("CREATE (n:Person)").find()).isTrue();
        assertThat(pattern.matcher("MERGE (n:Person {name:'IBM'})").find()).isTrue();
        assertThat(pattern.matcher("DELETE n").find()).isTrue();
        assertThat(pattern.matcher("SET n.name = 'IBM'").find()).isTrue();
        assertThat(pattern.matcher("REMOVE n:Person").find()).isTrue();
        assertThat(pattern.matcher("DROP CONSTRAINT c").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.merge.node(['Person'], {name:'IBM'})").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.create.node(['Person'], {name:'IBM'})").find()).isTrue();
    }

    @Test
    void writePatternDoesNotMatchReadOnlyQueries() {
        val pattern = GraphTools.writePattern();
        assertThat(pattern.matcher("MATCH (n:Person)-[r]->(m) RETURN n, r, m").find()).isFalse();
        assertThat(pattern.matcher("MATCH (n) WHERE n.name_norm = toLower('IBM') RETURN n").find()).isFalse();
        assertThat(pattern.matcher("CALL db.labels() YIELD label RETURN label").find()).isFalse();
    }

    @Test
    void externalPatternMatchesLoadCsvAndBlockedApocNamespaces() {
        val pattern = GraphTools.externalPattern();
        assertThat(pattern.matcher("LOAD CSV FROM 'file:///etc/passwd' AS row RETURN row").find()).isTrue();
        assertThat(pattern.matcher("load   csv with headers from 'http://x' as r return r").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.load.json('http://x') YIELD value RETURN value").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.import.csv([], [], {})").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.export.json.all('f.json', {})").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.cypher.run('MATCH (n) RETURN n', {})").find()).isTrue();
        assertThat(pattern.matcher("CALL apoc.periodic.iterate('a', 'b', {})").find()).isTrue();
    }

    @Test
    void externalPatternDoesNotMatchPlainReads() {
        val pattern = GraphTools.externalPattern();
        assertThat(pattern.matcher("MATCH (n:Person) RETURN n.name").find()).isFalse();
        assertThat(pattern.matcher("CALL db.labels() YIELD label RETURN label").find()).isFalse();
        assertThat(pattern.matcher("RETURN apoc.version()").find()).isFalse();
    }

    @Disabled("Known false positive: the keyword scan is not string-literal aware, so CONTAINS 'create' is blocked as a write")
    @Test
    void keywordInsideStringLiteralIsNotAWrite() {
        val pattern = GraphTools.writePattern();
        assertThat(pattern.matcher("MATCH (n) WHERE n.description CONTAINS 'create' RETURN n").find()).isFalse();
    }
}
