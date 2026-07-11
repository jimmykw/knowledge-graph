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
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;

import io.vavr.Tuple;
import io.vavr.Tuple3;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
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

        val futures = chunks.map(chunk -> {
            val prompt = buildPrompt(chunk, canonicalList);
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
                log.debug("Pass 2 chunk {}: success — {} relationships",
                        index, extraction.relationships().size());
            } else {
                log.warn("Pass 2 chunk {}: failed — {}", index, result.getCause().toString());
            }
        });

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

        val acc = allRelationships.foldLeft(
                Tuple.of(HashMap.<String, Integer>empty(),
                        HashSet.<Tuple3<Long, Long, String>>empty(),
                        0),
                (accumulator, rel) -> mergeRelationship(accumulator, rel, canonicalIndex));

        val notCreated = allRelationships.length() - acc._3;
        log.info("Pass 2 complete: {} relationship(s) created, {} not created (dropped/duplicate), "
                + "{} failed chunk(s)", acc._3, notCreated, failedChunks);
        return new Pass2Result(acc._3, acc._1, failedChunks);
    }

    private Tuple3<HashMap<String, Integer>, HashSet<Tuple3<Long, Long, String>>, Integer> mergeRelationship(
            Tuple3<HashMap<String, Integer>, HashSet<Tuple3<Long, Long, String>>, Integer> accumulator,
            ExtractionRecords.ExtractedRelationship rel, CanonicalIndex canonicalIndex) {
        val source = canonicalIndex.lookup(rel.sourceName(), rel.sourceLabel());
        val target = canonicalIndex.lookup(rel.targetName(), rel.targetLabel());
        if (source.isEmpty() || target.isEmpty()) {
            log.warn("Pass 2: dropped relationship {}:{} -[{}]-> {}:{} (endpoint not in canonical index)",
                    rel.sourceName(), rel.sourceLabel(), rel.type(), rel.targetName(), rel.targetLabel());
            return accumulator;
        }
        val key = Tuple.of(source.get(), target.get(), rel.type());
        if (accumulator._2.contains(key)) {
            log.debug("Skipped duplicate relationship: {} -[{}]-> {} (already created)",
                    rel.sourceName(), rel.type(), rel.targetName());
            return accumulator;
        }
        val relId = writer.mergeRelationship(source.get(), target.get(), rel.type(), rel.description());
        log.info("Created relationship: {} -[{}]-> {} (id={})",
                rel.sourceName(), rel.type(), rel.targetName(), relId);
        val counts = accumulator._1.put(rel.type(), accumulator._1.getOrElse(rel.type(), 0) + 1);
        val seen = accumulator._2.add(key);
        return Tuple.of(counts, seen, accumulator._3 + 1);
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
