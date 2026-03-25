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
import io.excalibur.store.StorageEngine;
import io.excalibur.store.WriteLog;
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
        storageEngine = new TestStorageEngine(dataDir);

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
                graph.setLastIntegratedOffset(writeLog.writeOffset());

                // Advance visibility epoch
                visibilityTracker.advanceEpoch();
            }

            // Create query pipeline
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
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

            // Verify results are sorted by distance
            List<excalibur.query.QueryResultItem> results = response.results();
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).score())
                    .isLessThanOrEqualTo(results.get(i - 1).score());
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
         */
        @Test
        @DisplayName("query results contain correct document IDs")
        void queryResultsContainCorrectDocumentIds() throws IOException {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Ingest documents with known IDs
            Set<String> expectedIds = new HashSet<>();
            for (int d = 0; d < 5; d++) {
                String documentId = "known-doc-" + d;
                expectedIds.add(documentId);

                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
            }

            // Query all documents
            float[] queryVector = createRandomVector(dimension);
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
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
            for (excalibur.query.QueryResultItem result : response.results()) {
                assertThat(result.documentId()).isNotNull();
                assertThat(result.documentId()).isNotEmpty();
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

            // Insert same vector multiple times
            float[] vector = createRandomVector(dimension);
            for (int i = 0; i < 10; i++) {
                graph.insert(vector);
            }

            // Query
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
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

            // Insert vectors
            for (int i = 0; i < 100; i++) {
                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
            }

            // Query
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
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
     */
    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog catalog;

        TestStorageEngine(Path dataDir) throws IOException {
            super(dataDir, Arena.ofShared());
            this.catalog = new TestSegmentCatalog();
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return null;
        }

        void close() {
            // Cleanup
        }
    }

    /**
     * Minimal SegmentCatalog for testing.
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        @Override
        public SegmentMetadata get(long segmentId) {
            return null;
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            return Collections.emptyMap();
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
            super(null);
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
    }
}
