package excalibur.shard;

import io.excalibur.metrics.Metrics;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the SystemHealth MBean's score moves on its own.
 *
 * <p>{@code Metrics.setHealthScore} had two callers: the bootstrap, which sets 0.5 once, and
 * the HTTP health endpoint. So the score only moved when somebody polled {@code /health} over
 * HTTP, and a monitor watching the node through JMX alone - which is what the seven MBeans
 * exist for - read 0.5 for ever, through a total loss of the corpus included. Coverage was
 * already refreshed on the epoch tick, which is where a background signal belongs.</p>
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
    void publishesOnTick() {
        // A node holding nothing a query can read. The score sat at the 0.5 the bootstrap
        // wrote and never moved, so the MBean reported this node as healthy.
        scheduler.tickNow();

        assertThat(Metrics.getHealthScore()).isEqualTo(Metrics.getEstimatedCoverage());
        assertThat(Metrics.getHealthScore()).isZero();
        assertThat(Metrics.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("moves the score back up as segments become visible")
    void followsCoverage() {
        addActiveSegment(1L);
        tracker.registerSegment(1L);

        scheduler.tickNow();

        assertThat(Metrics.getHealthScore()).isEqualTo(1.0);
        assertThat(Metrics.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("publishes it on the periodic tick too, not only on an immediate one")
    void publishesOnThePeriodicTick() throws InterruptedException {
        addActiveSegment(1L);
        tracker.registerSegment(1L);
        scheduler.start();

        long deadline = System.currentTimeMillis() + 5_000;
        while (Metrics.getHealthScore() != 1.0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(Metrics.getHealthScore()).isEqualTo(1.0);
    }

    private void addActiveSegment(long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment-" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }
}
