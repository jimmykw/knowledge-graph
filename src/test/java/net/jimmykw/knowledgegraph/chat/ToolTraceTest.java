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

    private static List<Map<String, Object>> rowsOf(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> Map.<String, Object>of("index", index))
                .toList();
    }
}
