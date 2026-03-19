package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link TailOffsetTracker}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Basic offset tracking and retrieval</li>
 *   <li>Monotonic offset increase guarantee</li>
 *   <li>Thread safety under concurrent access</li>
 *   <li>Integration with HNSW graph</li>
 *   <li>Tail range calculations</li>
 *   <li>Batch integration operations</li>
 *   <li>Persistence and recovery</li>
 * </ul>
 */
class TailOffsetTrackerTest {

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;
    private HnswGraph graph;
    private TailOffsetTracker tracker;

    @BeforeEach
    void setUp() throws IOException {
        graphPath = tempDir.resolve("test.hnsw");
        config = HnswConfig.create(64);
        distanceFunction = CosineSimilarity.getInstance();
        graph = new HnswGraph(graphPath, config, distanceFunction, 1000);
        tracker = new TailOffsetTracker(graph);
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.close();
        }
    }

    // ==================== Construction Tests ====================

    @Test
    void shouldCreateTrackerWithGraph() {
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(0);
    }

    @Test
    void shouldRejectNullGraph() {
        assertThatThrownBy(() -> new TailOffsetTracker(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Graph cannot be null");
    }

    @Test
    void shouldInitializeWithGraphOffset() throws IOException {
        // Set offset on graph before creating tracker
        graph.setLastIntegratedOffset(100);

        try (HnswGraph newGraph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            newGraph.setLastIntegratedOffset(100);
            newGraph.flush();
        }

        // Reopen graph
        try (HnswGraph reopenedGraph = HnswGraph.open(graphPath, distanceFunction)) {
            TailOffsetTracker newTracker = new TailOffsetTracker(reopenedGraph);
            assertThat(newTracker.getLastIntegratedOffset()).isEqualTo(100);
        }
    }

    // ==================== Offset Query Tests ====================

    @Test
    void shouldReturnZeroInitially() {
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(0);
    }

    @Test
    void shouldDetectTailEntries() {
        // No tail when write log head equals integrated offset
        assertThat(tracker.hasTailEntries(0)).isFalse();
        assertThat(tracker.hasTailEntries(100)).isTrue();
    }

    @Test
    void shouldCalculateTailSize() {
        assertThat(tracker.getTailSize(0)).isEqualTo(0);
        assertThat(tracker.getTailSize(100)).isEqualTo(100);
        assertThat(tracker.getTailSize(500)).isEqualTo(500);

        // After setting offset
        tracker.setLastIntegratedOffset(200);
        assertThat(tracker.getTailSize(100)).isEqualTo(0); // writeLogHead < integrated
        assertThat(tracker.getTailSize(200)).isEqualTo(0); // equal
        assertThat(tracker.getTailSize(300)).isEqualTo(100); // tail exists
    }

    @Test
    void shouldReturnTailRange() {
        // No tail when write log head equals integrated offset
        assertThat(tracker.getTailRange(0)).isNull();

        // Tail range when entries exist
        long[] range = tracker.getTailRange(100);
        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(0);  // start
        assertThat(range[1]).isEqualTo(100); // end

        // After setting offset
        tracker.setLastIntegratedOffset(50);
        range = tracker.getTailRange(100);
        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(50);
        assertThat(range[1]).isEqualTo(100);

        // No tail when write log head <= integrated offset
        assertThat(tracker.getTailRange(50)).isNull();
        assertThat(tracker.getTailRange(40)).isNull();
    }

    // ==================== Offset Update Tests ====================

    @Test
    void shouldSetLastIntegratedOffset() {
        boolean updated = tracker.setLastIntegratedOffset(100);
        assertThat(updated).isTrue();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(100);
    }

    @Test
    void shouldRejectNegativeOffset() {
        assertThatThrownBy(() -> tracker.setLastIntegratedOffset(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Offset cannot be negative");
    }

    @Test
    void shouldOnlyAllowIncreasingOffset() {
        tracker.setLastIntegratedOffset(100);

        // Try to decrease - should fail
        boolean decreased = tracker.setLastIntegratedOffset(50);
        assertThat(decreased).isFalse();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(100);

        // Try same value - should fail
        boolean same = tracker.setLastIntegratedOffset(100);
        assertThat(same).isFalse();

        // Increase - should succeed
        boolean increased = tracker.setLastIntegratedOffset(200);
        assertThat(increased).isTrue();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(200);
    }

    @Test
    void shouldUseCompareAndSetOffset() {
        tracker.setLastIntegratedOffset(100);

        // Try to decrease with CAS - should fail
        boolean decreased = tracker.compareAndSetOffset(50);
        assertThat(decreased).isFalse();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(100);

        // Increase with CAS - should succeed
        boolean increased = tracker.compareAndSetOffset(200);
        assertThat(increased).isTrue();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(200);
    }

    @Test
    void shouldRejectNegativeCompareAndSet() {
        boolean result = tracker.compareAndSetOffset(-1);
        assertThat(result).isFalse();
    }

    // ==================== Integration Result Tests ====================

    @Test
    void shouldMarkIntegratedResult() {
        // Insert a vector with write log offset
        float[] vector = randomVector(64);
        IncrementalInsertResult result = graph.insertWithResult(vector, 100);

        // Mark as integrated
        boolean marked = tracker.markIntegrated(result);
        assertThat(marked).isTrue();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(101); // offset + 1
    }

    @Test
    void shouldNotMarkFailedResult() {
        // Use FAILED result
        boolean marked = tracker.markIntegrated(IncrementalInsertResult.FAILED);
        assertThat(marked).isFalse();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(0);
    }

    @Test
    void shouldNotMarkNullResult() {
        boolean marked = tracker.markIntegrated(null);
        assertThat(marked).isFalse();
    }

    @Test
    void shouldNotMarkResultWithoutWriteLogOffset() {
        // Insert without write log offset
        float[] vector = randomVector(64);
        IncrementalInsertResult result = graph.insertWithResult(vector); // no offset

        boolean marked = tracker.markIntegrated(result);
        assertThat(marked).isFalse(); // writeLogOffset is -1
    }

    @Test
    void shouldMarkBatchIntegrated() {
        List<IncrementalInsertResult> results = new ArrayList<>();

        // Insert several vectors with write log offsets
        for (int i = 0; i < 5; i++) {
            float[] vector = randomVector(64);
            IncrementalInsertResult result = graph.insertWithResult(vector, i * 100);
            results.add(result);
        }

        int count = tracker.markBatchIntegrated(results);
        assertThat(count).isEqualTo(5);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(401); // (4 * 100) + 1
    }

    @Test
    void shouldHandleEmptyBatch() {
        int count = tracker.markBatchIntegrated(new ArrayList<>());
        assertThat(count).isEqualTo(0);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(0);
    }

    @Test
    void shouldHandleNullBatch() {
        int count = tracker.markBatchIntegrated(null);
        assertThat(count).isEqualTo(0);
    }

    // ==================== Integration Coordination Tests ====================

    @Test
    void shouldIntegrateSingleVector() {
        float[] vector = randomVector(64);
        IncrementalInsertResult result = tracker.integrateVector(vector, 100);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.writeLogOffset()).isEqualTo(100);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(101);
    }

    @Test
    void shouldIntegrateBatch() {
        List<float[]> vectors = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            vectors.add(randomVector(64));
            offsets.add((long) i * 50);
        }

        List<IncrementalInsertResult> results = tracker.integrateBatch(vectors, offsets);

        assertThat(results).hasSize(10);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(451); // (9 * 50) + 1
    }

    @Test
    void shouldMaintainOffsetAcrossMultipleIntegrations() {
        // First integration
        tracker.integrateVector(randomVector(64), 100);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(101);

        // Second integration with higher offset
        tracker.integrateVector(randomVector(64), 200);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(201);

        // Third with lower offset - should not decrease
        tracker.integrateVector(randomVector(64), 150);
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(201);
    }

    // ==================== Thread Safety Tests ====================

    @Test
    void shouldBeThreadSafeForConcurrentUpdates() throws InterruptedException {
        int numThreads = 10;
        int updatesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        // Each thread tries to set a unique offset
                        long offset = threadId * 1000L + i + 1;
                        if (tracker.compareAndSetOffset(offset)) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify offset was updated at least once (likely many times)
        assertThat(tracker.getLastIntegratedOffset()).isGreaterThan(0);

        // Final offset should be the maximum offset attempted
        long maxAttemptedOffset = (numThreads - 1) * 1000L + updatesPerThread;
        assertThat(tracker.getLastIntegratedOffset()).isLessThanOrEqualTo(maxAttemptedOffset + 1);
    }

    @Test
    void shouldBeThreadSafeForConcurrentIntegrations() throws InterruptedException {
        int numThreads = 4;
        int vectorsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger totalIntegrated = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < vectorsPerThread; i++) {
                        float[] vector = randomVector(64);
                        long offset = threadId * 1000L + i * 10L;
                        IncrementalInsertResult result = tracker.integrateVector(vector, offset);
                        if (result.isSuccess()) {
                            totalIntegrated.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // All vectors should be integrated
        assertThat(totalIntegrated.get()).isEqualTo(numThreads * vectorsPerThread);
        assertThat(graph.nodeCount()).isEqualTo(numThreads * vectorsPerThread);

        // Offset should reflect the highest write log offset integrated
        assertThat(tracker.getLastIntegratedOffset()).isGreaterThan(0);
    }

    // ==================== Persistence Tests ====================

    @Test
    void shouldFlushOffset() throws IOException {
        tracker.setLastIntegratedOffset(12345);
        tracker.flush();

        // Verify graph has the offset
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(12345);
    }

    @Test
    void shouldPersistAndReload() throws IOException {
        // Set offset and flush
        tracker.setLastIntegratedOffset(50000);
        tracker.flush();
        graph.flush();

        // Close graph
        graph.close();

        // Reopen and create new tracker
        try (HnswGraph reopenedGraph = HnswGraph.open(graphPath, distanceFunction)) {
            TailOffsetTracker newTracker = new TailOffsetTracker(reopenedGraph);
            assertThat(newTracker.getLastIntegratedOffset()).isEqualTo(50000);
        }
    }

    @Test
    void shouldReloadFromGraph() throws IOException {
        // Set offset directly on graph
        graph.setLastIntegratedOffset(999);
        graph.flush();

        // Reload tracker
        tracker.reload();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(999);
    }

    // ==================== Statistics Tests ====================

    @Test
    void shouldReturnStatsString() {
        tracker.setLastIntegratedOffset(500);

        String stats = tracker.getStats(1000);
        assertThat(stats).contains("offset=500");
        assertThat(stats).contains("writeLogHead=1000");
        assertThat(stats).contains("tailSize=500");
        assertThat(stats).contains("progress=50.0%");
    }

    @Test
    void shouldHandleZeroWriteLogHead() {
        String stats = tracker.getStats(0);
        assertThat(stats).contains("progress=100.0%"); // No tail means fully integrated
    }

    @Test
    void shouldReturnToString() {
        tracker.setLastIntegratedOffset(100);
        String str = tracker.toString();
        assertThat(str).contains("offset=100");
        assertThat(str).contains(graphPath.toString());
    }

    // ==================== Sync Operations Tests ====================

    @Test
    void shouldUpdateWithSync() throws IOException {
        boolean updated = tracker.compareAndSetOffsetSync(1000);
        assertThat(updated).isTrue();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(1000);

        // Verify persistence by flushing and reloading
        graph.flush();
        graph.close();

        try (HnswGraph reopenedGraph = HnswGraph.open(graphPath, distanceFunction)) {
            TailOffsetTracker newTracker = new TailOffsetTracker(reopenedGraph);
            assertThat(newTracker.getLastIntegratedOffset()).isEqualTo(1000);
        }
    }

    @Test
    void shouldNotDecreaseWithSync() throws IOException {
        tracker.setLastIntegratedOffset(1000);

        // Try to decrease with sync
        boolean updated = tracker.compareAndSetOffsetSync(500);
        assertThat(updated).isFalse();
        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(1000);
    }

    @Test
    void shouldBeAtomicUnderConcurrentSync() throws InterruptedException, IOException {
        int numThreads = 4;
        int updatesPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        long offset = threadId * 10000L + i * 100L + 1;
                        if (tracker.compareAndSetOffsetSync(offset)) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify offset increased
        assertThat(tracker.getLastIntegratedOffset()).isGreaterThan(0);

        // Verify persistence
        graph.flush();
        graph.close();

        try (HnswGraph reopenedGraph = HnswGraph.open(graphPath, distanceFunction)) {
            // Persisted offset should be <= in-memory offset
            assertThat(reopenedGraph.getLastIntegratedOffset()).isGreaterThanOrEqualTo(0);
            assertThat(reopenedGraph.getLastIntegratedOffset())
                .isLessThanOrEqualTo(tracker.getLastIntegratedOffset());
        }
    }

    // ==================== Helper Methods ====================

    private float[] randomVector(int dim) {
        Random random = new Random();
        float[] vec = new float[dim];
        for (int i = 0; i < dim; i++) {
            vec[i] = random.nextFloat() * 2 - 1;
        }
        return vec;
    }
}
