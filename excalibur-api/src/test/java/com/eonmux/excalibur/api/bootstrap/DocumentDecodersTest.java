package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.ingest.processor.DocumentDecoder;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A node's document decoder")
class DocumentDecodersTest {

    /** A data directory none of these decoders writes to. */
    private static final Path DATA = Path.of("/nonexistent/excalibur-data");

    @Test
    @DisplayName("reads PDFs by their text layer when no converter is named")
    void builtInByDefault() {
        DocumentDecoder decoder = DocumentDecoders.from(new IngestConfig(new HashMap<>()), DATA);

        assertThat(decoder).isSameAs(DocumentDecoder.builtIn());
        assertThat(decoder.pdfDecoding()).isEqualTo("pdf");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("converts PDFs with the named converter and its arguments")
    void converterWhenNamed(@TempDir Path dir) throws Exception {
        Path program = dir.resolve("indra-ocr");
        Files.writeString(program, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));

        DocumentDecoder decoder = DocumentDecoders.from(new IngestConfig(new HashMap<>(Map.of(
            "excalibur.ingest.pdf-converter-command", program.toString(),
            "excalibur.ingest.pdf-converter-args", "--format md --math"))), DATA);

        assertThat(decoder.pdfDecoding()).startsWith("pdf+").contains("indra-ocr", "--math");
    }

    @Test
    @DisplayName("refuses to start with a converter that is not there")
    void missingConverterStopsTheNode(@TempDir Path dir) {
        IngestConfig ingest = new IngestConfig(new HashMap<>(Map.of(
            "excalibur.ingest.pdf-converter-command", dir.resolve("absent").toString())));

        assertThatThrownBy(() -> DocumentDecoders.from(ingest, DATA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pdf-converter-command");
        assertThatThrownBy(() -> DocumentDecoders.from(null, DATA))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("refuses to read with Indra OCR until its model licence is accepted")
    void indraNeedsTheLicenceAccepted(@TempDir Path dir) {
        IngestConfig ingest = new IngestConfig(new HashMap<>(Map.of(
            "excalibur.ingest.pdf-reader", "indra",
            "excalibur.ingest.indra-model-dir", dir.toString())));

        assertThatThrownBy(() -> DocumentDecoders.from(ingest, DATA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RAIL-M")
            .hasMessageContaining("excalibur.ingest.indra-accept-model-license=true");
    }

    @Test
    @DisplayName("reads with Indra OCR, its models fetched only when the first PDF is read")
    void indraWhenAccepted(@TempDir Path dir) throws Exception {
        DocumentDecoder decoder = DocumentDecoders.from(new IngestConfig(new HashMap<>(Map.of(
            "excalibur.ingest.pdf-reader", "indra",
            "excalibur.ingest.indra-accept-model-license", "true",
            "excalibur.ingest.indra-model-dir", dir.toString(),
            "excalibur.ingest.indra-mode", "ocr",
            "excalibur.ingest.indra-threads", "2"))), DATA);
        try {
            assertThat(decoder.pdfDecoding()).startsWith("pdf+indra-ocr ")
                .contains("models=v1.1.0", "mode=ocr", "dpi=200")
                .doesNotContain("threads");
            assertThat(dir).isEmptyDirectory();
        } finally {
            assertThat(decoder.release(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    @DisplayName("reads a scanned PDF, which has no text layer, with Indra OCR")
    void indraReadsAScan() throws Exception {
        Map<String, String> props = new HashMap<>(Map.of(
            "excalibur.ingest.pdf-reader", "indra",
            "excalibur.ingest.indra-accept-model-license", "true",
            "excalibur.ingest.indra-threads", "2"));
        // The models the OCR module's tests use: INDRA_OCR_MODEL_DIR, else the default cache,
        // downloaded there the first time.
        String models = System.getenv("INDRA_OCR_MODEL_DIR");
        if (models != null && !models.isBlank()) {
            props.put("excalibur.ingest.indra-model-dir", models);
        }
        DocumentDecoder decoder = DocumentDecoders.from(new IngestConfig(props), DATA);
        try {
            DocumentDecoder.Decoded decoded = decoder.decode(new FetchedDocument("scan.pdf",
                scannedPdf("Rivers carry fine sediment downstream"), "application/pdf", 0L));

            assertThat(decoded.isText()).as("refused: %s", decoded.refusal()).isTrue();
            assertThat(decoded.text()).containsIgnoringCase("sediment")
                .containsIgnoringCase("downstream");
        } finally {
            assertThat(decoder.release(Duration.ofSeconds(30))).isTrue();
        }
    }

    /** A one-page PDF holding only a 200 dpi image of a line of text. */
    private static byte[] scannedPdf(String line) throws IOException {
        int dpi = 200;
        PDRectangle letter = PDRectangle.LETTER;
        BufferedImage page = new BufferedImage(Math.round(letter.getWidth() * dpi / 72),
            Math.round(letter.getHeight() * dpi / 72), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, page.getWidth(), page.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 44));
        g.drawString(line, 200, 400);
        g.dispose();
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage pdfPage = new PDPage(letter);
            doc.addPage(pdfPage);
            PDImageXObject image = LosslessFactory.createFromImage(doc, page);
            try (PDPageContentStream content = new PDPageContentStream(doc, pdfPage)) {
                content.drawImage(image, 0, 0, letter.getWidth(), letter.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("refuses to start when the models are missing and may not be downloaded")
    void indraWithoutModelsOrDownload(@TempDir Path dir) {
        IngestConfig ingest = new IngestConfig(new HashMap<>(Map.of(
            "excalibur.ingest.pdf-reader", "indra",
            "excalibur.ingest.indra-accept-model-license", "true",
            "excalibur.ingest.indra-model-download", "false",
            "excalibur.ingest.indra-model-dir", dir.toString())));

        assertThatThrownBy(() -> DocumentDecoders.from(ingest, DATA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(dir.toString())
            .hasMessageContaining("det.onnx")
            .hasMessageContaining("indra-model-download=false");
    }

    @Test
    @DisplayName("refuses a model directory inside the data directory")
    void modelsOutsideTheData(@TempDir Path data) {
        IngestConfig ingest = new IngestConfig(new HashMap<>(Map.of(
            "excalibur.ingest.pdf-reader", "indra",
            "excalibur.ingest.indra-accept-model-license", "true",
            "excalibur.ingest.indra-model-dir", data.resolve(".cache/models").toString())));

        assertThatThrownBy(() -> DocumentDecoders.from(ingest, data))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inside the data directory")
            .hasMessageContaining("excalibur.ingest.indra-model-dir");
    }
}
