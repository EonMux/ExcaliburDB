package excalibur.backup.manifest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a restore needs in order to refuse an image before it overwrites anything.
 *
 * <p>A backup nobody can check is a rumour. What makes this one checkable is that it says
 * in advance exactly what it should contain: every file by name, length and checksum, so a
 * missing file, a truncated file, a file nobody expected and a file with one bit flipped
 * are all detectable without a data directory to compare against - and detectable before a
 * restore has written its first byte.</p>
 *
 * <p>It also records the two things that make the image interpretable rather than merely
 * intact: the formats it was written by, so a build that cannot read them can say so; and
 * the epoch it is coherent at, which is the epoch a node opening it would resume from.</p>
 *
 * @param manifestVersion The version of the manifest format itself
 * @param snapshotId      A unique identifier for this image
 * @param createdAt       When the snapshot began
 * @param producer        What wrote it, name and version
 * @param sourceDataDir   The data directory it was taken from, for the operator's benefit
 * @param epoch           The visibility epoch the image is coherent at
 * @param epochSource     Where that epoch was read from
 * @param consistency     Whether the node was stopped when it was taken
 * @param formatVersions  On-disk format versions, keyed by structure
 * @param files           Every file in the image, in sorted path order
 * @author ExcaliburDB
 */
public record SnapshotManifest(
    int manifestVersion,
    String snapshotId,
    Instant createdAt,
    String producer,
    String sourceDataDir,
    long epoch,
    EpochSource epochSource,
    Consistency consistency,
    Map<String, String> formatVersions,
    List<FileEntry> files) {

    /**
     * Canonical constructor, copying both collections so the record is immutable in fact
     * as well as in name.
     *
     * @throws NullPointerException     if any reference argument is null
     * @throws IllegalArgumentException if the epoch is negative or a path appears twice
     */
    public SnapshotManifest {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(sourceDataDir, "sourceDataDir");
        Objects.requireNonNull(epochSource, "epochSource");
        Objects.requireNonNull(consistency, "consistency");
        formatVersions = Map.copyOf(Objects.requireNonNull(formatVersions, "formatVersions"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (epoch < 0) {
            throw new IllegalArgumentException("An epoch cannot be " + epoch);
        }
        long distinct = files.stream().map(FileEntry::path).distinct().count();
        if (distinct != files.size()) {
            throw new IllegalArgumentException("A manifest names " + files.size()
                + " files but only " + distinct + " distinct paths");
        }
    }

    /**
     * Returns how many bytes the image should hold in total.
     *
     * @return The sum of every entry's size
     */
    public long totalBytes() {
        return files.stream().mapToLong(FileEntry::size).sum();
    }

    /**
     * Looks one file up by its relative path.
     *
     * @param path Path relative to the payload root
     * @return The entry, or empty if the manifest does not name it
     */
    public Optional<FileEntry> entry(String path) {
        Objects.requireNonNull(path, "path");
        return files.stream().filter(file -> file.path().equals(path)).findFirst();
    }
}
