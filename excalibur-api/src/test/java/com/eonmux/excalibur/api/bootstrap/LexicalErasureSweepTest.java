package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That an erased chunk's deadline is a deadline on an idle node too.
 *
 * <p>{@code excalibur.ingest.lexical-max-latency-seconds} bounds how long an erased chunk may
 * stay on disk before cost stops being a consideration. The bound was only ever evaluated at the
 * end of an ingestion or reindex run, so a node that erased something and then went quiet kept
 * the chunk indefinitely - the promise held exactly as long as the node stayed busy.</p>
 */
@DisplayName("Lexical erasure sweep")
class LexicalErasureSweepTest {

    @Nested
    @DisplayName("Choosing how often to look")
    class Interval {

        @Test
        @DisplayName("checks well inside the bound, so it is met rather than approached")
        void checksInsideTheBound() {
            assertThat(LexicalErasureSweep.sweepIntervalMillis(3600)).isEqualTo(360_000L);
            assertThat(LexicalErasureSweep.sweepIntervalMillis(60)).isEqualTo(6_000L);
        }

        @Test
        @DisplayName("never spins, however short the configured bound")
        void neverSpins() {
            assertThat(LexicalErasureSweep.sweepIntervalMillis(1))
                .isGreaterThanOrEqualTo(LexicalErasureSweep.MIN_SWEEP_INTERVAL_MS);
            assertThat(LexicalErasureSweep.sweepIntervalMillis(0))
                .isGreaterThanOrEqualTo(LexicalErasureSweep.MIN_SWEEP_INTERVAL_MS);
        }
    }

    @Nested
    @DisplayName("Sweeping")
    class Sweeping {

        @Test
        @DisplayName("does not touch the index when nothing has been erased")
        void skipsWhenNothingIsOwed() throws Exception {
            RecordingIndex index = new RecordingIndex(0);

            try (LexicalErasureSweep sweep = sweepOver(index)) {
                assertThat(sweep.sweep()).isFalse();
            }

            assertThat(index.compactions()).isZero();
        }

        @Test
        @DisplayName("compacts once an obligation is outstanding")
        void compactsWhenSomethingIsOwed() throws Exception {
            RecordingIndex index = new RecordingIndex(1);

            try (LexicalErasureSweep sweep = sweepOver(index)) {
                assertThat(sweep.sweep()).isTrue();
            }

            assertThat(index.compactions()).isEqualTo(1);
        }

        /** An index owing nothing, holding one damaged segment and some outdated ones. */
        private static RecordingIndex holding(boolean rebuildable, int outdated) {
            return new RecordingIndex(new ChunkIndexStats("recording", 0, 3, 10, 0, 0,
                Optional.empty(), true, 0,
                java.util.List.of(new ChunkIndexStats.UnreadableSegment("seg-1", 4, rebuildable,
                    "post.dat fails its checksum")), outdated));
        }

        @Test
        @DisplayName("compacts a quiet node's damaged segment, which nothing else would rebuild")
        void compactsARebuildableSegment() throws Exception {
            RecordingIndex index = holding(true, 0);

            try (LexicalErasureSweep sweep = sweepOver(index)) {
                assertThat(sweep.sweep()).isTrue();
            }

            assertThat(index.compactions()).isEqualTo(1);
        }

