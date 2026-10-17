package net.jimmykw.knowledgegraph.resolve;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.graph.PreferredLabels;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewNode;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NodeUpdate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionPlan;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;

import io.vavr.Function1;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Either;
import lombok.experimental.UtilityClass;
import lombok.val;

/** Turns resolution decisions into the write plan: one new node per group, one update per existing node, one mention per entity. */
@UtilityClass
public class ResolutionPlanner {

    /** The plan plus what the upload response reports: label counts of the nodes written, nodes created, entities merged. */
    public record PlannedResolution(ResolutionPlan plan, Map<String, Integer> countsByLabel, int created, int merged) {
    }

    public static PlannedResolution plan(List<ExtractedEntity> entities, Resolved resolved) {
        val newNodes = resolved.newGroups().zipWithIndex().map(pair -> newNode(pair._2, pair._1, entities));
        val updates = resolved.merges()
                .groupBy(merge -> merge.target().elementId())
                .values()
                .toList()
                .sortBy(merges -> merges.head().idx())
                .map(merges -> update(merges, entities));
        val mentions = newGroupMentions(resolved.newGroups(), entities)
                .appendAll(resolved.merges().map(merge -> mention(merge.idx(), entities, Either.right(merge.target().elementId()))))
                .sortBy(Mention::idx);
        val labelByNode = updates.toMap(NodeUpdate::elementId, NodeUpdate::label);
        val counts = newNodes.map(NewNode::label)
                .appendAll(resolved.merges().map(merge -> labelByNode.getOrElse(merge.target().elementId(), merge.target().label())))
                .foldLeft(HashMap.<String, Integer>empty(), (acc, label) -> acc.put(label, acc.getOrElse(label, 0) + 1));
        return new PlannedResolution(new ResolutionPlan(newNodes, updates, mentions), counts, newNodes.size(), resolved.merges().size());
    }

    private static NewNode newNode(int key, NewGroup group, List<ExtractedEntity> entities) {
        val members = group.members().map(entities::get);
        val name = group.canonicalName().getOrElse(() -> fullestName(members.map(ExtractedEntity::name)));
        val label = members.tail().map(ExtractedEntity::label).foldLeft(members.head().label(), PreferredLabels::better);
        return new NewNode(key, name, label, members.head().description(), members.map(ExtractedEntity::name).prepend(name).distinct());
    }

    /** Merges into the same node are applied as one update: the first valid canonical name wins and the best-ranked label survives. */
    private static NodeUpdate update(List<MergeExisting> merges, List<ExtractedEntity> entities) {
        val target = merges.head().target();
        val names = merges.map(merge -> entities.get(merge.idx()).name());
        val name = merges.flatMap(MergeExisting::canonicalName).headOption().getOrElse(target.name());
        val label = merges.map(merge -> entities.get(merge.idx()).label()).foldLeft(target.label(), PreferredLabels::better);
        return new NodeUpdate(target.elementId(), name, target.label(), label, names.prepend(name).distinct());
    }

    private static List<Mention> newGroupMentions(List<NewGroup> groups, List<ExtractedEntity> entities) {
        return groups.zipWithIndex()
                .flatMap(pair -> pair._1.members().map(idx -> mention(idx, entities, Either.left(pair._2))));
    }

    private static Mention mention(int idx, List<ExtractedEntity> entities, Either<Integer, String> target) {
        val entity = entities.get(idx);
        return new Mention(idx, entity.name(), entity.description(), target);
    }

    /** The longest name: "Apple Inc." over "Apple". Ties keep the first. */
    public static String fullestName(List<String> names) {
        return names.maxBy(Function1.<String, Integer>of(String::length)).getOrElse("");
    }
}
