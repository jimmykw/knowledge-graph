package net.jimmykw.knowledgegraph.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CypherIdentifiersTest {

    @Test
    void ordinaryLabelsAndTypesAreSafe() {
        assertThat(CypherIdentifiers.isSafe("Person")).isTrue();
        assertThat(CypherIdentifiers.isSafe("WORKS_FOR")).isTrue();
        assertThat(CypherIdentifiers.isSafe("Research Lab / R&D-é")).isTrue();
    }

    @Test
    void blankNullBacktickAndControlCharactersAreUnsafe() {
        assertThat(CypherIdentifiers.isSafe(null)).isFalse();
        assertThat(CypherIdentifiers.isSafe("  ")).isFalse();
        assertThat(CypherIdentifiers.isSafe("A`B")).isFalse();
        assertThat(CypherIdentifiers.isSafe("A\nB")).isFalse();
        assertThat(CypherIdentifiers.isSafe("A\u0000B")).isFalse();
    }
}
