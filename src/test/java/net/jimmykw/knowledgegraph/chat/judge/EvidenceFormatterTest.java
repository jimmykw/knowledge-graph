package net.jimmykw.knowledgegraph.chat.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.QueryEvidence;
import org.junit.jupiter.api.Test;

class EvidenceFormatterTest {

    private static QueryEvidence query(String cypher, int rows, boolean truncated, String error) {
        val data = List.range(0, rows).map(index -> Map.<String, Object>of("name", "Entity " + index)).toJavaList();
        return new QueryEvidence(cypher, data, rows, truncated, error);
    }

    @Test
    void emptyEvidenceStatesNoRows() {
        val formatted = EvidenceFormatter.format(List.empty(), 1000);
        assertThat(formatted.text()).isEqualTo(EvidenceFormatter.NO_ROWS);
        assertThat(formatted.truncated()).isFalse();
    }

    @Test
    void zeroRowAndErrorOnlyEvidenceStatesNoRowsAndKeepsError() {
        val formatted = EvidenceFormatter.format(List.of(query("MATCH (n) RETURN n", 0, false, null),
                query("MATCH (x) RETURN x", 0, false, "boom")), 1000);
        assertThat(formatted.text()).contains("[query 1] MATCH (n) RETURN n", "[query 2]", "error: boom")
                .endsWith(EvidenceFormatter.NO_ROWS);
    }

    @Test
    void rowsAreRenderedAsJson() {
        val formatted = EvidenceFormatter.format(List.of(query("MATCH (n) RETURN n.name", 2, false, null)), 1000);
        assertThat(formatted.text()).contains("{\"name\":\"Entity 0\"}", "{\"name\":\"Entity 1\"}")
                .doesNotContain(EvidenceFormatter.NO_ROWS);
        assertThat(formatted.truncated()).isFalse();
    }

    @Test
    void overBudgetDropsRowsWithMarkerAndFlagsTruncation() {
        val formatted = EvidenceFormatter.format(List.of(query("MATCH (n) RETURN n", 100, false, null)), 300);
        assertThat(formatted.text().length()).isLessThanOrEqualTo(300 + 40);
        assertThat(formatted.text()).containsPattern("\\.\\.\\. \\d+ more rows not shown");
        assertThat(formatted.truncated()).isTrue();
    }

    @Test
    void shortQueryKeepsAllRowsWhileLargeQueryGetsTheRest() {
        val formatted = EvidenceFormatter.format(List.of(query("MATCH (a) RETURN a", 100, false, null),
                query("MATCH (b) RETURN b", 2, false, null)), 600);
        val secondBlock = formatted.text().substring(formatted.text().indexOf("[query 2]"));
        assertThat(secondBlock).contains("Entity 0", "Entity 1").doesNotContain("more rows not shown");
        assertThat(formatted.text()).contains("more rows not shown");
    }

    @Test
    void serverTruncationAloneFlagsTruncation() {
        assertThat(EvidenceFormatter.format(List.of(query("MATCH (n) RETURN n", 2, true, null)), 1000).truncated()).isTrue();
    }
}
