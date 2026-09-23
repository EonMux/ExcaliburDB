package com.eonmux.excalibur.api.reindex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        /**
         * A rolling replacement that hangs - a source whose fetch never returns, say - holds its
         * claim for the life of the process, and every later request for that source is answered
         * 409 forever. An operator needs a way out that is not a restart.
         */
        @Test
        @DisplayName("asks a running reindex to stop and reports that it did")
        void cancelsARunningReindex() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            java.util.concurrent.atomic.AtomicBoolean asked =
                new java.util.concurrent.atomic.AtomicBoolean();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");
            lease.onCancel(() -> asked.set(true));

            assertThat(coordinator.cancel("docs")).isTrue();
            assertThat(asked).isTrue();
        }

        /**
         * The claim is not dropped here. Releasing it while the run is still winding down would
         * let a second rolling replacement start alongside the first, and two of them tombstone
         * the segments the other has just published - which is the data loss the claim exists to
         * prevent. The run releases its own claim when it actually stops.
         */
        @Test
        @DisplayName("leaves the claim held until the run itself releases it")
        void doesNotReleaseTheClaimItself() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");
            lease.onCancel(() -> { });

            coordinator.cancel("docs");

            assertThat(coordinator.isRunning("docs"))
                .as("a cancelled run is still running until it stops")
                .isTrue();
            assertThat(coordinator.tryAcquire("docs")).isNull();

            lease.release();
            assertThat(coordinator.isRunning("docs")).isFalse();
        }

        /**
         * The canceller can be reached from two sides - {@code cancel()} running one already
         * registered, and {@code onCancel} replaying a cancellation that arrived first. Both
         * firing for one cancellation runs a canceller twice, which is only harmless while
         * every canceller happens to be idempotent.
         */
        @Test
        @DisplayName("runs the canceller once for one cancellation, whichever side got there first")
        void runsTheCancellerOnce() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            java.util.concurrent.atomic.AtomicInteger runs =
                new java.util.concurrent.atomic.AtomicInteger();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");

            coordinator.cancel("docs");
            lease.onCancel(runs::incrementAndGet);
            coordinator.cancel("docs");

            assertThat(runs).hasValue(1);
        }

        @Test
        @DisplayName("reports nothing to cancel for a source that is not being reindexed")
        void nothingToCancel() {
            assertThat(new ReindexCoordinator().cancel("docs")).isFalse();
        }

        /**
         * A lease whose run was never handed a way to stop is still cancelled as far as the
         * caller is concerned - there is simply nothing to interrupt yet.
         */
        @Test
        @DisplayName("cancels a lease that registered no canceller")
        void cancelsALeaseWithNoCanceller() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            coordinator.tryAcquire("docs");

            assertThat(coordinator.cancel("docs")).isTrue();
        }

        @Test
        @DisplayName("a cancelled lease says so, so the run can stop at its next checkpoint")
        void leaseReportsCancellation() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");

            assertThat(lease.isCancelled()).isFalse();
            coordinator.cancel("docs");
            assertThat(lease.isCancelled()).isTrue();
        }
    }
    @Nested
    @DisplayName("stopping one source's reindex and waiting for it")
    class StoppingOneSource {

        @Test
        @DisplayName("a source nothing is reindexing has already stopped")
        void nothingRunning() throws Exception {
            assertThat(new ReindexCoordinator().cancelAndAwait("docs", 0)).isTrue();
        }

        /**
         * Deregistering a source - and erasing one - must not return while a reindex of it is
         * still writing: the run would put back documents the erasure had just removed. So the
         * stop is asked for and then waited on, which a bare {@code cancel} does not do.
         */
        @Test
        @DisplayName("asks the run to stop and returns once it has released its claim")
        void waitsForTheRunToEnd() throws Exception {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");
            CountDownLatch asked = new CountDownLatch(1);
            lease.onCancel(asked::countDown);
            java.util.concurrent.atomic.AtomicBoolean releasedFirst =
                new java.util.concurrent.atomic.AtomicBoolean();
            Thread run = Thread.ofVirtual().start(() -> {
                try {
                    if (asked.await(5, TimeUnit.SECONDS)) {
                        releasedFirst.set(true);
                        lease.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(coordinator.cancelAndAwait("docs", TimeUnit.SECONDS.toMillis(10)))
                .isTrue();
            assertThat(releasedFirst).as("it returned because the run ended").isTrue();
            assertThat(coordinator.isRunning("docs")).isFalse();
            run.join();
        }

        @Test
        @DisplayName("reports a run that does not stop within the bound")
        void reportsARunThatOutlivesTheBound() throws Exception {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");

            assertThat(coordinator.cancelAndAwait("docs", 20)).isFalse();
            assertThat(lease.isCancelled()).isTrue();
            assertThat(coordinator.isRunning("docs"))
                .as("the claim is still the run's to release")
                .isTrue();
        }

        @Test
        @DisplayName("waits on the one source, not on every reindex in flight")
        void ignoresOtherSources() throws Exception {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            ReindexCoordinator.Lease other = coordinator.tryAcquire("other");

            assertThat(coordinator.cancelAndAwait("docs", 0)).isTrue();
            assertThat(other.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("draining for shutdown")
    class Draining {

        @Test
        @DisplayName("refuses every claim once it stops accepting, and says why")
        void refusesClaimsAfterStopAccepting() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            assertThat(coordinator.isAccepting()).isTrue();

            coordinator.stopAccepting();

            assertThat(coordinator.isAccepting()).isFalse();
            assertThat(coordinator.tryAcquire("docs"))
                .as("a reindex starting after the drain looked would write into closed storage")
                .isNull();
            assertThat(coordinator.inFlightCount()).isZero();
        }

        @Test
        @DisplayName("asks every run in flight to stop, through the canceller each registered")
        void cancelAllAsksEveryRun() {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            AtomicInteger asked = new AtomicInteger();
            coordinator.tryAcquire("a").onCancel(asked::incrementAndGet);
            ReindexCoordinator.Lease b = coordinator.tryAcquire("b");
            b.onCancel(asked::incrementAndGet);

            assertThat(coordinator.cancelAll()).isEqualTo(2);
            assertThat(asked).hasValue(2);
            assertThat(b.isCancelled()).isTrue();
            assertThat(coordinator.inFlightCount())
                .as("asking a run to stop is not the run ending")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("an idle coordinator is idle at once")
        void idleAtOnce() throws Exception {
            assertThat(new ReindexCoordinator().awaitIdle(0)).isTrue();
        }

        @Test
        @DisplayName("reports a run that outlives the wait")
        void reportsARunThatOutlivesTheWait() throws Exception {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            coordinator.tryAcquire("docs");

            assertThat(coordinator.awaitIdle(20)).isFalse();
        }

        @Test
        @DisplayName("wakes as soon as the last run releases its claim")
        void wakesOnTheLastRelease() throws Exception {
            ReindexCoordinator coordinator = new ReindexCoordinator();
            ReindexCoordinator.Lease lease = coordinator.tryAcquire("docs");
            CountDownLatch waiting = new CountDownLatch(1);
            Thread releaser = new Thread(() -> {
                try {
                    waiting.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lease.release();
            });
            releaser.start();

            waiting.countDown();
            assertThat(coordinator.awaitIdle(TimeUnit.SECONDS.toMillis(10))).isTrue();
            releaser.join();
        }
    }
}
