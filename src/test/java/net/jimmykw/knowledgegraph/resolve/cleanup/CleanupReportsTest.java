package net.jimmykw.knowledgegraph.resolve.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ApplyOutcome;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.CleanupPlan;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanNode;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ReviewItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CleanupReportsTest {

    private static final PlanNode A = new PlanNode("a", "A|B", "Organization", "fp-a");
    private static final PlanNode B = new PlanNode("b", "AB", "Organization", "fp-b");
    private static final PlanEntry ENTRY = new PlanEntry("a", java.util.List.of(A, B), "A|B", "Organization", java.util.List.of("A|B", "AB"),
            "match_key");
    private static final CleanupPlan PLAN = new CleanupPlan("2026-10-17T00:00:00Z", 1.0, java.util.List.of(ENTRY),
            java.util.List.of(new ReviewItem(java.util.List.of(B), "LLM not confident")));

    @TempDir
    Path dir;

    @Test
    void planJsonRoundTripsAndMissingPlanIsNone() {
        val reports = new CleanupReports(dir.resolve("nested"));
        assertThat(reports.readPlan()).isEmpty();

        reports.writePlan(PLAN);

        assertThat(reports.readPlan()).contains(PLAN);
    }

    @Test
    void planMarkdownSummarisesEscapesAndListsReview() {
        assertThat(CleanupReports.planMarkdown(PLAN))
                .contains("1 merge(s) covering 2 nodes (1 match_key). 1 node(s) need review.")
                .contains("| A\\|B | Organization | A\\|B [Organization], AB [Organization] | match_key |")
                .contains("- AB [Organization] — LLM not confident");
    }

    @Test
    void applyMarkdownListsMergesSelfLoopsAndFailures() {
        val markdown = CleanupReports.applyMarkdown(List.of(new ApplyOutcome(ENTRY, true, java.util.List.of("A -[OWNS]-> AB"), ""),
                new ApplyOutcome(ENTRY, false, java.util.List.of(), "boom")));

        assertThat(markdown).contains("1 merged, 1 failed", "self-loops removed: A -[OWNS]-> AB", "- A|B (a): boom");
    }
}
