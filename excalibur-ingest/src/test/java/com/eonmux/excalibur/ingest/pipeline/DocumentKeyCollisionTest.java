package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two sources whose documents share a key.
 *
 * <p>A source id may contain a colon, so document {@code wiki:home.md} of source {@code team}
 * and document {@code home.md} of source {@code team:wiki} are both {@code team:wiki:home.md} -
 * the registry's key and the prefix of every chunk id. Storage can hold only one of them, and the
 * second written used to overwrite the first's chunks in place and take over its registry entry.
 * The key belongs to whichever source registered it first; the other's document is refused
 * before anything of it is written, on every path that writes one.</p>
 */
@DisplayName("A document whose key another source's document holds")
class DocumentKeyCollisionTest {

    private static final String KEY = "team:wiki:home.md";

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private DocumentRegistry documents;
    private IndexOrchestrator index;

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
        storage.start();
        documents = new DocumentRegistry();
        index = mock(IndexOrchestrator.class);
        when(index.isBackpressured()).thenReturn(false);
        when(index.awaitBackpressureRelease(anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    @DisplayName("is refused by a first ingestion and leaves the holder's document intact")
    void aFirstIngestionIsRefused() {
        RecordingStatus status = new RecordingStatus();
        IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(64, 0),
            null, storage, index, sourceId -> status,
            new DocumentVisibilityTracker(documents));
        orchestrator.ingestNow(new FixedSource("team", Map.of("wiki:home.md", "the team page")));
        String heldChunk = documents.get("team", "wiki:home.md").getChunkIds().get(0);

        IngestionOrchestrator.IngestionOutcome outcome = orchestrator.ingestNow(
            new FixedSource("team:wiki", Map.of("home.md", "the wiki page", "faq.md", "faq")));

        assertThat(outcome.documentsFailed()).isOne();
        assertThat(outcome.documentsIngested()).isOne();
        assertThat(documents.getByKey(KEY).getSourceId()).isEqualTo("team");
        assertThat(documents.get("team:wiki", "home.md")).isNull();
        assertThat(documents.get("team:wiki", "faq.md")).isNotNull();
        assertThat(storedText(heldChunk)).contains("the team page");
        assertThat(status.error)
            .as("the status says why, and that it will not pass on a retry")
            .contains("1 of them because a document of another source already holds the same key");
    }

    @Test
    @DisplayName("is refused whichever source registered first")
    void theFirstRegistrationWins() {
        IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(64, 0),
            null, storage, index, sourceId -> new RecordingStatus(),
            new DocumentVisibilityTracker(documents));
        orchestrator.ingestNow(new FixedSource("team:wiki", Map.of("home.md", "the wiki page")));

        IngestionOrchestrator.IngestionOutcome outcome = orchestrator.ingestNow(
            new FixedSource("team", Map.of("wiki:home.md", "the team page")));

        assertThat(outcome.documentsFailed()).isOne();
        assertThat(documents.getByKey(KEY).getSourceId()).isEqualTo("team:wiki");
        assertThat(storedText(documents.getByKey(KEY).getChunkIds().get(0)))
            .contains("the wiki page");
    }

