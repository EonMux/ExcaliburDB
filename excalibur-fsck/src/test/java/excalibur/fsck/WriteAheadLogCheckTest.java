package excalibur.fsck;

import excalibur.fsck.check.CheckStatus;
import excalibur.fsck.check.WriteAheadLogCheck;
import io.excalibur.store.LogScan;
import io.excalibur.store.WriteLog;
import io.excalibur.store.format.LogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write-ahead log's three endings, and what repair is allowed to do about each.
 *
 * <p>This is the part of the tool where being wrong costs the most. A tail torn by a crash
 * may be disclaimed, because nothing behind it was ever acknowledged; damage with sound
 * records after it may not, because everything behind it was. The two look identical at the
 * point the scan stops, and the whole distinction rests on what follows.</p>
 */
class WriteAheadLogCheckTest {

    @Test
    void aCleanlyClosedLogIsIntact(@TempDir Path dir) throws IOException {
        Path log = writtenLog(dir, 4);

        var result = WriteAheadLogCheck.check(log, "segment_0.wal");

        assertThat(result.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.detail()).contains("ends cleanly");
    }

    @Test
    void anEmptyLogIsIntactWithNothingToReplay(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("segment_0.wal");
        Files.write(log, new byte[0]);

        var result = WriteAheadLogCheck.check(log, "segment_0.wal");

        assertThat(result.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.detail()).contains("empty");
    }

    @Test
    void aTailTornByACrashIsUnsealedRatherThanCorrupt(@TempDir Path dir) throws IOException {
        Path log = writtenLog(dir, 4);
        long end = tearTail(log);

        var result = WriteAheadLogCheck.check(log, "segment_0.wal");

        assertThat(result.status())
            .as("no acknowledged write was lost, so this must not read as damage")
            .isEqualTo(CheckStatus.UNSEALED);
        assertThat(result.detail()).contains("torn by a crash at offset " + end);
    }

    @Test
    void damageWithSoundRecordsBehindItIsCorrupt(@TempDir Path dir) throws IOException {
        Path log = writtenLog(dir, 6);
        corruptTheFirstRecord(log);

        var result = WriteAheadLogCheck.check(log, "segment_0.wal");

        assertThat(result.status())
            .as("records after the damage were fsynced and acknowledged")
            .isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("Sound records resume at offset");
    }

    @Test
    void repairDisclaimsATornTailAndLeavesTheSoundRecordsAlone(@TempDir Path tempDir)
            throws IOException {
        // A whole store, because a log whose segment is missing is a different case: it is
        // moved aside rather than trimmed, and trimming it would be the wrong repair.
        Path dir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dir, 32);
        Path log = dir.resolve("segment_0.wal");
        long end = tearTail(log);
        byte[] before = Files.readAllBytes(log);

        int exitCode = run(dir.toString(), "--repair",
            "--quarantine-dir=" + tempDir.resolve("q"));

        assertThat(exitCode).isZero();
        LogScan after = WriteAheadLogCheck.scanFor(log);
        assertThat(after).isNotNull();
        assertThat(after.tornTail())
            .as("the log must now end before the torn record")
            .isNull();
        assertThat(after.endOffset()).isEqualTo(end);
        byte[] now = Files.readAllBytes(log);
        assertThat(now[(int) end]).as("only the opcode byte may change").isZero();
        assertThat(countDifferences(before, now))
            .as("a torn tail costs exactly one byte")
            .isEqualTo(1);
    }

    @Test
    void trimmingIsSkippedWhenTheTearIsNoLongerThere(@TempDir Path dir) throws IOException {
        Path log = writtenLog(dir, 4);

        var result = WriteAheadLogCheck.check(log, "segment_0.wal");

        assertThat(result.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(WriteAheadLogCheck.scanFor(log).tornTail()).isNull();
    }

    /**
     * Runs the command, discarding its output.
     *
     * @param args The command line
     * @return The exit code
     */
    private static int run(String... args) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(sink, true, StandardCharsets.UTF_8)) {
            return ExcaliburFsck.run(args, stream, stream);
        }
    }

    /**
     * Writes a log with a number of records, through the log's own writer.
     *
     * @param dir     Where the log goes
     * @param records How many entries to append
     * @return The log file
     * @throws IOException if it cannot be written
     */
    private static Path writtenLog(Path dir, int records) throws IOException {
        Path log = dir.resolve("segment_0.wal");
        try (WriteLog writeLog = new WriteLog(log)) {
            for (int i = 0; i < records; i++) {
                writeLog.appendPut(("k" + i).getBytes(StandardCharsets.UTF_8),
                    ("value-number-" + i).getBytes(StandardCharsets.UTF_8));
            }
        }
        return log;
    }

    /**
     * Starts a record at the end of the log and writes nothing else, as a crash would.
     *
     * @param log The log file
     * @return The offset the log ended at before the tear
     * @throws IOException if the log cannot be scanned or written
     */
    private static long tearTail(Path log) throws IOException {
        long end = WriteAheadLogCheck.scanFor(log).endOffset();
        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] { LogEntry.OPCODE_PUT }),
                end + LogEntry.OPCODE_OFFSET);
        }
        return end;
    }

    /**
     * Damages the first record, leaving every record after it sound.
     *
     * @param log The log file
     * @throws IOException if the log cannot be written
     */
    private static void corruptTheFirstRecord(Path log) throws IOException {
        byte[] bytes = Files.readAllBytes(log);
        int at = LogEntry.KEY_BYTES_OFFSET;
        bytes[at] ^= 0x7F;
        Files.write(log, bytes);
    }

    /**
     * Counts how many bytes differ between two images of the same file.
     *
     * @param before The earlier image
     * @param after  The later image
     * @return The number of differing positions
     */
    private static int countDifferences(byte[] before, byte[] after) {
        assertThat(after).hasSameSizeAs(before);
        int differences = 0;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                differences++;
            }
        }
        return differences;
    }
}
