package com.eonmux.excalibur.fsck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a quarantine really is, with every symbolic link and {@code ..} on the way resolved.
 *
 * <p>The question is asked of the filesystem, not of the path's spelling: a path that reads as
 * outside the data directory can land inside it through a link, and a {@code ..} after a link
 * climbs from where the link points, not from where it sits.</p>
 */
class QuarantineLocationTest {

    @Test
    void aSiblingIsOutside(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));

        assertThat(QuarantineLocation.isInside(data, dir.resolve("q"))).isFalse();
        assertThat(QuarantineLocation.isInside(data, dir.resolve("data-q")))
            .as("a name that merely begins with the data directory's is not inside it")
            .isFalse();
    }

    @Test
    void aSubdirectoryIsInsideWhetherOrNotItExists(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.createDirectories(data.resolve("segments"));

        assertThat(QuarantineLocation.isInside(data, data.resolve("q/deeper"))).isTrue();
        assertThat(QuarantineLocation.isInside(data, data.resolve("segments"))).isTrue();
    }

    @Test
    void theDataDirectoryItselfIsInside(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));

        assertThat(QuarantineLocation.isInside(data, data)).isTrue();
        assertThat(QuarantineLocation.isInside(data, dir.resolve("elsewhere/../data"))).isTrue();
    }

    @Test
    void aLinkIntoTheDataDirectoryIsFollowed(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), data);

        assertThat(QuarantineLocation.isInside(data, link.resolve("q"))).isTrue();
        assertThat(QuarantineLocation.isInside(link, dir.resolve("data/q")))
            .as("a data directory named through a link is the directory it points at")
            .isTrue();
    }

    @Test
    void aClimbAfterALinkStartsWhereTheLinkPoints(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path inner = Files.createDirectories(data.resolve("inner"));
        Files.createSymbolicLink(dir.resolve("link"), inner);
        Path spelled = dir.resolve("link").resolve("..").resolve("q");

        assertThat(spelled.normalize())
            .as("spelled out, the path reads as a sibling of the data directory")
            .isEqualTo(dir.resolve("q"));
        assertThat(QuarantineLocation.isInside(data, spelled))
            .as("but the filesystem climbs out of inner/, into the data directory")
            .isTrue();
    }

    @Test
    void aLinkOutOfTheDataDirectoryIsOutside(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path link = Files.createSymbolicLink(data.resolve("away"), outside);

        assertThat(QuarantineLocation.isInside(data, link.resolve("q")))
            .as("files moved there land outside the directory being checked")
            .isFalse();
    }

    @Test
    void theCanonicalFormNamesWhereFilesWouldLand(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), data);

        assertThat(QuarantineLocation.canonical(link.resolve("a/../b")))
            .isEqualTo(data.toRealPath().resolve("b"));
    }

    @Test
    void aDataDirectoryWithNoParentHasNoDefaultOutsideIt() throws IOException {
        Path root = Path.of("/").toRealPath();
        FsckOptions options = FsckOptionsParser.parse(new String[] { root.toString() });

        assertThat(QuarantineLocation.isInside(root, options.quarantineDirectory()))
            .as("a filesystem root has no beside, so the default lands inside and is refused")
            .isTrue();
    }

    @Test
    void theDefaultForALinkedDataDirectoryIsBesideWhatItPointsAt(@TempDir Path dir)
            throws IOException {
        Path data = Files.createDirectories(dir.resolve("volume/data"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), data);

        FsckOptions options = FsckOptionsParser.parse(new String[] { link.toString() });

        assertThat(options.quarantineDirectory().getParent())
            .as("beside the real directory, on its filesystem, where a rename can reach")
            .isEqualTo(data.toRealPath().getParent());
        assertThat(QuarantineLocation.isInside(link, options.quarantineDirectory())).isFalse();
    }
}
