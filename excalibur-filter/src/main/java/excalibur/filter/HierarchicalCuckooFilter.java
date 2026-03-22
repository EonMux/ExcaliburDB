package excalibur.filter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A hierarchical Cuckoo filter structure for efficient segment-level membership testing.
 *
 * <h2>Structure</h2>
 * <p>The hierarchy consists of three levels:
 * <ul>
 *   <li><strong>Corpus (root):</strong> Top-level filter for the entire corpus</li>
 *   <li><strong>Topic Cluster (intermediate):</strong> Filters for topic/cluster groups</li>
 *   <li><strong>Segment (leaf):</strong> Per-segment filters</li>
 * </ul>
 *
 * <h2>Query Flow</h2>
 * <p>Queries check the coarsest filter first, drilling down on positives:
 * <ol>
 *   <li>Check corpus filter - if negative, entry definitely not in corpus</li>
 *   <li>Check relevant cluster filters - skip segments in negative clusters</li>
 *   <li>Check segment filters - identify candidate segments</li>
 * </ol>
 *
 * <h2>Deletion with Propagation</h2>
 * <p>When a document is deleted, its fingerprint is removed from all levels:
 * <ol>
 *   <li>Delete from segment filter (leaf level)</li>
 *   <li>Delete from cluster filter (intermediate level)</li>
 *   <li>Delete from corpus filter (root level)</li>
 * </ol>
 * <p>This ensures no stale false positives from tombstoned entries.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>All filter registrations/removals use ConcurrentHashMap</li>
 *   <li>All mutations use per-filter striped locks via CuckooFilter</li>
 *   <li>Hierarchy modifications use read-write locks</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Segment IDs are non-null, non-empty strings</li>
 *   <li>Cluster IDs are non-null strings</li>
 *   <li>Each segment belongs to exactly one cluster</li>
 *   <li>Deletion from any level returns false if not found (idempotent)</li>
 *   <li>All byte[] keys are converted using UTF-8 encoding</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 3.0
 * @see CuckooFilter
 */
public class HierarchicalCuckooFilter {

    private static final int DEFAULT_FINGERPRINT_BITS = 13;
    private static final int DEFAULT_CLUSTER_CAPACITY = 100_000;
    private static final int DEFAULT_SEGMENT_CAPACITY = 10_000;

    private final CuckooFilter corpusFilter;
    private final Map<String, CuckooFilter> clusterFilters;
    private final Map<String, CuckooFilter> segmentFilters;
    private final Map<String, String> segmentToCluster;

