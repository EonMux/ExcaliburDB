package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphBuildService;

import com.eonmux.excalibur.graph.build.BuildCancelledException;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runner behind {@code POST /v1/graph/build}.
 *
 * <p>A build replays every staged fact, writes two Lucene indexes and embeds every entity, which
 * on a real corpus is minutes. Held on the handler thread that was a request nobody could stop
 * and every client timed out of while the work carried on unseen, so the route accepts and this
 * runs it - which makes what the runner remembers the whole contract, exactly as it is for the
 * compaction runner beside it.</p>
 *
 * <p>The state machine is what these tests pin: one build at a time, a second request refused
 * rather than raced, and a cancelled build reported as cancelled rather than as a failure - a
 * stop somebody asked for is not an outage.</p>
 */
@DisplayName("Graph build runner")
class GraphBuildRunnerTest {

    private static final NamespaceId ACME = NamespaceId.of("acme");
    private static final NamespaceId OTHER = NamespaceId.of("other");

    private static final GraphBuildService.BuildOutcome PUBLISHED =
        new GraphBuildService.BuildOutcome(true, 12, 5, 2, 5, 0, 12);

    /** A build that blocks until it is released, so the state in between can be read. */
    private static final class Gate implements GraphBuildRunner.Build {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<BooleanSupplier> signal = new AtomicReference<>();

        @Override
        public GraphBuildService.BuildOutcome build(NamespaceId namespace,
                                                    BooleanSupplier cancelRequested)
                throws IOException {
            signal.set(cancelRequested);
            entered.countDown();
            await(release);
            if (cancelRequested.getAsBoolean()) {
                throw new BuildCancelledException("stopped at the next staged fact");
            }
            return PUBLISHED;
        }

        void awaitEntry() {
            await(entered);
        }

        void release() {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the build");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static GraphBuildRunner.Snapshot awaitState(GraphBuildRunner runner,
                                                        NamespaceId namespace, String state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        GraphBuildRunner.Snapshot snapshot = runner.snapshot(namespace);
        while (!state.equals(snapshot.state()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            snapshot = runner.snapshot(namespace);
        }
        assertThat(snapshot.state()).isEqualTo(state);
        return snapshot;
    }

    @Nested
    @DisplayName("accepting work")
    class Accepting {

        @Test
        @DisplayName("a namespace that has never been built reports idle")
        void nothingYet() {
            try (GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> PUBLISHED)) {
                GraphBuildRunner.Snapshot snapshot = runner.snapshot(ACME);

                assertThat(snapshot.state()).isEqualTo(GraphBuildRunner.STATE_IDLE);
                assertThat(snapshot.namespace()).isEqualTo(ACME);
                assertThat(snapshot.outcome()).isNull();
            }
        }

        @Test
        @DisplayName("a build runs behind the response and its counts become the status")
        void oneBuildCompletes() {
            try (GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> PUBLISHED)) {
                GraphBuildRunner.Claim claim = runner.start(ACME);

                assertThat(claim.accepted()).isTrue();
                assertThat(claim.started().state()).isEqualTo(GraphBuildRunner.STATE_RUNNING);
                assertThat(claim.started().startedAtMs()).isPositive();

                GraphBuildRunner.Snapshot done =
                    awaitState(runner, ACME, GraphBuildRunner.STATE_COMPLETED);
                assertThat(done.outcome()).isEqualTo(PUBLISHED);
                assertThat(done.finishedAtMs()).isGreaterThanOrEqualTo(done.startedAtMs());
                assertThat(done.error()).isNull();
            }
        }

        @Test
        @DisplayName("a second build while one is running is refused, and says which namespace "
            + "is holding the node")
        void onlyOneAtATime() {
            Gate gate = new Gate();
            try (GraphBuildRunner runner = new GraphBuildRunner(gate)) {
                assertThat(runner.start(ACME).accepted()).isTrue();
                gate.awaitEntry();

                // Node-wide rather than per-namespace: a build saturates the node's I/O and,
                // where one is configured, its embedding endpoint. Two would each take twice
                // as long and neither caller would be told why.
                GraphBuildRunner.Claim refused = runner.start(OTHER);

                assertThat(refused.accepted()).isFalse();
                assertThat(refused.started()).isNull();
                assertThat(refused.blockedBy().namespace()).isEqualTo(ACME);
                assertThat(refused.blockedBy().isRunning()).isTrue();
                assertThat(runner.snapshot(OTHER).state())
                    .as("a refused request started nothing")
                    .isEqualTo(GraphBuildRunner.STATE_IDLE);

                gate.release();
                awaitState(runner, ACME, GraphBuildRunner.STATE_COMPLETED);
            }
        }

        @Test
        @DisplayName("each namespace is told about its own last build")
        void lastBuildIsPerNamespace() {
            try (GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> PUBLISHED)) {
                runner.start(ACME);
                awaitState(runner, ACME, GraphBuildRunner.STATE_COMPLETED);

                assertThat(runner.snapshot(OTHER).state())
                    .as("one tenant's build is not another tenant's status")
                    .isEqualTo(GraphBuildRunner.STATE_IDLE);
                assertThat(runner.snapshot(ACME).namespace()).isEqualTo(ACME);
            }
        }

