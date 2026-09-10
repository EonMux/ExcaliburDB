package excalibur.integration;

import excalibur.api.bootstrap.StorageChunkWriter;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.erasure.SegmentVectorEraser;
import excalibur.ingest.lexical.NativeChunkIndex;
import excalibur.ingest.lexical.StoredChunkText;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.core.text.ChunkIdentifiers;
import io.excalibur.retrieval.expand.QueryExpander;
import io.excalibur.retrieval.lexical.native_.CompactionPlanner;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.NodeDocumentMapping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Erasure end to end: ingested for real, then removed for real.
 *
 * <h2>What this proves that the unit tests cannot</h2>
 * <p>Every component of an erasure has its own tests, and each of them stands in for the others:
 * the service is tested against a map instead of the storage engine, the lexical index against
 * text handed to it directly rather than read back out of storage. Those substitutions are exactly
 * where an erasure can fail without anything noticing - a text supplier reading a key the writer
 * never wrote under, a storage adapter deleting nothing and reporting success.
 *
 * <p>So this test uses the real ones: chunks written by the ingestion pipeline into a real storage
 * engine, indexed by the real native index, and erased through the same adapter, text supplier and
 * vector eraser the running node wires up. The assertions are the promise itself - the document
 * stops being findable, its chunks leave storage, its words leave the index's files, and no vector
 * search still names it.
 */
@DisplayName("Document erasure end to end")
class DocumentErasureIntegrationTest {

    private static final String SOURCE = "docs";
    private static final String SECRET = "zzquarantinedmarker";

    /** The documents given a vector, in the order their vectors are assigned. */
    private static final List<String> INDEXED = List.of("secret.md", "keep.md");

    private static final int DIMENSION = 8;

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private NativeChunkIndex lexical;
    private DocumentRegistry registry;
    private DocumentVisibilityTracker visibility;
    private IngestionOrchestrator orchestrator;
    private final AtomicLong epoch = new AtomicLong(1);

    /** A policy that never fires on its own, so a forced compaction is the only one that runs. */
    private static CompactionPlanner never() {
        return new CompactionPlanner((stats, now) -> false, 1.0, Duration.ofDays(3650), 4096, 8);
    }

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
        storage.start();
        lexical = NativeChunkIndex.open(tempDir.resolve("chunk-index"), epoch::get, 0, never());
        registry = new DocumentRegistry(tempDir.resolve("document-registry"));
        visibility = new DocumentVisibilityTracker(registry);

        IndexOrchestrator index = mock(IndexOrchestrator.class);
        when(index.isBackpressured()).thenReturn(false);
        when(index.awaitBackpressureRelease(anyInt())).thenReturn(true);

