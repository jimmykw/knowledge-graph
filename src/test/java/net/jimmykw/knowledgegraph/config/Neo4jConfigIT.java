package net.jimmykw.knowledgegraph.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class Neo4jConfigIT extends Neo4jItSupport {

    @Test
    void apocProbeSucceedsAgainstARealApocInstall() {
        assertThatCode(() -> new Neo4jConfig().apocProbe(driver, new Neo4jGraphWriter(driver),
                new AppProperties(0, 0, null, null, null, null, null)).run(null)).doesNotThrowAnyException();
    }
}
