package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.shard.CompactionReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runs of an on-demand compaction keep to themselves.
 *
 * <p>One pool thread serves every run, and a run's outcome is published before its thread is
 * free for the next. That leaves a window in which a client who read "completed" and posted
 * again has a second run queued behind the first one's last steps - and each of the ways the
 * runner told runs apart went wrong in it: the first run's tidying up forgot the second, so it
 * could not be cancelled; a cancel of the queued second run cancelled its task, which then never
 * ran to say it had ended, so the status read "running" and every later start was refused until
 * restart. The last test is the shutdown half: a rewrite of the chunk index that nothing could
 * stop let drain give up and storage close underneath it.</p>
 *
 * <p>The window is held open with the runner's settle hook, which runs on the worker after a
 * run's outcome is published: deterministic, with no sleeps.</p>
 */
@DisplayName("On-demand compaction: one run at a time, each its own")
class CompactionRunnerRaceTest {

    private static final CompactionReport REPORT =
        new CompactionReport(1, 1, 0, 0, 0, 0, Duration.ZERO);

    private static final long WAIT_SECONDS = 10;

    /** Holds the worker of the first run to settle, until released. */
    private static final class HeldSettle implements Runnable {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void run() {
            if (calls.getAndIncrement() > 0) {
                return;
            }
            reached.countDown();
            try {
                if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("the test never released the settled run");
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + WAIT_SECONDS + "s");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("a run started as the last one settles can still be cancelled")
    void theNextRunKeepsItsIdentity() throws Exception {
        HeldSettle settle = new HeldSettle();
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicBoolean secondSawStop = new AtomicBoolean();
        AtomicBoolean secondInterrupted = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> {
            if (runs.getAndIncrement() == 0) {
                return REPORT;
            }
            secondStarted.countDown();
            await(stop);
            secondSawStop.set(true);
            secondInterrupted.set(Thread.currentThread().isInterrupted());
            return REPORT;
        }, null, settle)) {
            runner.start(false);
            assertThat(settle.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_COMPLETED);

            // The client read "completed" and asked again, while the first run's worker is
            // still on its way back to the pool.
            assertThat(runner.start(true)).isNotNull();
            settle.release.countDown();
            assertThat(secondStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.cancel()).as("the second run is the one in progress").isTrue();
            await(() -> !runner.snapshot().isRunning());

            assertThat(secondSawStop).as("the stop reached the run it was meant for").isTrue();
            assertThat(secondInterrupted).as("and reached it without an interrupt").isFalse();
            assertThat(settle.interrupted).isFalse();
            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_CANCELLED);
            assertThat(runner.snapshot().force()).isTrue();
        }
    }

    @Test
    @DisplayName("a run cancelled before it starts still ends, and the next can start")
    void aQueuedRunCancelledStillEnds() throws Exception {
        HeldSettle settle = new HeldSettle();
        AtomicInteger compactions = new AtomicInteger();
        AtomicInteger lexical = new AtomicInteger();
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> {
            compactions.incrementAndGet();
            return REPORT;
        }, (force, stop) -> {
            lexical.incrementAndGet();
            return new CompactionResult(true, 1, 1, 0, "rewritten");
        }, settle)) {
            runner.start(false);
            assertThat(settle.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.start(true)).as("queued behind the first run's worker").isNotNull();
            assertThat(runner.cancel()).isTrue();
            settle.release.countDown();
            await(() -> !runner.snapshot().isRunning());

            CompactionRunner.Snapshot ended = runner.snapshot();
            assertThat(ended.state()).isEqualTo(CompactionRunner.STATE_CANCELLED);
            assertThat(ended.finishedAtMs()).isPositive();
            assertThat(ended.report()).as("it compacted nothing").isNull();
            assertThat(ended.lexical().reason())
                .isEqualTo(CompactionRunner.LEXICAL_SKIPPED_ON_CANCEL);
            assertThat(compactions).as("only the first run compacted").hasValue(1);
            assertThat(lexical).hasValue(1);
            assertThat(settle.interrupted)
                .as("cancelling the queued run never interrupts the thread finishing another")
                .isFalse();

            assertThat(runner.start(false)).as("nothing is left holding the claim").isNotNull();
            await(() -> CompactionRunner.STATE_COMPLETED.equals(runner.snapshot().state()));
        }
    }

    @Test
    @DisplayName("a cancel asks the chunk index rewrite to stop, without interrupting it")
    void aCancelStopsTheChunkIndexRewrite() throws Exception {
        CountDownLatch rewriting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> REPORT, (force, stop) -> {
            rewriting.countDown();
            await(stop);
            interrupted.set(Thread.currentThread().isInterrupted());
            return CompactionResult.stopped("asked to stop during the rewrite");
        })) {
            runner.start(true);
            assertThat(rewriting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.cancel()).isTrue();
            await(() -> !runner.snapshot().isRunning());

            assertThat(interrupted).isFalse();
            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_CANCELLED);
            assertThat(runner.snapshot().lexical().performed()).isFalse();
            assertThat(runner.snapshot().lexical().reason()).startsWith("stopped:");
        }
    }

    @Test
    @DisplayName("drain stops a chunk index rewrite rather than outwaiting it")
    void drainStopsTheChunkIndexRewrite() throws Exception {
        CountDownLatch rewriting = new CountDownLatch(1);
        AtomicBoolean stoppedCleanly = new AtomicBoolean();
        CompactionRunner runner = new CompactionRunner((force, stop) -> REPORT, (force, stop) -> {
            rewriting.countDown();
            // Stands in for a rewrite of every segment, which outlasts any shutdown budget:
            // it ends only when asked to.
            await(stop);
            stoppedCleanly.set(true);
            return CompactionResult.stopped("asked to stop during the rewrite");
        });
        runner.start(true);
        assertThat(rewriting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(runner.drain().clean())
            .as("nothing is left reading storage when drain says so")
            .isTrue();
        assertThat(stoppedCleanly).isTrue();
        assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_CANCELLED);
    }

    /**
     * A chunk index rewrite inside a step it cannot leave - one that ignores the stop request
     * until released - so a drain has to report it rather than wait it out.
     */
    private static final class StuckRewrite implements CompactionRunner.LexicalCompactor {
        final CountDownLatch rewriting = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final boolean readsStorage;

        StuckRewrite(boolean readsStorage) {
            this.readsStorage = readsStorage;
        }

        @Override
        public CompactionResult compact(boolean force, BooleanSupplier stopRequested) {
            rewriting.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompactionResult.stopped("released");
        }

        @Override
        public boolean readsStorage() {
            return readsStorage;
        }
    }

    @Test
    @DisplayName("a rewrite that reads storage and outlives the drain holds storage and the index")
    void aStuckRewriteThatReadsStorageHoldsBoth() throws Exception {
        StuckRewrite rewrite = new StuckRewrite(true);
        CompactionRunner runner = new CompactionRunner((force, stop) -> REPORT, rewrite, () -> { },
            Duration.ofMillis(1), Duration.ofMillis(1));
        runner.start(true);
        assertThat(rewrite.rewriting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(runner.drain()).isEqualTo(new CompactionRunner.Drained(false, false));
        } finally {
            rewrite.release.countDown();
        }
    }

    @Test
    @DisplayName("a rewrite from the index's own text that outlives the drain holds only the index")
    void aStuckRewriteThatReadsNoStorageHoldsOnlyTheIndex() throws Exception {
        // A Lucene force-merge: it never asks storage for text, so storage may close under it,
        // while the index's own files may not.
        StuckRewrite rewrite = new StuckRewrite(false);
        CompactionRunner runner = new CompactionRunner((force, stop) -> REPORT, rewrite, () -> { },
            Duration.ofMillis(1), Duration.ofMillis(1));
        runner.start(true);
        assertThat(rewrite.rewriting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(runner.drain()).isEqualTo(new CompactionRunner.Drained(true, false));
        } finally {
            rewrite.release.countDown();
        }
    }

    @Test
    @DisplayName("an idle runner drains clean")
    void anIdleRunnerDrainsClean() {
        CompactionRunner runner = new CompactionRunner((force, stop) -> REPORT,
            (force, stop) -> CompactionResult.declined("idle"));

        assertThat(runner.drain()).isEqualTo(CompactionRunner.Drained.CLEAN);
        assertThat(runner.drain().clean()).isTrue();
    }
}
