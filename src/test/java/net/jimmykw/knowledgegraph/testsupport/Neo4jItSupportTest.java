package net.jimmykw.knowledgegraph.testsupport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Neo4jItSupportTest {

    @Test
    void refusesTheRealGraphPort() {
        assertThatThrownBy(() -> Neo4jItSupport.assertSafeTarget("bolt://localhost:7687"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7687");
    }

    @Test
    void refusesAnUriWithoutPortBecauseThatIsTheDefaultRealGraphPort() {
        assertThatThrownBy(() -> Neo4jItSupport.assertSafeTarget("bolt://localhost")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Neo4jItSupport.assertSafeTarget("neo4j://db.internal")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesTheRealPortOnAnyHost() {
        assertThatThrownBy(() -> Neo4jItSupport.assertSafeTarget("neo4j://some.remote.host:7687")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsTheThrowawayPort() {
        assertThatCode(() -> Neo4jItSupport.assertSafeTarget("bolt://localhost:7688")).doesNotThrowAnyException();
    }
}
