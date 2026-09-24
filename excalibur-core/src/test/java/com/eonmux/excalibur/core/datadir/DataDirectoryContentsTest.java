package com.eonmux.excalibur.core.datadir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Which entries of a data directory hold data, and which belong to the filesystem or to the
 * processes coordinating over it.
 *
 * <p>A data directory that is the root of a mounted filesystem holds the {@code lost+found} the
 * filesystem was made with, owned by root and unreadable to the node's user. Every tool that
 * walks a data directory has to pass over it without opening it, or the walk fails before it
 * has looked at any data.</p>
 */
@DisplayName("DataDirectoryContents and FilesystemEntries")
class DataDirectoryContentsTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("lost+found is the filesystem's only as a directory directly under the root")
    void lostAndFoundIsRecognisedAsADirectory() throws IOException {
        Path lostAndFound = Files.createDirectory(directory.resolve("lost+found"));
        Path file = Files.createDirectories(directory.resolve("shared"))
            .resolve(FilesystemEntries.LOST_AND_FOUND);
        Files.writeString(file, "x");

        assertThat(FilesystemEntries.isFilesystemEntry(lostAndFound)).isTrue();
        assertThat(FilesystemEntries.isFilesystemEntry(file))
            .as("a regular file of that name is not what mkfs makes").isFalse();
        assertThat(FilesystemEntries.isFilesystemEntry(directory.resolve("segment_0.dat")))
            .isFalse();
    }

    @Test
    @DisplayName("a symbolic link named lost+found is not the filesystem's")
    void aLinkNamedLostAndFoundIsNotTheFilesystems() throws IOException {
        Path elsewhere = Files.createDirectories(directory.resolve("elsewhere"));
        Path root = Files.createDirectories(directory.resolve("root"));
        Path link = Files.createSymbolicLink(root.resolve("lost+found"), elsewhere);

        assertThat(FilesystemEntries.isFilesystemEntry(link)).isFalse();
    }

    @Test
    @DisplayName("an image path under lost+found names something the filesystem owned")
    void imagePathsUnderLostAndFound() {
        assertThat(FilesystemEntries.isWithinFilesystemEntry("lost+found/#12")).isTrue();
        assertThat(FilesystemEntries.isWithinFilesystemEntry("lost+found/a/b")).isTrue();
        assertThat(FilesystemEntries.isWithinFilesystemEntry("lost+found"))
            .as("a regular file of that name was never a directory").isFalse();
        assertThat(FilesystemEntries.isWithinFilesystemEntry("shared/lost+found/x")).isFalse();
        assertThat(FilesystemEntries.isWithinFilesystemEntry("segment_0.dat")).isFalse();
    }

    @Test
    @DisplayName("the entries of a data directory leave out lost+found and the reserved names")
    void entriesLeaveOutWhatIsNotData() throws IOException {
        Files.createDirectory(directory.resolve("lost+found"));
        Files.writeString(directory.resolve(DataDirectoryLock.FILE_NAME), "x");
        Files.createDirectory(directory.resolve(InterruptedRestore.STAGING_PREFIX + "a"));
        Files.writeString(directory.resolve("segment_0.dat"), "x");
        Files.createDirectory(directory.resolve("shared"));

        assertThat(names(DataDirectoryContents.entries(directory)))
            .containsExactly("segment_0.dat", "shared");
        assertThat(DataDirectoryContents.holdsData(directory)).isTrue();
    }

    @Test
    @DisplayName("a mount root holding only lost+found and a lock file holds no data")
    void aFreshMountRootHoldsNoData() throws IOException {
        Files.createDirectory(directory.resolve("lost+found"));
        Files.writeString(directory.resolve(DataDirectoryLock.FILE_NAME), "x");

        assertThat(DataDirectoryContents.entries(directory)).isEmpty();
        assertThat(DataDirectoryContents.holdsData(directory)).isFalse();
    }

    @Test
    @DisplayName("an unreadable lost+found is passed over without being opened")
    void anUnreadableLostAndFoundIsNotOpened() throws IOException {
        Path lostAndFound = Files.createDirectory(directory.resolve("lost+found"));
        Files.writeString(lostAndFound.resolve("#12"), "recovered");
        Files.writeString(directory.resolve("catalog.dat"), "x");
        Files.writeString(Files.createDirectories(directory.resolve("shared/graph-staging"))
            .resolve("facts.log"), "x");
        Files.writeString(Files.createDirectories(
            directory.resolve(InterruptedRestore.DISPLACED_PREFIX + "a")).resolve("x"), "x");
        Files.setPosixFilePermissions(lostAndFound, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(lostAndFound), "running as a user who can read anything");

            List<Path> files = DataDirectoryContents.regularFiles(directory);

            assertThat(files).extracting(file ->
                    directory.relativize(file).toString().replace('\\', '/'))
                .containsExactlyInAnyOrder("catalog.dat", "shared/graph-staging/facts.log");
        } finally {
            Files.setPosixFilePermissions(lostAndFound, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("a readable lost+found's files are not listed either")
    void aReadableLostAndFoundIsNotListed() throws IOException {
        Files.writeString(Files.createDirectories(directory.resolve("lost+found")).resolve("#12"),
            "recovered");
        Files.writeString(directory.resolve("catalog.dat"), "x");

        assertThat(DataDirectoryContents.regularFiles(directory))
            .containsExactly(directory.resolve("catalog.dat"));
    }

    @Test
    @DisplayName("lost+found below the root is data like any other directory")
    void lostAndFoundBelowTheRootIsData() throws IOException {
        Path nested = Files.createDirectories(directory.resolve("shared/lost+found"));
        Files.writeString(nested.resolve("x"), "x");

        assertThat(DataDirectoryContents.regularFiles(directory))
            .containsExactly(nested.resolve("x"));
    }

    @Test
    @DisplayName("a regular file named lost+found at the root is data")
    void aFileNamedLostAndFoundIsData() throws IOException {
        Files.writeString(directory.resolve("lost+found"), "x");

        assertThat(names(DataDirectoryContents.entries(directory))).containsExactly("lost+found");
        assertThat(DataDirectoryContents.regularFiles(directory))
            .containsExactly(directory.resolve("lost+found"));
    }

    private static List<String> names(List<Path> entries) {
        return entries.stream().map(entry -> entry.getFileName().toString()).toList();
    }
}
