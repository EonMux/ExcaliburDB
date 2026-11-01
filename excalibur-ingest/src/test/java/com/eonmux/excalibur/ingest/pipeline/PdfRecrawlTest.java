package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.DocumentDecoder;
import com.eonmux.excalibur.ingest.processor.PdfConverter;
import com.eonmux.excalibur.ingest.processor.PdfFixtures;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FilesystemSource;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A directory of PDFs through the re-crawl path, which is what indexes every document after a
 * source's first crawl.
 */
@DisplayName("Re-crawl of PDFs")
@DisabledOnOs(OS.WINDOWS)
class PdfRecrawlTest {

    private static final String SOURCE = "library";

    @TempDir
    Path root;

    @TempDir
    Path tools;

    private DocumentRegistry registry;
    private RecordingWriter writer;
    private DocumentReindexer reindexer;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, writer), registry, writer);
        Files.write(root.resolve("typeset.pdf"),
            PdfFixtures.withPages("Maxwell unified electricity and magnetism."));
        Files.write(root.resolve("scan.pdf"), PdfFixtures.withoutText(2));
    }

    private FilesystemSource source() {
        return FilesystemSource.builder().sourceId(SOURCE).rootPath(root).build();
    }

    /** A converter that "OCRs" every page by naming the file it was given. */
    private DocumentDecoder converter() throws IOException {
        Path program = tools.resolve("fake-ocr");
        Files.writeString(program, """
            #!/bin/sh
            for last; do :; done
            echo "Recognised text of a scanned page, $(wc -c < "$last" | tr -d ' ') bytes in."
            """);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return DocumentDecoder.reading(
            PdfConverter.of(program.toString(), List.of("--format", "md"),
                Duration.ofSeconds(30), 1));
    }

    @Test
    @DisplayName("a typeset PDF is indexed from its text layer, and a scan is passed over")
    void builtInReadsTheTextLayer() throws SourceException {
        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source());

        assertThat(writer.textOf(SOURCE + ":typeset.pdf:chunk:0"))
            .isEqualTo("Maxwell unified electricity and magnetism.");
        assertThat(writer.textOf(SOURCE + ":scan.pdf:chunk:0")).isNull();
        assertThat(result.getDocumentsIndexed()).isEqualTo(1);
        assertThat(registry.get(SOURCE, "typeset.pdf").getDecoding()).isEqualTo("pdf");
        assertThat(registry.get(SOURCE, "scan.pdf")).isNull();
    }

    @Test
    @DisplayName("configuring a converter reads every PDF again with it, and only once")
    void aConverterRereadsEveryPdf() throws Exception {
        reindexer.reindexAll(source());
        reindexer.setDocumentDecoder(converter());

        DocumentReindexer.ReindexResult converted = reindexer.reindexAll(source());

        assertThat(converted.getDocumentsIndexed())
            .as("the typeset PDF because how it is read changed, the scan because it is new")
            .isEqualTo(2);
        assertThat(writer.textOf(SOURCE + ":scan.pdf:chunk:0"))
            .startsWith("Recognised text of a scanned page");
        assertThat(writer.textOf(SOURCE + ":typeset.pdf:chunk:0"))
            .startsWith("Recognised text of a scanned page");
        assertThat(registry.get(SOURCE, "scan.pdf").getDecoding())
            .isEqualTo("pdf+fake-ocr --format md");

        DocumentReindexer.ReindexResult again = reindexer.reindexAll(source());
        assertThat(again.getDocumentsIndexed()).as("nothing changed since").isZero();
    }

    @Test
    @DisplayName("removing the converter reads typeset PDFs again, and a scan keeps its OCR text")
    void removingTheConverterKeepsWhatOnlyItCouldRead() throws Exception {
        reindexer.setDocumentDecoder(converter());
        reindexer.reindexAll(source());
        reindexer.setDocumentDecoder(DocumentDecoder.builtIn());

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source());

        assertThat(writer.textOf(SOURCE + ":typeset.pdf:chunk:0"))
            .isEqualTo("Maxwell unified electricity and magnetism.");
        assertThat(registry.get(SOURCE, "typeset.pdf").getDecoding()).isEqualTo("pdf");
        assertThat(writer.textOf(SOURCE + ":scan.pdf:chunk:0"))
            .as("a text layer that holds nothing is no reason to drop what was recognised")
            .startsWith("Recognised text of a scanned page");
        assertThat(result.getDocumentsIndexed()).isEqualTo(1);
    }

    /** A storage writer that keeps the text it was handed. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();

        String textOf(String chunkId) {
            return text.get(chunkId);
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            text.put(chunkId, content);
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return text.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }
}
