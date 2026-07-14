package net.jimmykw.knowledgegraph.api;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import net.jimmykw.knowledgegraph.exception.InvalidFileException;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService.Pass1Result;
import net.jimmykw.knowledgegraph.extract.RelationshipExtractionService;
import net.jimmykw.knowledgegraph.extract.RelationshipExtractionService.Pass2Result;
import net.jimmykw.knowledgegraph.ingest.PdfIngestionService;
import net.jimmykw.knowledgegraph.ingest.PdfIngestionService.IngestionResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
@RestController
public class GraphController {

    private final PdfIngestionService ingestionService;
    private final EntityExtractionService entityExtractionService;
    private final RelationshipExtractionService relationshipExtractionService;

    @PostMapping(value = "/api/knowledge-graph", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GraphSummaryResponse buildGraph(@RequestPart("file") MultipartFile file) {
        validate(file);

        val start = System.nanoTime();
        log.info("Building knowledge graph from '{}'", file.getOriginalFilename());

        val ingestion = ingestionService.ingest(file);

        if (PdfIngestionService.STATUS_SKIPPED_DUPLICATE.equals(ingestion.status())) {
            return skippedResponse(ingestion, start);
        }

        val pass1Start = System.nanoTime();
        val pass1 = entityExtractionService.extract(ingestion.chunks(), ingestion.documentHash());
        log.info("Pass 1 (entity extraction) done in {}ms", elapsed(pass1Start));

        val pass2Start = System.nanoTime();
        val pass2 = relationshipExtractionService.extract(ingestion.chunks(),
                pass1.canonicalIndex(), ingestion.documentHash());
        log.info("Pass 2 (relationship extraction) done in {}ms", elapsed(pass2Start));

        log.info("Knowledge graph built in {}ms", elapsed(start));
        return processedResponse(ingestion, pass1, pass2, start);
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Missing or empty file part.");
        }
        if (!isPdf(file)) {
            throw new InvalidFileException("Only PDF files are accepted.");
        }
    }

    private static boolean isPdf(MultipartFile file) {
        val contentType = file.getContentType();
        val filename = file.getOriginalFilename();
        return (contentType != null && contentType.equalsIgnoreCase("application/pdf"))
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    private static GraphSummaryResponse skippedResponse(IngestionResult ingestion, long startNanos) {
        return new GraphSummaryResponse(ingestion.documentId(), ingestion.documentHash(),
                ingestion.status(), 0, 0, Map.of(), Map.of(), 0, elapsed(startNanos));
    }

    private static GraphSummaryResponse processedResponse(IngestionResult ingestion, Pass1Result pass1,
                                                          Pass2Result pass2, long startNanos) {
        val failedChunks = pass1.failedChunks() + pass2.failedChunks();
        return new GraphSummaryResponse(ingestion.documentId(), ingestion.documentHash(),
                ingestion.status(), pass1.nodeCount(), pass2.relationshipCount(),
                pass1.countsByLabel().toJavaMap(), pass2.countsByType().toJavaMap(),
                failedChunks, elapsed(startNanos));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
