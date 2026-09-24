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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

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
        return runWith(null, args);
    }

    /**
     * Runs the command, through a stand-in for the subcommands when one is given.
     *
     * @param command What stands in for the subcommands, or null for the real ones
     * @param args    What was typed
     * @return The outcome
     */
    private static Run runWith(BackupCli.Command command, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = (command == null
                ? BackupCli.run(args, outStream, errStream)
                : BackupCli.run(args, outStream, errStream, command)).code();
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
    @DisplayName("restoring onto a fresh volume holding only its lost+found needs no --force")
    void aFreshVolumeNeedsNoForce() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", snapshotDir.toString());
        Path target = Files.createDirectories(tempDir.resolve("volume"));
        DataDirectoryFixture.writeRandom(target.resolve("lost+found/#12"), 64, 6L);

        Run restore = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", target.toString());

        assertThat(restore.code()).isEqualTo(ExitCode.OK.code());
        assertThat(target.resolve("lost+found/#12")).exists();
        assertThat(target.resolve("catalog.dat")).exists();
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

    @Test
    @DisplayName("--help after a subcommand prints that subcommand's usage and succeeds")
    void helpAfterASubcommandIsAnswered() {
        Run snapshot = run("snapshot", "--help");
        Run verify = run("verify", "--snapshot", tempDir.toString(), "-h");
        Run restore = run("restore", "--target", tempDir.toString(), "--help");

        assertThat(snapshot.code()).isEqualTo(ExitCode.OK.code());
        assertThat(snapshot.err()).isEmpty();
        assertThat(snapshot.out()).contains("--data-dir", "--exclude-derived", "--allow-live",
            "EXIT CODES").doesNotContain("--target");
        assertThat(verify.code()).isEqualTo(ExitCode.OK.code());
        assertThat(verify.out()).contains("verify", "--deep").doesNotContain("--data-dir");
        assertThat(restore.code()).isEqualTo(ExitCode.OK.code());
        assertThat(restore.out()).contains("--target", "--force").doesNotContain("--data-dir");
    }

    @Test
    @DisplayName("--help is answered even after an option the subcommand does not take")
    void helpWinsOverAnUnknownOption() {
        Run run = run("verify", "--frobnicate", "--help");

        assertThat(run.code()).isEqualTo(ExitCode.OK.code());
        assertThat(run.out()).contains("--deep");
    }

    @Test
    @DisplayName("a value that happens to read -h is a value, not a request for help")
    void anOptionValueIsNotHelp() {
        Run run = run("verify", "--snapshot", "-h");

        assertThat(run.code()).as("-h is the snapshot directory, which does not exist")
            .isEqualTo(ExitCode.VERIFICATION_FAILED.code());
        assertThat(run.err()).contains("-h");
        assertThat(run.out()).doesNotContain("USAGE");
    }

    @Test
    @DisplayName("--help after a subcommand does nothing but print")
    void helpAfterASubcommandTouchesNothing() {
        Path out = tempDir.resolve("snap");

        Run run = run("snapshot", "--data-dir", tempDir.resolve("data").toString(),
            "--out", out.toString(), "--help");

        assertThat(run.code()).isEqualTo(ExitCode.OK.code());
        assertThat(out).doesNotExist();
    }

    @Test
    @DisplayName("--help after a command this build does not know is still a usage error")
    void helpAfterAnUnknownCommandIsAUsageError() {
        Run run = run("snapshto", "--help");

        assertThat(run.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(run.err()).contains("unknown command: snapshto")
            .contains("snapshot", "verify", "restore");
    }

    @Test
    @DisplayName("a usage error in a subcommand prints that subcommand's usage")
    void aUsageErrorPrintsTheSubcommandsUsage() {
        Run run = run("restore", "--snapshot", tempDir.toString());

        assertThat(run.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(run.err()).contains("--target is required").contains("--force")
            .doesNotContain("--data-dir");
    }

    @Test
    @DisplayName("a path the platform cannot represent is a usage error, not a crash")
    void anUnrepresentablePathIsAUsageError() {
        Run run = run("verify", "--snapshot", "snap\0shot");

        assertThat(run.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(run.err()).contains("--snapshot").doesNotContain("unanticipated failure:");
    }

    @Test
    @DisplayName("a snapshot into its own data directory is a usage error, not a crash")
    void aSnapshotInsideItsSourceIsAUsageError() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));

        Run run = run("snapshot", "--data-dir", dataDir.toString(),
            "--out", dataDir.resolve("backup").toString());

        assertThat(run.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(run.err()).contains("inside the data directory")
            .doesNotContain("unanticipated failure:");
        assertThat(dataDir.resolve("backup")).doesNotExist();
    }

    @Test
    @DisplayName("a restore into the snapshot it reads is a usage error, not a crash")
    void aRestoreInsideItsSnapshotIsAUsageError() {
        Run run = run("restore", "--snapshot", tempDir.toString(),
            "--target", tempDir.resolve("inside").toString());

        assertThat(run.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(run.err()).contains("cannot write into the snapshot")
            .doesNotContain("unanticipated failure:");
    }

    @Test
    @DisplayName("a data directory written to during the copy exits 5, and --allow-live 0")
    void aRunningNodeIsRefusedWithItsOwnCode() throws Exception {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));

        Run refused = whileSomethingWrites(dataDir, () -> run("snapshot",
            "--data-dir", dataDir.toString(), "--out", tempDir.resolve("snap").toString()));
        assertThat(refused.code()).as(refused.err()).isEqualTo(ExitCode.NOT_QUIESCED.code());
        assertThat(refused.err()).contains("--allow-live");
        assertThat(tempDir.resolve("snap")).doesNotExist();

        Run allowed = whileSomethingWrites(dataDir, () -> run("snapshot",
            "--data-dir", dataDir.toString(), "--out", tempDir.resolve("live").toString(),
            "--allow-live"));
        assertThat(allowed.code()).as(allowed.err()).isEqualTo(ExitCode.OK.code());
        assertThat(allowed.out()).contains("LIVE_UNSAFE");
    }

    @Test
    @DisplayName("a snapshot of, or a restore into, a directory a node holds exits 7")
    void aHeldDirectoryHasItsOwnCode() throws Exception {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        assertThat(run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString()).code()).isEqualTo(ExitCode.OK.code());

        try (var ignored = com.eonmux.excalibur.core.datadir.DataDirectoryLock
                 .acquire(dataDir, "excalibur node")) {
            Run snapshot = run("snapshot", "--data-dir", dataDir.toString(),
                "--out", tempDir.resolve("again").toString());
            assertThat(snapshot.code()).as(snapshot.err()).isEqualTo(ExitCode.IN_USE.code());
            assertThat(snapshot.err()).contains("excalibur node").contains(dataDir.toString());

            Run restore = run("restore", "--snapshot", snapshotDir.toString(),
                "--target", dataDir.toString(), "--force");
            assertThat(restore.code()).as(restore.err()).isEqualTo(ExitCode.IN_USE.code());
            assertThat(restore.err()).contains("excalibur node");
        }
    }

    @Test
    @DisplayName("an unanticipated failure exits 70 and says where it came from")
    void anUnanticipatedFailureHasItsOwnCode() {
        Run run = runWith((args, out, err) -> {
            throw new IllegalStateException("a defect in this build");
        }, "verify", "--snapshot", tempDir.toString());

        assertThat(run.code()).isEqualTo(ExitCode.INTERNAL_ERROR.code());
        assertThat(run.err()).contains("unanticipated failure")
            .contains("a defect in this build")
            .contains("at com.eonmux.excalibur.backup.cli");
    }

    @Test
    @DisplayName("verify --deep walks every format and says how much of it was proven")
    void deepVerifyReportsWhatItProved() throws IOException {
        Path dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        assertThat(run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString()).code()).isEqualTo(ExitCode.OK.code());

        Run verify = run("verify", "--snapshot", snapshotDir.toString(), "--deep");

        assertThat(verify.code()).as(verify.err()).isEqualTo(ExitCode.OK.code());
        assertThat(verify.out()).contains("DEEP")
            .containsPattern("formats  \\d+ checked: \\d+ proven against their own checksums")
            .contains("unproven")
            .contains(DataDirectoryFixture.IN_FLIGHT_JOURNAL);
    }

    @Test
    @DisplayName("verify --deep fails an image whose damage was copied faithfully")
    void deepVerifyFailsWhatAChecksumCannotSee() throws IOException {
        Path dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
        DataDirectoryFixture.flipAByte(dataDir.resolve(DataDirectoryFixture.GRAPH));
        Path snapshotDir = tempDir.resolve("snap");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", snapshotDir.toString());

        Run shallow = run("verify", "--snapshot", snapshotDir.toString());
        Run deep = run("verify", "--snapshot", snapshotDir.toString(), "--deep");

        assertThat(shallow.code()).isEqualTo(ExitCode.OK.code());
        assertThat(deep.code()).isEqualTo(ExitCode.VERIFICATION_FAILED.code());
        assertThat(deep.err()).contains(DataDirectoryFixture.GRAPH).contains("integrity");
    }

    @Test
    @DisplayName("restore --deep restores a sound image and refuses a damaged one")
    void deepRestoreFollowsTheDeepVerdict() throws IOException {
        Path dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", snapshotDir.toString());

        Run restored = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", tempDir.resolve("restored").toString(), "--deep");
        assertThat(restored.code()).as(restored.err()).isEqualTo(ExitCode.OK.code());
        assertThat(restored.out()).contains("restored");

        DataDirectoryFixture.flipAByte(dataDir.resolve(DataDirectoryFixture.CODEBOOK));
        Path damagedSnapshot = tempDir.resolve("snap-damaged");
        run("snapshot", "--data-dir", dataDir.toString(), "--out", damagedSnapshot.toString());
        Path target = tempDir.resolve("refused");

        Run refused = run("restore", "--snapshot", damagedSnapshot.toString(),
            "--target", target.toString(), "--deep");
        assertThat(refused.code()).isEqualTo(ExitCode.VERIFICATION_FAILED.code());
        assertThat(refused.err()).contains(DataDirectoryFixture.CODEBOOK);
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("verify --deep and restore --deep accept an image holding a torn compaction")
    void aTornCompactionPairPassesTheDeepChecks() throws IOException {
        Path dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
        byte[] graph = Files.readAllBytes(dataDir.resolve(DataDirectoryFixture.GRAPH));
        byte[] mapping =
            Files.readAllBytes(dataDir.resolve(DataDirectoryFixture.DOCUMENT_MAPPING));
        Files.write(dataDir.resolve("segment_0.hnsw.compact"),
            java.util.Arrays.copyOf(graph, graph.length / 2));
        Files.write(dataDir.resolve("segment_0.hnsw.compact.docmap"),
            java.util.Arrays.copyOf(mapping, mapping.length / 2));
        Path snapshotDir = tempDir.resolve("snap");
        assertThat(run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString()).code()).isEqualTo(ExitCode.OK.code());

        Run verify = run("verify", "--snapshot", snapshotDir.toString(), "--deep");
        Path target = tempDir.resolve("restored");
        Run restore = run("restore", "--snapshot", snapshotDir.toString(),
            "--target", target.toString(), "--deep");

        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(ExitCode.OK.code());
        assertThat(restore.code()).as(restore.out() + restore.err())
            .isEqualTo(ExitCode.OK.code());
        assertThat(target.resolve("segment_0.hnsw.compact.docmap")).exists();
    }

    @Test
    @DisplayName("a heap dump in the data directory is left out of the image, and says so")
    void aHeapDumpIsOmittedAndReported() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        DataDirectoryFixture.writeRandom(dataDir.resolve("java_pid4242.hprof"), 8192, 77L);
        Path snapshotDir = tempDir.resolve("snap");

        Run snapshot = run("snapshot", "--data-dir", dataDir.toString(),
            "--out", snapshotDir.toString());

        assertThat(snapshot.code()).isEqualTo(ExitCode.OK.code());
        assertThat(snapshot.out()).contains("omitted  1 file(s) classed DIAGNOSTIC");
        assertThat(snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
            .resolve("java_pid4242.hprof")).doesNotExist();
        assertThat(run("verify", "--snapshot", snapshotDir.toString()).code())
            .isEqualTo(ExitCode.OK.code());
    }

    /**
     * Runs a command while another thread keeps appending to a file in the data directory.
     *
     * <p>Standing in for a running node. The file written is a staged name, which a snapshot
     * never copies, so the copy itself cannot trip over it; what does see it is the
     * fingerprint taken before and after the copy, which is the check a running node is
     * caught by. The writer is confirmed to be running before the command starts and
     * appends without pausing, so its file is certain to have grown across a copy that
     * digests a 64 MiB write-ahead log.</p>
     *
     * @param dataDir The directory to write into
     * @param command The command to run meanwhile
     * @return What the command did
     * @throws Exception if the writer failed or the command threw
     */
    private static Run whileSomethingWrites(Path dataDir, Callable<Run> command)
        throws Exception {
        Path scratch = dataDir.resolve("segment_0.dat.tmp");
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().start(() -> {
            try (FileChannel channel = FileChannel.open(scratch, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (running.get()) {
                    channel.write(ByteBuffer.wrap(new byte[] { 1 }));
                    started.countDown();
                }
            } catch (IOException e) {
                started.countDown();
                throw new java.io.UncheckedIOException(e);
            }
        });
        try {
            started.await();
            return command.call();
        } finally {
            running.set(false);
            writer.join();
            Files.deleteIfExists(scratch);
        }
    }
}
