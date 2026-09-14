package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thresholds a compaction worker is built with are the ones it applies.
 *
 * <p>The worker used to hold its policy in compile-time constants, so a deployment that wanted
 * compaction to start earlier or later had nowhere to say so. Now that the policy arrives as
 * construction arguments, the question worth asking of it is not whether the getters echo the
 * numbers back but whether the numbers change what the worker picks - and whether a caller can
 * still insist on a compaction the policy would decline.</p>
 */
@DisplayName("Compaction policy")
class CompactionPolicyTest {

    @TempDir
    Path tempDir;

    private Arena arena;

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    /**
     * Builds a segment holding ten entries, of which {@code tombstones} are deleted.
     *
     * @param name       File name for the segment
     * @param tombstones How many of the ten entries to delete
     * @return The segment, left open
     * @throws IOException if the segment cannot be created
     */
    private Segment segmentWithTombstones(String name, int tombstones) throws IOException {
        Segment segment = new Segment(tempDir.resolve(name), 1024 * 1024, arena);
        for (int i = 0; i < 10; i++) {
            segment.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
        }
        for (int i = 0; i < tombstones; i++) {
            segment.delete(("key-" + i).getBytes());
        }
        return segment;
    }

    @Test
    @DisplayName("the configured ratio decides which segments are worth compacting")
    void theConfiguredRatioDecidesTheCandidates() throws IOException {
        arena = Arena.ofShared();
        CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> CompactionOutcome.INSTALLED,
            5000L, 0.40, 32L * 1024 * 1024, 4);

        Segment below = segmentWithTombstones("segment_0.dat", 3);
        Segment above = segmentWithTombstones("segment_1.dat", 5);

        assertThat(worker.getTombstoneRatioThreshold()).isEqualTo(0.40);
        assertThat(worker.isCompactionWorthwhile(below)).isFalse();
        assertThat(worker.isCompactionWorthwhile(above)).isTrue();
    }

    @Test
    @DisplayName("an unforced manual compaction leaves a segment the policy declines alone")
    void anUnforcedCompactionRespectsThePolicy() throws IOException {
        arena = Arena.ofShared();
        AtomicReference<Segment> swapped = new AtomicReference<>();
        CompactionWorker worker = new CompactionWorker(arena,
            (oldSegment, newSegment) -> {
                swapped.set(newSegment);
                return CompactionOutcome.INSTALLED;
            },
            5000L, 0.40, 32L * 1024 * 1024, 4);

        Segment below = segmentWithTombstones("segment_0.dat", 3);

        assertThat(worker.compactNow(below, false)).isFalse();
        assertThat(swapped.get()).isNull();
        assertThat(below.isClosed()).isFalse();
    }

    @Test
    @DisplayName("a forced manual compaction compacts it anyway")
    void aForcedCompactionIgnoresThePolicy() throws IOException {
        arena = Arena.ofShared();
        AtomicReference<Segment> swapped = new AtomicReference<>();
        CompactionWorker worker = new CompactionWorker(arena,
            (oldSegment, newSegment) -> {
                swapped.set(newSegment);
                return CompactionOutcome.INSTALLED;
            },
            5000L, 0.40, 32L * 1024 * 1024, 4);

        Segment below = segmentWithTombstones("segment_0.dat", 3);

        assertThat(worker.compactNow(below, true)).isTrue();
        assertThat(swapped.get()).isNotNull();
        assertThat(swapped.get().get("key-9".getBytes())).isNotNull();
    }
}
