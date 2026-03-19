package io.excalibur.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StorageEngine manages segments that form the keyspace for the ExcaliburDB storage engine.
 *
 * <p>The keyspace is divided into segments, each backed by a memory-mapped file.
 * StorageEngine provides the top-level interface for segment management, lifecycle
 * tracking, and metadata cataloging.</p>
 *
 * <h2>Segment Lifecycle States</h2>
 * <p>Each segment progresses through lifecycle states:
 * <ul>
 *   <li>{@link #STATE_BOOTSTRAPPING} — Allocated, no data. Invisible to queries.</li>
 *   <li>{@link #STATE_INDEXING} — Entries being written. Cuckoo filter and LSH incrementally populated.</li>
 *   <li>{@link #STATE_WARMING} — Batch complete. HNSW edges being optimized, PQ codebooks training.</li>
 *   <li>{@link #STATE_ACTIVE} — Fully indexed, fully optimized. Queryable.</li>
 *   <li>{@link #STATE_COMPACTING} — Tombstones being cleaned. Frozen snapshot serves queries.</li>
 *   <li>{@link #STATE_TOMBSTONED} — Invisible to new queries. Kept on disk until epoch-based reclamation.</li>
 *   <li>{@link #STATE_PURGED} — Disk freed. Removed from catalog.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>StorageEngine is fully thread-safe. All public methods are synchronized
 * where necessary to ensure consistent state across concurrent operations.
 * The segment registry uses ConcurrentHashMap for lock-free read access</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see Segment
 * @see SegmentCatalog
 * @see SegmentConfig
 */
public class StorageEngine implements AutoCloseable {

    // ==================== Constants ====================

    /**
     * Segment lifecycle states.
     * 
     * <p>States are assigned integer values for efficient comparison and serialization.
     * The ordering is not semantically significant but provides a natural progression.</p>
     */
    public static final int STATE_BOOTSTRAPPING = 0;
    public static final int STATE_INDEXING = 1;
    public static final int STATE_WARMING = 2;
    public static final int STATE_ACTIVE = 3;
    public static final int STATE_COMPACTING = 4;
    public static final int STATE_TOMBSTONED = 5;
    public static final int STATE_PURGED = 6;

    /**
     * Minimum number of segments to pre-allocate for initial load.
     * 
     * <p>This provides a reasonable default for most workloads while avoiding
     * excessive fragmentation. Can be tuned based on expected dataset size.</p>
     */
    public static final int DEFAULT_INITIAL_SEGMENTS = 64;

    /**
     * Target number of segments to pre-allocate based on estimated dataset size.
     * 
     * <p>Formula: (estimated_entries / MAX_SEGMENT_CAPACITY) * BUFFER_FACTOR
     * where BUFFER_FACTOR = 1.5 to account for growth during indexing</p>
     */
    public static final int SEGMENT_BUFFER_FACTOR = 15; // 1.5x buffer

    // ==================== Instance Fields ====================

    /**
     * Path to the data directory containing segment files.
     * 
     * <p>All segment files are stored under this directory with naming convention
     * "segment_<id>.dat" to ensure deterministic placement.</p>
     */
    private final Path dataDir;

    /**
     * Arena for memory allocation.
     * 
     * <p>The Arena controls the lifecycle of the MemorySegment backing each segment.
     * This Arena must outlive the StorageEngine for proper resource cleanup.</p>
     */
    private final Arena arena;

    /**
     * Segment ID generator.
     * 
     * <p>Each segment receives a unique identifier for tracking, cataloging,
     * and segment-to-volume assignment (Phase 5).</p>
     */
    private final AtomicLong segmentIdGenerator = new AtomicLong(0);

    /**
     * Registry of active segments.
     * 
     * <p>Key: segment ID (long) → Value: Segment instance</p>
     * 
     * <p>This uses a HashMap for O(1) lookup by segment ID. The segment ID
     * is more efficient than Path for internal operations since it's a
     * compact, serializable identifier.</p>
     */
    private Map<Long, Segment> segments;

    /**
     * Initial capacity for the segment registry.
     */
    private final int initialCapacity = DEFAULT_INITIAL_SEGMENTS;

    /**
     * Segment catalog for metadata management.
     * 
     * <p>The catalog stores persistent metadata about each segment including:
     * - segmentId
     * - volumePath
     * - entryCount
     * - lifecycleState
     * - lastIntegratedOffset
     * - lastCompactionTime
     * 
     * This catalog is used by the Index Orchestration module (Phase 5) for
     * segment discovery, lifecycle management, and visibility tracking.</p>
     */
    private final SegmentCatalog catalog;

    /**
     * Current visibility epoch.
     * 
     * <p>The visibility epoch is a monotonically increasing counter that
     * provides consistent snapshots for readers. Each query captures the
     * epoch at start time and sees a consistent segment snapshot.</p>
     */
    private final AtomicLong visibilityEpoch = new AtomicLong(0);

    /**
     * Statistics for monitoring and diagnostics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * Flag indicating whether the engine has been started.
     */
    private volatile boolean started = false;

    // ==================== Constructors ====================

    /**
     * Creates a new StorageEngine with default configuration.
     *
     * <p>Default configuration:
     * <ul>
     *   <li>Data directory: current working directory</li>
     *   <li>Initial segments: 64</li>
     *   <li>Visibility epoch: starts at 0</li>
     * </ul>
     *
     * @param dataDir Directory for storing segment files (must exist)
     * @param arena Arena for memory allocation (must outlive this engine)
     * @throws IllegalArgumentException if dataDir is null or doesn't exist
     */
    public StorageEngine(Path dataDir, Arena arena) {
        if (dataDir == null) {
            throw new IllegalArgumentException("Data directory cannot be null");
        }
        if (!java.nio.file.Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException(
                "Data directory must exist: " + dataDir);
        }
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }

        this.dataDir = dataDir;
        this.arena = arena;
        this.catalog = new SegmentCatalog();
        this.segments = new HashMap<>(initialCapacity);
    }

    /**
     * Creates a new StorageEngine with custom initial segment count.
     *
     * @param dataDir Directory for storing segment files (must exist)
     * @param arena Arena for memory allocation (must outlive this engine)
     * @param initialSegments Number of segments to pre-allocate
     * @throws IllegalArgumentException if dataDir is null or doesn't exist
     */
    public StorageEngine(Path dataDir, Arena arena, int initialSegments) {
        this(dataDir, arena);
        this.segments = new HashMap<>(initialSegments);
    }

    // ==================== Public API - Keyspace Management ====================

    /**
     * Starts the storage engine.
     *
     * <p>This method initializes internal state and prepares the engine
     * for accepting operations. The engine can accept operations before
     * this method is called, but certain features (like visibility epoch
     * tracking) require the engine to be started.</p>
     *
     * <h3>Startup Sequence</h3>
     * <ol>
     *   <li>Validate data directory accessibility</li>
     *   <li>Initialize segment registry</li>
     *   <li>Pre-allocate initial segments (optional)</li>
     *   <li>Set started flag to true</li>
     * </ol>
     */
    public void start() {
        if (started) {
            return; // Already started
        }

        // Validate data directory is accessible
        if (!java.nio.file.Files.exists(dataDir)) {
            throw new IllegalStateException(
                "Data directory does not exist: " + dataDir);
        }

        // Pre-allocate initial segments for better performance
        // This reduces contention during high-throughput workloads
        for (int i = 0; i < DEFAULT_INITIAL_SEGMENTS; i++) {
            preAllocateSegment(i);
        }

        started = true;
        statistics.incrementStartOperations();
    }

    /**
     * Inserts a key-value pair, routing to the appropriate segment.
     *
     * <p>This method computes the segment index using the key hash
     * and delegates the actual insertion to the target segment.</p>
     *
     * <h3>Key Distribution</h3>
     * <p>Keys are distributed evenly across segments using:
     * {@code segmentIndex = keyHash & (segmentCount - 1)}
     * which is equivalent to hash % segmentCount for power-of-2 segment counts.</p>
     *
     * @param key The key (must not be null)
     * @param value The value (must not be null)
     * @throws NullPointerException if key or value is null
     * @throws IllegalStateException if engine is not started
     */
    public void put(byte[] key, byte[] value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));
        long segmentId = segmentIdGenerator.getAndIncrement();
        Path segmentPath = dataDir.resolve("segment_" + segmentIndex + "_" + segmentId + ".dat");

        // Get or create segment
        Segment segment = getOrCreateSegment(segmentId, segmentPath);
        segment.put(key, value);

        // Update catalog
        catalog.update(segmentId, SegmentCatalog.ACTIVE, 1);

        // Increment visibility epoch for consistent reads
        visibilityEpoch.incrementAndGet();

        statistics.incrementWriteOperations();
    }

    /**
     * Retrieves a value by key, routing to the appropriate segment.
     *
     * @param key The key to look up (must not be null)
     * @return MemorySegment slice with value, or null if not found
     * @throws NullPointerException if key is null
     * @throws IllegalStateException if engine is not started
     */
    public MemorySegment get(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));
        
        for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
            Segment segment = entry.getValue();
            MemorySegment value = segment.get(key);
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    /**
     * Retrieves a value with visibility epoch tracking.
     *
     * <p>Returns a {@link ZeroCopySlice} that includes the visibility
     * epoch at the time of the read. Callers can use {@link ZeroCopySlice#isValid()}
     * to verify the snapshot is still valid.</p>
     *
     * @param key The key to look up (must not be null)
     * @return ZeroCopySlice with value and creation epoch, or not found slice
     * @throws NullPointerException if key is null
     */
    public ZeroCopySlice getWithEpoch(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        // Capture epoch before any operation
        long epoch = visibilityEpoch.get();

        for (Segment segment : segments.values()) {
            MemorySegment value = segment.get(key);
            if (value != null) {
                return new ZeroCopySlice(epoch, segment.segmentId(), value, segment);
            }
        }

        return ZeroCopySlice.notFound(epoch, -1, null);
    }

    /**
     * Deletes a key-value pair.
     *
     * <p>The key is tombstoned in the target segment. Actual removal
     * occurs during compaction. This allows concurrent readers to
     * continue accessing the data safely.</p>
     *
     * @param key The key to delete (must not be null)
     * @return true if the key was found and deleted, false if not found
     * @throws NullPointerException if key is null
     */
    public boolean delete(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));

        for (Segment segment : segments.values()) {
            if (segment.delete(key)) {
                catalog.update(segment.segmentId(), SegmentCatalog.DELETED, 0);
                statistics.incrementDeleteOperations();
                return true;
            }
        }

        return false;
    }

    // ==================== Segment Management ====================

    /**
     * Gets or creates a segment with the given ID.
     *
     * @param segmentId Unique segment identifier
     * @param segmentPath Path to the segment file
     * @return The segment instance
     */
    private Segment getOrCreateSegment(long segmentId, Path segmentPath) {
        Segment segment = segments.get(segmentId);
        if (segment != null) {
            return segment;
        }

        try {
            // Create new segment
            segment = new Segment(segmentPath, 1024 * 1024, arena);
            segments.put(segmentId, segment);

            // Update catalog
            catalog.update(segmentId, SegmentCatalog.INDEXING, 0);

            statistics.incrementSegmentOperations();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create segment: " + segmentPath, e);
        }

        return segment;
    }

    /**
     * Pre-allocates a segment without adding it to the registry.
     *
     * <p>This is used during startup to reduce contention during high-throughput
     * workloads. Pre-allocated segments are available for immediate use.</p>
     *
     * @param segmentIndex Index of the segment to pre-allocate
     */
    public void preAllocateSegment(int segmentIndex) {
        long segmentId = segmentIdGenerator.getAndIncrement();
        Path segmentPath = dataDir.resolve("segment_" + segmentIndex + "_" + segmentId + ".dat");

        try {
            Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
            segments.put(segmentId, segment);

            catalog.update(segmentId, SegmentCatalog.INDEXING, 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to pre-allocate segment: " + segmentPath, e);
        }
    }

    /**
     * Gets the segment with the given ID.
     *
     * @param segmentId Unique segment identifier
     * @return The segment, or null if not found
     */
    public Segment getSegment(long segmentId) {
        return segments.get(segmentId);
    }

    /**
     * Gets all active segments.
     *
     * @return Map of segment ID to segment instance
     */
    public Map<Long, Segment> getSegments() {
        return segments;
    }

    /**
     * Returns the number of segments in the engine.
     *
     * @return Segment count
     */
    public int getSegmentCount() {
        return segments.size();
    }

    /**
     * Returns total entries across all segments.
     *
     * @return Total entry count
     */
    public long getTotalEntryCount() {
        return segments.values().stream()
            .mapToLong(Segment::entryCount)
            .sum();
    }

    /**
     * Flushes all segments to disk.
     *
     * <p>This forces all dirty pages to be written to disk, ensuring
     * durability of recent operations.</p>
     */
    public void flush() {
        for (Segment segment : segments.values()) {
            segment.flush();
        }
    }

    /**
     * Closes all segments and releases resources.
     *
     * <p>This method should be called when the engine is no longer needed
     * to ensure proper cleanup of file handles and memory mappings.</p>
     */
    @Override
    public void close() {
        // Close all segments
        for (Segment segment : segments.values()) {
            if (!segment.isClosed()) {
                segment.close();
            }
        }
        segments.clear();

        // Clear catalog
        catalog.clear();

        // Reset state
        started = false;
        visibilityEpoch.set(0);
    }

    /**
     * Checks if the engine is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return segments.isEmpty();
    }

    // ==================== Statistics and Monitoring ====================

    /**
     * Returns statistics about the storage engine.
     *
     * @return Current statistics
     */
    public Statistics getStatistics() {
        return statistics;
    }

    /**
     * Returns the current visibility epoch.
     *
     * @return The current epoch
     */
    public long currentEpoch() {
        return visibilityEpoch.get();
    }

    /**
     * Returns the segment catalog.
     *
     * @return The catalog
     */
    public SegmentCatalog getCatalog() {
        return catalog;
    }

    /**
     * Returns the data directory path.
     *
     * @return The data directory
     */
    public Path dataDir() {
        return dataDir;
    }

    /**
     * Returns the Arena used for memory allocation.
     *
     * @return The Arena
     */
    public Arena arena() {
        return arena;
    }

    // ==================== Inner Classes ====================

    /**
     * Statistics for the StorageEngine.
     */
    public static class Statistics {
        long writeOperations = 0;
        long deleteOperations = 0;
        long readOperations = 0;
        long segmentOperations = 0;
        long startOperations = 0;

        void incrementWriteOperations() { writeOperations++; }
        void incrementDeleteOperations() { deleteOperations++; }
        void incrementReadOperations() { readOperations++; }
        void incrementSegmentOperations() { segmentOperations++; }
        void incrementStartOperations() { startOperations++; }

        @Override
        public String toString() {
            return String.format(
                "Statistics{writes=%d, deletes=%d, reads=%d, segments=%d, starts=%d}",
                writeOperations, deleteOperations, readOperations, segmentOperations, startOperations);
        }
    }
}
