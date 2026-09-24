package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.core.datadir.ResolvedPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the default quarantine lands: beside the real data directory, never inside it.
 */
class QuarantineDefaultTest {

    @Test
    void aDataDirectoryWithNoParentHasNoDefaultOutsideIt() throws IOException {
        Path root = Path.of("/").toRealPath();
        FsckOptions options = FsckOptionsParser.parse(new String[] { root.toString() });

        assertThat(ResolvedPath.isInside(root, options.quarantineDirectory()))
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
        assertThat(ResolvedPath.isInside(link, options.quarantineDirectory())).isFalse();
    }
}
