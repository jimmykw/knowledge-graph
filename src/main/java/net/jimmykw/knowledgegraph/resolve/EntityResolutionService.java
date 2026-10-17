package net.jimmykw.knowledgegraph.resolve;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;

import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService.ExtractedEntities;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService.Pass1Result;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.graph.CanonicalIndex;
import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.graph.FulltextQueries;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityProbe;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.MergeExisting;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewGroup;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Question;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Resolved;

import io.vavr.Function1;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Resolves pass 1's entities against the whole graph and writes them: exact matches merge without the LLM, ambiguous ones
 * go to {@link ResolutionPrompter} in parallel batches, and a failed batch creates its entities as new. One lock serializes
 * resolution and the entity write across uploads (extraction still runs in parallel), so a second upload sees the first's nodes.
 */
@Slf4j
public class EntityResolutionService {

    private final Neo4jGraphWriter writer;
    private final ResolutionPrompter prompter;
    private final AppProperties.Resolution settings;
    private final ExecutorService executor;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public EntityResolutionService(Neo4jGraphWriter writer, ResolutionPrompter prompter, AppProperties appProperties) {
        this.writer = writer;
        this.prompter = prompter;
        this.settings = appProperties.resolution();
        this.executor = Executors.newFixedThreadPool(appProperties.models().extract().poolSize());
    }

    public Pass1Result resolve(ExtractedEntities extracted, String hash) {
        lock.lock();
        return Try.of(() -> resolveLocked(extracted.entities(), extracted.failedChunks(), hash))
                .andFinally(lock::unlock)
                .getOrElseThrow(failure -> failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure));
    }

    private Pass1Result resolveLocked(List<ExtractedEntity> entities, int failedChunks, String hash) {
        if (entities.isEmpty()) {
            return new Pass1Result(CanonicalIndex.empty(), 0, HashMap.empty(), failedChunks);
        }
        ensureSchemaOnce();
        val start = System.nanoTime();

        val candidates = writer.findCandidates(probes(entities), settings.candidateLimit(), settings.minFulltextScore());
        val classification = ResolutionClassifier.classify(entities, candidates);
        val llmEntities = classification.questions().flatMap(Question::entityIdxs).size();
        log.info("Resolution: {} entities → {} exact, {} LLM, {} new", entities.size(), classification.automatic().merges().size(),
                llmEntities, entities.size() - classification.automatic().merges().size() - llmEntities);

        val resolved = classification.automatic().combine(decide(classification.questions(), entities));
        val planned = ResolutionPlanner.plan(entities, resolved);
        val ids = writer.applyResolution(planned.plan(), hash);
        writer.recordSchema(planned.countsByLabel().keySet(), HashSet.of(Neo4jGraphWriter.MENTIONS));

        resolved.merges().forEach(merge -> logMerge(merge, entities, ids));
        log.info("Resolution: {} merged, {} created, {} unresolved in {}ms", planned.merged(), planned.created(),
                resolved.unresolved().size(), (System.nanoTime() - start) / 1_000_000L);

        val canonicalIndex = entities.zipWithIndex()
                .foldLeft(CanonicalIndex.empty(), (acc, pair) -> acc.put(pair._1, nodeId(ids, pair._2, pair._1)));
        return new Pass1Result(canonicalIndex, planned.created() + planned.merged(), planned.countsByLabel(), failedChunks,
                planned.created(), planned.merged(), resolved.unresolved().size());
    }

    /** Startup creates the schema when it can; this covers a Neo4j that was down then. Idempotent, so a race only repeats it. */
    private void ensureSchemaOnce() {
        if (!schemaReady.get()) {
            writer.ensureSchema();
            schemaReady.set(true);
        }
    }

    private static List<EntityProbe> probes(List<ExtractedEntity> entities) {
        return entities.zipWithIndex().map(pair -> new EntityProbe(pair._2, pair._1.name(), EntityNormalizer.normalize(pair._1.name()),
                EntityNormalizer.matchKey(pair._1.name()), pair._1.label(), FulltextQueries.forName(pair._1.name())));
    }

    private Resolved decide(List<Question> questions, List<ExtractedEntity> entities) {
        if (questions.isEmpty()) {
            return Resolved.empty();
        }
        val batches = batches(questions, settings.batchSize());
        log.info("Resolution: {} LLM batch(es)", batches.size());
        val futures = batches.map(batch -> CompletableFuture.supplyAsync(() -> decideBatch(batch, entities), executor));
        CompletableFuture.allOf(futures.toJavaArray(CompletableFuture[]::new)).join();
        return futures.map(CompletableFuture::join).foldLeft(Resolved.empty(), Resolved::combine);
    }

    private Resolved decideBatch(List<Question> batch, List<ExtractedEntity> entities) {
        val idxs = batch.flatMap(Question::entityIdxs);
        return Try.of(() -> prompter.decide(batch, entities))
                .flatMap(Function1.identity())
                .map(resolved -> withUncovered(resolved, idxs))
                .onFailure(failure -> log.warn("Resolution: LLM batch of {} entit(ies) failed; creating them as new — {}",
                        idxs.size(), failure.toString()))
                .getOrElse(() -> Resolved.unresolved(idxs));
    }

    /** Every entity of the batch must end up somewhere: one the result does not place is created as new and counted unresolved. */
    private static Resolved withUncovered(Resolved resolved, List<Integer> idxs) {
        val covered = resolved.merges().map(MergeExisting::idx).appendAll(resolved.newGroups().flatMap(NewGroup::members)).toSet();
        return resolved.combine(Resolved.unresolved(idxs.reject(covered::contains)));
    }

    /** Packs questions into batches of at most {@code batchSize} entities; a group larger than that gets a batch of its own. */
    static List<List<Question>> batches(List<Question> questions, int batchSize) {
        return questions.foldLeft(List.<List<Question>>empty(), (acc, question) -> acc.lastOption()
                        .filter(last -> entityCount(last) + question.entityIdxs().size() <= batchSize)
                        .map(last -> acc.init().append(last.append(question)))
                        .getOrElse(() -> acc.append(List.of(question))));
    }

    private static int entityCount(List<Question> batch) {
        return batch.map(question -> question.entityIdxs().size()).sum().intValue();
    }

    private static void logMerge(MergeExisting merge, List<ExtractedEntity> entities, Map<Integer, String> ids) {
        log.info("Merged '{}' into '{}' (id={}, {})", entities.get(merge.idx()).name(), merge.canonicalName().getOrElse(merge.target().name()),
                ids.getOrElse(merge.idx(), merge.target().elementId()), merge.reason());
    }

    private static String nodeId(Map<Integer, String> ids, int idx, ExtractedEntity entity) {
        return ids.get(idx).getOrElseThrow(() -> new IllegalStateException("Neo4j returned no node id for entity '" + entity.name() + "'"));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        val terminated = Try.of(() -> executor.awaitTermination(5, TimeUnit.SECONDS))
                .recover(InterruptedException.class, e -> {
                    Thread.currentThread().interrupt();
                    return false;
                })
                .get();
        if (!terminated) {
            executor.shutdownNow();
        }
    }
}
