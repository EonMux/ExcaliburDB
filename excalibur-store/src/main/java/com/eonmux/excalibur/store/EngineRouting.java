package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.catalog.CatalogFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * The extensible-hashing directory {@link StorageEngine} routes keys through: how it is
 * built, how it is published, how it is written down, and how it is read back.
 *
 * <h2>Why the directory is not just a set of segments</h2>
 * <p>{@code StorageEngine.segments} is a <em>directory</em>: several indices may point at the
 * same {@link Segment}. That is what makes the directory the thing with the invariants, rather
 * than a bookkeeping detail of the map: its width must stay a power of two, every slot below the
 * width must resolve, and the smallest index pointing at a segment - its <em>canonical</em>
 * index - is the one that names its file and its catalog row. Any traversal that must see each
 * segment once has to de-duplicate first, which is {@link #distinctSegments()}.</p>
 *
 * <h2>Why the directory grows with the deepest segment, not with the splits</h2>
 * <p>This is extendible hashing. A segment's <em>local depth</em> is how many low hash bits
 * all of its keys share; it owns every slot whose low bits are those, which is
 * {@code width / 2^depth} slots. A segment shallower than the directory therefore already
 * owns several slots, and splitting it partitions on the lowest bit in which they differ and
 * hands each child half of them: the width does not change. Only a segment that owns a single
 * slot - one as deep as the directory - needs a new bit, and only then does the directory
 * double, every other slot {@code i} gaining a buddy at {@code i + width} aimed at the segment
 * it already had. {@link #planSplit(Segment)} makes that choice for every split.</p>
 *
 * <p>The difference is the whole heap cost of the directory. Doubling on every split makes it
 * {@code 2^splits} slots wide: an ingest of 61 MB of embedded chunks held two million slots for
 * seventy-nine segments when it ran out of a 256 MB heap. Growing with the deepest segment keeps
 * it a small multiple of the segment count for keys that hash evenly, and
 * {@code StorageEngine.maxRoutingWidth} bounds what keys that do not can cost: a split that
 * would double past it is refused with {@link RoutingDirectoryFullException}, counted and
 * logged, instead of being allowed to exhaust the heap. A split the segment catalog has no row
 * for is refused the same way, with {@link CatalogFullException}.</p>
 *
 * <h2>Why the directory is persisted</h2>
 * <p>The bit each split partitioned on is recorded nowhere except the directory itself. A
 * directory this build grew can be read back off the file names - every slot belongs to the
 * segment whose index it reduces to by clearing high bits - but one grown by a build that
 * doubled on every split cannot once two segments have split, and nothing in the names tells
 * the two apart. So this class writes the directory down after every change and reads it back
 * at start-up, and refuses to guess when the file is gone and the guess could be wrong. See
 * {@link #requireReconstructibleRouting(int, String)} for why guessing is worse than
 * refusing.</p>
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
     * Segments whose refused split has been logged, so a refusal is reported once per segment.
     * Weak, so a segment retired later is not kept alive by having been refused once; guarded
     * by {@code StorageEngine.segmentLock}.
     */
    private final Set<Segment> refusalsReported = Collections.newSetFromMap(new WeakHashMap<>());

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
        RoutingDirectory.Loaded persisted = RoutingDirectory.load(engine.dataDir);
        if (applyPersistedRouting(persisted.canonicalPerSlot(), width)) {
            return;
        }

        // Otherwise the buddy heuristic -- but only where it is provably exact, which the
        // check below is responsible for. Beyond that point it is not a best effort, it is a
        // wrong answer that looks like a right one.
        requireReconstructibleRouting(width, persisted.problem());

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
     * <p>The buddy rule - slot {@code i} without a file of its own belongs to whatever
     * {@code i - highestOneBit(i)} belongs to - is exactly how this build's splits assign slots,
     * so it reproduces any directory this build grew. It does not reproduce one grown by a
     * build that doubled the directory on every split, which partitioned each segment on the
     * newest bit rather than its own next one; and the two leave the same file names behind. A
     * width of at most twice {@code StorageEngine.DEFAULT_INITIAL_SEGMENTS} is the one case where
     * both agree - at most one split of the older kind, or only the first round of this build's
     * - because there is at most one partitioning bit to get wrong. Past it, the rule may send
     * slots to a segment that does not hold them. {@link RoutingDirectory} carries the worked
     * example.</p>
     *
     * <p>Nothing is lost when that happens, which is exactly why it has to stop the start: the
     * mis-addressed segment keeps its data and simply stops being reachable, while the next
     * write for those keys lands somewhere else. There is no later symptom that names the
     * cause, and by the time one appears the two copies have diverged. Refusing is reversible -
     * restoring the file starts the store unharmed - and guessing is not.</p>
     *
     * @param width   The directory width implied by the segment files found on disk
     * @param problem Why a directory file that is present could not be used - cut short,
     *                failing its checksum, from a newer build - or null when there is none
     * @throws IllegalStateException if the directory is unreadable and cannot be reconstructed
     */
    private void requireReconstructibleRouting(int width, String problem) {
        if (width <= StorageEngine.DEFAULT_INITIAL_SEGMENTS * 2) {
            return;
        }
        String state = problem == null ? "is missing or unusable"
            : "is present and cannot be used (" + problem + ")";
        throw new IllegalStateException(
            "Cannot start: " + engine.dataDir.resolve(RoutingDirectory.FILE_NAME) + " " + state
                + ", and this store has split too far for its directory to be rebuilt from "
                + "file names (the segment files imply " + width + " routing slots, and only up "
                + "to " + (StorageEngine.DEFAULT_INITIAL_SEGMENTS * 2) + " can be reconstructed "
                + "from their names with certainty). "
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
     * describes a store this file was not written for. The one disagreement that is corrected
     * rather than rejected is a split published but not yet written down; see
     * {@link #replayUnrecordedSplit(int[])}.
     *
     * @param persisted The directory the previous run wrote, or null when there is no usable one
     * @param width     The width implied by the segment files found
     * @return true when the persisted directory was applied
     */
    private boolean applyPersistedRouting(int[] persisted, int width) {
        if (persisted == null) {
            return false;
        }
        int[] canonicalPerSlot = replayUnrecordedSplit(persisted);
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
        if (canonicalPerSlot != persisted) {
            persistRoutingDirectory();
        }
        StorageEngine.LOGGER.log(Level.INFO, "Routing directory restored: {0} slots",
            canonicalPerSlot.length);
        return true;
    }

    /**
     * Brings a persisted directory up to date with a split whose children reached disk and
     * whose directory did not.
     *
     * <p>A split renames its children into place and routes to them first, and writes the
     * directory down after - outside the lock, so that no routing decision waits on a disk
     * write. A process that dies between the two leaves a right child's file that the directory
     * does not name. Ignoring it is not an option once the left child has taken over its
     * parent's file, because the keys with the split bit set are then only in the right child;
     * and when the directory has the same width either way, nothing else would notice.</p>
     *
     * <p>The file's name says which split it came from. A right child is always published at its
     * parent's canonical index plus {@code 2^b}, where {@code b} is the bit it was partitioned on
     * and the parent's index is below {@code 2^b}; so clearing the highest bit of its index gives
     * the parent, and that bit is the split bit. Replaying the split is then what publication
     * did: double the directory if it does not reach the child's index, and hand the child every
     * slot of the parent's with that bit set. Splits run one at a time and each writes the
     * directory before the next begins, so at most one is ever missing.</p>
     *
     * <p>A file that cannot be read this way - its supposed parent is not in the directory, or
     * the slot at its own index is not its parent's - is not a split this directory missed, and
     * the directory is not applied.</p>
     *
     * @param persisted The directory as written
     * @return {@code persisted} itself when it names every segment on disk, a corrected copy
     *         when one split was missing from it, or null when it cannot be reconciled
     */
    private int[] replayUnrecordedSplit(int[] persisted) {
        Set<Long> named = new HashSet<>();
        for (int canonical : persisted) {
            named.add((long) canonical);
        }
        List<Long> unnamed = new ArrayList<>();
        for (Long index : engine.segments.keySet()) {
            if (!named.contains(index)) {
                unnamed.add(index);
            }
        }
        if (unnamed.isEmpty()) {
            return persisted;
        }
        if (unnamed.size() > 1) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Routing directory does not name segments {0}; at most one split can be missing "
                    + "from it, so it is not applied", unnamed);
            return null;
        }

        long child = unnamed.get(0);
        long splitValue = Long.highestOneBit(child);
        long parent = child - splitValue;
        int splitBit = Long.numberOfTrailingZeros(splitValue);
        if (child <= 0 || child >= StorageEngine.MAX_ROUTING_WIDTH_LIMIT
                || !named.contains(parent)) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Routing directory does not name segment {0}, and no segment it names could have "
                    + "split into it; it is not applied", child);
            return null;
        }

        int[] replayed = persisted.clone();
        while (replayed.length <= child) {
            int[] doubled = new int[replayed.length * 2];
            System.arraycopy(replayed, 0, doubled, 0, replayed.length);
            System.arraycopy(replayed, 0, doubled, replayed.length, replayed.length);
            replayed = doubled;
        }
        if (replayed[(int) child] != parent) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Routing directory sends slot {0} to segment {1}, so segment {0} is not a split "
                    + "of segment {2} it missed; it is not applied",
                new Object[] { child, replayed[(int) child], parent });
            return null;
        }
        for (int slot = 0; slot < replayed.length; slot++) {
            if (replayed[slot] == parent && Segment.shouldGoToRightChild(slot, splitBit)) {
                replayed[slot] = (int) child;
            }
        }
        StorageEngine.LOGGER.log(Level.WARNING,
            "Routing directory predates the split of segment {0} into {0} and {1}; the split "
                + "was replayed from the file names and the directory rewritten",
            new Object[] { parent, child });
        return replayed;
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
     * How one segment's split fits the directory: the bit it partitions on, and the slots it
     * owns now.
     *
     * @param splitBit    The hash bit the children are partitioned on
     * @param parentSlots The slots the segment owns before the split, smallest first; the first
     *                    is its canonical index and stays the left child's
     * @param doubles     Whether the directory has to double to give the right child a slot
     */
    record SplitPlan(int splitBit, List<Long> parentSlots, boolean doubles) {

        /**
         * Returns the canonical index the right child is published at: the smallest of the
         * parent's slots with the split bit set, which after a doubling is the new buddy of the
         * parent's only slot.
         *
         * @return The right child's index
         */
        long rightIndex() {
            return parentSlots.get(0) + (1L << splitBit);
        }

        /**
         * Returns the catalog indices the children are registered at: the left child's, which
         * is the parent's own, then the right child's.
         *
         * @return The left and right indices
         */
        long[] childIndices() {
            return new long[] {parentSlots.get(0), rightIndex()};
        }
    }

    /**
     * Decides how a segment splits. The caller holds {@code StorageEngine.segmentLock}.
     *
     * <p>The slots a segment owns are those whose low bits match its canonical index up to its
     * local depth, so they differ only in the bits above it. The lowest bit they differ in is
     * the one to partition on: each child keeps the slots with that bit clear or set, and both
     * halves are again sets of that shape. A segment that owns one slot is as deep as the
     * directory, and the only bit left is the one a doubling exposes.</p>
     *
     * <p>The same answer is reached for a slot set a build that doubled on every split left
     * behind, because it too is every combination of some bits over fixed values of the rest;
     * such a segment simply splits on its lowest free bit from then on.</p>
     *
     * <p>The answer depends only on the segment's own slots, and nothing re-points those
     * except its own split - which the split worker runs one at a time, and a segment under
     * split is never compacted - so the split worker's answer, taken before it partitions, is
     * still the answer when {@link SplitPublication} publishes.</p>
     *
     * @param segment The segment to split
     * @return The plan
     * @throws IllegalStateException if the segment is not in the directory, or its slots are
     *         not a set a split can halve
     * @throws RoutingDirectoryFullException if the split needs a doubling past
     *         {@code StorageEngine.maxRoutingWidth}
     * @throws CatalogFullException if the catalog has no row for a child
     */
    SplitPlan planSplit(Segment segment) {
        SplitPlan plan = planSplitIfRouted(segment);
        if (plan == null) {
            throw new IllegalStateException(
                "Segment is not in the routing directory, so it has no slots to split: "
                    + segment.path());
        }
        return plan;
    }

    /**
     * {@link #planSplit(Segment)}, answering null for a segment the directory no longer routes
     * to - one a split or a compaction has already replaced. The caller holds
     * {@code StorageEngine.segmentLock}.
     *
     * @param segment The segment to split
     * @return The plan, or null when the segment owns no slot
     * @throws IllegalStateException if its slots are not a set a split can halve
     * @throws RoutingDirectoryFullException if the split needs a doubling past the limit
     * @throws CatalogFullException if the catalog has no row for a child
     */
    private SplitPlan planSplitIfRouted(Segment segment) {
        // The published snapshot rather than the map: under the lock the two agree, and the
        // array is a scan of references rather than of boxed keys.
        RoutingSnapshot routing = engine.routing;
        int width = routing.width();
        Segment[] routed = routing.slots();
        List<Long> slots = new ArrayList<>();
        for (int slot = 0; slot < width; slot++) {
            if (routed[slot] == segment) {
                slots.add((long) slot);
            }
        }
        if (slots.isEmpty()) {
            return null;
        }

        long first = slots.get(0);
        long varying = 0;
        for (long slot : slots) {
            varying |= slot ^ first;
        }
        if (slots.size() != 1L << Long.bitCount(varying)) {
            throw new IllegalStateException(
                "Segment " + segment.path() + " owns " + slots.size() + " routing slots that "
                    + "are not every combination of the bits they differ in, so no single bit "
                    + "halves them; the routing directory is inconsistent");
        }

        SplitPlan plan;
        if (varying != 0) {
            plan = new SplitPlan(Long.numberOfTrailingZeros(varying), List.copyOf(slots), false);
        } else {
            if ((long) width * 2 > engine.maxRoutingWidth) {
                refuseDoubling(segment, width);
            }
            plan = new SplitPlan(Integer.numberOfTrailingZeros(width), List.copyOf(slots), true);
        }
        // Asked here so a writer hears at once and the worker does not partition in vain.
        // Publication reserves the rows before it moves anything, which is what holds.
        if (!engine.catalog.hasRoomFor(plan.childIndices())) {
            refuseCatalogFull(segment);
        }
        return plan;
    }

    /**
     * Reserves the catalog rows a split's children will be registered in, before publication
     * moves any file. The caller holds {@code StorageEngine.segmentLock}.
     *
     * <p>The check {@link #planSplit(Segment)} makes can be overtaken: a rolling reindex
     * registers its segments without this lock. A reservation cannot, and publication moves the
     * children's files into place before it writes their rows, so it has to hold one first.</p>
     *
     * @param segment The segment being split
     * @param plan    Its plan
     * @throws CatalogFullException if the catalog has no row for a child
     */
    void reserveCatalogRows(Segment segment, SplitPlan plan) {
        if (!engine.catalog.reserveRows(plan.childIndices())) {
            refuseCatalogFull(segment);
        }
    }

    /**
     * Returns the bit a split of this segment partitions on, deciding it under the lock.
     *
     * <p>The split worker's view of {@link #planSplit(Segment)}.</p>
     *
     * @param segment The segment to split
     * @return The split bit
     * @throws RoutingDirectoryFullException if the split needs a doubling past the limit
     * @throws CatalogFullException if the catalog has no row for a child
     */
    int splitBitFor(Segment segment) {
        synchronized (engine.segmentLock) {
            return planSplit(segment).splitBit();
        }
    }

    /**
     * Returns the bit a split of this segment partitions on, or -1 when the directory no longer
     * routes to it.
     *
     * <p>The write path's view: a writer that found a segment full may find, by the time it
     * asks, that another thread's split has already replaced it, and the answer to that is to
     * route again rather than to fail.</p>
     *
     * @param segment The segment to split
     * @return The split bit, or -1 when the segment has been replaced
     * @throws RoutingDirectoryFullException if the split needs a doubling past the limit
     * @throws CatalogFullException if the catalog has no row for a child
     */
    int splitBitIfRouted(Segment segment) {
        synchronized (engine.segmentLock) {
            SplitPlan plan = planSplitIfRouted(segment);
            return plan == null ? -1 : plan.splitBit();
        }
    }

    /**
     * Counts, reports and refuses a split that would double the directory past its limit.
     *
     * @param segment The segment that cannot split
     * @param width   The directory's width
     * @throws RoutingDirectoryFullException always
     */
    private void refuseDoubling(Segment segment, int width) {
        refuse(segment, "Segment " + segment.path() + " is full and as deep as the routing "
            + "directory, and splitting it would double the directory from " + width + " to "
            + (width * 2L) + " slots, past its limit of " + engine.maxRoutingWidth + ". Its keys "
            + "share their lowest " + Integer.numberOfTrailingZeros(width) + " hash bits, which "
            + "for evenly hashed keys means a store of about " + (width / 2) + " segments or "
            + "more; this one has " + engine.canonicalIndex.size() + ". The segment keeps what "
            + "it holds and refuses further inserts.", RoutingDirectoryFullException::new);
    }

    /**
     * Counts, reports and refuses a split the segment catalog has no row for.
     *
     * @param segment The segment that cannot split
     * @throws CatalogFullException always
     */
    private void refuseCatalogFull(Segment segment) {
        refuse(segment, "Segment " + segment.path() + " is full, and the segment catalog has no "
            + "row left for the segment a split would add: it holds at most "
            + CatalogFile.MAX_SEGMENTS + " live segments, and a purged segment's row is reused "
            + "rather than added to. The segment keeps what it holds and refuses further "
            + "inserts.", CatalogFullException::new);
    }

    /**
     * Counts a refused split, logs it the first time this segment is refused, and throws.
     *
     * <p>Logged once per segment: the split worker offers a full segment again on every sweep
     * and a writer on every write it routes there, and the thing worth knowing is that it
     * happened and to which segment, not how often it was asked. Every refusal is counted.</p>
     *
     * @param segment   The segment that cannot split
     * @param message   Why
     * @param exception Makes the refusal from the message
     */
    private void refuse(Segment segment, String message,
                        Function<String, ? extends SegmentAtCapacityException> exception) {
        engine.statistics.incrementRefusedSplits();
        if (refusalsReported.add(segment)) {
            StorageEngine.LOGGER.log(Level.WARNING, message);
        }
        throw exception.apply(message);
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
