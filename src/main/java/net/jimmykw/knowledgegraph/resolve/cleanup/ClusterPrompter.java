package net.jimmykw.knowledgegraph.resolve.cleanup;

import java.util.Objects;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import net.jimmykw.knowledgegraph.resolve.ResolutionPrompter;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ClusterGroup;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ClusterResponse;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.GraphEntity;
import net.jimmykw.knowledgegraph.util.JsonPrompter;

import io.vavr.Function1;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Cluster mode for the cleanup: asks the extraction model which members of one candidate cluster are the same entity. Members
 * are shown as {@code m1, m2, ...}; a group that reuses or invents a reference is not trusted and its members go to review.
 */
@RequiredArgsConstructor
public class ClusterPrompter implements CleanupPlanner.ClusterDecider {

    static final String SYSTEM_PROMPT = """
            You clean up duplicate nodes in a knowledge graph built from documents. You get one cluster
            of nodes (m1, m2, ...) that share a name, a stripped name or similar wording. Decide which of
            them are the same real-world entity, using the names, labels, aliases, descriptions and
            neighbouring edges. Homonyms are different entities: a person and a product both called
            Watson, or Thomas J. Watson Sr. and Thomas J. Watson Jr.

            Reply with:
            - "groups": one entry per real-world entity that has two or more members here, with
              "members" (their references) and "canonicalName" (the fullest unambiguous name, chosen
              from the members' names or aliases).
            - "unsure": members you cannot place with confidence, for example a bare "Watson" whose
              edges do not say which Watson.
            Members that are distinct entities go in no group and not in "unsure". Use each reference at
            most once and never invent references.
            """;

    /** Members of {@code members} that are one entity; {@code canonicalName} none keeps the survivor's name. */
    public record ClusterMerge(List<GraphEntity> members, Option<String> canonicalName) {
    }

    public record ClusterOutcome(List<ClusterMerge> merges, List<GraphEntity> unsure) {
    }

    private final JsonPrompter<ClusterResponse> prompter;

    @Override
    public Try<ClusterOutcome> decide(List<GraphEntity> cluster, Map<String, List<String>> neighbours) {
        return prompter.callWithRetry(buildPrompt(cluster, neighbours)).map(response -> interpret(cluster, response));
    }

    Prompt buildPrompt(List<GraphEntity> cluster, Map<String, List<String>> neighbours) {
        val members = cluster.zipWithIndex().map(pair -> render(ref(pair._2), pair._1, neighbours.getOrElse(pair._1.elementId(), List.empty())));
        val userText = """
                Cluster:
                %s
                ---
                %s
                """.formatted(members.mkString("\n"), prompter.getFormat());
        return new Prompt(java.util.List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userText)));
    }

    private static String render(String ref, GraphEntity entity, List<String> edges) {
        val aliases = List.ofAll(entity.aliases()).remove(entity.name());
        return "%s: \"%s\" [%s] - %s%s%s".formatted(ref, entity.name(), entity.label(), entity.description(),
                aliases.isEmpty() ? "" : "; aliases: " + aliases.mkString(", "),
                edges.isEmpty() ? "" : "; edges: " + edges.mkString(", "));
    }

    static ClusterOutcome interpret(List<GraphEntity> cluster, ClusterResponse response) {
        val byRef = cluster.zipWithIndex().toMap(pair -> ref(pair._2), pair -> pair._1);
        val groups = listOf(response.groups()).filter(Objects::nonNull);
        val usage = groups.flatMap(ClusterPrompter::refs).groupBy(Function1.identity()).mapValues(List::size);
        val partition = groups.partition(group -> refs(group).forAll(ref -> byRef.containsKey(ref) && usage.getOrElse(ref, 0) == 1));
        val merges = partition._1.filter(group -> refs(group).size() >= 2)
                .map(group -> merge(refs(group).flatMap(byRef::get), group.canonicalName()));
        val untrusted = partition._2.flatMap(ClusterPrompter::refs).flatMap(byRef::get);
        val unsure = listOf(response.unsure()).filter(Objects::nonNull).map(String::trim).flatMap(byRef::get);
        val merged = merges.flatMap(ClusterMerge::members).map(GraphEntity::elementId).toSet();
        return new ClusterOutcome(merges, unsure.appendAll(untrusted).distinct().reject(entity -> merged.contains(entity.elementId())));
    }

    private static List<String> refs(ClusterGroup group) {
        return listOf(group.members()).filter(Objects::nonNull).map(String::trim);
    }

    private static <T> List<T> listOf(java.util.List<T> list) {
        return Option.of(list).map(List::ofAll).getOrElse(List.empty());
    }

    private static ClusterMerge merge(List<GraphEntity> members, String canonicalName) {
        val known = members.flatMap(member -> List.ofAll(member.aliases()).prepend(member.name()));
        return new ClusterMerge(members, Option.of(canonicalName).flatMap(name -> ResolutionPrompter.knownName(name, known)));
    }

    private static String ref(int index) {
        return "m" + (index + 1);
    }
}
