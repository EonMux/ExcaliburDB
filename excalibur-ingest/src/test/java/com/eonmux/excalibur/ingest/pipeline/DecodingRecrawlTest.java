package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FilesystemSource;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A re-crawl reads a document again when its bytes are intact but would now become other text.
 *
 * <p>Replacing a source's {@code charset} with {@code PUT /v1/ingest} is how an operator fixes a
 * directory that was indexed as mojibake, and the fix is only real if the documents already
 * indexed are read again in the new encoding. Their bytes have not changed, so a comparison of
 * bytes alone leaves every one of them as it was.</p>
 */
@DisplayName("Re-crawl after a decoding change")
class DecodingRecrawlTest {

    private static final String SOURCE = "legacy-exports";

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @TempDir
    Path root;

    private DocumentRegistry registry;
    private RecordingWriter writer;
    private DocumentReindexer reindexer;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, writer), registry, writer);
        Files.write(root.resolve("menu.txt"),
            "Café crème brûlée, served daily. ".repeat(20)
                .getBytes(WINDOWS_1252));
    }

    private FilesystemSource source(Charset charset) {
        return FilesystemSource.builder()
            .sourceId(SOURCE)
            .rootPath(root)
            .charset(charset)
            .build();
    }

    private String indexedText() {
        return writer.textOf(SOURCE + ":menu.txt:chunk:0");
    }

    @Test
    @DisplayName("a changed charset reads the unchanged file again, in the new encoding")
    void changedCharsetRereadsTheDocument() throws SourceException {
        reindexer.reindexAll(source(StandardCharsets.UTF_8));
        assertThat(indexedText()).as("precondition: read as UTF-8, the file is mojibake")
            .doesNotContain("Café");

        DocumentReindexer.ReindexResult corrected = reindexer.reindexAll(source(WINDOWS_1252));

        assertThat(corrected.getDocumentsIndexed()).isEqualTo(1);
        assertThat(indexedText()).contains("Café crème brûlée");
        assertThat(registry.get(SOURCE, "menu.txt").getDecoding()).isEqualTo("windows-1252");
    }

    @Test
    @DisplayName("the same charset again leaves the document alone")
    void unchangedCharsetLeavesTheDocument() throws SourceException {
        reindexer.reindexAll(source(WINDOWS_1252));

        DocumentReindexer.ReindexResult again = reindexer.reindexAll(source(WINDOWS_1252));

        assertThat(again.getDocumentsIndexed()).isZero();
    }

    @Test
    @DisplayName("an entry recorded before decodings were kept is not re-read, and gains one")
    void entryWithoutDecodingIsStampedNotReread() throws SourceException {
        reindexer.reindexAll(source(WINDOWS_1252));
        DocumentMetadata indexed = registry.get(SOURCE, "menu.txt");
        registry.register(indexed.toBuilder().decoding(null).build());

        DocumentReindexer.ReindexResult upgraded = reindexer.reindexAll(source(WINDOWS_1252));

        assertThat(upgraded.getDocumentsIndexed())
            .as("nothing says it was read any other way, and reading a whole corpus again on "
                + "upgrade would re-embed every document")
            .isZero();
        assertThat(registry.get(SOURCE, "menu.txt").getDecoding()).isEqualTo("windows-1252");

        DocumentReindexer.ReindexResult corrected =
            reindexer.reindexAll(source(StandardCharsets.UTF_8));
        assertThat(corrected.getDocumentsIndexed())
            .as("from then on a change to the source's charset is seen")
            .isEqualTo(1);
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
