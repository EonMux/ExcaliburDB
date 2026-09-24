package com.eonmux.excalibur.backup.restore;

import com.eonmux.excalibur.backup.verify.VerifyDepth;
import com.eonmux.excalibur.core.datadir.ResolvedPath;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What to restore, to where, and how hard to check it first.
 *
 * @param snapshotDir        The image to restore from
 * @param target             The data directory to produce
 * @param overwriteNonEmpty  Whether a target that already holds something may be replaced
 * @param depth              How hard to verify the image before touching the target
 * @author ExcaliburDB
 */
public record RestoreOptions(Path snapshotDir, Path target, boolean overwriteNonEmpty,
                             VerifyDepth depth) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if any reference argument is null
     * @throws IllegalArgumentException if the target is inside the snapshot
     */
    public RestoreOptions {
        Objects.requireNonNull(snapshotDir, "snapshotDir");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(depth, "depth");
        // As for a snapshot's --out: a target reaching into the image through a symbolic link is
        // as inside it as one spelled under it.
        Path image = ResolvedPath.canonicalArgument(snapshotDir);
        Path destination = ResolvedPath.canonicalArgument(target);
        if (destination.startsWith(image)) {
            throw new IllegalArgumentException("A restore cannot write into the snapshot it "
                + "is reading: " + destination + " is under " + image);
        }
    }
}
