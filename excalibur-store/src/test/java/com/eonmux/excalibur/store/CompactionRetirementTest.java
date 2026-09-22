package com.eonmux.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a compacted segment stops being compactable.
 *
 * <p>A compaction takes the segment's write lock for the whole rewrite and re-checks under it
 * that the segment is still open, which is the only thing standing between one segment and two
 * compactors rewriting it one after the other. That check is only as good as the moment the
 * segment is closed: a compactor that was queued on the write lock while the winner ran takes
 * the lock the instant it is released, and anything the winner does after releasing it happens
 * too late to be seen. A second rewrite of an already-replaced segment moves its stale output
 * over the canonical segment name and deletes the write log of the replacement that is by then
 * serving reads, so every write accepted since the first swap is gone at the next start.</p>
 *
 * <p>The ordering is asserted directly rather than by racing two compactors for it: whether the
 * queued compactor or the winner's own close wins that race is up to the scheduler, and a test
 * that usually loses the race would pass over the defect it exists to catch.</p>
 */
@DisplayName("Retiring a compacted segment")
class CompactionRetirementTest {

    @TempDir
    Path tempDir;

    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("the compacted segment is retired before its write lock is released")
    void theCompactedSegmentIsRetiredUnderItsWriteLock() throws IOException {
        RetirementObservingSegment segment = openObservedSegment();
        CompactionWorker worker = new CompactionWorker(arena,
            (oldSegment, newSegment) -> CompactionOutcome.INSTALLED);

        assertThat(worker.compactNow(segment, true)).isTrue();

        assertThat(segment.closeCount()).as("the segment is retired exactly once").isEqualTo(1);
        assertThat(segment.closedUnderWriteLock())
            .as("a compactor queued on the write lock must find the segment already closed when "
                + "it is granted the lock, which it only does if the close happened under it")
            .isTrue();
    }

    @Test
    @DisplayName("a refused swap leaves the compacted segment open and discards the replacement")
    void aRefusedSwapKeepsTheSegmentTheRegistryStillRoutesTo() throws IOException {
        RetirementObservingSegment segment = openObservedSegment();
        AtomicReference<Segment> replacement = new AtomicReference<>();
        CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> {
            replacement.set(newSegment);
            return CompactionOutcome.REFUSED;
        });

        assertThat(worker.compactNow(segment, true))
            .as("nothing was installed, so nothing was compacted")
            .isFalse();

        assertThat(segment.isClosed())
            .as("the registry still routes to this segment, so closing it would fail every read "
                + "and write to its key range for the life of the process")
            .isFalse();
        assertThat(segment.get("keep".getBytes(StandardCharsets.UTF_8)))
            .as("the key range this segment holds is still readable")
            .isNotNull()
            .extracting(value -> value.toArray(ValueLayout.JAVA_BYTE))
            .isEqualTo("value".getBytes(StandardCharsets.UTF_8));
        assertThat(replacement.get().isClosed())
            .as("the replacement nothing routes to is discarded rather than left mapped")
            .isTrue();
    }

    @Test
    @DisplayName("a swap nothing routes to any more retires both segments")
    void anAbandonedSwapRetiresBothSegments() throws IOException {
        RetirementObservingSegment segment = openObservedSegment();
        AtomicReference<Segment> replacement = new AtomicReference<>();
        CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> {
            replacement.set(newSegment);
            return CompactionOutcome.ABANDONED;
        });

        assertThat(worker.compactNow(segment, true)).isFalse();

        assertThat(segment.isClosed())
            .as("nothing routes to the compacted segment, so keeping it open only leaks it")
            .isTrue();
        assertThat(replacement.get().isClosed())
            .as("nor does anything route to the replacement")
            .isTrue();
    }

    /**
     * Creates a segment holding one live entry and one tombstone, with a write log attached.
     *
     * @return The segment, open, at the canonical name compaction installs its output at
     * @throws IOException if the segment or its write log cannot be created
     */
    private RetirementObservingSegment openObservedSegment() throws IOException {
        RetirementObservingSegment segment = new RetirementObservingSegment(
            tempDir.resolve("segment_0.dat"), 1024 * 1024, arena);
        segment.attachWriteLog(new WriteLog(tempDir.resolve("segment_0.wal")));
        segment.put("keep".getBytes(StandardCharsets.UTF_8), "value".getBytes(StandardCharsets.UTF_8));
        segment.put("drop".getBytes(StandardCharsets.UTF_8), "value".getBytes(StandardCharsets.UTF_8));
        segment.delete("drop".getBytes(StandardCharsets.UTF_8));
        return segment;
    }

    /**
     * A segment that records whether its retirement held the lock that guards compaction.
     */
    private static final class RetirementObservingSegment extends Segment {

        private volatile boolean closedUnderWriteLock;
        private volatile int closeCount;

        RetirementObservingSegment(Path path, long capacityBytes, Arena arena) throws IOException {
            super(path, capacityBytes, arena);
        }

        @Override
        public void close() {
            closedUnderWriteLock =
                ((ReentrantReadWriteLock.WriteLock) writeLock()).isHeldByCurrentThread();
            closeCount++;
            super.close();
        }

        boolean closedUnderWriteLock() {
            return closedUnderWriteLock;
        }

        int closeCount() {
            return closeCount;
        }
    }
}
