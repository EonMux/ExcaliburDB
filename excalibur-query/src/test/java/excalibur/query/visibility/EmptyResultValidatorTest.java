package excalibur.query.visibility;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.executor.SegmentQueryTask;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EmptyResultValidator.
 *
 * <p>These tests verify that the validator correctly identifies when empty
 * results are legitimate vs. when they indicate a potential bug.</p>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Empty results are ONLY acceptable when no documents match the query vector</li>
 *   <li>Legitimate reasons: empty index, no queryable segments, no visible segments, no vector match</li>
 *   <li>Illegitimate reasons: visibility bugs, HNSW bugs, aggregation bugs</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class EmptyResultValidatorTest {

    @TempDir
    Path tempDir;

    private TestStorageEngine storageEngine;
    private TestVisibilityTracker visibilityTracker;
    private TestGraphProvider graphProvider;

    @BeforeEach
    void setUp() throws IOException {
        Arena arena = Arena.ofShared();
        Path dataDir = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataDir);
        storageEngine = new TestStorageEngine(dataDir, arena);
        visibilityTracker = new TestVisibilityTracker();
        graphProvider = new TestGraphProvider();
    }

    @Nested
    @DisplayName("Legitimate Empty Results")
    class LegitimateEmptyResults {

        @Test
        @DisplayName("empty index is legitimate")
        void emptyIndexIsLegitimate() {
            // No segments in catalog
            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                Collections.emptyList(), graphProvider
            );

            assertThat(result.isLegitimate()).isTrue();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.EMPTY_INDEX);
            assertThat(result.catalogSegments()).isEqualTo(0);
        }

        @Test
        @DisplayName("no queryable segments is legitimate")
        void noQueryableSegmentsIsLegitimate() throws IOException {
            // Add segment in BOOTSTRAPPING state (not queryable)
            storageEngine.addSegmentInState(LifecycleState.BOOTSTRAPPING);

            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                Collections.emptyList(), graphProvider
            );

            assertThat(result.isLegitimate()).isTrue();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.NO_QUERYABLE_SEGMENTS);
            assertThat(result.queryableSegments()).isEqualTo(0);
        }

        @Test
        @DisplayName("no vectors in graphs is legitimate")
        void noVectorsInGraphsIsLegitimate() throws IOException {
            // Add segment in ACTIVE state
            long segmentId = storageEngine.addSegmentInState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();

            // Graph exists but has no nodes
            graphProvider.addEmptyGraph(segmentId);

            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                Collections.emptyList(), graphProvider
            );

            assertThat(result.isLegitimate()).isTrue();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.NO_VECTOR_MATCH);
        }

        @Test
        @DisplayName("threshold filtering is legitimate")
        void thresholdFilteringIsLegitimate() throws IOException {
            // Add segment with vectors
            long segmentId = storageEngine.addSegmentInState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();
            graphProvider.addGraphWithNodes(segmentId, 10);

            // Simulate segment search that returned results
            List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
            segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                segmentId,
                List.of(new HnswSearchResult(0, 0.5f)), // Result with distance 0.5
                0, 1, true, null
            ));

            // Request with threshold 0.3 (filters out the result with 0.5 distance)
            QueryRequest request = QueryRequest.builder()
                .queryText("test")
                .queryVector(new float[128])
                .topK(10)
                .threshold(0.3f)  // This will filter out the 0.5 distance result
                .build();

            QueryResponse response = createEmptyResponse();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                segmentResults, graphProvider
            );

            assertThat(result.isLegitimate()).isTrue();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.THRESHOLD_FILTERING);
            assertThat(result.thresholdFiltering()).isTrue();
            assertThat(result.rawResultCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Illegitimate Empty Results")
    class IllegitimateEmptyResults {

        @Test
        @DisplayName("visibility bug when segments registered but not visible")
        void visibilityBugDetected() throws IOException {
            // Add segment in ACTIVE state
            long segmentId = storageEngine.addSegmentInState(LifecycleState.ACTIVE);

            // Register with FUTURE epoch (will never be visible at current epoch)
            visibilityTracker.registerSegment(segmentId, 1000); // Future epoch

            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                Collections.emptyList(), graphProvider
            );

            assertThat(result.isLegitimate()).isFalse();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.VISIBILITY_BUG);
        }

        @Test
        @DisplayName("HNSW bug when graph has vectors but search returns empty")
        void hnswBugDetected() throws IOException {
            // Add segment with vectors
            long segmentId = storageEngine.addSegmentInState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();
            graphProvider.addGraphWithNodes(segmentId, 10);

            // Segment was searched but returned no results
            List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
            segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                segmentId,
                Collections.emptyList(), // Empty results despite active nodes
                0, 1, true, null
            ));

            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                segmentResults, graphProvider
            );

            assertThat(result.isLegitimate()).isFalse();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.HNSW_EMPTY_BUG);
            assertThat(result.activeVectorsInGraphs()).isGreaterThan(0);
            assertThat(result.segmentsSearched()).isEqualTo(1);
            assertThat(result.segmentsWithResults()).isEqualTo(0);
        }

        @Test
        @DisplayName("aggregation bug when results lost during merge")
        void aggregationBugDetected() throws IOException {
            // Add segment with vectors
            long segmentId = storageEngine.addSegmentInState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();
            graphProvider.addGraphWithNodes(segmentId, 10);

            // Segment returned results but final response is empty
            List<SegmentQueryTask.SegmentQueryResult> segmentResults = new ArrayList<>();
            segmentResults.add(new SegmentQueryTask.SegmentQueryResult(
                segmentId,
                List.of(new HnswSearchResult(0, 0.1f)), // Has results
                0, 1, true, null
            ));

            QueryResponse response = createEmptyResponse(); // But final response is empty
            QueryRequest request = QueryRequest.builder()
                .queryText("test")
                .queryVector(new float[128])
                .topK(10)
                .threshold(0.0f)  // No threshold filtering
                .build();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                segmentResults, graphProvider
            );

            assertThat(result.isLegitimate()).isFalse();
            assertThat(result.reason()).isEqualTo(EmptyResultValidator.ValidationResult.Reason.AGGREGATION_BUG);
            assertThat(result.rawResultCount()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Quick Check")
    class QuickCheck {

        @Test
        @DisplayName("shouldHaveResults returns false for empty catalog")
        void emptyCatalogReturnsFalse() {
            assertThat(EmptyResultValidator.shouldHaveResults(
                storageEngine, visibilityTracker, graphProvider
            )).isFalse();
        }

        @Test
        @DisplayName("shouldHaveResults returns true when active vectors exist")
        void activeVectorsReturnsTrue() throws IOException {
            long segmentId = storageEngine.addSegmentInState(LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();
            graphProvider.addGraphWithNodes(segmentId, 10);

            assertThat(EmptyResultValidator.shouldHaveResults(
                storageEngine, visibilityTracker, graphProvider
            )).isTrue();
        }
    }

    @Nested
    @DisplayName("Diagnostic Output")
    class DiagnosticOutput {

        @Test
        @DisplayName("diagnosticSummary produces readable output")
        void diagnosticSummaryIsReadable() throws IOException {
            storageEngine.addSegmentInState(LifecycleState.BOOTSTRAPPING);

            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                Collections.emptyList(), graphProvider
            );

            String summary = result.diagnosticSummary();
            // Check for description text, not enum name
            assertThat(summary).contains("non-queryable states");
            assertThat(summary).contains("Catalog:");
            assertThat(summary).contains("queryable");
        }

        @Test
        @DisplayName("oneLineSummary is concise")
        void oneLineSummaryIsConcise() throws IOException {
            storageEngine.addSegmentInState(LifecycleState.BOOTSTRAPPING);

            QueryResponse response = createEmptyResponse();
            QueryRequest request = createTestRequest();

            EmptyResultValidator.ValidationResult result = EmptyResultValidator.validate(
                response, request, storageEngine, visibilityTracker,
                Collections.emptyList(), graphProvider
            );

            String summary = result.oneLineSummary();
            assertThat(summary).contains("EmptyResult[NO_QUERYABLE_SEGMENTS]");
            assertThat(summary).doesNotContain("\n"); // Should be single line
        }
    }

    // ==================== Helper Methods ====================

    private QueryResponse createEmptyResponse() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(0)
            .computeCoverage()
            .build();
        return QueryResponse.empty(coverage, 0);
    }

    private QueryRequest createTestRequest() {
        return QueryRequest.builder()
            .queryText("test query")
            .queryVector(new float[128])
            .topK(10)
            .timeoutMs(5000)
            .build();
    }

    // ==================== Test Infrastructure ====================

    /**
     * Minimal StorageEngine for testing.
     */
    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog catalog;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private long segmentIdCounter = 0;

        TestStorageEngine(Path dataDir, Arena arena) throws IOException {
            super(dataDir, arena);
            this.catalog = new TestSegmentCatalog();
        }

        public long addSegmentInState(LifecycleState state) throws IOException {
            long segmentId = segmentIdCounter++;
            catalog.addSegment(segmentId, state);
            return segmentId;
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, Segment> getSegments() {
            return Collections.unmodifiableMap(segments);
        }
    }

    /**
     * Minimal SegmentCatalog for testing.
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        private final Map<Long, SegmentMetadata> segments = new ConcurrentHashMap<>();

        public void addSegment(long segmentId, LifecycleState lifecycleState) {
            SegmentMetadata metadata = new SegmentMetadata(segmentId);
            // Transition to target state
            if (lifecycleState != LifecycleState.BOOTSTRAPPING) {
                metadata.transitionToIndexing();
                if (lifecycleState == LifecycleState.WARMING || lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                    metadata.transitionToWarming();
                    if (lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                        metadata.transitionToActive();
                        if (lifecycleState == LifecycleState.COMPACTING) {
                            metadata.transitionToCompacting();
                        }
                    }
                }
            }
            segments.put(segmentId, metadata);
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
            return creationEpochs.entrySet().stream()
                .filter(e -> e.getValue() <= queryEpoch)
                .filter(e -> tombstoneEpochs.getOrDefault(e.getKey(), Long.MAX_VALUE) > queryEpoch)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            Long creationEpoch = creationEpochs.get(segmentId);
            if (creationEpoch == null) return false;
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

    /**
     * Test graph provider that tracks mock graphs.
     */
    private static class TestGraphProvider implements EmptyResultValidator.HnswGraphProvider {
        private final Map<Long, TestGraph> graphs = new HashMap<>();

        void addEmptyGraph(long segmentId) throws IOException {
            graphs.put(segmentId, new TestGraph(0));
        }

        void addGraphWithNodes(long segmentId, int nodeCount) throws IOException {
            graphs.put(segmentId, new TestGraph(nodeCount));
        }

        @Override
        public HnswGraph getGraph(long segmentId) {
            return graphs.get(segmentId);
        }
    }

    /**
     * Minimal HNSW graph mock for testing.
     */
    private static class TestGraph extends HnswGraph {
        private final long nodeCount;

        TestGraph(int nodeCount) throws IOException {
            super(createTempPath(),
                   io.excalibur.vector.hnsw.HnswConfig.create(128),
                   io.excalibur.vector.distance.CosineSimilarity.getInstance(),
                   10000);
            this.nodeCount = nodeCount;
        }

        private static Path createTempPath() {
            try {
                return java.nio.file.Files.createTempFile("mock-graph-", ".graph");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public long nodeCount() {
            return nodeCount;
        }

        @Override
        public long activeNodeCount() {
            return nodeCount;
        }

        @Override
        public long entryPoint() {
            return nodeCount > 0 ? 0 : -1;
        }
    }
}
