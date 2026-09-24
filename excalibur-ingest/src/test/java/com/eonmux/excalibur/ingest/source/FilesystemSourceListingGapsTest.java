package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A file the walk cannot judge is a gap in the listing, not a file that is gone.
 */
class FilesystemSourceListingGapsTest {

    @TempDir
    Path root;

    private Path vault;

    @AfterEach
    void restorePermissions() throws IOException {
        if (vault != null) {
            Files.setPosixFilePermissions(vault, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static List<String> crawl(FilesystemSource source) throws SourceException {
        try (Stream<String> listing = source.crawl()) {
            return listing.toList();
        }
    }

    private FilesystemSource followingLinks() {
        return FilesystemSource.builder()
            .sourceId("files")
            .rootPath(root)
            .followSymlinks(true)
            .excludedDirectories("vault")
            .build();
    }

    @Test
    void aLinkWhoseTargetCannotBeReadIsAGap() throws IOException, SourceException {
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.writeString(root.resolve("readable.txt"), "text");
        vault = Files.createDirectory(root.resolve("vault"));
        Files.writeString(vault.resolve("secret.txt"), "text");
        Files.createSymbolicLink(root.resolve("link.txt"), Path.of("vault", "secret.txt"));
        Files.setPosixFilePermissions(vault, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(vault.resolve("secret.txt")),
            "permissions are not enforced for this user");

        FilesystemSource source = followingLinks();
        List<String> listed = crawl(source);

        assertThat(listed).containsExactly("readable.txt");
        ListingGaps gaps = source.getListingGaps();
        assertThat(gaps.count()).isEqualTo(1);
        assertThat(gaps.sample().getFirst().location()).isEqualTo("link.txt");
        assertThat(gaps.sample().getFirst().reason()).startsWith("attributes not read: ");
        assertThat(source.getDocumentCount())
            .as("counting is not a crawl, and leaves the crawl's gaps alone")
            .isEqualTo(1);
        assertThat(source.getListingGaps()).isEqualTo(gaps);
    }

    @Test
    void aLinkToNothingIsGoneNotAGap() throws IOException, SourceException {
        Files.writeString(root.resolve("readable.txt"), "text");
        Files.createSymbolicLink(root.resolve("dangling.txt"), Path.of("nowhere.txt"));

        FilesystemSource source = followingLinks();

        assertThat(crawl(source)).containsExactly("readable.txt");
        assertThat(source.getListingGaps().isComplete()).isTrue();
    }

    @Test
    void aFileOfAnExcludedTypeIsNeverAGap() throws IOException, SourceException {
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        vault = Files.createDirectory(root.resolve("vault"));
        Files.writeString(vault.resolve("secret.bin"), "bytes");
        Files.createSymbolicLink(root.resolve("link.bin"), Path.of("vault", "secret.bin"));
        Files.setPosixFilePermissions(vault, PosixFilePermissions.fromString("---------"));

        FilesystemSource source = FilesystemSource.builder()
            .sourceId("files")
            .rootPath(root)
            .followSymlinks(true)
            .excludedDirectories("vault")
            .includedExtensions(".txt")
            .build();

        assertThat(crawl(source)).isEmpty();
        assertThat(source.getListingGaps().isComplete()).isTrue();
    }
}
