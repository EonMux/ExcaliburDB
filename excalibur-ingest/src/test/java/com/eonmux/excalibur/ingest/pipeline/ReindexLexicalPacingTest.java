package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When a reindex makes what it has published lexically searchable.
 *
 * <p>A reindex refreshed the lexical index once, at its end. A re-crawl of a large source runs
 * for hours, and every document it had republished by then was stored, visible to every other
 * signal and missing from BM25 until the last one was done - on a lexical-only node, missing
 * from search. It now refreshes on the interval ingestion does.</p>
 */
@DisplayName("Reindex lexical refresh pacing")
class ReindexLexicalPacingTest {

    private static final String SOURCE = "paced";

    private DocumentReindexer reindexer;
    private RecordingIndex lexical;

    @BeforeEach
    void setUp() {
        DocumentRegistry registry = new DocumentRegistry();
        MapWriter writer = new MapWriter();
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, writer), registry, writer);
        lexical = new RecordingIndex();
        reindexer.setLexicalIndex(lexical, writer.text::get);
    }

    private static SlowSource fourDocuments() {
        SlowSource source = new SlowSource();
        for (String id : List.of("a.txt", "b.txt", "c.txt", "d.txt")) {
            source.documents.put(id, "The text of " + id + ", long enough to index.");
        }
        return source;
    }

    @Test
    @DisplayName("a long reindex refreshes as it goes, not only when it ends")
    void aLongReindexRefreshesAsItGoes() throws SourceException {
        reindexer.setLexicalRefreshIntervalMs(1);

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(fourDocuments());

        assertThat(result.getDocumentsIndexed()).isEqualTo(4);
        assertThat(lexical.refreshedWith)
            .as("a refresh happened while documents were still to come")
            .anyMatch(published -> published > 0 && published < 4);
        assertThat(lexical.refreshedWith).last().isEqualTo(4);
    }

    @Test
    @DisplayName("a reindex shorter than the interval refreshes once, when it ends")
    void aShortReindexRefreshesOnce() throws SourceException {
        reindexer.reindexAll(fourDocuments());

        assertThat(lexical.refreshedWith)
            .as("the default interval is ingestion's, far longer than this run")
            .containsExactly(4);
    }

    @Test
    @DisplayName("a paced refresh that fails is logged and the run goes on")
    void aFailedPacedRefreshDoesNotStopTheRun() throws SourceException {
        AtomicBoolean failedOnce = new AtomicBoolean();
        lexical.compaction = () -> {
            if (failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("compaction failed");
            }
        };
        reindexer.setLexicalRefreshIntervalMs(1);

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(fourDocuments());

        assertThat(failedOnce).as("precondition: a paced refresh failed").isTrue();
        assertThat(result.getDocumentsIndexed()).isEqualTo(4);
        assertThat(result.hasErrors()).isFalse();
        assertThat(lexical.refreshedWith).last().isEqualTo(4);
    }

    @Test
    @DisplayName("an interval that is not positive is refused")
    void aNonPositiveIntervalIsRefused() {
        assertThatThrownBy(() -> reindexer.setLexicalRefreshIntervalMs(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reindexer.setLexicalRefreshIntervalMs(-5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Stores chunks in a map. */
    private static final class MapWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();

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

    /** A source that takes a few milliseconds per fetch, so a 1ms interval passes mid-run. */
    private static final class SlowSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();

        @Override
        public String getSourceId() {
            return SOURCE;
        }

        @Override
        public String getSourceType() {
            return "slow";
        }

        @Override
        public Stream<String> crawl() {
            return documents.keySet().stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException("interrupted", e);
            }
            return new FetchedDocument(documentId,
                documents.get(documentId).getBytes(StandardCharsets.UTF_8), "text/plain", 1000L);
        }

        @Override
        public void close() {
        }
    }

    /** Records, per refresh, how many documents had been added by then. */
    private static final class RecordingIndex implements ChunkIndex {

        private final List<Map<String, String>> documents = new CopyOnWriteArrayList<>();
        private final List<Integer> refreshedWith = new CopyOnWriteArrayList<>();
        private volatile Runnable compaction = () -> { };

        @Override
        public void add(String chunkId, String text) {
            documents.add(Map.of(chunkId, text));
        }

        @Override
        public void addDocument(Map<String, String> chunks) {
            documents.add(Map.copyOf(chunks));
        }

        @Override
        public void refresh() {
            refreshedWith.add(documents.size());
        }

        @Override
        public int erase(Set<String> chunkIds) {
            return 0;
        }

        @Override
        public String chunkIdOf(long id) {
            return null;
        }

        @Override
        public long indexedCount() {
            return documents.size();
        }

        @Override
        public CompactionResult compactIfDue(Function<String, String> textOf) {
            compaction.run();
            return CompactionResult.declined("not due");
        }

        @Override
        public CompactionResult compactNow(Function<String, String> textOf) {
            return compactIfDue(textOf);
        }

        @Override
        public Ranker ranker(QueryExpander expander, Predicate<String> admits) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String engine() {
            return "recording";
        }

        @Override
        public ChunkIndexStats stats() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
