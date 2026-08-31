package excalibur.api.reindex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the reindex mutual-exclusion primitive.
 */
@DisplayName("ReindexCoordinator")
class ReindexCoordinatorTest {

    @Test
    @DisplayName("grants one lease per source at a time")
    void oneLeasePerSource() {
        ReindexCoordinator coordinator = new ReindexCoordinator();

        ReindexCoordinator.Lease first = coordinator.tryAcquire("docs");
        assertThat(first).isNotNull();
        assertThat(coordinator.tryAcquire("docs")).isNull();
        assertThat(coordinator.isRunning("docs")).isTrue();

        first.release();
        assertThat(coordinator.isRunning("docs")).isFalse();
        assertThat(coordinator.tryAcquire("docs")).isNotNull();
    }

    @Test
    @DisplayName("keeps different sources independent")
    void sourcesAreIndependent() {
        ReindexCoordinator coordinator = new ReindexCoordinator();

        assertThat(coordinator.tryAcquire("a")).isNotNull();
        assertThat(coordinator.tryAcquire("b")).isNotNull();
        assertThat(coordinator.inFlightCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("releases idempotently")
    void releaseIsIdempotent() {
        ReindexCoordinator coordinator = new ReindexCoordinator();

        ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");
        lease.release();
        ReindexCoordinator.Lease second = coordinator.tryAcquire("docs");
        lease.release();

        // The stale release must not drop the claim the second acquirer holds.
        assertThat(coordinator.isRunning("docs")).isTrue();
        second.release();
    }

    @Test
    @DisplayName("lets exactly one of many concurrent acquirers through")
    void concurrentAcquireIsAtomic() throws InterruptedException {
        ReindexCoordinator coordinator = new ReindexCoordinator();
        int threads = 32;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (coordinator.tryAcquire("docs") != null) {
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        // Two rolling replacements of one source tombstone each other's segments, so
        // exactly one caller may proceed.
        assertThat(acquired).hasValue(1);
    }
}
