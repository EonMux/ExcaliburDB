package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test
    @DisplayName("shutdown waits out the whole of its wait for a sweep in progress, and a later "
        + "one waits again")
    void shutdownWaitsAsLongAsItIsGiven() throws InterruptedException {
        CountDownLatch sweeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SegmentSyncScheduler scheduler = new SegmentSyncScheduler(mock(StorageEngine.class),
            mock(IndexOrchestrator.class), () -> {
                sweeping.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, SegmentSyncScheduler.MIN_INTERVAL_MS);
        scheduler.start();
        assertThat(sweeping.await(30, TimeUnit.SECONDS)).as("a sweep started").isTrue();
        scheduler.stop();
        Duration wait = Duration.ofMillis(300);

        long began = System.nanoTime();
        try {
            assertThat(scheduler.shutdown(wait))
                .as("a sweep still registering index structures is reported")
                .isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - began))
                .as("the sweep was given all of the wait shutdown was handed")
                .isGreaterThanOrEqualTo(wait);
        } finally {
            release.countDown();
        }
        assertThat(scheduler.shutdown(Duration.ofSeconds(10)))
            .as("given long enough, a later shutdown sees the sweep end")
            .isTrue();
    }

    @Test
    @DisplayName("a scheduler that never started has nothing to wait for")
    void neverStartedShutsDownAtOnce() {
        assertThat(schedulerAt(SegmentSyncScheduler.MIN_INTERVAL_MS).shutdown(Duration.ZERO))
            .isTrue();
    }
}
