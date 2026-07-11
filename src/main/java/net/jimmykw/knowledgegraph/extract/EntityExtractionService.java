package net.jimmykw.knowledgegraph.extract;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import io.vavr.Tuple3;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
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
        this.executor = Executors.newFixedThreadPool(appProperties.poolSize());
    }

    public Pass1Result extract(List<Document> chunks, String hash) {
        if (chunks.isEmpty()) {
            return new Pass1Result(CanonicalIndex.empty(), 0, HashMap.empty(), 0);
        }

        val futures = chunks.map(chunk -> {
            val prompt = buildPrompt(chunk);
            return CompletableFuture.supplyAsync(() -> prompter.callWithRetry(prompt), executor);
        });
        CompletableFuture.allOf(futures.toJavaArray(CompletableFuture[]::new)).join();

        val results = futures.map(CompletableFuture::join);
        val failedChunks = results.filter(Try::isFailure).length();

        results.zipWithIndex().forEach(pair -> {
            val result = pair._1;
            val index = pair._2;
            if (result.isSuccess()) {
                val extraction = result.get();
                log.debug("Pass 1 chunk {}: success — {} entities, {} relationships",
                        index, extraction.entities().size(), extraction.relationships().size());
            } else {
                log.warn("Pass 1 chunk {}: failed — {}", index, result.getCause().toString());
            }
        });

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

        val acc = allEntities.foldLeft(
                Tuple.of(CanonicalIndex.empty(), HashMap.<String, Integer>empty(), 0),
                (accumulator, entity) -> mergeEntity(accumulator, entity, hash));

        val entitiesNotCreated = allEntities.length() - acc._3;
        log.info("Pass 1 complete: {} entit(ies) created, {} not created (duplicate), {} failed chunk(s)",
                acc._3, entitiesNotCreated, failedChunks);

        return new Pass1Result(acc._1, acc._3, acc._2, failedChunks);
    }

    private Tuple3<CanonicalIndex, HashMap<String, Integer>, Integer> mergeEntity(
            Tuple3<CanonicalIndex, HashMap<String, Integer>, Integer> accumulator,
            ExtractionRecords.ExtractedEntity entity, String hash) {
        if (accumulator._1.lookup(entity.name(), entity.label()).isDefined()) {
            log.debug("Skipped duplicate entity: '{}' [{}] (already in canonical index)",
                    entity.name(), entity.label());
            return accumulator;
        }
        val nodeId = writer.mergeEntity(entity.name(), EntityNormalizer.normalize(entity.name()),
                entity.label(), entity.description(), hash);
        log.info("Created node: '{}' [{}] (id={})", entity.name(), entity.label(), nodeId);
        val index = accumulator._1.put(entity, nodeId);
        val counts = accumulator._2.put(entity.label(), accumulator._2.getOrElse(entity.label(), 0) + 1);
        return Tuple.of(index, counts, accumulator._3 + 1);
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
