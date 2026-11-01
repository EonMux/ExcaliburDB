package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How PDFs are read: their text layer, Indra OCR in process, or an external program.
 */
@DisplayName("IngestConfig PDF reader")
class ConfigIngestPdfReaderTest {

    private static IngestConfig ingest(Map<String, String> props) {
        return new IngestConfig(new HashMap<>(props));
    }

    @Test
    @DisplayName("none by default: PDFs are read by their text layer")
    void noneByDefault() {
        IngestConfig settings = ingest(Map.of());

        assertThat(settings.getPdfConverterCommand()).isEmpty();
        assertThat(settings.getPdfConverterArgs()).isEmpty();
        assertThat(settings.getPdfConverterTimeoutSeconds()).isEqualTo(3600);
        assertThat(settings.getPdfConverterParallelism()).isEqualTo(1);
        assertThat(settings.getPdfReader()).isEqualTo(IngestConfig.PDF_READER_TEXT_LAYER);
    }

    @Test
    @DisplayName("a converter command alone means the command reader, as before there was a choice")
    void commandImpliesCommandReader() {
        assertThat(ingest(Map.of("excalibur.ingest.pdf-converter-command", "indra-ocr"))
            .getPdfReader()).isEqualTo(IngestConfig.PDF_READER_COMMAND);
        assertThat(ingest(Map.of("excalibur.ingest.pdf-reader", " Command ",
                "excalibur.ingest.pdf-converter-command", "indra-ocr"))
            .getPdfReader()).isEqualTo(IngestConfig.PDF_READER_COMMAND);
    }

    @Test
    @DisplayName("a reader that contradicts the converter command, or is unknown, is refused")
    void contradictionsAreRefused() {
        assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.pdf-reader", "indra",
                "excalibur.ingest.pdf-converter-command", "indra-ocr")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("clear one of the two");
        assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.pdf-reader", "command")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("names no program");
        assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.pdf-reader", "tesseract")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("text-layer, indra or command");
    }

    @Test
    @DisplayName("Indra reads with its own defaults, and its licence is not accepted for anyone")
    void indraDefaults() {
        IngestConfig settings = ingest(Map.of("excalibur.ingest.pdf-reader", "indra"));

        assertThat(settings.getPdfReader()).isEqualTo(IngestConfig.PDF_READER_INDRA);
        assertThat(settings.getIndraModelDir()).isEmpty();
        assertThat(settings.isIndraModelDownload()).isTrue();
        assertThat(settings.isIndraAcceptModelLicense()).isFalse();
        assertThat(settings.getIndraMode()).isEqualTo(IngestConfig.INDRA_MODE_AUTO);
        assertThat(settings.getIndraDpi()).isEqualTo(200);
        assertThat(settings.getIndraThreads()).isZero();
        assertThat(settings.isIndraMath()).isFalse();
        assertThat(settings.isIndraKeepFurniture()).isFalse();
        assertThat(settings.isIndraImageOcr()).isTrue();
        assertThat(settings.toString()).contains("pdfReader=indra");
    }

    @Test
    @DisplayName("Indra's settings are read, and refused outside their bounds")
    void indraSettings() {
        IngestConfig settings = ingest(Map.of(
            "excalibur.ingest.indra-model-dir", " /srv/models ",
            "excalibur.ingest.indra-mode", "OCR",
            "excalibur.ingest.indra-dpi", "300",
            "excalibur.ingest.indra-threads", "8"));

        assertThat(settings.getIndraModelDir()).isEqualTo("/srv/models");
        assertThat(settings.getIndraMode()).isEqualTo(IngestConfig.INDRA_MODE_OCR);
        assertThat(settings.getIndraDpi()).isEqualTo(300);
        assertThat(settings.getIndraThreads()).isEqualTo(8);
        assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.indra-mode", "fast")))
            .hasMessageContaining("indra-mode");
        for (String bad : List.of("71", "601")) {
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.indra-dpi", bad)))
                .hasMessageContaining("indra-dpi");
        }
        for (String bad : List.of("-1", "257")) {
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.indra-threads", bad)))
                .hasMessageContaining("indra-threads");
        }
    }

    @Test
    @DisplayName("a command and its arguments, split on whitespace")
    void commandAndArguments() {
        IngestConfig settings = ingest(Map.of(
            "excalibur.ingest.pdf-converter-command", " /opt/indra/bin/indra-ocr ",
            "excalibur.ingest.pdf-converter-args", "  --format md   --math "));

        assertThat(settings.getPdfConverterCommand()).isEqualTo("/opt/indra/bin/indra-ocr");
        assertThat(settings.getPdfConverterArgs()).containsExactly("--format", "md", "--math");
    }

    @Test
    @DisplayName("a timeout and a parallelism outside their bounds are refused at startup")
    void boundsAreEnforced() {
        for (String bad : List.of("0", "86401")) {
            assertThatThrownBy(() -> ingest(Map.of(
                    "excalibur.ingest.pdf-converter-timeout-seconds", bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdf-converter-timeout-seconds");
        }
        for (String bad : List.of("0", "65")) {
            assertThatThrownBy(() -> ingest(Map.of(
                    "excalibur.ingest.pdf-converter-parallelism", bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdf-converter-parallelism");
        }
    }

    @Test
    @DisplayName("every setting takes part in equality, so a changed converter is a changed config")
    void equality() {
        IngestConfig base = ingest(Map.of());
        assertThat(ingest(Map.of("excalibur.ingest.pdf-converter-command", "indra-ocr")))
            .isNotEqualTo(base);
        assertThat(ingest(Map.of("excalibur.ingest.pdf-converter-args", "--math")))
            .isNotEqualTo(base);
        assertThat(ingest(Map.of("excalibur.ingest.pdf-converter-parallelism", "2")))
            .isNotEqualTo(base);
        assertThat(ingest(Map.of("excalibur.ingest.pdf-converter-timeout-seconds", "60")))
            .isNotEqualTo(base);
        assertThat(ingest(Map.of())).isEqualTo(base).hasSameHashCodeAs(base);
    }
}