        // No embedding client: lexical-only ingestion, which is the deployment shape this
        // erasure path has to work in and the one that needs no LLM to test.
        orchestrator = new IngestionOrchestrator(
            new TextChunker(), null, storage, index,
            sourceId -> new IngestionOrchestrator.IngestionStatus() {
                @Override
                public void update(String state, double progress, long ingested, long pending) {
                }

                @Override
                public void setError(String error) {
                }
            },
            visibility, null, 5000);
        orchestrator.setLexicalIndex(lexical);
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (lexical != null) {
            lexical.close();
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception ignored) {
                // Tidy-up only; the assertions have already run.
            }
        }
    }

    private DocumentErasureService erasure() {
        return new DocumentErasureService(registry,
            StorageChunkWriter.tombstoneAdapter(storage), lexical, StoredChunkText.of(storage));
    }

    /** The same erasure with the vector index attached, exactly as the node assembles it. */
    private DocumentErasureService erasureWithVectors() {
        return new DocumentErasureService(registry,
            StorageChunkWriter.tombstoneAdapter(storage), lexical, StoredChunkText.of(storage),
            visibility, SegmentVectorEraser.of(storage));
    }

    /** Runs one source to completion, then lets the node's epoch clock move on. */
    private void ingest(Map<String, String> documents) {
        Source source = new MapSource(SOURCE, documents);
        orchestrator.startIngestion(source);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (orchestrator.isIngestionActive(SOURCE)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("ingestion did not finish in 30s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while ingesting", e);
            }
        }
        epoch.incrementAndGet();
    }

    private int hits(String term) {
        return lexical.ranker(QueryExpander.identity())
            .rank(RetrievalQuery.ofText(term), 100).documents().size();
    }

    private boolean storageHolds(String chunkId) {
        return storage.get(chunkId.getBytes(StandardCharsets.UTF_8)) != null;
    }

    private boolean anyIndexFileContains(String needle) throws IOException {
        Path dir = tempDir.resolve("chunk-index");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> corpus() {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("secret.md", "the incident report contains " + SECRET + " throughout");
        documents.put("keep.md", "routine capacity planning for the next quarter");
        return documents;
    }

    /**
     * Gives every {@link #INDEXED} document a vector, the way the integration worker does.
     *
     * <p>Each node is attributed by running the chunk key the ingestion really wrote through
     * {@link ChunkIdentifiers#documentKeyOfChunk(String)}, which is what {@code
     * HnswIntegrationWorker} registers as it inserts. The erasure asks for the document under
     * {@link ChunkIdentifiers#documentKey(String, String)}. Those two derivations agreeing is the
     * whole of the attribution, and only running both of them can show that they do.</p>
     *
     * @return The graph the nodes were inserted into, attached to a segment of the live engine
     */
    private HnswGraph indexVectors() throws IOException {
        Segment segment = storage.getSegments().values().stream().findFirst()
            .orElseThrow(() -> new AssertionError("the engine holds no segment to attach a graph to"));
        HnswGraph graph = new HnswGraph(tempDir.resolve("segment.hnsw"),
            HnswConfig.create(DIMENSION), L2Distance.getInstance(), 100);
        segment.attachHnswGraph(graph);

        for (String documentId : INDEXED) {
            String chunkId = ChunkIdentifiers.chunkId(SOURCE, documentId, 0);
            assertThat(storageHolds(chunkId))
                .as("a vector has to belong to a chunk the ingestion really wrote")
                .isTrue();
            graph.registerDocumentId(graph.insert(vectorOf(documentId)),
                ChunkIdentifiers.documentKeyOfChunk(chunkId));
        }
        return graph;
    }

    /** The documents a vector search names, nearest first. */
    private static List<String> documentsRanked(HnswGraph graph, float[] query) {
        NodeDocumentMapping mapping = graph.getNodeDocumentMapping();
        return graph.search(query, 10).stream()
            .map(result -> mapping.getDocumentId(result.nodeId()))
            .toList();
    }

    /** A distinct unit vector per indexed document, so a query can aim at one of them. */
    private static float[] vectorOf(String documentId) {
        float[] vector = new float[DIMENSION];
        vector[INDEXED.indexOf(documentId)] = 1.0f;
        return vector;
    }

    @Test
    @DisplayName("an erased document stops being found, leaves storage and leaves the index files")
    void erasureRemovesTheDocumentEverywhere() throws Exception {
        ingest(corpus());

        assertThat(hits(SECRET)).as("precondition: it was ingested and indexed").isEqualTo(1);
        assertThat(storageHolds(SOURCE + ":secret.md:chunk:0")).isTrue();

        DocumentErasureService.Outcome outcome =
            erasure().erase(SOURCE, List.of("secret.md"), true);

        assertThat(outcome.documentsErased()).isEqualTo(1);
        assertThat(outcome.chunksTombstoned()).isEqualTo(1);
        assertThat(outcome.chunksUnindexed()).isEqualTo(1);
        assertThat(outcome.compacted())
            .as("the caller asked for the data to be gone, not scheduled")
            .isTrue();
        assertThat(outcome.outstanding()).isZero();

        assertThat(hits(SECRET)).as("no longer findable").isZero();
        assertThat(storageHolds(SOURCE + ":secret.md:chunk:0"))
            .as("storage is the only place the text lives; it has to go first and stay gone")
            .isFalse();
        assertThat(anyIndexFileContains(SECRET))
            .as("hiding is not erasing: the words have to leave the index's own files")
            .isFalse();
        assertThat(registry.contains(SOURCE, "secret.md")).isFalse();
    }

    @Test
    @DisplayName("the documents around it are untouched and still answer")
    void neighboursSurvive() throws Exception {
        ingest(corpus());

        erasure().erase(SOURCE, List.of("secret.md"), true);

        assertThat(hits("capacity"))
            .as("a compaction rewrites the surviving chunks from storage; losing them would be "
                + "the same defect in the other direction")
            .isEqualTo(1);
        assertThat(storageHolds(SOURCE + ":keep.md:chunk:0")).isTrue();
        assertThat(registry.contains(SOURCE, "keep.md")).isTrue();
    }

    @Test
    @DisplayName("erasing the whole source empties it and records that it is empty")
    void sourceScopeEmptiesTheSource() throws Exception {
        ingest(corpus());

        DocumentErasureService.Outcome outcome = erasure().eraseSource(SOURCE, 100, true);

        assertThat(outcome.documentsErased()).isEqualTo(2);
        assertThat(outcome.documentsRemaining()).isZero();
        assertThat(hits(SECRET)).isZero();
        assertThat(hits("capacity")).isZero();
        assertThat(storageHolds(SOURCE + ":secret.md:chunk:0")).isFalse();
        assertThat(storageHolds(SOURCE + ":keep.md:chunk:0")).isFalse();
        assertThat(registry.size(SOURCE)).isZero();
    }

    @Test
    @DisplayName("an erasure survives a restart of the registry")
    void erasureIsRecordedOnDisk() throws Exception {
        ingest(corpus());
        registry.persist();

        erasure().erase(SOURCE, List.of("secret.md"), false);

        DocumentRegistry afterRestart = new DocumentRegistry(tempDir.resolve("document-registry"));
        assertThat(afterRestart.contains(SOURCE, "secret.md"))
            .as("an erasure that only happened in memory is one a restart undoes")
            .isFalse();
        assertThat(afterRestart.contains(SOURCE, "keep.md")).isTrue();
    }

    @Test
    @DisplayName("a vector search stops naming an erased document")
    void erasureReachesTheVectorIndex() throws Exception {
        ingest(corpus());
        HnswGraph graph = indexVectors();
        String erased = ChunkIdentifiers.documentKey(SOURCE, "secret.md");

        assertThat(documentsRanked(graph, vectorOf("secret.md")))
            .as("precondition: the vector index answers with it")
            .contains(erased);

        DocumentErasureService.Outcome outcome =
            erasureWithVectors().erase(SOURCE, List.of("secret.md"), true);

        assertThat(outcome.vectorsHidden()).isEqualTo(1);
        assertThat(documentsRanked(graph, vectorOf("secret.md")))
            .as("a ranked node names its document and gives it a distance whether or not any text "
                + "is left to show for it, and that naming is what the request asked to remove")
            .doesNotContain(erased)
            .contains(ChunkIdentifiers.documentKey(SOURCE, "keep.md"));
    }

    @Test
    @DisplayName("erasing the whole source leaves the vector index with nothing to rank")
    void sourceScopeReachesTheVectorIndex() throws Exception {
        ingest(corpus());
        HnswGraph graph = indexVectors();

        DocumentErasureService.Outcome outcome = erasureWithVectors().eraseSource(SOURCE, 100, true);

        assertThat(outcome.vectorsHidden()).isEqualTo(INDEXED.size());
        assertThat(documentsRanked(graph, vectorOf("secret.md")))
            .as("every document of the source is gone; none of them may still be ranked")
            .isEmpty();
    }

    /** A source serving a fixed map of documents. */
    private record MapSource(String sourceId, Map<String, String> documents) implements Source {

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "map";
        }

        @Override
        public Stream<String> crawl() {
            return documents.keySet().stream();
        }

        @Override
        public boolean hasChanged(java.time.Instant since) {
            return true;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            String text = documents.get(documentId);
            if (text == null) {
                throw new SourceException("no such document: " + documentId);
            }
            return new FetchedDocument(documentId, text.getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis());
        }

        @Override
        public void close() {
        }
    }
}
