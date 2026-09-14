package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A write the device would not take must not turn out to have happened.
 *
 * <p>The record bytes reach the mapping before the fsync that makes them durable, so a device
 * error at that fsync is reported to the caller as a failed write while the record is already
 * in the file and the mutation is already in the segment. That is the inverse of losing an
 * acknowledged write and is worse: the caller believes the write failed, cannot see it now,
 * and finds it committed after a restart. Both halves have to be undone - the record so that a
 * rescan does not find it, and the segment so that no reader does.</p>
 */
@DisplayName("A write-ahead log append the device rejects")
class WriteLogSyncFailureTest {

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

    /** A log whose device rejects the write only once the record is already in the mapping. */
    private static final class SyncFailingLog extends WriteLog {

        private boolean failing;

        SyncFailingLog(Path path) throws IOException {
            super(path);
        }

        void failSync() {
            failing = true;
        }

        @Override
        void fsync(long offset, int length) throws IOException {
            if (failing) {
                throw new IOException("simulated device error");
            }
            super.fsync(offset, length);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String textOf(MemorySegment value) {
        return value == null ? null : new String(value.toArray(ValueLayout.JAVA_BYTE),
            StandardCharsets.UTF_8);
    }

    private Segment segmentWith(WriteLog log) throws IOException {
        Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1024 * 1024, arena);
        segment.attachWriteLog(log);
        return segment;
    }

    @Test
    @DisplayName("leaves the log head where it was")
    void theHeadDoesNotAdvance() throws IOException {
        try (SyncFailingLog log = new SyncFailingLog(tempDir.resolve("head.wal"))) {
            log.appendPut(bytes("k1"), bytes("v1"));
            log.appendPut(bytes("k2"), bytes("v2"));
            long head = log.writeOffset();
            log.failSync();

            assertThatThrownBy(() -> log.appendPut(bytes("k3"), bytes("v3")))
                .isInstanceOf(IOException.class);

            assertThat(log.writeOffset())
                .as("a record nothing has made durable is not part of the log")
                .isEqualTo(head);
            assertThat(log.entriesAppended()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("leaves nothing for a reopened log to replay")
    void theRecordIsNotResurrectedOnReopen() throws IOException {
        Path logFile = tempDir.resolve("reopen.wal");
        try (SyncFailingLog log = new SyncFailingLog(logFile)) {
            log.appendPut(bytes("k1"), bytes("v1"));
            log.appendPut(bytes("k2"), bytes("v2"));
            log.failSync();
            assertThatThrownBy(() -> log.appendPut(bytes("k3"), bytes("v3")))
                .isInstanceOf(IOException.class);
        }

        List<String> replayed = new ArrayList<>();
        try (WriteLog reopened = new WriteLog(logFile)) {
            reopened.recover(entry -> replayed.add(new String(entry.key(), StandardCharsets.UTF_8)));
        }

        assertThat(replayed)
            .as("the caller was told the write failed, so a restart must not commit it")
            .containsExactly("k1", "k2");
    }

    @Test
    @DisplayName("leaves an insert out of the segment")
    void anInsertIsNotApplied() throws IOException {
        try (SyncFailingLog log = new SyncFailingLog(tempDir.resolve("insert.wal"));
             Segment segment = segmentWith(log)) {
            segment.put(bytes("present"), bytes("v"));
            log.failSync();

            assertThatThrownBy(() -> segment.put(bytes("k"), bytes("v")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(segment.get(bytes("k")))
                .as("the caller was told the write failed; a reader must not find it anyway")
                .isNull();
            assertThat(segment.entryCount())
                .as("an undone insert must not leave its slot occupied")
                .isEqualTo(1);
            assertThat(textOf(segment.get(bytes("present"))))
                .as("undoing the insert must restore whatever it displaced")
                .isEqualTo("v");
        }
    }

    @Test
    @DisplayName("leaves the previous value of an overwrite in place")
    void anOverwriteIsNotApplied() throws IOException {
        try (SyncFailingLog log = new SyncFailingLog(tempDir.resolve("overwrite.wal"));
             Segment segment = segmentWith(log)) {
            segment.put(bytes("k"), bytes("first"));
            log.failSync();

            assertThatThrownBy(() -> segment.put(bytes("k"), bytes("second-and-longer")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(textOf(segment.get(bytes("k"))))
                .as("a refused overwrite must not replace the value that is still logged")
                .isEqualTo("first");
        }
    }

    @Test
    @DisplayName("leaves a deleted entry readable")
    void aDeleteIsNotApplied() throws IOException {
        try (SyncFailingLog log = new SyncFailingLog(tempDir.resolve("delete.wal"));
             Segment segment = segmentWith(log)) {
            segment.put(bytes("k"), bytes("v"));
            log.failSync();

            assertThatThrownBy(() -> segment.delete(bytes("k")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(textOf(segment.get(bytes("k"))))
                .as("a delete nothing recorded would come back on restart; it must not apply now")
                .isEqualTo("v");
            assertThat(segment.liveEntryCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("does not stop the log taking the next write")
    void thelogStillWorksAfterwards() throws IOException {
        Path logFile = tempDir.resolve("recovering.wal");
        try (SyncFailingLog log = new SyncFailingLog(logFile)) {
            log.appendPut(bytes("k1"), bytes("v1"));
            log.failSync();
            assertThatThrownBy(() -> log.appendPut(bytes("k2"), bytes("v2")))
                .isInstanceOf(IOException.class);

            log.failing = false;
            log.appendPut(bytes("k3"), bytes("v3"));
        }

        List<String> replayed = new ArrayList<>();
        try (WriteLog reopened = new WriteLog(logFile)) {
            reopened.recover(entry -> replayed.add(new String(entry.key(), StandardCharsets.UTF_8)));
        }
        assertThat(replayed).containsExactly("k1", "k3");
    }
}
