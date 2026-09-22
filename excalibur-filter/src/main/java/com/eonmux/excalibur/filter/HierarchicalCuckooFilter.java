package com.eonmux.excalibur.filter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A two-level Cuckoo filter structure for efficient segment-level membership testing.
 *
 * <h2>Structure</h2>
 * <p>The hierarchy consists of two levels:
 * <ul>
 *   <li><strong>Corpus (root):</strong> One filter holding every term on the node</li>
 *   <li><strong>Segment (leaf):</strong> One filter per segment, holding that segment's terms</li>
 * </ul>
 *
 * <h2>Why there is no level between them</h2>
 * <p>An intermediate level earns its memory only when it partitions the terms: a group of
 * segments whose filter rejects a term lets a lookup skip the whole group without asking each
 * segment. Storage places documents by the hash of their key, so every segment holds a uniform
 * sample of the vocabulary and any group of segments holds nearly all of it - a group filter
 * would answer "maybe" for almost every term that the corpus filter already let through, prune
 * nothing, and cost a second corpus-sized filter to do it. It would also widen what one refused
 * insert disables, from one segment to the whole group. A grouping level becomes worth having
 * only when placement groups related documents, and then it belongs here with the placement
 * that justifies it.</p>
 *
 * <h2>Query Flow</h2>
 * <p>Queries check the coarsest filter first, drilling down on positives:
 * <ol>
 *   <li>Check the corpus filter - if negative, the term is definitely not on the node</li>
 *   <li>Check segment filters - identify candidate segments</li>
 * </ol>
 *
 * <h2>Deletion with Propagation</h2>
 * <p>When a document is deleted, its fingerprint is removed from both levels:
 * <ol>
 *   <li>Delete from the segment filter (leaf level)</li>
 *   <li>Delete from the corpus filter (root level), once no other segment still reports it</li>
 * </ol>
 * <p>The corpus level stores each fingerprint once, so two different terms that land on the same
 * fingerprint and buckets there share one entry, and deleting it for one would deny the other.
 * Every level therefore hashes with one seed, and no segment filter has more buckets than the
 * corpus filter. Bucket counts are powers of two and bucket indexes are taken by mask, so two
 * terms that collide at the corpus level collide in every segment filter too: the segment that
 * holds the survivor reports the deleted term as present, and the shared entry is kept.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>All filter registrations/removals use ConcurrentHashMap</li>
 *   <li>All mutations use per-filter striped locks via CuckooFilter</li>
 *   <li>Hierarchy modifications use read-write locks</li>
 * </ul>
 *
 * <h2>When a level runs out of room</h2>
 * <p>A cuckoo filter refuses an insert once its buckets are exhausted, and a refused term is one
 * the filter does not hold and will answer "absent" for. A reader that prunes on that answer
 * drops documents that are sitting in storage - the one failure a membership filter must never
 * cause, and a silent one. So a level that has refused a term records it and stops claiming to
 * speak for its scope: {@link #coversSegment(String)} returns false, the caller searches the
 * segment, and pruning costs latency instead of answers. The mark is sticky until the filter is
 * rebuilt, because freeing space elsewhere does not record the term that was refused.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Segment IDs are non-null, non-empty strings</li>
 *   <li>Deletion from any level returns false if not found (idempotent)</li>
 *   <li>All byte[] keys are converted using UTF-8 encoding</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 3.0
 * @see CuckooFilter
 */
public class HierarchicalCuckooFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchicalCuckooFilter.class);

    private static final int DEFAULT_FINGERPRINT_BITS = 13;
    private static final int DEFAULT_SEGMENT_CAPACITY = 10_000;

    private final CuckooFilter corpusFilter;
    private final Map<String, CuckooFilter> segmentFilters;

    // Levels that have refused a term. A cuckoo filter that cannot accept an item does not hold
    // it, and every later question about that item gets "no" from a level that was never told.
    // A reader that prunes on such an answer drops documents that are sitting in storage, so a
    // level records the refusal and stops claiming to know what it holds. Sticky until the
    // filter is rebuilt: freeing space elsewhere does not record the term that was refused.
    private final Set<String> saturatedSegments;
    private volatile boolean corpusSaturated;

    private final int fingerprintBits;
    private final int segmentCapacity;

    private final ReentrantReadWriteLock hierarchyLock;
    private final AtomicLong totalEntries;

    /**
     * Creates a new HierarchicalCuckooFilter with default capacities.
     *
     * @param corpusCapacity Expected number of entries in the entire corpus
     * @throws IllegalArgumentException if corpusCapacity is not positive
     */
    public HierarchicalCuckooFilter(long corpusCapacity) {
        this(corpusCapacity, DEFAULT_FINGERPRINT_BITS, DEFAULT_SEGMENT_CAPACITY);
    }

    /**
     * Creates a new HierarchicalCuckooFilter with custom capacities.
     *
     * @param corpusCapacity Expected number of entries in the corpus
     * @param fingerprintBits Number of bits per fingerprint (8-16)
     * @param segmentCapacity Expected entries per segment
     * @throws IllegalArgumentException if either capacity is not positive
     */
    public HierarchicalCuckooFilter(long corpusCapacity, int fingerprintBits,
                                     int segmentCapacity) {
        this(createCorpusFilter(corpusCapacity, fingerprintBits), segmentCapacity);
    }

    /**
     * Creates a HierarchicalCuckooFilter from an existing corpus filter.
     *
     * <p>Segment filters are created with the corpus filter's fingerprint width.</p>
     *
     * @param corpusFilter The corpus-level Cuckoo filter
     * @param segmentCapacity Expected entries per segment
     * @throws NullPointerException if corpusFilter is null
     * @throws IllegalArgumentException if segmentCapacity is not positive
     */
    public HierarchicalCuckooFilter(CuckooFilter corpusFilter, int segmentCapacity) {
        Objects.requireNonNull(corpusFilter, "corpusFilter must not be null");
        if (segmentCapacity <= 0) {
            throw new IllegalArgumentException("segmentCapacity must be positive: " + segmentCapacity);
        }

        if (CuckooFilter.bucketsFor(segmentCapacity) > corpusFilter.numBuckets()) {
            throw new IllegalArgumentException("segmentCapacity " + segmentCapacity
                + " gives a segment filter more buckets than the corpus filter's "
                + corpusFilter.numBuckets() + "; a segment cannot be sized past the corpus");
        }

        this.corpusFilter = corpusFilter;
        this.fingerprintBits = corpusFilter.fingerprintBits();
        this.segmentCapacity = segmentCapacity;
        this.segmentFilters = new ConcurrentHashMap<>();
        this.hierarchyLock = new ReentrantReadWriteLock();
        this.totalEntries = new AtomicLong(0);
        this.saturatedSegments = ConcurrentHashMap.newKeySet();
    }

    /**
     * Creates the corpus level, refusing a capacity that could hold nothing.
     *
     * @param corpusCapacity Expected number of entries in the corpus
     * @param fingerprintBits Number of bits per fingerprint
     * @return The corpus filter
     * @throws IllegalArgumentException if corpusCapacity is not positive
     */
    private static CuckooFilter createCorpusFilter(long corpusCapacity, int fingerprintBits) {
        if (corpusCapacity <= 0) {
            throw new IllegalArgumentException("corpusCapacity must be positive: " + corpusCapacity);
        }
        return CuckooFilter.create(corpusCapacity, fingerprintBits);
    }

    /**
     * Registers a new segment filter in the hierarchy.
     *
     * @param segmentId Unique segment identifier
     * @return The created or existing segment filter
     * @throws NullPointerException if segmentId is null
     * @throws IllegalArgumentException if segmentId is empty
     */
    public CuckooFilter registerSegment(String segmentId) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentId.isEmpty()) {
            throw new IllegalArgumentException("segmentId must not be empty");
        }

        hierarchyLock.writeLock().lock();
        try {
            CuckooFilter existing = segmentFilters.get(segmentId);
            if (existing != null) {
                return existing;
            }
            // A segment id can come back after a split or a reindex, holding a filter that has
            // refused nothing. Carrying the old mark would disable pruning for that id forever.
            saturatedSegments.remove(segmentId);
            CuckooFilter created =
                CuckooFilter.create(segmentCapacity, fingerprintBits, corpusFilter.seed());
            segmentFilters.put(segmentId, created);
            return created;
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Registers an existing Cuckoo filter for a segment.
     *
     * <p>The filter must hash as the corpus level does - the same seed and fingerprint width -
     * and have no more buckets than it, or a delete could strip a corpus entry another segment's
     * term still depends on. See the class documentation.</p>
     *
     * @param segmentId Unique segment identifier
     * @param filter The Cuckoo filter for this segment
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if segmentId is empty, or the filter does not hash as the
     *                                  corpus level does
     */
    public void registerSegment(String segmentId, CuckooFilter filter) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        if (segmentId.isEmpty()) {
            throw new IllegalArgumentException("segmentId must not be empty");
        }
        if (filter.seed() != corpusFilter.seed()
                || filter.fingerprintBits() != corpusFilter.fingerprintBits()
                || filter.numBuckets() > corpusFilter.numBuckets()) {
            throw new IllegalArgumentException("segment " + segmentId + "'s filter must share "
                + "the corpus filter's seed and fingerprint width and have no more buckets");
        }

        hierarchyLock.writeLock().lock();
        try {
            saturatedSegments.remove(segmentId);
            segmentFilters.put(segmentId, filter);
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Unregisters a segment from the hierarchy.
     *
     * <p>This removes the segment filter but does NOT delete entries from
     * the corpus filter. Use {@link #deleteFromHierarchy(byte[])}
     * for proper cleanup of deleted entries.</p>
     *
     * @param segmentId The segment ID to unregister
     * @return true if the segment was removed
     */
    public boolean unregisterSegment(String segmentId) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        hierarchyLock.writeLock().lock();
        try {
            CuckooFilter removed = segmentFilters.remove(segmentId);
            saturatedSegments.remove(segmentId);
            return removed != null;
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Inserts an item into all levels of the hierarchy.
     *
     * <p>The item is inserted into the segment filter and then the corpus filter.</p>
     *
     * @param item The item to insert
     * @param segmentId The segment ID
     * @return true if insertion succeeded at both levels
     * @throws NullPointerException if item or segmentId is null
     * @throws IllegalStateException if segment is not registered
     */
    public boolean insert(byte[] item, String segmentId) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        hierarchyLock.readLock().lock();
        try {
            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
            if (segmentFilter == null) {
                throw new IllegalStateException("Segment not registered: " + segmentId);
            }

            // Set semantics at both levels: one entry per item per filter, not one per
            // insertion. A cuckoo filter stores a fingerprint in one of two buckets, each
            // holding a fixed handful of entries, so the same item inserted repeatedly
            // exhausts them and then fails for good - and deletion removes one slot per
            // call, so it can never undo a count of insertions nobody recorded. The delete
            // path removes each of a segment's distinct terms once, which is exactly what
            // this records.
            //
            // Safe against a concurrent delete because deletes take the write lock of the
            // same hierarchyLock this insert holds for reading: without that, an insert
            // could find an entry present, skip its own, and have the delete remove it a
            // moment later -- leaving a level denying an item a level below it holds, which
            // is the false negative this whole arrangement exists to prevent.
            LevelInsert segmentResult = insertIfAbsent(segmentFilter, item);
            if (segmentResult == LevelInsert.FULL) {
                if (saturatedSegments.add(segmentId)) {
                    LOGGER.warn("Segment {} filter is full at {} entries; it can no longer rule "
                        + "the segment out and every query will search it. Raise the segment "
                        + "capacity if this is common", segmentId, segmentFilter.count());
                }
                return false;
            }

            // Roll back only if this call added the segment entry. Deleting an entry an
            // earlier insertion put there would strip the item from a segment that still
            // holds it.
            LevelInsert corpusResult = insertIfAbsent(corpusFilter, item);
            if (corpusResult == LevelInsert.FULL) {
                rollBack(segmentFilter, item, segmentResult);
                if (!corpusSaturated) {
                    corpusSaturated = true;
                    LOGGER.warn("Corpus filter is full at {} entries; segment pruning is off and "
                        + "every query now searches every segment. Raise "
                        + "excalibur.filter.corpus-capacity past the corpus term count and "
                        + "restart", corpusFilter.count());
                }
                return false;
            }

            if (segmentResult == LevelInsert.ADDED) {
                totalEntries.incrementAndGet();
            }
            return true;
        } finally {
            hierarchyLock.readLock().unlock();
        }
    }

    /**
     * What one level's insertion did.
     */
    private enum LevelInsert {
        /** The level did not hold the item and now does. */
        ADDED,
        /** The level already reported the item; nothing was written. */
        PRESENT,
        /** The level does not hold the item and had no room for it. */
        FULL
    }

    /**
     * Records an item in one level unless that level already reports it.
     *
     * @param filter The level's filter
     * @param item The item
     * @return What the level did
     */
    private static LevelInsert insertIfAbsent(CuckooFilter filter, byte[] item) {
        if (filter.contains(item)) {
            return LevelInsert.PRESENT;
        }
        return filter.insert(item) ? LevelInsert.ADDED : LevelInsert.FULL;
    }

    /**
     * Undoes one level's insertion, and only if this call made it.
     *
     * @param filter The level's filter
     * @param item The item
     * @param result What that level did
     */
    private static void rollBack(CuckooFilter filter, byte[] item, LevelInsert result) {
        if (result == LevelInsert.ADDED) {
            filter.delete(item);
        }
    }

    /**
     * Checks if an item might be present in the corpus.
     *
     * <p>This checks only the corpus filter (coarsest level).</p>
     *
     * @param item The item to check
     * @return true if the item might be present (may be false positive)
     * @throws NullPointerException if item is null
     */
    public boolean mightContain(byte[] item) {
        Objects.requireNonNull(item, "item must not be null");
        return corpusFilter.contains(item);
    }

    /**
     * The entries the corpus level was sized to hold.
     *
     * <p>Exposed because this level is what decides whether a term is looked for at all: once it
     * is full it cannot record a new term, and a term it has no record of prunes every segment.
     * The result is an empty answer for documents that are sitting in storage, which is
     * indistinguishable from "nothing matched" unless the sizing can be read back.</p>
     *
     * @return The corpus-level capacity, at least the value the filter was constructed with
     */
    public long corpusCapacity() {
        return corpusFilter.capacity();
    }

    /**
     * Entries each segment's own filter is sized for.
     *
     * @return The per-segment capacity this filter was built with
     */
    public int segmentCapacity() {
        return segmentCapacity;
    }

    /**
     * Checks if an item might be present in a specific segment.
     *
     * <p>This checks the full hierarchy: corpus, then segment.</p>
     *
     * @param item The item to check
     * @param segmentId The segment ID
     * @return true if the item might be in the segment
     * @throws NullPointerException if item or segmentId is null
     */
    public boolean mightContainInSegment(byte[] item, String segmentId) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        if (!corpusFilter.contains(item)) {
            return false;
        }

        hierarchyLock.readLock().lock();
        try {
            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
            if (segmentFilter == null) {
                return false;
            }

            return segmentFilter.contains(item);
        } finally {
            hierarchyLock.readLock().unlock();
        }
    }

    /**
     * Finds all segments that might contain the item.
     *
     * <p>The corpus filter is checked first, and a term it rejects returns no candidates
     * without asking any segment. Otherwise every segment filter is asked once.</p>
     *
     * @param item The item to search for
     * @return List of segment IDs that might contain the item
     * @throws NullPointerException if item is null
     */
    public List<String> findCandidateSegments(byte[] item) {
        Objects.requireNonNull(item, "item must not be null");

        List<String> candidates = new ArrayList<>();

        if (!corpusFilter.contains(item)) {
            return candidates;
        }

        hierarchyLock.readLock().lock();
        try {
            for (Map.Entry<String, CuckooFilter> segmentEntry : segmentFilters.entrySet()) {
                if (segmentEntry.getValue().contains(item)) {
                    candidates.add(segmentEntry.getKey());
                }
            }
        } finally {
            hierarchyLock.readLock().unlock();
        }

        return candidates;
    }

    /**
     * Deletes an item from all levels of the hierarchy immediately.
     *
     * <p><strong>CRITICAL:</strong> This method is called when a document is deleted.
     * It removes the entry from the segment filter, and from the corpus filter once no other
     * segment still reports it.</p>
     *
     * <p>This ensures queries issued after deletion will not consider the entry
     * as a candidate, preventing stale false positives.</p>
     *
     * <p>The corpus level is only touched when the segment filter actually
     * held the item. A Cuckoo delete removes the first slot whose fingerprint matches, so
     * deleting an item that was never inserted evicts some other item that happens to share
     * a fingerprint - and at the corpus level that other item can belong to any segment.
     * A miss at the leaf proves the item was never inserted for this segment.</p>
     *
     * <p>That guard has a cost, and it is the reason the corpus level can hold an entry no
     * segment needs. Every level hashes independently, so two items whose fingerprints
     * collide in the segment filter share one entry there and hold two in the corpus. Deleting
     * the first removes the shared segment entry; deleting the second finds nothing at the leaf
     * and stops, leaving its corpus entry behind for good. The rate is the
     * fingerprint collision rate, and the error is the harmless one: a candidate segment
     * that turns out to hold nothing, never a document that cannot be found.</p>
     *
     * @param item The item to delete
     * @param segmentId The segment ID where the item was stored
     * @return true if the item was found in the segment filter and removed
     * @throws NullPointerException if item or segmentId is null
     */
    public boolean deleteFromHierarchy(byte[] item, String segmentId) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        hierarchyLock.writeLock().lock();
        try {
            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
            if (segmentFilter == null || !segmentFilter.delete(item)) {
                return false;
            }

            propagateDelete(item, segmentId);
            totalEntries.decrementAndGet();
            return true;
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Removes an item from the corpus filter, if no other segment still needs it there.
     *
     * <p>The corpus level holds each item once however many segments contain it, so removing one
     * segment's copy must not remove the shared entry while another segment still has the item
     * - that would make the corpus filter answer "absent" for something present, and the query
     * path prunes on exactly that answer.
     *
     * <p>The checks are deliberately conservative. {@code mightContain} can be a false positive,
     * so the entry is sometimes kept when it could have gone; the cost of that is a candidate
     * that turns out to hold nothing, which is what a probabilistic filter is for. The cost of
     * the opposite mistake is a document that can no longer be found.
     *
     * <p>The caller must hold {@code hierarchyLock}.
     *
     * <p>Asking every other segment makes a delete proportional to the number of segments,
     * which is the cost a lookup already pays on every corpus hit; deletes are far rarer.</p>
     *
     * @param item        The item to remove
     * @param fromSegment The segment it was just removed from, which is not consulted
     */
    private void propagateDelete(byte[] item, String fromSegment) {
        for (Map.Entry<String, CuckooFilter> entry : segmentFilters.entrySet()) {
            if (!entry.getKey().equals(fromSegment) && entry.getValue().contains(item)) {
                return;
            }
        }
        corpusFilter.delete(item);
    }

    /**
     * Deletes an item from all levels of the hierarchy without requiring segment ID.
     *
     * <p>This method searches all segments for the item and deletes it.
     * Less efficient than {@link #deleteFromHierarchy(byte[], String)} when
     * the segment ID is known.</p>
     *
     * @param item The item to delete
     * @return true if the item was found and deleted
     * @throws NullPointerException if item is null
     */
    public boolean deleteFromHierarchy(byte[] item) {
        Objects.requireNonNull(item, "item must not be null");

        boolean deleted = false;

        hierarchyLock.writeLock().lock();
        try {
            for (Map.Entry<String, CuckooFilter> segmentEntry : segmentFilters.entrySet()) {
                if (segmentEntry.getValue().delete(item)) {
                    deleted = true;
                    propagateDelete(item, segmentEntry.getKey());
                    break;
                }
            }

            if (deleted) {
                totalEntries.decrementAndGet();
            }
        } finally {
            hierarchyLock.writeLock().unlock();
        }

        return deleted;
    }

    /**
     * Deletes multiple items from the hierarchy in batch.
     *
     * <p>This is more efficient than calling {@link #deleteFromHierarchy(byte[], String)}
     * multiple times when deleting many items from the same segment.</p>
     *
     * <p>As in {@link #deleteFromHierarchy(byte[], String)}, an item is removed from the
     * corpus filter only when the segment filter held it. Deleting an item that
     * was never inserted would evict a fingerprint-colliding item belonging to another
     * segment from the shared corpus filter, which the query path then hard-prunes.</p>
     *
     * @param items The items to delete
     * @param segmentId The segment ID
     * @return Count of successfully deleted items
     * @throws NullPointerException if items or segmentId is null
     */
    public int deleteFromHierarchy(List<byte[]> items, String segmentId) {
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        int deletedCount = 0;

        hierarchyLock.writeLock().lock();
        try {
            CuckooFilter segmentFilter = segmentFilters.get(segmentId);

            if (segmentFilter == null) {
                return 0;
            }

            for (byte[] item : items) {
                if (!segmentFilter.delete(item)) {
                    continue;
                }

                propagateDelete(item, segmentId);
                deletedCount++;
                totalEntries.decrementAndGet();
            }
        } finally {
            hierarchyLock.writeLock().unlock();
        }

        return deletedCount;
    }

    /**
     * Removes all entries for a segment from the hierarchy.
     *
     * <p>This is used when a segment is tombstoned. It removes the segment
     * filter but does NOT attempt to delete individual entries from the corpus
     * filter (they may have false positives from other segments).</p>
     *
     * <p>For proper cleanup of deleted entries, use 
     * {@link #deleteFromHierarchy(byte[], String)} for each entry.</p>
     *
     * @param segmentId The segment ID to remove
     * @return true if the segment was removed
     */
    public boolean removeSegment(String segmentId) {
        return unregisterSegment(segmentId);
    }

    /**
     * Whether this filter may be used to rule a segment out.
     *
     * <p>A pre-filter earns the right to exclude a segment only by holding everything that
     * segment holds. Three things take that right away, and all three are silent: the segment
     * may hold nothing yet, so the filter has looked at nothing rather than looked and found
     * nothing; a level may have refused a term for want of room, so the term is absent from a
     * filter whose absences a reader would otherwise trust; and the corpus level - the first one
     * consulted - may be full, which makes every segment's verdict worthless at once.</p>
     *
     * <p>When this returns false the caller must search the segment. Pruning is an optimisation;
     * answering a query with nothing while the documents sit in storage is not a slow answer but
     * a wrong one.</p>
     *
     * @param segmentId The segment's id
     * @return true when the filter's silence about a term genuinely means the segment lacks it
     */
    public boolean coversSegment(String segmentId) {
        if (segmentId == null || corpusSaturated || saturatedSegments.contains(segmentId)) {
            return false;
        }
        CuckooFilter segmentFilter = segmentFilters.get(segmentId);
        return segmentFilter != null && segmentFilter.count() > 0;
    }

    /**
     * Whether the corpus level has run out of room.
     *
     * <p>Once it has, no segment can be ruled out by this filter and every query pays the full
     * scan. Sized by {@code excalibur.filter.corpus-capacity}; an operator seeing this wants to
     * raise it and restart rather than to keep running without pruning.</p>
     *
     * @return true when a term has been refused by the corpus filter
     */
    public boolean isSaturated() {
        return corpusSaturated;
    }

    /**
     * Segments this filter can no longer rule out because a term was refused for them.
     *
     * <p>For reporting. A growing count is the signal that {@code segmentCapacity} is too small
     * for the vocabulary the segments actually hold.</p>
     *
     * @return How many segments have refused a term
     */
    public int saturatedSegmentCount() {
        return saturatedSegments.size();
    }

    /**
     * Returns the segment filter for a segment.
     *
     * @param segmentId The segment ID
     * @return The segment filter, or null if not registered
     */
    public CuckooFilter getSegmentFilter(String segmentId) {
        return segmentFilters.get(segmentId);
    }

    /**
     * Returns the corpus filter.
     *
     * @return The corpus-level Cuckoo filter
     */
    public CuckooFilter getCorpusFilter() {
        return corpusFilter;
    }

    /**
     * Returns the total number of registered segments.
     *
     * @return Segment count
     */
    public int getSegmentCount() {
        return segmentFilters.size();
    }

    /**
     * Returns the approximate total entries in the hierarchy.
     *
     * @return Total entry count
     */
    public long getTotalEntries() {
        return totalEntries.get();
    }

    /**
     * Returns the corpus filter load factor.
     *
     * @return Corpus load factor (0.0 to 1.0)
     */
    public double getCorpusLoadFactor() {
        return corpusFilter.loadFactor();
    }

    /**
     * Converts a string key to bytes for filter operations.
     *
     * @param key The string key
     * @return UTF-8 encoded bytes
     */
    public static byte[] toBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Helper method to delete a string key from the hierarchy.
     *
     * @param key The string key to delete
     * @param segmentId The segment ID
     * @return true if deleted
     */
    public boolean deleteKey(String key, String segmentId) {
        return deleteFromHierarchy(toBytes(key), segmentId);
    }

    /**
     * Helper method to insert a string key into the hierarchy.
     *
     * @param key The string key to insert
     * @param segmentId The segment ID
     * @return true if inserted
     */
    public boolean insertKey(String key, String segmentId) {
        return insert(toBytes(key), segmentId);
    }

    /**
     * Helper method to check if a string key might be in a segment.
     *
     * @param key The string key to check
     * @param segmentId The segment ID
     * @return true if might be present
     */
    public boolean mightContainKey(String key, String segmentId) {
        return mightContainInSegment(toBytes(key), segmentId);
    }

    /**
     * Helper method to check if a string key might be in the corpus.
     *
     * @param key The string key to check
     * @return true if might be present in corpus
     */
    public boolean mightContainKey(String key) {
        return mightContain(toBytes(key));
    }

    @Override
    public String toString() {
        return String.format(
            "HierarchicalCuckooFilter[segments=%d, entries=%d, corpusLoadFactor=%.4f]",
            getSegmentCount(), getTotalEntries(), getCorpusLoadFactor());
    }
}
