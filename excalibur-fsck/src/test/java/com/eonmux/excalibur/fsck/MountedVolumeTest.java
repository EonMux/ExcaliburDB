package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A data directory that is the root of a mounted volume, and the {@code lost+found} in it.
 *
 * <p>The directory belongs to the filesystem, is owned by root and cannot be read by the node's
 * user. A check that walked into it would fail before it had looked at a single segment, and one
 * that could read it would report what {@code e2fsck} put there as unrecognised files of the
 * store. Neither is right: it is not the store's, so it is not surveyed, not reported and not
 * taken for a namespace.</p>
 *
 * <p>Root ownership is stood in for by taking every permission away, which its owner then cannot
 * list either; where the tests run as a user who can read anything, that check is skipped.</p>
 */
class MountedVolumeTest {

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
    void anUnreadableLostAndFoundIsNotSurveyed(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        Invocation without = invoke(dataDir.toString(), "--strict");
        Path lostAndFound = Files.createDirectories(dataDir.resolve("lost+found"));
        Files.writeString(lostAndFound.resolve("#131073"), "recovered");

        Invocation with;
        Files.setPosixFilePermissions(lostAndFound, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(lostAndFound), "running as a user who can read anything");
            with = invoke(dataDir.toString(), "--strict");
        } finally {
            Files.setPosixFilePermissions(lostAndFound, PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(with.exitCode())
            .as("the volume's own directory neither fails the survey nor counts against it")
            .isEqualTo(without.exitCode())
            .isNotEqualTo(FsckExit.FAILED);
        assertThat(with.out()).doesNotContain("lost+found");
        assertThat(with.err()).doesNotContain("lost+found");
    }

    @Test
    void aReadableLostAndFoundIsNotReportedOrTakenForANamespace(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        Files.writeString(Files.createDirectories(dataDir.resolve("lost+found"))
            .resolve("#131073"), "recovered");
        Files.writeString(Files.createDirectories(dataDir.resolve("lost+found/graph"))
            .resolve("edgestore.meta"), "not a graph");

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(scan.files()).extracting(FileCheck::relative)
            .noneMatch(path -> path.startsWith("lost+found"));
        assertThat(scan.findings()).noneMatch(finding -> finding.subject().contains("lost+found")
            || finding.detail().contains("lost+found"));
        assertThat(FileInspector.graphStores(dataDir))
            .as("a directory with a graph/ inside it is still not a namespace when it is the "
                + "filesystem's")
            .isEmpty();
        assertThat(invoke(dataDir.toString()).exitCode()).isEqualTo(FsckExit.CLEAN);
    }

    @Test
    void lostAndFoundBelowTheRootIsStillSurveyed(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        Files.writeString(Files.createDirectories(dataDir.resolve("shared/lost+found"))
            .resolve("x"), "x");

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(scan.files()).extracting(FileCheck::relative).contains("shared/lost+found/x");
    }
}
