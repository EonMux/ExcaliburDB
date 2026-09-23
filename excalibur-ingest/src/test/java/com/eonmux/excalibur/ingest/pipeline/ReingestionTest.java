package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A document ingested under a key this node already holds.
 *
 * <p>A client posting the same document id again, and the first crawl of a source registered
 * again after its registration was removed, both reach the first-ingest path with a previous
 * version already published. Chunk ids are the document key and an index, so a new version
 * overwrites the ids it shares with the old one; whatever the old version held past the new
 * one's last chunk is named by nothing once the registry entry is replaced. Left in storage
 * and in the lexical index, that tail stays searchable and no erasure can find it.</p>
 */
@DisplayName("A document ingested again")
class ReingestionTest {

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private DocumentRegistry documents;
    private ChunkLexicalIndex lexical;
    private IndexOrchestrator index;

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
        storage.start();
        documents = new DocumentRegistry();
        lexical = ChunkLexicalIndex.open(tempDir.resolve("lexical"), 4.0);
        index = mock(IndexOrchestrator.class);
        when(index.isBackpressured()).thenReturn(false);
        when(index.awaitBackpressureRelease(anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        lexical.close();
        storage.close();
    }

    @Test
    @DisplayName("with fewer chunks retires the previous version's tail everywhere")
    void fewerChunksRetireTheTail() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(documents);
        List<String> retiredVectors = new CopyOnWriteArrayList<>();
        IngestionOrchestrator orchestrator = orchestrator(tracker);
        orchestrator.setVectorEraser(documentKey -> {
            retiredVectors.add(documentKey);
            return 1;
        });
        MutableSource source = new MutableSource("docs");

        List<String> oldWords = words("old", 60);
        source.put("a.txt", String.join(" ", oldWords));
        orchestrator.ingestNow(source);
        List<String> oldChunkIds = documents.get("docs", "a.txt").getChunkIds();
        assertThat(oldChunkIds).as("precondition: the first version spans chunks").hasSizeGreaterThan(2);
        assertThat(hits(oldWords.get(oldWords.size() - 1))).as("precondition: indexed").isOne();

        source.put("a.txt", "a short replacement");
        orchestrator.ingestNow(source);

        DocumentMetadata current = documents.get("docs", "a.txt");
        assertThat(current.getChunkIds()).hasSizeLessThan(oldChunkIds.size());
        for (int i = current.getChunkIds().size(); i < oldChunkIds.size(); i++) {
            assertThat(stored(oldChunkIds.get(i)))
                .as("chunk %d of the previous version is named by nothing and must be gone", i)
                .isFalse();
        }
        for (String word : oldWords) {
            assertThat(hits(word))
                .as("'%s' belongs only to the previous version", word)
                .isZero();
        }
        assertThat(hits("replacement")).isOne();
        assertThat(tracker.getVisibility("docs:a.txt").chunkIds())
            .containsExactlyElementsOf(current.getChunkIds());
        assertThat(retiredVectors)
            .as("the document stays published, so its graph nodes are left to it")
            .isEmpty();
    }

    @Test
    @DisplayName("that fails to publish leaves the previous version in place")
    void aFailedReplacementKeepsThePrevious() {
        RefusingTracker tracker = new RefusingTracker(documents);
        List<String> retiredVectors = new CopyOnWriteArrayList<>();
        IngestionOrchestrator orchestrator = orchestrator(tracker);
        orchestrator.setVectorEraser(documentKey -> {
            retiredVectors.add(documentKey);
            return 1;
        });
        MutableSource source = new MutableSource("docs");

        source.put("a.txt", String.join(" ", words("old", 60)));
        orchestrator.ingestNow(source);
        List<String> oldChunkIds = documents.get("docs", "a.txt").getChunkIds();

        tracker.refusing = true;
        List<String> newWords = words("new", 120);
        source.put("a.txt", String.join(" ", newWords));
        IngestionOrchestrator.IngestionOutcome outcome = orchestrator.ingestNow(source);

        assertThat(outcome.documentsFailed()).isOne();
        assertThat(documents.get("docs", "a.txt").getChunkIds())
            .as("the registry still describes the previous version")
            .containsExactlyElementsOf(oldChunkIds);
        for (String chunkId : oldChunkIds) {
            assertThat(stored(chunkId))
                .as("%s is still named by the surviving entry", chunkId)
                .isTrue();
        }
        String documentKey = ChunkIdentifiers.documentKey("docs", "a.txt");
        for (int i = oldChunkIds.size(); i < 200; i++) {
            assertThat(stored(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, i)))
                .as("chunk %d was written past the surviving entry and must be undone", i)
                .isFalse();
        }
        assertThat(hits(newWords.get(newWords.size() - 1))).isZero();
        assertThat(retiredVectors)
            .as("the previous version is still published and still owns its graph nodes")
            .isEmpty();
    }

    private IngestionOrchestrator orchestrator(DocumentVisibilityTracker tracker) {
        IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(8, 0),
            null, storage, index, sourceId -> new IgnoredStatus(), tracker);
        orchestrator.setLexicalIndex(lexical);
        return orchestrator;
    }

    private boolean stored(String chunkId) {
        MemorySegment value = storage.get(chunkId.getBytes(StandardCharsets.UTF_8));
        return value != null && value.byteSize() > 0;
    }

    private int hits(String term) {
        return lexical.ranker(QueryExpander.identity())
            .rank(RetrievalQuery.ofText(term), 100).documents().size();
    }

    /** Distinct alphabetic words, so each one is found in exactly the chunk that holds it. */
    private static List<String> words(String prefix, int count) {
        List<String> words = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            words.add(prefix + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        }
        return words;
    }

    /** A tracker that refuses to publish once told to. */
    private static final class RefusingTracker extends DocumentVisibilityTracker {

        volatile boolean refusing;

        RefusingTracker(DocumentRegistry registry) {
            super(registry);
        }

        @Override
        public void registerFromMetadata(DocumentMetadata metadata, long commitEpoch) {
            if (refusing) {
                throw new IllegalStateException("publication refused");
            }
            super.registerFromMetadata(metadata, commitEpoch);
        }
    }

    /** A source whose documents can be replaced between runs. */
    private static final class MutableSource implements Source {

        private final String sourceId;
        private final Map<String, String> texts = new ConcurrentHashMap<>();

        MutableSource(String sourceId) {
            this.sourceId = sourceId;
        }

        void put(String documentId, String text) {
            texts.put(documentId, text);
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "mutable";
        }

        @Override
        public Stream<String> crawl() {
            return texts.keySet().stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) throws DocumentNotFoundException {
            String text = texts.get(documentId);
            if (text == null) {
                throw new DocumentNotFoundException("no such document: " + documentId);
            }
            return new FetchedDocument(documentId, text.getBytes(StandardCharsets.UTF_8),
                "text/plain");
        }

        @Override
        public void close() {
        }
    }

    private static final class IgnoredStatus implements IngestionOrchestrator.IngestionStatus {

        @Override
        public void update(String state, double progress, long ingested, long pending) {
        }

        @Override
        public void setSkipped(long skipped) {
        }

        @Override
        public void setError(String error) {
        }
    }
}
