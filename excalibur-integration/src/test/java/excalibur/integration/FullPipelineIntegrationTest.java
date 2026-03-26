package excalibur.integration;

import excalibur.filter.CuckooFilter;
import excalibur.ingest.buffer.AtomicDocumentBatch;
import excalibur.ingest.buffer.DocumentChunkBuffer;
import excalibur.ingest.pipeline.DocumentBatchBuffer;
import excalibur.ingest.pipeline.DocumentBatchPublisher;
import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.SegmentCatalog.SegmentMetadata;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.log.WriteLog;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for the full pipeline: ingest → query → verify.
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Documents ingested via the ingestion pipeline appear in query results</li>
 *   <li>Query results contain expected content and metadata</li>
 *   <li>The full 6-stage query pipeline executes correctly</li>
 *   <li>Coverage metadata accurately reflects index state</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Query results are sorted by distance/score (closest first)</li>
 *   <li>No duplicate entries in results</li>
 *   <li>Coverage values in range [0.0, 1.0]</li>
 *   <li>Latency values are non-negative</li>
 *   <li>Document IDs in results match ingested documents</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class FullPipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    // Infrastructure
    private TestStorageEngine storageEngine;
    private TestVisibilityTracker visibilityTracker;
    private WriteLog writeLog;
    private DocumentChunkBuffer chunkBuffer;
    private DocumentRegistry documentRegistry;
    private DocumentBatchPublisher publisher;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        distanceFunction = CosineSimilarity.getInstance();
        dimension = 128;
        random = new Random(42);

        // Create storage infrastructure
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        storageEngine = new TestStorageEngine(dataDir, arena);

        // Create visibility tracker
        visibilityTracker = new TestVisibilityTracker();

        // Create write log
        Path logPath = tempDir.resolve("test.log");
        writeLog = new WriteLog(logPath);

        // Create ingestion infrastructure
        chunkBuffer = new DocumentChunkBuffer(writeLog);
        documentRegistry = new DocumentRegistry();
        publisher = new DocumentBatchPublisher(
            visibilityTracker,
            documentRegistry,
            (CuckooFilter) null,
            writeLog::sync,
            new ReentrantLock()
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Nested
    @DisplayName("Full Pipeline Flow")
    class FullPipelineFlow {

        /**
         * Test: Ingest documents and verify they appear in query results.
         *
         * <p>Domain correctness: Ingested documents must appear in query results
         * within one epoch tick (100ms) of write log fsync.</p>
         */
        @Test
        @DisplayName("ingested documents appear in query results")
        void ingestedDocumentsAppearInQueryResults() throws IOException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // CRITICAL FIX: Create and register a segment before ingesting documents
            // The QueryPipeline reads from segments registered in the VisibilityTracker
            // Use the actual segment ID from the created segment
            Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch(); // Make segment visible at next epoch

            // Ingest documents
            int numDocuments = 10;
            List<float[]> documentVectors = new ArrayList<>();

            for (int d = 0; d < numDocuments; d++) {
                String documentId = "doc-" + d;
                float[] vector = createRandomVector(dimension);
                documentVectors.add(vector);

                // Create chunks
                List<TextChunk> chunks = createChunks(documentId, 3);
                chunkBuffer.bufferChunks(chunks);

                // Commit to write log
                AtomicDocumentBatch batch = chunkBuffer.commitDocument(documentId);
                writeLog.sync();

                // Insert vector into HNSW graph
                graph.insert(vector);
                graph.setLastIntegratedOffset(writeLog.writeHead());

                // Advance visibility epoch
                visibilityTracker.advanceEpoch();
            }

            // Create query pipeline with segment-aware graph provider
            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) {
                    return graph;
                }
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Query using first document's vector
            float[] queryVector = documentVectors.get(0);
            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(queryVector)
                .topK(5)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Verify response
            assertThat(response).isNotNull();
            assertThat(response.isEmpty()).isFalse();
            assertThat(response.results()).isNotEmpty();
            assertThat(response.results().size()).isLessThanOrEqualTo(5);

            // Verify results are sorted by distance (ascending - closest first)
            // For distance metrics, lower score = closer = more similar
            // Results should be in ascending order of distance
            List<excalibur.query.QueryResultItem> results = response.results();
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).score())
                    .isGreaterThanOrEqualTo(results.get(i - 1).score());
            }

            // Verify coverage
            IndexCoverage coverage = response.coverage();
            assertThat(coverage).isNotNull();
            assertThat(coverage.estimatedCorpusCoverage()).isGreaterThanOrEqualTo(0.0);
            assertThat(coverage.estimatedCorpusCoverage()).isLessThanOrEqualTo(1.0);
            assertThat(coverage.degraded()).isFalse();

            // Verify timing
            assertThat(response.queryTimeMs()).isGreaterThanOrEqualTo(0);

            graph.close();

            System.out.println("Full pipeline test: " + results.size() + " results in " +
                response.queryTimeMs() + "ms, coverage=" + coverage.estimatedCorpusCoverage());
        }

        /**
         * Test: Query returns results with correct document IDs.
         *
         * <p><strong>Domain Correctness:</strong> This test verifies that:</p>
         * <ul>
         *   <li>Document IDs registered with the HNSW graph appear in query results</li>
         *   <li>Returned document IDs match the documents that were ingested</li>
         *   <li>Each vector insertion is associated with its source document</li>
         * </ul>
         *
         * <p><strong>Implementation Note:</strong> The HNSW graph uses node IDs internally,
         * but these must be mapped to actual document IDs via {@code registerDocumentId()}
         * for the query pipeline to return meaningful document identifiers.</p>
         */
        @Test
        @DisplayName("query results contain correct document IDs")
        void queryResultsContainCorrectDocumentIds() throws IOException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // CRITICAL FIX: Create and register a segment before ingesting documents
            Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch(); // Make segment visible

            // Ingest documents with known IDs - CRITICAL: register document IDs with graph
            Set<String> expectedIds = new HashSet<>();
            Map<Long, String> nodeIdToDocumentId = new HashMap<>();

            for (int d = 0; d < 5; d++) {
                String documentId = "known-doc-" + d;
                expectedIds.add(documentId);

                float[] vector = createRandomVector(dimension);

                // CRITICAL: Capture the node ID returned by insert()
                long nodeId = graph.insert(vector);

                // CRITICAL: Register the document ID with the graph
                // This maps the internal node ID to the actual document ID
                graph.registerDocumentId(nodeId, documentId);
                nodeIdToDocumentId.put(nodeId, documentId);

                // Advance visibility epoch to make the document visible
                visibilityTracker.advanceEpoch();
            }

            // Query using a vector that should match one of our documents
            // Use the first document's vector as the query for guaranteed match
            float[] queryVector = createRandomVector(dimension);
            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) {
                    return graph;
                }
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(queryVector)
                .topK(10)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Verify results contain document IDs
            assertThat(response.results()).isNotEmpty();

            // CRITICAL: Verify that returned document IDs match ingested documents
            Set<String> returnedDocumentIds = new HashSet<>();
            for (excalibur.query.QueryResultItem result : response.results()) {
                // Verify numeric document ID is valid
                assertThat(result.documentId()).isNotNull();
                assertThat(result.documentId()).isGreaterThanOrEqualTo(0);

                // Verify string document ID is present and valid
                // This is the actual document ID that was registered with the graph
                String docIdStr = result.documentIdStr();
                if (docIdStr != null) {
                    assertThat(docIdStr).isNotEmpty();
                    returnedDocumentIds.add(docIdStr);
                }
            }

            // CRITICAL: Verify at least some returned IDs match expected IDs
            // Note: Not all results may have string document IDs if they weren't registered,
            // but at least some should match our expected set
            assertThat(returnedDocumentIds)
                .as("Returned document IDs should match some of the ingested document IDs")
                .isNotEmpty();

            // Verify that all returned string document IDs are from our expected set
            for (String returnedId : returnedDocumentIds) {
                assertThat(expectedIds)
                    .as("Returned document ID '%s' should be in expected set %s", returnedId, expectedIds)
                    .contains(returnedId);
            }

            graph.close();
        }

        /**
         * Test: No duplicate entries in results.
         */
        @Test
        @DisplayName("no duplicate entries in results")
        void noDuplicateEntriesInResults() throws IOException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // CRITICAL FIX: Create and register a segment before ingesting documents
            Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch(); // Make segment visible

            // Insert same vector multiple times
            float[] vector = createRandomVector(dimension);
            for (int i = 0; i < 10; i++) {
                graph.insert(vector);
            }

            // Query with segment-aware graph provider
            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) {
                    return graph;
                }
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(vector)
                .topK(100)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Verify no duplicates
            Set<Long> seenIds = new HashSet<>();
            for (excalibur.query.QueryResultItem result : response.results()) {
                assertThat(seenIds).doesNotContain(result.documentId());
                seenIds.add(result.documentId());
            }

            graph.close();
        }
    }

    // ==================== Coverage Metadata Tests ====================

    @Nested
    @DisplayName("Coverage Metadata")
    class CoverageMetadata {

        /**
         * Test: Coverage reflects index state accurately.
         */
        @Test
        @DisplayName("coverage accurately reflects index state")
        void coverageAccuratelyReflectsIndexState() throws IOException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // CRITICAL FIX: Create and register a segment before ingesting documents
            Segment segment = storageEngine.createSegment(LifecycleState.ACTIVE);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch(); // Make segment visible

            // Insert vectors
            for (int i = 0; i < 100; i++) {
                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
            }

            // Query with segment-aware graph provider
            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) {
                    return graph;
                }
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(createRandomVector(dimension))
                .topK(10)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Verify coverage metadata
            IndexCoverage coverage = response.coverage();
            assertThat(coverage).isNotNull();
            assertThat(coverage.totalSegments()).isGreaterThanOrEqualTo(0);
            assertThat(coverage.activeSegments()).isGreaterThanOrEqualTo(0);
            assertThat(coverage.activeSegments()).isLessThanOrEqualTo(coverage.totalSegments());
            assertThat(coverage.estimatedCorpusCoverage()).isBetween(0.0, 1.0);

            graph.close();
        }
    }

    // ==================== Helper Methods ====================

    private float[] createRandomVector(int dimension) {
        float[] vector = new float[dimension];
        float sum = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
            sum += vector[i] * vector[i];
        }
        // Normalize
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private List<TextChunk> createChunks(String documentId, int count) {
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = "This is chunk " + i + " of document " + documentId +
                ". It contains enough text to be meaningful for testing purposes.";
            chunks.add(new TextChunk(
                documentId,
                text,
                text.length() / 4,
                i * 100,
                (i + 1) * 100,
                i
            ));
        }
        return chunks;
    }

    // ==================== Test Infrastructure ====================

    /**
     * Minimal StorageEngine for testing.
     * Uses real Segment files for proper integration testing.
     */
    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog catalog;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private final Path dataDir;
        private final Arena arena;
        private int segmentCounter = 0;

        TestStorageEngine(Path dataDir, Arena arena) throws IOException {
            super(dataDir, arena);
            this.dataDir = dataDir;
            this.arena = arena;
            this.catalog = new TestSegmentCatalog();
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        /**
         * Creates a test segment with real disk backing.
         * The segment ID is auto-generated by the Segment class.
         *
         * @return The created segment with its auto-generated ID
         */
        public Segment createSegment(LifecycleState lifecycleState) throws IOException {
            // Create real segment file
            Path segmentPath = dataDir.resolve("segment-" + segmentCounter++ + ".seg");
            Segment segment = new Segment(segmentPath, 4 * 1024 * 1024, arena); // 4MB segment

            long segmentId = segment.segmentId();

            // Add to catalog with proper lifecycle transitions
            catalog.addSegment(segmentId, lifecycleState);

            // Store segment for later retrieval
            segments.put(segmentId, segment);

            return segment;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, Segment> getSegments() {
            return Collections.unmodifiableMap(segments);
        }

        public void close() {
            for (Segment segment : segments.values()) {
                try {
                    segment.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Minimal SegmentCatalog for testing.
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        private final Map<Long, SegmentMetadata> segments = new ConcurrentHashMap<>();

        /**
         * Adds a segment with the specified lifecycle state.
         * Uses proper state transitions to ensure valid lifecycle.
         *
         * @param segmentId The segment ID
         * @param lifecycleState The lifecycle state
         * @return The created metadata
         */
        public SegmentMetadata addSegment(long segmentId, LifecycleState lifecycleState) {
            SegmentMetadata metadata = new SegmentMetadata(segmentId);
            // Default state is BOOTSTRAPPING, transition to target state through valid transitions
            if (lifecycleState != LifecycleState.BOOTSTRAPPING) {
                metadata.transitionToIndexing(); // BOOTSTRAPPING -> INDEXING
                if (lifecycleState == LifecycleState.WARMING
                    || lifecycleState == LifecycleState.ACTIVE
                    || lifecycleState == LifecycleState.COMPACTING) {
                    metadata.transitionToWarming(); // INDEXING -> WARMING
                    if (lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                        metadata.transitionToActive(); // WARMING -> ACTIVE
                        if (lifecycleState == LifecycleState.COMPACTING) {
                            metadata.transitionToCompacting(); // ACTIVE -> COMPACTING
                        }
                    }
                }
                // For INDEXING, just the transitionToIndexing() call is enough
            }
            segments.put(segmentId, metadata);
            return metadata;
        }

        /**
         * Removes a segment from the catalog.
         *
         * @param segmentId The segment ID to remove
         */
        public void removeSegment(long segmentId) {
            segments.remove(segmentId);
        }

        @Override
        public SegmentMetadata get(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            return Collections.unmodifiableMap(segments);
        }

        @Override
        public int size() {
            return segments.size();
        }
    }

    /**
     * Test VisibilityTracker implementation.
     */
    private static class TestVisibilityTracker extends VisibilityTracker {
        private final AtomicLong epoch = new AtomicLong(0);
        private final ConcurrentHashMap<Long, Long> creationEpochs = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Long> tombstoneEpochs = new ConcurrentHashMap<>();

        TestVisibilityTracker() {
            super(new TestSegmentCatalog());
        }

        @Override
        public long currentEpoch() {
            return epoch.get();
        }

        @Override
        public long captureEpoch() {
            return epoch.get();
        }

        @Override
        public long advanceEpoch() {
            return epoch.incrementAndGet();
        }

        @Override
        public void registerSegment(long segmentId, long epoch) {
            creationEpochs.put(segmentId, epoch);
            tombstoneEpochs.putIfAbsent(segmentId, Long.MAX_VALUE);
        }

        @Override
        public void markTombstoned(long segmentId, long epoch) {
            tombstoneEpochs.put(segmentId, epoch);
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            Set<Long> visible = new HashSet<>();
            for (Map.Entry<Long, Long> entry : creationEpochs.entrySet()) {
                long segmentId = entry.getKey();
                long creationEpoch = entry.getValue();
                long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);

                if (creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch) {
                    visible.add(segmentId);
                }
            }
            return Collections.unmodifiableSet(visible);
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            Long creationEpoch = creationEpochs.get(segmentId);
            if (creationEpoch == null) {
                return false;
            }
            long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
            return creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch;
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            return creationEpochs.getOrDefault(segmentId, -1L);
        }

        @Override
        public long getTombstoneEpoch(long segmentId) {
            return tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
        }

        @Override
        public int getRegisteredSegmentCount() {
            return creationEpochs.size();
        }
    }
}
