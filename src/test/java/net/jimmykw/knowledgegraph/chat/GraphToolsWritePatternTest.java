package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
}