        @Test
        @DisplayName("a build that throws is recorded as failed, with the reason")
        void failureIsRecorded() {
            try (GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> {
                throw new UncheckedIOException(new IOException("not a directory: /data/acme"));
            })) {
                runner.start(ACME);

                GraphBuildRunner.Snapshot failed =
                    awaitState(runner, ACME, GraphBuildRunner.STATE_FAILED);
                assertThat(failed.error()).contains("not a directory");
                assertThat(failed.outcome()).isNull();
            }
        }

        @Test
        @DisplayName("a failed build does not hold the node against the next one")
        void failureReleasesTheClaim() {
            AtomicReference<Boolean> fail = new AtomicReference<>(Boolean.TRUE);
            try (GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> {
                if (fail.get()) {
                    throw new IOException("the device is full");
                }
                return PUBLISHED;
            })) {
                runner.start(ACME);
                awaitState(runner, ACME, GraphBuildRunner.STATE_FAILED);

                fail.set(Boolean.FALSE);
                assertThat(runner.start(ACME).accepted()).isTrue();
                awaitState(runner, ACME, GraphBuildRunner.STATE_COMPLETED);
            }
        }

        @Test
        @DisplayName("a null build is refused at construction")
        void nullBuildIsRefused() {
            assertThatThrownBy(() -> new GraphBuildRunner((GraphBuildRunner.Build) null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("stopping one")
    class Stopping {

        @Test
        @DisplayName("a running build is asked to stop and reports itself cancelled")
        void cancelStops() {
            Gate gate = new Gate();
            try (GraphBuildRunner runner = new GraphBuildRunner(gate)) {
                runner.start(ACME);
                gate.awaitEntry();

                assertThat(runner.cancel(ACME)).isTrue();
                gate.release();

                GraphBuildRunner.Snapshot stopped =
                    awaitState(runner, ACME, GraphBuildRunner.STATE_CANCELLED);
                assertThat(stopped.outcome())
                    .as("a build publishes by a rename or not at all, so a stopped one "
                        + "published nothing")
                    .isNull();
                assertThat(stopped.finishedAtMs()).isPositive();
            }
        }

        @Test
        @DisplayName("the build sees the request through the signal it was given")
        void theSignalReachesTheBuild() {
            Gate gate = new Gate();
            try (GraphBuildRunner runner = new GraphBuildRunner(gate)) {
                runner.start(ACME);
                gate.awaitEntry();

                assertThat(gate.signal.get().getAsBoolean()).isFalse();
                runner.cancel(ACME);

                assertThat(gate.signal.get().getAsBoolean())
                    .as("the running build reads this between staged facts")
                    .isTrue();
                gate.release();
                awaitState(runner, ACME, GraphBuildRunner.STATE_CANCELLED);
            }
        }

        @Test
        @DisplayName("cancelling when nothing is running is answered, not refused")
        void cancellingNothing() {
            try (GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> PUBLISHED)) {
                assertThat(runner.cancel(ACME)).isFalse();
            }
        }

        @Test
        @DisplayName("another namespace's build is not cancelled by this namespace's request")
        void cancellationIsScopedToTheNamespace() {
            Gate gate = new Gate();
            try (GraphBuildRunner runner = new GraphBuildRunner(gate)) {
                runner.start(ACME);
                gate.awaitEntry();

                assertThat(runner.cancel(OTHER))
                    .as("every graph route is scoped by the namespace header, and this one "
                        + "ends a tenant's build")
                    .isFalse();
                assertThat(gate.signal.get().getAsBoolean()).isFalse();

                gate.release();
                awaitState(runner, ACME, GraphBuildRunner.STATE_COMPLETED);
            }
        }

        @Test
        @DisplayName("the next build after a cancelled one is accepted")
        void cancellationReleasesTheClaim() {
            Gate gate = new Gate();
            try (GraphBuildRunner runner = new GraphBuildRunner(gate)) {
                runner.start(ACME);
                gate.awaitEntry();
                runner.cancel(ACME);
                gate.release();
                awaitState(runner, ACME, GraphBuildRunner.STATE_CANCELLED);

                assertThat(runner.start(ACME).accepted())
                    .as("a stopped build must not hold the node for the life of the process")
                    .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("shutdown")
    class Shutdown {

        @Test
        @DisplayName("a build in progress is asked to stop and waited for")
        void drainWaits() {
            Gate gate = new Gate();
            GraphBuildRunner runner = new GraphBuildRunner(gate);
            runner.start(ACME);
            gate.awaitEntry();

            Thread releaser = new Thread(() -> {
                await(gate.entered);
                gate.release();
            });
            releaser.start();

            assertThat(runner.drain())
                .as("closing the tenants under a build that is still mapping files is how a "
                    + "shutdown takes the process down instead")
                .isTrue();
            assertThat(gate.signal.get().getAsBoolean())
                .as("a drain asks the build to stop rather than only waiting for it")
                .isTrue();
        }

        @Test
        @DisplayName("a build started after shutdown is refused rather than lost")
        void startAfterClose() {
            GraphBuildRunner runner = new GraphBuildRunner((namespace, cancel) -> PUBLISHED);
            runner.close();

            assertThatThrownBy(() -> runner.start(ACME))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(runner.snapshot(ACME).state()).isEqualTo(GraphBuildRunner.STATE_FAILED);
        }
    }
}
