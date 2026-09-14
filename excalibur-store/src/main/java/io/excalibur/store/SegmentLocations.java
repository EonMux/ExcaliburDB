package io.excalibur.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each segment index's files actually are, as opposed to where a placement would put a
 * segment that does not exist yet.
 *
 * <p>{@link SegmentPlacement} is a function of the segment index and the volume set. On a
 * multi-volume node that function is a consistent hash ring, and the volume set is
 * configuration: adding or removing a directory from {@code excalibur.shard.volumes} moves some
 * indices to a different ring position. The placement's contract holds an implementation to a
 * consistent answer for the lifetime of one engine, but nothing can hold it to the same answer
 * across a restart, because the input changed.</p>
 *
 * <p>That matters because a segment's file is not re-derived from the index on every use - it
 * is opened once, from wherever discovery found it - while the names built around it are. A
 * replacement write-ahead log, and the canonical name a split child is renamed onto, are both
 * built from the index. After a remap those names point at a volume the segment is not on, so
 * the split child is written beside nothing while the parent's file stays where it was, and the
 * store now has two {@code segment_N.dat} files. The next start scans every volume, finds both,
 * and refuses - correctly, because nothing at that point can tell which of the two holds the
 * newer data.</p>
 *
 * <p>So the index-to-directory map is authoritative only for a segment that has no file yet.
 * Once a file exists, the file decides: a segment discovered on one volume keeps every name it
 * owns on that volume, whatever the ring now prefers. What remains of the placement's job is
 * choosing a home for genuinely new indices, and even there a name already taken on another
 * volume is refused rather than written over - at the point of creation, where the directories
 * involved can still be named, rather than at the next start.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Adoptions are published through a concurrent map; the engine serialises the
 * creations and split publications that write to it under its own segment lock.</p>
 */
final class SegmentLocations {

    /**
     * The directory each adopted index's files live in, keyed by directory index.
     */
    private final Map<Long, Path> adopted = new ConcurrentHashMap<>();

    /**
     * Returns the canonical file name for a segment index.
     *
     * @param segmentIndex The directory index
     * @return {@code segment_<index>.dat}, the name {@link SegmentDiscovery} matches
     */
    static String fileName(long segmentIndex) {
        return "segment_" + segmentIndex + ".dat";
    }

    /**
     * Records where a segment's files actually are.
     *
     * <p>Called for every segment discovery opened and every segment the engine creates or
     * renames, so that the location is a fact about the store rather than a re-derivation of a
     * configuration that may since have changed.</p>
     *
     * @param segmentIndex The directory index
     * @param segmentFile  The file the segment occupies
     * @throws NullPointerException if segmentFile is null
     */
    void adopt(long segmentIndex, Path segmentFile) {
        Objects.requireNonNull(segmentFile, "segmentFile cannot be null");
        Path directory = segmentFile.getParent();
        if (directory != null) {
            adopted.put(segmentIndex, directory);
        }
    }

    /**
     * Discards every recorded location.
     *
     * <p>Only meaningful before an engine starts, when a placement is installed over whatever
     * the default single-directory placement had already been asked about.</p>
     */
    void forgetAll() {
        adopted.clear();
    }

    /**
     * Returns the file a segment index occupies, or would occupy if it were created now.
     *
     * @param segmentIndex The directory index
     * @param placement    Where a segment with no file yet belongs
     * @return The {@code segment_<index>.dat} path
     * @throws IllegalStateException if another scanned directory already holds that name
     */
    Path segmentFile(long segmentIndex, SegmentPlacement placement) {
        return directoryFor(segmentIndex, placement).resolve(fileName(segmentIndex));
    }

    /**
     * Returns the directory a segment index's files belong in.
     *
     * @param segmentIndex The directory index
     * @param placement    Where a segment with no file yet belongs
     * @return The adopted directory if there is one, otherwise the placement's choice
     * @throws IllegalStateException if another scanned directory already holds that name
     */
    Path directoryFor(long segmentIndex, SegmentPlacement placement) {
        Path known = adopted.get(segmentIndex);
        if (known != null) {
            return known;
        }

        Path chosen = placement.directoryFor(segmentIndex);
        refuseIfNameIsTakenElsewhere(segmentIndex, chosen, placement.directories());
        return chosen;
    }

    /**
     * Refuses a placement that would create a second file for an index another volume holds.
     *
     * <p>Two files with one name is a state no later code can resolve, so it is rejected where
     * the operator can still act on it: at creation, naming the directory the file was about to
     * be written to and the directory that already has one.</p>
     *
     * @param segmentIndex The directory index about to be created
     * @param chosen       The directory the placement chose
     * @param scanned      Every directory a restart scans
     * @throws IllegalStateException if any other scanned directory holds the same name
     */
    private static void refuseIfNameIsTakenElsewhere(long segmentIndex, Path chosen,
                                                     List<Path> scanned) {
        String name = fileName(segmentIndex);
        List<Path> holders = new ArrayList<>();
        for (Path directory : scanned) {
            if (!directory.equals(chosen) && Files.isRegularFile(directory.resolve(name))) {
                holders.add(directory);
            }
        }
        if (holders.isEmpty()) {
            return;
        }

        throw new IllegalStateException(
            "Refusing to create segment " + segmentIndex + " in " + chosen + ": " + holders
                + " already holds " + name + ". Two files with one segment name cannot be told "
                + "apart by the next start, which will refuse to open the store. Remove or move "
                + "the copy that is no longer wanted before writing to this index.");
    }
}
