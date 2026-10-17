package net.jimmykw.knowledgegraph.resolve;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Classification;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.GroupQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Question;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Decides which entities need the LLM. Without it: a sole exact (name, alias or match-key) candidate with the same label is
 * merged, an entity with no candidates and no batch sibling sharing its match key is new, and batch siblings that share a
 * match key and a label become one new node. Everything else is a {@link Question}.
 */
@UtilityClass
public class ResolutionClassifier {

    public static Classification classify(List<ExtractedEntity> entities, Map<Integer, List<Candidate>> candidatesByIdx) {
        val idxs = List.range(0, entities.size());
        val partition = idxs.partition(idx -> candidatesByIdx.get(idx).exists(List::nonEmpty));
        val withCandidates = partition._1.map(idx -> classifyAgainstGraph(idx, entities.get(idx), candidatesByIdx.getOrElse(idx, List.empty())));
        val withoutCandidates = partition._2
                .groupBy(idx -> EntityNormalizer.matchKey(entities.get(idx).name()))
                .values()
                .toList()
                .sortBy(List::head)
                .map(members -> classifyWithinBatch(members, entities));
        return withCandidates.appendAll(withoutCandidates)
                .foldLeft(new Classification(Resolved.empty(), List.empty()), ResolutionClassifier::add);
    }

    private static Classification add(Classification acc, Classification next) {
        return new Classification(acc.automatic().combine(next.automatic()), acc.questions().appendAll(next.questions()));
    }

    private static Classification classifyAgainstGraph(int idx, ExtractedEntity entity, List<Candidate> candidates) {
        val exact = candidates.filter(Candidate::exact);
        return exact.size() == 1 && exact.exists(candidate -> candidate.label().equals(entity.label()))
                ? automatic(new Resolved(List.of(new MergeExisting(idx, exact.head(), Option.none(), reason(entity, exact.head()))),
                        List.empty(), HashSet.empty()))
                : question(new EntityQuestion(idx, candidates));
    }

    private static Classification classifyWithinBatch(List<Integer> members, List<ExtractedEntity> entities) {
        val labels = members.map(idx -> entities.get(idx).label()).distinct();
        return members.size() == 1 || labels.size() == 1
                ? automatic(new Resolved(List.empty(), List.of(new NewGroup(members, Option.none())), HashSet.empty()))
                : question(new GroupQuestion(members));
    }

    /** {@code exact} when the name is the node's name or one of its aliases, {@code match_key} when only the stripped forms agree. */
    static String reason(ExtractedEntity entity, Candidate candidate) {
        val nameNorm = EntityNormalizer.normalize(entity.name());
        return candidate.aliases().prepend(candidate.name()).map(EntityNormalizer::normalize).contains(nameNorm) ? "exact" : "match_key";
    }

    private static Classification automatic(Resolved resolved) {
        return new Classification(resolved, List.empty());
    }

    private static Classification question(Question question) {
        return new Classification(Resolved.empty(), List.of(question));
    }
}
