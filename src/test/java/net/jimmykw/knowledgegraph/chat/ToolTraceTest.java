package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import lombok.val;

class ToolTraceTest {

    @Test
    void accumulatesEvidenceAcrossQueries() {
        val trace = new ToolTrace();
        trace.recordRunReadCypher("MATCH (e)-[r]->(n) RETURN r, n", rowsOf(136), 136, false, null);
        trace.recordRunReadCypher("MATCH (e) RETURN e LIMIT 1", rowsOf(1), 1, false, null);

        assertThat(trace.evidence()).hasSize(2);
        assertThat(trace.evidence().stream().mapToInt(QueryEvidence::count).max().orElseThrow())
                .as("largest query result survives a later smaller query")
                .isEqualTo(136);
        assertThat(trace.lastCount()).as("legacy last-query count").isEqualTo(1);
        assertThat(trace.cypherQueries()).hasSize(2);
    }

    @Test
    void recordsErroredQueryAsEvidenceWithError() {
        val trace = new ToolTrace();
        trace.recordRunReadCypher("MATCH (e) WHERE e.name_norm = toLower($name) RETURN e",
                List.of(), 0, false, "Expected parameter(s): name");

        assertThat(trace.evidence()).hasSize(1);
        val evidence = trace.evidence().get(0);
        assertThat(evidence.error()).isEqualTo("Expected parameter(s): name");
        assertThat(evidence.rows()).isEmpty();
        assertThat(evidence.count()).isZero();
    }

    @Test
    void blankCypherIsKeptAsEvidenceButNotListedAsAQuery() {
        val trace = new ToolTrace();
        trace.recordRunReadCypher("  ", List.of(), 0, false, "Cypher query must not be blank.");
        trace.recordRunReadCypher(null, List.of(), 0, false, null);
        trace.recordCypherQuery("");

        assertThat(trace.cypherQueries()).isEmpty();
        assertThat(trace.evidence()).hasSize(2);
    }

    @Test
    void lastAccessorsReflectTheMostRecentQuery() {
        val trace = new ToolTrace();
        trace.recordRunReadCypher("Q1", rowsOf(3), 3, true, null);
        trace.recordRunReadCypher("Q2", rowsOf(1), 1, false, null);

        assertThat(trace.lastCypher()).isEqualTo("Q2");
        assertThat(trace.lastRows()).hasSize(1);
        assertThat(trace.lastCount()).isEqualTo(1);
        assertThat(trace.lastTruncated()).isFalse();
        assertThat(trace.evidence().get(0).truncated()).isTrue();
    }

    @Test
    void freshTraceHasEmptyDefaults() {
        val trace = new ToolTrace();

        assertThat(trace.lastCypher()).isNull();
        assertThat(trace.lastRows()).isEmpty();
        assertThat(trace.error()).isNull();
        assertThat(trace.roundCount()).isZero();
        assertThat(trace.maxRoundsExceeded()).isFalse();
    }

    @Test
    void recordsToolCallsSkillsErrorAndRounds() {
        val trace = new ToolTrace();
        trace.recordToolCall("getGraphSchema", "{}", "{}", null);
        trace.recordSkill("entity-lookup");
        trace.setError("boom");
        trace.incrementRoundCount();
        trace.markMaxRoundsExceeded();

        assertThat(trace.toolCalls()).hasSize(1);
        assertThat(trace.toolCalls().get(0).name()).isEqualTo("getGraphSchema");
        assertThat(trace.skillsExecuted()).containsExactly("entity-lookup");
        assertThat(trace.error()).isEqualTo("boom");
        assertThat(trace.roundCount()).isEqualTo(1);
        assertThat(trace.maxRoundsExceeded()).isTrue();
    }

    private static List<Map<String, Object>> rowsOf(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> Map.<String, Object>of("index", index))
                .toList();
    }
}
