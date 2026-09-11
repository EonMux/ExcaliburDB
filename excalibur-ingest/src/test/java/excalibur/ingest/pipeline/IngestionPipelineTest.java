package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.source.DocumentNotFoundException;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.llm.embedding.EmbeddingClient;
import excalibur.shard.IndexOrchestrator;

import io.excalibur.core.text.ChunkIdentifiers;
import io.excalibur.store.StorageEngine;

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

        @Override
        public Stream<String> crawl() throws SourceException {
            if (crawlFailure != null) {
                throw crawlFailure;
            }
            return documents.keySet().stream();
        }

        @Override
        public FetchedDocument fetch(String documentId)
                throws SourceException, DocumentNotFoundException {
            fetches.incrementAndGet();
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
            excalibur.filter.HierarchicalCuckooFilter membership =
                new excalibur.filter.HierarchicalCuckooFilter(1_000_000);
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
                .thenThrow(new io.excalibur.llm.LLMException(
                    "embedding backend is down", io.excalibur.llm.LLMException.ErrorType.NETWORK));
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
                .isLessThanOrEqualTo(IngestionOrchestrator.EMBED_BATCH_SIZE);
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
            assertThat(io.excalibur.core.codec.ChunkPayloadCodec.textOf(value))
                .as("the text is what retrieval will rank; it must survive")
                .contains("cgroup memory limit exceeded");
            assertThat(io.excalibur.core.codec.ChunkPayloadCodec.embeddingOf(value))
                .as("no vector, reported as absent rather than as corruption")
                .isNull();
            assertThat(io.excalibur.core.codec.ChunkPayloadCodec.dimensionOf(value)).isZero();
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
            try (excalibur.ingest.lexical.NativeChunkIndex native_ =
                     excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                         io.excalibur.retrieval.lexical.native_.CompactionPlanner.standard())) {
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

                var ranker = native_.ranker(io.excalibur.retrieval.expand.QueryExpander.identity());
                var ranked = ranker.rank(
                    io.excalibur.retrieval.rank.RetrievalQuery.ofText("memory pressure"), 5);

                assertThat(ranked.documents()).as("BM25 found the chunk").isNotEmpty();
                assertThat(native_.chunkIdOf(ranked.documents().get(0).documentId()))
                    .as("a hit resolves to the chunk id a caller can look up")
                    .contains("oom.txt");

                var other = ranker.rank(
                    io.excalibur.retrieval.rank.RetrievalQuery.ofText("certificate handshake"), 5);
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
            try (excalibur.ingest.lexical.NativeChunkIndex native_ =
                     excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                         io.excalibur.retrieval.lexical.native_.CompactionPlanner.standard())) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(native_);
                FakeSource source = new FakeSource("docs", Map.of(
                    "oom.txt", "the OOM killer terminated the process under memory pressure"));

                ingest(lexical, source);
                epoch.incrementAndGet();
                ingest(lexical, source);
                epoch.incrementAndGet();

                var ranked = native_.ranker(io.excalibur.retrieval.expand.QueryExpander.identity())
                    .rank(io.excalibur.retrieval.rank.RetrievalQuery.ofText("memory pressure"), 10);

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
            try (excalibur.ingest.lexical.NativeChunkIndex native_ =
                     excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                         io.excalibur.retrieval.lexical.native_.CompactionPlanner.standard())) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(native_);

                ingest(lexical, new FakeSource("docs", Map.of(
                    "note.txt", "the TLS handshake failed")));
                epoch.incrementAndGet();
                ingest(lexical, new FakeSource("docs", Map.of(
                    "note.txt", "the certificate expired")));
                epoch.incrementAndGet();

                var ranker = native_.ranker(io.excalibur.retrieval.expand.QueryExpander.identity());
                assertThat(ranker.rank(
                        io.excalibur.retrieval.rank.RetrievalQuery.ofText("handshake"), 10)
                        .documents())
                    .as("storage holds the new text under this id, so the old words must not match")
                    .isEmpty();
                assertThat(ranker.rank(
                        io.excalibur.retrieval.rank.RetrievalQuery.ofText("expired"), 10)
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
            try (excalibur.ingest.lexical.ChunkLexicalIndex lucene =
                     excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0)) {
                IngestionOrchestrator lexical =
                    new IngestionOrchestrator(new TextChunker(), null, storage, index, registry);
                lexical.setLexicalIndex(lucene);

                ingest(lexical, new FakeSource("docs", Map.of(
                    "oom.txt", "the OOM killer terminated the process under memory pressure",
                    "tls.txt", "the certificate chain could not be verified during the handshake")));

                assertThat(lucene.indexedCount()).as("chunks reached Lucene").isGreaterThan(0);
                assertThat(lucene.collisionCount()).isZero();

                // Through the same ranker the query path uses, not a hand-built query.
                var ranker = new io.excalibur.retrieval.lexical.LuceneRanker(lucene.index());
                var ranked = ranker.rank(
                    io.excalibur.retrieval.rank.RetrievalQuery.ofText("memory pressure"), 5);

                assertThat(ranked.documents()).as("BM25 found the chunk").isNotEmpty();
                String chunkId = lucene.chunkIdOf(ranked.documents().get(0).documentId());
                assertThat(chunkId)
                    .as("a hit resolves to the chunk id a caller can look up")
                    .contains("oom.txt");

                var other = ranker.rank(
                    io.excalibur.retrieval.rank.RetrievalQuery.ofText("certificate handshake"), 5);
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

            assertThat(status.error).isNull();
            assertThat(storedChunkExists("docs", "real.txt", 0)).isTrue();
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
}
