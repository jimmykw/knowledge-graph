package net.jimmykw.knowledgegraph.extract;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.graph.CanonicalIndex;
import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
public class EntityExtractionService {

    public record Pass1Result(CanonicalIndex canonicalIndex, int nodeCount,
                              io.vavr.collection.Map<String, Integer> countsByLabel, int failedChunks) {
    }

    private static final String SYSTEM_PROMPT = """
            You are an information-extraction engine. From the supplied text, extract the salient
            entities (people, organizations, places, concepts, events, technologies, etc.). Choose
            a concise, free-form label for each entity (for example Person, Organization, Location,
            Concept, Event, Technology). Ignore relationships for now and only emit entities. Each
            entity must have a name, a label, and a short description grounded in the text. If the
            text contains no entities, return empty lists.
            """;

    private final ExtractionPrompter prompter;
    private final Neo4jGraphWriter writer;
    private final ExecutorService executor;

    public EntityExtractionService(ExtractionPrompter prompter, Neo4jGraphWriter writer,
                                   AppProperties appProperties) {
        this.prompter = prompter;
        this.writer = writer;
        this.executor = Executors.newFixedThreadPool(appProperties.models().extract().poolSize());
    }

    public Pass1Result extract(List<Document> chunks, String hash) {
        if (chunks.isEmpty()) {
            return new Pass1Result(CanonicalIndex.empty(), 0, HashMap.empty(), 0);
        }

        val total = chunks.length();
        val completed = new AtomicInteger(0);
        log.info("Pass 1: extracting entities from {} chunk(s)...", total);

        val llmStart = System.nanoTime();
        val futures = chunks.map(chunk -> {
            val prompt = buildPrompt(chunk);
            return CompletableFuture.supplyAsync(() -> prompter.callWithRetry(prompt), executor)
                    .whenComplete((result, error) -> logProgress(completed, total, result, error));
        });
        CompletableFuture.allOf(futures.toJavaArray(CompletableFuture[]::new)).join();
        log.info("Pass 1: LLM calls completed in {}ms", (System.nanoTime() - llmStart) / 1_000_000L);

        val results = futures.map(CompletableFuture::join);
        val failedChunks = results.filter(Try::isFailure).length();

        val candidateEntities = results
                .filter(Try::isSuccess)
                .map(Try::get)
                .flatMap(extractionResult -> List.ofAll(extractionResult.entities()));
        log.info("Pass 1: {} candidate entit(ies) collected from successful chunks",
                candidateEntities.length());

        val entityPartition = candidateEntities.partition(EntityExtractionService::isValidEntity);
        entityPartition._2.forEach(entity ->
                log.warn("Rejected entity: name='{}', label='{}' (blank or null name/label)",
                        entity.name(), entity.label()));
        log.info("Pass 1: {} valid entit(ies), {} rejected by validation",
                entityPartition._1.length(), entityPartition._2.length());

        val allEntities = entityPartition._1;

        val deduped = allEntities.foldLeft(
                Tuple.of(HashSet.<Tuple2<String, String>>empty(), List.<ExtractionRecords.ExtractedEntity>empty()),
                EntityExtractionService::dedupeEntity);
        val uniqueEntities = deduped._2.reverse();

        val writeStart = System.nanoTime();
        val idsByIndex = writer.mergeEntities(uniqueEntities, hash);
        val counts = uniqueEntities.foldLeft(HashMap.<String, Integer>empty(),
                (acc, entity) -> acc.put(entity.label(), acc.getOrElse(entity.label(), 0) + 1));
        writer.recordSchema(counts.keySet(), HashSet.empty());
        log.info("Pass 1: {} entit(ies) written to Neo4j in {}ms", uniqueEntities.length(),
                (System.nanoTime() - writeStart) / 1_000_000L);

        val canonicalIndex = uniqueEntities.zipWithIndex().foldLeft(CanonicalIndex.empty(),
                (acc, pair) -> acc.put(pair._1, nodeId(idsByIndex, pair)));
        uniqueEntities.zipWithIndex().forEach(pair -> log.info("Created node: '{}' [{}] (id={})",
                pair._1.name(), pair._1.label(), nodeId(idsByIndex, pair)));

        val entitiesNotCreated = allEntities.length() - uniqueEntities.length();
        log.info("Pass 1 complete: {} entit(ies) created, {} not created (duplicate), {} failed chunk(s)",
                uniqueEntities.length(), entitiesNotCreated, failedChunks);

        return new Pass1Result(canonicalIndex, uniqueEntities.length(), counts, failedChunks);
    }

    private static void logProgress(AtomicInteger completed, int total,
                                     Try<ExtractionRecords.ExtractionResult> result, Throwable error) {
        val done = completed.incrementAndGet();
        if (error != null) {
            log.warn("Pass 1: chunk {}/{} failed — {}", done, total, error.toString());
        } else if (result.isSuccess()) {
            log.info("Pass 1: chunk {}/{} completed ({}%) — {} entities",
                    done, total, done * 100 / total, result.get().entities().size());
        } else {
            log.warn("Pass 1: chunk {}/{} failed — {}", done, total, result.getCause().toString());
        }
    }

    private static Tuple2<HashSet<Tuple2<String, String>>, List<ExtractionRecords.ExtractedEntity>> dedupeEntity(
            Tuple2<HashSet<Tuple2<String, String>>, List<ExtractionRecords.ExtractedEntity>> acc,
            ExtractionRecords.ExtractedEntity entity) {
        val key = Tuple.of(EntityNormalizer.normalize(entity.name()), entity.label());
        if (acc._1.contains(key)) {
            log.debug("Skipped duplicate entity: '{}' [{}] (duplicate in extraction results)",
                    entity.name(), entity.label());
            return acc;
        }
        return Tuple.of(acc._1.add(key), acc._2.prepend(entity));
    }

    private static long nodeId(Map<Integer, Long> idsByIndex,
                               Tuple2<ExtractionRecords.ExtractedEntity, Integer> pair) {
        return idsByIndex.get(pair._2)
                .getOrElseThrow(() -> new IllegalStateException(
                        "Neo4j returned no node id for entity '" + pair._1.name() + "'"));
    }

    private Prompt buildPrompt(Document chunk) {
        val userText = """
                Extract the entities from the following text.
                Text:
                ---
                %s
                ---
                %s
                """.formatted(chunk.getText(), prompter.getFormat());
        return new Prompt(java.util.List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userText)));
    }

    private static boolean isValidEntity(ExtractionRecords.ExtractedEntity entity) {
        return entity != null
                && entity.name() != null && !entity.name().isBlank()
                && entity.label() != null && !entity.label().isBlank();
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
