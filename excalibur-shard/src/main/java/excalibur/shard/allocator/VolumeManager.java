package excalibur.shard.allocator;

import io.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages storage volumes and segment-to-volume assignment.
 *
 * <p>VolumeManager provides a high-level API for volume management and
 * segment assignment using a consistent hash ring. It integrates with
 * {@link SegmentCatalog} to maintain volume-to-segment mappings.</p>
 *
 * <h2>Volume Registration</h2>
 * <p>Volumes are registered with the manager and automatically added to
 * the consistent hash ring. Each volume must have a unique identifier
 * and a valid filesystem path.</p>
 *
 * <h2>Segment Assignment</h2>
 * <p>Segments are assigned to volumes using consistent hashing:</p>
 * <ul>
 *   <li>Same segment ID always maps to the same volume (deterministic)</li>
 *   <li>Adding/removing volumes minimally affects existing assignments</li>
 *   <li>Assignment is O(log N) where N is total virtual nodes</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. All public methods are synchronized
 * where necessary to ensure consistent state across concurrent operations.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Volume paths: Must be valid directories on the filesystem</li>
 *   <li>Segment IDs: Must be non-negative long values</li>
 *   <li>Virtual nodes: Default 150 provides good distribution</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see ConsistentHashRing
 * @see SegmentCatalog
 */
