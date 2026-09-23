package com.eonmux.excalibur.fsck.repair;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Setting a file aside into a quarantine directory on another filesystem.
 *
 * <p>The case a container makes routine: the data directory is one volume, the quarantine
 * another, and a rename between two mounts is refused whatever lies beneath them. The
 * refusal is simulated rather than produced, because no test can count on two filesystems
 * being available; everything after it - the copy, the comparison, the removal of the
 * original - happens on the real filesystem and is asserted there.</p>
 */
class QuarantineAcrossFilesystemsTest {

    /** A rename that is refused the way a rename across mounts is. */
    private static final Quarantine.Renamer ACROSS_MOUNTS = (source, target) -> {
        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
            "Invalid cross-device link");
    };

    @Test
    void aFileIsCopiedVerifiedAndOnlyThenRemoved(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_3.dat");
        byte[] content = new byte[256 * 1024];
        new Random(7L).nextBytes(content);
        Files.write(file, content);

        Path taken = new Quarantine(dir.resolve("q"), ACROSS_MOUNTS)
            .take(file, "segment_3.dat");

        assertThat(taken).isEqualTo(dir.resolve("q/segment_3.dat"));
        assertThat(taken).hasBinaryContent(content);
        assertThat(file).doesNotExist();
        try (var left = Files.list(dir.resolve("q"))) {
            assertThat(left.map(p -> p.getFileName().toString()))
                .as("the staged copy is published under its final name, not left beside it")
                .containsExactly("segment_3.dat");
        }
    }

    @Test
    void aTakenNameIsStillNeverOverwritten(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Quarantine quarantine = new Quarantine(dir.resolve("q"), ACROSS_MOUNTS);
        Files.writeString(store.resolve("routing.dir"), "first");
        Path first = quarantine.take(store.resolve("routing.dir"), "routing.dir");
        Files.writeString(store.resolve("routing.dir"), "second");

        Path second = quarantine.take(store.resolve("routing.dir"), "routing.dir");

        assertThat(first).hasContent("first");
        assertThat(second).isNotEqualTo(first).hasContent("second");
    }

    @Test
    void aCopyThatCannotBeWrittenLeavesTheOriginalInPlace(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_4.wal");
        Files.writeString(file, "evidence");
        Path quarantineDir = Files.createDirectories(dir.resolve("q"));
        Files.setPosixFilePermissions(quarantineDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Quarantine quarantine = new Quarantine(quarantineDir, ACROSS_MOUNTS);

            assertThatThrownBy(() -> quarantine.take(file, "segment_4.wal"))
                .isInstanceOf(IOException.class);

            assertThat(file).as("nothing is removed until its copy is proven")
                .hasContent("evidence");
        } finally {
            Files.setPosixFilePermissions(quarantineDir,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aRenameThatWorksIsUsedAndNothingIsCopied(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_5.dat");
        Files.writeString(file, "x");
        AtomicInteger renames = new AtomicInteger();

        Path taken = new Quarantine(dir.resolve("q"), (source, target) -> {
            renames.incrementAndGet();
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }).take(file, "segment_5.dat");

        assertThat(renames).hasValue(1);
        assertThat(taken).hasContent("x");
        assertThat(file).doesNotExist();
    }

    @Test
    void aReadOnlyFileIsCopiedForcedAndRemoved(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_6.hnsw");
        byte[] content = new byte[4096];
        new Random(11L).nextBytes(content);
        Files.write(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));

        Path taken = new Quarantine(dir.resolve("q"), ACROSS_MOUNTS).take(file, "segment_6.hnsw");

        assertThat(taken).hasBinaryContent(content);
        assertThat(Files.getPosixFilePermissions(taken))
            .as("the copy keeps the original's attributes, read-only included")
            .isEqualTo(PosixFilePermissions.fromString("r--r--r--"));
        assertThat(file).doesNotExist();
    }

    @Test
    void everyDirectoryItCreatesIsForcedBeforeAnythingIsPutInside(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_7.dat");
        Files.writeString(file, "evidence");
        Path quarantineDir = dir.resolve("a/b/q");
        List<String> events = new ArrayList<>();

        new Quarantine(quarantineDir, ACROSS_MOUNTS, recording(events)).take(file,
            "segment_7.dat");

        assertThat(events).as("the parent of the first directory created, then each one created, "
                + "all before the copy is published")
            .startsWith("force " + dir, "force " + dir.resolve("a"),
                "force " + dir.resolve("a/b"), "force " + quarantineDir);
    }

    @Test
    void aDirectoryThatAlreadyExistedIsNotForcedForItsCreation(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_8.dat");
        Files.writeString(file, "evidence");
        Path quarantineDir = Files.createDirectories(dir.resolve("q"));
        List<String> events = new ArrayList<>();

        new Quarantine(quarantineDir, ACROSS_MOUNTS, recording(events)).take(file,
            "segment_8.dat");

        assertThat(events).doesNotContain("force " + dir);
    }

    @Test
    void theOriginalsRemovalIsForcedAfterTheCopyIsPublished(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_9.dat");
        Files.writeString(file, "evidence");
        Path quarantineDir = Files.createDirectories(dir.resolve("q"));
        List<String> events = new ArrayList<>();

        Path taken = new Quarantine(quarantineDir, ACROSS_MOUNTS, directory -> {
            events.add("force " + directory + (Files.exists(file) ? " (original present)"
                : " (original gone)") + (Files.exists(quarantineDir.resolve("segment_9.dat"))
                ? " (copy published)" : ""));
            Quarantine.forceDirectory(directory);
        }).take(file, "segment_9.dat");

        assertThat(taken).hasContent("evidence");
        assertThat(events).containsExactly(
            "force " + quarantineDir + " (original present) (copy published)",
            "force " + store + " (original gone) (copy published)");
    }

    @Test
    void aRenameIsForcedInBothDirectories(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_10.dat");
        Files.writeString(file, "x");
        Path quarantineDir = Files.createDirectories(dir.resolve("q"));
        List<String> events = new ArrayList<>();

        new Quarantine(quarantineDir, (source, target) -> {
            events.add("rename " + source.getFileName());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }, recording(events)).take(file, "segment_10.dat");

        assertThat(events).containsExactly("rename segment_10.dat", "force " + quarantineDir,
            "force " + store);
    }

    @Test
    void anOriginalThatWillNotGoIsReportedWithItsCopyNamed(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_11.dat");
        Files.writeString(file, "evidence");
        Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(store),
                "a process that writes through directory permissions (root) cannot be refused");
            Quarantine quarantine = new Quarantine(dir.resolve("q"), ACROSS_MOUNTS);

            assertThatThrownBy(() -> quarantine.take(file, "segment_11.dat"))
                .isInstanceOfSatisfying(PartialQuarantineException.class, e -> {
                    assertThat(e.copy()).isEqualTo(dir.resolve("q/segment_11.dat"));
                    assertThat(e.getMessage()).contains(e.copy().toString(), "still in place");
                });

            assertThat(file).hasContent("evidence");
            assertThat(dir.resolve("q/segment_11.dat")).hasContent("evidence");
        } finally {
            Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aRenameThatCannotBeForcedIsReportedAsMovedNotUntouched(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("data"));
        Path file = store.resolve("segment_12.dat");
        Files.writeString(file, "x");
        Path quarantineDir = Files.createDirectories(dir.resolve("q"));
        Quarantine quarantine = new Quarantine(quarantineDir,
            (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
            directory -> {
                throw new IOException("injected");
            });

        assertThatThrownBy(() -> quarantine.take(file, "segment_12.dat"))
            .isInstanceOfSatisfying(PartialQuarantineException.class, e ->
                assertThat(e.getMessage()).contains("moved to " + e.copy(), "injected"));
    }

    /**
     * A directory sync that records each directory it is handed, and then forces it.
     *
     * @param events Where each call is recorded
     * @return The sync
     */
    private static Quarantine.DirectorySync recording(List<String> events) {
        return directory -> {
            events.add("force " + directory);
            Quarantine.forceDirectory(directory);
        };
    }
}
