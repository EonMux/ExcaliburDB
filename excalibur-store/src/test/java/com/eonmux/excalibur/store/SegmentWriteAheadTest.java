package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.LogEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A mutation the log cannot record must not reach the segment.
 *
 * <p>The log is what makes an acknowledged write survive a restart, and it is also a live read
 * source for tail-scan queries. A segment that applies a change the log refused is therefore
 * wrong twice over: the caller is told the write failed while a reader can still see it, and a
 * crash loses it with nothing to replay. The log filling is not hypothetical - it is bounded,
 * and reclaimed only by compaction.</p>
 */
@DisplayName("The write-ahead log is written ahead")
class SegmentWriteAheadTest {

    @TempDir
    Path tempDir;

    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    /** A log that can be told to stop accepting, standing in for one that is full. */
    private static final class FlakyLog extends WriteLog {

        private boolean refusing;

        FlakyLog(Path path) throws IOException {
            super(path);
        }

        void refuse() {
            refusing = true;
        }

        private void check() throws IOException {
            if (refusing) {
                throw new IOException("write-ahead log is full");
            }
        }

        @Override
        public void reserve(byte opcode, byte[] key, byte[] value) throws IOException {
            check();
            super.reserve(opcode, key, value);
        }

        @Override
        public long append(byte opcode, byte[] key, byte[] value) throws IOException {
            check();
            return super.append(opcode, key, value);
        }

        @Override
        public long appendPut(byte[] key, byte[] value) throws IOException {
            check();
            return super.appendPut(key, value);
        }

        @Override
        public long appendDelete(byte[] key) throws IOException {
            check();
            return super.appendDelete(key);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String textOf(MemorySegment value) {
        return value == null ? null : new String(value.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
            StandardCharsets.UTF_8);
    }

    private Segment segmentWith(FlakyLog log) throws IOException {
        Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1024 * 1024, arena);
        segment.attachWriteLog(log);
        return segment;
    }

    @Test
    @DisplayName("an insert the log cannot record is not applied")
    void anUnloggableInsertIsNotApplied() throws IOException {
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            log.refuse();

            assertThatThrownBy(() -> segment.put(bytes("k"), bytes("v")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(segment.get(bytes("k")))
                .as("the caller was told the write failed; a reader must not find it anyway")
                .isNull();
        }
    }

    @Test
    @DisplayName("an overwrite the log cannot record leaves the previous value in place")
    void anUnloggableOverwriteIsNotApplied() throws IOException {
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            segment.put(bytes("k"), bytes("first"));
            log.refuse();

            assertThatThrownBy(() -> segment.put(bytes("k"), bytes("second-and-longer")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(textOf(segment.get(bytes("k"))))
                .as("a refused overwrite must not half-replace the value that is still logged")
                .isEqualTo("first");
        }
    }

    @Test
    @DisplayName("a delete the log cannot record does not remove the entry")
    void anUnloggableDeleteIsNotApplied() throws IOException {
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            segment.put(bytes("k"), bytes("v"));
            log.refuse();

            assertThatThrownBy(() -> segment.delete(bytes("k")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(textOf(segment.get(bytes("k"))))
                .as("a delete nothing recorded would come back on restart; it must not apply now")
                .isEqualTo("v");
        }
    }

    @Test
    @DisplayName("a key the log will not carry is refused before the segment changes")
    void aKeyTheLogCannotCarryIsRefusedFirst() throws IOException {
        // The segment admits an empty key; a log record cannot carry one. Whichever of the two
        // is right, they must agree before the segment is touched - a write that fails on the
        // append has already changed the segment the caller was told it did not.
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            byte[] empty = new byte[0];

            assertThatThrownBy(() -> segment.put(empty, bytes("v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

            assertThat(log.entriesAppended())
                .as("the caller was told the write failed; the log must not say otherwise")
                .isZero();
            assertThat(segment.liveEntryCount())
                .as("an entry no iterator can read leaves the segment uncompactable")
                .isZero();
        }
    }

    @Test
    @DisplayName("a delete the log cannot carry does not tombstone the entry")
    void aDeleteTheLogCannotCarryIsRefusedFirst() throws IOException {
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            byte[] empty = new byte[0];

            assertThatThrownBy(() -> segment.delete(empty))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(log.entriesAppended()).isZero();
        }
    }

    @Test
    @DisplayName("a write the segment refuses never reaches the log")
    void aRefusedWriteIsNotLogged() throws IOException {
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            // One large entry fits the data region; a second one cannot, so the segment
            // refuses it on its own terms with the log perfectly able to take it.
            segment.put(bytes("first"), new byte[700 * 1024]);
            long before = log.entriesAppended();

            assertThatThrownBy(() -> segment.put(bytes("second"), new byte[700 * 1024]))
                .isInstanceOf(SegmentCapacityExceededException.class);

            assertThat(log.entriesAppended())
                .as("a tail scan reads the log live, so a refused write must not appear in it")
                .isEqualTo(before);
            assertThat(segment.get(bytes("second"))).isNull();
        }
    }

    @Test
    @DisplayName("a logged write is applied and readable")
    void aLoggedWriteIsApplied() throws IOException {
        FlakyLog log = new FlakyLog(tempDir.resolve("segment_0.wal"));
        try (Segment segment = segmentWith(log)) {
            segment.put(bytes("k"), bytes("v"));

            assertThat(textOf(segment.get(bytes("k")))).isEqualTo("v");
            assertThat(log.entriesAppended()).isEqualTo(1L);
            assertThat(LogEntry.calculateSize(1, 1)).isPositive();
        }
    }
}
