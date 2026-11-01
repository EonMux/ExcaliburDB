package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ocr.IndraOcr;
import com.eonmux.excalibur.ocr.IndraOptions;
import com.eonmux.excalibur.ocr.ReadMode;
import com.eonmux.excalibur.ocr.model.ModelStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outcomes of reading a PDF with Indra OCR in process: text, a refusal, a remembered failure,
 * and a failure a later crawl retries. The models are those the OCR module's tests use:
 * {@code INDRA_OCR_MODEL_DIR}, else the default cache, downloaded there the first time.
 */
@DisplayName("Reading PDFs with Indra OCR in process")
class IndraPdfReaderTest {

    private static final IndraOptions OPTIONS =
        new IndraOptions(ReadMode.AUTO, 200, false, false, true, 2);

    private static ModelStore models() {
        String dir = System.getenv("INDRA_OCR_MODEL_DIR");
        return dir != null && !dir.isBlank() ? ModelStore.local(Path.of(dir))
            : ModelStore.downloading(ModelStore.defaultDirectory());
    }

    @Test
    @DisplayName("indexes a typeset PDF's text, read from its text layer")
    void text() throws Exception {
        IndraPdfReader reader = IndraPdfReader.of(IndraOcr.create(models(), OPTIONS),
            Duration.ofMinutes(5), 1);
        try {
            DocumentDecoder.Decoded decoded = reader.read(pdf("paper.pdf",
                "Sediment settles where the river slows."));

            assertThat(decoded.isText()).as("refused: %s", decoded.refusal()).isTrue();
            assertThat(decoded.text()).contains("Sediment settles where the river slows.");
            assertThat(reader.identity()).startsWith("indra-ocr ").contains("mode=auto");
        } finally {
            assertThat(reader.release(Duration.ofSeconds(30))).isTrue();
        }
    }

    @Test
    @DisplayName("refuses a PDF it cannot open, as the text-layer reader does")
    void refused() throws Exception {
        IndraPdfReader reader = IndraPdfReader.of(IndraOcr.create(models(), OPTIONS),
            Duration.ofMinutes(5), 1);
        try {
            DocumentDecoder.Decoded decoded = reader.read(new FetchedDocument("broken.pdf",
                "%PDF-1.7 not really a PDF".getBytes(StandardCharsets.US_ASCII),
                "application/pdf", 0L));

            assertThat(decoded.isText()).isFalse();
            assertThat(decoded.refusal()).contains("cannot open");
        } finally {
            reader.release(Duration.ofSeconds(30));
        }
    }

    @Test
    @DisplayName("remembers a conversion that ran past its timeout, and does not run it again")
    void timeoutRemembered() throws Exception {
        IndraPdfReader reader = IndraPdfReader.of(IndraOcr.create(models(), OPTIONS),
            Duration.ofNanos(1), 1);
        try {
            FetchedDocument document = pdf("slow.pdf", "Too slow for a nanosecond.");

            assertThatThrownBy(() -> reader.read(document))
                .isInstanceOf(PdfConversionException.class)
                .hasMessageContaining("did not finish slow.pdf")
                .hasMessageContaining("pdf-converter-timeout-seconds");
            assertThatThrownBy(() -> reader.read(document))
                .isInstanceOf(PdfConversionException.class)
                .hasMessageContaining("did not finish slow.pdf");
        } finally {
            reader.release(Duration.ofSeconds(30));
        }
    }

    @Test
    @DisplayName("fails, to be retried and not remembered, when the models cannot be had")
    void modelsUnavailable(@TempDir Path empty) throws Exception {
        IndraPdfReader reader = IndraPdfReader.of(
            IndraOcr.create(ModelStore.local(empty), OPTIONS), Duration.ofNanos(1), 1);
        try {
            FetchedDocument document = pdf("any.pdf", "Some text.");

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> reader.read(document))
                    .isInstanceOf(PdfConversionException.class)
                    .hasMessageContaining("could not read any.pdf")
                    .hasMessageNotContaining("did not finish");
            }
        } finally {
            assertThat(reader.release(Duration.ZERO)).isTrue();
        }
    }

    /** A one-page PDF whose text layer holds a line of text. */
    private static FetchedDocument pdf(String name, String line) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(72, 700);
                content.showText(line);
                content.endText();
            }
            doc.save(out);
            return new FetchedDocument(name, out.toByteArray(), "application/pdf", 0L);
        }
    }
}
