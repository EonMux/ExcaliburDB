package com.eonmux.excalibur.vector.hnsw;

import java.io.IOException;
import java.nio.file.Path;

import com.eonmux.excalibur.vector.distance.L2Distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The integration checkpoint across a write-log replacement.
 *
 * <p>Compaction discards a segment's write log and starts an empty one. The checkpoint is a byte
 * offset into the discarded file, so it survives as a number that is meaningless against the new
 * log and — because logs are rewritten smaller — usually larger than it. Every subsequent append
 * then falls below the checkpoint and is skipped, which looks exactly like an idle segment.
 */
class CheckpointRebaseTest {

    private static HnswGraph graph(Path dir) throws IOException {
        return new HnswGraph(dir.resolve("test.hnsw"),
            HnswConfig.create(8), L2Distance.getInstance(), 1000);
    }

    @Test
    @DisplayName("the ordinary setter refuses to go backwards")
    void ordinarySetterIsMonotone(@TempDir Path dir) throws IOException {
        try (HnswGraph g = graph(dir)) {
            g.setLastIntegratedOffset(5000);
            g.setLastIntegratedOffset(1000);

            assertThat(g.getLastIntegratedOffset())
                .as("lowering it while the log is unchanged would re-integrate existing entries")
                .isEqualTo(5000);
            assertThat(g.compareAndSetLastIntegratedOffset(1000)).isFalse();
        }
    }

    @Test
    @DisplayName("the reset does go backwards, which is the whole point")
    void resetIsNotMonotone(@TempDir Path dir) throws IOException {
        try (HnswGraph g = graph(dir)) {
            g.setLastIntegratedOffset(5000);
            g.resetLastIntegratedOffset(0);

            assertThat(g.getLastIntegratedOffset()).isZero();
        }
    }

    @Test
    @DisplayName("after a reset, appends above the new origin are integrable again")
    void appendsBecomeIntegrableAgain(@TempDir Path dir) throws IOException {
        try (HnswGraph g = graph(dir)) {
            // A checkpoint carried over from a log that compaction discarded.
            g.setLastIntegratedOffset(5000);
            TailOffsetTracker tracker = new TailOffsetTracker(g);

            // The replacement log starts empty; its head is far below the stale checkpoint, so
            // nothing appended to it registers as tail work.
            long replacementHead = 0;
            assertThat(tracker.hasTailEntries(200))
                .as("this is the bug: real appends look like nothing to integrate")
                .isFalse();

            tracker.resetTo(replacementHead);

            assertThat(tracker.getLastIntegratedOffset()).isEqualTo(replacementHead);
            assertThat(tracker.hasTailEntries(200))
                .as("the same appends are now visible to the integration worker")
                .isTrue();
        }
    }

    @Test
    @DisplayName("the reset survives reopening the graph")
    void resetIsPersisted(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("test.hnsw");
        try (HnswGraph g = new HnswGraph(path, HnswConfig.create(8),
                L2Distance.getInstance(), 1000)) {
            g.setLastIntegratedOffset(5000);
            g.resetLastIntegratedOffset(0);
        }
        try (HnswGraph reopened = new HnswGraph(path, HnswConfig.create(8),
                L2Distance.getInstance(), 1000)) {
            assertThat(reopened.getLastIntegratedOffset())
                .as("restoring the old value would point at a log that no longer exists")
                .isZero();
        }
    }

    @Test
    @DisplayName("a negative checkpoint is refused")
    void negativeRefused(@TempDir Path dir) throws IOException {
        try (HnswGraph g = graph(dir)) {
            assertThatThrownBy(() -> g.resetLastIntegratedOffset(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
