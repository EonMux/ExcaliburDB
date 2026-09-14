package excalibur.shard.allocator;

import io.excalibur.core.hash.XxHash64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hash ring with virtual nodes for segment-to-volume assignment.
 *
 * <p>This implementation provides deterministic assignment of segments to volumes
 * using consistent hashing with virtual nodes for improved distribution. The ring
 * supports dynamic addition and removal of volumes with minimal remapping.</p>
 *
 * <h2>Virtual Nodes</h2>
 * <p>Each physical volume is represented by multiple virtual nodes (default 150)
 * on the hash ring. This provides:</p>
 * <ul>
 *   <li>Better distribution of segments across volumes</li>
 *   <li>Reduced load imbalance when volumes are added or removed</li>
 *   <li>More predictable remapping behavior during topology changes</li>
 * </ul>
 *
 * <h2>Assignment Algorithm</h2>
 * <p>Segment assignment uses the following algorithm:</p>
 * <ol>
 *   <li>Hash the segment ID using XXHash64</li>
 *   <li>Find the first virtual node on the ring with hash >= segment hash</li>
 *   <li>If no such node exists, wrap around to the first node (ring behavior)</li>
 *   <li>Return the volume path associated with that virtual node</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. Read operations (assignment, getVolumes) are
 * lock-free after acquiring a read lock. Write operations (add/remove volume)
 * acquire a write lock to ensure consistent state.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Virtual node count: Must be positive, default 150 provides good distribution</li>
 *   <li>Ring positions: All hash values are in range [0, Long.MAX_VALUE]</li>
 *   <li>Segment assignment: Deterministic - same segmentId always maps to same volume</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class ConsistentHashRing {

    /**
     * Default number of virtual nodes per physical volume.
     *
     * <p>Domain justification: 150 virtual nodes provides a good balance between
     * distribution quality and memory overhead. With this value, the standard
     * deviation of segment distribution is typically &lt; 10% of the mean.</p>
     */
    public static final int DEFAULT_VIRTUAL_NODES = 150;

    /**
     * Minimum number of virtual nodes per physical volume.
     */
    public static final int MIN_VIRTUAL_NODES = 10;

    /**
     * Maximum number of virtual nodes per physical volume.
     * Limited to prevent excessive memory usage.
     */
    public static final int MAX_VIRTUAL_NODES = 1000;

    /**
     * The hash ring implemented as a sorted map.
     * Key: hash position on the ring
     * Value: VolumeInfo for the volume at that position
     */
    private final NavigableMap<Long, VolumeInfo> ring = new TreeMap<>();

    /**
     * List of all registered volumes for quick access.
     */
    private final List<VolumeInfo> volumes = new ArrayList<>();

    /**
     * Number of virtual nodes per physical volume.
     */
    private final int virtualNodesPerVolume;

    /**
     * Lock for thread-safe access to the ring.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates a new ConsistentHashRing with default virtual node count.
     */
    public ConsistentHashRing() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    /**
     * Creates a new ConsistentHashRing with the specified virtual node count.
     *
     * @param virtualNodesPerVolume Number of virtual nodes per physical volume
     * @throws IllegalArgumentException if virtualNodesPerVolume is out of valid range
     */
    public ConsistentHashRing(int virtualNodesPerVolume) {
        if (virtualNodesPerVolume < MIN_VIRTUAL_NODES || virtualNodesPerVolume > MAX_VIRTUAL_NODES) {
            throw new IllegalArgumentException(
                "Virtual nodes per volume must be between " + MIN_VIRTUAL_NODES + " and " + MAX_VIRTUAL_NODES +
                ", got: " + virtualNodesPerVolume);
        }
        this.virtualNodesPerVolume = virtualNodesPerVolume;
    }

    // ==================== Volume Management ====================

    /**
     * Adds a volume to the hash ring.
     *
     * <p>When a volume is added, it is represented on the ring by multiple
     * virtual nodes. The virtual node positions are computed by hashing
     * "volumeId#nodeIndex" for each virtual node.</p>
     *
     * <p><strong>Remapping:</strong> Adding a volume will cause some segments
     * to be remapped to the new volume. Only segments whose hash falls between
     * the new volume's virtual nodes and the next virtual nodes will be affected.</p>
     *
     * @param volumeId Unique identifier for the volume (e.g., "volume-0", "/data/vol1")
     * @param volumePath Path to the volume directory
     * @return true if the volume was added, false if it already exists
     * @throws NullPointerException if volumeId or volumePath is null
     * @throws IllegalArgumentException if volumeId is empty
     */
    public boolean addVolume(String volumeId, java.nio.file.Path volumePath) {
        if (volumeId == null) {
            throw new NullPointerException("Volume ID cannot be null");
        }
        if (volumeId.isEmpty()) {
            throw new IllegalArgumentException("Volume ID cannot be empty");
        }
        if (volumePath == null) {
            throw new NullPointerException("Volume path cannot be null");
        }

        lock.writeLock().lock();
        try {
            // Check if volume already exists
            for (VolumeInfo info : volumes) {
                if (info.volumeId().equals(volumeId)) {
                    return false; // Already exists
                }
            }

            // Create volume info
            VolumeInfo volumeInfo = new VolumeInfo(volumeId, volumePath);
            volumes.add(volumeInfo);

            // Add virtual nodes to the ring
            for (int i = 0; i < virtualNodesPerVolume; i++) {
                String virtualNodeKey = volumeId + "#" + i;
                long hash = hashKey(virtualNodeKey);
                ring.put(hash, volumeInfo);
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a volume from the hash ring.
     *
     * <p><strong>Remapping:</strong> Removing a volume will cause all segments
     * previously assigned to this volume to be remapped to other volumes.
     * The new assignment is determined by the next virtual node on the ring.</p>
     *
     * @param volumeId Unique identifier for the volume to remove
     * @return true if the volume was removed, false if it didn't exist
     * @throws NullPointerException if volumeId is null
     */
    public boolean removeVolume(String volumeId) {
        if (volumeId == null) {
            throw new NullPointerException("Volume ID cannot be null");
        }

        lock.writeLock().lock();
        try {
            // Find and remove the volume
            VolumeInfo toRemove = null;
            for (VolumeInfo info : volumes) {
                if (info.volumeId().equals(volumeId)) {
                    toRemove = info;
                    break;
                }
            }

            if (toRemove == null) {
                return false; // Not found
            }

            // Remove from volumes list
            volumes.remove(toRemove);

            // Remove all virtual nodes for this volume
            ring.entrySet().removeIf(entry -> entry.getValue().volumeId().equals(volumeId));

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a volume exists in the ring.
     *
     * @param volumeId Volume identifier to check
     * @return true if the volume exists
     */
    public boolean hasVolume(String volumeId) {
        lock.readLock().lock();
        try {
            for (VolumeInfo info : volumes) {
                if (info.volumeId().equals(volumeId)) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets all registered volumes.
     *
     * @return Unmodifiable list of volume information
     */
    public List<VolumeInfo> getVolumes() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(volumes));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the number of registered volumes.
     *
     * @return Volume count
     */
    public int getVolumeCount() {
        lock.readLock().lock();
        try {
            return volumes.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the total number of virtual nodes on the ring.
     *
     * @return Virtual node count
     */
    public int getRingSize() {
        lock.readLock().lock();
        try {
            return ring.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Segment Assignment ====================

    /**
     * Assigns a segment to a volume using consistent hashing.
     *
     * <p>The assignment algorithm:</p>
     * <ol>
     *   <li>Hash the segment ID using XXHash64</li>
     *   <li>Find the first virtual node on the ring with hash >= segment hash</li>
     *   <li>If no such node exists, wrap around to the first node</li>
     *   <li>Return the volume path associated with that virtual node</li>
     * </ol>
     *
     * <p><strong>Deterministic:</strong> The same segment ID will always be
     * assigned to the same volume as long as the ring topology is unchanged.</p>
     *
     * @param segmentId The segment ID to assign
     * @return The path to the assigned volume, or null if no volumes are registered
     */
    public java.nio.file.Path assignSegment(long segmentId) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }

            // Hash the segment ID
            long hash = hashSegmentId(segmentId);

            // Find the first node >= hash (ceiling entry)
            var entry = ring.ceilingEntry(hash);

            if (entry == null) {
                // Wrap around to the first node on the ring
                entry = ring.firstEntry();
            }

            return entry.getValue().volumePath();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Assigns a segment to a volume and returns the volume ID.
     *
     * @param segmentId The segment ID to assign
     * @return The volume ID, or null if no volumes are registered
     */
    public String assignSegmentToVolumeId(long segmentId) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }

            long hash = hashSegmentId(segmentId);
            var entry = ring.ceilingEntry(hash);

            if (entry == null) {
                entry = ring.firstEntry();
            }

            return entry.getValue().volumeId();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the volume information for a specific segment assignment.
     *
     * @param segmentId The segment ID
     * @return The VolumeInfo for the assigned volume, or null if no volumes registered
     */
    public VolumeInfo getAssignedVolume(long segmentId) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }

            long hash = hashSegmentId(segmentId);
            var entry = ring.ceilingEntry(hash);

            if (entry == null) {
                entry = ring.firstEntry();
            }

            return entry.getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Gets the virtual node positions for a volume.
     *
     * <p>This is useful for debugging and monitoring distribution.</p>
     *
     * @param volumeId Volume identifier
     * @return List of hash positions for the volume's virtual nodes
     */
    public List<Long> getVolumeNodePositions(String volumeId) {
        lock.readLock().lock();
        try {
            List<Long> positions = new ArrayList<>();
            for (var entry : ring.entrySet()) {
                if (entry.getValue().volumeId().equals(volumeId)) {
                    positions.add(entry.getKey());
                }
            }
            Collections.sort(positions);
            return positions;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Computes distribution statistics for the ring.
     *
     * <p>For testing and monitoring purposes.</p>
     *
     * @param sampleSize Number of segment IDs to sample
     * @return Distribution statistics
     */
    public DistributionStats computeDistribution(int sampleSize) {
        if (sampleSize <= 0) {
            sampleSize = 10000;
        }

        lock.readLock().lock();
        try {
            if (volumes.isEmpty()) {
                return new DistributionStats(0, 0, 0, 0.0);
            }

            // Count assignments per volume
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (VolumeInfo info : volumes) {
                counts.put(info.volumeId(), 0);
            }

            // Sample segment IDs
            for (long i = 0; i < sampleSize; i++) {
                String volumeId = assignSegmentToVolumeId(i);
                if (volumeId != null) {
                    counts.merge(volumeId, 1, Integer::sum);
                }
            }

            // Compute statistics
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            double sum = 0;

            for (int count : counts.values()) {
                min = Math.min(min, count);
                max = Math.max(max, count);
                sum += count;
            }

            double mean = sum / counts.size();
            double stdDev = 0;

            for (int count : counts.values()) {
                stdDev += Math.pow(count - mean, 2);
            }
            stdDev = Math.sqrt(stdDev / counts.size());

            return new DistributionStats(min, max, (int) mean, stdDev);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears all volumes from the ring.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            ring.clear();
            volumes.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Hashes a key string for ring position.
     *
     * <p>Uses XXHash64 for fast, deterministic hashing.</p>
     *
     * @param key The key to hash
     * @return Hash value in range [0, Long.MAX_VALUE]
     */
    private long hashKey(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        // Mask with Long.MAX_VALUE to ensure non-negative hash values,
        // consistent with the documented range [0, Long.MAX_VALUE]
        return XxHash64.hash(bytes) & Long.MAX_VALUE;
    }

    /**
     * Hashes a segment ID for assignment.
     *
     * @param segmentId The segment ID
     * @return Hash value in range [0, Long.MAX_VALUE]
     */
    private long hashSegmentId(long segmentId) {
        // Mask with Long.MAX_VALUE to ensure non-negative hash values,
        // consistent with the documented range [0, Long.MAX_VALUE]
        return XxHash64.hash(segmentId) & Long.MAX_VALUE;
    }

    // ==================== Inner Classes ====================

    /**
     * Information about a registered volume.
     */
    public static final class VolumeInfo {
        private final String volumeId;
        private final java.nio.file.Path volumePath;

        /**
         * Creates a new VolumeInfo.
         *
         * @param volumeId Unique volume identifier
         * @param volumePath Path to the volume directory
         */
        public VolumeInfo(String volumeId, java.nio.file.Path volumePath) {
            this.volumeId = volumeId;
            this.volumePath = volumePath;
        }

        /**
         * Gets the volume ID.
         *
         * @return Volume identifier
         */
        public String volumeId() {
            return volumeId;
        }

        /**
         * Gets the volume path.
         *
         * @return Path to volume directory
         */
        public java.nio.file.Path volumePath() {
            return volumePath;
        }

        @Override
        public String toString() {
            return "VolumeInfo{" +
                "volumeId='" + volumeId + '\'' +
                ", volumePath=" + volumePath +
                '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VolumeInfo that = (VolumeInfo) o;
            return volumeId.equals(that.volumeId);
        }

        @Override
        public int hashCode() {
            return volumeId.hashCode();
        }
    }

    /**
     * Statistics about segment distribution across volumes.
     */
    public static final class DistributionStats {
        private final int minCount;
        private final int maxCount;
        private final int meanCount;
        private final double stdDev;

        /**
         * Creates distribution statistics.
         *
         * @param minCount Minimum segment count assigned to any volume
         * @param maxCount Maximum segment count assigned to any volume
         * @param meanCount Mean segment count per volume
         * @param stdDev Standard deviation of segment counts
         */
        public DistributionStats(int minCount, int maxCount, int meanCount, double stdDev) {
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.meanCount = meanCount;
            this.stdDev = stdDev;
        }

        /**
         * Gets the minimum segment count.
         *
         * @return Minimum count
         */
        public int minCount() {
            return minCount;
        }

        /**
         * Gets the maximum segment count.
         *
         * @return Maximum count
         */
        public int maxCount() {
            return maxCount;
        }

        /**
         * Gets the mean segment count.
         *
         * @return Mean count
         */
        public int meanCount() {
            return meanCount;
        }

        /**
         * Gets the standard deviation.
         *
         * @return Standard deviation
         */
        public double stdDev() {
            return stdDev;
        }

        /**
         * Gets the coefficient of variation (stdDev / mean).
         *
         * <p>Lower values indicate more uniform distribution.</p>
         *
         * @return Coefficient of variation, or 0 if mean is 0
         */
        public double coefficientOfVariation() {
            if (meanCount == 0) {
                return 0;
            }
            return stdDev / meanCount;
        }

        @Override
        public String toString() {
            return String.format("DistributionStats{min=%d, max=%d, mean=%d, stdDev=%.2f, cv=%.4f}",
                minCount, maxCount, meanCount, stdDev, coefficientOfVariation());
        }
    }
}
