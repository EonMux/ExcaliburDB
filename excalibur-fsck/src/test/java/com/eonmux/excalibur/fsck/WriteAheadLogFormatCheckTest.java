package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.WriteAheadLogCheck;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.store.format.WriteLogFormat;
import com.eonmux.excalibur.store.format.WriteLogHeader;
import com.eonmux.excalibur.store.hash.XxHash64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write-ahead log's header as the checker sees it.
 *
 * <p>Four verdicts hang on it, and the planner treats each differently: a sound current header
 * is proof like any checksum, a missing one is an older build's log to be reported and left, a
 * damaged one is damage an operator decides about, and one naming a newer version is a file
 * this build may neither judge nor touch - so it is never trimmed and never moved aside.</p>
 */
class WriteAheadLogFormatCheckTest {

    private static final int NEWER = WriteLogFormat.CURRENT.version() + 1;

    private static final String NAME = "segment_0.wal";

    @Test
    void aLogWithASoundHeaderIsIntactAtTheCurrentVersion(@TempDir Path dir) throws IOException {
        Path log = writtenLog(dir, 3);

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.version()).isEqualTo("v" + WriteLogFormat.CURRENT.version());
        assertThat(check.detail()).contains("header and every record matched");
    }

    @Test
    void aHeaderlessLogIsLegacyUnprotectedWithItsRecordsStillWeighed(@TempDir Path dir)
            throws IOException {
        Path log = dir.resolve(NAME);
        byte[] records = legacyRecords(3);
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            file.write(records);
            file.setLength(WriteLog.MAX_FILE_SIZE);
        }

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(check.version()).isEqualTo("v1");
        assertThat(check.isDamage()).isFalse();
        assertThat(check.detail())
            .contains("every record matched its checksum")
            .contains("ends cleanly at offset " + records.length)
            .contains("no header");
    }

    @Test
    void aHeaderlessLogWithDamageInTheMiddleIsStillCorrupt(@TempDir Path dir) throws IOException {
        Path log = dir.resolve(NAME);
        byte[] records = legacyRecords(4);
        records[LogEntry.KEY_BYTES_OFFSET] ^= 0x7F;
        Files.write(log, records);

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.needsOperator()).isTrue();
    }

    @Test
    void anEmptyHeaderlessLogIsLegacyWithNothingToLose(@TempDir Path dir) throws IOException {
        Path log = dir.resolve(NAME);
        Files.write(log, new byte[4096]);

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(check.detail()).contains("holds no record");
    }

    @Test
    void aLogFromANewerBuildIsUnreadableAndItsRecordsAreNotWalked(@TempDir Path dir)
            throws IOException {
        Path log = writtenLog(dir, 3);
        restamp(log, NEWER);
        byte[] before = Files.readAllBytes(log);

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(check.version()).isEqualTo("v" + NEWER);
        assertThat(check.isDamage()).as("the node refuses it, so it fails the run").isTrue();
        assertThat(check.detail())
            .contains("version " + NEWER)
            .contains("newer build")
            .contains("records were not walked");
        assertThat(Files.readAllBytes(log)).isEqualTo(before);
    }

    @Test
    void aDamagedHeaderIsCorruptionForAnOperatorNotANewerVersion(@TempDir Path dir)
            throws IOException {
        Path log = writtenLog(dir, 3);
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            file.seek(6);
            file.writeByte(0x55);
        }

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.needsOperator()).isTrue();
        assertThat(check.detail()).contains("damaged header").contains("not a log from a newer");
    }

    @Test
    void aHeaderTornAsTheLogWasCreatedIsUnsealed(@TempDir Path dir) throws IOException {
        Path log = dir.resolve(NAME);
        byte[] image = new byte[4096];
        System.arraycopy(WriteLogHeader.encode(WriteLogFormat.CURRENT.version()), 0, image, 0, 5);
        Files.write(log, image);

        FileCheck check = WriteAheadLogCheck.check(log, NAME);

        assertThat(check.status()).isEqualTo(CheckStatus.UNSEALED);
        assertThat(check.detail()).contains("header is torn");
    }

    @Test
    void aNewerLogIsPutToAnOperatorAndNeverTrimmedOrMovedAside(@TempDir Path tempDir)
            throws IOException {
        Path dir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dir, 32);
        Path log = dir.resolve(NAME);
        restamp(log, NEWER);
        byte[] before = Files.readAllBytes(log);
        Path quarantine = tempDir.resolve("q");

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dir).scan());
        assertThat(plan.actions()).noneMatch(action -> action.relative().equals(NAME));
        assertThat(plan.needsOperator()).anyMatch(note -> note.contains(NAME)
            && note.contains("newer build"));

        int exitCode = run(dir.toString(), "--repair", "--quarantine-dir=" + quarantine);

        assertThat(exitCode).as("an unreadable log fails the run").isEqualTo(1);
        assertThat(Files.readAllBytes(log)).isEqualTo(before);
        assertThat(quarantine.resolve(NAME)).doesNotExist();
    }

    @Test
    void aNewerLogWhoseSegmentIsGoneIsNotMovedAside(@TempDir Path tempDir) throws IOException {
        Path dir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dir, 32);
        String orphan = "segment_4242.wal";
        Path log = dir.resolve(orphan);
        Files.write(log, concat(WriteLogHeader.encode(NEWER), legacyRecords(2)));

        DataDirectoryScan scan = new DataDirectoryScanner(dir).scan();
        assertThat(scan.findings()).anyMatch(finding -> finding.subject().equals(orphan));
        RepairPlan plan = RepairPlanner.plan(scan);

        assertThat(plan.actions()).noneMatch(action -> action.relative().equals(orphan));
        assertThat(plan.needsOperator()).anyMatch(note -> note.contains(orphan));
    }

    @Test
    void aLegacyLogInAStoreIsReportedAndLeftAlone(@TempDir Path tempDir) throws IOException {
        Path dir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dir, 32);
        Path log = dir.resolve(NAME);
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            file.setLength(0);
            file.write(legacyRecords(2));
            file.setLength(WriteLog.MAX_FILE_SIZE);
        }

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dir).scan());

        assertThat(plan.actions()).noneMatch(action -> action.relative().equals(NAME));
        assertThat(plan.needsOperator()).noneMatch(note -> note.contains(NAME));
    }

    private static int run(String... args) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(sink, true, StandardCharsets.UTF_8)) {
            return ExcaliburFsck.run(args, stream, stream);
        }
    }

    private static Path writtenLog(Path dir, int records) throws IOException {
        Path log = dir.resolve(NAME);
        try (WriteLog writeLog = new WriteLog(log)) {
            for (int i = 0; i < records; i++) {
                writeLog.appendPut(("k" + i).getBytes(StandardCharsets.UTF_8),
                    ("value-" + i).getBytes(StandardCharsets.UTF_8));
            }
        }
        return log;
    }

    /** Rewrites a log's header, soundly, to name another version. */
    private static void restamp(Path log, int version) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            file.write(WriteLogHeader.encode(version));
        }
    }

    /**
     * Builds the records of a headerless log byte for byte, as a build before the header wrote
     * them, independently of how this build writes records.
     */
    private static byte[] legacyRecords(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] key = ("legacy-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = ("legacy-value-" + i).getBytes(StandardCharsets.UTF_8);
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

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
