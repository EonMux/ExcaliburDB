package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.shard.IndexOrchestrator;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ingestion pipeline, end to end, against a real storage engine.
 *
 * <p>The property that matters most is atomicity. A document is chunked, every chunk embedded and
 * written, and only then is the document published; a document whose chunks could not all be
 * handled must leave nothing behind that a query can reach. Half a document is not a smaller
 * answer - it is a wrong one that looks exactly like a right one, and no later stage can tell.</p>
 *
 * <p>The second property is that ingestion of one bad document does not stop the crawl. Sources
 * are remote and imperfect: a document that has been deleted since the crawl listed it, or one
 * that cannot be fetched, has to be skipped and recorded rather than ending the run and leaving
 * the rest of the corpus unindexed.</p>
 */
@DisplayName("Ingestion pipeline")
class IngestionPipelineTest {

    private static final int DIMENSION = 8;

    /** Records what the orchestrator reported, in order. */
    private static final class RecordingStatus
            implements IngestionOrchestrator.IngestionStatus {
        private final List<String> states = new CopyOnWriteArrayList<>();
        private volatile String state = "idle";
        private volatile double progress;
        private volatile long ingested;
        private volatile long pending;
        private volatile long skipped;
        private volatile String error;

        @Override
        public void update(String state, double progress, long ingested, long pending) {
            this.state = state;
            this.progress = progress;
            this.ingested = ingested;
            this.pending = pending;
            this.states.add(state);
        }

        @Override
        public void setSkipped(long skipped) {
            this.skipped = skipped;
        }

        @Override
        public void setError(String error) {
            this.error = error;
            this.states.add("error");
        }
    }

    private static final class RecordingRegistry implements IngestionOrchestrator.StatusRegistry {
        private final Map<String, RecordingStatus> statuses = new ConcurrentHashMap<>();

        @Override
        public IngestionOrchestrator.IngestionStatus getOrCreate(String sourceId) {
            return statuses.computeIfAbsent(sourceId, k -> new RecordingStatus());
        }

        RecordingStatus of(String sourceId) {
            return statuses.get(sourceId);
        }
    }

    /** A source serving documents from a map, with configurable failures. */
    private static class FakeSource implements Source {
        private final String sourceId;
        private final Map<String, String> documents;
        /** Documents supplied as bytes, for content that is not text in any encoding. */
        private final Map<String, byte[]> rawDocuments = new ConcurrentHashMap<>();
        private final Map<String, String> rawContentTypes = new ConcurrentHashMap<>();
        /** What encoding a raw document is declared to be in; UTF-8 when unnamed. */
        private final Map<String, java.nio.charset.Charset> charsets = new ConcurrentHashMap<>();
        private final AtomicInteger fetches = new AtomicInteger();
        private volatile boolean closed;
        private volatile SourceException crawlFailure;

        FakeSource(String sourceId, Map<String, String> documents) {
            this.sourceId = sourceId;
            this.documents = documents;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "fake";
        }

        /**
         * Adds a document whose bytes are served as they are, with no encoding step.
         *
         * @param documentId What to serve it under
         * @param content    The bytes
         * @param contentType What the source claims it is
         * @return This source
         */
        FakeSource withRaw(String documentId, byte[] content, String contentType) {
            rawDocuments.put(documentId, content);
            rawContentTypes.put(documentId, contentType);
            return this;
        }

        @Override
        public Stream<String> crawl() throws SourceException {
            if (crawlFailure != null) {
                throw crawlFailure;
            }
            return Stream.concat(documents.keySet().stream(), rawDocuments.keySet().stream());
        }

        @Override
        public FetchedDocument fetch(String documentId)
                throws SourceException, DocumentNotFoundException {
            fetches.incrementAndGet();
            byte[] raw = rawDocuments.get(documentId);
            if (raw != null) {
                return new FetchedDocument(documentId, raw, rawContentTypes.get(documentId),
                    charsets.getOrDefault(documentId, StandardCharsets.UTF_8), 0L);
            }
            String text = documents.get(documentId);
            if (text == null) {
                throw new DocumentNotFoundException("no such document: " + documentId);
            }
            if (text.equals("!fail")) {
                throw new SourceException("cannot fetch " + documentId);
            }
            return new FetchedDocument(documentId,
                text.getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        @Override
        public boolean hasChanged(Instant since) {
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A chunk index that records what reached it and when it was refreshed.
     *
     * <p>{@code refreshedWith} holds, per refresh, how many documents had been added by then - so
     * a test can tell a refresh during a run from the one at its end.</p>
     */
    private static class RecordingChunkIndex
            implements com.eonmux.excalibur.ingest.lexical.ChunkIndex {

        final List<Map<String, String>> documents = new CopyOnWriteArrayList<>();
        final List<Integer> refreshedWith = new CopyOnWriteArrayList<>();

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
        public int erase(java.util.Set<String> chunkIds) {
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
        public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactIfDue(
                java.util.function.Function<String, String> textOf) {
            return com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult
                .declined("not due");
        }

        @Override
        public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactNow(
                java.util.function.Function<String, String> textOf) {
            return compactIfDue(textOf);
        }

        @Override
        public com.eonmux.excalibur.retrieval.rank.Ranker ranker(
                com.eonmux.excalibur.retrieval.expand.QueryExpander expander) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String engine() {
            return "recording";
        }

        @Override
        public com.eonmux.excalibur.ingest.lexical.ChunkIndexStats stats() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private RecordingRegistry registry;
    private EmbeddingClient embeddings;
    private IndexOrchestrator index;

    @BeforeEach
    void setUp() throws java.io.IOException {
        Path dataDir = java.nio.file.Files.createDirectories(tempDir.resolve("data"));
        storage = new StorageEngine(dataDir);
        storage.start();
        registry = new RecordingRegistry();

        embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyString())).thenAnswer(invocation ->
            vectorFor(invocation.getArgument(0, String.class)));
        // Ingestion embeds a document's chunks in batches, so this is the call that matters.
        // Each vector is derived from its own text, which is what lets a test detect a batch
        // whose results came back paired with the wrong chunks.
        when(embeddings.embedBatch(org.mockito.ArgumentMatchers.anyList()))
            .thenAnswer(invocation -> {
                java.util.List<String> texts = invocation.getArgument(0);
                java.util.List<float[]> out = new java.util.ArrayList<>(texts.size());
                for (String t : texts) {
                    out.add(vectorFor(t));
                }
                return out;
            });

        index = mock(IndexOrchestrator.class);
        when(index.isBackpressured()).thenReturn(false);
        when(index.awaitBackpressureRelease(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception ignored) {
                // Only tidy-up; the assertions have already run.
            }
        }
    }

    /** Deterministic per-text vector, so a mispaired batch is detectable. */
    private static float[] vectorFor(String text) {
        float[] vector = new float[DIMENSION];
        int hash = text.hashCode();
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = ((hash >> i) & 1) == 0 ? 0.25f : 0.75f;
        }
        return vector;
    }

    private IngestionOrchestrator orchestrator() {
        return new IngestionOrchestrator(new TextChunker(), embeddings, storage, index, registry);
    }

    /** Runs one source to completion and returns the status it reported. */
    private RecordingStatus ingest(IngestionOrchestrator orchestrator, FakeSource source) {
        orchestrator.startIngestion(source);
        awaitIdle(orchestrator, source.getSourceId());
        return registry.of(source.getSourceId());
    }

    /** Ingestion runs on its own virtual thread; wait for it rather than sleeping a fixed time. */
    private static void awaitIdle(IngestionOrchestrator orchestrator, String sourceId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (orchestrator.isIngestionActive(sourceId)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("ingestion of " + sourceId + " did not finish in 30s");
            }
            Thread.onSpinWait();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + sourceId, e);
            }
        }
    }

