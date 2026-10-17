package net.jimmykw.knowledgegraph.resolve;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.GroupQuestion;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Question;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionDecision;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionResponse;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;
import net.jimmykw.knowledgegraph.util.JsonPrompter;

import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.collection.Set;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Asks the extraction model to resolve one batch of {@link Question}s. The model only sees short references ({@code n1} for new
 * entities, {@code c1} for existing nodes); they are mapped back here, and anything it was not offered leaves that entity unresolved.
 */
@Slf4j
@RequiredArgsConstructor
public class ResolutionPrompter {

    static final String SYSTEM_PROMPT = """
            You resolve entities extracted from a new document against an existing knowledge graph.
            For each new entity (n1, n2, ...) decide whether it is the same real-world thing as one of
            the options listed under it. Options are existing graph nodes (c1, c2, ...) or other new
            entities from the same document (n...). Use the names, labels, aliases, descriptions and
            neighbouring edges. Homonyms are different entities: a person and a product both called
            Watson, or Thomas J. Watson Sr. and Thomas J. Watson Jr. When you are not sure, do not match.

            Reply with exactly one decision per new entity:
            - "entity": the new entity's reference, for example "n1".
            - "match": the reference of the matching option, or null when none matches.
            - "canonicalName": when matched, the fullest unambiguous name of that entity, chosen from
              the new entity's name, the option's name or the option's aliases; otherwise null.
            Never invent references; use only the ones offered.
            """;

    private final JsonPrompter<ResolutionResponse> prompter;

    /** Fails only when the call or the parse fails; per-entity problems come back as unresolved entities. */
    public Try<Resolved> decide(List<Question> batch, List<ExtractedEntity> entities) {
        val refs = References.of(batch);
        return prompter.callWithRetry(buildPrompt(batch, entities, refs))
                .map(response -> interpret(batch, entities, refs, decisionsByEntity(response)));
    }

