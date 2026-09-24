package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the SystemHealth MBean's score moves on its own.
 *
 * <p>A monitor watching a node through JMX alone, which is what the MBeans exist for, must see
 * the score follow coverage without anybody polling {@code /health} over HTTP. The epoch tick
 * is the writer that guarantees it. Each test seeds the score with a value the tick would never
 * compute for its catalog, so a score that was not published cannot pass by coincidence.</p>
 */
@DisplayName("Epoch tick: the health score")
class EpochTickHealthScoreTest {

    private SegmentCatalog catalog;
    private VisibilityTracker tracker;
    private EpochTickScheduler scheduler;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        tracker = new VisibilityTracker(catalog);
        scheduler = new EpochTickScheduler(tracker);
        Metrics.setHealthScore(0.5);
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
        scheduler.close();
        catalog.close();
        Metrics.setHealthScore(0.5);
    }

    @Test
    @DisplayName("publishes the health score without anybody polling an endpoint")
    void publishesOnTick() throws InterruptedException {
        // A node holding nothing a query can read: the seeded 0.5 would report it as healthy.
        scheduler.start();
        awaitFirstTick();

        assertThat(Metrics.getHealthScore()).isEqualTo(Metrics.getEstimatedCoverage());
        assertThat(Metrics.getHealthScore()).isZero();
        assertThat(Metrics.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("moves the score back up as segments become visible")
    void followsCoverage() throws InterruptedException {
        addActiveSegment(1L);
        tracker.registerSegment(1L);

        scheduler.start();
        awaitFirstTick();

        assertThat(Metrics.getHealthScore()).isEqualTo(1.0);
        assertThat(Metrics.isHealthy()).isTrue();
    }

    /**
     * Waits for the scheduler's first tick, then stops it.
     *
     * <p>The tick counter moves before the score is published, so seeing it is not enough.
     * Stopping takes the tick lock, which waits out a tick in progress, so once it returns the
     * tick's publication is complete and no later tick can overwrite it.</p>
     */
    private void awaitFirstTick() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (scheduler.getTickCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(scheduler.getTickCount()).as("the scheduler ticked").isPositive();
        scheduler.stop();
    }

    private void addActiveSegment(long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment-" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }
}
