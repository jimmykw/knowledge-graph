package net.jimmykw.knowledgegraph.resolve.cleanup;

import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ApplyOutcome;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.CleanupPlan;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanNode;

import io.vavr.Function1;
import io.vavr.collection.List;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/** The two modes of {@code ./gradlew resolveGraph}: migrate and plan (no merges), or apply a reviewed plan. */
@Slf4j
@RequiredArgsConstructor
public class ResolveGraph {

    static final String BACKUP_REMINDER = "Apply merges nodes in the graph and cannot be undone. Take a dump first: "
            + "neo4j-admin database dump --to-path=<dir> neo4j (with the database stopped), then rerun with -Dresolve.backupConfirmed=true.";

    private final GraphCleanupStore store;
    private final CleanupPlanner planner;
    private final CleanupReports reports;

    public CleanupPlan plan() {
        store.migrate();
        val plan = planner.plan();
        reports.writePlan(plan);
        log.info("Plan written to {} and {}: {} merge(s), {} review item(s). Nothing was merged.", reports.planJson(), reports.planMarkdown(),
                plan.entries().size(), plan.needsReview().size());
        return plan;
    }

    /** Fails before changing anything when the plan is missing or any node in it changed or disappeared. */
    public List<ApplyOutcome> apply(boolean backupConfirmed) {
        log.warn(BACKUP_REMINDER);
        if (!backupConfirmed) {
            throw new IllegalStateException("Refusing to apply without -Dresolve.backupConfirmed=true. " + BACKUP_REMINDER);
        }
        val plan = reports.readPlan().getOrElseThrow(() -> new IllegalStateException(
                "No plan at " + reports.planJson() + ". Run plan mode first: ./gradlew resolveGraph"));
        val problems = validate(plan);
        if (problems.nonEmpty()) {
            throw new IllegalStateException("The graph changed since the plan was made; run plan mode again. Changed or missing nodes:\n  "
                    + problems.mkString("\n  "));
        }
        val outcomes = List.ofAll(plan.entries()).map(this::applyEntry);
        reports.writeApply(outcomes);
        log.info("Apply finished: {} merged, {} failed. Report: {}", outcomes.count(ApplyOutcome::ok), outcomes.count(outcome -> !outcome.ok()),
                reports.applyMarkdown());
        return outcomes;
    }

    List<String> validate(CleanupPlan plan) {
        val nodes = List.ofAll(plan.entries()).flatMap(entry -> entry.nodes());
        val current = store.currentFingerprints(nodes.map(PlanNode::elementId).distinct());
        return nodes.flatMap(node -> current.get(node.elementId()).flatMap(Function1.identity())
                .fold(() -> List.of("missing: " + node.name() + " (" + node.elementId() + ")"),
                        fingerprint -> fingerprint.equals(node.fingerprint())
                                ? List.<String>empty()
                                : List.of("changed: " + node.name() + " (" + node.elementId() + ")")));
    }

    private ApplyOutcome applyEntry(PlanEntry entry) {
        return Try.of(() -> store.applyEntry(entry))
                .map(loops -> new ApplyOutcome(entry, true, loops.toJavaList(), ""))
                .onFailure(failure -> log.warn("Apply: merging into '{}' failed — {}", entry.canonicalName(), failure.getMessage()))
                .getOrElseGet(failure -> new ApplyOutcome(entry, false, java.util.List.of(), String.valueOf(failure.getMessage())));
    }
}
