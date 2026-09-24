package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileInspector;
import com.eonmux.excalibur.fsck.check.FileKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A data directory some other process holds, and the entries that say so.
 *
 * <p>A repair moves and rewrites files, so one run beside a node would change what the node has
 * mapped underneath it: it takes the directory's lock and is refused when a node holds it. A
 * check writes nothing and is still worth running on a live directory, so it proceeds and says
 * that what it read may have been changing. The lock file itself, and a restore's journal and
 * scratch, are coordination rather than data and must not be checked as data.</p>
 */
class DataDirectoryInUseTest {

    /** Captured stdout, stderr and exit code of one invocation. */
    private record Invocation(int exitCode, String out, String err) {
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = ExcaliburFsck.run(args, outStream, errStream);
        }
        return new Invocation(code,
            out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void repairIsRefusedWhileANodeHoldsTheDirectory(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Path damaged = DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);

        Invocation result;
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDir, "excalibur node")) {
            result = invoke(dataDir.toString(), "--repair",
                "--quarantine-dir=" + tempDir.resolve("quarantine"));
        }

        assertThat(result.exitCode()).isEqualTo(FsckExit.IN_USE);
        assertThat(result.err())
            .contains(dataDir.toString())
            .contains("excalibur node")
            .contains("pid " + ProcessHandle.current().pid());
        assertThat(damaged).as("a refused repair moves nothing").exists();
        assertThat(tempDir.resolve("quarantine")).doesNotExist();
    }

    @Test
    void aCheckProceedsOnAHeldDirectoryAndWarns(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);

        Invocation result;
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDir, "excalibur node")) {
            result = invoke(dataDir.toString());
        }

        assertThat(result.exitCode()).isEqualTo(FsckExit.DAMAGE);
        assertThat(result.out()).containsIgnoringCase("corrupt");
        assertThat(result.err())
            .contains("excalibur node")
            .containsIgnoringCase("may be changing");
    }

    @Test
    void aCheckOnAFreeDirectorySaysNothingAboutLocksAndCreatesNothing(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Map<String, String> before = DataDirectoryFixture.fingerprint(dataDir);

        Invocation result = invoke(dataDir.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(DataDirectoryFixture.fingerprint(dataDir)).isEqualTo(before);
        assertThat(dataDir.resolve(DataDirectoryLock.FILE_NAME)).doesNotExist();
    }

    @Test
    void aRepairLeavesTheLockFileWhichTheNextCheckDoesNotReport(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);

        Invocation repair = invoke(dataDir.toString(), "--repair",
            "--quarantine-dir=" + tempDir.resolve("quarantine"));
        assertThat(repair.exitCode()).as("%s%s", repair.out(), repair.err()).isZero();
        assertThat(dataDir.resolve(DataDirectoryLock.FILE_NAME)).exists();
        assertThat(DataDirectoryLock.probe(dataDir)).as("released when the repair ends").isEmpty();

        Invocation check = invoke(dataDir.toString(), "--strict");
        assertThat(check.exitCode()).as("%s", check.out()).isZero();
        assertThat(check.out()).doesNotContain(DataDirectoryLock.FILE_NAME);
    }

    @Test
    void theLockFileIsClassifiedAsCoordinationNotData(@TempDir Path tempDir) throws IOException {
        assertThat(FileClassifier.classify(DataDirectoryLock.FILE_NAME)).isEqualTo(FileKind.NODE_LOCK);
        assertThat(FileClassifier.classify("shared/" + DataDirectoryLock.FILE_NAME))
            .isEqualTo(FileKind.UNRECOGNISED);
        Path lock = tempDir.resolve(DataDirectoryLock.FILE_NAME);
        Files.writeString(lock, "pid=1\n");
        assertThat(FileInspector.inspect(lock, DataDirectoryLock.FILE_NAME).check().status())
            .isEqualTo(CheckStatus.NOT_DATA);
    }

    @Test
    void anInterruptedRestoreIsDamageForAnOperator(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Files.writeString(dataDir.resolve(InterruptedRestore.JOURNAL_FILE_NAME), "journal");
        Path staging = dataDir.resolve(InterruptedRestore.STAGING_PREFIX + "abc");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("segment_9.dat"), "half a segment");

        Invocation result = invoke(dataDir.toString());

        assertThat(result.exitCode()).isEqualTo(FsckExit.DAMAGE);
        assertThat(result.out())
            .contains(InterruptedRestore.JOURNAL_FILE_NAME)
            .contains("excalibur-backup restore")
            .doesNotContain(InterruptedRestore.STAGING_PREFIX + "abc/segment_9.dat");
    }

    @Test
    void restoreScratchWithoutAJournalIsAWarningNotData(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Path displaced = dataDir.resolve(InterruptedRestore.DISPLACED_PREFIX + "abc");
        Files.createDirectories(displaced);
        Files.writeString(displaced.resolve("segment_900.dat"), "old contents");

        Invocation result = invoke(dataDir.toString(), "--strict");

        assertThat(result.exitCode()).as("%s", result.out()).isZero();
        assertThat(result.out())
            .contains(InterruptedRestore.DISPLACED_PREFIX + "abc")
            .doesNotContain("segment_900.dat");
    }
}
