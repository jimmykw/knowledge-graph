package net.jimmykw.knowledgegraph.resolve.cleanup;

import java.util.List;

/** The one-time cleanup's data. Plan records use {@code java.util.List} because they are written to and read from plan.json. */
public final class CleanupRecords {

    private CleanupRecords() {
    }

    /** An {@code :Entity} node as planning reads it; {@code degree} leaves {@code MENTIONS} edges out. */
    public record GraphEntity(String elementId, String name, String label, List<String> labels, String description, List<String> aliases,
                              int degree) {
    }

    /** A node in the plan, with the fingerprint apply checks before it changes anything. */
    public record PlanNode(String elementId, String name, String label, String fingerprint) {
    }

    /** Merge {@code nodes} (survivor first) into the survivor with this name, label and aliases. */
    public record PlanEntry(String survivorId, List<PlanNode> nodes, String canonicalName, String label, List<String> aliases,
                            String reason) {
    }

    /** Nodes the planner left alone because the LLM was not confident or the decision could not be made. */
    public record ReviewItem(List<PlanNode> nodes, String note) {
    }

    public record CleanupPlan(String createdAt, double minFulltextScore, List<PlanEntry> entries, List<ReviewItem> needsReview) {
    }

    public record MigrationResult(long labelled, long keyed, long mentions) {
    }

    /** What applying one entry did: the edges between merged nodes that became self-loops, or why it failed. */
    public record ApplyOutcome(PlanEntry entry, boolean ok, List<String> removedSelfLoops, String error) {
    }

    /** What the cluster LLM returns: groups of members that are one entity, and members it is unsure about. */
    public record ClusterResponse(List<ClusterGroup> groups, List<String> unsure) {
    }

    public record ClusterGroup(List<String> members, String canonicalName) {
    }
}
