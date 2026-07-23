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
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
public class RelationshipExtractionService {

    public record Pass2Result(int relationshipCount,
                              io.vavr.collection.Map<String, Integer> countsByType, int failedChunks) {
    }

    private static final String SYSTEM_PROMPT = """
            You are an information-extraction engine. You are given a chunk of text together with the
            canonical list of entities already discovered in the document. Emit ONLY relationships
            that are directly supported by the chunk's text and whose both endpoints appear in the
            canonical entity list (matched by name and label, case-insensitive). Choose a concise,
            free-form relationship type for each (for example WORKS_FOR, LOCATED_IN, FOUNDED,
            PART_OF, MEMBER_OF). Each relationship has source and target name and label, a type, and
            a short description. If the text supports no relationship between canonical entities,
            return an empty relationships list.
            """;

    private final ExtractionPrompter prompter;
    private final Neo4jGraphWriter writer;
    private final ExecutorService executor;

    public RelationshipExtractionService(ExtractionPrompter prompter, Neo4jGraphWriter writer,
                                         AppProperties appProperties) {
        this.prompter = prompter;
        this.writer = writer;
        this.executor = Executors.newFixedThreadPool(appProperties.poolSize());
    }

    public Pass2Result extract(List<Document> chunks, CanonicalIndex canonicalIndex, String hash) {
        if (chunks.isEmpty() || canonicalIndex.size() == 0) {
            log.info("Pass 2 skipped: {} chunk(s), {} canonical entit(ies)", chunks.length(), canonicalIndex.size());
            return new Pass2Result(0, HashMap.empty(), 0);
        }

    val canonicalList = renderCanonicalList(canonicalIndex);
    log.info("Pass 2: processing {} chunk(s) with {} canonical entities", chunks.length(), canonicalIndex.size());
    log.info("Pass 2 canonical entities:\n{}", canonicalIndex.entities()
            .map(entity -> "  " + entity.name() + " [" + entity.label() + "]")
            .mkString("\n"));

        val total = chunks.length();
        val completed = new AtomicInteger(0);
        log.info("Pass 2: extracting relationships from {} chunk(s)...", total);

        val llmStart = System.nanoTime();
        val futures = chunks.map(chunk -> {
            val prompt = buildPrompt(chunk, canonicalList);
            return CompletableFuture.supplyAsync(() -> prompter.callWithRetry(prompt), executor)
                    .whenComplete((result, error) -> logProgress(completed, total, result, error));
        });
        CompletableFuture.allOf(futures.toJavaArray(CompletableFuture[]::new)).join();
        log.info("Pass 2: LLM calls completed in {}ms", (System.nanoTime() - llmStart) / 1_000_000L);

        val results = futures.map(CompletableFuture::join);
        val failedChunks = results.filter(Try::isFailure).length();

        val candidateRelationships = results
                .filter(Try::isSuccess)
                .map(Try::get)
                .flatMap(extractionResult -> List.ofAll(extractionResult.relationships()));
        log.info("Pass 2: {} candidate relationship(s) collected from successful chunks",
                candidateRelationships.length());

        val relationshipPartition = candidateRelationships
                .partition(RelationshipExtractionService::isValidRelationship);
        relationshipPartition._2.forEach(rel ->
                log.warn("Rejected relationship: {}:{} -[{}]-> {}:{} (blank or null fields)",
                        rel.sourceName(), rel.sourceLabel(), rel.type(),
                        rel.targetName(), rel.targetLabel()));
        log.info("Pass 2: {} valid relationship(s), {} rejected by validation",
                relationshipPartition._1.length(), relationshipPartition._2.length());

        val allRelationships = relationshipPartition._1;

        val resolved = allRelationships.foldLeft(
                Tuple.of(HashSet.<Tuple3<Long, Long, String>>empty(),
                        List.<ExtractionRecords.ResolvedRelationship>empty()),
                (acc, rel) -> resolveRelationship(acc, rel, canonicalIndex));
        val uniqueRelationships = resolved._2.reverse();

        val writeStart = System.nanoTime();
        val idsByIndex = writer.mergeRelationships(uniqueRelationships);
        val counts = uniqueRelationships.foldLeft(HashMap.<String, Integer>empty(),
                (acc, rel) -> acc.put(rel.type(), acc.getOrElse(rel.type(), 0) + 1));
        writer.recordSchema(HashSet.empty(), counts.keySet());
        log.info("Pass 2: {} relationship(s) written to Neo4j in {}ms", uniqueRelationships.length(),
                (System.nanoTime() - writeStart) / 1_000_000L);

        uniqueRelationships.zipWithIndex().forEach(pair ->
                log.info("Created relationship: {} -[{}]-> {} (id={})", pair._1.sourceName(), pair._1.type(),
                        pair._1.targetName(), relId(idsByIndex, pair)));

        val notCreated = allRelationships.length() - uniqueRelationships.length();
        log.info("Pass 2 complete: {} relationship(s) created, {} not created (dropped/duplicate), "
                + "{} failed chunk(s)", uniqueRelationships.length(), notCreated, failedChunks);
        return new Pass2Result(uniqueRelationships.length(), counts, failedChunks);
    }

