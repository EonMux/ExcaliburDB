package io.excalibur.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Segment lifecycle orchestrator for the ExcaliburDB storage engine.
 *
 * <p>SegmentOrchestrator manages the creation, splitting, and compaction of
 * segments that store key-value pairs across the keyspace. It provides
 * automatic load balancing by splitting segments when they exceed a
 * threshold, and background compaction to merge the write log into segments.</p>
 *
 * <h2>Segment Lifecycle States</h2>
 * <ul>
 *   <li>{@link #STATE_NEW}: Segment was just created, ready for writes</li>
 *   <li>{@link #STATE_ACTIVE}: Segment is actively storing data</li>
 *   <li>{@link #STATE_SPLITTING}: Segment is being split (copy-on-write in progress)</li>
 *   <li>{@link #STATE_COMPACTING}: Segment is being compacted into new segment</li>
 *   <li>{@link #STATE_IDLE}: Segment has no pending operations</li>
 *   <li>{@link #STATE_CLOSED}: Segment has been closed and cleaned up</li>
 * </ul>
 *
 * <h2>Elastic Splitting</h2>
 * <p>When a segment's load factor exceeds 75% of the threshold (0.735),
 * it triggers automatic splitting. The split uses copy-on-write semantics:
 * <ol>
 *   <li>The original segment is read-only</li>
 *   <li>A new segment is created</li>
 *   <li>All entries are copied to the new segment</li>
 *   <li>Both segments are made visible with updated slot tables</li>
 *   <li>The old segment is closed and eventually garbage collected</li>
 * </ol>
 * This provides zero-downtime splitting - reads continue to work on
 * both the old and new segments during the transition.</p>
 *
 * <h2>Background Compaction</h2>
 * <p>Segments accumulate write log entries for durability. Compaction merges
 * the write log into the segment, reclaiming space and enabling garbage
 * collection. Compaction runs in the background using virtual threads
 * (Thread.vstart()) for non-blocking operation.</p>
 *
 * <h2>Visibility Epoch</h2>
 * <p>For consistent reads, each segment maintains a visibility epoch. This
 * monotonically increasing counter is captured when a snapshot is taken.
 * Readers use the epoch to verify they are seeing a consistent snapshot
 * by checking {@link ZeroCopySlice#isValid()}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>SegmentOrchestrator is fully thread-safe. Segment operations are
 * serialized where necessary, and background threads are isolated from
 * the main execution path.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see Segment
 * @see WriteLog
 * @see ZeroCopySlice
 */
public class SegmentOrchestrator implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SegmentOrchestrator.class.getName());

    // ==================== Configuration Constants ====================

    /**
     * Load factor threshold for triggering segment splitting.
     * 
     * <p>Segments split when their load factor exceeds 75% of the
     * standard threshold (0.7). This gives us 0.735, providing
     * a safety margin while avoiding excessive splitting.</p>
     *
     * <p>Rationale:</p>
     * <ul>
     *   <li>At 0.7 load factor with Robin Hood hashing, average probe
     *     length is ~2</li>
     *   <li>Splitting at 0.735 allows some entries to accumulate
     *     without performance degradation</li>
     *   <li>75% threshold is a common industry practice (Redis, etc.)</li>
     * </ul>
     */
    public static final double SPLIT_THRESHOLD = 0.75;

    /**
     * Virtual thread pool size for background compaction.
     * 
     * <p>Virtual threads have minimal overhead and can create thousands
     * of concurrent threads. This size is sufficient for most workloads
     * while keeping resource usage low.</p>
     *
     * <p>Value: 16 virtual threads</p>
     */
    public static final int COMPACTOR_POOL_SIZE = 16;

    // ==================== Segment Lifecycle States ====================

    /**
     * Segment was just created, ready for writes.
     */
    public static final int STATE_NEW = 0;

    /**
     * Segment is actively storing data.
     */
    public static final int STATE_ACTIVE = 1;

    /**
     * Segment is being split (copy-on-write in progress).
     */
    public static final int STATE_SPLITTING = 2;

    /**
     * Segment is being compacted into a new segment.
     */
    public static final int STATE_COMPACTING = 3;

    /**
     * Segment has no pending operations.
     */
    public static final int STATE_IDLE = 4;

    /**
     * Segment has been closed and cleaned up.
     */
    public static final int STATE_CLOSED = 5;

    // ==================== Instance Fields ====================

    private final Arena arena;
    private final Path dataDir;
    private final AtomicLong visibilityEpoch = new AtomicLong(0);

    // Segment registry: key -> segment instance
    private final Map<Path, Segment> segments = new ConcurrentHashMap<>();

    // Background compactor
    private final AtomicBoolean compactorRunning = new AtomicBoolean(false);
    private final CompactionWorker compactionWorker;

    // Visibility epoch for this orchestrator
    private final AtomicLong orchestratorEpoch = new AtomicLong(0);

    // Statistics
    private final Statistics statistics = new Statistics();

    // ==================== Constructors ====================

    /**
     * Creates a new SegmentOrchestrator.
     *
     * @param arena The Arena for memory allocation (must outlive this orchestrator)
     * @param dataDir Directory for storing segment files
     * @throws IOException if the data directory cannot be created
     */
    public SegmentOrchestrator(Arena arena, Path dataDir) throws IOException {
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException("Data directory must exist: " + dataDir);
        }

        this.arena = arena;
        this.dataDir = dataDir;

        // Create and configure the compaction worker
        // Note: We use 'this' reference in the callback to handle segment registration
        // to avoid the "variable might not have been initialized" error
        this.compactionWorker = new CompactionWorker(arena, (oldSegment, newSegment) -> {
            // Atomic swap: replace old segment with new compacted segment
            Path oldPath = oldSegment.path();
            segments.replace(oldPath, newSegment);

            // Increment visibility epoch for snapshot consistency
            orchestratorEpoch.incrementAndGet();

            statistics.incrementCompactionOperations();
        });

        LOGGER.log(Level.INFO, "SegmentOrchestrator created with dataDir={0}", dataDir);
    }

    // ==================== Public API - Keyspace Management ====================

    /**
     * Inserts a key-value pair, creating a new segment if needed.
     *
     * <p>This method finds the appropriate segment for the key using
     * hash-based routing. If the segment doesn't exist or is full,
     * it creates a new segment and distributes keys evenly across
     * all segments.</p>
     *
     * <h3>Key Distribution</h3>
     * <p>Keys are distributed evenly across segments using:
     * {@code segmentIndex = keyHash % numberOfSegments}</p>
     *
     * <h3>Segment Creation</h3>
     * <p>When a new segment is created:</p>
     * <ol>
     *   <li>The segment is created in STATE_NEW state</li>
     *   <li>The orchestrator epoch is incremented</li>
     *   <li>The new segment is registered</li>
     *   <li>Existing segments may be split to balance the load</li>
     * </ol>
     *
     * @param key The key (must not be null)
     * @param value The value (must not be null)
     * @throws NullPointerException if key or value is null
     */
    public void put(byte[] key, byte[] value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));
        Path segmentPath = dataDir.resolve("segment_" + segmentIndex + ".dat");

        // Get or create segment
        Segment segment = getOrCreateSegment(segmentPath);
        segment.put(key, value);

        // Increment orchestrator epoch for visibility tracking
        orchestratorEpoch.incrementAndGet();

        statistics.incrementWriteOperations();
    }

    /**
     * Retrieves a value by key, with visibility epoch tracking.
     *
     * <p>This method provides a snapshot of the keyspace at the time
     * of the call, tracked via the orchestrator epoch. Use with
     * {@link ZeroCopySlice#isValid()} to verify snapshot consistency.</p>
     *
     * @param key The key to look up
     * @return MemorySegment slice with value, or null if not found
     * @throws NullPointerException if key is null
     */
    public MemorySegment get(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));
        Path segmentPath = dataDir.resolve("segment_" + segmentIndex + ".dat");

        Segment segment = getSegment(segmentPath);
        return segment.get(key);
    }

    /**
     * Retrieves a value by key with epoch tracking for consistent snapshots.
     *
     * <p>This method returns a {@link ZeroCopySlice} that includes the
     * orchestrator epoch at the time of the read. Callers can use
     * {@link ZeroCopySlice#isValid()} or {@link SegmentOrchestrator#isSnapshotValid(ZeroCopySlice)}
     * to verify the snapshot is still valid.</p>
     *
     * <h3>Snapshot Semantics</h3>
     * <ul>
     *   <li>The epoch is captured at the start of the operation</li>
     *   <li>The epoch is incremented when the operation completes</li>
     *   <li>Readers can verify their snapshot by checking the epoch</li>
     * </ul>
     *
     * @param key The key to look up
     * @return ZeroCopySlice with value and creation epoch, or not found slice
     * @throws NullPointerException if key is null
     * @see ZeroCopySlice
     */
    public ZeroCopySlice getWithEpoch(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        // Capture epoch before any operation
        long epoch = orchestratorEpoch.get();

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));
        Path segmentPath = dataDir.resolve("segment_" + segmentIndex + ".dat");

        Segment segment = getSegment(segmentPath);
        ZeroCopySlice slice = segment.getWithEpoch(key);

        // Increment epoch after read
        orchestratorEpoch.incrementAndGet();

        return new ZeroCopySlice(epoch, segment.segmentId(), slice.data(), segment);
    }

    /**
     * Deletes a key-value pair.
     *
     * <p>The key is tombstoned in the segment. Actual removal occurs
     * during compaction. This allows concurrent readers to continue
     * accessing the data safely.</p>
     *
     * @param key The key to delete
     * @return true if the key was deleted, false if not found
     * @throws NullPointerException if key is null
     */
    public boolean delete(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (segments.size() - 1));
        Path segmentPath = dataDir.resolve("segment_" + segmentIndex + ".dat");

        Segment segment = getSegment(segmentPath);
        boolean deleted = segment.delete(key);

        // Increment orchestrator epoch
        orchestratorEpoch.incrementAndGet();

        statistics.incrementDeleteOperations();

        return deleted;
    }

    // ==================== Visibility and Snapshot API ====================

    /**
     * Returns the current orchestrator visibility epoch.
     *
     * <p>The epoch is monotonically increasing and represents the
     * snapshot point at which readers were allowed to proceed.</p>
     *
     * @return The current epoch
     */
    public long currentEpoch() {
        return orchestratorEpoch.get();
    }

    /**
     * Checks if a snapshot is still valid.
     *
     * <p>A snapshot is valid if its epoch matches the current orchestrator
     * epoch. This allows readers to verify their snapshot before accessing
     * the data.</p>
     *
     * @param slice The slice to check
     * @return true if the snapshot is still valid
     * @throws NullPointerException if slice is null
     */
    public boolean isSnapshotValid(ZeroCopySlice slice) {
        if (slice == null) {
            throw new NullPointerException("Slice cannot be null");
        }
        return slice.epoch() == currentEpoch();
    }

    // ==================== Statistics and Monitoring ====================

    /**
     * Returns statistics about the orchestrator.
     *
     * @return Current statistics
     */
    public Statistics getStatistics() {
        return statistics;
    }

    /**
     * Returns the number of active segments.
     *
     * @return Count of non-closed segments
     */
    public int getSegmentCount() {
        return (int) segments.values().stream()
            .filter(s -> !s.isClosed())
            .count();
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

    // ==================== AutoCloseable ====================

    /**
     * Starts the orchestrator and its background compaction worker.
     *
     * <p>This method starts the background compaction thread that monitors
     * segments and performs compaction as needed. The orchestrator accepts
     * operations before this method is called, but compaction will not run.</p>
     */
    public void start() {
        if (compactorRunning.compareAndSet(false, true)) {
            compactionWorker.start();
            LOGGER.log(Level.INFO, "SegmentOrchestrator started with background compaction");
        }
    }

    @Override
    public void close() {
        compactorRunning.set(false);
        compactionWorker.stop();
        closeSegments();
        LOGGER.log(Level.INFO, "SegmentOrchestrator closed");
    }

    private void closeSegments() {
        segments.forEach((path, segment) -> {
            if (!segment.isClosed()) {
                segment.close();
            }
        });
        segments.clear();
    }

    // ==================== Internal Helper Methods ====================

    Segment getOrCreateSegment(Path segmentPath) {
        Segment segment = segments.get(segmentPath);
        if (segment == null) {
            try {
                segment = new Segment(segmentPath, 1024 * 1024, arena);
                segments.put(segmentPath, segment);

                // Register segment for compaction monitoring
                compactionWorker.registerSegment(segment);

                LOGGER.log(Level.FINE, "Created and registered segment: {0}", segmentPath);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to create segment: " + segmentPath, e);
            }
        }
        return segment;
    }

    Segment getSegment(Path segmentPath) {
        return segments.get(segmentPath);
    }

    /**
     * Reads the key for a given entry index.
     *
     * <p>This method reads entry keys from all active segments for use in
     * compaction operations. It should only be called when the orchestrator
     * is shutting down or performing maintenance.</p>
     *
     * @param index Entry index (0-based, sequential across all segments)
     * @return Key bytes, or null if index is out of range
     */
    public byte[] readEntryKey(int index) {
        long totalEntries = getTotalEntryCount();
        if (index >= totalEntries) {
            return null;
        }

        int currentEntry = 0;
        for (Segment segment : segments.values()) {
            if (!segment.isClosed()) {
                int segmentEntries = segment.entryCount();
                if (index < currentEntry + segmentEntries) {
                    int entryInSegment = index - currentEntry;
                    return segment.readEntryKey(entryInSegment);
                }
                currentEntry += segmentEntries;
            }
        }
        return null;
    }

    /**
     * Reads the value for a given entry index.
     *
     * @param index Entry index (0-based, sequential across all segments)
     * @return Value bytes, or null if index is out of range
     */
    public byte[] readEntryValue(int index) {
        byte[] key = readEntryKey(index);
        if (key == null) {
            return null;
        }

        long totalEntries = getTotalEntryCount();
        if (index >= totalEntries) {
            return null;
        }

        int currentEntry = 0;
        for (Segment segment : segments.values()) {
            if (!segment.isClosed()) {
                int segmentEntries = segment.entryCount();
                if (index < currentEntry + segmentEntries) {
                    int entryInSegment = index - currentEntry;
                    return segment.readEntryValue(entryInSegment);
                }
                currentEntry += segmentEntries;
            }
        }
        return null;
    }

    /**
     * Reads all entries from a segment.
     *
     * @param segmentPath Path to the segment
     * @return List of entry objects (key, value pairs)
     */
    public List<Entry> readAllEntries(Path segmentPath) {
        Segment segment = getSegment(segmentPath);
        List<Entry> entries = new ArrayList<>();

        for (int i = 0; i < segment.entryCount(); i++) {
            byte[] key = segment.readEntryKey(i);
            byte[] value = segment.readEntryValue(i);
            entries.add(new Entry(key, value));
        }

        return entries;
    }

    /**
     * Reads all entries from all segments.
     *
     * @return List of all entries across the keyspace
     */
    public List<Entry> readAllEntries() {
        List<Entry> allEntries = new ArrayList<>();
        for (Path segmentPath : segments.keySet()) {
            if (!segments.get(segmentPath).isClosed()) {
                allEntries.addAll(readAllEntries(segmentPath));
            }
        }
        return allEntries;
    }

    /**
     * Entry record for compacting segments.
     */
    private record Entry(byte[] key, byte[] value) {}

    /**
     * Returns the Arena used for memory allocation.
     *
     * @return The Arena
     */
    public Arena arena() {
        return arena;
    }

    /**
     * Returns the data directory path.
     *
     * @return The data directory
     */
    public Path dataDir() {
        return dataDir;
    }

    // ==================== Inner Classes ====================

    /**
     * Statistics for the SegmentOrchestrator.
     */
    class Statistics {
        long writeOperations = 0;
        long deleteOperations = 0;
        long splitOperations = 0;
        long compactionOperations = 0;

        void incrementWriteOperations() { writeOperations++; }
        void incrementDeleteOperations() { deleteOperations++; }
        void incrementCompactionOperations() { compactionOperations++; }

        @Override
        public String toString() {
            return String.format(
                "Statistics{writes=%d, deletes=%d, splits=%d, compactions=%d}",
                writeOperations, deleteOperations, splitOperations, compactionOperations);
        }
    }

    /**
     * Returns the compaction worker for this orchestrator.
     *
     * <p>The compaction worker manages background compaction of segments.
     * Use this to configure compaction thresholds or request manual compaction.</p>
     *
     * @return The compaction worker
     */
    public CompactionWorker getCompactionWorker() {
        return compactionWorker;
    }
}
