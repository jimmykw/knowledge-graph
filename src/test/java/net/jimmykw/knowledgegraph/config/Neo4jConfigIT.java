package net.jimmykw.knowledgegraph.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class Neo4jConfigIT extends Neo4jItSupport {

    @Test
    void apocProbeSucceedsAgainstARealApocInstall() {
        assertThatCode(() -> new Neo4jConfig().apocProbe(driver).run(null)).doesNotThrowAnyException();
    }
}
