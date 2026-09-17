package excalibur.backup.snapshot;

import excalibur.backup.manifest.SnapshotManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a snapshot produced, and what it deliberately left out.
 *
 * <p>The skipped list is part of the result rather than a log line, because "the restore
 * is missing the chunk index" is a fact the operator has to be told at the moment the
 * decision is made, not discovered from a directory listing later.</p>
 *
 * @param snapshotDir Where the image is
 * @param manifest    What it contains
 * @param skipped     Every file that was not copied, with the reason
 * @author ExcaliburDB
 */
public record SnapshotResult(Path snapshotDir, SnapshotManifest manifest,
                             List<ClassifiedFile> skipped) {

    /**
     * Canonical constructor, copying the skipped list.
     *
     * @throws NullPointerException if any argument is null
     */
    public SnapshotResult {
        Objects.requireNonNull(snapshotDir, "snapshotDir");
        Objects.requireNonNull(manifest, "manifest");
        skipped = List.copyOf(Objects.requireNonNull(skipped, "skipped"));
    }
}