    private static void logProgress(AtomicInteger completed, int total,
                                     Try<ExtractionRecords.ExtractionResult> result, Throwable error) {
        val done = completed.incrementAndGet();
        if (error != null) {
            log.warn("Pass 2: chunk {}/{} failed — {}", done, total, error.toString());
        } else if (result.isSuccess()) {
            log.info("Pass 2: chunk {}/{} completed ({}%) — {} relationships",
                    done, total, done * 100 / total, result.get().relationships().size());
        } else {
            log.warn("Pass 2: chunk {}/{} failed — {}", done, total, result.getCause().toString());
        }
    }

    private static Tuple2<HashSet<Tuple3<Long, Long, String>>, List<ExtractionRecords.ResolvedRelationship>> resolveRelationship(
            Tuple2<HashSet<Tuple3<Long, Long, String>>, List<ExtractionRecords.ResolvedRelationship>> acc,
            ExtractionRecords.ExtractedRelationship rel, CanonicalIndex canonicalIndex) {
        return canonicalIndex.lookup(rel.sourceName(), rel.sourceLabel())
                .flatMap(sourceId -> canonicalIndex.lookup(rel.targetName(), rel.targetLabel())
                        .map(targetId -> Tuple.of(sourceId, targetId)))
                .map(endpoints -> addResolved(acc, rel, endpoints))
                .getOrElse(() -> {
                    log.warn("Pass 2: dropped relationship {}:{} -[{}]-> {}:{} (endpoint not in canonical index)",
                            rel.sourceName(), rel.sourceLabel(), rel.type(), rel.targetName(), rel.targetLabel());
                    return acc;
                });
    }

    private static Tuple2<HashSet<Tuple3<Long, Long, String>>, List<ExtractionRecords.ResolvedRelationship>> addResolved(
            Tuple2<HashSet<Tuple3<Long, Long, String>>, List<ExtractionRecords.ResolvedRelationship>> acc,
            ExtractionRecords.ExtractedRelationship rel, Tuple2<Long, Long> endpoints) {
        val key = Tuple.of(endpoints._1, endpoints._2, rel.type());
        if (acc._1.contains(key)) {
            log.debug("Skipped duplicate relationship: {} -[{}]-> {} (already created)",
                    rel.sourceName(), rel.type(), rel.targetName());
            return acc;
        }
        val resolved = new ExtractionRecords.ResolvedRelationship(endpoints._1, endpoints._2, rel.type(),
                rel.description(), rel.sourceName(), rel.targetName());
        return Tuple.of(acc._1.add(key), acc._2.prepend(resolved));
    }

    private static long relId(Map<Integer, Long> idsByIndex,
                              Tuple2<ExtractionRecords.ResolvedRelationship, Integer> pair) {
        return idsByIndex.get(pair._2)
                .getOrElseThrow(() -> new IllegalStateException(
                        "Neo4j returned no relationship id for '" + pair._1.sourceName() + " -[" + pair._1.type()
                                + "]-> " + pair._1.targetName() + "'"));
    }

    private Prompt buildPrompt(Document chunk, String canonicalList) {
        val userText = """
                Canonical entities already discovered in the document (use these as endpoints):
                %s
                ---
                Extract relationships supported by the following text, using only the canonical
                entities above as endpoints.
                Text:
                ---
                %s
                ---
                %s
                """.formatted(canonicalList, chunk.getText(), prompter.getFormat());
        return new Prompt(java.util.List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userText)));
    }

    private static String renderCanonicalList(CanonicalIndex index) {
        return index.entities()
                .map(entity -> "- " + entity.name() + " [" + entity.label() + "]")
                .mkString("\n");
    }

    private static boolean isValidRelationship(ExtractionRecords.ExtractedRelationship rel) {
        return rel != null
                && rel.sourceName() != null && !rel.sourceName().isBlank()
                && rel.sourceLabel() != null && !rel.sourceLabel().isBlank()
                && rel.targetName() != null && !rel.targetName().isBlank()
                && rel.targetLabel() != null && !rel.targetLabel().isBlank()
                && rel.type() != null && !rel.type().isBlank();
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
