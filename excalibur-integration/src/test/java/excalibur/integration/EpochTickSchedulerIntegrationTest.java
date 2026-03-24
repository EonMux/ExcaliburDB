package excalibur.integration;

import excalibur.shard.EpochTickScheduler;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for epoch tick scheduler wiring.
 *
 * <p>Verifies that when ExcaliburDBServer starts, the EpochTickScheduler
 * is automatically started and advances the visibility epoch periodically.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Epoch should advance automatically without manual intervention</li>
 *   <li>Default tick interval is 100ms per spec</li>
 *   <li>Epoch values should be non-negative and monotonically increasing</li>
 * </ul>
 */
class EpochTickSchedulerIntegrationTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("test-store");
        Files.createDirectories(dataDir);
        storageEngine = new StorageEngine(dataDir, Arena.ofShared());
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
    }

    @Test
    @DisplayName("epoch tick scheduler advances epoch automatically")
    void epochAdvancesAutomatically() throws InterruptedException {
        // Create a real IndexOrchestrator with default settings
        orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // Get initial epoch
        long initialEpoch = orchestrator.getVisibilityEpoch();

        // Start the orchestrator (which starts the epoch tick scheduler)
        boolean started = orchestrator.start();
        assertThat(started).isTrue();

        // Wait for at least 2 tick intervals (default is 100ms)
        Thread.sleep(250);

        // Verify epoch has advanced
        long newEpoch = orchestrator.getVisibilityEpoch();
        assertThat(newEpoch).isGreaterThan(initialEpoch);

        // Verify the tick count has increased
        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();
        assertThat(scheduler.getTickCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("epoch tick scheduler uses configured interval")
    void usesConfiguredInterval() {
        // Create orchestrator with custom 50ms tick interval
        orchestrator = new IndexOrchestrator(
            catalog,
            storageEngine,
            100000,  // hnswQueueDepthLimit
            5000,    // cuckooMaintenanceIntervalMs
            60000,   // reindexCheckIntervalMs
            50       // epochTickIntervalMs (50ms for faster testing)
        );

        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();
        assertThat(scheduler.getTickIntervalMs()).isEqualTo(50);
    }

    @Test
    @DisplayName("epoch tick scheduler stops when orchestrator closes")
    void stopsWhenOrchestratorCloses() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        orchestrator.start();

        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();
        assertThat(scheduler.isRunning()).isTrue();

        // Close the orchestrator
        orchestrator.close();

        // Verify scheduler is stopped
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("visibility tracker epoch is synchronized with scheduler")
    void epochSynchronized() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        VisibilityTracker tracker = orchestrator.getVisibilityTracker();

        long initialEpoch = tracker.currentEpoch();

        orchestrator.start();
        Thread.sleep(150);

        // Verify visibility tracker epoch has advanced
        long newEpoch = tracker.currentEpoch();
        assertThat(newEpoch).isGreaterThan(initialEpoch);
    }

    @Test
    @DisplayName("multiple orchestrator starts are idempotent")
    void multipleStartsIdempotent() {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // First start should succeed
        boolean firstStart = orchestrator.start();
        assertThat(firstStart).isTrue();

        // Second start should return false (already running)
        boolean secondStart = orchestrator.start();
        assertThat(secondStart).isFalse();

        // Verify it's running
        assertThat(orchestrator.isRunning()).isTrue();
    }
}
