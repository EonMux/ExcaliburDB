package excalibur.backup.restore;

import excalibur.backup.manifest.SnapshotManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a restore produced.
 *
 * <p>{@code rebuildRequired} is the part an operator has to act on. An image taken without
 * its derived state restores a node that will start and serve, and will answer lexical
 * queries out of an index that is not there until a reindex has run. That is a correct
 * backup and an incomplete node, and the difference has to be said out loud.</p>
 *
 * @param target          The data directory that now exists
 * @param filesRestored   How many files it holds
 * @param bytesRestored   How many logical bytes they hold
 * @param manifest        What the image said it was
 * @param rebuildRequired What has to be rebuilt before the node is whole, possibly empty
 * @author ExcaliburDB
 */
public record RestoreResult(Path target, int filesRestored, long bytesRestored,
                            SnapshotManifest manifest, List<String> rebuildRequired) {

    /**
     * Canonical constructor, copying the rebuild list.
     *
     * @throws NullPointerException if any argument is null
     */
    public RestoreResult {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(manifest, "manifest");
        rebuildRequired = List.copyOf(Objects.requireNonNull(rebuildRequired,
            "rebuildRequired"));
    }
}
