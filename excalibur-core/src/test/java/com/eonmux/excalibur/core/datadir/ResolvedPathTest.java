package com.eonmux.excalibur.core.datadir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a path really is, with every symbolic link and {@code ..} on the way resolved.
 *
 * <p>The question is asked of the filesystem, not of the path's spelling: a path that reads as
 * outside the data directory can land inside it through a link, and a {@code ..} after a link
 * climbs from where the link points, not from where it sits.</p>
 */
class ResolvedPathTest {

    @Test
    void aSiblingIsOutside(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));

        assertThat(ResolvedPath.isInside(data, dir.resolve("q"))).isFalse();
        assertThat(ResolvedPath.isInside(data, dir.resolve("data-q")))
            .as("a name that merely begins with the data directory's is not inside it")
            .isFalse();
    }

    @Test
    void aSubdirectoryIsInsideWhetherOrNotItExists(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.createDirectories(data.resolve("segments"));

        assertThat(ResolvedPath.isInside(data, data.resolve("q/deeper"))).isTrue();
        assertThat(ResolvedPath.isInside(data, data.resolve("segments"))).isTrue();
    }

    @Test
    void theDataDirectoryItselfIsInside(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));

        assertThat(ResolvedPath.isInside(data, data)).isTrue();
        assertThat(ResolvedPath.isInside(data, dir.resolve("elsewhere/../data"))).isTrue();
    }

    @Test
    void aLinkIntoTheDataDirectoryIsFollowed(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), data);

        assertThat(ResolvedPath.isInside(data, link.resolve("q"))).isTrue();
        assertThat(ResolvedPath.isInside(link, dir.resolve("data/q")))
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
        assertThat(ResolvedPath.isInside(data, spelled))
            .as("but the filesystem climbs out of inner/, into the data directory")
            .isTrue();
    }

    @Test
    void aLinkOutOfTheDataDirectoryIsOutside(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path link = Files.createSymbolicLink(data.resolve("away"), outside);

        assertThat(ResolvedPath.isInside(data, link.resolve("q")))
            .as("files moved there land outside the directory being checked")
            .isFalse();
    }

    @Test
    void theCanonicalFormNamesWhereFilesWouldLand(@TempDir Path dir) throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), data);

        assertThat(ResolvedPath.canonical(link.resolve("a/../b")))
            .isEqualTo(data.toRealPath().resolve("b"));
    }
}