    Prompt buildPrompt(List<Question> batch, List<ExtractedEntity> entities, References refs) {
        val userText = """
                Decide for each new entity below.

                %s
                ---
                %s
                """.formatted(batch.map(question -> render(question, entities, refs)).mkString("\n\n"), prompter.getFormat());
        return new Prompt(java.util.List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userText)));
    }

    private static String render(Question question, List<ExtractedEntity> entities, References refs) {
        return switch (question) {
            case EntityQuestion entityQuestion -> renderEntity(entityQuestion.idx(), entities, refs) + "\n  options:\n"
                    + entityQuestion.candidates().map(candidate -> "    " + renderCandidate(candidate, refs)).mkString("\n");
            case GroupQuestion group -> group.members()
                    .map(idx -> renderEntity(idx, entities, refs) + "\n  options: "
                            + group.members().remove(idx).map(refs::entity).mkString(", "))
                    .mkString("\n");
        };
    }

    private static String renderEntity(int idx, List<ExtractedEntity> entities, References refs) {
        val entity = entities.get(idx);
        return "%s: \"%s\" [%s] - %s".formatted(refs.entity(idx), entity.name(), entity.label(), Option.of(entity.description()).getOrElse(""));
    }

    private static String renderCandidate(Candidate candidate, References refs) {
        val aliases = candidate.aliases().remove(candidate.name());
        return "%s: \"%s\" [%s] - %s%s%s".formatted(refs.candidate(candidate.elementId()), candidate.name(), candidate.label(),
                candidate.description(),
                aliases.isEmpty() ? "" : "; aliases: " + aliases.mkString(", "),
                candidate.neighbours().isEmpty() ? "" : "; edges: " + candidate.neighbours().mkString(", "));
    }

    private static Map<String, ResolutionDecision> decisionsByEntity(ResolutionResponse response) {
        return Option.of(response.decisions()).map(List::ofAll).getOrElse(List.empty())
                .filter(decision -> decision != null && decision.entity() != null)
                .foldLeft(HashMap.empty(), (acc, decision) -> acc.containsKey(decision.entity().trim())
                        ? acc
                        : acc.put(decision.entity().trim(), decision));
    }

    private static Resolved interpret(List<Question> batch, List<ExtractedEntity> entities, References refs,
                                      Map<String, ResolutionDecision> decisions) {
        return batch.map(question -> switch (question) {
            case EntityQuestion entityQuestion -> interpretEntity(entityQuestion, entities, refs, decisions);
            case GroupQuestion group -> interpretGroup(group, entities, refs, decisions);
        }).foldLeft(Resolved.empty(), Resolved::combine);
    }

    private static Resolved interpretEntity(EntityQuestion question, List<ExtractedEntity> entities, References refs,
                                            Map<String, ResolutionDecision> decisions) {
        val entity = entities.get(question.idx());
        val decision = decisions.get(refs.entity(question.idx()));
        if (decision.isEmpty()) {
            log.warn("Resolution: no decision for '{}' [{}]; created as new", entity.name(), entity.label());
            return Resolved.unresolved(List.of(question.idx()));
        }
        val match = decision.flatMap(ResolutionPrompter::matchRef);
        if (match.isEmpty()) {
            return new Resolved(List.empty(), List.of(new NewGroup(List.of(question.idx()), Option.none())), HashSet.empty());
        }
        return match.flatMap(ref -> question.candidates().find(candidate -> refs.candidate(candidate.elementId()).equals(ref)))
                .map(candidate -> merged(question.idx(), entity, candidate, decision.flatMap(ResolutionPrompter::canonicalName)))
                .getOrElse(() -> {
                    log.warn("Resolution: '{}' [{}] matched '{}', which was not offered; created as new", entity.name(), entity.label(),
                            match.getOrElse(""));
                    return Resolved.unresolved(List.of(question.idx()));
                });
    }

    private static Resolved merged(int idx, ExtractedEntity entity, Candidate candidate, Option<String> proposed) {
        val known = candidate.aliases().prepend(candidate.name()).prepend(entity.name());
        val canonical = proposed.flatMap(name -> knownName(name, known));
        proposed.filter(name -> canonical.isEmpty()).forEach(name ->
                log.warn("Resolution: canonical name '{}' for '{}' is not a known name; keeping '{}'", name, entity.name(), candidate.name()));
        return new Resolved(List.of(new MergeExisting(idx, candidate, canonical, "llm")), List.empty(), HashSet.empty());
    }

    /** Members that the LLM matched to each other (directly or through another member) become one new node. */
    private static Resolved interpretGroup(GroupQuestion group, List<ExtractedEntity> entities, References refs,
                                           Map<String, ResolutionDecision> decisions) {
        val byRef = group.members().toMap(refs::entity, idx -> idx);
        val answers = group.members().map(idx -> new GroupAnswer(idx, decisions.get(refs.entity(idx)),
                decisions.get(refs.entity(idx)).flatMap(ResolutionPrompter::matchRef)));
        val invalid = answers.filter(answer -> answer.decision().isEmpty()
                        || answer.match().exists(ref -> !byRef.containsKey(ref) || byRef.get(ref).contains(answer.idx())))
                .map(GroupAnswer::idx);
        invalid.forEach(idx -> log.warn("Resolution: no valid decision for '{}' [{}]; created as new", entities.get(idx).name(),
                entities.get(idx).label()));
        val links = answers.filter(answer -> !invalid.contains(answer.idx()))
                .flatMap(answer -> answer.match().flatMap(byRef::get).map(target -> List.of(answer.idx(), target)));
        val components = links.foldLeft(group.members().map(idx -> (Set<Integer>) HashSet.of(idx)), ResolutionPrompter::link);
        val newGroups = components.map(component -> List.ofAll(component).sorted())
                .sortBy(List::head)
                .map(members -> new NewGroup(members, groupName(members, answers, entities)));
        return new Resolved(List.empty(), newGroups, HashSet.ofAll(invalid));
    }

    private record GroupAnswer(int idx, Option<ResolutionDecision> decision, Option<String> match) {
    }

    private static List<Set<Integer>> link(List<Set<Integer>> components, List<Integer> pair) {
        val joined = components.filter(component -> component.contains(pair.head()) || component.contains(pair.last()));
        return components.removeAll(joined).append(joined.foldLeft((Set<Integer>) HashSet.<Integer>empty(), Set::union));
    }

    private static Option<String> groupName(List<Integer> members, List<GroupAnswer> answers, List<ExtractedEntity> entities) {
        val known = members.map(idx -> entities.get(idx).name());
        return members.size() < 2
                ? Option.none()
                : answers.filter(answer -> members.contains(answer.idx()))
                        .flatMap(answer -> answer.decision().flatMap(ResolutionPrompter::canonicalName))
                        .flatMap(name -> knownName(name, known))
                        .headOption();
    }

    /** The known name the proposal equals after normalization, keeping the known casing. */
    public static Option<String> knownName(String proposed, List<String> known) {
        val normalized = EntityNormalizer.normalize(proposed);
        return known.find(name -> EntityNormalizer.normalize(name).equals(normalized));
    }

    private static Option<String> matchRef(ResolutionDecision decision) {
        return Option.of(decision.match()).map(String::trim).filter(ref -> !ref.isEmpty() && !ref.equalsIgnoreCase("null"));
    }

    private static Option<String> canonicalName(ResolutionDecision decision) {
        return Option.of(decision.canonicalName()).map(String::trim).filter(name -> !name.isEmpty() && !name.equalsIgnoreCase("null"));
    }

    /** {@code n<k>} per entity and {@code c<k>} per distinct candidate node, numbered in batch order. */
    record References(Map<Integer, String> entities, Map<String, String> candidates) {

        static References of(List<Question> batch) {
            val entityRefs = batch.flatMap(Question::entityIdxs).distinct().zipWithIndex()
                    .toMap(pair -> pair._1, pair -> "n" + (pair._2 + 1));
            val candidateRefs = batch.flatMap(question -> question instanceof EntityQuestion entityQuestion
                            ? entityQuestion.candidates().map(Candidate::elementId)
                            : List.<String>empty())
                    .distinct()
                    .zipWithIndex()
                    .toMap(pair -> pair._1, pair -> "c" + (pair._2 + 1));
            return new References(entityRefs, candidateRefs);
        }

        String entity(int idx) {
            return entities.getOrElse(idx, "");
        }

        String candidate(String elementId) {
            return candidates.getOrElse(elementId, "");
        }
    }
}
