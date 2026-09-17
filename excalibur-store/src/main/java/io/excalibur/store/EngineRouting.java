package io.excalibur.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The extensible-hashing directory {@link StorageEngine} routes keys through: how it is
 * built, how it is published, how it is written down, and how it is read back.
 *
 * <h2>Why the directory is not just a set of segments</h2>
 * <p>{@code StorageEngine.segments} is a <em>directory</em>: several indices may point at the
 * same {@link Segment}. When one segment splits, the directory doubles and every index
 * {@code i} that did not split gets a buddy pointer at {@code i + oldCount} aimed at the same
 * segment, so widening the routing mask never orphans another segment's keys. Only the
 * segment that split gets two distinct children. That is what makes the directory the thing
 * with the invariants, rather than a bookkeeping detail of the map: its width must stay a
 * power of two, every slot below the width must resolve, and the smallest index pointing at a
 * segment - its <em>canonical</em> index - is the one that names its file and its catalog row.
 * Any traversal that must see each segment once has to de-duplicate first, which is
 * {@link #distinctSegments()}.</p>
 *
 * <h2>Why the directory is persisted</h2>
 * <p>The bit each split partitioned on is recorded nowhere except the directory itself. From
 * the second split onwards it cannot be reconstructed from the segment file names, so this
 * class writes the directory down after every change and reads it back at start-up, and
 * refuses to guess when the file is gone and more than one split has happened. See
 * {@link #requireReconstructibleRouting(int)} for why guessing is worse than refusing.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Every mutation here happens under {@code StorageEngine.segmentLock} - the engine's one
 * monitor, shared rather than copied, because the split path relies on holding it across
 * several of these operations. The reader-visible result of any mutation is the single
 * volatile {@code StorageEngine.routing} field, replaced wholesale by {@link #publishRouting()}
 * with a fresh {@link RoutingSnapshot}. Reads of the directory never take the lock.</p>
 *
 * <p>Start-up is the exception, and deliberately so: {@link #rebuildRoutingDirectory(int)} and
 * the methods it calls run before the engine is started and before any worker exists, so they
 * mutate the map directly and publish once at the end.</p>
 */
final class EngineRouting {

    /** The engine whose directory this is. */
    private final StorageEngine engine;

    /**
     * Binds this directory to its engine.
     *
     * @param engine The owning engine
     */
    EngineRouting(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Rebuilds the immutable routing snapshot from the current directory.
     *
     * <p>Must be called with {@code StorageEngine.segmentLock} held, or from a context where no other thread
     * can be changing the directory - start-up and construction.</p>
     */
    void publishRouting() {
        int width = engine.routingSegmentCount;
        Segment[] slots = new Segment[width];
        for (int slot = 0; slot < width; slot++) {
            slots[slot] = engine.segments.get((long) slot);
        }
        engine.routing = new RoutingSnapshot(width, slots);
    }

    /**
     * Rebuilds the routing directory so it covers exactly {@code [0, routingSegmentCount)}.
     *
     * <p>The directory width is the smallest power of two that both covers every
     * discovered segment index and is at least {@link StorageEngine#DEFAULT_INITIAL_SEGMENTS}. The
     * base indices below {@code StorageEngine.DEFAULT_INITIAL_SEGMENTS} are created if missing;
     * every higher index without a file of its own is a buddy pointer to the segment it
     * was split from, exactly as it was before the restart.</p>
     *
     * <p>Creating a fresh empty segment for such an index instead would orphan half of
     * the keys of the segment it buddies with, which is why only base indices are
     * created here.</p>
     *
     * @param maxSegmentIndex Highest segment index found on disk, or -1 if none
     */
    void rebuildRoutingDirectory(int maxSegmentIndex) {
        int width = StorageEngine.DEFAULT_INITIAL_SEGMENTS;
        while (width <= maxSegmentIndex) {
            width <<= 1;
        }

        // Base indices always have their own file.
        for (int i = 0; i < StorageEngine.DEFAULT_INITIAL_SEGMENTS; i++) {
            if (!engine.segments.containsKey((long) i)) {
                engine.registry.createSegment(i);
            }
        }

        // The directory as it actually was, when the previous run wrote it down.
        if (applyPersistedRouting(width)) {
            return;
        }

        // Otherwise the buddy heuristic -- but only where it is provably exact, which the
        // check below is responsible for. Beyond that point it is not a best effort, it is a
        // wrong answer that looks like a right one.
        requireReconstructibleRouting(width);

        if (maxSegmentIndex < 0) {
            // A store with no segments has no directory because it has never needed one. What is
            // built below is the initial directory rather than a reconstruction of a lost one,
            // and nothing has split for it to get wrong - so warning here would only teach an
            // operator that the first line of every new deployment is noise.
            StorageEngine.LOGGER.log(Level.INFO, "New store: routing directory created with {0} slots", width);
        } else {
            StorageEngine.LOGGER.log(Level.WARNING,
                "No routing directory at {0}; reconstructing it from segment file names. This is "
                    + "exact only if at most one segment has ever split.",
                engine.dataDir.resolve(RoutingDirectory.FILE_NAME));
        }

        for (int i = StorageEngine.DEFAULT_INITIAL_SEGMENTS; i < width; i++) {
            if (engine.segments.containsKey((long) i)) {
                continue;
            }
            Segment buddy = engine.segments.get((long) (i - Integer.highestOneBit(i)));
            if (buddy != null) {
                engine.segments.put((long) i, buddy);
            } else {
                StorageEngine.LOGGER.log(Level.WARNING,
                    "No buddy segment for routing index {0}; creating an empty segment", i);
                engine.registry.createSegment(i);
            }
        }

        engine.routingSegmentCount = width;
        publishRouting();
        persistRoutingDirectory();
    }

    /**
     * Stops the start when the directory cannot be read and cannot be reconstructed either.
     *
     * <p>Every split doubles the directory, so a width of {@code StorageEngine.DEFAULT_INITIAL_SEGMENTS}
     * means nothing has split and a width of twice that means exactly one segment has. In both
     * cases the buddy rule - slot {@code i} without a file of its own belongs to
     * {@code i - highestOneBit(i)} - reproduces the directory exactly, because there is at most
     * one partitioning bit to get wrong. From the second split onwards there is more than one,
     * the bits are not recoverable from the file names, and the rule starts sending slots to a
     * segment that does not hold them. {@link RoutingDirectory} carries the worked example.</p>
     *
     * <p>Nothing is lost when that happens, which is exactly why it has to stop the start: the
     * mis-addressed segment keeps its data and simply stops being reachable, while the next
     * write for those keys lands somewhere else. There is no later symptom that names the
     * cause, and by the time one appears the two copies have diverged. Refusing is reversible -
     * restoring the file starts the store unharmed - and guessing is not.</p>
     *
     * @param width The directory width implied by the segment files found on disk
     * @throws IllegalStateException if the directory is unreadable and cannot be reconstructed
     */
    private void requireReconstructibleRouting(int width) {
        if (width <= StorageEngine.DEFAULT_INITIAL_SEGMENTS * 2) {
            return;
        }
        throw new IllegalStateException(
            "Cannot start: " + engine.dataDir.resolve(RoutingDirectory.FILE_NAME) + " is missing or "
                + "unreadable, and this store has more than one segment split behind it (the "
                + "segment files imply " + width + " routing slots, and only up to "
                + (StorageEngine.DEFAULT_INITIAL_SEGMENTS * 2) + " can be reconstructed from their names). "
                + "The bit each split partitioned on is recorded nowhere else, so starting "
                + "would route part of the keyspace to a segment that does not hold it, "
                + "leaving the real data on disk and unreachable. No data has been lost: "
                + "restore " + RoutingDirectory.FILE_NAME + " from a backup taken with these "
                + "segment files and start again.");
    }

    /**
     * Restores the directory the previous run wrote, if it is there and still makes sense.
     *
     * <p>Rejected rather than partially applied when it does not: a directory naming a width
     * that disagrees with the files on disk, or pointing at a segment that is no longer there,
     * describes a store this file was not written for.
     *
     * @param width The width implied by the segment files found
     * @return true when the persisted directory was applied
     */
    private boolean applyPersistedRouting(int width) {
        int[] canonicalPerSlot = RoutingDirectory.read(engine.dataDir);
        if (canonicalPerSlot == null) {
            return false;
        }
        if (canonicalPerSlot.length < width) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Routing directory covers {0} slots but the segment files need {1}; "
                    + "reconstructing instead",
                new Object[] { canonicalPerSlot.length, width });
            return false;
        }

        Map<Long, Segment> resolved = new HashMap<>(canonicalPerSlot.length);
        for (int slot = 0; slot < canonicalPerSlot.length; slot++) {
            Segment segment = engine.segments.get((long) canonicalPerSlot[slot]);
            if (segment == null) {
                StorageEngine.LOGGER.log(Level.WARNING,
                    "Routing directory sends slot {0} to segment {1}, which is not on disk; "
                        + "reconstructing instead",
                    new Object[] { slot, canonicalPerSlot[slot] });
                return false;
            }
            resolved.put((long) slot, segment);
        }

        engine.segments.putAll(resolved);
        engine.routingSegmentCount = canonicalPerSlot.length;
        publishRouting();
        StorageEngine.LOGGER.log(Level.INFO, "Routing directory restored: {0} slots",
            canonicalPerSlot.length);
        return true;
    }

    /**
     * Writes the current directory so the next start does not have to guess it.
     *
     * <p>Failure is logged, not thrown. Losing the file costs accuracy on the next restart,
     * which is recoverable; failing the split that just succeeded is not.
     */
    void persistRoutingDirectory() {
        try {
            int width = engine.routingSegmentCount;
            Map<Segment, Long> canonical = new IdentityHashMap<>();
            for (Map.Entry<Long, Segment> entry : engine.segments.entrySet()) {
                canonical.merge(entry.getValue(), entry.getKey(), Math::min);
            }

            int[] canonicalPerSlot = new int[width];
            for (int slot = 0; slot < width; slot++) {
                Segment segment = engine.segments.get((long) slot);
                Long index = segment == null ? null : canonical.get(segment);
                canonicalPerSlot[slot] = index == null ? slot : index.intValue();
            }
            RoutingDirectory.write(engine.dataDir, canonicalPerSlot);
        } catch (IOException | RuntimeException e) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Failed to persist the routing directory; the next start will reconstruct it", e);
        }
    }

    /**
     * Returns each distinct segment keyed by its canonical (smallest) directory index.
     *
     * <p>The directory can point several indices at the same segment, so any traversal
     * that must visit a segment once - counting entries, flushing, closing, building a
     * query snapshot - has to go through this de-duplication.</p>
     *
     * @return Canonical index to segment
     */
    Map<Long, Segment> distinctSegments() {
        IdentityHashMap<Segment, Long> canonical = new IdentityHashMap<>();
        for (Map.Entry<Long, Segment> entry : engine.segments.entrySet()) {
            canonical.merge(entry.getValue(), entry.getKey(), Math::min);
        }
        Map<Long, Segment> result = new HashMap<>(canonical.size());
        canonical.forEach((segment, index) -> result.put(index, segment));
        return result;
    }

    /**
     * Returns every directory index pointing at a segment, smallest first.
     *
     * @param segment The segment to locate
     * @return Sorted directory indices, empty if the segment is not in the directory
     */
    List<Long> directorySlotsOf(Segment segment) {
        List<Long> slots = new ArrayList<>();
        for (Map.Entry<Long, Segment> entry : engine.segments.entrySet()) {
            if (entry.getValue() == segment) {
                slots.add(entry.getKey());
            }
        }
        Collections.sort(slots);
        return slots;
    }

    /**
     * Returns the catalog index for a segment.
     *
     * <p>Buddy pointers make a segment reachable from several directory indices; its
     * catalog entry lives under the canonical (smallest) one. Falls back to the routed
     * index for a segment that is not in the directory.</p>
     *
     * @param segment     The segment
     * @param routedIndex The directory index the key routed to
     * @return The catalog index to update
     */
    long catalogIndexOf(Segment segment, long routedIndex) {
        Long canonical = engine.canonicalIndex.get(segment);
        return canonical != null ? canonical : routedIndex;
    }

    /**
     * Repoints every directory slot holding a segment at its replacement.
     *
     * <p>Under the same monitor as every other directory mutation, so the compare-and-set
     * against {@code oldSegment} cannot interleave with a split publishing its children. The
     * directory may point several slots at one segment, so all of them move, not only the one
     * the caller named; leaving any behind would keep a retired segment reachable for part of
     * its own keyspace.</p>
     *
     * @param segmentIndex The segment index the caller identified
     * @param oldSegment   The segment being replaced, verified against the directory
     * @param newSegment   The replacement
     * @return true if the swap happened
     */
    boolean replaceSegment(long segmentIndex, Segment oldSegment, Segment newSegment) {
        // Guarded by the same monitor as every other directory mutation, so the
        // compare-and-set cannot interleave with a split publishing its children.
        synchronized (engine.segmentLock) {
            Segment current = engine.segments.get(segmentIndex);
            if (current == null) {
                StorageEngine.LOGGER.log(Level.WARNING, "Cannot replace segment {0}: not found in registry", segmentIndex);
                return false;
            }
            if (current != oldSegment) {
                StorageEngine.LOGGER.log(Level.WARNING, "Cannot replace segment {0}: old segment mismatch", segmentIndex);
                return false;
            }

            for (long slot : directorySlotsOf(oldSegment)) {
                engine.segments.put(slot, newSegment);
            }

            Long canonical = engine.canonicalIndex.remove(oldSegment);
            engine.canonicalIndex.put(newSegment, canonical != null ? canonical : segmentIndex);
            publishRouting();

            StorageEngine.LOGGER.log(Level.INFO, "Replaced segment {0}: {1} entries in new segment",
                new Object[] { segmentIndex, newSegment.entryCount() });
            return true;
        }
    }
}
