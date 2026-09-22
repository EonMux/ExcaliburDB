package com.eonmux.excalibur.backup.cli;

import com.eonmux.excalibur.backup.snapshot.SnapshotLayout;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command, and the exit codes an operator's script reads.
 *
 * <p>Every refusal has its own code, because a wrapper that can only tell success from
 * failure cannot distinguish "the backup is damaged, page someone" from "you pointed at a
 * directory that already holds a node, which is probably not what you meant".
 */
@DisplayName("The excalibur-backup command")
class BackupCliTest {

    @TempDir
    Path tempDir;

    /** One invocation's outcome. */
    private record Run(int code, String out, String err) {
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = BackupCli.run(args, outStream, errStream).code();
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("no arguments prints usage and exits with the usage code")
    void noArgumentsIsAUsageError() {
        Run run = run();

        assertThat(run.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(run.err()).contains("snapshot").contains("verify").contains("restore");
    }

    @Test
    @DisplayName("an unknown command is a usage error")
    void unknownCommandIsAUsageError() {
        assertThat(run("frobnicate").code()).isEqualTo(ExitCode.USAGE.code());
    }

    @Test
    @DisplayName("a missing required option is a usage error")
    void missingOptionIsAUsageError() {
        assertThat(run("snapshot", "--data-dir", tempDir.toString()).code())
            .isEqualTo(ExitCode.USAGE.code());
    }

    @Test
    @DisplayName("an unknown option is a usage error rather than being ignored")
    void unknownOptionIsAUsageError() {
        assertThat(run("verify", "--snapshot", tempDir.toString(), "--turbo").code())
            .isEqualTo(ExitCode.USAGE.code());
    }

    @Test
    @DisplayName("help is not an error")
    void helpSucceeds() {
        Run run = run("--help");

        assertThat(run.code()).isEqualTo(ExitCode.OK.code());
        assertThat(run.out()).contains("snapshot");
    }

    @Test
    @DisplayName("snapshot, verify and restore all succeed against a real directory")
    void theWholeCycleSucceeds() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        Path target = tempDir.resolve("restored");

        Run snapshot = run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString());
        assertThat(snapshot.code()).isEqualTo(ExitCode.OK.code());
        assertThat(snapshot.out()).contains("epoch");

        Run verify = run("verify", "--snapshot", snapshotDir.toString());
        assertThat(verify.code()).isEqualTo(ExitCode.OK.code());
        assertThat(verify.out()).contains("verified");

        Run restore = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", target.toString());
        assertThat(restore.code()).isEqualTo(ExitCode.OK.code());
        assertThat(target.resolve("catalog.dat")).exists();
    }

    @Test
    @DisplayName("verifying a damaged snapshot exits with the verification code")
    void damagedSnapshotFailsVerification() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", snapshotDir.toString());
        DataDirectoryFixture.flipAByte(snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
            .resolve("segment_0.dat"));

        Run verify = run("verify", "--snapshot", snapshotDir.toString());

        assertThat(verify.code()).isEqualTo(ExitCode.VERIFICATION_FAILED.code());
        assertThat(verify.err()).contains("segment_0.dat");
    }

    @Test
    @DisplayName("restoring a damaged snapshot exits with the verification code")
    void damagedSnapshotIsNotRestored() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", snapshotDir.toString());
        DataDirectoryFixture.flipAByte(snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
            .resolve("catalog.dat"));
        Path target = tempDir.resolve("restored");

        Run restore = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", target.toString());

        assertThat(restore.code()).isEqualTo(ExitCode.VERIFICATION_FAILED.code());
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("restoring over a non-empty target exits with its own code")
    void nonEmptyTargetHasItsOwnExitCode() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", snapshotDir.toString());
        Path target = Files.createDirectories(tempDir.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 128, 5L);

        Run refused = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", target.toString());
        assertThat(refused.code()).isEqualTo(ExitCode.TARGET_NOT_EMPTY.code());
        assertThat(refused.err()).contains("--force");

        Run forced = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", target.toString(), "--force");
        assertThat(forced.code()).isEqualTo(ExitCode.OK.code());
    }

    @Test
    @DisplayName("snapshotting into a non-empty directory exits with the not-empty code")
    void nonEmptySnapshotDestinationHasTheNotEmptyCode() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = Files.createDirectories(tempDir.resolve("snap"));
        Path occupant = snapshotDir.resolve("already-here");
        DataDirectoryFixture.writeRandom(occupant, 64, 9L);

        Run refused = run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString());

        assertThat(refused.code()).isEqualTo(ExitCode.TARGET_NOT_EMPTY.code());
        assertThat(refused.err()).contains(snapshotDir.toString());
        try (var entries = Files.list(snapshotDir)) {
            assertThat(entries).containsExactly(occupant);
        }
    }

    @Test
    @DisplayName("snapshotting a directory that is not there exits with the I/O code")
    void missingDataDirectoryIsAnIoError() {
        Run run = run("snapshot", "--data-dir", tempDir.resolve("nope").toString(),
            "--out", tempDir.resolve("snap").toString());

        assertThat(run.code()).isEqualTo(ExitCode.IO_ERROR.code());
        assertThat(run.err()).isNotBlank();
    }

    @Test
    @DisplayName("--exclude-derived leaves the rebuildable indexes out")
    void excludeDerivedOmitsRebuildableIndexes() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        Run snapshot = run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString(), "--exclude-derived");

        assertThat(snapshot.code()).isEqualTo(ExitCode.OK.code());
        Path payload = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY);
        assertThat(payload.resolve("chunk-index")).doesNotExist();
        assertThat(payload.resolve("segment_0.hnsw")).exists();
        assertThat(payload.resolve("catalog.dat")).exists();
    }

    @Test
    @DisplayName("every exit code is distinct, so a script can tell them apart")
    void exitCodesAreDistinct() {
        assertThat(java.util.Arrays.stream(ExitCode.values()).map(ExitCode::code).distinct()
            .count()).isEqualTo(ExitCode.values().length);
    }
}
