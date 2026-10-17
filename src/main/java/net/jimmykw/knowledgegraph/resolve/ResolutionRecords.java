package net.jimmykw.knowledgegraph.resolve;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Set;
import io.vavr.control.Either;
import io.vavr.control.Option;
import lombok.With;

public final class ResolutionRecords {

    private ResolutionRecords() {
    }

    /** One extracted entity as the candidate query sees it; {@code fulltextQuery} is none when the name has nothing to search on. */
    public record EntityProbe(int idx, String name, String nameNorm, String matchKey, String label, Option<String> fulltextQuery) {
    }

    /** An existing {@code :Entity} node offered for one probe. {@code exact} = name, alias or match-key hit (not only full-text). */
    @With
    public record Candidate(int idx, String elementId, String name, String label, String description, List<String> aliases,
                            List<String> neighbours, boolean exact) {
    }

    /** A node to create; {@code key} correlates it with the {@link Mention}s that point at it. */
    public record NewNode(int key, String name, String label, String description, List<String> aliases) {
    }

    /** An existing node that this upload's entities resolved to: new display name, label and the names to union into its aliases. */
    public record NodeUpdate(String elementId, String name, String oldLabel, String label, List<String> aliases) {
    }

    /** Entity {@code idx} as written in this document, pointing at a new node (left: its key) or an existing one (right: elementId). */
    public record Mention(int idx, String name, String description, Either<Integer, String> target) {
    }

    public record ResolutionPlan(List<NewNode> newNodes, List<NodeUpdate> updates, List<Mention> mentions) {
    }

    /** Something the LLM has to decide: one entity against its candidates, or a group of new entities against each other. */
    public sealed interface Question permits EntityQuestion, GroupQuestion {
        List<Integer> entityIdxs();
    }

    public record EntityQuestion(int idx, List<Candidate> candidates) implements Question {
        @Override
        public List<Integer> entityIdxs() {
            return List.of(idx);
        }
    }

    /** New entities that share a match key but not a label, and have no candidates in the graph. */
    public record GroupQuestion(List<Integer> members) implements Question {
        @Override
        public List<Integer> entityIdxs() {
            return members;
        }
    }

    /** Entity {@code idx} resolved to an existing node; {@code canonicalName} none keeps the node's current name. */
    public record MergeExisting(int idx, Candidate target, Option<String> canonicalName, String reason) {
    }

    /** Entities that become one new node. */
    public record NewGroup(List<Integer> members, Option<String> canonicalName) {
    }

    /** Resolution decisions; {@code unresolved} entities are also in {@code newGroups} (they are created as new). */
    public record Resolved(List<MergeExisting> merges, List<NewGroup> newGroups, Set<Integer> unresolved) {

        public static Resolved empty() {
            return new Resolved(List.empty(), List.empty(), HashSet.empty());
        }

        /** Every entity created as its own new node because its decision could not be made. */
        public static Resolved unresolved(List<Integer> idxs) {
            return new Resolved(List.empty(), idxs.map(idx -> new NewGroup(List.of(idx), Option.none())), HashSet.ofAll(idxs));
        }

        public Resolved combine(Resolved other) {
            return new Resolved(merges.appendAll(other.merges), newGroups.appendAll(other.newGroups), unresolved.addAll(other.unresolved));
        }
    }

    /** Decisions made without the LLM plus the questions left for it. */
    public record Classification(Resolved automatic, List<Question> questions) {
    }

    /** What the resolution LLM returns for a batch. */
    public record ResolutionResponse(java.util.List<ResolutionDecision> decisions) {
    }

    public record ResolutionDecision(String entity, String match, String canonicalName) {
    }
}
