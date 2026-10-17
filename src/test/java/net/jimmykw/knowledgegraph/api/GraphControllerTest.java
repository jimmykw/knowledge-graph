package net.jimmykw.knowledgegraph.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.InvalidFileException;
import net.jimmykw.knowledgegraph.exception.MaxPagesExceededException;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService.Pass1Result;
import net.jimmykw.knowledgegraph.extract.RelationshipExtractionService;
import net.jimmykw.knowledgegraph.extract.RelationshipExtractionService.Pass2Result;
import net.jimmykw.knowledgegraph.graph.CanonicalIndex;
import net.jimmykw.knowledgegraph.ingest.PdfIngestionService;
import net.jimmykw.knowledgegraph.ingest.PdfIngestionService.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GraphControllerTest {

    private static final String URL = "/api/knowledge-graph";

    private PdfIngestionService ingestion;
    private EntityExtractionService entities;
    private RelationshipExtractionService relationships;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingestion = mock(PdfIngestionService.class);
        entities = mock(EntityExtractionService.class);
        relationships = mock(RelationshipExtractionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new GraphController(ingestion, entities, relationships))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMultipartFile pdf(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    @Test
    void missingPartIs400() throws Exception {
        mvc.perform(post(URL).contentType("multipart/form-data")).andExpect(status().isBadRequest());
        verifyNoInteractions(ingestion);
    }

    @Test
    void emptyFileIs400() throws Exception {
        mvc.perform(multipart(URL).file(pdf("a.pdf", "application/pdf", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or empty file part."));
        verifyNoInteractions(ingestion);
    }

    @Test
    void nonPdfIs400() throws Exception {
        mvc.perform(multipart(URL).file(pdf("notes.txt", "text/plain", new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only PDF files are accepted."));
        verifyNoInteractions(ingestion);
    }

    @Test
    void pdfAcceptedByContentTypeEvenWithOtherName() throws Exception {
        when(ingestion.ingest(any())).thenReturn(new IngestionResult("1", "h", PdfIngestionService.STATUS_SKIPPED_DUPLICATE, List.empty()));

        mvc.perform(multipart(URL).file(pdf("upload.bin", "application/PDF", new byte[] {1}))).andExpect(status().isOk());
    }

    @Test
    void pdfAcceptedByUppercaseExtensionWithGenericContentType() throws Exception {
        when(ingestion.ingest(any())).thenReturn(new IngestionResult("1", "h", PdfIngestionService.STATUS_SKIPPED_DUPLICATE, List.empty()));

        mvc.perform(multipart(URL).file(pdf("REPORT.PDF", "application/octet-stream", new byte[] {1}))).andExpect(status().isOk());
    }

    @Test
    void duplicateReturnsZeroedCountsAndRunsNoExtraction() throws Exception {
        when(ingestion.ingest(any())).thenReturn(new IngestionResult("42", "abc", PdfIngestionService.STATUS_SKIPPED_DUPLICATE, List.empty()));

        mvc.perform(multipart(URL).file(pdf("a.pdf", "application/pdf", new byte[] {1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED_DUPLICATE"))
                .andExpect(jsonPath("$.documentId").value("42"))
                .andExpect(jsonPath("$.nodeCount").value(0))
                .andExpect(jsonPath("$.relationshipCount").value(0))
                .andExpect(jsonPath("$.failedChunks").value(0));
        verifyNoInteractions(entities, relationships);
    }

    @Test
    void processedResponseSumsFailedChunksOfBothPasses() throws Exception {
        val chunks = List.of(new Document("text"));
        val index = CanonicalIndex.empty();
        when(ingestion.ingest(any())).thenReturn(new IngestionResult("7", "hash", PdfIngestionService.STATUS_PROCESSED, chunks));
        when(entities.extract(chunks, "hash")).thenReturn(new Pass1Result(index, 5, HashMap.of("Person", 5), 2));
        when(relationships.extract(chunks, index, "hash")).thenReturn(new Pass2Result(3, HashMap.of("USES", 3), 1));

        mvc.perform(multipart(URL).file(pdf("a.pdf", "application/pdf", new byte[] {1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.nodeCount").value(5))
                .andExpect(jsonPath("$.relationshipCount").value(3))
                .andExpect(jsonPath("$.countsByLabel.Person").value(5))
                .andExpect(jsonPath("$.countsByType.USES").value(3))
                .andExpect(jsonPath("$.failedChunks").value(3));
    }

    @Test
    void unreadablePdfFromIngestionIs400() throws Exception {
        when(ingestion.ingest(any())).thenThrow(new InvalidFileException("PDF could not be read (corrupt, encrypted or not a PDF)."));

        mvc.perform(multipart(URL).file(pdf("x.pdf", "application/pdf", new byte[] {1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("PDF could not be read (corrupt, encrypted or not a PDF)."));
        verifyNoInteractions(entities, relationships);
    }

    @Test
    void tooManyPagesIs413() throws Exception {
        when(ingestion.ingest(any())).thenThrow(new MaxPagesExceededException("PDF has 9 pages"));

        mvc.perform(multipart(URL).file(pdf("a.pdf", "application/pdf", new byte[] {1}))).andExpect(status().isContentTooLarge());
    }

    @Test
    void neo4jDownIs503() throws Exception {
        when(ingestion.ingest(any())).thenThrow(new Neo4jUnavailableException("down"));

        mvc.perform(multipart(URL).file(pdf("a.pdf", "application/pdf", new byte[] {1}))).andExpect(status().isServiceUnavailable());
    }
}
