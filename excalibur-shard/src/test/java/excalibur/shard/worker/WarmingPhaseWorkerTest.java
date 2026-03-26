package excalibur.shard.worker;

import io.excalibur.store.SegmentCatalog;
import io.excalibur.vector.quantization.PQConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    // ==================== Test Helpers ====================

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
