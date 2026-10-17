package net.jimmykw.knowledgegraph.ingest;

import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.multipart.MultipartFile;

import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.InvalidFileException;
import net.jimmykw.knowledgegraph.exception.MaxPagesExceededException;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;

import io.vavr.collection.List;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class PdfIngestionService {

    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE";

    private static final String UNREADABLE_PDF = "PDF could not be read (corrupt, encrypted or not a PDF).";

    public record IngestionResult(String documentId, String documentHash, String status,
                                  List<Document> chunks) {
    }

    private final Neo4jGraphWriter writer;
    private final AppProperties appProperties;

    public IngestionResult ingest(MultipartFile file) {
        val bytes = Try.of(file::getBytes)
                .getOrElseThrow(e -> new IllegalStateException("Failed to read uploaded PDF bytes", e));
        val hash = sha256(bytes);

        val existing = writer.findDocumentId(hash);
        if (existing.isDefined()) {
            log.info("Duplicate document detected (hash={}); skipping extraction", hash);
            return new IngestionResult(String.valueOf(existing.get()), hash, STATUS_SKIPPED_DUPLICATE, List.empty());
        }

        log.info("Reading PDF: '{}', {} bytes", file.getOriginalFilename(), bytes.length);
        val pages = readPages(bytes).getOrElseThrow(e -> new InvalidFileException(UNREADABLE_PDF, e));
        if (pages.size() > appProperties.maxPages()) {
            throw new MaxPagesExceededException("PDF has " + pages.size() + " pages, which exceeds the maximum of "
                    + appProperties.maxPages() + ".");
        }

        val chunks = splitChunks(pages);
        log.info("PDF ingested: hash={}, pages={}, chunks={}", hash, pages.size(), chunks.length());
        val docId = writer.mergeDocument(hash, file.getOriginalFilename());

        return new IngestionResult(String.valueOf(docId), hash, STATUS_PROCESSED, chunks);
    }

    private Try<List<Document>> readPages(byte[] bytes) {
        val config = PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build();
        return Try.of(() -> new PagePdfDocumentReader(new ByteArrayResource(bytes), config).get())
                .map(List::ofAll)
                .onFailure(e -> log.warn("PDF could not be read (corrupt, encrypted or not a PDF): {}", e.getMessage()));
    }

    private List<Document> splitChunks(List<Document> pages) {
        val splitter = TokenTextSplitter.builder().withChunkSize(appProperties.chunkTokens()).build();
        return List.ofAll(splitter.apply(pages.toJavaList()));
    }

    private static String sha256(byte[] bytes) {
        return Try.of(() -> MessageDigest.getInstance("SHA-256").digest(bytes))
                .map(HexFormat.of()::formatHex)
                .getOrElseThrow(e -> new IllegalStateException("SHA-256 algorithm not available", e));
    }
}
