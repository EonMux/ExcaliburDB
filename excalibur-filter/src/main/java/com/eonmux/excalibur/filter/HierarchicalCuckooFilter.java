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
 * would answer "maybe" for almost every term that the corpus filter already let through, rule
 * nothing out, and cost a second corpus-sized filter to do it. It would also widen what one refused
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
 * the filter does not hold and will answer "absent" for. A reader that takes that answer as the
 * segment's lacking the term is told something false about a document sitting in storage - the
 * one thing a membership filter must never say, and silently. So a level that has refused a term
 * records it and stops claiming to speak for its scope: {@link #coversSegment(String)} returns
 * false, and the reader treats the segment as one the filter knows nothing about. The mark is
 * sticky until the filter is rebuilt, because freeing space elsewhere does not record the term
 * that was refused.</p>
 *
 * <h2>When a level stops describing its segment</h2>
 * <p>A level is filled by the writes that reach its segment, so it describes the segment only
 * while every entry the segment holds arrived that way. Entries also arrive wholesale: a split
 * hands each child a share of its parent's entries, a restart reopens segments whose levels this
 * process never filled, a compaction rewrites a segment without the entries its level still
 * counts. The first write to land afterwards makes the level non-empty and it would read as
 * covering the segment while missing everything the segment held before, so a reader trusting
 * it is told the segment lacks terms it holds. The owner of the segment therefore declares the level
 * {@linkplain #markSegmentStale(String) stale} before the segment can take such a write, refills
 * it from the segment's entries between {@link #beginSegmentRebuild(String)} and
 * {@link #completeSegmentRebuild(String, long)}, and {@link #coversSegment(String)} answers
 * false for the whole of that time. Each mark carries a generation, so a rebuild that started
 * before a later mark cannot clear it: only the rebuild that began after the last mark vouches
 * for the segment again.</p>
 *
 * <h2>A level is trusted only once a rebuild has vouched for it</h2>
 * <p>Nothing about a newly created level says what its segment already holds: the id may name a
 * segment reopened from disk, a split child, or one a compaction rewrote, and the write that
 * created the level is not evidence of the entries before it. So every level - whether
 * {@linkplain #registerSegment(String) created here} or {@linkplain #registerSegment(String,
 * CuckooFilter) supplied by a caller} - is born stale, and only a
 * {@linkplain #completeSegmentRebuild(String, long) completed rebuild} makes it trusted. A
 * segment whose owner never schedules one is one the filter never speaks for. Failing open
 * instead would let the first ingested write turn a reopened segment's empty level into a
 * verdict against everything the segment held before the restart.</p>
 *
 * <h2>Asking "does this segment lack every term" across several lookups</h2>
 * <p>A reader judges a segment from two answers taken at different moments: whether
 * the level covers the segment, and whether the level holds any of the query's terms. A rebuild
 * can complete between them - a lookup that ran against the half-refilled level misses a term
 * the segment holds, and the coverage asked afterwards says trusted. So coverage is also
 * offered as a {@linkplain #coverageStamp(String) stamp} that changes on every transition into
 * or out of trust: a reader takes it before its lookups and again after, and trusts a miss only
 * when both are the same covering stamp, which means the level was trusted, and was the same level,
 * for the whole of every lookup in between.</p>
 *
 * <p>The alternative - one read section under the hierarchy lock around the coverage read and
 * the lookups, with marks and completions taking the write lock - was rejected on cost:
 * {@link #markSegmentStale(String)} is called while a split is being published and must not wait
 * for every in-flight query, and a query would have to hold the read lock across all its terms
 * rather than one lookup at a time. The stamp costs a reader two map reads per candidate and
 * costs the writers nothing.</p>
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
    // A reader that trusts such an answer is told a segment lacks a term it holds, so a
    // level records the refusal and stops claiming to know what it holds. Sticky until the
    // filter is rebuilt: freeing space elsewhere does not record the term that was refused.
    private final Set<String> saturatedSegments;
    private volatile boolean corpusSaturated;

    // Where each level stands on trust, keyed by segment id. A negative value is a stale level
    // and holds the negated generation of the latest mark; a positive value is a trusted level
    // and holds the generation it became trusted at, which is the coverage stamp readers
    // compare. An id with no entry has no level or has never been marked, and is trusted by
    // nothing. One map, rather than a stale set beside a trust record, so that the transition
    // from stale to trusted is a single compare-and-set a reader cannot see half-done. See "When a
    // level stops describing its segment" and "A level is trusted only once a rebuild has
    // vouched for it".
    private final Map<String, Long> levelStates;
    private final AtomicLong generations;

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
        this.levelStates = new ConcurrentHashMap<>();
        this.generations = new AtomicLong();
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
     * Registers a new segment filter in the hierarchy, or returns the one already registered.
     *
     * <p>A level created here is born {@linkplain #markSegmentStale(String) stale}: it covers its
     * segment only once a rebuild has refilled it from the segment and
     * {@linkplain #completeSegmentRebuild(String, long) completed}. See "A level is trusted only
     * once a rebuild has vouched for it". Returning an existing level changes nothing about it.</p>
     *
     * <p>Ingestion calls this before every chunk it files, so the common case - the level exists
     * - is answered without the hierarchy lock, whose write side would otherwise make every
     * chunk written wait for every lookup in flight.</p>
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

        CuckooFilter known = segmentFilters.get(segmentId);
        if (known != null) {
            return known;
        }
        hierarchyLock.writeLock().lock();
        try {
            return levelOf(segmentId);
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Returns a segment's level, creating it stale when there is none.
     *
     * <p>The caller holds the hierarchy write lock.</p>
     *
     * @param segmentId The segment's id
     * @return The level
     */
    private CuckooFilter levelOf(String segmentId) {
        CuckooFilter existing = segmentFilters.get(segmentId);
        if (existing != null) {
            return existing;
        }
        // A segment id can come back after a split or a reindex, holding a filter that has
        // refused nothing. Carrying the old mark would silence the level for that id forever.
        saturatedSegments.remove(segmentId);
        CuckooFilter created =
            CuckooFilter.create(segmentCapacity, fingerprintBits, corpusFilter.seed());
        markSegmentStale(segmentId);
        segmentFilters.put(segmentId, created);
        return created;
    }

    /**
     * Registers an existing Cuckoo filter for a segment.
     *
     * <p>The filter must hash as the corpus level does - the same seed and fingerprint width -
     * and have no more buckets than it, or a delete could strip a corpus entry another segment's
     * term still depends on. See the class documentation.</p>
     *
     * <p>The level is installed stale, whatever it holds: nothing here can say that the filter
     * describes the segment registered under the id, so it covers the segment only once a
     * rebuild has refilled it and completed.</p>
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
            markSegmentStale(segmentId);
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
     * <p>The id's trust goes with its level, so a rebuild still running against the removed
     * level cannot complete, and a level registered under the id later starts stale.</p>
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
            levelStates.remove(segmentId);
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
                    LOGGER.warn("Corpus filter is full at {} entries; it no longer tells a "
                        + "query which segments hold its terms, so segments are searched in "
                        + "sketch order alone. Raise "
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
     * is full it cannot record a new term, and a term it has no record of reads as held by no
     * segment. The filter stops speaking for any segment once that happens, which is invisible
     * from a query's answer unless the sizing can be read back.</p>
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
     * - that would make the corpus filter answer "absent" for something present, and a reader
     * of a covered level trusts exactly that answer.
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
     * segment from the shared corpus filter, which then answers "absent" for an item that
     * other segment holds.</p>
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
     * Whether this filter's silence about a term may be read as the segment lacking it.
     *
     * <p>A pre-filter earns the right to say a segment lacks a term only by holding everything
     * that segment holds. Five things take that right away, and all five are silent: no rebuild may
     * have vouched for the level yet, so it holds whatever writes happened to reach it; the
     * segment may hold nothing yet, so the filter has looked at nothing rather than looked and
     * found nothing; a level may have refused a term for want of room, so the term is absent from
     * a filter whose absences a reader would otherwise trust; the level may be
     * {@linkplain #markSegmentStale(String) stale}, holding the terms of writes but not of the
     * entries a split, a restart or a compaction put in the segment; and the corpus level - the
     * first one consulted - may be full, which makes every segment's verdict worthless at
     * once.</p>
     *
     * <p>When this returns false the caller must treat the segment as one the filter knows
     * nothing about. The query pipeline, which only orders segments by the filter's answer,
     * then leaves the segment among those it searches first.</p>
     *
     * <p>A reader that combines this with lookups made at other moments wants
     * {@link #coverageStamp(String)} instead; see "Asking "does this segment lack every term"
     * across several lookups".</p>
     *
     * @param segmentId The segment's id
     * @return true when the filter's silence about a term genuinely means the segment lacks it
     */
    public boolean coversSegment(String segmentId) {
        return coverageStamp(segmentId) != NOT_COVERED;
    }

    /**
     * The stamp {@link #coverageStamp(String)} returns for a segment the filter does not cover.
     */
    public static final long NOT_COVERED = 0L;

    /**
     * Whether this filter may be used to rule a segment out, as a stamp that changes whenever
     * the answer could have.
     *
     * <p>{@link #NOT_COVERED} when {@link #coversSegment(String)} would say false. Otherwise a
     * positive value that stays the same for as long as the level stays trusted, and that no
     * later period of trust for the id will repeat: every mark, every completed rebuild and every
     * replacement of the level moves it. A reader that takes the stamp before its lookups and
     * again after, and finds the same covering value both times, knows the level was trusted for
     * the whole of every lookup in between - so a term those lookups did not find in it is a
     * term the segment does not hold.</p>
     *
     * <p>Lock-free: two map reads and a counter read.</p>
     *
     * @param segmentId The segment's id, which may be null
     * @return The stamp, or {@link #NOT_COVERED}
     */
    public long coverageStamp(String segmentId) {
        if (segmentId == null || corpusSaturated) {
            return NOT_COVERED;
        }
        Long state = levelStates.get(segmentId);
        if (state == null || state <= 0 || saturatedSegments.contains(segmentId)) {
            return NOT_COVERED;
        }
        CuckooFilter segmentFilter = segmentFilters.get(segmentId);
        return segmentFilter != null && segmentFilter.count() > 0 ? state : NOT_COVERED;
    }

    /**
     * Declares that a segment's level no longer describes the segment.
     *
     * <p>From this call until a rebuild that begins after it completes,
     * {@link #coversSegment(String)} answers false and the segment is searched for every query.
     * The level itself is left as it is and keeps taking inserts, so nothing recorded meanwhile
     * is lost; the rebuild that follows empties and refills it. The segment need not have a
     * level yet: the mark is kept by id, and a level created for the id afterwards is born stale
     * in any case.</p>
     *
     * <p>Lock-free and cheap, so it can be called on a path that must not block, such as the
     * one that publishes a split.</p>
     *
     * @param segmentId The segment's id
     * @return The generation of this mark
     * @throws NullPointerException if segmentId is null
     */
    public long markSegmentStale(String segmentId) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        long generation = generations.incrementAndGet();
        levelStates.put(segmentId, -generation);
        return generation;
    }

    /**
     * Marks a segment's level stale and empties it, ready to be refilled from the segment.
     *
     * <p>The caller then {@linkplain #insert(byte[], String) inserts} the terms of every entry
     * the segment holds and passes the returned generation to
     * {@link #completeSegmentRebuild(String, long)}. Writes that reach the segment meanwhile keep
     * inserting into the same level, which is what makes the refill safe against them: a write
     * recorded before the level was emptied was stored before the caller's scan of the segment
     * began, so the scan sees it, and one recorded after lands in the emptied level directly.</p>
     *
     * <p>The level is emptied in place, so every holder of it keeps holding the live one. A
     * segment with no level gets a new, empty one. A refusal recorded against the old contents
     * is dropped with them: the refill will record its own.</p>
     *
     * @param segmentId The segment's id
     * @return The generation to complete the rebuild with
     * @throws NullPointerException if segmentId is null
     * @throws IllegalArgumentException if segmentId is empty
     */
    public long beginSegmentRebuild(String segmentId) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentId.isEmpty()) {
            throw new IllegalArgumentException("segmentId must not be empty");
        }
        // One step under the write lock, so no lookup sees the level emptied while it still
        // reads as covering, and so creating a missing level does not mark it a second time
        // after the generation this rebuild is to complete with.
        hierarchyLock.writeLock().lock();
        try {
            CuckooFilter level = levelOf(segmentId);
            long generation = markSegmentStale(segmentId);
            totalEntries.addAndGet(-level.count());
            level.clear();
            saturatedSegments.remove(segmentId);
            return generation;
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Declares a segment's level refilled, so it may rule the segment out again.
     *
     * <p>Only the rebuild that began after the latest mark may do this. A mark made while this
     * rebuild was running - a split or a compaction that changed the segment under it - means
     * the refill describes a segment that no longer exists, and the rebuild that the mark was
     * made for is the one that will vouch for the segment. So does an unregistration: the level
     * this rebuild filled is no longer the segment's.</p>
     *
     * <p>The transition is one compare-and-set from the mark this rebuild made to a coverage
     * stamp no earlier period of trust used, so a reader comparing stamps sees either the stale
     * level or the new stamp, never the old stamp over the refilled level.</p>
     *
     * @param segmentId  The segment's id
     * @param generation The generation {@link #beginSegmentRebuild(String)} returned
     * @return true if the level covers the segment again, false if a later mark superseded it
     * @throws NullPointerException if segmentId is null
     */
    public boolean completeSegmentRebuild(String segmentId, long generation) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        return levelStates.replace(segmentId, -generation, generations.incrementAndGet());
    }

    /**
     * Whether a segment's level is waiting to be refilled from the segment.
     *
     * @param segmentId The segment's id
     * @return true between a {@link #markSegmentStale(String)} - or the creation of the level,
     *         which marks it - and the rebuild that clears it
     */
    public boolean isSegmentStale(String segmentId) {
        if (segmentId == null) {
            return false;
        }
        Long state = levelStates.get(segmentId);
        return state != null && state < 0;
    }

    /**
     * Whether the corpus level has run out of room.
     *
     * <p>Once it has, the filter speaks for no segment and queries order segments by their
     * sketches alone. Sized by {@code excalibur.filter.corpus-capacity}; an operator seeing this
     * wants to raise it and restart.</p>
     *
     * @return true when a term has been refused by the corpus filter
     */
    public boolean isSaturated() {
        return corpusSaturated;
    }

    /**
     * Segments this filter no longer speaks for because a term was refused for them.
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
