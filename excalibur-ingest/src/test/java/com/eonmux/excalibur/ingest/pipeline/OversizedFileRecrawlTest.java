package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FilesystemSource;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file that grows past its source's {@code max_file_bytes} is still there, and a re-crawl has to
 * treat it so.
 *
 * <p>A re-crawl infers deletions by subtraction: a registered document its crawl does not list is
 * taken to be gone and its chunks are tombstoned. A size limit that kept the file out of the
 * listing therefore erased a document the operator never removed, silently, the first time it
 * grew. An HTTP response grown past its limit keeps its indexed version and counts as a failure,
 * and the filesystem has to answer the same way.</p>
 */
@DisplayName("Re-crawl of a file grown past max_file_bytes")
class OversizedFileRecrawlTest {

    private static final String SOURCE = "notes";

    private static final long LIMIT = 1_000;

    @TempDir
    Path root;

    private DocumentRegistry registry;
    private RecordingWriter writer;
    private DocumentReindexer reindexer;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, writer), registry, writer);
    }

    private FilesystemSource source() {
        return FilesystemSource.builder()
            .sourceId(SOURCE)
            .rootPath(root)
            .maxFileSize(LIMIT)
            .build();
    }

    private static String text(int bytes) {
        return "Minutes of the weekly meeting. ".repeat(bytes / 31 + 1).substring(0, bytes);
    }

    @Test
    @DisplayName("keeps the version it indexed, counted as failed rather than deleted")
    void grownFileKeepsItsIndexedVersion() throws IOException, SourceException {
        Path minutes = root.resolve("minutes.txt");
        Files.writeString(minutes, text(500));
        reindexer.reindexAll(source());
        assertThat(registry.get(SOURCE, "minutes.txt")).as("precondition: indexed").isNotNull();
        String indexed = writer.textOf(SOURCE + ":minutes.txt:chunk:0");
        assertThat(indexed).isNotNull();

        Files.writeString(minutes, text((int) LIMIT * 2));
        DocumentReindexer.ReindexResult recrawl = reindexer.reindexAll(source());

        assertThat(recrawl.getDocumentsTombstoned())
            .as("a file still on disk is not a file that was deleted")
            .isZero();
        assertThat(recrawl.getDocumentsFailed()).isEqualTo(1);
        assertThat(recrawl.getDocumentsUnchecked())
            .as("the source answered for it: it is too large, which is a known reason")
            .isZero();
        assertThat(registry.get(SOURCE, "minutes.txt")).isNotNull();
        assertThat(writer.textOf(SOURCE + ":minutes.txt:chunk:0")).isEqualTo(indexed);
    }

    @Test
    @DisplayName("a file over the limit the first time it is seen is not indexed, and is counted")
    void firstTimeOversizedFileIsNotIndexed() throws IOException, SourceException {
        Files.writeString(root.resolve("dump.txt"), text((int) LIMIT * 2));

        DocumentReindexer.ReindexResult crawl = reindexer.reindexAll(source());

        assertThat(crawl.getDocumentsIndexed()).isZero();
        assertThat(crawl.getDocumentsFailed()).isEqualTo(1);
        assertThat(registry.get(SOURCE, "dump.txt")).isNull();
    }

    @Test
    @DisplayName("a file shrunk back under the limit is read again")
    void shrunkFileIsReadAgain() throws IOException, SourceException {
        Path minutes = root.resolve("minutes.txt");
        Files.writeString(minutes, text((int) LIMIT * 2));
        reindexer.reindexAll(source());

        Files.writeString(minutes, text(500));
        DocumentReindexer.ReindexResult recrawl = reindexer.reindexAll(source());

        assertThat(recrawl.getDocumentsIndexed()).isEqualTo(1);
        assertThat(recrawl.getDocumentsFailed()).isZero();
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
