package io.excalibur.store;

import io.excalibur.store.format.LogEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Damage in the middle of a log is not the same event as a torn tail.
 *
 * <p>A tail torn by a crash is expected: the records after the last complete one were never
 * acknowledged, so dropping them loses nothing anybody was told had happened. Bit rot in the
 * middle of a log is the opposite - every record after it was fsynced and acknowledged, and
 * stopping the scan there discards all of them while reporting a successful recovery. The two
 * are told apart by what follows the damaged record: only unwritten bytes, or more well-formed
 * records.</p>
 */
@DisplayName("Mid-log corruption")
class WriteLogMidLogCorruptionTest {

    /** Every record here carries a six-byte key and an eight-byte value, so they are uniform. */
    private static final int RECORD_SIZE = LogEntry.HEADER_SIZE + 6 + 8 + LogEntry.CRC32_SIZE;

    private static final int RECORD_COUNT = 100;

    @TempDir
    Path directory;

    private Path writeRecords() throws IOException {
        Path logFile = directory.resolve("segment_0.wal");
        try (WriteLog log = new WriteLog(logFile)) {
            for (int i = 0; i < RECORD_COUNT; i++) {
                log.appendPut(key(i), value(i));
            }
            assertThat(log.writeOffset()).isEqualTo((long) RECORD_COUNT * RECORD_SIZE);
        }
        return logFile;
    }

    private static byte[] key(int index) {
        return String.format("key%03d", index).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int index) {
        return String.format("value%03d", index).getBytes(StandardCharsets.UTF_8);
    }

    /** Flips a payload byte, leaving the opcode and the lengths intact, as bit rot would. */
    private void corruptPayloadOf(Path logFile, int recordIndex) throws IOException {
        long target = (long) recordIndex * RECORD_SIZE + LogEntry.KEY_BYTES_OFFSET;
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, target);
            one.flip();
            byte flipped = (byte) (one.get(0) ^ 0x40);
            channel.write(ByteBuffer.wrap(new byte[] { flipped }), target);
        }
    }

    @Test
    @DisplayName("a damaged record with sound records after it refuses to open the log")
    void midLogDamageRefusesToOpen() throws IOException {
        Path logFile = writeRecords();
        corruptPayloadOf(logFile, 50);

        assertThatThrownBy(() -> new WriteLog(logFile))
            .as("the 49 acknowledged records after the damage must not be discarded in silence")
            .isInstanceOf(CorruptLogException.class)
            .hasMessageContaining("2550");
    }

    @Test
    @DisplayName("a damaged record at the tail is a torn tail and still opens")
    void tailDamageStillOpens() throws IOException {
        Path logFile = writeRecords();
        corruptPayloadOf(logFile, RECORD_COUNT - 1);

        try (WriteLog reopened = new WriteLog(logFile)) {
            assertThat(reopened.writeOffset())
                .as("a crash-torn tail was never acknowledged, so the log ends before it")
                .isEqualTo((long) (RECORD_COUNT - 1) * RECORD_SIZE);
            assertThat(reopened.nextLsn()).isEqualTo(RECORD_COUNT - 1);
        }
    }

    @Test
    @DisplayName("a record cut short at the tail is a torn tail and still opens")
    void aPartialTailRecordStillOpens() throws IOException {
        Path logFile = writeRecords();
        long lastRecord = (long) (RECORD_COUNT - 1) * RECORD_SIZE;
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
            // The header of the last record survives and its payload does not, which is what
            // a write interrupted part-way through leaves behind.
            channel.write(ByteBuffer.allocate(RECORD_SIZE - LogEntry.HEADER_SIZE),
                lastRecord + LogEntry.HEADER_SIZE);
        }

        try (WriteLog reopened = new WriteLog(logFile)) {
            assertThat(reopened.writeOffset()).isEqualTo(lastRecord);
            assertThat(reopened.nextLsn()).isEqualTo(RECORD_COUNT - 1);
        }
    }

    @Test
    @DisplayName("replay that cannot read every record it was given fails the recovery")
    void anUnreadableRecordFailsReplay() throws IOException {
        Path logFile = directory.resolve("replay.wal");
        Path segmentFile = directory.resolve("segment_0.dat");

        try (Arena arena = Arena.ofShared();
             WriteLog log = new WriteLog(logFile);
             Segment segment = new Segment(segmentFile, 1024 * 1024, arena)) {

            log.appendPut(key(0), value(0));
            log.appendPut(key(1), value(1));
            log.appendPut(key(2), value(2));
            segment.attachWriteLog(log);

            // Damage the first record behind the log's back, so the write offset still covers
            // it. This is the state the scan can no longer rule out once the log is open.
            corruptPayloadOf(logFile, 0);

            assertThatThrownBy(segment::recoverFromLog)
                .as("a replay that skipped a record must not report the segment as restored")
                .isInstanceOf(CorruptLogException.class);
        }
    }

    @Test
    @DisplayName("an undamaged log reopens with every record")
    void anUndamagedLogReopensWhole() throws IOException {
        Path logFile = writeRecords();

        List<LogEntry> replayed = new ArrayList<>();
        try (WriteLog reopened = new WriteLog(logFile)) {
            assertThat(reopened.writeOffset()).isEqualTo((long) RECORD_COUNT * RECORD_SIZE);
            assertThat(reopened.recover(replayed::add)).isTrue();
        }
        assertThat(replayed).hasSize(RECORD_COUNT);
    }
}