    /** The raw stored value for a chunk, or null when it was never written. */
    private byte[] storedChunk(String sourceId, String documentId, int chunkIndex) {
        java.lang.foreign.MemorySegment value = storage.get(
            ChunkIdentifiers.chunkId(sourceId, documentId, chunkIndex)
                .getBytes(StandardCharsets.UTF_8));
        return value == null ? null : value.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }

    private boolean storedChunkExists(String sourceId, String documentId, int chunkIndex) {
        java.lang.foreign.MemorySegment value = storage.get(
            ChunkIdentifiers.chunkId(sourceId, documentId, chunkIndex)
                .getBytes(StandardCharsets.UTF_8));
        return value != null && value.byteSize() > 0;
    }

    @Nested
    @DisplayName("a successful run")
    class SuccessfulRun {

        @Test
        @DisplayName("every crawled document is chunked, embedded and written")
        void documentsAreIngested() {
            FakeSource source = new FakeSource("docs", Map.of(
                "a.txt", "Ada Lovelace wrote the first algorithm.",
                "b.txt", "Charles Babbage designed the analytical engine."));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error).isNull();
            assertThat(status.state).isEqualTo("idle");
            assertThat(status.progress).isEqualTo(1.0);
            assertThat(status.ingested)
                .as("at least one chunk per document")
                .isGreaterThanOrEqualTo(2);
            assertThat(storedChunkExists("docs", "a.txt", 0)).isTrue();
            assertThat(storedChunkExists("docs", "b.txt", 0)).isTrue();
        }

        @Test
        @DisplayName("a crawled chunk's terms reach the membership filter, under the segment it landed in")
        void crawledTermsPopulateTheMembershipFilter() {
            // Stage 1 of the query pipeline prunes segments that cannot hold a query's terms.
            // It was fed only by the reindex path, which targets a segment it chose itself, so
            // a node that ingested and never reindexed ran every query against every segment.
            com.eonmux.excalibur.filter.HierarchicalCuckooFilter membership =
                new com.eonmux.excalibur.filter.HierarchicalCuckooFilter(1_000_000);
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setMembershipFilter(membership);

            ingest(orchestrator, new FakeSource("docs",
                Map.of("a.txt", "Ada Lovelace wrote the first algorithm.")));

            java.util.List<String> segments = membership.findCandidateSegments(
                "lovelace".getBytes(StandardCharsets.UTF_8));
            assertThat(segments)
                .as("a term of an ingested chunk must be findable in the filter")
                .isNotEmpty();
            assertThat(membership.coversSegment(segments.get(0)))
                .as("the segment the chunk landed in must now be prunable")
                .isTrue();
            assertThat(membership.findCandidateSegments(
                "babbage".getBytes(StandardCharsets.UTF_8)))
                .as("a term no ingested chunk holds must not be reported")
                .isEmpty();
        }

        @Test
        @DisplayName("ingestion without a membership filter still writes its chunks")
        void noMembershipFilterIsNotAFailure() {
            RecordingStatus status = ingest(orchestrator(),
                new FakeSource("docs", Map.of("a.txt", "some text")));

            assertThat(status.error).isNull();
            assertThat(storedChunkExists("docs", "a.txt", 0)).isTrue();
        }

        @Test
        @DisplayName("chunks are written under the shared naming convention")
        void chunksUseTheSharedConvention() {
            // The key written here is the one excalibur-shard derives a document key from and
            // excalibur-query then looks up. Writing a key of a different shape does not fail;
            // it makes the document unreachable.
            FakeSource source = new FakeSource("src", Map.of("doc", "one short document"));

            ingest(orchestrator(), source);

            assertThat(storedChunkExists("src", "doc", 0)).isTrue();
            assertThat(ChunkIdentifiers.documentKeyOfChunk(
                ChunkIdentifiers.chunkId("src", "doc", 0)))
                .isEqualTo(ChunkIdentifiers.documentKey("src", "doc"));
        }