public class VolumeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(VolumeManager.class);

    /**
     * Default number of virtual nodes per volume.
     */
    public static final int DEFAULT_VIRTUAL_NODES = ConsistentHashRing.DEFAULT_VIRTUAL_NODES;

    /**
     * The consistent hash ring for segment assignment.
     */
    private final ConsistentHashRing hashRing;

    /**
     * Mapping from volume path to list of assigned segment IDs.
     */
    private final Map<Path, List<Long>> volumeToSegments = new ConcurrentHashMap<>();

    /**
     * Mapping from segment ID to assigned volume path.
     */
    private final Map<Long, Path> segmentToVolume = new ConcurrentHashMap<>();

    /**
     * Optional segment catalog for integration.
     */
    private final SegmentCatalog catalog;

    /**
     * Lock for synchronizing volume operations.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates a new VolumeManager with default virtual node count.
     */
    public VolumeManager() {
        this(null, DEFAULT_VIRTUAL_NODES);
    }

    /**
     * Creates a new VolumeManager with optional catalog integration.
     *
     * @param catalog Optional segment catalog for metadata integration
     */
    public VolumeManager(SegmentCatalog catalog) {
        this(catalog, DEFAULT_VIRTUAL_NODES);
    }

    /**
     * Creates a new VolumeManager with specified virtual node count.
     *
     * @param catalog Optional segment catalog
     * @param virtualNodesPerVolume Number of virtual nodes per volume
     */
    public VolumeManager(SegmentCatalog catalog, int virtualNodesPerVolume) {
        this.hashRing = new ConsistentHashRing(virtualNodesPerVolume);
        this.catalog = catalog;
    }

    // ==================== Volume Registration ====================

    /**
     * Registers a volume for segment assignment.
     *
     * <p>The volume directory is created if it doesn't exist. Once registered,
     * the volume participates in segment assignment via the consistent hash ring.</p>
     *
     * @param volumePath Path to the volume directory
     * @return The volume ID (derived from the path)
     * @throws NullPointerException if volumePath is null
     * @throws IllegalStateException if volume registration fails
     */
    public String registerVolume(Path volumePath) {
        if (volumePath == null) {
            throw new NullPointerException("Volume path cannot be null");
        }

        // Create volume ID from path
        String volumeId = generateVolumeId(volumePath);

        lock.writeLock().lock();
        try {
            // Create directory if it doesn't exist
            try {
                if (!Files.exists(volumePath)) {
                    Files.createDirectories(volumePath);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create volume directory: " + volumePath, e);
            }

            // Add to hash ring
            if (!hashRing.addVolume(volumeId, volumePath)) {
                // Volume already exists
                return volumeId;
            }

            // Initialize segment tracking for this volume (CopyOnWriteArrayList for thread-safe reads)
            volumeToSegments.computeIfAbsent(volumePath, k -> new CopyOnWriteArrayList<>());

            // Register with catalog if available
            if (catalog != null) {
                catalog.registerVolume(volumePath);
            }

            return volumeId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Registers multiple volumes at once.
     *
     * @param volumePaths Paths to volume directories
     * @return List of registered volume IDs
     */
    public List<String> registerVolumes(List<Path> volumePaths) {
        List<String> volumeIds = new ArrayList<>();
        for (Path path : volumePaths) {
            volumeIds.add(registerVolume(path));
        }
        return volumeIds;
    }

    /**
     * Unregisters a volume from the manager.
     *
     * <p><strong>Warning:</strong> Unregistering a volume does NOT reassign
     * its segments. Callers must handle segment migration before unregistering.</p>
     *
     * @param volumePath Path to the volume to unregister
     * @return true if the volume was unregistered, false if not found
     */
    public boolean unregisterVolume(Path volumePath) {
        if (volumePath == null) {
            throw new NullPointerException("Volume path cannot be null");
        }

        String volumeId = generateVolumeId(volumePath);

        lock.writeLock().lock();
        try {
            // Remove from hash ring
            if (!hashRing.removeVolume(volumeId)) {
                return false;
            }

            // Remove segment tracking
            List<Long> segments = volumeToSegments.remove(volumePath);
            if (segments != null) {
                // Remove reverse mappings
                for (Long segmentId : segments) {
                    segmentToVolume.remove(segmentId);
                }
            }

            // Unregister from catalog if available
            if (catalog != null) {
                catalog.unregisterVolume(volumePath);
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a volume is registered.
     *
     * @param volumePath Path to check
     * @return true if the volume is registered
     */
    public boolean hasVolume(Path volumePath) {
        if (volumePath == null) {
            return false;
        }
        String volumeId = generateVolumeId(volumePath);
        return hashRing.hasVolume(volumeId);
    }

    /**
     * Gets all registered volumes.
     *
     * @return List of volume information
     */
    public List<ConsistentHashRing.VolumeInfo> getVolumes() {
        return hashRing.getVolumes();
    }

    /**
     * Gets the number of registered volumes.
     *
     * @return Volume count
     */
    public int getVolumeCount() {
        return hashRing.getVolumeCount();
    }

    // ==================== Segment Assignment ====================

    /**
     * Assigns a segment to a volume using consistent hashing.
     *
     * <p>If the segment has already been assigned, returns the previously
     * assigned volume (assignment is idempotent).</p>
     *
     * @param segmentId The segment ID to assign
     * @return The path to the assigned volume, or null if no volumes registered
     * @throws IllegalStateException if no volumes are registered
     */
    public Path assignSegmentToVolume(long segmentId) {
        // Use write lock since this method mutates segmentToVolume and volumeToSegments
        // via recordAssignment(). A read lock would allow concurrent check-then-act races
        // where the same segment could be assigned and recorded twice.
        lock.writeLock().lock();
        try {
            // Check for existing assignment
            Path existingAssignment = segmentToVolume.get(segmentId);
            if (existingAssignment != null) {
                return existingAssignment;
            }

            // Get assignment from hash ring
            Path assignedVolume = hashRing.assignSegment(segmentId);
            if (assignedVolume == null) {
                return null;
            }

            // Record the assignment
            recordAssignment(segmentId, assignedVolume);

            return assignedVolume;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Assigns a segment to a volume and updates the catalog.
     *
     * <p>This method assigns the segment and updates the segment's volumePath
     * in the catalog if a catalog is configured.</p>
     *
     * @param segmentId The segment ID to assign
     * @return The path to the assigned volume
     */
    public Path assignAndUpdateCatalog(long segmentId) {
        Path volumePath = assignSegmentToVolume(segmentId);

        if (volumePath != null && catalog != null) {
            // Update catalog with volume path
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata != null) {
                metadata.setVolumePath(volumePath);
            }
        }

        return volumePath;
    }

    /**
     * Forces reassignment of a segment to a new volume.
     *
     * <p>This is useful when a volume is being removed and segments
     * need to be migrated to other volumes.</p>
     *
     * @param segmentId The segment ID to reassign
     * @return The new volume path
     */
    public Path reassignSegment(long segmentId) {
        lock.writeLock().lock();
        try {
            // Remove existing assignment
            Path oldVolume = segmentToVolume.remove(segmentId);
            if (oldVolume != null) {
                List<Long> segments = volumeToSegments.get(oldVolume);
                if (segments != null) {
                    segments.remove(segmentId);
                }
            }

            // Get new assignment
            Path newVolume = hashRing.assignSegment(segmentId);
            if (newVolume != null) {
                recordAssignment(segmentId, newVolume);
            }

            return newVolume;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the volume assignment for a segment.
     *
     * @param segmentId The segment ID
     * @return The assigned volume path, or null if not assigned
     */
    public Path getVolumeForSegment(long segmentId) {
        return segmentToVolume.get(segmentId);
    }

    /**
     * Gets all segments assigned to a volume.
     *
     * @param volumePath The volume path
     * @return List of segment IDs assigned to the volume
     */
    public List<Long> getSegmentsForVolume(Path volumePath) {
        List<Long> segments = volumeToSegments.get(volumePath);
        if (segments == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(segments));
    }

    /**
     * Gets the total number of assigned segments.
     *
     * @return Total segment count
     */
    public int getTotalSegmentCount() {
        return segmentToVolume.size();
    }

    /**
     * Gets the segment distribution across volumes.
     *
     * @return Map of volume path to segment count
     */
    public Map<Path, Integer> getSegmentDistribution() {
        Map<Path, Integer> distribution = new HashMap<>();
        for (Map.Entry<Path, List<Long>> entry : volumeToSegments.entrySet()) {
            distribution.put(entry.getKey(), entry.getValue().size());
        }
        return distribution;
    }

    // ==================== Utility Methods ====================

    /**
     * Gets the underlying hash ring for advanced operations.
     *
     * @return The consistent hash ring
     */
    public ConsistentHashRing getHashRing() {
        return hashRing;
    }

    /**
     * Computes distribution statistics for segment assignment.
     *
     * @param sampleSize Number of segments to sample
     * @return Distribution statistics
     */
    public ConsistentHashRing.DistributionStats computeDistribution(int sampleSize) {
        return hashRing.computeDistribution(sampleSize);
    }

    /**
     * Clears all volume and segment assignments.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            hashRing.clear();
            volumeToSegments.clear();
            segmentToVolume.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Generates a unique volume ID from a path.
     *
     * @param volumePath The volume path
     * @return A unique volume ID
     */
    private String generateVolumeId(Path volumePath) {
        // Use the absolute path as the volume ID
        // This ensures uniqueness across different volumes
        return volumePath.toAbsolutePath().toString();
    }

    /**
     * Records a segment-to-volume assignment.
     *
     * <p>Note: Caller must hold appropriate lock.</p>
     *
     * @param segmentId The segment ID
     * @param volumePath The volume path
     */
    private void recordAssignment(long segmentId, Path volumePath) {
        // Update segment -> volume mapping
        segmentToVolume.put(segmentId, volumePath);

        // Update volume -> segments mapping (CopyOnWriteArrayList for thread-safety)
        volumeToSegments.computeIfAbsent(volumePath, k -> new CopyOnWriteArrayList<>())
            .add(segmentId);
    }

    // ==================== Statistics ====================

    /**
     * Gets statistics about the volume manager.
     *
     * @return Statistics snapshot
     */
    public Statistics getStatistics() {
        lock.readLock().lock();
        try {
            return new Statistics(
                getVolumeCount(),
                getTotalSegmentCount(),
                volumeToSegments.size()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Statistics for the VolumeManager.
     */
    public static final class Statistics {
        private final int volumeCount;
        private final int totalSegments;
        private final int activeVolumes;

        /**
         * Creates statistics.
         *
         * @param volumeCount Total number of volumes
         * @param totalSegments Total number of assigned segments
         * @param activeVolumes Number of volumes with at least one segment
         */
        public Statistics(int volumeCount, int totalSegments, int activeVolumes) {
            this.volumeCount = volumeCount;
            this.totalSegments = totalSegments;
            this.activeVolumes = activeVolumes;
        }

        /**
         * Gets the volume count.
         *
         * @return Number of registered volumes
         */
        public int volumeCount() {
            return volumeCount;
        }

        /**
         * Gets the total segment count.
         *
         * @return Number of assigned segments
         */
        public int totalSegments() {
            return totalSegments;
        }

        /**
         * Gets the active volume count.
         *
         * @return Number of volumes with at least one segment
         */
        public int activeVolumes() {
            return activeVolumes;
        }

        @Override
        public String toString() {
            return String.format("Statistics{volumes=%d, segments=%d, activeVolumes=%d}",
                volumeCount, totalSegments, activeVolumes);
        }
    }

    // ==================== Disk Space Tracking ====================

    /**
     * Record representing disk space statistics for a volume.
     *
     * <p>Domain correctness guarantees:</p>
     * <ul>
     *   <li>All byte values are non-negative</li>
     *   <li>usedBytes = totalBytes - availableBytes (when file system reports correctly)</li>
     *   <li>Values represent actual disk capacity, not directory metadata size</li>
     * </ul>
     *
     * @param totalBytes     Total disk capacity in bytes
     * @param usedBytes      Used disk space in bytes (total - available)
     * @param availableBytes Available disk space for the JVM in bytes
     */
    public record VolumeStats(
        long totalBytes,
        long usedBytes,
        long availableBytes
    ) {
        /**
         * Validates domain constraints on construction.
         *
         * @throws IllegalArgumentException if any value is negative
         */
        public VolumeStats {
            if (totalBytes < 0) {
                throw new IllegalArgumentException("totalBytes must be non-negative: " + totalBytes);
            }
            if (usedBytes < 0) {
                throw new IllegalArgumentException("usedBytes must be non-negative: " + usedBytes);
            }
            if (availableBytes < 0) {
                throw new IllegalArgumentException("availableBytes must be non-negative: " + availableBytes);
            }
        }

        /**
         * Returns the usage ratio (used / total).
         *
         * @return Usage ratio in range [0.0, 1.0], or 0.0 if total is 0
         */
        public double usageRatio() {
            if (totalBytes == 0) {
                return 0.0;
            }
            return Math.min(1.0, (double) usedBytes / totalBytes);
        }

        /**
         * Returns the available ratio (available / total).
         *
         * @return Available ratio in range [0.0, 1.0], or 1.0 if total is 0
         */
        public double availableRatio() {
            if (totalBytes == 0) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, (double) availableBytes / totalBytes));
        }

        @Override
        public String toString() {
            return String.format("VolumeStats{total=%d GB, used=%d GB, available=%d GB, usage=%.1f%%}",
                totalBytes / (1024 * 1024 * 1024),
                usedBytes / (1024 * 1024 * 1024),
                availableBytes / (1024 * 1024 * 1024),
                usageRatio() * 100);
        }
    }

    /**
     * Gets disk space statistics for a specific volume.
     *
     * <p>Uses {@link Files#getFileStore(Path)} to retrieve actual disk capacity
     * and usage information, not directory metadata size.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>totalBytes: Total disk capacity from {@link FileStore#getTotalSpace()}</li>
     *   <li>availableBytes: Available space for JVM from {@link FileStore#getUsableSpace()}</li>
     *   <li>usedBytes: Computed as totalBytes - availableBytes</li>
     * </ul>
     *
     * @param volumePath The path to the volume directory
     * @return VolumeStats with disk space information, or null if the volume doesn't exist or an error occurs
     * @throws NullPointerException if volumePath is null
     */
    public VolumeStats getVolumeStats(Path volumePath) {
        if (volumePath == null) {
            throw new NullPointerException("Volume path cannot be null");
        }

        // Check if volume is registered
        if (!hasVolume(volumePath)) {
            return null;
        }

        try {
            // Use Files.getFileStore() to get actual disk information
            // This returns the file store for the underlying filesystem,
            // not the directory metadata size
            FileStore store = Files.getFileStore(volumePath);

            long totalBytes = store.getTotalSpace();
            long availableBytes = store.getUsableSpace();
            long usedBytes = totalBytes - availableBytes;

            // Ensure non-negative usedBytes (can happen with quotas or special filesystems)
            usedBytes = Math.max(0, usedBytes);

            return new VolumeStats(totalBytes, usedBytes, availableBytes);
        } catch (IOException e) {
            LOGGER.debug("Could not get file store for volume {}: {}", volumePath, e.getMessage());
            return null;
        }
    }

    /**
     * Gets disk space statistics for all registered volumes.
     *
     * <p>Aggregates statistics from all volumes registered with this manager.
     * Volumes that cannot be queried are excluded from the result.</p>
     *
     * <p><strong>Thread Safety:</strong> This method acquires a read lock to
     * safely iterate over registered volumes.</p>
     *
     * @return Map of volume path to VolumeStats, never null
     */
    public Map<Path, VolumeStats> getAllVolumeStats() {
        Map<Path, VolumeStats> stats = new HashMap<>();

        lock.readLock().lock();
        try {
            for (Path volumePath : volumeToSegments.keySet()) {
                VolumeStats volumeStats = getVolumeStatsInternal(volumePath);
                if (volumeStats != null) {
                    stats.put(volumePath, volumeStats);
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        return stats;
    }

    /**
     * Gets aggregated disk space statistics across all volumes.
     *
     * <p>Computes the sum of total, used, and available bytes across all
     * registered volumes. This is useful for monitoring overall storage health.</p>
     *
     * @return Aggregated VolumeStats, or VolumeStats(0, 0, 0) if no volumes registered
     */
    public VolumeStats getAggregatedVolumeStats() {
        Map<Path, VolumeStats> allStats = getAllVolumeStats();

        long totalBytes = 0;
        long usedBytes = 0;
        long availableBytes = 0;

        for (VolumeStats stats : allStats.values()) {
            totalBytes += stats.totalBytes();
            usedBytes += stats.usedBytes();
            availableBytes += stats.availableBytes();
        }

        return new VolumeStats(totalBytes, usedBytes, availableBytes);
    }

    /**
     * Checks if a volume has sufficient disk space for a write operation.
     *
     * <p><strong>Domain Correctness:</strong> Returns false if:</p>
     * <ul>
     *   <li>Volume is not registered</li>
     *   <li>Volume stats cannot be retrieved</li>
     *   <li>Available space is less than required bytes</li>
     * </ul>
     *
     * @param volumePath   The volume path to check
     * @param requiredBytes Minimum required bytes
     * @return true if the volume has at least requiredBytes available
     */
    public boolean hasAvailableSpace(Path volumePath, long requiredBytes) {
        if (requiredBytes < 0) {
            throw new IllegalArgumentException("Required bytes must be non-negative: " + requiredBytes);
        }

        VolumeStats stats = getVolumeStats(volumePath);
        if (stats == null) {
            return false;
        }

        return stats.availableBytes() >= requiredBytes;
    }

    /**
     * Finds the volume with the most available disk space.
     *
     * <p>Useful for load balancing writes across volumes.</p>
     *
     * @return Path to the volume with most available space, or null if no volumes registered
     */
    public Path getVolumeWithMostSpace() {
        Map<Path, VolumeStats> allStats = getAllVolumeStats();

        Path bestVolume = null;
        long maxAvailable = -1;

        for (Map.Entry<Path, VolumeStats> entry : allStats.entrySet()) {
            long available = entry.getValue().availableBytes();
            if (available > maxAvailable) {
                maxAvailable = available;
                bestVolume = entry.getKey();
            }
        }

        return bestVolume;
    }

    /**
     * Internal method to get volume stats without lock acquisition.
     *
     * <p>Caller must hold appropriate lock.</p>
     *
     * @param volumePath The volume path
     * @return VolumeStats or null if unavailable
     */
    private VolumeStats getVolumeStatsInternal(Path volumePath) {
        try {
            FileStore store = Files.getFileStore(volumePath);

            long totalBytes = store.getTotalSpace();
            long availableBytes = store.getUsableSpace();
            long usedBytes = Math.max(0, totalBytes - availableBytes);

            return new VolumeStats(totalBytes, usedBytes, availableBytes);
        } catch (IOException e) {
            LOGGER.debug("Could not get file store for volume {}: {}", volumePath, e.getMessage());
            return null;
        }
    }
}