        @Test
        @DisplayName("compacts a quiet node's outdated segments, so an upgrade finishes")
        void compactsOutdatedSegments() throws Exception {
            RecordingIndex index = new RecordingIndex(new ChunkIndexStats("recording", 0, 3, 10,
                0, 0, Optional.empty(), true, 0, java.util.List.of(), 2));

            try (LexicalErasureSweep sweep = sweepOver(index)) {
                assertThat(sweep.sweep()).isTrue();
            }

            assertThat(index.compactions()).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves alone a damaged segment no compaction can rebuild")
        void skipsAnUnrebuildableSegment() throws Exception {
            RecordingIndex index = holding(false, 0);

            try (LexicalErasureSweep sweep = sweepOver(index)) {
                assertThat(sweep.sweep()).isFalse();
            }

            assertThat(index.compactions()).isZero();
        }

        @Test
        @DisplayName("survives a failed compaction, so the next sweep still runs")
        void survivesAFailure() throws Exception {
            RecordingIndex index = new RecordingIndex(1);
            index.failNext();

            try (LexicalErasureSweep sweep = sweepOver(index)) {
                assertThat(sweep.sweep()).isFalse();
                assertThat(sweep.sweep()).isTrue();
            }

            assertThat(index.compactions()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Draining")
    class Draining {

        @Test
        @DisplayName("tells a compaction in flight to stop, without interrupting it")
        void signalsTheCompactionInFlight() throws Exception {
            StoppableIndex index = new StoppableIndex(true);
            LexicalErasureSweep sweep = new LexicalErasureSweep(index, key -> null, 0, 60_000);
            index.entered.await();

            assertThat(sweep.drain(Duration.ofSeconds(30))).as("the compaction acted on the stop and ended").isTrue();
            assertThat(index.sawStop.get()).isTrue();
            assertThat(index.interrupted.get())
                .as("an interrupt would close the channels the index shares with queries")
                .isFalse();
            assertThat(sweep.sweep()).as("nothing is started once draining").isFalse();
            assertThat(index.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("reports a compaction that outlives the wait, so storage is left open")
        void reportsACompactionStillRunning() throws Exception {
            StoppableIndex index = new StoppableIndex(false);
            LexicalErasureSweep sweep = new LexicalErasureSweep(index, key -> null, 0, 60_000);
            index.entered.await();
            Duration wait = Duration.ofMillis(300);

            long began = System.nanoTime();
            try {
                assertThat(sweep.drain(wait)).isFalse();
                assertThat(Duration.ofNanos(System.nanoTime() - began))
                    .as("the compaction was given all of the wait the drain was handed")
                    .isGreaterThanOrEqualTo(wait);
            } finally {
                index.release.countDown();
            }
            // An interrupt is delivered before a drain returns, so a compaction that got one
            // would have left its wait by now rather than waiting to be released.
            index.finished.await();
            assertThat(index.interrupted.get()).isFalse();
        }

        @Test
        @DisplayName("an idle sweep drains at once")
        void idleSweepDrains() {
            LexicalErasureSweep sweep = new LexicalErasureSweep(new RecordingIndex(0),
                key -> null, 60_000, 60_000);

            assertThat(sweep.drain(Duration.ofSeconds(30))).isTrue();
        }
    }

    @Test
    @DisplayName("refuses to be built without the pieces it needs")
    void refusesIncompleteConstruction() {
        assertThatThrownBy(() -> new LexicalErasureSweep(null, key -> null, 3600))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LexicalErasureSweep(new RecordingIndex(0), null, 3600))
            .isInstanceOf(NullPointerException.class);
    }

    private static LexicalErasureSweep sweepOver(ChunkIndex index) {
        return new LexicalErasureSweep(index, key -> null, 3600);
    }

    /**
     * A chunk index whose compaction holds until it is told to stop, or until released.
     *
     * <p>{@code honoursStop} decides which: an index that reads the signal at its boundaries, or
     * one inside a step it cannot leave - the case a drain has to report rather than wait out.</p>
     */
    private static final class StoppableIndex implements ChunkIndex {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished =
            new CountDownLatch(1);
        final AtomicBoolean sawStop =
            new AtomicBoolean();
        final AtomicBoolean interrupted =
            new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        private final boolean honoursStop;

        StoppableIndex(boolean honoursStop) {
            this.honoursStop = honoursStop;
        }

        @Override
        public CompactionResult compactIfDue(java.util.function.Function<String, String> textOf,
                                             java.util.function.BooleanSupplier stopRequested) {
            calls.incrementAndGet();
            entered.countDown();
            if (honoursStop) {
                while (!stopRequested.getAsBoolean()) {
                    Thread.onSpinWait();
                }
                sawStop.set(true);
                interrupted.set(Thread.currentThread().isInterrupted());
                return CompactionResult.stopped("asked to");
            }
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                finished.countDown();
            }
            return CompactionResult.declined("released");
        }

        @Override
        public CompactionResult compactIfDue(java.util.function.Function<String, String> textOf) {
            throw new AssertionError("the sweep must pass its stop signal");
        }

        @Override
        public ChunkIndexStats stats() {
            return new ChunkIndexStats("stoppable", 0, 0, 0, 0, 1,
                Optional.of(Instant.EPOCH), true);
        }

        @Override
        public void add(String chunkId, String text) {
        }

        @Override
        public void addDocument(java.util.Map<String, String> chunks) {
        }

        @Override
        public void refresh() {
        }

        @Override
        public int erase(java.util.Set<String> chunkIds) {
            return 0;
        }

        @Override
        public String chunkIdOf(long id) {
            return null;
        }

        @Override
        public long indexedCount() {
            return 0;
        }

        @Override
        public CompactionResult compactNow(java.util.function.Function<String, String> textOf) {
            return CompactionResult.declined("not used");
        }

        @Override
        public com.eonmux.excalibur.retrieval.rank.Ranker ranker(
                com.eonmux.excalibur.retrieval.expand.QueryExpander expander) {
            return null;
        }

        @Override
        public String engine() {
            return "stoppable";
        }

        @Override
        public void close() {
        }
    }

    /** A chunk index that counts compactions and can be told to fail one. */
    private static final class RecordingIndex implements ChunkIndex {

        private final ChunkIndexStats reported;
        private final AtomicInteger compactions = new AtomicInteger();
        private volatile boolean failNext;

        RecordingIndex(int outstanding) {
            this(new ChunkIndexStats("recording", 0, 0, 0, 0, outstanding,
                outstanding > 0 ? Optional.of(Instant.EPOCH) : Optional.empty(), false));
        }

        RecordingIndex(ChunkIndexStats reported) {
            this.reported = reported;
        }

        void failNext() {
            this.failNext = true;
        }

        int compactions() {
            return compactions.get();
        }

        @Override
        public CompactionResult compactIfDue(java.util.function.Function<String, String> textOf)
                throws IOException {
            compactions.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new IOException("disk went away");
            }
            return new CompactionResult(true, 1, 1, 1, "deadline reached");
        }

        @Override
        public ChunkIndexStats stats() {
            return reported;
        }

        @Override
        public void add(String chunkId, String text) {
        }

        @Override
        public void addDocument(java.util.Map<String, String> chunks) {
        }

        @Override
        public void refresh() {
        }

        @Override
        public int erase(java.util.Set<String> chunkIds) {
            return 0;
        }

        @Override
        public String chunkIdOf(long id) {
            return null;
        }

        @Override
        public long indexedCount() {
            return 0;
        }

        @Override
        public CompactionResult compactNow(java.util.function.Function<String, String> textOf) {
            return CompactionResult.declined("not used");
        }

        @Override
        public com.eonmux.excalibur.retrieval.rank.Ranker ranker(
                com.eonmux.excalibur.retrieval.expand.QueryExpander expander) {
            return null;
        }

        @Override
        public String engine() {
            return "recording";
        }

        @Override
        public void close() {
        }
    }
}