        @Test
        @DisplayName("the run passes through crawling, embedding and indexing before idle")
        void statesProgressInOrder() {
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "some text"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.states).startsWith("crawling");
            assertThat(status.states).contains("embedding", "indexing");
            assertThat(status.states.get(status.states.size() - 1)).isEqualTo("idle");
        }

        @Test
        @DisplayName("a source yielding nothing finishes cleanly rather than erroring")
        void emptySourceFinishesClean() {
            RecordingStatus status = ingest(orchestrator(), new FakeSource("empty", Map.of()));

            assertThat(status.error).isNull();
            assertThat(status.state).isEqualTo("idle");
            assertThat(status.progress).isEqualTo(1.0);
            assertThat(status.ingested).isZero();
        }

        @Test
        @DisplayName("progress never leaves [0.0, 1.0] and counts never go negative")
        void progressStaysInRange() {
            Map<String, String> documents = new java.util.HashMap<>();
            for (int i = 0; i < 12; i++) {
                documents.put("doc" + i, "text for document " + i);
            }

            RecordingStatus status = ingest(orchestrator(), new FakeSource("many", documents));

            assertThat(status.progress).isBetween(0.0, 1.0);
            assertThat(status.ingested).isNotNegative();
            assertThat(status.pending).isNotNegative();
        }

        @Test
        @DisplayName("the source is left open, because the registry owns it")
        void theSourceIsNotClosed() {
            // The same instance is held by the API layer's registry and reused by the reindexer.
            // Closing it here would break every later crawl of that source.
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "text"));

            ingest(orchestrator(), source);

            assertThat(source.closed).isFalse();
        }
    }

    @Nested
    @DisplayName("imperfect sources")
    class ImperfectSources {

        @Test
        @DisplayName("a document that has gone missing is skipped, not fatal")
        void missingDocumentsAreSkipped() {
            // crawl() listed it; by the time fetch() ran it was deleted. The rest of the corpus
            // still has to be indexed.
            FakeSource source = new FakeSource("docs", Map.of(
                "present.txt", "real text here",
                "vanished.txt", "!missing")) {
                @Override
                public Stream<String> crawl() {
                    return Stream.of("present.txt", "gone.txt");
                }
            };

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.state).isEqualTo("idle");
            assertThat(status.error)
                .as("the run finishes, but a document it was asked for is missing from it")
                .isNotNull()
                .contains("1 of 2");
            assertThat(storedChunkExists("docs", "present.txt", 0))
                .as("the document that was there is indexed")
                .isTrue();
        }

        @Test
        @DisplayName("a document that cannot be fetched is skipped, not fatal")
        void unfetchableDocumentsAreSkipped() {
            FakeSource source = new FakeSource("docs", Map.of(
                "good.txt", "real text here",
                "bad.txt", "!fail"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error)
                .as("half the corpus is absent from the index and the status has to say so")
                .isNotNull()
                .contains("1 of 2");
            assertThat(storedChunkExists("docs", "good.txt", 0)).isTrue();
            assertThat(storedChunkExists("docs", "bad.txt", 0))
                .as("a document that could not be fetched leaves nothing behind")
                .isFalse();
        }

        @Test
        @DisplayName("a run that ingested everything it was asked for reports no error")
        void aCleanRunReportsNoError() {
            FakeSource source = new FakeSource("docs", Map.of(
                "a.txt", "real text here",
                "b.txt", "more real text"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error).isNull();
            assertThat(status.state).isEqualTo("idle");
        }

        @Test
        @DisplayName("a run that ingested nothing at all is not reported as a clean finish")
        void aRunThatDroppedEverythingSaysSo() {
            // Otherwise this is indistinguishable from a source that had nothing to ingest:
            // state idle, progress 1.0, nothing ingested, no error.
            FakeSource source = new FakeSource("docs", Map.of(
                "a.txt", "!fail",
                "b.txt", "!fail"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error)
                .isNotNull()
                .contains("2 of 2");
            assertThat(status.ingested).isZero();
        }

        @Test
        @DisplayName("a crawl that fails is reported as an error, not as a completed run")
        void crawlFailureIsReported() {
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "text"));
            source.crawlFailure = new SourceException("root is unreadable");

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error)
                .as("a run that never listed anything must not settle on idle at 100%")
                .isNotNull()
                .contains("root is unreadable");
        }

        @Test
        @DisplayName("an embedding failure leaves the document unpublished rather than partial")
        void embeddingFailureLeavesNothingHalfWritten() {
            // This is the atomicity guarantee: a chunk that could not be embedded means the
            // document is not published, and a query must not be able to reach part of it.
            when(embeddings.embedBatch(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new com.eonmux.excalibur.llm.LLMException(
                    "embedding backend is down", com.eonmux.excalibur.llm.LLMException.ErrorType.NETWORK));
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "some text to embed"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(storedChunkExists("docs", "a.txt", 0))
                .as("no chunk of an unpublishable document is left readable")
                .isFalse();
            assertThat(status.state)
                .as("the run itself completes; one document failing is not a crawl failure")
                .isIn("idle", "error");
        }

        @Test
        @DisplayName("a document's chunks are embedded in batches, not one request each")
        void chunksAreEmbeddedInBatches() {
            // One request per chunk was the ingestion throughput ceiling: request overhead, not
            // the model, is what three different sub-1B models all bottomed out against.
            String text = "sentence about storage engines. ".repeat(4000);
            FakeSource source = new FakeSource("docs", Map.of("big.txt", text));

            ingest(orchestrator(), source);

            org.mockito.ArgumentCaptor<java.util.List> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
            verify(embeddings, org.mockito.Mockito.atLeastOnce()).embedBatch(captor.capture());
            verify(embeddings, org.mockito.Mockito.never()).embed(anyString());

            assertThat(captor.getAllValues()).isNotEmpty();
            assertThat(captor.getAllValues().get(0).size())
                .as("a batch carries several chunks and never exceeds the bound")
                .isGreaterThan(1)
                .isLessThanOrEqualTo(DocumentIngestor.EMBED_BATCH_SIZE);
        }

        @Test
        @DisplayName("a node with no embedding client still indexes documents")
        void lexicalOnlyIngestionPublishesDocuments() {
            // The point of the whole exercise: retrieval through Lucene and the graph does not
            // need vectors, and requiring them made a corpus-wide embedding pass the price of
            // indexing anything at all.
            IngestionOrchestrator lexical =
                new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
            FakeSource source = new FakeSource("docs", Map.of(
                "a.txt", "the OOM killer terminated the process under memory pressure"));

            RecordingStatus status = ingest(lexical, source);

            assertThat(status.ingested)
                .as("documents are published without any embedding endpoint")
                .isGreaterThan(0);
            assertThat(storedChunkExists("docs", "a.txt", 0))
                .as("the chunk is readable")
                .isTrue();
            verify(embeddings, org.mockito.Mockito.never())
                .embedBatch(org.mockito.ArgumentMatchers.anyList());
            verify(embeddings, org.mockito.Mockito.never()).embed(anyString());
        }

        @Test
        @DisplayName("a lexically indexed chunk keeps its text and reports no vector")
        void lexicalChunksAreDecodable() {
            IngestionOrchestrator lexical =
                new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
            ingest(lexical, new FakeSource("docs", Map.of("a.txt", "cgroup memory limit exceeded")));

            byte[] value = storedChunk("docs", "a.txt", 0);
            assertThat(value).as("chunk was written").isNotNull();
            assertThat(com.eonmux.excalibur.core.codec.ChunkPayloadCodec.textOf(value))
                .as("the text is what retrieval will rank; it must survive")
                .contains("cgroup memory limit exceeded");
            assertThat(com.eonmux.excalibur.core.codec.ChunkPayloadCodec.embeddingOf(value))
                .as("no vector, reported as absent rather than as corruption")
                .isNull();
            assertThat(com.eonmux.excalibur.core.codec.ChunkPayloadCodec.dimensionOf(value)).isZero();
        }

        @Test
        @DisplayName("the native engine indexes and answers from the same ingestion run")
        void chunksAreSearchableOnTheNativeEngine() throws Exception {
            // The same property as the Lucene case below, through the engine's own index: a
            // corpus becomes retrievable without a corpus-wide embedding pass, and without a
            // second copy of every chunk's text on disk.
            java.nio.file.Path dir = tempDir.resolve("chunk-index");
            java.util.concurrent.atomic.AtomicLong epoch =
                new java.util.concurrent.atomic.AtomicLong(1);
            try (com.eonmux.excalibur.ingest.lexical.NativeChunkIndex native_ =
                     com.eonmux.excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                         com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner.standard())) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(native_);

                ingest(lexical, new FakeSource("docs", Map.of(
                    "oom.txt", "the OOM killer terminated the process under memory pressure",
                    "tls.txt", "the certificate chain could not be verified during the handshake")));

                assertThat(native_.indexedCount()).as("chunks reached the index").isGreaterThan(0);
                assertThat(native_.collisionCount()).isZero();

                // The run refreshed at its end, publishing at the next epoch.
                epoch.incrementAndGet();
                assertThat(native_.liveChunks()).isGreaterThan(0);

                var ranker = native_.ranker(com.eonmux.excalibur.retrieval.expand.QueryExpander.identity());
                var ranked = ranker.rank(
                    com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("memory pressure"), 5);

                assertThat(ranked.documents()).as("BM25 found the chunk").isNotEmpty();
                assertThat(native_.chunkIdOf(ranked.documents().get(0).documentId()))
                    .as("a hit resolves to the chunk id a caller can look up")
                    .contains("oom.txt");

                var other = ranker.rank(
                    com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("certificate handshake"), 5);
                assertThat(native_.chunkIdOf(other.documents().get(0).documentId()))
                    .as("a different query reaches a different document")
                    .contains("tls.txt");
            }
        }

        @Test
        @DisplayName("ingesting a source twice does not index its chunks twice")
        void reingestionDoesNotDuplicate() throws Exception {
            // A source is re-crawled on a timer, and every crawl writes the same deterministic
            // chunk ids. An index that appended would double on every pass: the same chunk taking
            // two of a caller's result slots, counted twice by fusion, and growing without bound.
            java.nio.file.Path dir = tempDir.resolve("chunk-index-reingest");
            java.util.concurrent.atomic.AtomicLong epoch =
                new java.util.concurrent.atomic.AtomicLong(1);
            try (com.eonmux.excalibur.ingest.lexical.NativeChunkIndex native_ =
                     com.eonmux.excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                         com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner.standard())) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(native_);
                FakeSource source = new FakeSource("docs", Map.of(
                    "oom.txt", "the OOM killer terminated the process under memory pressure"));

                ingest(lexical, source);
                epoch.incrementAndGet();
                ingest(lexical, source);
                epoch.incrementAndGet();

                var ranked = native_.ranker(com.eonmux.excalibur.retrieval.expand.QueryExpander.identity())
                    .rank(com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("memory pressure"), 10);

                assertThat(ranked.documents())
                    .as("one chunk, however many times its source was crawled")
                    .hasSize(1);
                assertThat(native_.liveChunks()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a source that changed stops matching the text it used to hold")
        void reingestionReplacesStaleText() throws Exception {
            java.nio.file.Path dir = tempDir.resolve("chunk-index-changed");
            java.util.concurrent.atomic.AtomicLong epoch =
                new java.util.concurrent.atomic.AtomicLong(1);
            try (com.eonmux.excalibur.ingest.lexical.NativeChunkIndex native_ =
                     com.eonmux.excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                         com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner.standard())) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(native_);

                ingest(lexical, new FakeSource("docs", Map.of(
                    "note.txt", "the TLS handshake failed")));
                epoch.incrementAndGet();
                ingest(lexical, new FakeSource("docs", Map.of(
                    "note.txt", "the certificate expired")));
                epoch.incrementAndGet();

                var ranker = native_.ranker(com.eonmux.excalibur.retrieval.expand.QueryExpander.identity());
                assertThat(ranker.rank(
                        com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("handshake"), 10)
                        .documents())
                    .as("storage holds the new text under this id, so the old words must not match")
                    .isEmpty();
                assertThat(ranker.rank(
                        com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("expired"), 10)
                        .documents())
                    .hasSize(1);
            }
        }

        @Test
        @DisplayName("ingested chunks are BM25-searchable without any embedding")
        void chunksAreLexicallySearchable() throws Exception {
            // The whole point of items 1 and 2 together: a corpus becomes retrievable without a
            // corpus-wide embedding pass.
            java.nio.file.Path dir = tempDir.resolve("lucene-chunks");
            try (com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex lucene =
                     com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0)) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(lucene);

                ingest(lexical, new FakeSource("docs", Map.of(
                    "oom.txt", "the OOM killer terminated the process under memory pressure",
                    "tls.txt", "the certificate chain could not be verified during the handshake")));

                assertThat(lucene.indexedCount()).as("chunks reached Lucene").isGreaterThan(0);
                assertThat(lucene.collisionCount()).isZero();

                // Through the same ranker the query path uses, not a hand-built query.
                var ranker = new com.eonmux.excalibur.retrieval.lexical.LuceneRanker(lucene.index());
                var ranked = ranker.rank(
                    com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("memory pressure"), 5);

                assertThat(ranked.documents()).as("BM25 found the chunk").isNotEmpty();
                String chunkId = lucene.chunkIdOf(ranked.documents().get(0).documentId());
                assertThat(chunkId)
                    .as("a hit resolves to the chunk id a caller can look up")
                    .contains("oom.txt");

                var other = ranker.rank(
                    com.eonmux.excalibur.retrieval.rank.RetrievalQuery.ofText("certificate handshake"), 5);
                assertThat(lucene.chunkIdOf(other.documents().get(0).documentId()))
                    .as("a different query reaches a different document")
                    .contains("tls.txt");
            }
        }

        @Test
        @DisplayName("an empty document produces no chunks and no error")
        void emptyDocumentsAreHarmless() {
            FakeSource source = new FakeSource("docs", Map.of(
                "empty.txt", "",
                "real.txt", "actual content here"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error)
                .as("a document with nothing in it was not dropped: it was listed and had "
                    + "nothing to give, which is not a failure to report to the caller")
                .isNull();
            assertThat(storedChunkExists("docs", "real.txt", 0)).isTrue();
        }

        @Test
        @DisplayName("a document that could not be embedded is reported as dropped, not ingested")
        void unembeddableDocumentsAreReported() throws Exception {
            // Every way a document fails to be ingested other than by throwing used to go
            // uncounted - too large, too many chunks, unembeddable, rolled back - so the run
            // reported the document as ingested and the caller was told it had been stored when
            // nothing of it had. Refusing to embed is the cheapest of those to reach.
            when(embeddings.embedBatch(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    java.util.List<String> texts = invocation.getArgument(0);
                    if (texts.stream().anyMatch(t -> t.contains("unembeddable"))) {
                        throw new com.eonmux.excalibur.llm.LLMException("endpoint refused",
                            com.eonmux.excalibur.llm.LLMException.ErrorType.HTTP_ERROR);
                    }
                    java.util.List<float[]> out = new java.util.ArrayList<>(texts.size());
                    for (String t : texts) {
                        out.add(vectorFor(t));
                    }
                    return out;
                });
            FakeSource source = new FakeSource("docs", Map.of(
                "bad.txt", "this one is unembeddable",
                "real.txt", "actual content here"));

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error)
                .as("the status endpoint is the only place an operator would see this; the "
                    + "document was listed, had content, and none of it was stored")
                .contains("1 of 2");
            assertThat(storedChunkExists("docs", "real.txt", 0))
                .as("and the rest of the crawl still ran")
                .isTrue();
        }
    }


    @Nested
    @DisplayName("shutting down while a request is ingesting")
    class SynchronousShutdown {

        /**
         * Lets a test act at the one point inside {@code ingestNow} between its shutdown check
         * and the work itself: {@code shutdown()} waits on nothing but the map of synchronous
         * runs, so a run that is past its check and not yet in that map is a run shutdown
         * reports a clean stop over - and the caller then closes the Arena underneath it.
         */
        private final class HookedRegistry implements IngestionOrchestrator.StatusRegistry {
            private final Runnable hook;

            HookedRegistry(Runnable hook) {
                this.hook = hook;
            }

            @Override
            public IngestionOrchestrator.IngestionStatus getOrCreate(String sourceId) {
                hook.run();
                return new RecordingStatus();
            }
        }

        @Test
        @DisplayName("a synchronous run is reachable by shutdown before it does any work")
        void publishesItselfBeforeWorking() {
            java.util.concurrent.atomic.AtomicReference<IngestionOrchestrator> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
            AtomicInteger reachable = new AtomicInteger(-1);
            IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(),
                embeddings, storage, index,
                new HookedRegistry(() ->
                    reachable.set(holder.get().stopIngestion("docs") ? 1 : 0)));
            holder.set(orchestrator);

            orchestrator.ingestNow(new FakeSource("docs", Map.of("a.txt", "some text")));

            assertThat(reachable.get())
                .as("a run shutdown cannot see is a run it will report a clean stop over")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a run that was interrupted hands its thread back with the flag cleared")
        void clearsTheInterruptBeforeReturning() {
            // ingestNow runs on the request thread, and a pooled thread goes on to serve other
            // requests. An interrupt left set fails the next request at its first channel
            // operation with ClosedByInterruptException, which closes that channel for good.
            IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(),
                embeddings, storage, index,
                new HookedRegistry(() -> Thread.currentThread().interrupt()));

            orchestrator.ingestNow(new FakeSource("docs", Map.of("a.txt", "some text")));

            assertThat(Thread.interrupted())
                .as("the interrupt was addressed to the run, not to whatever the thread does next")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("interlock with erasure")
    class DocumentInterlock {

        @Test
        @DisplayName("a document is written and published under its own lock")
        void ingestionHoldsTheDocumentLock() throws Exception {
            // An erasure that runs between a document's chunks being written and its being
            // registered deletes chunks the registry does not name yet, and the registration
            // then puts back an entry for content that is gone. Holding the lock is what makes
            // the two orders the only two outcomes.
            IngestionOrchestrator orchestrator = orchestrator();
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "some text to ingest"));
            java.util.concurrent.CountDownLatch held =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

            Thread holder = new Thread(() -> com.eonmux.excalibur.ingest.registry.DocumentLocks.shared()
                .inDocument("docs:a.txt", () -> {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            holder.setDaemon(true);
            holder.start();
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

            Thread ingesting = new Thread(() -> orchestrator.ingestNow(source));
            ingesting.setDaemon(true);
            ingesting.start();
            try {
                ingesting.join(500);
                assertThat(storedChunkExists("docs", "a.txt", 0))
                    .as("nothing of the document may land while another holder has it")
                    .isFalse();
            } finally {
                release.countDown();
            }

            ingesting.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(storedChunkExists("docs", "a.txt", 0))
                .as("and it goes through as soon as the lock is free")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("a rollback")
    class Rollback {

        /** Publishes nothing, so a document reaches the rollback path with its chunks written. */
        private static final class RefusingTracker
                extends com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker {

            RefusingTracker() {
                super(new com.eonmux.excalibur.ingest.registry.DocumentRegistry());
            }

            @Override
            public void registerFromMetadata(
                    com.eonmux.excalibur.ingest.registry.DocumentMetadata metadata, long commitEpoch) {
                throw new IllegalStateException("publication refused");
            }
        }

        @Test
        @DisplayName("a rolled back document has its graph nodes retired as well as its chunks")
        void rollbackRetiresVectors() {
            // The integration worker inserts asynchronously from what storage holds, so a
            // document rolled back after its chunks were written may already be in a graph -
            // rankable by name and distance, with no text behind it and nothing else able to
            // reach the node.
            List<String> retired = new CopyOnWriteArrayList<>();
            IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(),
                embeddings, storage, index, registry, new RefusingTracker());
            orchestrator.setVectorEraser(documentKey -> {
                retired.add(documentKey);
                return 1;
            });

            orchestrator.ingestNow(new FakeSource("docs", Map.of("a.txt", "some text")));

            assertThat(storedChunkExists("docs", "a.txt", 0))
                .as("precondition: the rollback removed the chunks it had written")
                .isFalse();
            assertThat(retired)
                .as("a node outlives the chunk it was built from unless it is retired too")
                .containsExactly("docs:a.txt");
        }

        @Test
        @DisplayName("a rolled back document never reaches the lexical index")
        void rollbackIndexesNothing() {
            // Indexed as its chunks were written, a document another run's refresh published
            // before it rolled back would be a lexical hit on a chunk no query can see.
            RecordingChunkIndex lexical = new RecordingChunkIndex();
            IngestionOrchestrator orchestrator = new IngestionOrchestrator(new TextChunker(),
                embeddings, storage, index, registry, new RefusingTracker());
            orchestrator.setLexicalIndex(lexical);

            orchestrator.ingestNow(new FakeSource("docs", Map.of("a.txt", "some text")));

            assertThat(lexical.documents).isEmpty();
        }
    }

    @Nested
    @DisplayName("lexical visibility during a run")
    class LexicalPacing {

        @Test
        @DisplayName("a document reaches the lexical index whole, once it is published")
        void documentsArriveWhole() {
            RecordingChunkIndex lexical = new RecordingChunkIndex();
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setLexicalIndex(lexical);
            String longText = "memory pressure caused the OOM killer to run. ".repeat(400);

            orchestrator.ingestNow(new FakeSource("docs", Map.of("long.txt", longText)));

            assertThat(lexical.documents).hasSize(1);
            assertThat(lexical.documents.get(0))
                .as("precondition: the document is long enough to chunk more than once")
                .hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("a long run refreshes as it goes, not only when it ends")
        void longRunsRefreshAsTheyGo() {
            // A crawl can run for days, and a document it published at the start is stored and
            // visible to every other signal. Refreshing only at the end kept it out of BM25 for
            // all of that time - on a lexical-only node, out of search altogether.
            RecordingChunkIndex lexical = new RecordingChunkIndex();
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setLexicalIndex(lexical);
            orchestrator.setLexicalRefreshIntervalMs(1);
            FakeSource slow = new FakeSource("docs", Map.of(
                "a.txt", "alpha", "b.txt", "beta", "c.txt", "gamma", "d.txt", "delta")) {
                @Override
                public FetchedDocument fetch(String documentId)
                        throws SourceException, DocumentNotFoundException {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.fetch(documentId);
                }
            };

            orchestrator.ingestNow(slow);

            assertThat(lexical.refreshedWith)
                .as("a refresh happened while documents were still to come")
                .anyMatch(published -> published > 0 && published < 4);
            assertThat(lexical.refreshedWith).last().isEqualTo(4);
        }

        @Test
        @DisplayName("a run that fails partway still makes what it published searchable")
        void failedRunsAreRefreshed() {
            // A compaction that keeps throwing fails the run at its closing refresh. The
            // documents already published are in storage; left unrefreshed, they would be
            // missing from BM25 until some later run happened to refresh.
            RecordingChunkIndex lexical = new RecordingChunkIndex() {
                @Override
                public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactIfDue(
                        java.util.function.Function<String, String> textOf) {
                    throw new IllegalStateException("compaction failed");
                }
            };
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setLexicalIndex(lexical);
            orchestrator.setLexicalRefreshIntervalMs(1);
            FakeSource slow = new FakeSource("docs", Map.of("a.txt", "alpha", "b.txt", "beta")) {
                @Override
                public FetchedDocument fetch(String documentId)
                        throws SourceException, DocumentNotFoundException {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.fetch(documentId);
                }
            };

            assertThatThrownBy(() -> orchestrator.ingestNow(slow))
                .isInstanceOf(IngestionOrchestrator.IngestionFailedException.class);

            assertThat(lexical.documents).as("precondition: a document was published").isNotEmpty();
            assertThat(lexical.refreshedWith).last().isEqualTo(lexical.documents.size());
        }

        @Test
        @DisplayName("a paced refresh that fails is logged and the run goes on")
        void aFailedPacedRefreshDoesNotStopTheRun() {
            // The documents stay published and reach BM25 at the next refresh. Failing the run
            // would abandon every document it had not reached for the sake of ones it had.
            java.util.concurrent.atomic.AtomicBoolean failedOnce =
                new java.util.concurrent.atomic.AtomicBoolean();
            RecordingChunkIndex lexical = new RecordingChunkIndex() {
                @Override
                public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactIfDue(
                        java.util.function.Function<String, String> textOf) {
                    if (failedOnce.compareAndSet(false, true)) {
                        throw new IllegalStateException("compaction failed");
                    }
                    return super.compactIfDue(textOf);
                }
            };
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setLexicalIndex(lexical);
            orchestrator.setLexicalRefreshIntervalMs(1);
            FakeSource slow = new FakeSource("docs", Map.of("a.txt", "alpha", "b.txt", "beta")) {
                @Override
                public FetchedDocument fetch(String documentId)
                        throws SourceException, DocumentNotFoundException {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.fetch(documentId);
                }
            };

            IngestionOrchestrator.IngestionOutcome outcome = orchestrator.ingestNow(slow);

            assertThat(failedOnce).as("precondition: a paced refresh failed").isTrue();
            assertThat(outcome.stopped()).isFalse();
            assertThat(lexical.documents).hasSize(2);
            assertThat(lexical.refreshedWith).last().isEqualTo(2);
        }

        @Test
        @DisplayName("an interrupt that arrives during a paced refresh still stops the run")
        void anInterruptDuringAPacedRefreshStopsTheRun() {
            java.util.concurrent.atomic.AtomicBoolean interruptedOnce =
                new java.util.concurrent.atomic.AtomicBoolean();
            RecordingChunkIndex lexical = new RecordingChunkIndex() {
                @Override
                public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactIfDue(
                        java.util.function.Function<String, String> textOf) {
                    if (interruptedOnce.compareAndSet(false, true)) {
                        Thread.currentThread().interrupt();
                    }
                    return super.compactIfDue(textOf);
                }
            };
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setLexicalIndex(lexical);
            orchestrator.setLexicalRefreshIntervalMs(1);
            FakeSource slow = new FakeSource("docs",
                    Map.of("a.txt", "alpha", "b.txt", "beta", "c.txt", "gamma")) {
                @Override
                public FetchedDocument fetch(String documentId)
                        throws SourceException, DocumentNotFoundException {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.fetch(documentId);
                }
            };

            try {
                IngestionOrchestrator.IngestionOutcome outcome = orchestrator.ingestNow(slow);

                assertThat(interruptedOnce).as("precondition: a paced refresh ran").isTrue();
                assertThat(outcome.stopped()).isTrue();
                assertThat(lexical.documents).hasSizeLessThan(3);
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("the refresh interval must be positive")
        void intervalMustBePositive() {
            assertThatThrownBy(() -> orchestrator().setLexicalRefreshIntervalMs(0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a source already being ingested is not started twice")
        void duplicateStartsAreIgnored() {
            IngestionOrchestrator orchestrator = orchestrator();
            Map<String, String> documents = new java.util.HashMap<>();
            for (int i = 0; i < 30; i++) {
                documents.put("doc" + i, "text for document " + i);
            }
            FakeSource source = new FakeSource("docs", documents);

            orchestrator.startIngestion(source);
            orchestrator.startIngestion(source);
            awaitIdle(orchestrator, "docs");

            assertThat(source.fetches.get())
                .as("each document is fetched once, not once per concurrent start")
                .isEqualTo(documents.size());
        }

        @Test
        @DisplayName("an inactive source reports as inactive")
        void inactiveSourcesAreReported() {
            assertThat(orchestrator().isIngestionActive("never-started")).isFalse();
        }

        @Test
        @DisplayName("shutdown stops accepting new work")
        void shutdownRefusesNewWork() {
            IngestionOrchestrator orchestrator = orchestrator();

            assertThat(orchestrator.shutdown()).isTrue();
            orchestrator.startIngestion(new FakeSource("docs", Map.of("a.txt", "text")));

            assertThat(orchestrator.isIngestionActive("docs"))
                .as("a shutting-down orchestrator must not start another crawl")
                .isFalse();
        }

        @Test
        @DisplayName("shutting down twice is harmless")
        void shutdownIsIdempotent() {
            IngestionOrchestrator orchestrator = orchestrator();

            assertThat(orchestrator.shutdown()).isTrue();
            assertThat(orchestrator.shutdown()).isTrue();
        }

        @Test
        @DisplayName("a null source is refused")
        void nullSourceIsRefused() {
            assertThatThrownBy(() -> orchestrator().startIngestion(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("stopping one source")
    class Stopping {

        /** A source that hands out one document per permit, so a run can be caught mid-flight. */
        private static final class GatedSource implements Source {

            private final java.util.concurrent.Semaphore gate =
                new java.util.concurrent.Semaphore(0);
            private final java.util.concurrent.CountDownLatch fetched;
            private final int documents;

            GatedSource(int documents) {
                this.documents = documents;
                this.fetched = new java.util.concurrent.CountDownLatch(1);
            }

            @Override
            public String getSourceId() {
                return "gated";
            }

            @Override
            public String getSourceType() {
                return "gated";
            }

            @Override
            public Stream<String> crawl() {
                return java.util.stream.IntStream.range(0, documents)
                    .mapToObj(i -> "doc" + i);
            }

            @Override
            public boolean hasChanged(java.time.Instant since) {
                return true;
            }

            @Override
            public FetchedDocument fetch(String documentId) throws SourceException {
                try {
                    gate.acquire();
                } catch (InterruptedException e) {
                    // What any interruptible source has to do: restore the flag, so the loop
                    // that asked for the stop can see that it was granted.
                    Thread.currentThread().interrupt();
                    throw new SourceException("interrupted fetching " + documentId);
                }
                fetched.countDown();
                return new FetchedDocument(documentId,
                    ("content of " + documentId).getBytes(StandardCharsets.UTF_8),
                    "text/plain", System.currentTimeMillis());
            }

            @Override
            public void close() {
            }
        }

        @Test
        @DisplayName("a source is stopped without waiting for the rest of its documents")
        void stopsOneSourceMidRun() throws Exception {
            // What deregistering a source needs: a run left in flight would go on writing
            // documents nobody can reach the source through - and, when the deregistration is
            // part of erasing it, would go on writing the documents being erased.
            IngestionOrchestrator orchestrator = orchestrator();
            GatedSource source = new GatedSource(50);

            orchestrator.startIngestion(source);
            source.gate.release();
            assertThat(source.fetched.await(30, TimeUnit.SECONDS))
                .as("precondition: the run reached its first document")
                .isTrue();

            assertThat(orchestrator.stopIngestion("gated")).isTrue();
            source.gate.release(49);
            awaitIdle(orchestrator, "gated");

            assertThat(storedChunkExists("gated", "doc49", 0))
                .as("the documents after the stop must not have been written")
                .isFalse();
        }

        @Test
        @DisplayName("what a stopped run published is made lexically searchable")
        void stoppedRunIsRefreshed() throws Exception {
            // Stopping abandons the documents a run had not reached, not the ones it finished:
            // those are published in storage, and left unrefreshed they would be absent from BM25
            // until some other run happened to refresh the index.
            RecordingChunkIndex lexical = new RecordingChunkIndex();
            IngestionOrchestrator orchestrator = orchestrator();
            orchestrator.setLexicalIndex(lexical);
            GatedSource source = new GatedSource(50);

            orchestrator.startIngestion(source);
            source.gate.release();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (lexical.documents.isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(lexical.documents).as("precondition: the first document was published")
                .isNotEmpty();
            assertThat(orchestrator.stopIngestion("gated")).isTrue();
            source.gate.release(49);
            awaitIdle(orchestrator, "gated");

            assertThat(lexical.refreshedWith)
                .as("the stopped run refreshed after the documents it published")
                .isNotEmpty()
                .last().isEqualTo(lexical.documents.size());
        }

        @Test
        @DisplayName("stopping a source that is not running is not an error")
        void stoppingAnIdleSource() {
            assertThat(orchestrator().stopIngestion("never-started")).isFalse();
        }

        @Test
        @DisplayName("a source id is required")
        void sourceIdIsRequired() {
            assertThatThrownBy(() -> orchestrator().stopIngestion(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ingesting on the caller's thread")
    class SynchronousIngestion {

        /**
         * Documents handed in directly are ingested inside the request that supplied them. The
         * caller has to be told what happened to them: a 202 and a status to poll is the right
         * answer for a crawl of unknown size, and the wrong one for a batch the caller is
         * holding in its hand.
         */
        @Test
        @DisplayName("returns only once the documents it was given are searchable")
        void reportsWhatItIngested() {
            FakeSource source = new FakeSource("notes",
                Map.of("a.md", "storage engines and their write paths",
                       "b.md", "query planning over segment catalogs"));

            IngestionOrchestrator.IngestionOutcome outcome = orchestrator().ingestNow(source);

            assertThat(outcome.documentsIngested()).isEqualTo(2);
            assertThat(outcome.documentsFailed()).isZero();
            assertThat(outcome.chunksIngested()).isPositive();
            assertThat(storedChunk("notes", "a.md", 0))
                .as("the run is finished when this returns, not scheduled")
                .isNotNull();
        }

        /**
         * A source already being crawled must not have a second run interleaved with it: the two
         * would race on the same document ids. Refusing is what lets the caller retry, where
         * silently returning would report documents as ingested that were dropped.
         */
        @Test
        @DisplayName("refuses to run while the same source is already being ingested")
        void refusesAConcurrentRunOfTheSameSource() throws Exception {
            IngestionOrchestrator orchestrator = orchestrator();
            java.util.concurrent.CountDownLatch inFlight =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
            FakeSource blocking = new FakeSource("notes", Map.of("a.md", "text")) {
                @Override
                public FetchedDocument fetch(String documentId) throws SourceException {
                    inFlight.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.fetch(documentId);
                }
            };
            orchestrator.startIngestion(blocking);
            assertThat(inFlight.await(30, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> orchestrator.ingestNow(
                    new FakeSource("notes", Map.of("b.md", "other text"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("notes");
            } finally {
                release.countDown();
                awaitIdle(orchestrator, "notes");
            }
        }

        @Test
        @DisplayName("rejects a null source")
        void rejectsNullSource() {
            assertThatThrownBy(() -> orchestrator().ingestNow(null))
                .isInstanceOf(NullPointerException.class);
        }

        /**
         * A synchronous run happens on the request thread, so it is not one of the background
         * ingestion threads shutdown joins. Left untracked, shutdown returned while the run was
         * still inside storageEngine.put - and the caller closes the Arena on the strength of
         * that return, which is undefined behaviour rather than a caught exception.
         */
        @Test
        @DisplayName("shutdown does not return while a synchronous run is still writing")
        void shutdownWaitsForASynchronousRun() throws Exception {
            IngestionOrchestrator orchestrator = orchestrator();
            java.util.concurrent.CountDownLatch inFlight =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean runFinished =
                new java.util.concurrent.atomic.AtomicBoolean();
            // Deliberately does not stop on an interrupt: it stands in for a write that is
            // partway through a memory-mapped region and has to be allowed to finish.
            FakeSource slow = new FakeSource("notes", Map.of("a.md", "text")) {
                @Override
                public FetchedDocument fetch(String documentId) throws SourceException {
                    inFlight.countDown();
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    return super.fetch(documentId);
                }
            };
            Thread caller = new Thread(() -> {
                try {
                    orchestrator.ingestNow(slow);
                } catch (RuntimeException ignored) {
                    // What the interrupted run produced is not what this test is about.
                } finally {
                    runFinished.set(true);
                }
            });
            caller.start();
            assertThat(inFlight.await(30, TimeUnit.SECONDS)).isTrue();

            orchestrator.shutdown();

            assertThat(runFinished)
                .as("shutdown returned while the run was still going, so its caller is about to "
                    + "close the storage engine underneath it")
                .isTrue();
            caller.join(30_000);
        }

        /**
         * Stopping one source has to reach a synchronous run of it too, or an operator cannot
         * stop one at all.
         */
        @Test
        @DisplayName("stopIngestion reaches a synchronous run of that source")
        void stopIngestionReachesASynchronousRun() throws Exception {
            IngestionOrchestrator orchestrator = orchestrator();
            java.util.concurrent.CountDownLatch inFlight =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
            FakeSource blocking = new FakeSource("notes", Map.of("a.md", "text")) {
                @Override
                public FetchedDocument fetch(String documentId) throws SourceException {
                    inFlight.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.fetch(documentId);
                }
            };
            Thread caller = new Thread(() -> {
                try {
                    orchestrator.ingestNow(blocking);
                } catch (RuntimeException ignored) {
                    // Interrupted by the stop; the assertion is that the stop found it.
                }
            });
            caller.start();
            assertThat(inFlight.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(orchestrator.stopIngestion("notes")).isTrue();

            release.countDown();
            caller.join(30_000);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("the required collaborators are refused when null")
        void requiredCollaboratorsAreChecked() {
            assertThatThrownBy(() -> new IngestionOrchestrator(
                null, embeddings, storage, index, registry))
                .isInstanceOf(NullPointerException.class);
            // The embedding client is deliberately absent from this list: null means lexical-only
            // ingestion, which is a supported deployment rather than a missing dependency.
            assertThatCode(() -> new IngestionOrchestrator(
                new TextChunker(), null, storage, index, registry))
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> new IngestionOrchestrator(
                new TextChunker(), embeddings, null, index, registry))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new IngestionOrchestrator(
                new TextChunker(), embeddings, storage, null, registry))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new IngestionOrchestrator(
                new TextChunker(), embeddings, storage, index, null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a non-positive document timeout falls back to the default")
        void timeoutFallsBackToTheDefault() {
            // Zero would make every backpressure wait time out immediately, turning a busy
            // index into an ingestion that silently indexes nothing.
            IngestionOrchestrator withZero = new IngestionOrchestrator(
                new TextChunker(), embeddings, storage, index, registry,
                null, null, 0);

            FakeSource source = new FakeSource("docs", Map.of("a.txt", "text"));
            RecordingStatus status = ingest(withZero, source);

            assertThat(status.error).isNull();
            assertThat(IngestionOrchestrator.DEFAULT_DOCUMENT_TIMEOUT_MS).isPositive();
        }

        @Test
        @DisplayName("the optional collaborators may all be absent")
        void optionalCollaboratorsMayBeNull() {
            IngestionOrchestrator minimal = new IngestionOrchestrator(
                new TextChunker(), embeddings, storage, index, registry,
                null, null, 2000);
            minimal.setGraphSink(null);

            RecordingStatus status = ingest(minimal,
                new FakeSource("docs", Map.of("a.txt", "text here")));

            assertThat(status.error).isNull();
            assertThat(status.state).isEqualTo("idle");
        }
    }

    @Nested
    @DisplayName("content that is not text")
    class NotText {

        /** A one-pixel PNG: signature, header chunk, one data chunk, end. Full of zero bytes. */
        private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 'I', 'D', 'A', 'T'
        };

        @Test
        @DisplayName("a binary file is not chunked, embedded or written")
        void binaryIsNotIngested() {
            // The whole point: a crawl with no extension filter lists every file under its root,
            // and decoded leniently a PNG is kilobytes of replacement characters that would be
            // indexed, paid for at the embedding endpoint and returned as a search result.
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "Ada Lovelace"))
                .withRaw("pixel.png", PNG, "image/png");

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(storedChunkExists("docs", "pixel.png", 0)).isFalse();
            assertThat(storedChunkExists("docs", "a.txt", 0)).isTrue();
            assertThat(status.skipped).isEqualTo(1);
        }

        @Test
        @DisplayName("skipping one is not reported as a failure")
        void skippingIsNotAnError() {
            // last_error is what an operator reads to decide something is wrong. A directory
            // holding photographs beside its documents is not.
            FakeSource source = new FakeSource("docs", Map.of("a.txt", "Ada Lovelace"))
                .withRaw("pixel.png", PNG, "image/png");

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.error).isNull();
            assertThat(status.state).isEqualTo("idle");
        }

        @Test
        @DisplayName("a run that skips nothing reports no skips")
        void nothingSkippedIsZero() {
            RecordingStatus status = ingest(orchestrator(),
                new FakeSource("docs", Map.of("a.txt", "Ada Lovelace")));

            assertThat(status.skipped).isZero();
        }

        @Test
        @DisplayName("the embedding endpoint is never asked about it")
        void binaryIsNeverEmbedded() throws Exception {
            // A skip that happened after embedding would still cost what the corpus costs.
            FakeSource source = new FakeSource("docs", Map.of())
                .withRaw("pixel.png", PNG, "image/png");

            ingest(orchestrator(), source);

            verify(embeddings, org.mockito.Mockito.never())
                .embedBatch(org.mockito.ArgumentMatchers.anyList());
            verify(embeddings, org.mockito.Mockito.never()).embed(anyString());
        }

        @Test
        @DisplayName("a UTF-16 document its source declares is still ingested")
        void declaredUtf16IsText() {
            // Every other byte is zero. Judging the raw bytes would throw this away; judging the
            // characters the declared encoding produces keeps it.
            FakeSource source = new FakeSource("docs", Map.of());
            source.rawDocuments.put("utf16.txt",
                "The Analytical Engine".getBytes(java.nio.charset.StandardCharsets.UTF_16));
            source.rawContentTypes.put("utf16.txt", "text/plain");
            source.charsets.put("utf16.txt", java.nio.charset.StandardCharsets.UTF_16);

            RecordingStatus status = ingest(orchestrator(), source);

            assertThat(status.skipped).isZero();
            assertThat(storedChunkExists("docs", "utf16.txt", 0)).isTrue();
        }
    }
}
