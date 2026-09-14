package io.excalibur.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * Decides which directory a segment's files are written to.
 *
 * <p>A store may span more than one filesystem. Spreading segments over several volumes is how
 * a node outgrows a single disk, and the decision of which volume a given segment belongs on is
 * not the storage engine's to make - it depends on the volume set, which this module has no way
 * to learn. So the engine asks, and something above it answers.</p>
 *
 * <p>The two questions are inseparable. {@link #directoryFor(long)} says where a segment is
 * written; {@link #directories()} says everywhere a segment might already be, which is what a
 * restart has to scan. A placement that answers the first without the second would write
 * segments a restart cannot find, and the engine would quietly create empty files over their
 * indices.</p>
 *
 * <p>Implementations must be thread-safe and must answer {@link #directoryFor(long)}
 * consistently for the lifetime of an engine: a segment whose directory changes underneath it
 * is a segment with two files and one name.</p>
 *
 * @see StorageEngine#withSegmentPlacement(SegmentPlacement)
 */
public interface SegmentPlacement {

    /**
     * Returns the directory the given segment's files belong in.
     *
     * @param segmentIndex The directory index of the segment
     * @return The directory, always one of {@link #directories()}
     */
    Path directoryFor(long segmentIndex);

    /**
     * Returns every directory that may hold a segment file, primary first.
     *
     * <p>This is the set a restart scans. The first entry is the data directory, which also
     * holds the catalog and the routing directory.</p>
     *
     * @return The directories, in order, never empty
     */
    List<Path> directories();

    /**
     * Returns the placement that keeps every segment in one directory.
     *
     * <p>What an engine does when nothing tells it otherwise.</p>
     *
     * @param directory The directory to keep segments in
     * @return A placement over that directory alone
     * @throws NullPointerException if directory is null
     */
    static SegmentPlacement singleDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory cannot be null");
        List<Path> only = List.of(directory);
        return new SegmentPlacement() {
            @Override
            public Path directoryFor(long segmentIndex) {
                return directory;
            }

            @Override
            public List<Path> directories() {
                return only;
            }

            @Override
            public String toString() {
                return "SegmentPlacement[" + directory + "]";
            }
        };
    }

    /**
     * Returns a placement spreading segments over several directories.
     *
     * <p>The resolver decides; this only holds it to the set it was given. A resolver that
     * names a directory outside that set has placed a segment where no restart will look, so it
     * is refused rather than obeyed. A resolver with no answer at all - which is what a volume
     * manager returns before any volume is registered - falls back to the first directory,
     * because a segment in the data directory is always findable.</p>
     *
     * @param directories Every directory a segment may live in, data directory first
     * @param resolver    Maps a segment index to one of those directories
     * @return A placement backed by the resolver
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the directory list is empty
     */
    static SegmentPlacement across(List<Path> directories, LongFunction<Path> resolver) {
        Objects.requireNonNull(directories, "directories cannot be null");
        Objects.requireNonNull(resolver, "resolver cannot be null");

        List<Path> unique = List.copyOf(new LinkedHashSet<>(new ArrayList<>(directories)));
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("At least one directory is required");
        }
        for (Path directory : unique) {
            Objects.requireNonNull(directory, "directories cannot contain null");
        }

        return new SegmentPlacement() {
            @Override
            public Path directoryFor(long segmentIndex) {
                Path chosen = resolver.apply(segmentIndex);
                if (chosen == null) {
                    return unique.get(0);
                }
                if (!unique.contains(chosen)) {
                    throw new IllegalStateException(
                        "Segment " + segmentIndex + " was placed on " + chosen
                            + ", which is not one of the directories a restart scans " + unique
                            + ". Such a segment would be lost on the next start.");
                }
                return chosen;
            }

            @Override
            public List<Path> directories() {
                return unique;
            }

            @Override
            public String toString() {
                return "SegmentPlacement" + unique;
            }
        };
    }
}
