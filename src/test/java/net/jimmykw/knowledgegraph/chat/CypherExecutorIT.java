package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import lombok.val;
import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class CypherExecutorIT extends Neo4jItSupport {

    private CypherExecutor executor;

    @BeforeEach
    void createExecutor() {
        executor = new CypherExecutor(driver, 3, 1000L);
    }

    private static void run(String cypher) {
        io.vavr.control.Try.withResources(() -> driver.session()).of(session -> session.run(cypher).consume()).get();
    }

    @Test
    void returnsRealRows() {
        val result = executor.execute("UNWIND [1, 2] AS n RETURN n").get();

        assertThat(result.rows()).containsExactly(Map.of("n", 1L), Map.of("n", 2L));
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void truncatesAtTheRowCap() {
        val result = executor.execute("UNWIND range(1, 10) AS n RETURN n").get();

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aSlowQueryFailsOnTheConfiguredTimeout() {
        val start = System.nanoTime();
        val result = executor.execute("CALL apoc.util.sleep(5000) RETURN 1 AS x");

        assertThat(result.isFailure()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(4000L);
        assertThat(result.getCause()).isInstanceOf(org.neo4j.driver.exceptions.ClientException.class).hasMessageContaining("timeout");
    }

    @Test
    void aWriteQueryFailsInsideTheReadTransaction() {
        val result = executor.execute("CREATE (n:Intruder {name: 'x'}) RETURN n");

        assertThat(result.isFailure()).isTrue();
        assertThat(countNodes("MATCH (n:Intruder) RETURN count(n)")).isZero();
    }

    @Test
    void flattensRealNodesRelationshipsAndPaths() {
        run("CREATE (:Person {name: 'Watson'})-[:WORKS_FOR {description: 'led'}]->(:Organization {name: 'IBM'})");

        val nodes = executor.execute("MATCH (p:Person) RETURN p").get().rows().get(0);
        val rel = executor.execute("MATCH ()-[r:WORKS_FOR]->() RETURN r").get().rows().get(0);
        val path = executor.execute("MATCH p = (:Person)-[:WORKS_FOR]->(:Organization) RETURN p").get().rows().get(0);

        assertThat((Map<String, Object>) nodes.get("p")).containsEntry("__type", "node")
                .containsEntry("label", "Person").containsEntry("name", "Watson");
        assertThat((Map<String, Object>) rel.get("r")).containsEntry("__type", "rel")
                .containsEntry("type", "WORKS_FOR").containsEntry("description", "led");
        val flattened = (List<Map<String, Object>>) path.get("p");
        assertThat(flattened).hasSize(3);
        assertThat(flattened.get(1)).containsEntry("source", "Watson").containsEntry("target", "IBM");
    }
}
