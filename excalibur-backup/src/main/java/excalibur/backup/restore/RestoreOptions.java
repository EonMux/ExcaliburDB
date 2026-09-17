package excalibur.backup.restore;

import excalibur.backup.verify.VerifyDepth;

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
        Path image = snapshotDir.toAbsolutePath().normalize();
        Path destination = target.toAbsolutePath().normalize();
        if (destination.startsWith(image)) {
            throw new IllegalArgumentException("A restore cannot write into the snapshot it "
                + "is reading: " + destination + " is under " + image);
        }
    }
}