    @Test
    @DisplayName("is refused by a re-crawl that writes straight to storage")
    void aDirectReindexIsRefused() {
        MapWriter writer = new MapWriter();
        documents.register(held("team", "wiki:home.md"));
        writer.writeChunk(KEY + ":chunk:0", "the team page", null);
        DocumentReindexer reindexer = new DocumentReindexer(new TextChunker(64, 0), null,
            new UpsertHandler(documents, writer), documents, writer);

        DocumentReindexer.DocumentResult result = reindexer.reindexDocument("team:wiki",
            "home.md", document("home.md", "the wiki page"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("already holds");
        assertThat(writer.stored.get(KEY + ":chunk:0")).isEqualTo("the team page");
        assertThat(documents.getByKey(KEY).getSourceId()).isEqualTo("team");
    }

    @Test
    @DisplayName("is refused by a re-crawl that publishes atomically, before it is marked in flight")
    void anAtomicReindexIsRefused() {
        MapWriter writer = new MapWriter();
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(documents);
        documents.register(held("team", "wiki:home.md"));
        writer.writeChunk(KEY + ":chunk:0", "the team page", null);
        DocumentReindexer reindexer = new DocumentReindexer(new TextChunker(64, 0), null,
            new UpsertHandler(documents, writer), documents, writer,
            new DocumentBatchPublisher(tracker));

        DocumentReindexer.DocumentResult result = reindexer.reindexDocument("team:wiki",
            "home.md", document("home.md", "the wiki page"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("already holds");
        assertThat(writer.stored.get(KEY + ":chunk:0")).isEqualTo("the team page");
        assertThat(tracker.isDocumentTracked(KEY))
            .as("the holder's document was never hidden for the other's attempt")
            .isFalse();
    }

    @Test
    @DisplayName("is not what an erasure of the other source's document reaches")
    void anErasureOfTheOtherSourceLeavesTheHolderAlone() {
        MapWriter writer = new MapWriter();
        documents.register(held("team", "wiki:home.md"));
        writer.writeChunk(KEY + ":chunk:0", "the team page", null);
        DocumentErasureService erasure = new DocumentErasureService(documents, writer, null, null);

        DocumentErasureService.Outcome outcome =
            erasure.erase("team:wiki", List.of("home.md"), false);

        assertThat(outcome.documentsNotFound()).isOne();
        assertThat(outcome.erasedDocumentIds()).isEmpty();
        assertThat(writer.stored.get(KEY + ":chunk:0")).isEqualTo("the team page");
        assertThat(erasure.eraseSource("team:wiki", 10, false).erasedDocumentIds()).isEmpty();
        assertThat(documents.getByKey(KEY).getSourceId()).isEqualTo("team");
    }

    private String storedText(String chunkId) {
        MemorySegment value = storage.get(chunkId.getBytes(StandardCharsets.UTF_8));
        if (value == null) {
            return null;
        }
        return ChunkPayloadCodec.textOf(value.toArray(ValueLayout.JAVA_BYTE));
    }

    private static DocumentMetadata held(String sourceId, String documentId) {
        byte[] content = "the team page".getBytes(StandardCharsets.UTF_8);
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentHash(DocumentRegistry.computeContentHash(content))
            .contentLength(content.length)
            .lastModifiedMillis(1000L)
            .chunkIds(List.of(ChunkIdentifiers.chunkId(sourceId, documentId, 0)))
            .build();
    }

    private static FetchedDocument document(String id, String text) {
        return new FetchedDocument(id, text.getBytes(StandardCharsets.UTF_8), "text/plain", 1000L);
    }

    /** Storage as a map, for the re-crawl paths. */
    private static final class MapWriter implements DocumentReindexer.StorageWriter,
            OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {

        final Map<String, String> stored = new ConcurrentHashMap<>();
        private final AtomicLong offsets = new AtomicLong();

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            stored.put(chunkId, content);
            return true;
        }

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            stored.put(chunkId, content);
            return offsets.incrementAndGet();
        }

        @Override
        public boolean delete(byte[] key) {
            return stored.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }

    /** Records the last error a run reported. */
    private static final class RecordingStatus implements IngestionOrchestrator.IngestionStatus {

        volatile String error;

        @Override
        public void update(String state, double progress, long entriesIngested,
                           long entriesPending) {
        }

        @Override
        public void setSkipped(long entriesSkipped) {
        }

        @Override
        public void setDisallowed(long entriesDisallowed) {
        }

        @Override
        public void setError(String error) {
            this.error = error;
        }
    }

    /** A source holding a fixed set of documents. */
    private static final class FixedSource implements Source {

        private final String sourceId;
        private final Map<String, String> texts;

        FixedSource(String sourceId, Map<String, String> texts) {
            this.sourceId = sourceId;
            this.texts = new LinkedHashMap<>(texts);
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "test";
        }

        @Override
        public Stream<String> crawl() {
            return texts.keySet().stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            return document(documentId, texts.get(documentId));
        }

        @Override
        public void close() {
        }
    }
}
