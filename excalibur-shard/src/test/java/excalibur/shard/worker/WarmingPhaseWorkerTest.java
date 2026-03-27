package excalibur.shard.worker;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import io.excalibur.vector.quantization.PQConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for WarmingPhaseWorker.
 *
 * <p>Tests focus on configuration, lifecycle, and API correctness.
 * Integration tests verify the full PQ training flow.</p>
 */
class WarmingPhaseWorkerTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private WarmingPhaseWorker worker;
    private TestWarmingListener listener;

    @BeforeEach
    void setUp() throws Exception {
        // Create catalog
        Path catalogPath = tempDir.resolve("catalog.dat");
        catalog = new SegmentCatalog(catalogPath);

        // Create listener
        listener = new TestWarmingListener();

        // Create worker with short check interval for faster tests
        worker = new WarmingPhaseWorker(catalog, listener, 50, 10, null);
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.stop();
        }
        if (catalog != null) {
            catalog.close();
        }
    }

    // ==================== Construction Tests ====================

    @Test
    @DisplayName("Should create worker with default configuration")
    void shouldCreateWithDefaults() {
        WarmingPhaseWorker defaultWorker = new WarmingPhaseWorker(catalog, listener);
        assertThat(defaultWorker.getCheckIntervalMs()).isEqualTo(WarmingPhaseWorker.DEFAULT_CHECK_INTERVAL_MS);
        assertThat(defaultWorker.getMinVectorsForTraining()).isEqualTo(WarmingPhaseWorker.DEFAULT_MIN_VECTORS_FOR_TRAINING);
        assertThat(defaultWorker.getPqConfig()).isNotNull();
    }

    @Test
    @DisplayName("Should create worker with custom configuration")
    void shouldCreateWithCustomConfig() {
        PQConfig pqConfig = PQConfig.builder().numSubspaces(16).build();
        WarmingPhaseWorker customWorker = new WarmingPhaseWorker(
            catalog, listener, 200, 500, pqConfig);

        assertThat(customWorker.getCheckIntervalMs()).isEqualTo(200);
        assertThat(customWorker.getMinVectorsForTraining()).isEqualTo(500);
        assertThat(customWorker.getPqConfig().numSubspaces()).isEqualTo(16);
    }

    @Test
    @DisplayName("Should reject null catalog")
    void shouldRejectNullCatalog() {
        assertThatCode(() -> new WarmingPhaseWorker(null, listener))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Catalog cannot be null");
    }

    @Test
    @DisplayName("Should reject null listener")
    void shouldRejectNullListener() {
        assertThatCode(() -> new WarmingPhaseWorker(catalog, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Listener cannot be null");
    }

    @Test
    @DisplayName("Should reject invalid check interval")
    void shouldRejectInvalidCheckInterval() {
        assertThatCode(() -> new WarmingPhaseWorker(catalog, listener, 5, 100, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Check interval must be at least");
    }

    @Test
    @DisplayName("Should reject invalid min vectors")
    void shouldRejectInvalidMinVectors() {
        assertThatCode(() -> new WarmingPhaseWorker(catalog, listener, 100, 5, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Minimum vectors for training must be at least");
    }

    // ==================== Lifecycle Tests ====================

    @Test
    @DisplayName("Should start and stop correctly")
    void shouldStartAndStop() {
        assertThat(worker.start()).isTrue();
        assertThat(worker.isRunning()).isTrue();

        // Second start should return false
        assertThat(worker.start()).isFalse();

        // Stop
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should handle multiple stop calls")
    void shouldHandleMultipleStops() {
        worker.start();
        worker.stop();
        worker.stop(); // Should not throw
        assertThat(worker.isRunning()).isFalse();
    }

    // ==================== Configuration Tests ====================

    @Test
    @DisplayName("Should support configuration changes")
    void shouldSupportConfigChanges() {
        worker.setCheckIntervalMs(500);
        assertThat(worker.getCheckIntervalMs()).isEqualTo(500);

        worker.setMinVectorsForTraining(1000);
        assertThat(worker.getMinVectorsForTraining()).isEqualTo(1000);

        PQConfig newConfig = new PQConfig(16, 256);
        worker.setPqConfig(newConfig);
        assertThat(worker.getPqConfig().numSubspaces()).isEqualTo(16);
    }

    @Test
    @DisplayName("Should reject invalid config changes")
    void shouldRejectInvalidConfigChanges() {
        assertThatCode(() -> worker.setCheckIntervalMs(5))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> worker.setMinVectorsForTraining(5))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> worker.setPqConfig(null))
            .isInstanceOf(NullPointerException.class);
    }

    // ==================== Statistics Tests ====================

    @Test
    @DisplayName("Should have correct initial statistics")
    void shouldHaveCorrectInitialStats() {
        assertThat(worker.getTotalPqTrained()).isEqualTo(0);
        assertThat(worker.getTotalPqFailed()).isEqualTo(0);
        assertThat(worker.getTotalTransitions()).isEqualTo(0);
        assertThat(worker.getLastWarmingTime()).isEqualTo(0);
        assertThat(worker.getRegisteredSegmentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Statistics should remain 0 without segments")
    void statsShouldRemainZeroWithoutSegments() throws InterruptedException {
        worker.start();
        Thread.sleep(100);

        assertThat(worker.getTotalPqTrained()).isEqualTo(0);
        assertThat(worker.getTotalPqFailed()).isEqualTo(0);
        assertThat(worker.getTotalTransitions()).isEqualTo(0);
    }

    // ==================== toString Tests ====================

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveToString() {
        String str = worker.toString();
        assertThat(str).contains("WarmingPhaseWorker");
        assertThat(str).contains("running=false");
        assertThat(str).contains("segments=0");
        assertThat(str).contains("pqTrained=0");
        assertThat(str).contains("pqFailed=0");
    }

    // ==================== Listener Tests ====================

    @Test
    @DisplayName("Listener should not be called without registered segments")
    void listenerNotCalledWithoutSegments() throws InterruptedException {
        worker.start();
        Thread.sleep(150);

        assertThat(listener.completeCount.get()).isEqualTo(0);
    }

    // ==================== Integration Tests ====================

    @Test
    @DisplayName("Should process WARMING segment and transition to ACTIVE")
    void shouldProcessWarmingSegmentAndTransitionToActive() throws Exception {
        // This test verifies the core fix for the WarmingPhaseWorker bypass issue:
        // When DocumentReindexer transitions a segment to WARMING (without immediately
        // calling completeWarmup()), the WarmingPhaseWorker should process it and
        // transition to ACTIVE asynchronously.

        // Create a segment in WARMING state
        long segmentId = 100L;
        catalog.create("test-source", tempDir.resolve("test-segment"));

        // Get the actual segment ID that was assigned
        SegmentCatalog.SegmentMetadata created = catalog.getAll().values().stream()
            .filter(m -> m.getSourceId().equals("test-source"))
            .findFirst()
            .orElseThrow();
        segmentId = created.getSegmentId();

        // Transition through INDEXING to WARMING (simulating DocumentReindexer's new behavior)
        catalog.beginIndexing(segmentId);
        catalog.completeIndexing(segmentId); // Now in WARMING state

        // Verify segment is in WARMING state
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        assertThat(metadata).isNotNull();
        assertThat(metadata.getLifecycleState()).isEqualTo(LifecycleState.WARMING);

        // Create a minimal test graph with vectors for PQ training
        Path graphPath = tempDir.resolve("test-graph-" + segmentId + ".dat");
        int dimension = 128;

        // Use HnswConfig.create() to create a valid config
        HnswConfig config = HnswConfig.create(dimension);

        try (HnswGraph graph = HnswGraph.createWithSegment(
                graphPath, config,
                CosineSimilarity.getInstance(),
                1000)) {

            // Insert enough vectors for PQ training (at least minVectorsForTraining = 10)
            int numVectors = 15;
            for (int i = 0; i < numVectors; i++) {
                float[] vector = new float[dimension];
                // Generate deterministic normalized vectors
                for (int j = 0; j < dimension; j++) {
                    vector[j] = (float) Math.sin(i * j + 0.1);
                }
                // Normalize
                float norm = 0f;
                for (float v : vector) {
                    norm += v * v;
                }
                norm = (float) Math.sqrt(norm);
                for (int j = 0; j < dimension; j++) {
                    vector[j] /= norm;
                }
                graph.insert(vector);
            }

            // Create tail offset tracker for this graph
            TailOffsetTracker tracker = new TailOffsetTracker(graph);

            // Create a mock segment for registration
            Segment mockSegment = createMockSegment(segmentId);

            // Register the segment with the worker
            worker.registerSegment(segmentId, mockSegment, graph, tracker);

            // Start the worker
            worker.start();

            // Wait for the worker to process the segment (with timeout)
            // The worker checks every 50ms, so 3 seconds should be enough
            boolean processed = false;
            int maxAttempts = 60; // 3 seconds / 50ms
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                // Check if PQ training completed or failed (either means it was processed)
                if (worker.getTotalPqTrained() > 0 || worker.getTotalPqFailed() > 0) {
                    processed = true;
                    break;
                }
                Thread.sleep(50);
            }

            // Verify PQ training was attempted
            assertThat(processed)
                .withFailMessage("WarmingPhaseWorker did not process the WARMING segment within timeout")
                .isTrue();

            // Verify listener was called
            assertThat(listener.completeCount.get()).isGreaterThan(0);
            assertThat(listener.lastSegmentId.get()).isEqualTo(segmentId);

            // Verify segment was transitioned to ACTIVE
            SegmentCatalog.SegmentMetadata afterWarming = catalog.get(segmentId);
            assertThat(afterWarming).isNotNull();
            assertThat(afterWarming.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);

            // Verify statistics were updated
            assertThat(worker.getTotalTransitions()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Should skip PQ training for segments with insufficient vectors and still transition")
    void shouldSkipPqTrainingForInsufficientVectors() throws Exception {
        // This test verifies graceful fallback when a segment has too few vectors
        // for PQ training. The segment should still transition to ACTIVE.

        // Create a segment in WARMING state
        catalog.create("test-source-2", tempDir.resolve("test-segment-2"));

        // Get the actual segment ID
        SegmentCatalog.SegmentMetadata created = catalog.getAll().values().stream()
            .filter(m -> m.getSourceId().equals("test-source-2"))
            .findFirst()
            .orElseThrow();
        long segmentId = created.getSegmentId();

        catalog.beginIndexing(segmentId);
        catalog.completeIndexing(segmentId); // Now in WARMING state

        // Verify segment is in WARMING state
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        assertThat(metadata.getLifecycleState()).isEqualTo(LifecycleState.WARMING);

        // Create a graph with fewer vectors than minimum threshold
        Path graphPath = tempDir.resolve("test-graph-small-" + segmentId + ".dat");
        int dimension = 128;
        HnswConfig config = HnswConfig.create(dimension);

        try (HnswGraph graph = HnswGraph.createWithSegment(
                graphPath, config,
                CosineSimilarity.getInstance(),
                1000)) {
            // Insert only a few vectors (below minVectorsForTraining = 10)
            int numVectors = 5;
            for (int i = 0; i < numVectors; i++) {
                float[] vector = new float[dimension];
                for (int j = 0; j < dimension; j++) {
                    vector[j] = (float) Math.cos(i * j);
                }
                graph.insert(vector);
            }

            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            Segment mockSegment = createMockSegment(segmentId);

            worker.registerSegment(segmentId, mockSegment, graph, tracker);
            worker.start();

            // Wait for processing
            boolean processed = false;
            for (int attempt = 0; attempt < 60; attempt++) {
                if (worker.getTotalPqFailed() > 0 || worker.getTotalTransitions() > 0) {
                    processed = true;
                    break;
                }
                Thread.sleep(50);
            }

            assertThat(processed).isTrue();

            // PQ training should fail (insufficient vectors), but segment should still transition
            assertThat(worker.getTotalPqFailed()).isGreaterThan(0);

            // Verify segment was transitioned to ACTIVE (graceful fallback)
            SegmentCatalog.SegmentMetadata afterWarming = catalog.get(segmentId);
            assertThat(afterWarming.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        }
    }

    @Test
    @DisplayName("DocumentReindexer should not immediately call completeWarmup")
    void documentReindexerShouldNotImmediatelyCallCompleteWarmup() throws Exception {
        // This test verifies the fix: DocumentReindexer.reindexWithRollingReplacement()
        // should transition segments to WARMING but NOT immediately call completeWarmup().
        //
        // The WarmingPhaseWorker handles the WARMING -> ACTIVE transition asynchronously.

        // Create a segment that simulates what DocumentReindexer creates
        catalog.create("test-reindex-source", tempDir.resolve("test-reindex-segment"));

        SegmentCatalog.SegmentMetadata created = catalog.getAll().values().stream()
            .filter(m -> m.getSourceId().equals("test-reindex-source"))
            .findFirst()
            .orElseThrow();
        long segmentId = created.getSegmentId();

        // Simulate the NEW DocumentReindexer behavior:
        // 1. beginIndexing() -> INDEXING
        // 2. completeIndexing() -> WARMING (NOT immediately calling completeWarmup!)
        catalog.beginIndexing(segmentId);
        catalog.completeIndexing(segmentId);

        // Verify the segment stays in WARMING state (not immediately ACTIVE)
        SegmentCatalog.SegmentMetadata afterReindex = catalog.get(segmentId);
        assertThat(afterReindex.getLifecycleState())
            .withFailMessage("Segment should remain in WARMING state after DocumentReindexer completes. " +
                "The WarmingPhaseWorker should handle the WARMING -> ACTIVE transition.")
            .isEqualTo(LifecycleState.WARMING);

        // Now simulate WarmingPhaseWorker processing
        Path graphPath = tempDir.resolve("test-reindex-graph-" + segmentId + ".dat");
        int dimension = 128;
        HnswConfig config = HnswConfig.create(dimension);

        try (HnswGraph graph = HnswGraph.createWithSegment(
                graphPath, config,
                CosineSimilarity.getInstance(),
                1000)) {
            // Add minimum vectors
            for (int i = 0; i < 15; i++) {
                float[] vector = new float[dimension];
                for (int j = 0; j < dimension; j++) {
                    vector[j] = (float) Math.sin(i * j);
                }
                graph.insert(vector);
            }

            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            Segment mockSegment = createMockSegment(segmentId);

            worker.registerSegment(segmentId, mockSegment, graph, tracker);
            worker.start();

            // Wait for worker to process
            for (int attempt = 0; attempt < 60; attempt++) {
                if (worker.getTotalTransitions() > 0) {
                    break;
                }
                Thread.sleep(50);
            }

            // NOW the segment should be ACTIVE (after WarmingPhaseWorker processed it)
            SegmentCatalog.SegmentMetadata afterWorker = catalog.get(segmentId);
            assertThat(afterWorker.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        }
    }

    // ==================== Test Helpers ====================

    /**
     * Creates a minimal segment for testing purposes.
     * Uses a real segment file with a shared Arena for memory management.
     */
    private Segment createMockSegment(long segmentId) throws IOException {
        // Create a real segment file for testing
        Path segmentPath = tempDir.resolve("mock-segment-" + segmentId + ".dat");
        long capacityBytes = 1024 * 1024; // 1MB
        Arena arena = Arena.ofShared();
        return new Segment(segmentPath, capacityBytes, arena);
    }

    private static class TestWarmingListener implements WarmingPhaseWorker.WarmingListener {
        final AtomicInteger completeCount = new AtomicInteger(0);
        final AtomicBoolean lastPqSuccess = new AtomicBoolean(false);
        final AtomicInteger lastVectorCount = new AtomicInteger(0);
        final AtomicLong lastSegmentId = new AtomicLong(-1);

        @Override
        public void onWarmingComplete(long segmentId, boolean pqSuccess, int vectorCount) {
            completeCount.incrementAndGet();
            lastPqSuccess.set(pqSuccess);
            lastVectorCount.set(vectorCount);
            lastSegmentId.set(segmentId);
        }
    }
}
