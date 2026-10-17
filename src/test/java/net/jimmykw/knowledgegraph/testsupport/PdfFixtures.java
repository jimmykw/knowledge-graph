package net.jimmykw.knowledgegraph.testsupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small PDFs for the ingestion tests. */
@UtilityClass
public class PdfFixtures {

    /** One page per entry of {@code pageTexts}; an empty string yields a blank page. */
    public static byte[] pages(List<String> pageTexts) {
        return build(pageTexts, null);
    }

    public static byte[] textPages(int count) {
        return pages(List.range(0, count).map(index -> "Marker" + index + " mentions IBM and Thomas Watson."));
    }

    public static byte[] blankPage() {
        return pages(List.of(""));
    }

    /** Requires a user password to open, so a reader without the password cannot parse it. */
    public static byte[] encrypted(String userPassword) {
        return build(List.of("Secret page text"), userPassword);
    }

    public static byte[] garbage() {
        return "this is definitely not a pdf".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] build(List<String> pageTexts, String userPassword) {
        return Try.withResources(PDDocument::new)
                .of(document -> {
                    pageTexts.forEach(text -> addPage(document, text));
                    Option.of(userPassword).forEach(password -> protect(document, password));
                    val out = new ByteArrayOutputStream();
                    document.save(out);
                    return out;
                })
                .map(ByteArrayOutputStream::toByteArray)
                .get();
    }

    private static void protect(PDDocument document, String userPassword) {
        val policy = new StandardProtectionPolicy("owner-" + userPassword, userPassword, new AccessPermission());
        policy.setEncryptionKeyLength(128);
        Try.run(() -> document.protect(policy)).get();
    }

    private static void addPage(PDDocument document, String text) {
        val page = new PDPage();
        document.addPage(page);
        if (text.isEmpty()) {
            return;
        }
        Try.withResources(() -> new PDPageContentStream(document, page))
                .of(stream -> writeText(stream, text))
                .get();
    }

    private static PDPageContentStream writeText(PDPageContentStream stream, String text) throws java.io.IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText(text);
        stream.endText();
        return stream;
    }
}
