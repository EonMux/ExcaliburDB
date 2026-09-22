package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
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

    /** A chunk index that counts compactions and can be told to fail one. */
    private static final class RecordingIndex implements ChunkIndex {

        private final int outstanding;
        private final AtomicInteger compactions = new AtomicInteger();
        private volatile boolean failNext;

        RecordingIndex(int outstanding) {
            this.outstanding = outstanding;
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
            return new ChunkIndexStats("recording", 0, 0, 0, 0, outstanding,
                outstanding > 0 ? Optional.of(Instant.EPOCH) : Optional.empty(), false);
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
