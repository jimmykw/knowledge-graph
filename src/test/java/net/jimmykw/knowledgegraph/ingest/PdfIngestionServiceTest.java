package net.jimmykw.knowledgegraph.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

import io.vavr.collection.List;
import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.InvalidFileException;
import net.jimmykw.knowledgegraph.exception.MaxPagesExceededException;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.testsupport.PdfFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class PdfIngestionServiceTest {

    private Neo4jGraphWriter writer;

    @BeforeEach
    void setUp() {
        writer = mock(Neo4jGraphWriter.class);
        when(writer.findDocumentId(anyString())).thenReturn(Option.none());
        when(writer.mergeDocument(anyString(), anyString())).thenReturn(11L);
    }

    private PdfIngestionService service(int maxPages, int chunkTokens) {
        return new PdfIngestionService(writer, new AppProperties(maxPages, chunkTokens, null, null, null, null));
    }

    private static MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf", bytes);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void processedPdfIsHashedChunkedAndRecorded() throws Exception {
        val bytes = PdfFixtures.textPages(3);

        val result = service(10, 1000).ingest(upload(bytes));

        assertThat(result.status()).isEqualTo(PdfIngestionService.STATUS_PROCESSED);
        assertThat(result.documentHash()).isEqualTo(sha256(bytes)).hasSize(64);
        assertThat(result.documentId()).isEqualTo("11");
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().head().getText()).contains("IBM");
        verify(writer).mergeDocument(sha256(bytes), "doc.pdf");
    }

    @Test
    void duplicateIsSkippedWithoutParsingOrWriting() throws Exception {
        val garbage = PdfFixtures.garbage();
        when(writer.findDocumentId(sha256(garbage))).thenReturn(Option.some(99L));

        val result = service(10, 1000).ingest(upload(garbage));

        assertThat(result.status()).isEqualTo(PdfIngestionService.STATUS_SKIPPED_DUPLICATE);
        assertThat(result.documentId()).isEqualTo("99");
        assertThat(result.chunks()).isEmpty();
        verify(writer, never()).mergeDocument(anyString(), anyString());
    }

    @Test
    void garbageBytesAreRejectedAsInvalidFileWithoutWriting() {
        assertThatThrownBy(() -> service(10, 1000).ingest(upload(PdfFixtures.garbage())))
                .isInstanceOf(InvalidFileException.class)
                .hasMessage("PDF could not be read (corrupt, encrypted or not a PDF).")
                .hasCauseInstanceOf(RuntimeException.class);
        verify(writer, never()).mergeDocument(anyString(), anyString());
    }

    @Test
    void passwordProtectedPdfIsRejectedAsInvalidFileWithoutWriting() {
        assertThatThrownBy(() -> service(10, 1000).ingest(upload(PdfFixtures.encrypted("secret"))))
                .isInstanceOf(InvalidFileException.class);
        verify(writer, never()).mergeDocument(anyString(), anyString());
    }

    @Test
    void pageLimitIsEnforcedBeforeAnyWrite() {
        assertThatThrownBy(() -> service(2, 1000).ingest(upload(PdfFixtures.textPages(3))))
                .isInstanceOf(MaxPagesExceededException.class)
                .hasMessageContaining("3 pages")
                .hasMessageContaining("maximum of 2");
        verify(writer, never()).mergeDocument(anyString(), anyString());
    }

    @Test
    void pageCountAtTheLimitIsAccepted() {
        val result = service(3, 1000).ingest(upload(PdfFixtures.textPages(3)));

        assertThat(result.status()).isEqualTo(PdfIngestionService.STATUS_PROCESSED);
    }

    @Test
    void blankPdfThatParsesIsProcessedWithZeroChunks() {
        val result = service(10, 1000).ingest(upload(PdfFixtures.blankPage()));

        assertThat(result.status()).isEqualTo(PdfIngestionService.STATUS_PROCESSED);
        assertThat(result.chunks()).isEmpty();
        verify(writer).mergeDocument(anyString(), anyString());
    }

    @Test
    void smallChunkSizeProducesMultipleChunksFromOnePage() {
        val longPage = PdfFixtures.pages(List.of("alpha beta gamma delta ".repeat(40).trim()));

        val result = service(10, 20).ingest(upload(longPage));

        assertThat(result.chunks().size()).isGreaterThan(1);
    }

    @Test
    void unreadableUploadStreamBecomesIllegalState() throws Exception {
        val file = mock(MultipartFile.class);
        when(file.getBytes()).thenThrow(new IOException("disk gone"));

        assertThatThrownBy(() -> service(10, 1000).ingest(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to read uploaded PDF bytes");
    }
}
