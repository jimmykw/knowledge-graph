package net.jimmykw.knowledgegraph.resolve.cleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ApplyOutcome;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.CleanupPlan;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanNode;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ReviewItem;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.val;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** plan.json (what apply reads), plan.md (for review) and apply.md, all in one directory. */
@RequiredArgsConstructor
public class CleanupReports {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path dir;

    public Path planJson() {
        return dir.resolve("plan.json");
    }

    public Path planMarkdown() {
        return dir.resolve("plan.md");
    }

    public Path applyMarkdown() {
        return dir.resolve("apply.md");
    }

    public void writePlan(CleanupPlan plan) {
        write(planJson(), JSON.writeValueAsString(plan));
        write(planMarkdown(), planMarkdown(plan));
    }

    public Option<CleanupPlan> readPlan() {
        return Files.exists(planJson())
                ? Option.some(Try.of(() -> JSON.readValue(planJson().toFile(), CleanupPlan.class))
                        .getOrElseThrow(failure -> new IllegalStateException("Cannot read " + planJson() + ": " + failure.getMessage(), failure)))
                : Option.none();
    }

    public void writeApply(List<ApplyOutcome> outcomes) {
        write(applyMarkdown(), applyMarkdown(outcomes));
    }

    static String planMarkdown(CleanupPlan plan) {
        val entries = List.ofAll(plan.entries());
        val review = List.ofAll(plan.needsReview());
        val reasons = entries.groupBy(PlanEntry::reason).mapValues(List::size).toList().sortBy(pair -> pair._1)
                .map(pair -> pair._2 + " " + pair._1).mkString(", ");
        return """
                # Entity resolution plan

                Created %s. Full-text score floor: %s.

                %d merge(s) covering %d nodes%s. %d node(s) need review.

                Apply with `./gradlew resolveGraph -Dresolve.apply=true -Dresolve.backupConfirmed=true` after taking a dump \
                (`neo4j-admin database dump`).

                ## Merges

                | Canonical name | Label | Nodes merged (survivor first) | Reason |
                |---|---|---|---|
                %s

                ## Needs review

                %s
                """.formatted(plan.createdAt(), plan.minFulltextScore(), entries.size(), entries.map(entry -> entry.nodes().size()).sum().intValue(),
                reasons.isEmpty() ? "" : " (" + reasons + ")", review.map(item -> item.nodes().size()).sum().intValue(),
                entries.map(CleanupReports::entryRow).mkString("\n"),
                review.isEmpty() ? "Nothing." : review.map(CleanupReports::reviewRow).mkString("\n"));
    }

    private static String entryRow(PlanEntry entry) {
        return "| %s | %s | %s | %s |".formatted(cell(entry.canonicalName()), cell(entry.label()),
                cell(List.ofAll(entry.nodes()).map(CleanupReports::describe).mkString(", ")), entry.reason());
    }

    private static String reviewRow(ReviewItem item) {
        return "- " + List.ofAll(item.nodes()).map(CleanupReports::describe).mkString(", ") + " — " + item.note();
    }

    private static String describe(PlanNode node) {
        return node.name() + " [" + node.label() + "]";
    }

    static String applyMarkdown(List<ApplyOutcome> outcomes) {
        val partition = outcomes.partition(ApplyOutcome::ok);
        return """
                # Entity resolution apply

                Applied %s: %d merged, %d failed.

                ## Merged

                %s

                ## Failed

                %s
                """.formatted(Instant.now(), partition._1.size(), partition._2.size(),
                partition._1.isEmpty() ? "Nothing." : partition._1.map(CleanupReports::appliedRow).mkString("\n"),
                partition._2.isEmpty() ? "Nothing." : partition._2.map(CleanupReports::failedRow).mkString("\n"));
    }

    private static String appliedRow(ApplyOutcome outcome) {
        val loops = List.ofAll(outcome.removedSelfLoops());
        return "- " + outcome.entry().canonicalName() + " [" + outcome.entry().label() + "] ← "
                + List.ofAll(outcome.entry().nodes()).map(CleanupReports::describe).mkString(", ")
                + (loops.isEmpty() ? "" : "; self-loops removed: " + loops.mkString(", "));
    }

    private static String failedRow(ApplyOutcome outcome) {
        return "- " + outcome.entry().canonicalName() + " (" + outcome.entry().survivorId() + "): " + outcome.error();
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private static void write(Path path, String content) {
        Try.run(() -> {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        }).getOrElseThrow(failure -> new IllegalStateException("Cannot write " + path + ": " + failure.getMessage(), failure));
    }
}
