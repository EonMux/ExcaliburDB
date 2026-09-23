package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * A heap dump the JVM wrote into the data directory.
 *
 * <p>{@code -XX:+HeapDumpOnOutOfMemoryError} writes {@code java_pid<N>.hprof} wherever
 * {@code -XX:HeapDumpPath} points, and a node started with its working directory as the dump
 * path leaves one beside its segments. It is not engine state: nothing reads it, nothing
 * checksums it, and calling it unverified data would make {@code --strict} fail every node
 * that has ever run out of heap until someone deletes the evidence of why. It is still
 * reported, with its size, because a multi-gigabyte file in a data directory is something an
 * operator should see.</p>
 */
class DiagnosticDumpTest {

    /** The name the JVM gives a heap dump. */
    private static final String DUMP = "java_pid4242.hprof";

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

    private static Path writeDump(Path directory, String name) throws IOException {
        Path dump = directory.resolve(name);
        Files.createDirectories(dump.getParent());
        Files.write(dump, new byte[4096]);
        return dump;
    }

    @Test
    void aHeapDumpIsClassifiedAsADiagnosticDumpWhereverItSits() {
        assertThat(FileClassifier.classify(DUMP)).isEqualTo(FileKind.DIAGNOSTIC_DUMP);
        assertThat(FileClassifier.classify("shared/graph-staging/" + DUMP))
            .isEqualTo(FileKind.DIAGNOSTIC_DUMP);
        assertThat(FileClassifier.classify("chunk-index/" + DUMP))
            .as("a dump is a dump even inside the lexical store's directory")
            .isEqualTo(FileKind.DIAGNOSTIC_DUMP);
    }

    @Test
    void aHeapDumpIsReportedAsNotDataWithItsSize(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        writeDump(dataDir, DUMP);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        FileCheck dump = scan.files().stream()
            .filter(file -> file.relative().equals(DUMP)).findFirst().orElseThrow();
        assertThat(dump.status()).isEqualTo(CheckStatus.NOT_DATA);
        assertThat(dump.sizeBytes()).isEqualTo(4096);
        assertThat(dump.detail()).contains("heap dump");
        assertThat(dump.status().isDamage()).isFalse();
        assertThat(dump.status().isProven()).isFalse();
        assertThat(scan.statusCounts().get(CheckStatus.NOT_DATA)).isEqualTo(1L);
    }

    @Test
    void aHeapDumpDoesNotMakeADirectoryUnverified(@TempDir Path tempDir) throws IOException {
        Path withDump = tempDir.resolve("with");
        Path without = tempDir.resolve("without");
        DataDirectoryFixture.createStore(withDump, 8);
        DataDirectoryFixture.createStore(without, 8);
        writeDump(withDump, DUMP);

        assertThat(new DataDirectoryScanner(withDump).scan().hasUnverified())
            .isEqualTo(new DataDirectoryScanner(without).scan().hasUnverified());
    }

    @Test
    void strictDoesNotFailOnAHeapDump(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        writeDump(dataDir, DUMP);

        Invocation result = invoke(dataDir.toString(), "--strict");

        assertThat(result.exitCode())
            .as("a heap dump is not unverified data\n%s%s", result.out(), result.err())
            .isEqualTo(FsckExit.CLEAN);
        assertThat(result.out()).contains(DUMP).contains("NOT_DATA");
        assertThat(result.out()).contains("1 not engine data");
    }

    @Test
    void strictStillFailsOnAFileNothingRecognises(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        writeDump(dataDir, "java_pid4242.hprof.notes");

        Invocation result = invoke(dataDir.toString(), "--strict");

        assertThat(result.exitCode())
            .as("only the dump's own name is exempt, not anything that mentions it")
            .isEqualTo(FsckExit.DAMAGE);
    }

    @Test
    void theJsonReportCountsDumpsOnTheirOwn(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        writeDump(dataDir, DUMP);

        Invocation result = invoke(dataDir.toString(), "--json", "--strict");

        JsonNode root = new ObjectMapper().readTree(result.out());
        assertThat(root.path("summary").path("notData").asInt()).isEqualTo(1);
        assertThat(root.path("summary").path("unchecked").asInt()).isZero();
        assertThat(root.path("files").get(0).path("status").asText()).isEqualTo("NOT_DATA");
        assertThat(root.path("exitCode").asInt()).isEqualTo(FsckExit.CLEAN);
    }

    @Test
    void noRepairIsPlannedForAHeapDump(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        writeDump(dataDir, DUMP);

        var plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.needsOperator()).isEmpty();
    }
}
