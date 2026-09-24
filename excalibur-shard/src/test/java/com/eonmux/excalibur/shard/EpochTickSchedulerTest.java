package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.SegmentCatalog;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for EpochTickScheduler periodic epoch advancement.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Default tick interval is 100ms</li>
 *   <li>Start/stop lifecycle works correctly</li>
 *   <li>Epoch advances periodically when scheduler is running</li>
 *   <li>Each tick records its count and timestamp</li>
 *   <li>No ticks after stop</li>
 *   <li>Custom intervals work correctly</li>
 * </ul>
 */
class EpochTickSchedulerTest {

    private SegmentCatalog catalog;
    private VisibilityTracker tracker;
    private EpochTickScheduler scheduler;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        tracker = new VisibilityTracker(catalog);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.stop();
            scheduler.close();
        }
        if (catalog != null) {
            catalog.close();
        }
    }

    /**
     * Maximum time a test waits for the scheduler to reach a tick count.
     *
     * <p>Generous on purpose: the build runs test forks in parallel, so a fixed sleep
     * sized to the tick interval measures the machine's load, not the scheduler.</p>
     */
    private static final long AWAIT_TIMEOUT_MS = 5_000;

    /**
     * Waits until the scheduler has recorded at least {@code minTicks} ticks.
     *
     * @param minTicks Tick count to wait for
     */
    private void awaitTicks(long minTicks) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
        while (scheduler.getTickCount() < minTicks && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(scheduler.getTickCount())
            .as("scheduler should have ticked at least %d times within %dms",
                minTicks, AWAIT_TIMEOUT_MS)
            .isGreaterThanOrEqualTo(minTicks);
    }

    @Test
    @DisplayName("Default tick interval should be 100ms")
    void defaultTickIntervalShouldBe100ms() {
        scheduler = new EpochTickScheduler(tracker);

        assertThat(scheduler.getTickIntervalMs()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Initial state should not be running")
    void initialStateShouldNotBeRunning() {
        scheduler = new EpochTickScheduler(tracker);

        assertThat(scheduler.isRunning()).isFalse();
        assertThat(scheduler.getTickCount()).isEqualTo(0L);
        assertThat(scheduler.getLastTickTimestamp()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Start should return true and set running state")
    void startShouldSetRunningState() {
        scheduler = new EpochTickScheduler(tracker);

        boolean started = scheduler.start();

        assertThat(started).isTrue();
        assertThat(scheduler.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Start should return false if already running")
    void startShouldReturnFalseIfAlreadyRunning() {
        scheduler = new EpochTickScheduler(tracker);
        scheduler.start();

        boolean startedAgain = scheduler.start();

        assertThat(startedAgain).isFalse();
    }

    @Test
    @DisplayName("Stop should clear running state")
    void stopShouldClearRunningState() {
        scheduler = new EpochTickScheduler(tracker);
        scheduler.start();

        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Epoch should advance periodically when scheduler is running")
    void epochShouldAdvancePeriodically() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10); // Use 10ms interval for faster tests
        long epochBefore = tracker.currentEpoch();

        scheduler.start();

        awaitTicks(2);

        long epochAfter = tracker.currentEpoch();
        assertThat(epochAfter).isGreaterThan(epochBefore);
    }

    @Test
    @DisplayName("a tick records its count and timestamp")
    void tickRecordsCountAndTimestamp() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10);
        long epochBefore = tracker.currentEpoch();
        long startedAt = System.currentTimeMillis();
        assertThat(scheduler.getTickCount()).isZero();

        scheduler.start();
        awaitTicks(1);

        assertThat(tracker.currentEpoch()).isGreaterThan(epochBefore);
        assertThat(scheduler.getLastTickTimestamp()).isGreaterThanOrEqualTo(startedAt);
    }

    @Test
    @DisplayName("No ticks should occur after stop")
    void noTicksAfterStop() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10);
        scheduler.start();
        awaitTicks(1); // Let some ticks happen
        scheduler.stop();

        // stop() fences a tick that was already dispatched, so the counts read here are
        // final rather than racing an in-flight tick.
        long tickCountAfterStop = scheduler.getTickCount();
        long epochAfterStop = tracker.currentEpoch();

        // Wait several tick intervals to give a leaked tick every chance to fire
        Thread.sleep(200);

        // Tick count and epoch should not have changed
        assertThat(scheduler.getTickCount()).isEqualTo(tickCountAfterStop);
        assertThat(tracker.currentEpoch()).isEqualTo(epochAfterStop);
    }

    @Test
    @DisplayName("Custom interval should be respected")
    void customIntervalShouldBeRespected() {
        scheduler = new EpochTickScheduler(tracker, 50);

        assertThat(scheduler.getTickIntervalMs()).isEqualTo(50L);
    }

    @Test
    @DisplayName("Custom interval scheduler should advance epoch faster")
    void customIntervalShouldAdvanceFaster() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10); // 20ms interval
        long epochBefore = tracker.currentEpoch();

        scheduler.start();

        awaitTicks(4);

        long epochAfter = tracker.currentEpoch();
        assertThat(epochAfter).isGreaterThan(epochBefore);
    }

    @Test
    @DisplayName("Rejects null VisibilityTracker")
    void rejectsNullVisibilityTracker() {
        assertThatThrownBy(() -> new EpochTickScheduler(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("VisibilityTracker cannot be null");
    }

    @Test
    @DisplayName("Rejects non-positive tick interval")
    void rejectsNonPositiveTickInterval() {
        assertThatThrownBy(() -> new EpochTickScheduler(tracker, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tick interval must be positive");

        assertThatThrownBy(() -> new EpochTickScheduler(tracker, -100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tick interval must be positive");
    }

    @Test
    @DisplayName("getStats should return correct statistics")
    void getStatsShouldReturnCorrectStats() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10);
        scheduler.start();
        awaitTicks(2);

        EpochTickScheduler.EpochTickStats stats = scheduler.getStats();

        assertThat(stats.running()).isTrue();
        assertThat(stats.tickCount()).isGreaterThanOrEqualTo(2L);
        assertThat(stats.tickIntervalMs()).isEqualTo(10L);
        assertThat(stats.lastTickTimestamp()).isGreaterThan(0L);
        assertThat(stats.currentEpoch()).isGreaterThanOrEqualTo(2L);
        assertThat(stats.timeSinceLastTickMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Scheduler can be restarted after stop")
    void schedulerCanBeRestarted() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10); // Use 20ms interval

        // Start, wait, stop
        scheduler.start();
        awaitTicks(1);
        scheduler.stop();

        long tickCountAfterFirstRun = scheduler.getTickCount();
        assertThat(tickCountAfterFirstRun).isGreaterThanOrEqualTo(1L);

        // Restart
        boolean restarted = scheduler.start();
        assertThat(restarted).isTrue();
        assertThat(scheduler.isRunning()).isTrue();

        // Wait for more ticks
        awaitTicks(tickCountAfterFirstRun + 1);
    }

    @Test
    @DisplayName("Close should stop scheduler and shutdown executor")
    void closeShouldStopAndShutdown() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10);
        scheduler.start();
        awaitTicks(1);

        scheduler.close();

        assertThat(scheduler.isRunning()).isFalse();
    }
}