    private final int fingerprintBits;
    private final int clusterCapacity;
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
        this(corpusCapacity, DEFAULT_FINGERPRINT_BITS, 
             DEFAULT_CLUSTER_CAPACITY, DEFAULT_SEGMENT_CAPACITY);
    }

    /**
     * Creates a new HierarchicalCuckooFilter with custom capacities.
     *
     * @param corpusCapacity Expected number of entries in the corpus
     * @param fingerprintBits Number of bits per fingerprint (8-16)
     * @param clusterCapacity Expected entries per cluster
     * @param segmentCapacity Expected entries per segment
     * @throws IllegalArgumentException if any capacity is not positive
     */
    public HierarchicalCuckooFilter(long corpusCapacity, int fingerprintBits,
                                     int clusterCapacity, int segmentCapacity) {
        if (corpusCapacity <= 0) {
            throw new IllegalArgumentException("corpusCapacity must be positive: " + corpusCapacity);
        }
        if (clusterCapacity <= 0) {
            throw new IllegalArgumentException("clusterCapacity must be positive: " + clusterCapacity);
        }
        if (segmentCapacity <= 0) {
            throw new IllegalArgumentException("segmentCapacity must be positive: " + segmentCapacity);
        }

        this.fingerprintBits = fingerprintBits;
        this.clusterCapacity = clusterCapacity;
        this.segmentCapacity = segmentCapacity;
        this.corpusFilter = CuckooFilter.create(corpusCapacity, fingerprintBits);
        this.clusterFilters = new ConcurrentHashMap<>();
        this.segmentFilters = new ConcurrentHashMap<>();
        this.segmentToCluster = new ConcurrentHashMap<>();
        this.hierarchyLock = new ReentrantReadWriteLock();
        this.totalEntries = new AtomicLong(0);
    }

    /**
     * Creates a HierarchicalCuckooFilter from an existing corpus filter.
     *
     * @param corpusFilter The corpus-level Cuckoo filter
     * @param clusterCapacity Expected entries per cluster
     * @param segmentCapacity Expected entries per segment
     * @throws NullPointerException if corpusFilter is null
     */
    public HierarchicalCuckooFilter(CuckooFilter corpusFilter, 
                                     int clusterCapacity, int segmentCapacity) {
        Objects.requireNonNull(corpusFilter, "corpusFilter must not be null");
        if (clusterCapacity <= 0) {
            throw new IllegalArgumentException("clusterCapacity must be positive");
        }
        if (segmentCapacity <= 0) {
            throw new IllegalArgumentException("segmentCapacity must be positive");
        }

        this.corpusFilter = corpusFilter;
        this.fingerprintBits = corpusFilter.fingerprintBits();
        this.clusterCapacity = clusterCapacity;
        this.segmentCapacity = segmentCapacity;
        this.clusterFilters = new ConcurrentHashMap<>();
        this.segmentFilters = new ConcurrentHashMap<>();
        this.segmentToCluster = new ConcurrentHashMap<>();
        this.hierarchyLock = new ReentrantReadWriteLock();
        this.totalEntries = new AtomicLong(0);
    }

    /**
     * Registers a new segment filter in the hierarchy.
     *
     * <p>If the cluster doesn't exist, it will be created automatically.</p>
     *
     * @param segmentId Unique segment identifier
     * @param clusterId Cluster this segment belongs to
     * @return The created or existing segment filter
     * @throws NullPointerException if segmentId or clusterId is null
     * @throws IllegalArgumentException if segmentId is empty
     */
    public CuckooFilter registerSegment(String segmentId, String clusterId) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        if (segmentId.isEmpty()) {
            throw new IllegalArgumentException("segmentId must not be empty");
        }

        hierarchyLock.writeLock().lock();
        try {
            segmentToCluster.put(segmentId, clusterId);

            clusterFilters.computeIfAbsent(clusterId, k -> 
                CuckooFilter.create(clusterCapacity, fingerprintBits));

            return segmentFilters.computeIfAbsent(segmentId, k ->
                CuckooFilter.create(segmentCapacity, fingerprintBits));
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Registers an existing Cuckoo filter for a segment.
     *
     * @param segmentId Unique segment identifier
     * @param clusterId Cluster this segment belongs to
     * @param filter The Cuckoo filter for this segment
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if segmentId is empty
     */
    public void registerSegment(String segmentId, String clusterId, CuckooFilter filter) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        if (segmentId.isEmpty()) {
            throw new IllegalArgumentException("segmentId must not be empty");
        }

        hierarchyLock.writeLock().lock();
        try {
            segmentToCluster.put(segmentId, clusterId);
            clusterFilters.computeIfAbsent(clusterId, k -> 
                CuckooFilter.create(clusterCapacity, fingerprintBits));
            segmentFilters.put(segmentId, filter);
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Unregisters a segment from the hierarchy.
     *
     * <p>This removes the segment filter but does NOT delete entries from
     * cluster or corpus filters. Use {@link #deleteFromHierarchy(byte[])}
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
            segmentToCluster.remove(segmentId);
            return removed != null;
        } finally {
            hierarchyLock.writeLock().unlock();
        }
    }

    /**
     * Inserts an item into all levels of the hierarchy.
     *
     * <p>The item is inserted into:
     * <ol>
     *   <li>The segment filter</li>
     *   <li>The cluster filter</li>
     *   <li>The corpus filter</li>
     * </ol>
     *
     * @param item The item to insert
     * @param segmentId The segment ID
     * @return true if insertion succeeded at all levels
     * @throws NullPointerException if item or segmentId is null
     * @throws IllegalStateException if segment is not registered
     */
    public boolean insert(byte[] item, String segmentId) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        hierarchyLock.readLock().lock();
        try {
            String clusterId = segmentToCluster.get(segmentId);
            if (clusterId == null) {
                throw new IllegalStateException("Segment not registered: " + segmentId);
            }

            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
            CuckooFilter clusterFilter = clusterFilters.get(clusterId);

            if (segmentFilter == null || clusterFilter == null) {
                throw new IllegalStateException("Filter missing for segment: " + segmentId);
            }

            boolean segmentOk = segmentFilter.insert(item);
            if (!segmentOk) {
                return false;
            }

            boolean clusterOk = clusterFilter.insert(item);
            boolean corpusOk = corpusFilter.insert(item);

            if (!clusterOk || !corpusOk) {
                segmentFilter.delete(item);
                if (clusterOk) {
                    clusterFilter.delete(item);
                }
                return false;
            }

            totalEntries.incrementAndGet();
            return true;
        } finally {
            hierarchyLock.readLock().unlock();
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
     * Checks if an item might be present in a specific segment.
     *
     * <p>This checks the full hierarchy: corpus → cluster → segment.</p>
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
            String clusterId = segmentToCluster.get(segmentId);
            if (clusterId == null) {
                return false;
            }

            CuckooFilter clusterFilter = clusterFilters.get(clusterId);
            if (clusterFilter != null && !clusterFilter.contains(item)) {
                return false;
            }

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
     * <p>This method efficiently prunes the search space by:
     * <ol>
     *   <li>Checking corpus filter first (early exit if negative)</li>
     *   <li>Checking cluster filters to narrow candidates</li>
     *   <li>Checking segment filters for final candidates</li>
     * </ol>
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
            for (Map.Entry<String, CuckooFilter> clusterEntry : clusterFilters.entrySet()) {
                if (clusterEntry.getValue().contains(item)) {
                    String clusterId = clusterEntry.getKey();
                    for (Map.Entry<String, String> segmentEntry : segmentToCluster.entrySet()) {
                        if (segmentEntry.getValue().equals(clusterId)) {
                            String segmentId = segmentEntry.getKey();
                            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
                            if (segmentFilter != null && segmentFilter.contains(item)) {
                                candidates.add(segmentId);
                            }
                        }
                    }
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
     * It removes the entry from:
     * <ol>
     *   <li>The segment filter</li>
     *   <li>The cluster filter</li>
     *   <li>The corpus filter</li>
     * </ol>
     *
     * <p>This ensures queries issued after deletion will not consider the entry
     * as a candidate, preventing stale false positives.</p>
     *
     * @param item The item to delete
     * @param segmentId The segment ID where the item was stored
     * @return true if the item was found and deleted from at least one level
     * @throws NullPointerException if item or segmentId is null
     */
    public boolean deleteFromHierarchy(byte[] item, String segmentId) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");

        boolean deleted = false;

        hierarchyLock.readLock().lock();
        try {
            String clusterId = segmentToCluster.get(segmentId);
            
            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
            if (segmentFilter != null && segmentFilter.delete(item)) {
                deleted = true;
            }

            if (clusterId != null) {
                CuckooFilter clusterFilter = clusterFilters.get(clusterId);
                if (clusterFilter != null && clusterFilter.delete(item)) {
                    deleted = true;
                }
            }

            if (corpusFilter.delete(item)) {
                deleted = true;
            }

            if (deleted) {
                totalEntries.decrementAndGet();
            }
        } finally {
            hierarchyLock.readLock().unlock();
        }

        return deleted;
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

        hierarchyLock.readLock().lock();
        try {
            for (Map.Entry<String, CuckooFilter> segmentEntry : segmentFilters.entrySet()) {
                if (segmentEntry.getValue().delete(item)) {
                    deleted = true;
                    String segmentId = segmentEntry.getKey();
                    String clusterId = segmentToCluster.get(segmentId);
                    if (clusterId != null) {
                        CuckooFilter clusterFilter = clusterFilters.get(clusterId);
                        if (clusterFilter != null) {
                            clusterFilter.delete(item);
                        }
                    }
                    break;
                }
            }

            if (deleted && corpusFilter.delete(item)) {
                totalEntries.decrementAndGet();
            }
        } finally {
            hierarchyLock.readLock().unlock();
        }

        return deleted;
    }

    /**
     * Deletes multiple items from the hierarchy in batch.
     *
     * <p>This is more efficient than calling {@link #deleteFromHierarchy(byte[], String)}
     * multiple times when deleting many items from the same segment.</p>
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

        hierarchyLock.readLock().lock();
        try {
            String clusterId = segmentToCluster.get(segmentId);
            CuckooFilter segmentFilter = segmentFilters.get(segmentId);
            CuckooFilter clusterFilter = clusterId != null ? clusterFilters.get(clusterId) : null;

            for (byte[] item : items) {
                boolean deleted = false;

                if (segmentFilter != null && segmentFilter.delete(item)) {
                    deleted = true;
                }

                if (clusterFilter != null && clusterFilter.delete(item)) {
                    deleted = true;
                }

                if (corpusFilter.delete(item)) {
                    deleted = true;
                }

                if (deleted) {
                    deletedCount++;
                    totalEntries.decrementAndGet();
                }
            }
        } finally {
            hierarchyLock.readLock().unlock();
        }

        return deletedCount;
    }

    /**
     * Removes all entries for a segment from the hierarchy.
     *
     * <p>This is used when a segment is tombstoned. It removes the segment
     * filter but does NOT attempt to delete individual entries from cluster
     * or corpus filters (they may have false positives from other segments).</p>
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
     * Returns the segment filter for a segment.
     *
     * @param segmentId The segment ID
     * @return The segment filter, or null if not registered
     */
    public CuckooFilter getSegmentFilter(String segmentId) {
        return segmentFilters.get(segmentId);
    }

    /**
     * Returns the cluster filter for a cluster.
     *
     * @param clusterId The cluster ID
     * @return The cluster filter, or null if not found
     */
    public CuckooFilter getClusterFilter(String clusterId) {
        return clusterFilters.get(clusterId);
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
     * Returns the total number of clusters.
     *
     * @return Cluster count
     */
    public int getClusterCount() {
        return clusterFilters.size();
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
            "HierarchicalCuckooFilter[segments=%d, clusters=%d, entries=%d, corpusLoadFactor=%.4f]",
            getSegmentCount(), getClusterCount(), getTotalEntries(), getCorpusLoadFactor());
    }
}
