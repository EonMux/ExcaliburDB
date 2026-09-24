package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("PrunedWalk")
class PrunedWalkTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.createDirectories(root.resolve("keep/inner"));
        Files.writeString(root.resolve("keep/b.txt"), "b");
        Files.writeString(root.resolve("keep/inner/c.txt"), "c");
        Files.createDirectories(root.resolve("skip"));
        Files.writeString(root.resolve("skip/d.txt"), "d");
    }

    private List<String> walk(int maxDepth, boolean followLinks) throws IOException {
        try (Stream<Path> paths = PrunedWalk.of(root, maxDepth, followLinks,
                dir -> !dir.getFileName().toString().equals("skip"))) {
            return paths.map(path -> root.relativize(path).toString().replace('\\', '/')).toList();
        }
    }

    @Test
    @DisplayName("lists everything below the root except what it was told not to enter")
    void prunesRefusedDirectories() throws IOException {
        assertThat(walk(10, false))
            .containsExactlyInAnyOrder("a.txt", "keep/b.txt", "keep/inner/c.txt");
    }

    @Test
    @DisplayName("lists a directory at the depth limit rather than entering it")
    void stopsAtTheDepthLimit() throws IOException {
        assertThat(walk(2, false)).containsExactlyInAnyOrder("a.txt", "keep/b.txt", "keep/inner");
        assertThat(walk(0, false)).isEmpty();
    }

    @Test
    @DisplayName("lists a directory's contents before moving on to its siblings")
    void isDepthFirst() throws IOException {
        List<String> listed = walk(10, false);

        assertThat(Math.abs(listed.indexOf("keep/b.txt") - listed.indexOf("keep/inner/c.txt")))
            .as("nothing outside keep/ comes between two entries under it")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("lists a link that leads nowhere rather than failing on it")
    void listsADanglingLink() throws IOException {
        linkOrSkip(root.resolve("dangling"), root.resolve("gone"));

        assertThat(walk(10, true)).contains("dangling");
    }

    @Test
    @Timeout(30)
    @DisplayName("reports a link back to an ancestor as a loop")
    void reportsACycle() throws IOException {
        linkOrSkip(root.resolve("keep/inner/up"), root.resolve("keep"));

        assertThatThrownBy(() -> walk(10, true))
            .isInstanceOf(UncheckedIOException.class)
            .hasCauseInstanceOf(FileSystemLoopException.class);
    }

    @Test
    @DisplayName("does not enter a pruned directory even when a link loops inside it")
    void prunedCycleIsNeverMet() throws IOException {
        linkOrSkip(root.resolve("skip/up"), root);

        assertThat(walk(10, true))
            .containsExactlyInAnyOrder("a.txt", "keep/b.txt", "keep/inner/c.txt");
    }

    @Test
    @DisplayName("fails on a directory it cannot read rather than listing it as empty")
    void failsOnAnUnreadableDirectory() throws IOException {
        Path locked = root.resolve("keep/inner");
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "this filesystem has no POSIX permissions");
        }
        try {
            assumeTrue(!Files.isReadable(locked), "running with permission to read anything");

            assertThatThrownBy(() -> walk(10, false)).isInstanceOf(UncheckedIOException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static void linkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "this filesystem does not support symbolic links: " + e.getMessage());
        }
    }
}
