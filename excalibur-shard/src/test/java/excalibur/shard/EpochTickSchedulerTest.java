package excalibur.shard;

import io.excalibur.store.SegmentCatalog;
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
 *   <li>tickNow() provides immediate epoch advancement</li>
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

        // Wait for 3 ticks at 10ms interval (35ms with buffer)
        Thread.sleep(35);

        long epochAfter = tracker.currentEpoch();

        // Should have at least 2 ticks (could be 3+ due to timing)
        assertThat(epochAfter).isGreaterThan(epochBefore);
        assertThat(scheduler.getTickCount()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("tickNow should advance epoch immediately")
    void tickNowShouldAdvanceImmediately() {
        scheduler = new EpochTickScheduler(tracker);
        long epochBefore = tracker.currentEpoch();

        long newEpoch = scheduler.tickNow();

        assertThat(newEpoch).isGreaterThan(epochBefore);
        assertThat(scheduler.getTickCount()).isEqualTo(1L);
        assertThat(scheduler.getLastTickTimestamp()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("No ticks should occur after stop")
    void noTicksAfterStop() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10); // Use 20ms interval
        scheduler.start();
        Thread.sleep(25); // Let some ticks happen
        scheduler.stop();

        long tickCountAfterStop = scheduler.getTickCount();
        long epochAfterStop = tracker.currentEpoch();

        // Wait another period
        Thread.sleep(50);

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

        // Wait for 5 ticks at 10ms interval (50ms with buffer)
        Thread.sleep(50);

        long epochAfter = tracker.currentEpoch();

        // Should have at least 4 ticks with 20ms interval
        assertThat(epochAfter).isGreaterThan(epochBefore);
        assertThat(scheduler.getTickCount()).isGreaterThanOrEqualTo(4L);
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
        Thread.sleep(50);

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
        Thread.sleep(50);
        scheduler.stop();

        long tickCountAfterFirstRun = scheduler.getTickCount();
        assertThat(tickCountAfterFirstRun).isGreaterThanOrEqualTo(1L);

        // Restart
        boolean restarted = scheduler.start();
        assertThat(restarted).isTrue();
        assertThat(scheduler.isRunning()).isTrue();

        // Wait for more ticks
        Thread.sleep(50);

        // Should have more ticks now
        assertThat(scheduler.getTickCount()).isGreaterThan(tickCountAfterFirstRun);
    }

    @Test
    @DisplayName("Close should stop scheduler and shutdown executor")
    void closeShouldStopAndShutdown() throws InterruptedException {
        scheduler = new EpochTickScheduler(tracker, 10);
        scheduler.start();
        Thread.sleep(15);

        scheduler.close();

        assertThat(scheduler.isRunning()).isFalse();
    }
}
