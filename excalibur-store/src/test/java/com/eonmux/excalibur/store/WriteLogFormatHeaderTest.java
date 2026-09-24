package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.store.format.UnsupportedWriteLogFormatException;
import com.eonmux.excalibur.store.format.WriteLogFormat;
import com.eonmux.excalibur.store.format.WriteLogHeader;
import com.eonmux.excalibur.store.hash.XxHash64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The header that names a write-ahead log's format, and the logs written before it existed.
 *
 * <p>Every case here is about the one decision the header adds to opening a log: which format
 * is this file in, and may this build touch it. A headerless log must keep replaying exactly as
 * it always did; a log a newer build wrote must be refused without a byte of it changing; and
 * a header that is merely damaged must not be mistaken for either, because the three call for
 * three different answers from an operator.</p>
 */
@DisplayName("Write-ahead log format header")
class WriteLogFormatHeaderTest {

    @TempDir
    Path directory;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a new log starts with a header, and its records round-trip through a reopen")
    void aNewLogCarriesAHeaderAndRoundTrips() throws IOException {
        Path path = directory.resolve("segment_0.wal");
        try (WriteLog log = new WriteLog(path)) {
            assertThat(log.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(log.writeOffset())
                .as("record positions do not include the header")
                .isZero();
            for (int i = 0; i < 3; i++) {
                log.appendPut(bytes("k" + i), bytes("v" + i));
            }
            assertThat(log.readEntryAt(0).key()).isEqualTo(bytes("k0"));
        }

        byte[] head = Arrays.copyOf(Files.readAllBytes(path), WriteLogHeader.SIZE);
        assertThat(new String(head, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("EXWL");
        assertThat(ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).getInt(4))
            .isEqualTo(WriteLogFormat.CURRENT.version());

        try (WriteLog reopened = new WriteLog(path)) {
            assertThat(reopened.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(reopened.nextLsn()).isEqualTo(3);
            List<LogEntry> replayed = new ArrayList<>();
            assertThat(reopened.recover(replayed::add)).isTrue();
            assertThat(replayed).extracting(LogEntry::lsn).containsExactly(0L, 1L, 2L);
            assertThat(replayed.get(2).value()).isEqualTo(bytes("v2"));
        }
    }

    @Test
    @DisplayName("a headerless log is replayed exactly as before, and is not rewritten")
    void aHeaderlessLogRecoversAsBefore() throws IOException {
        Path path = directory.resolve("segment_0.wal");
        byte[] legacy = legacyRecords(3);
        writeSparse(path, legacy, WriteLog.MAX_FILE_SIZE);

        try (WriteLog log = new WriteLog(path)) {
            assertThat(log.format()).isEqualTo(WriteLogFormat.V1);
            assertThat(log.writeOffset()).isEqualTo(legacy.length);
            assertThat(log.nextLsn()).isEqualTo(3);
            List<LogEntry> replayed = new ArrayList<>();
            assertThat(log.recover(replayed::add)).isTrue();
            assertThat(replayed).extracting(entry -> new String(entry.key(), StandardCharsets.UTF_8))
                .containsExactly("legacy-0", "legacy-1", "legacy-2");
            assertThat(log.readEntryAt(0).value()).isEqualTo(bytes("legacy-value-0"));

            assertThat(log.appendPut(bytes("after"), bytes("upgrade"))).isEqualTo(3);
        }

        byte[] now = Files.readAllBytes(path);
        assertThat(Arrays.copyOf(now, legacy.length))
            .as("the records an older build wrote stay where it wrote them")
            .isEqualTo(legacy);
        try (WriteLog reopened = new WriteLog(path)) {
            assertThat(reopened.format()).isEqualTo(WriteLogFormat.V1);
            List<LogEntry> replayed = new ArrayList<>();
            assertThat(reopened.recover(replayed::add)).isTrue();
            assertThat(replayed).extracting(LogEntry::lsn).containsExactly(0L, 1L, 2L, 3L);
        }
    }

    @Test
    @DisplayName("a headerless log with nothing in it is given a header when it is opened")
    void anEmptyHeaderlessLogIsUpgraded() throws IOException {
        Path path = directory.resolve("segment_0.wal");
        Files.write(path, new byte[4096]);

        try (WriteLog log = new WriteLog(path)) {
            assertThat(log.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(log.writeOffset()).isZero();
            log.appendPut(bytes("k"), bytes("v"));
        }

        assertThat(Arrays.copyOf(Files.readAllBytes(path), WriteLogHeader.SIZE))
            .isEqualTo(WriteLogHeader.encode(WriteLogFormat.CURRENT.version()));
        try (WriteLog reopened = new WriteLog(path)) {
            assertThat(reopened.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(reopened.nextLsn()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a log a newer build wrote is refused, and not a byte of it changes")
    void aNewerVersionIsRefusedAndLeftUntouched() throws IOException {
        Path path = directory.resolve("segment_0.wal");
        int newer = WriteLogFormat.CURRENT.version() + 1;
        byte[] image = concat(WriteLogHeader.encode(newer), legacyRecords(2), new byte[512]);
        Files.write(path, image);

        assertThatThrownBy(() -> new WriteLog(path))
            .isInstanceOf(UnsupportedWriteLogFormatException.class)
            .isNotInstanceOf(CorruptLogException.class)
            .hasMessageContaining("version " + newer)
            .hasMessageContaining(path.toString())
            .satisfies(e -> {
                UnsupportedWriteLogFormatException refused = (UnsupportedWriteLogFormatException) e;
                assertThat(refused.version()).isEqualTo(newer);
                assertThat(refused.file()).isEqualTo(path);
                assertThat(refused.newest()).isEqualTo(WriteLogFormat.CURRENT.version());
            });

        assertThat(Files.size(path)).as("not extended to the mapping size").isEqualTo(image.length);
        assertThat(Files.readAllBytes(path)).isEqualTo(image);
    }

    @Test
    @DisplayName("a damaged header is corruption, not a newer version, and is left untouched")
    void aDamagedHeaderIsCorruption() throws IOException {
        Path path = logWithRecords(3);
        flipByte(path, 5);
        byte[] before = Files.readAllBytes(path);

        assertThatThrownBy(() -> new WriteLog(path))
            .isInstanceOf(CorruptLogException.class)
            .isNotInstanceOf(UnsupportedWriteLogFormatException.class)
            .hasMessageContaining("header");

        assertThat(Files.readAllBytes(path)).isEqualTo(before);
    }

    @Test
    @DisplayName("a header wiped out with records behind it is corruption, not an empty log")
    void aWipedHeaderWithRecordsIsCorruption() throws IOException {
        Path path = logWithRecords(3);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.write(new byte[WriteLogHeader.SIZE]);
        }

        assertThatThrownBy(() -> new WriteLog(path))
            .isInstanceOf(CorruptLogException.class)
            .hasMessageContaining("header");
    }

    @Test
    @DisplayName("a header torn as the log was created, with nothing behind it, is rewritten")
    void aHeaderTornAtCreationIsRewritten() throws IOException {
        Path path = directory.resolve("segment_0.wal");
        byte[] image = new byte[4096];
        System.arraycopy(WriteLogHeader.encode(WriteLogFormat.CURRENT.version()), 0, image, 0, 6);
        Files.write(path, image);

        try (WriteLog log = new WriteLog(path)) {
            assertThat(log.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(log.writeOffset()).isZero();
            log.appendPut(bytes("k"), bytes("v"));
        }

        try (WriteLog reopened = new WriteLog(path)) {
            List<LogEntry> replayed = new ArrayList<>();
            assertThat(reopened.recover(replayed::add)).isTrue();
            assertThat(replayed).hasSize(1);
        }
    }

    @Test
    @DisplayName("a tail torn after the header ends the log there and the next append replaces it")
    void aTornTailAfterTheHeaderEndsTheLog() throws IOException {
        Path path = logWithRecords(3);
        long end;
        try (WriteLog log = new WriteLog(path)) {
            end = log.writeOffset();
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(WriteLogHeader.SIZE + end + LogEntry.OPCODE_OFFSET);
            file.writeByte(LogEntry.OPCODE_PUT);
        }

        try (WriteLog log = new WriteLog(path)) {
            assertThat(log.writeOffset()).isEqualTo(end);
            assertThat(log.nextLsn()).isEqualTo(3);
            log.appendPut(bytes("k3"), bytes("v3"));
        }
        try (WriteLog reopened = new WriteLog(path)) {
            List<LogEntry> replayed = new ArrayList<>();
            assertThat(reopened.recover(replayed::add)).isTrue();
            assertThat(replayed).extracting(LogEntry::lsn).containsExactly(0L, 1L, 2L, 3L);
        }
    }

    @Test
    @DisplayName("a rotated log starts the new file with a header")
    void aRotatedLogStartsWithAHeader() throws IOException {
        Path path = directory.resolve("segment_0.wal");
        writeSparse(path, legacyRecords(2), 8192);
        try (WriteLog log = new WriteLog(path)) {
            assertThat(log.format()).isEqualTo(WriteLogFormat.V1);
            log.rotate();
            assertThat(log.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(log.writeOffset()).isZero();
            log.appendPut(bytes("k"), bytes("v"));
            assertThat(log.readEntryAt(0).key()).isEqualTo(bytes("k"));
        }
        try (WriteLog reopened = new WriteLog(path)) {
            assertThat(reopened.format()).isEqualTo(WriteLogFormat.CURRENT);
            assertThat(reopened.nextLsn()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a header naming a version reads back, and one with its CRC altered does not")
    void theHeaderIsCheckedByItsCrc() {
        byte[] header = WriteLogHeader.encode(7);
        assertThat(header).hasSize(WriteLogHeader.SIZE);
        byte[] altered = header.clone();
        altered[WriteLogHeader.SIZE - 1] ^= 1;

        var sound = com.eonmux.excalibur.core.memory.MemorySegmentFactory
            .wrapBuffer(ByteBuffer.wrap(header));
        var damaged = com.eonmux.excalibur.core.memory.MemorySegmentFactory
            .wrapBuffer(ByteBuffer.wrap(altered));
        assertThat(WriteLogHeader.hasMagic(sound)).isTrue();
        assertThat(WriteLogHeader.isSound(sound)).isTrue();
        assertThat(WriteLogHeader.version(sound)).isEqualTo(7);
        assertThat(WriteLogHeader.hasMagic(damaged)).isTrue();
        assertThat(WriteLogHeader.isSound(damaged)).isFalse();
    }

    /**
     * Writes a current log holding some records, through the log's own writer.
     */
    private Path logWithRecords(int records) throws IOException {
        Path path = directory.resolve("segment_0.wal");
        try (WriteLog log = new WriteLog(path)) {
            for (int i = 0; i < records; i++) {
                log.appendPut(bytes("k" + i), bytes("v" + i));
            }
        }
        return path;
    }

    /**
     * Builds the records of a headerless log byte for byte, as a build before the header wrote
     * them: opcode, lsn, timestamp, key hash, key length and value length big-endian, then the
     * key, the value, and a CRC-32 of everything before it. Deliberately not through
     * {@link LogEntry}, so a change to how this build writes records cannot also change what
     * the test calls an old one.
     */
    private static byte[] legacyRecords(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] key = bytes("legacy-" + i);
            byte[] value = bytes("legacy-value-" + i);
            ByteBuffer record = ByteBuffer.allocate(33 + key.length + value.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
            record.put(LogEntry.OPCODE_PUT)
                .putLong(i)
                .putLong(1_700_000_000_000L + i)
                .putLong(XxHash64.hash(key))
                .putInt(key.length)
                .putInt(value.length)
                .put(key)
                .put(value);
            CRC32 crc = new CRC32();
            crc.update(record.array(), 0, record.position());
            record.putInt((int) crc.getValue());
            out.writeBytes(record.array());
        }
        return out.toByteArray();
    }

    /**
     * Writes a file's leading bytes and extends it, sparsely, to a size.
     */
    private static void writeSparse(Path path, byte[] head, long size) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.write(head);
            file.setLength(size);
        }
    }

    private static void flipByte(Path path, long offset) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(offset);
            byte original = file.readByte();
            file.seek(offset);
            file.writeByte(original ^ 0x01);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
