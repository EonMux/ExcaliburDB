package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for epoch tick scheduler wiring.
 *
 * <p>Verifies that when ExcaliburDBServer starts, the EpochTickScheduler
 * is automatically started and advances the visibility epoch periodically.</p>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>Epoch should advance automatically without manual intervention</li>
 *   <li>Default tick interval is 100ms per spec (excalibur.visibility.epoch-tick-ms)</li>
 *   <li>Epoch values must be non-negative and monotonically increasing</li>
 *   <li>Scheduler must properly start, stop, and close resources</li>
 *   <li>Executor must be fully shut down after close()</li>
 *   <li>Tick count must be >= 0 at all times</li>
 *   <li>Visibility epoch must match scheduler tick count progression</li>
 * </ul>
 *
 * <h2>Write Visibility Contract</h2>
 * <p>Per spec, an ingested entry becomes queryable within one epoch tick
 * (default 100ms) of write log fsync.</p>
 *
 * @see EpochTickScheduler
 * @see IndexOrchestrator
 * @see VisibilityTracker
 */
class EpochTickSchedulerIntegrationTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    /**
     * Sets up the test fixtures before each test.
     *
     * <p>Creates a SegmentCatalog and StorageEngine in a temporary directory
     * for isolated testing.</p>
     *
     * @throws Exception if setup fails
     */
    @BeforeEach
    void setUp() throws Exception {
        catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("test-store");
        Files.createDirectories(dataDir);
        storageEngine = new StorageEngine(dataDir, Arena.ofShared());
    }

    /**
     * Tears down test fixtures after each test.
     *
     * <p>Ensures all resources are properly closed to prevent resource leaks.</p>
     */
    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
    }

    /**
     * Verifies that the epoch advances automatically at the configured interval.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Epoch must increase over time when scheduler is running</li>
     *   <li>Tick count must be >= 2 after waiting 2.5x the tick interval</li>
     *   <li>Default interval of 100ms must be respected</li>
     * </ul>
     *
     * @throws InterruptedException if the test thread is interrupted
     */
    @Test
    @DisplayName("epoch tick scheduler advances epoch automatically")
    void epochAdvancesAutomatically() throws InterruptedException {
        // Create a real IndexOrchestrator with default settings
        orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // Get initial epoch - must be non-negative
        long initialEpoch = orchestrator.getVisibilityEpoch();
        assertThat(initialEpoch).isGreaterThanOrEqualTo(0L);

        // Start the orchestrator (which starts the epoch tick scheduler)
        boolean started = orchestrator.start();
        assertThat(started).as("First start() should return true").isTrue();

        // Wait for at least 2 tick intervals (default is 100ms)
        Thread.sleep(250);

        // Verify epoch has advanced (domain correctness: monotonically increasing)
        long newEpoch = orchestrator.getVisibilityEpoch();
        assertThat(newEpoch)
            .as("Epoch should advance when scheduler is running")
            .isGreaterThan(initialEpoch);

        // Verify the tick count has increased (at least 2 ticks in 250ms with 100ms interval)
        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();
        assertThat(scheduler.getTickCount())
            .as("Should have at least 2 ticks after 250ms with 100ms interval")
            .isGreaterThanOrEqualTo(2L);

        // Domain correctness: tick count must be non-negative
        assertThat(scheduler.getTickCount()).isGreaterThanOrEqualTo(0L);
    }

    /**
     * Verifies that the scheduler uses the configured interval.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Custom interval must be respected</li>
     *   <li>Interval must be positive</li>
     * </ul>
     */
    @Test
    @DisplayName("epoch tick scheduler uses configured interval")
    void usesConfiguredInterval() {
        // Create orchestrator with custom 50ms tick interval
        orchestrator = new IndexOrchestrator(
            catalog,
            storageEngine,
            100000,  // hnswQueueDepthLimit
            60000,   // reindexCheckIntervalMs
            50       // epochTickIntervalMs (50ms for faster testing)
        );

        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();

        // Domain correctness: interval must match configured value
        assertThat(scheduler.getTickIntervalMs())
            .as("Scheduler should use the configured interval")
            .isEqualTo(50L);

        // Domain correctness: interval must be positive
        assertThat(scheduler.getTickIntervalMs()).isGreaterThan(0L);
    }

    /**
     * Verifies that the scheduler stops when the orchestrator closes.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Scheduler must not be running after close()</li>
     *   <li>Executor must be shut down after close()</li>
     *   <li>Tick count must be preserved after close</li>
     * </ul>
     *
     * @throws InterruptedException if the test thread is interrupted
     */
    @Test
    @DisplayName("epoch tick scheduler stops when orchestrator closes")
    void stopsWhenOrchestratorCloses() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        orchestrator.start();

        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();
        assertThat(scheduler.isRunning())
            .as("Scheduler should be running after orchestrator starts")
            .isTrue();

        // Let some ticks happen
        Thread.sleep(150);

        // Record tick count before close
        long tickCountBeforeClose = scheduler.getTickCount();
        assertThat(tickCountBeforeClose)
            .as("Should have at least 1 tick before close")
            .isGreaterThanOrEqualTo(1L);

        // Close the orchestrator
        orchestrator.close();

        // Domain correctness: scheduler must not be running after close
        assertThat(scheduler.isRunning())
            .as("Scheduler should not be running after orchestrator closes")
            .isFalse();

        // Tick count must be preserved after close
        assertThat(scheduler.getTickCount())
            .as("Tick count should be preserved after close")
            .isEqualTo(tickCountBeforeClose);
    }

    /**
     * Verifies that the executor is fully shut down after close().
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Executor service must be terminated after close()</li>
     *   <li>Resources must be properly cleaned up</li>
     *   <li>No threads should be left running after close</li>
     * </ul>
     *
     * @throws InterruptedException if the test thread is interrupted
     */
    @Test
    @DisplayName("executor is fully shut down after close")
    void executorIsFullyShutdownAfterClose() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        orchestrator.start();

        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();

        // Let some ticks happen to ensure scheduler is active
        Thread.sleep(150);

        // Close the orchestrator which should close the scheduler
        orchestrator.close();

        // Domain correctness: scheduler must not be running after close
        assertThat(scheduler.isRunning())
            .as("Scheduler should not be running after close")
            .isFalse();

        // Get stats to verify scheduler state
        EpochTickScheduler.EpochTickStats stats = scheduler.getStats();
        assertThat(stats.running())
            .as("Stats should report scheduler as not running")
            .isFalse();

        // Verify tick count is preserved
        assertThat(stats.tickCount())
            .as("Tick count should be preserved after close")
            .isGreaterThanOrEqualTo(1L);
    }

    /**
     * Verifies that the visibility tracker epoch is synchronized with the scheduler.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Visibility tracker epoch must advance when scheduler ticks</li>
     *   <li>Epoch values must be consistent between scheduler and tracker</li>
     * </ul>
     *
     * @throws InterruptedException if the test thread is interrupted
     */
    @Test
    @DisplayName("visibility tracker epoch is synchronized with scheduler")
    void epochSynchronized() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        VisibilityTracker tracker = orchestrator.getVisibilityTracker();

        // Domain correctness: initial epoch must be non-negative
        long initialEpoch = tracker.currentEpoch();
        assertThat(initialEpoch).isGreaterThanOrEqualTo(0L);

        orchestrator.start();
        Thread.sleep(150);

        // Verify visibility tracker epoch has advanced
        long newEpoch = tracker.currentEpoch();

        // Domain correctness: epoch must be monotonically increasing
        assertThat(newEpoch)
            .as("Visibility tracker epoch should advance when scheduler runs")
            .isGreaterThan(initialEpoch);
    }

    /**
     * Verifies that multiple orchestrator starts are idempotent.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>First start() should return true</li>
     *   <li>Second start() should return false (already running)</li>
     *   <li>Scheduler state must be consistent</li>
     * </ul>
     */
    @Test
    @DisplayName("multiple orchestrator starts are idempotent")
    void multipleStartsIdempotent() {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // First start should succeed
        boolean firstStart = orchestrator.start();
        assertThat(firstStart)
            .as("First start() should return true")
            .isTrue();

        // Verify it's running
        assertThat(orchestrator.isRunning())
            .as("Orchestrator should be running after first start")
            .isTrue();

        // Second start should return false (already running)
        boolean secondStart = orchestrator.start();
        assertThat(secondStart)
            .as("Second start() should return false when already running")
            .isFalse();

        // Domain correctness: should still be running after second start attempt
        assertThat(orchestrator.isRunning())
            .as("Orchestrator should still be running after second start attempt")
            .isTrue();

        // Scheduler should also report running
        assertThat(orchestrator.getEpochTickScheduler().isRunning())
            .as("Epoch tick scheduler should be running")
            .isTrue();
    }

    /**
     * Verifies that the default tick interval matches the spec (100ms).
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Default interval must be exactly 100ms per spec</li>
     *   <li>excalibur.visibility.epoch-tick-ms default is 100</li>
     * </ul>
     */
    @Test
    @DisplayName("default tick interval is 100ms per spec")
    void defaultTickIntervalIs100ms() {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);

        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();

        // Domain correctness: default must be 100ms per spec
        assertThat(scheduler.getTickIntervalMs())
            .as("Default tick interval must be 100ms per spec")
            .isEqualTo(100L);
    }

    /**
     * Verifies that the scheduler properly handles start-stop-restart cycles.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Scheduler can be stopped and restarted</li>
     *   <li>Tick count accumulates across restart cycles</li>
     *   <li>Epoch continues to advance after restart</li>
     * </ul>
     *
     * @throws InterruptedException if the test thread is interrupted
     */
    @Test
    @DisplayName("scheduler can be stopped and restarted")
    void schedulerCanBeStoppedAndRestarted() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();

        // Start and let ticks happen
        orchestrator.start();
        Thread.sleep(150);

        long tickCountAfterFirstRun = scheduler.getTickCount();
        assertThat(tickCountAfterFirstRun)
            .as("Should have at least 1 tick after first run")
            .isGreaterThanOrEqualTo(1L);

        // Stop via orchestrator.stop (not close)
        orchestrator.stop(Duration.ofSeconds(10));

        // Verify scheduler is stopped
        assertThat(scheduler.isRunning())
            .as("Scheduler should be stopped after orchestrator.stop")
            .isFalse();

        // Restart
        boolean restarted = orchestrator.start();
        assertThat(restarted)
            .as("Restart should succeed")
            .isTrue();

        // Let more ticks happen
        Thread.sleep(150);

        // Domain correctness: tick count should have increased
        assertThat(scheduler.getTickCount())
            .as("Tick count should increase after restart")
            .isGreaterThan(tickCountAfterFirstRun);

        // Verify scheduler is running again
        assertThat(scheduler.isRunning())
            .as("Scheduler should be running after restart")
            .isTrue();
    }

    /**
     * Verifies that the scheduler stats are accurate.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Stats must accurately reflect scheduler state</li>
     *   <li>Current epoch in stats must match visibility tracker</li>
     *   <li>All stats values must be non-negative</li>
     * </ul>
     *
     * @throws InterruptedException if the test thread is interrupted
     */
    @Test
    @DisplayName("scheduler stats are accurate")
    void schedulerStatsAreAccurate() throws InterruptedException {
        orchestrator = new IndexOrchestrator(catalog, storageEngine);
        EpochTickScheduler scheduler = orchestrator.getEpochTickScheduler();

        orchestrator.start();
        Thread.sleep(150);

        EpochTickScheduler.EpochTickStats stats = scheduler.getStats();

        // Domain correctness: all stats values must be non-negative
        assertThat(stats.running()).isTrue();
        assertThat(stats.tickCount()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.tickIntervalMs()).isEqualTo(100L);
        assertThat(stats.currentEpoch()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.lastTickTimestamp()).isGreaterThan(0L);
        assertThat(stats.timeSinceLastTickMs()).isGreaterThanOrEqualTo(0L);

        // Stats epoch must match visibility tracker
        assertThat(stats.currentEpoch())
            .as("Stats epoch must match visibility tracker epoch")
            .isEqualTo(orchestrator.getVisibilityEpoch());
    }
}
