package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.core.datadir.ResolvedPath;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What to snapshot, to where, and what to do about the two awkward cases.
 *
 * @param dataDir        The data directory to copy; never modified
 * @param destination    Where the snapshot goes; must be absent or an empty directory
 * @param includeDerived Whether to copy derived state that has a rebuild path
 * @param allowLive      Whether to produce an image even if the directory moved underneath
 * @author ExcaliburDB
 */
public record SnapshotOptions(Path dataDir, Path destination, boolean includeDerived,
                              boolean allowLive) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if either path is null
     * @throws IllegalArgumentException if the destination is inside the data directory
     */
    public SnapshotOptions {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(destination, "destination");
        // Where the image would really land, not how its path is spelled: an --out reaching the
        // data directory through a symbolic link is as inside it as one spelled under it.
        Path source = ResolvedPath.canonicalArgument(dataDir);
        Path target = ResolvedPath.canonicalArgument(destination);
        if (target.startsWith(source)) {
            throw new IllegalArgumentException("A snapshot cannot be written inside the data "
                + "directory it is copying: " + target + " is under " + source);
        }
    }
}
