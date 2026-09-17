package excalibur.shard;

import io.excalibur.store.StorageEngine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The sweep's own floor on how often it may run.
 *
 * <p>The floor lives here rather than in the configuration layer because this is the thing
 * that pays for it: a sweep is quadratic in registered segments and runs on one thread beside
 * the workers it corrects. Refusing a value below it, rather than clamping, is the point - an
 * operator who asks for a 10ms sweep has misunderstood what the sweep is for, and silently
 * giving them 250ms hides that.</p>
 */
@DisplayName("Segment sync scheduler bounds")
class SegmentSyncSchedulerTest {

    private static SegmentSyncScheduler schedulerAt(long intervalMs) {
        return new SegmentSyncScheduler(mock(StorageEngine.class), mock(IndexOrchestrator.class),
            () -> { }, intervalMs);
    }

    @Test
    @DisplayName("a period below the floor is refused, not clamped")
    void aPeriodBelowTheFloorIsRefused() {
        assertThatThrownBy(() -> schedulerAt(SegmentSyncScheduler.MIN_INTERVAL_MS - 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(String.valueOf(SegmentSyncScheduler.MIN_INTERVAL_MS));
    }

    @Test
    @DisplayName("the floor itself is accepted")
    void theFloorItselfIsAccepted() {
        assertThatCode(() -> schedulerAt(SegmentSyncScheduler.MIN_INTERVAL_MS))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the stock period is above the floor, so the default is always legal")
    void theStockPeriodIsAboveTheFloor() {
        assertThat(IndexOrchestrator.DEFAULT_SEGMENT_SYNC_INTERVAL_MS)
            .isGreaterThanOrEqualTo(SegmentSyncScheduler.MIN_INTERVAL_MS);
    }
}
