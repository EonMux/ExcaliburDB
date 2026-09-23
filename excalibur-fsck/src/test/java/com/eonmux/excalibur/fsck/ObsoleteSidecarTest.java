package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
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
 * A term sketch an earlier build kept beside a segment.
 *
 * <p>Earlier builds wrote {@code segment_N.tfs} for every segment, staged as
 * {@code segment_N.tfs.<unique>.tmp}. Nothing in this build reads either, and the node removes
 * them when it starts, so one is met in a directory an upgraded node has not yet started on.
 * Reporting it as damage would fail the check on every such directory; reporting it as a file
 * nothing recognises would fail {@code --strict} on it. It is neither: it is reported as not
 * engine data, with its size, and left for the node to remove.</p>
 */
class ObsoleteSidecarTest {

    private static final String SKETCH = "segment_0.tfs";
    private static final String STAGED_SKETCH = "segment_0.tfs.8c1f.tmp";

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

    private static void write(Path directory, String name) throws IOException {
        Files.write(directory.resolve(name), new byte[512]);
    }

    @Test
    void bothNamesAreClassifiedAsObsolete() {
        assertThat(FileClassifier.classify(SKETCH)).isEqualTo(FileKind.OBSOLETE);
        assertThat(FileClassifier.classify(STAGED_SKETCH))
            .as("the staging name is the node's to remove, not a half-finished write")
            .isEqualTo(FileKind.OBSOLETE);
        assertThat(FileClassifier.classify("segment_0.tfs.notes"))
            .as("only the names the store wrote are exempt")
            .isEqualTo(FileKind.UNRECOGNISED);
    }

    @Test
    void aSketchIsReportedAsNotDataWithItsSize(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        write(dataDir, SKETCH);
        write(dataDir, STAGED_SKETCH);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        FileCheck sketch = scan.files().stream()
            .filter(file -> file.relative().equals(SKETCH)).findFirst().orElseThrow();
        assertThat(sketch.status()).isEqualTo(CheckStatus.NOT_DATA);
        assertThat(sketch.sizeBytes()).isEqualTo(512);
        assertThat(sketch.detail()).contains("earlier build").contains("removes it");
        assertThat(scan.statusCounts().get(CheckStatus.NOT_DATA)).isEqualTo(2L);
        assertThat(scan.hasDamage()).isFalse();
        assertThat(scan.findings())
            .as("an obsolete file is neither a leftover write nor an orphaned index")
            .noneMatch(finding -> finding.subject().startsWith("segment_0.tfs"));
    }

    @Test
    void aSketchWithoutItsSegmentIsNotAnOrphan(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        write(dataDir, "segment_41.tfs");

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(scan.hasDamage())
            .as("nothing reads the sketch, so its segment being gone says nothing new")
            .isFalse();
    }

    @Test
    void strictDoesNotFailOnASketch(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        write(dataDir, SKETCH);

        Invocation result = invoke(dataDir.toString(), "--strict");

        assertThat(result.exitCode())
            .as("an upgraded directory is not unverified data\n%s%s", result.out(), result.err())
            .isEqualTo(FsckExit.CLEAN);
        assertThat(result.out()).contains(SKETCH).contains("NOT_DATA");
    }

    @Test
    void noRepairIsPlannedForASketch(@TempDir Path tempDir) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        write(dataDir, SKETCH);

        var plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.needsOperator()).isEmpty();
    }
}
