package io.excalibur.store;

import io.excalibur.store.format.LogEntry;
import io.excalibur.store.format.SegmentEntry;
import io.excalibur.store.hash.XxHash64;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Disk-backed key-value storage segment with Robin Hood hashing.
 *
 * <p>This class implements a memory-mapped hash table segment with:
 * <ul>
 *   <li>Robin Hood hashing for O(1) average-case lookups with reduced probe lengths</li>
 *   <li>Zero-copy reads via MemorySegment slices</li>
 *   <li>Variable-length key and value storage</li>
 *   <li>Thread-safe concurrent read access via striped read-write locks</li>
 *   <li>Load factor monitoring with rejection at 0.7</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 * <p>The segment is divided into two regions:
 * <ol>
 *   <li><strong>Slot Table</strong>: Fixed-size slots for the hash table (24 bytes each)</li>
 *   <li><strong>Data Region</strong>: Variable-length entries stored contiguously</li>
 * </ol>
 *
 * <h3>Slot Format (24 bytes)</h3>
 * <pre>
 * Offset 0:  key_hash (8 bytes, little-endian)
 * Offset 8:  distance (1 byte, unsigned - distance from ideal slot)
 * Offset 9:  flags (1 byte, bit 0: occupied, bit 1: tombstone)
 * Offset 10: data_offset (6 bytes, unsigned - offset into data region)
 * Offset 16: reserved (8 bytes, for alignment)
 * </pre>
 *
 * <h3>Entry Format (variable length)</h3>
 * <pre>
 * Offset 0:  key_hash (8 bytes, little-endian)
 * Offset 8:  key_len (4 bytes, signed int, little-endian)
 * Offset 12: value_len (4 bytes, signed int, little-endian)
 * Offset 16: distance (1 byte, unsigned - distance from ideal slot)
 * Offset 17: key_bytes (variable)
 * Offset 17+key_len: value_bytes (variable)
 * Offset 17+key_len+value_len: tombstone (1 byte, 0=live, 1=deleted)
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 *
 * <h3>Striped Locking Pattern</h3>
 * <p>Each segment instance has its own dedicated {@link ReentrantReadWriteLock}.
 * This "striped" locking pattern provides:
 * <ul>
 *   <li><strong>Segment isolation</strong>: Operations on different segments
 *       do not contend with each other - each segment has its own lock</li>
 *   <li><strong>Concurrent reads</strong>: Multiple threads can read the same
 *       segment simultaneously via the read lock</li>
 *   <li><strong>Exclusive writes</strong>: Write operations acquire the write
 *       lock for exclusive access to that segment only</li>
 * </ul>
 *
 * <h3>Lock Acquisition</h3>
 * <ul>
 *   <li>{@link #get(byte[])} acquires the <em>read lock</em></li>
 *   <li>{@link #put(byte[], byte[])} acquires the <em>write lock</em></li>
 *   <li>{@link #delete(byte[])} acquires the <em>write lock</em></li>
 *   <li>{@link #close()} acquires the <em>write lock</em></li>
 * </ul>
 *
 * <h3>Advanced Usage</h3>
 * <p>For bulk operations or consistent reads across multiple calls, use
 * {@link #readLock()} or {@link #writeLock()} to acquire the lock manually:</p>
 * <pre>{@code
 * // Bulk read with consistent snapshot
 * segment.readLock().lock();
 * try {
 *     MemorySegment v1 = segment.getWithoutLock(key1);
 *     MemorySegment v2 = segment.getWithoutLock(key2);
 * } finally {
 *     segment.readLock().unlock();
 * }
 * }</pre>
 *
 * <h3>Zero-Copy Slice Validity</h3>
 * <p>Zero-copy slices returned by {@link #get(byte[])} remain valid until:
 * <ul>
 *   <li>The segment is modified (put/delete) by any thread</li>
 *   <li>The segment is closed</li>
 *   <li>The Arena used to create the segment is closed</li>
 * </ul>
 *
 * <h4>Epoch-Based Validity Tracking</h4>
 * <p>For applications that need to verify slice validity, use {@link #getWithEpoch(byte[])}
 * which returns a {@link ZeroCopySlice} with embedded epoch tracking:</p>
 * <pre>{@code
 * ZeroCopySlice slice = segment.getWithEpoch(key);
 * if (slice.isFound()) {
 *     // Access data
 *     MemorySegment data = slice.data();
 *
 *     // Later, check validity before accessing again
 *     if (slice.isValid()) {
 *         // Safe to access - no modifications occurred
 *         processValue(data);
 *     } else {
 *         // Slice invalidated - need to re-fetch
 *         slice = segment.getWithEpoch(key);
 *     }
 * }
 * }</pre>
 *
 * <p>The validity check compares the slice's creation epoch with the current
 * {@link #currentEpoch()}. If they differ, a modification (put/delete) occurred.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>key_hash: positive 64-bit values in range [0, Long.MAX_VALUE]</li>
 *   <li>key_len, value_len: in range [0, Integer.MAX_VALUE - 17]</li>
 *   <li>distance: in range [0, 127] (fits in unsigned byte)</li>
 *   <li>tombstone: exactly 0 (live) or 1 (deleted)</li>
 *   <li>Capacity: power of 2 in range [1024, 1048576]</li>
 *   <li>Load factor: computed as entry_count / slot_count</li>
 *   <li>Insertions rejected when load factor ≥ 0.7</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see ReentrantReadWriteLock
 * @see SegmentConfig
 */
public class Segment implements AutoCloseable {

    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(Segment.class.getName());

    // ==================== Constants ====================

    /** Minimum segment capacity (number of slots). Must be power of 2. */
    public static final int MIN_SEGMENT_CAPACITY = 1024;

    /** Maximum segment capacity (number of slots). Must be power of 2. */
    public static final int MAX_SEGMENT_CAPACITY = 1 << 20; // 1M entries

    /** Load factor threshold for triggering split/rejection. */
    public static final double LOAD_FACTOR_THRESHOLD = 0.7;

    /**
     * This segment's threshold, defaulting to {@link #LOAD_FACTOR_THRESHOLD}.
     *
     * <p>Per-segment rather than static so it can be configured. The constant remains the default
     * and the documented value.
     */
    private final double loadFactorThreshold;

    /** Maximum probe length before giving up. */
    public static final int MAX_PROBE_LENGTH = 128;

    /**
     * Maximum distance value that can be stored (fits in unsigned byte).
     * Domain constraint: distance must be in range [0, 127].
     * Distance values 0-127 are valid. A distance of 128 would overflow
     * the unsigned byte storage.
     */
    public static final int MAX_DISTANCE = 127;

    /** Size of each slot in the hash table (bytes). */
    private static final int SLOT_SIZE = 24;

    /** Size of entry header (key_hash + key_len + value_len + distance). */
    private static final int ENTRY_HEADER_SIZE = 17; // 8 + 4 + 4 + 1

    /** Size of entry trailer (tombstone byte). */
    private static final int ENTRY_TRAILER_SIZE = 1;

    // ==================== Segment ID Generation ====================

    /**
     * Global counter for generating unique segment IDs.
     * Each Segment instance gets a unique ID for slice validity tracking.
     */
    private static final AtomicLong SEGMENT_ID_GENERATOR = new AtomicLong(0);

    // Entry field offsets (in data region)
    private static final int ENTRY_KEY_HASH_OFFSET = 0;
    private static final int ENTRY_KEY_LEN_OFFSET = 8;
    private static final int ENTRY_VALUE_LEN_OFFSET = 12;
    private static final int ENTRY_DISTANCE_OFFSET = 16;
    private static final int ENTRY_KEY_BYTES_OFFSET = 17;

    // Slot field offsets
    private static final int SLOT_KEY_HASH_OFFSET = 0;
    private static final int SLOT_DISTANCE_OFFSET = 8;
    private static final int SLOT_FLAGS_OFFSET = 9;
    private static final int SLOT_DATA_OFFSET_OFFSET = 10;
    private static final int SLOT_RESERVED_OFFSET = 16;

    // Flag bits
    private static final byte FLAG_OCCUPIED = 0x01;
    private static final byte FLAG_TOMBSTONE = 0x02;

    // ValueLayouts for memory access (little-endian as per spec)
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    // ==================== Instance Fields ====================

    /**
     * Path to the backing file.
     *
     * <p>Not final: compaction and split publication rename the backing file into its
     * canonical {@code segment_<index>.dat} name while this segment stays mapped, and
     * {@link #renamedTo(Path)} then records the new name.</p>
     */
    private volatile Path path;

    private final Arena arena;
    private final FileChannel fileChannel;
    private final MemorySegment segment;
    private final int slotCount;
    private final long slotTableSize;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile boolean closed = false;

    /**
     * Number of OCCUPIED slots, tombstoned or not.
     *
     * <p>This is the number the Robin Hood probe budget has to be measured against,
     * because a tombstoned slot is never reused. {@link #liveEntryCount} tracks the
     * number of readable entries.</p>
     */
    private volatile int entryCount = 0;

    /** Number of occupied slots that are not tombstoned. */
    private volatile int liveEntryCount = 0;

    private volatile long dataOffset = 0; // Current write offset in data region

    /**
     * Modification epoch counter for zero-copy slice validity tracking.
     * Incremented on every put() and delete() operation.
     * Volatile to ensure visibility across threads.
     */
    private volatile long modificationEpoch = 0;

    /**
     * Unique identifier for this segment instance.
     * Used to verify that slices belong to the correct segment.
     */
    private final long segmentId;

    /**
     * Write-ahead log for crash recovery.
     * Every mutation (put, delete) is first appended to this log
     * before updating the in-memory hash map.
     */
    private volatile WriteLog writeLog;

    /** Guards {@link #warnUnlogged()} so the warning is issued once per segment. */
    private final java.util.concurrent.atomic.AtomicBoolean unloggedWarningIssued =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Locality-Sensitive Hashing index for approximate nearest neighbor pre-filtering.
     * Used during Stage 2 of the query pipeline to reduce candidates to ~10x-100x top_k.
     * The index is populated during document ingestion when vectors are written.
     */
    private volatile excalibur.filter.lsh.LshIndex lshIndex;

    /**
     * Count-Min Sketch for term/token frequency estimation.
     * Used by the query planner to estimate selectivity and optimize query execution.
     * Populated during document ingestion by tracking term frequencies from text chunks.
     *
     * <p>The sketch enables approximate frequency queries in O(1) time with bounded error,
     * allowing the query planner to make informed decisions about segment prioritization
     * and filter ordering without exact counts.</p>
     */
    private volatile excalibur.filter.sketch.CountMinSketch termFrequencySketch;

    /**
     * HNSW graph for approximate nearest neighbor search.
     * This graph stores vectors and enables efficient ANN queries.
     * Attached during segment initialization by the HNSW initializer.
     * Volatile to ensure visibility across threads after attachment.
     */
    private volatile io.excalibur.vector.hnsw.HnswGraph hnswGraph;

    /**
     * Tail offset tracker for the HNSW graph.
     * Tracks the write log offset up to which entries have been integrated
     * into the HNSW graph. Used for tail scan during queries.
     * Volatile to ensure visibility across threads after attachment.
     */
    private volatile io.excalibur.vector.hnsw.TailOffsetTracker tailOffsetTracker;

    /**
     * Split state for copy-on-write splitting.
     * A segment can be in one of three states:
     * - NORMAL: No split in progress
     * - SPLITTING: Split in progress, serving reads from pre-split snapshot
     * - SPLIT_COMPLETE: Split finished, segment ready for retirement
     */
    private volatile SplitState splitState = SplitState.NORMAL;

    /**
     * Snapshot reference used during splitting to serve consistent reads.
     * When splitState is SPLITTING, reads are served from this snapshot
     * instead of the current (mutating) state.
     */
    private volatile SegmentSnapshot splitSnapshot;

    /**
     * Child segments created during a split operation.
     * After split completes, these contain the partitioned data.
     */
    private volatile Segment leftChild;
    private volatile Segment rightChild;

    /**
     * Hash bit used to partition this segment's entries between its two children.
     * Set by {@link #completeSplit(Segment, Segment, int)}; -1 until a split completes.
     */
    private volatile int splitBit = -1;

    // ==================== Constructors ====================

    /**
     * Creates a new Segment backed by a memory-mapped file using Panama FFM.
     *
     * <p>This constructor:
     * <ol>
     *   <li>Validates the path, capacity, and arena parameters</li>
     *   <li>Calculates the slot count as a power of 2 within valid range</li>
     *   <li>Creates or opens the backing file</li>
     *   <li>Maps the file into memory using the provided Arena</li>
     *   <li>Initializes the slot table to an empty state</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Capacity is rounded to a power of 2 for efficient modulo operations</li>
     *   <li>Slot count is clamped to [MIN_SEGMENT_CAPACITY, MAX_SEGMENT_CAPACITY]</li>
     *   <li>File size includes slot table + data region (minimum 1MB for data)</li>
     *   <li>Memory segment is allocated from the provided Arena for proper lifecycle management</li>
     * </ul>
     *
     * @param path Path to the backing file (must not be null)
     * @param capacityBytes Initial capacity in bytes (will be rounded to accommodate slots)
     * @param arena Arena for memory management (must not be null)
     * @throws IOException if the file cannot be created or mapped
     * @throws IllegalArgumentException if path is null, arena is null, or capacity is invalid
     */
    public Segment(Path path, long capacityBytes, Arena arena) throws IOException {
        this(path, capacityBytes, arena, LOAD_FACTOR_THRESHOLD);
    }

    /**
     * Creates a segment with an explicit load-factor threshold.
     *
     * <p>The threshold decides when the segment refuses further inserts and therefore when it is
     * split. It is a parameter rather than a constant because it is an operational trade-off —
     * lower splits sooner and wastes space, higher packs tighter and lengthens probe chains — and
     * {@code excalibur.shard.segment-load-factor} exists to express it. This module has no
     * dependency on the config module, so the value is passed in rather than read here.
     *
     * @param loadFactorThreshold fraction of slots that may be occupied, in (0, 1)
     */
    public Segment(Path path, long capacityBytes, Arena arena, double loadFactorThreshold)
            throws IOException {
        if (loadFactorThreshold <= 0.0 || loadFactorThreshold >= 1.0
                || Double.isNaN(loadFactorThreshold)) {
            // At or above 1.0 an open-addressed table cannot terminate its probe; at or below 0
            // it can never accept anything.
            throw new IllegalArgumentException(
                "loadFactorThreshold must be in (0, 1), got " + loadFactorThreshold);
        }
        this.loadFactorThreshold = loadFactorThreshold;

        // Validate parameters
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacityBytes);
        }

        this.path = path;
        this.arena = arena;
        this.segmentId = SEGMENT_ID_GENERATOR.getAndIncrement();

        // Calculate slot count (power of 2, within valid range)
        // Estimate: 50% of capacity for slot table, 50% for data
        long estimatedSlots = (capacityBytes / 2) / SLOT_SIZE;
        this.slotCount = roundToPowerOf2((int) Math.min(estimatedSlots, MAX_SEGMENT_CAPACITY));

        // Domain constraint: Capacity MUST be a power of 2 in range [1024, 1048576]
        // This validation ensures the slot count satisfies the domain correctness criteria
        SegmentConfig.validateCapacity(this.slotCount);

        // Calculate actual required size
        this.slotTableSize = (long) this.slotCount * SLOT_SIZE;
        long minDataSize = 1024 * 1024; // At least 1MB for data
        long totalSize = slotTableSize + minDataSize;

        // Create parent directories if they don't exist
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Open or create the file with read/write access
        this.fileChannel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        );

        // Set file size
        fileChannel.truncate(totalSize);

        // Map the file into memory using the provided Arena
        // Using READ_WRITE mode to allow both reading and writing
        this.segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize, arena);

        // Initialize data offset to start of data region (0 offset within data region)
        // The data region starts at slotTableSize, but dataOffset tracks the offset within
        // that region, not the absolute position in the segment
        this.dataOffset = 0;

        // Initialize slot table (clear all slots)
        clearSlotTable();
    }

    /**
     * Opens an existing Segment from a file.
     *
     * <p>This method reads the segment metadata from the existing file
     * and creates a Segment instance backed by that file.</p>
     *
     * @param path Path to the existing segment file
     * @param arena Arena for memory management
     * @return The opened Segment
     * @throws IOException if the file cannot be opened or is invalid
     * @deprecated Use {@link #openExisting(Path, Arena)} instead to preserve existing data
     */
    @Deprecated
    public static Segment open(Path path, Arena arena) throws IOException {
        return openExisting(path, arena);
    }

    /**
     * Opens an existing Segment from a file without truncating it.
     *
     * <p>This method opens an existing memory-mapped segment file and recovers
     * its state by scanning the slot table. This is the preferred method for
     * opening segments during startup to preserve existing data.</p>
     *
     * <h3>Recovery Process</h3>
     * <ol>
     *   <li>Opens the file without truncating</li>
     *   <li>Maps the file into memory</li>
     *   <li>Derives slot count from file size (accounting for minimum 1MB data region)</li>
     *   <li>Scans slot table to recover entry count and data offset</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>File is NOT truncated - existing data is preserved</li>
     *   <li>Entry count is recovered by scanning the slot table</li>
     *   <li>Data offset is calculated from the last entry position</li>
     *   <li>Slot count is derived from file size and validated</li>
     * </ul>
     *
     * @param path Path to the existing segment file (must exist)
     * @param arena Arena for memory management (must not be null)
     * @return The opened Segment with recovered state
     * @throws IOException if the file cannot be opened or is invalid
     * @throws IllegalArgumentException if path or arena is null, or file doesn't exist
     */
    public static Segment openExisting(Path path, Arena arena) throws IOException {
        return openExisting(path, arena, LOAD_FACTOR_THRESHOLD);
    }

    /**
     * Opens an existing segment with an explicit load-factor threshold.
     *
     * <p>The threshold is not stored in the file - it governs when to stop accepting inserts,
     * not how the file is laid out - so it has to be supplied on every open. Defaulting it
     * meant a segment written under a configured 0.9 was reopened at 0.7 and then rejected
     * replay of its own already-acknowledged entries, which fails the startup and keeps failing
     * it.
     *
     * @param path                Segment file
     * @param arena               Arena owning the mapping
     * @param loadFactorThreshold Fraction of slots that may be occupied, in (0, 1)
     * @return The opened segment
     * @throws IOException if the file cannot be opened
     */
    public static Segment openExisting(Path path, Arena arena, double loadFactorThreshold)
            throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }
        if (!Files.exists(path)) {
            throw new IOException("Segment file does not exist: " + path);
        }

        long fileSize = Files.size(path);

        // Minimum valid file size is the slot table + minimum data region
        // If file is too small, it's corrupted or empty
        if (fileSize < 1024) {
            throw new IOException("Segment file too small to be valid: " + path + " (size=" + fileSize + ")");
        }

        // Derive slot count from file size
        // The file format is: [slot_table | data_region]
        // Where data_region is at least 1MB (MIN_DATA_SIZE = 1024*1024)
        // So: fileSize = slotCount * SLOT_SIZE + dataRegionSize
        // And: dataRegionSize >= MIN_DATA_SIZE
        // Therefore: slotCount <= (fileSize - MIN_DATA_SIZE) / SLOT_SIZE
        int slotCount = deriveSlotCountForRecovery(fileSize);
        long slotTableSize = (long) slotCount * SLOT_SIZE;

        // Open file WITHOUT truncating - this is the key difference from the constructor
        FileChannel fileChannel = FileChannel.open(
            path,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
            // Note: No TRUNCATE_EXISTING - we want to preserve existing data
        );

        // Map the file into memory
        MemorySegment segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize, arena);

        // Create a Segment instance using private constructor pattern
        // We need to recover the state from the file
        Segment result = new Segment(path, arena, fileChannel, segment, slotCount, slotTableSize,
            loadFactorThreshold);

        // Recover entry count and data offset by scanning the slot table
        result.recoverStateFromSlotTable();

        return result;
    }

    /**
     * Derives slot count from file size for recovery purposes.
     *
     * <p>This method accounts for the minimum 1MB data region that the constructor
     * always includes. The file format is: [slot_table | data_region] where
     * data_region is at least 1MB.</p>
     *
     * <p>Formula: slotCount = (fileSize - MIN_DATA_SIZE) / SLOT_SIZE, rounded to power of 2</p>
     *
     * @param fileSize The file size in bytes
     * @return The derived slot count
     */
    private static int deriveSlotCountForRecovery(long fileSize) {
        // Minimum data region size (must match constructor)
        final long MIN_DATA_SIZE = 1024 * 1024;

        // Calculate slot table size by subtracting minimum data region
        long slotTableSize = fileSize - MIN_DATA_SIZE;
        if (slotTableSize <= 0) {
            // File is too small, use minimum
            return MIN_SEGMENT_CAPACITY;
        }

        // Calculate estimated slot count
        long estimatedSlots = slotTableSize / SLOT_SIZE;

        // Round to power of 2 (same logic as constructor)
        int slotCount = roundToPowerOf2((int) Math.min(estimatedSlots, MAX_SEGMENT_CAPACITY));

        // Validate the slot count
        SegmentConfig.validateCapacity(slotCount);

        return slotCount;
    }

    /**
     * Private constructor for opening existing segments.
     *
     * <p>This constructor is used by {@link #openExisting(Path, Arena)} to
     * create a Segment instance without truncating the file.</p>
     */
    private Segment(Path path, Arena arena, FileChannel fileChannel, MemorySegment segment,
                    int slotCount, long slotTableSize, double loadFactorThreshold) {
        if (loadFactorThreshold <= 0.0 || loadFactorThreshold >= 1.0
                || Double.isNaN(loadFactorThreshold)) {
            throw new IllegalArgumentException(
                "loadFactorThreshold must be in (0, 1), got " + loadFactorThreshold);
        }
        this.path = path;
        this.arena = arena;
        this.fileChannel = fileChannel;
        this.segment = segment;
        this.slotCount = slotCount;
        this.slotTableSize = slotTableSize;
        this.segmentId = SEGMENT_ID_GENERATOR.getAndIncrement();
        // Supplied by the caller, because it is not in the file. A reopened segment that took
        // the compile-time default would reject replay of entries it had already acknowledged
        // under a larger configured threshold.
        this.loadFactorThreshold = loadFactorThreshold;
        // entryCount and dataOffset will be recovered by recoverStateFromSlotTable()
    }

    /**
     * Recovers the segment state by scanning the slot table.
     *
     * <p>This method scans all slots in the slot table to:</p>
     * <ul>
     *   <li>Count occupied slots (tombstoned included) into {@code entryCount}</li>
     *   <li>Count non-tombstoned slots into {@code liveEntryCount}</li>
     *   <li>Find the maximum data offset (for future writes)</li>
     * </ul>
     *
     * <p>This is called during recovery from an existing segment file.</p>
     */
    private void recoverStateFromSlotTable() {
        int recoveredEntryCount = 0;
        int recoveredLiveCount = 0;
        long maxEndOffset = 0;  // Track the END of the last entry (offset + size)
        long segmentSize = segment.byteSize();

        for (int i = 0; i < slotCount; i++) {
            long slotOffset = (long) i * SLOT_SIZE;
            byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);

            // Check if slot is occupied
            if ((flags & FLAG_OCCUPIED) != 0) {
                // Read the data offset for this entry (offset within data region)
                long dataRegionOffset = readDataOffset(i);

                // Calculate absolute entry offset
                long entryOffset = slotTableSize + dataRegionOffset;

                // Validate entry offset is within bounds
                if (entryOffset + ENTRY_HEADER_SIZE > segmentSize) {
                    // Entry header would be out of bounds - skip this slot
                    continue;
                }

                // Read entry size to calculate where this entry ends
                int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
                int valueLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);
                int entrySize = ENTRY_HEADER_SIZE + keyLen + valueLen + ENTRY_TRAILER_SIZE;

                // Calculate where this entry ends
                long entryEndOffset = dataRegionOffset + entrySize;

                // Update max end offset
                if (entryEndOffset > maxEndOffset) {
                    maxEndOffset = entryEndOffset;
                }

                // Every occupied slot counts towards occupancy: a tombstoned slot is
                // never reused, so it still consumes probe budget. Live entries are
                // counted separately for the read-side index space.
                recoveredEntryCount++;
                if ((flags & FLAG_TOMBSTONE) == 0) {
                    recoveredLiveCount++;
                }
            }
        }

        this.entryCount = recoveredEntryCount;
        this.liveEntryCount = recoveredLiveCount;
        this.dataOffset = maxEndOffset;
    }

    // ==================== Public API ====================

    /**
     * Inserts or updates a key-value pair.
     *
     * <p>This operation:
     * <ol>
     *   <li>Computes the key hash</li>
     *   <li>Finds the appropriate slot using Robin Hood hashing</li>
     *   <li>Writes the entry to the data region</li>
     *   <li>Updates the slot table</li>
     * </ol>
     *
     * <p>If the key already exists, its value is updated. If the load factor
     * would exceed 0.7, the operation is rejected.</p>
     *
     * @param key The key (must not be null)
     * @param value The value (must not be null)
     * @throws IllegalStateException if segment is closed or load factor exceeded
     * @throws IllegalArgumentException if key or value is null, or too large
     */
    public void put(byte[] key, byte[] value) {
        validateKey(key);
        validateValue(value);

        lock.writeLock().lock();
        try {
            // Inside the lock, not before it. beginSplit() and close() both take this same
            // write lock to set their state, so a check made outside it is a check-then-act:
            // the write blocks acquiring the lock, the split snapshots the segment while it
            // waits, and the write then lands in a segment whose contents have already been
            // copied into the children. The caller is told it succeeded and the entry is gone.
            requireWritable();
            putInternal(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Applies a put to the segment. The caller must hold the write lock.
     *
     * <p>Ordering matters here:</p>
     * <ol>
     *   <li>An overwrite of an existing key consumes no new slot, so the load-factor
     *       guard is evaluated only for a genuine insert. Otherwise a segment at the
     *       capacity boundary would reject updates it can trivially satisfy.</li>
     *   <li>The segment mutation happens <em>before</em> the write-ahead log append.
     *       The log is a live read source for tail-scan queries, so a write that the
     *       segment rejects must never be visible in the log or replayed on restart.</li>
     * </ol>
     *
     * @param key   The key (already validated)
     * @param value The value (already validated)
     * @throws SegmentCapacityExceededException if the segment cannot admit the write
     */
    private void putInternal(byte[] key, byte[] value) {
        long keyHash = computeHash(key);
        int existingSlot = findSlot(keyHash, key);

        if (existingSlot >= 0) {
            updateEntry(existingSlot, key, value);
        } else {
            if (isFull()) {
                double loadFactor = (double) (entryCount + 1) / slotCount;
                throw new SegmentCapacityExceededException(
                    SegmentCapacityExceededException.Reason.LOAD_FACTOR,
                    "Load factor " + loadFactor + " would reach or exceed threshold " + loadFactorThreshold);
            }

            int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
            requireDataSpace(entrySize);
            insertEntry(keyHash, key, value);
        }

        appendPutToLog(key, value);

        // Increment modification epoch to invalidate existing zero-copy slices
        modificationEpoch++;
    }

    /**
     * Says once that this segment is accepting mutations that nothing is logging.
     *
     * <p>Every segment the engine creates - on discovery, on creation, on a split, on a
     * compaction - is given a log, so reaching here means the segment was built directly and
     * its writes survive only if the mapping happens to be written back. Silence made that
     * indistinguishable from a healthy segment.</p>
     */
    private void warnUnlogged() {
        if (unloggedWarningIssued.compareAndSet(false, true)) {
            LOGGER.log(java.util.logging.Level.SEVERE,
                "Segment {0} has no write-ahead log attached: its mutations are not logged and "
                + "will not survive a crash.", path);
        }
    }

    /**
     * Appends a PUT record to the attached write-ahead log, if any.
     *
     * @param key   The key
     * @param value The value
     * @throws IllegalStateException if the log append fails
     */
    private void appendPutToLog(byte[] key, byte[] value) {
        WriteLog log = this.writeLog;
        if (log == null) {
            warnUnlogged();
            return;
        }
        try {
            log.appendPut(key, value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append to write-ahead log", e);
        }
    }

    /**
     * Appends a DELETE record to the attached write-ahead log, if any.
     *
     * @param key The key
     * @throws IllegalStateException if the log append fails
     */
    private void appendDeleteToLog(byte[] key) {
        WriteLog log = this.writeLog;
        if (log == null) {
            warnUnlogged();
            return;
        }
        try {
            log.appendDelete(key);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append to write-ahead log", e);
        }
    }

    /**
     * Ensures the data region can hold one more entry record of the given size.
     *
     * @param entrySize Size of the record about to be appended
     * @throws SegmentCapacityExceededException if the data region is exhausted
     */
    private void requireDataSpace(int entrySize) {
        if (!hasDataSpace(entrySize)) {
            throw new SegmentCapacityExceededException(
                SegmentCapacityExceededException.Reason.DATA_REGION,
                "Insufficient space in segment data region. Entry size: " + entrySize +
                ", available: " + (segment.byteSize() - slotTableSize - dataOffset));
        }
    }

    /**
     * Retrieves the value for a key as a zero-copy MemorySegment slice.
     *
     * <p>The returned MemorySegment is a direct view into the off-heap memory-mapped
     * file region. This is a true zero-copy operation - no bytes are copied to the
     * Java heap. The returned slice points directly to native memory.</p>
     *
     * <h3>Zero-Copy Guarantee</h3>
     * <ul>
     *   <li>The returned MemorySegment is a native segment (off-heap), not a heap segment</li>
     *   <li>{@code result.heapBase().orElse(null)} will return {@code null}</li>
     *   <li>The slice is created via {@code segment.asSlice()}, not by copying to a byte array</li>
     * </ul>
     *
     * <h3>Slice Validity</h3>
     * <p>The slice remains valid until:</p>
     * <ul>
     *   <li>The segment is closed</li>
     *   <li>The underlying memory-mapped file is unmapped</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method uses a read lock, allowing concurrent reads from multiple threads.</p>
     *
     * @param key The key to look up
     * @return MemorySegment slice pointing to off-heap memory containing the value,
     *         or null if not found or deleted (tombstoned)
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if key is null
     */
    public MemorySegment get(byte[] key) {
        validateNotClosed();
        validateKey(key);

        lock.readLock().lock();
        try {
            // Check if segment is splitting - serve from snapshot for consistent reads
            // This ensures copy-on-write semantics: reads see the pre-split snapshot
            // even if the underlying memory could be modified externally
            if (splitState == SplitState.SPLITTING && splitSnapshot != null) {
                return getFromSnapshot(key);
            }

            long keyHash = computeHash(key);
            int slotIndex = findSlot(keyHash, key);

            if (slotIndex < 0) {
                return null; // Not found
            }

            // Check if tombstoned
            if (isSlotTombstoned(slotIndex)) {
                return null;
            }

            // Get the entry and return value slice
            return getValueSlice(slotIndex);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Zero-Copy Slice Validity API ====================

    /**
     * Retrieves the value for a key as a zero-copy slice with epoch tracking.
     *
     * <p>This method returns a {@link ZeroCopySlice} that wraps the value's MemorySegment
     * along with the modification epoch at which it was created. Callers can use
     * {@link ZeroCopySlice#isValid()} or {@link #isSliceValid(ZeroCopySlice)} to check
     * if the slice is still valid before accessing the data.</p>
     *
     * <h3>Validity Contract</h3>
     * <p>The returned slice remains valid until:</p>
     * <ul>
     *   <li>The segment is modified (put/delete) by any thread</li>
     *   <li>The segment is closed</li>
     *   <li>The Arena used to create the segment is closed</li>
     * </ul>
     *
     * <h3>Usage Pattern</h3>
     * <pre>{@code
     * ZeroCopySlice slice = segment.getWithEpoch(key);
     * if (slice.isFound()) {
     *     // Use the data
     *     processValue(slice.data());
     *
     *     // Later, check validity before accessing again
     *     if (slice.isValid()) {
     *         byte[] copy = slice.toByteArray();
     *     }
     * }
     * }</pre>
     *
     * @param key The key to look up
     * @return A ZeroCopySlice containing the value data and creation epoch,
     *         or a not-found slice if the key doesn't exist
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if key is null
     * @see ZeroCopySlice
     * @see #isSliceValid(ZeroCopySlice)
     * @see #currentEpoch()
     */
    public ZeroCopySlice getWithEpoch(byte[] key) {
        validateNotClosed();
        validateKey(key);

        lock.readLock().lock();
        try {
            // Capture current epoch before reading
            // During split, use the snapshot's epoch for consistency
            long epoch = (splitState == SplitState.SPLITTING && splitSnapshot != null)
                ? splitSnapshot.modificationEpoch
                : modificationEpoch;

            // Check if segment is splitting - serve from snapshot for consistent reads
            if (splitState == SplitState.SPLITTING && splitSnapshot != null) {
                MemorySegment valueSlice = getFromSnapshot(key);
                if (valueSlice == null) {
                    return ZeroCopySlice.notFound(epoch, segmentId, this);
                }
                return new ZeroCopySlice(epoch, segmentId, valueSlice, this);
            }

            long keyHash = computeHash(key);
            int slotIndex = findSlot(keyHash, key);

            if (slotIndex < 0) {
                return ZeroCopySlice.notFound(epoch, segmentId, this);
            }

            // Check if tombstoned
            if (isSlotTombstoned(slotIndex)) {
                return ZeroCopySlice.notFound(epoch, segmentId, this);
            }

            // Get the value slice
            MemorySegment valueSlice = getValueSlice(slotIndex);
            return new ZeroCopySlice(epoch, segmentId, valueSlice, this);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if a zero-copy slice is still valid.
     *
     * <p>A slice is valid if and only if:</p>
     * <ul>
     *   <li>The segment has not been closed</li>
     *   <li>No modifications have occurred since the slice was created
     *       (slice.epoch == current modification epoch)</li>
     *   <li>The slice belongs to this segment (slice.segmentId == this.segmentId)</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong> This method is thread-safe and can be called
     * from any thread. However, the validity result is only meaningful at the moment
     * it is checked - a slice could become invalid immediately after this method
     * returns true if another thread modifies the segment.</p>
     *
     * @param slice The slice to check
     * @return true if the slice is still valid, false otherwise
     * @throws NullPointerException if slice is null
     * @see ZeroCopySlice#isValid()
     */
    public boolean isSliceValid(ZeroCopySlice slice) {
        if (slice == null) {
            throw new NullPointerException("Slice cannot be null");
        }

        // Check if segment is closed
        if (closed) {
            return false;
        }

        // Check if slice belongs to this segment
        if (slice.segmentId() != this.segmentId) {
            return false;
        }

        // Check if epoch matches (no modifications since slice creation)
        return slice.epoch() == modificationEpoch;
    }

    /**
     * Returns the current modification epoch.
     *
     * <p>The modification epoch is a monotonically increasing counter that is
     * incremented every time the segment is modified (put or delete). This can
     * be used to track whether any changes have occurred since a previous point
     * in time.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Epoch starts at 0 when segment is created</li>
     *   <li>Epoch is always non-negative</li>
     *   <li>Epoch wraps to negative after Long.MAX_VALUE modifications (extremely rare)</li>
     * </ul>
     *
     * @return The current modification epoch
     */
    public long currentEpoch() {
        return modificationEpoch;
    }

    /**
     * Returns the unique identifier for this segment.
     *
     * <p>Each Segment instance is assigned a unique ID at creation time. This ID
     * is used to verify that zero-copy slices belong to the correct segment when
     * checking validity.</p>
     *
     * @return The unique segment identifier (non-negative)
     */
    public long segmentId() {
        return segmentId;
    }

    /**
     * Deletes a key-value pair by marking it as tombstoned.
     *
     * <p>This operation does not immediately remove the entry from disk.
     * The entry is marked with a tombstone flag and will be cleaned up
     * during compaction.</p>
     *
     * @param key The key to delete
     * @return true if the key was found and deleted, false if not found
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if key is null
     */
    public boolean delete(byte[] key) {
        validateKey(key);

        lock.writeLock().lock();
        try {
            // Inside the lock, for the same reason as put: an acknowledged delete that a
            // concurrent split discards leaves the key readable through the children.
            requireWritable();
            long keyHash = computeHash(key);
            int slotIndex = findSlot(keyHash, key);

            if (slotIndex < 0) {
                return false; // Not found
            }

            // Check if already tombstoned
            if (isSlotTombstoned(slotIndex)) {
                return false;
            }

            // Mark slot and entry as tombstoned first: a delete the segment rejects
            // must never appear in the log, which tail-scan queries read live.
            markAsTombstoned(slotIndex);

            appendDeleteToLog(key);

            // Increment modification epoch to invalidate existing zero-copy slices
            modificationEpoch++;

            // Note: entryCount is NOT decremented for tombstones - the slot stays
            // occupied until compaction. liveEntryCount is decremented instead.

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the current load factor.
     *
     * @return Load factor as entry_count / slot_count
     */
    public double loadFactor() {
        return (double) entryCount / slotCount;
    }

    /**
     * Returns the number of occupied slots in this segment.
     *
     * <p>Tombstoned entries are included: their slots stay occupied until compaction
     * rebuilds the segment, so they still consume probe budget and count towards the
     * load factor. Use {@link #liveEntryCount()} for the number of readable entries.</p>
     *
     * @return Occupied slot count
     */
    public int entryCount() {
        return entryCount;
    }

    /**
     * Returns the number of readable (non-tombstoned) entries in this segment.
     *
     * <p>This is the index space accepted by {@link #readEntryKey(int)} and
     * {@link #readEntryValue(int)}.</p>
     *
     * @return Live entry count
     */
    public int liveEntryCount() {
        return liveEntryCount;
    }

    /**
     * Returns the number of slots in this segment.
     *
     * @return Slot count
     */
    public int slotCount() {
        return slotCount;
    }

    /**
     * Returns the path to the backing file.
     *
     * @return File path
     */
    public Path path() {
        return path;
    }

    /**
     * Records that the backing file has been renamed.
     *
     * <p>Compaction and split publication move the backing file into its canonical
     * {@code segment_<index>.dat} name with an atomic rename while this segment stays
     * mapped. The mapping and the open file descriptor follow the inode, so only the
     * recorded path has to be updated.</p>
     *
     * <p>Callers must have completed the rename successfully before calling this.</p>
     *
     * @param newPath The path the backing file now lives at (must not be null)
     * @throws NullPointerException if newPath is null
     */
    void renamedTo(Path newPath) {
        this.path = Objects.requireNonNull(newPath, "newPath cannot be null");
    }

    /**
     * Returns the size of the memory-mapped region.
     *
     * @return Size in bytes
     */
    public long size() {
        return segment.byteSize();
    }

    /**
     * Flushes all changes to disk.
     */
    public void flush() {
        validateNotClosed();
        segment.force();
    }

    /**
     * Checks if this segment is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the Arena used for memory management.
     *
     * <p>The Arena controls the lifecycle of the memory-mapped region.
     * When the Arena is closed, the memory mapping is released.</p>
     *
     * @return The Arena
     */
    public Arena arena() {
        return arena;
    }

    /**
     * Returns the underlying MemorySegment.
     *
     * <p><strong>Warning:</strong> Direct access to the MemorySegment bypasses
     * thread-safety guarantees. Use with caution.</p>
     *
     * @return The MemorySegment
     */
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            // Force any dirty pages to disk
            segment.force();

            // Close the write-ahead log this segment owns, so its descriptor and
            // final force() are not leaked when the segment is retired.
            WriteLog log = this.writeLog;
            if (log != null) {
                try {
                    log.close();
                } catch (IOException e) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close write log for segment " + path, e);
                }
            }

            // Close the HNSW graph this segment owns. It holds a FileChannel, an Arena and a
            // whole-file mapping, and nothing else in production releases them -- so every retired
            // segment leaked one of each until the process exited, which is what eventually
            // exhausts the descriptor and mapping budgets. A graph carried onto a replacement by
            // compaction is detached first (see detachHnswGraph), so this only ever closes a graph
            // no one else is using.
            io.excalibur.vector.hnsw.HnswGraph graph = this.hnswGraph;
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close HNSW graph for segment " + path, e);
                }
            }

            // Close the LSH index this segment owns. It maps one file per hash table plus a
            // descriptor, so leaving it open leaks every one of those mappings when the segment
            // is retired. Only the owner can release them.
            excalibur.filter.lsh.LshIndex lsh = this.lshIndex;
            if (lsh != null) {
                try {
                    lsh.close();
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close LSH index for segment " + path, e);
                }
            }

            // Close the file channel
            try {
                fileChannel.close();
            } catch (IOException e) {
                // Ignore close errors - segment is still considered closed
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Striped Lock Accessors ====================

    /**
     * Returns the read lock for this segment.
     *
     * <p>Use this for advanced scenarios where you need to hold the lock
     * across multiple operations, such as consistent bulk reads:</p>
     *
     * <pre>{@code
     * Lock readLock = segment.readLock();
     * readLock.lock();
     * try {
     *     MemorySegment v1 = segment.getWithoutLock(key1);
     *     MemorySegment v2 = segment.getWithoutLock(key2);
     *     // Both reads see a consistent snapshot
     * } finally {
     *     readLock.unlock();
     * }
     * }</pre>
     *
     * <p><strong>Striped Locking Note:</strong> Each segment instance has its
     * own independent read lock. Acquiring this lock only blocks operations
     * on <em>this</em> segment, not other segments in the keyspace.</p>
     *
     * @return The read lock for this segment
     * @see #writeLock()
     * @see #getWithoutLock(byte[])
     */
    public Lock readLock() {
        return lock.readLock();
    }

    /**
     * Returns the write lock for this segment.
     *
     * <p>Use this for advanced scenarios where you need to hold the lock
     * across multiple write operations:</p>
     *
     * <pre>{@code
     * Lock writeLock = segment.writeLock();
     * writeLock.lock();
     * try {
     *     segment.putWithoutLock(key1, value1);
     *     segment.putWithoutLock(key2, value2);
     *     // Both writes are atomic from external perspective
     * } finally {
     *     writeLock.unlock();
     * }
     * }</pre>
     *
     * <p><strong>Striped Locking Note:</strong> Each segment instance has its
     * own independent write lock. Acquiring this lock only blocks operations
     * on <em>this</em> segment, not other segments in the keyspace.</p>
     *
     * @return The write lock for this segment
     * @see #readLock()
     * @see #putWithoutLock(byte[], byte[])
     */
    public Lock writeLock() {
        return lock.writeLock();
    }

    /**
     * Retrieves the value for a key WITHOUT acquiring the lock.
     *
     * <p><strong>Warning:</strong> This method does NOT acquire any lock.
     * The caller MUST hold either the read lock or write lock before calling
     * this method. Use only in conjunction with {@link #readLock()} or
     * {@link #writeLock()}.</p>
     *
     * <p>This method is designed for bulk operations where the lock is
     * held externally:</p>
     *
     * <pre>{@code
     * Lock readLock = segment.readLock();
     * readLock.lock();
     * try {
     *     // Multiple reads without lock acquisition overhead
     *     for (byte[] key : keys) {
     *         MemorySegment value = segment.getWithoutLock(key);
     *         // Process value...
     *     }
     * } finally {
     *     readLock.unlock();
     * }
     * }</pre>
     *
     * @param key The key to look up
     * @return MemorySegment slice containing the value, or null if not found or deleted
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if key is null
     * @see #readLock()
     */
    public MemorySegment getWithoutLock(byte[] key) {
        validateNotClosed();
        validateKey(key);

        // Caller must hold lock - no lock acquisition here
        long keyHash = computeHash(key);
        int slotIndex = findSlot(keyHash, key);

        if (slotIndex < 0) {
            return null; // Not found
        }

        // Check if tombstoned
        if (isSlotTombstoned(slotIndex)) {
            return null;
        }

        // Get the entry and return value slice
        return getValueSlice(slotIndex);
    }

    // ==================== High-Performance Lookup Methods ====================

    /**
     * Retrieves the value for a key using a pre-computed hash.
     *
     * <p>This method is optimized for high-throughput scenarios where the hash
     * can be pre-computed and cached. The caller is responsible for ensuring
     * the hash matches the key.</p>
     *
     * <p><strong>Thread Safety:</strong> This method acquires the read lock.</p>
     *
     * @param key The key to look up (must not be null)
     * @param keyHash Pre-computed hash of the key (must match the key)
     * @return MemorySegment slice containing the value, or null if not found
     * @throws IllegalStateException if segment is closed
     * @see #hashKey(byte[])
     */
    public MemorySegment getByHash(byte[] key, long keyHash) {
        validateNotClosed();
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        lock.readLock().lock();
        try {
            int slotIndex = findSlot(keyHash, key);
            if (slotIndex < 0 || isSlotTombstoned(slotIndex)) {
                return null;
            }
            return getValueSlice(slotIndex);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retrieves the value for a key using a pre-computed hash without lock acquisition.
     *
     * <p><strong>Warning:</strong> This method does NOT acquire any lock.
     * The caller MUST hold the read lock before calling this method.
     * Use only in read-only scenarios or with external synchronization.</p>
     *
     * <p>This is the fastest possible lookup path, designed for benchmarking
     * and high-throughput batch operations where the caller manages concurrency.</p>
     *
     * @param key The key to look up (must not be null)
     * @param keyHash Pre-computed hash of the key
     * @return MemorySegment slice containing the value, or null if not found
     * @throws IllegalStateException if segment is closed
     * @see #readLock()
     */
    public MemorySegment getByHashWithoutLock(byte[] key, long keyHash) {
        validateNotClosed();
        // Caller must hold lock - no lock acquisition here
        int slotIndex = findSlot(keyHash, key);
        if (slotIndex < 0 || isSlotTombstoned(slotIndex)) {
            return null;
        }
        return getValueSlice(slotIndex);
    }

    /**
     * Computes the hash for a key that can be used with getByHash methods.
     *
     * <p>This method returns a normalized positive hash suitable for use
     * with the high-performance lookup methods.</p>
     *
     * @param key The key to hash
     * @return The 64-bit hash value (positive, in range [0, Long.MAX_VALUE])
     * @throws IllegalArgumentException if key is null
     */
    public long hashKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        return computeHash(key);
    }

    /**
     * Batch lookup of multiple keys with pre-computed hashes.
     *
     * <p>This method is optimized for high-throughput batch operations.
     * It acquires the read lock once and performs all lookups, then releases.</p>
     *
     * @param keys Array of keys to look up
     * @param hashes Array of pre-computed hashes (must match keys)
     * @return Array of MemorySegment slices (null for not-found entries)
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if arrays are null or mismatched length
     */
    public MemorySegment[] batchGetByHash(byte[][] keys, long[] hashes) {
        validateNotClosed();
        if (keys == null || hashes == null) {
            throw new IllegalArgumentException("Keys and hashes cannot be null");
        }
        if (keys.length != hashes.length) {
            throw new IllegalArgumentException("Keys and hashes arrays must have same length");
        }

        MemorySegment[] results = new MemorySegment[keys.length];
        lock.readLock().lock();
        try {
            for (int i = 0; i < keys.length; i++) {
                int slotIndex = findSlot(hashes[i], keys[i]);
                if (slotIndex >= 0 && !isSlotTombstoned(slotIndex)) {
                    results[i] = getValueSlice(slotIndex);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return results;
    }

    /**
     * Inserts or updates a key-value pair WITHOUT acquiring the lock.
     *
     * <p><strong>Warning:</strong> This method does NOT acquire any lock.
     * The caller MUST hold the write lock before calling this method.
     * Use only in conjunction with {@link #writeLock()}.</p>
     *
     * <p>This method is designed for bulk write operations:</p>
     *
     * <pre>{@code
     * Lock writeLock = segment.writeLock();
     * writeLock.lock();
     * try {
     *     // Multiple writes without lock acquisition overhead
     *     for (Map.Entry<byte[], byte[]> entry : entries) {
     *         segment.putWithoutLock(entry.getKey(), entry.getValue());
     *     }
     * } finally {
     *     writeLock.unlock();
     * }
     * }</pre>
     *
     * @param key The key (must not be null)
     * @param value The value (must not be null)
     * @throws IllegalStateException if segment is closed or load factor exceeded
     * @throws IllegalArgumentException if key or value is null, or too large
     * @see #writeLock()
     */
    public void putWithoutLock(byte[] key, byte[] value) {
        validateNotClosed();
        validateKey(key);
        validateValue(value);

        // Caller must hold write lock - no lock acquisition here
        putInternal(key, value);
    }

    /**
     * Deletes a key-value pair WITHOUT acquiring the lock.
     *
     * <p><strong>Warning:</strong> This method does NOT acquire any lock.
     * The caller MUST hold the write lock before calling this method.
     * Use only in conjunction with {@link #writeLock()}.</p>
     *
     * @param key The key to delete
     * @return true if the key was found and deleted, false if not found
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if key is null
     * @see #writeLock()
     */
    public boolean deleteWithoutLock(byte[] key) {
        validateNotClosed();
        validateKey(key);

        // Caller must hold write lock - no lock acquisition here

        long keyHash = computeHash(key);
        int slotIndex = findSlot(keyHash, key);

        if (slotIndex < 0) {
            return false; // Not found
        }

        // Check if already tombstoned
        if (isSlotTombstoned(slotIndex)) {
            return false;
        }

        // Mark slot and entry as tombstoned first: a delete the segment rejects
        // must never appear in the log, which tail-scan queries read live.
        markAsTombstoned(slotIndex);

        appendDeleteToLog(key);

        // Increment modification epoch to invalidate existing zero-copy slices
        modificationEpoch++;

        return true;
    }

    // ==================== Robin Hood Hashing Implementation ====================

    /**
     * Finds the slot index for a key, or returns -1 if not found.
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @return Slot index if found, -1 if not found
     */
    private int findSlot(long keyHash, byte[] key) {
        int idealSlot = (int) (keyHash & (slotCount - 1)); // hash % slotCount for power of 2

        for (int distance = 0; distance < MAX_PROBE_LENGTH; distance++) {
            int slotIndex = (idealSlot + distance) & (slotCount - 1);
            long slotOffset = (long) slotIndex * SLOT_SIZE;

            // Check if slot is occupied
            byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
            if ((flags & FLAG_OCCUPIED) == 0) {
                // Empty slot - key not found
                return -1;
            }

            // Check if we've gone past all entries with smaller distances
            byte slotDistance = segment.get(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET);
            if (slotDistance < distance) {
                // Would have found it by now due to Robin Hood ordering
                return -1;
            }

            // Check if hash matches
            long slotKeyHash = segment.get(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET);
            if (slotKeyHash != keyHash) {
                continue;
            }

            // Hash matches - verify key bytes
            if (keyMatches(slotIndex, key)) {
                return slotIndex;
            }
        }

        return -1;
    }

    /**
     * Inserts a new entry using Robin Hood hashing.
     *
     * <p>Robin Hood hashing: When inserting, if the current slot occupant has a
     * smaller distance from its ideal slot than the new entry, we swap them.
     * This ensures that entries with longer probe distances "steal" from
     * entries with shorter distances, minimizing maximum probe length.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Robin Hood swap occurs when: {@code new_entry.distance > existing_entry.distance}</li>
     *   <li>After swap, displaced entry's distance is incremented by 1</li>
     *   <li>Distance values are maintained in range [0, 127]</li>
     * </ul>
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @param value The value bytes
     */
    private void insertEntry(long keyHash, byte[] key, byte[] value) {
        // The incoming entry's record is appended to the data region exactly once.
        // A Robin Hood displacement then moves only slot metadata: the displaced
        // entry keeps the data-region record it already owns, so a probe chain of
        // length k consumes one record, not k + 1.
        int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
        long newRecordOffset = dataOffset;
        writeEntryAtOffset(slotTableSize + newRecordOffset, keyHash, 0, key, value);
        dataOffset += entrySize;

        // Metadata of the entry currently being carried along the probe chain.
        long carriedHash = keyHash;
        long carriedRecordOffset = newRecordOffset;
        byte carriedFlags = FLAG_OCCUPIED;

        // Ideal slot of the carried entry; recalculated after every displacement
        // because the displaced entry hashes elsewhere.
        int currentIdealSlot = (int) (keyHash & (slotCount - 1));
        int distance = 0;

        // Victims overwritten so far, newest last, so a failure part-way through
        // the chain can be unwound instead of losing a pre-existing entry.
        List<SlotSnapshot> displaced = null;

        while (distance < MAX_PROBE_LENGTH) {
            int slotIndex = (currentIdealSlot + distance) & (slotCount - 1);
            long slotOffset = (long) slotIndex * SLOT_SIZE;

            byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
            if ((flags & FLAG_OCCUPIED) == 0) {
                // Found an empty slot - the chain terminates here
                writeSlotMetadata(slotIndex, carriedHash, distance, carriedFlags, carriedRecordOffset);
                entryCount++;
                liveEntryCount++;
                return;
            }

            // Robin Hood swap condition: the occupant travelled less far than we have
            int slotDistance = segment.get(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET) & 0xFF;
            if (slotDistance < distance) {
                SlotSnapshot victim = new SlotSnapshot(
                    slotIndex,
                    segment.get(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET),
                    slotDistance,
                    flags,
                    readDataOffset(slotIndex));

                writeSlotMetadata(slotIndex, carriedHash, distance, carriedFlags, carriedRecordOffset);

                if (displaced == null) {
                    displaced = new ArrayList<>();
                }
                displaced.add(victim);

                // Carry the victim onwards from the slot after the one we took
                carriedHash = victim.keyHash();
                carriedRecordOffset = victim.dataRegionOffset();
                carriedFlags = victim.flags();
                currentIdealSlot = (int) (carriedHash & (slotCount - 1));
                distance = slotDistance + 1;

                if (distance > MAX_DISTANCE) {
                    rollbackInsert(displaced, newRecordOffset);
                    throw new SegmentCapacityExceededException(
                        SegmentCapacityExceededException.Reason.DISTANCE_OVERFLOW,
                        "Distance overflow: displaced entry would have distance " + distance +
                        " which exceeds maximum " + MAX_DISTANCE + ". " +
                        "Hash table is too full or has pathological collision pattern.");
                }

                // The distance was already advanced for the new position
                continue;
            }

            distance++;
        }

        rollbackInsert(displaced, newRecordOffset);
        throw new SegmentCapacityExceededException(
            SegmentCapacityExceededException.Reason.PROBE_EXHAUSTED,
            "Failed to insert after " + MAX_PROBE_LENGTH + " probes. Load factor: " + loadFactor());
    }

    /**
     * Restores every slot overwritten by a failed insertion and releases the data
     * region record that was appended for the incoming entry.
     *
     * <p>Victims are restored newest-first so that a slot overwritten more than once
     * ends up holding its original occupant.</p>
     *
     * @param displaced      Victims in the order they were overwritten, or null if none
     * @param recordOffset   Data region offset of the record appended for this insert
     */
    private void rollbackInsert(List<SlotSnapshot> displaced, long recordOffset) {
        if (displaced != null) {
            for (int i = displaced.size() - 1; i >= 0; i--) {
                SlotSnapshot victim = displaced.get(i);
                writeSlotMetadata(victim.slotIndex(), victim.keyHash(), victim.distance(),
                    victim.flags(), victim.dataRegionOffset());
            }
        }
        // Nothing references the record we appended, so the space is reclaimed.
        dataOffset = recordOffset;
    }

    /**
     * Points a slot at an existing data region record.
     *
     * <p>The record's distance byte is kept in sync with the slot's, so a record moved
     * by Robin Hood displacement stays self-describing without being re-appended.</p>
     *
     * @param slotIndex        The slot to write
     * @param keyHash          The entry's key hash
     * @param distance         Distance from the entry's ideal slot, in [0, {@link #MAX_DISTANCE}]
     * @param flags            Slot flags to store (preserves the tombstone bit)
     * @param dataRegionOffset Offset of the entry's record within the data region
     * @throws IllegalStateException if distance is out of range
     */
    private void writeSlotMetadata(int slotIndex, long keyHash, int distance,
                                   byte flags, long dataRegionOffset) {
        validateDistance(distance);

        segment.set(BYTE_LAYOUT, slotTableSize + dataRegionOffset + ENTRY_DISTANCE_OFFSET, (byte) distance);

        long slotOffset = (long) slotIndex * SLOT_SIZE;
        segment.set(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET, keyHash);
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET, (byte) distance);
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET, flags);
        writeDataOffset(slotIndex, dataRegionOffset);
    }

    /**
     * Updates an existing entry with a new value.
     *
     * @param slotIndex The slot index of the existing entry
     * @param key The key (for validation)
     * @param value The new value
     */
    private void updateEntry(int slotIndex, byte[] key, byte[] value) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;

        // Read current data offset
        long dataRegionOffset = readDataOffset(slotIndex);

        // Read existing entry to get key length and distance
        long entryOffset = slotTableSize + dataRegionOffset;
        int existingKeyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        int distance = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_DISTANCE_OFFSET) & 0xFF;

        // Calculate sizes
        int newEntrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
        int existingEntrySize = ENTRY_HEADER_SIZE + existingKeyLen +
            segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET) + ENTRY_TRAILER_SIZE;

        // Check if we can update in place
        if (newEntrySize <= existingEntrySize) {
            // Update in place, preserving distance
            long keyHash = segment.get(LONG_LAYOUT, entryOffset + ENTRY_KEY_HASH_OFFSET);
            writeEntryAtOffset(entryOffset, keyHash, distance, key, value);
        } else {
            // Need to append a new record and repoint the slot at it
            requireDataSpace(newEntrySize);
            long newDataOffset = dataOffset;
            long keyHash = segment.get(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET);
            writeEntryAtOffset(slotTableSize + newDataOffset, keyHash, distance, key, value);

            // Update slot's data offset
            writeDataOffset(slotIndex, newDataOffset);
            dataOffset += newEntrySize;
        }

        // Clear tombstone flag in case we're resurrecting a deleted entry
        byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
        if ((flags & FLAG_TOMBSTONE) != 0) {
            liveEntryCount++;
        }
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET, (byte) (flags & ~FLAG_TOMBSTONE));
    }

    // ==================== Memory Operations ====================

    /**
     * Writes an entry at the specified offset in the data region.
     *
     * <p>Entry format (as per spec):</p>
     * <ul>
     *   <li>key_hash (8 bytes, little-endian)</li>
     *   <li>key_len (4 bytes, little-endian)</li>
     *   <li>value_len (4 bytes, little-endian)</li>
     *   <li>distance (1 byte, unsigned, range [0, 127])</li>
     *   <li>key_bytes (variable)</li>
     *   <li>value_bytes (variable)</li>
     *   <li>tombstone (1 byte)</li>
     * </ul>
     *
     * @param offset The byte offset in the data region
     * @param keyHash The 64-bit key hash
     * @param distance Distance from ideal slot (must be in range [0, 127])
     * @param key The key bytes
     * @param value The value bytes
     * @throws IllegalStateException if distance is out of range [0, 127]
     */
    private void writeEntryAtOffset(long offset, long keyHash, int distance, byte[] key, byte[] value) {
        // Domain correctness: validate distance before writing
        validateDistance(distance);

        segment.set(LONG_LAYOUT, offset + ENTRY_KEY_HASH_OFFSET, keyHash);
        segment.set(INT_LAYOUT, offset + ENTRY_KEY_LEN_OFFSET, key.length);
        segment.set(INT_LAYOUT, offset + ENTRY_VALUE_LEN_OFFSET, value.length);
        segment.set(BYTE_LAYOUT, offset + ENTRY_DISTANCE_OFFSET, (byte) distance);

        // Copy key bytes
        MemorySegment.copy(key, 0, segment, BYTE_LAYOUT, offset + ENTRY_KEY_BYTES_OFFSET, key.length);

        // Copy value bytes
        MemorySegment.copy(value, 0, segment, BYTE_LAYOUT, offset + ENTRY_KEY_BYTES_OFFSET + key.length, value.length);

        // Write tombstone (0 = live)
        segment.set(BYTE_LAYOUT, offset + ENTRY_KEY_BYTES_OFFSET + key.length + value.length, (byte) 0);
    }

    /**
     * Writes the 6-byte data offset to a slot.
     */
    private void writeDataOffset(int slotIndex, long dataRegionOffset) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        // Write as 6 bytes (48 bits) - sufficient for large segments
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET,
            (byte) (dataRegionOffset & 0xFF));
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 1,
            (byte) ((dataRegionOffset >> 8) & 0xFF));
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 2,
            (byte) ((dataRegionOffset >> 16) & 0xFF));
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 3,
            (byte) ((dataRegionOffset >> 24) & 0xFF));
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 4,
            (byte) ((dataRegionOffset >> 32) & 0xFF));
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 5,
            (byte) ((dataRegionOffset >> 40) & 0xFF));
    }

    /**
     * Reads the 6-byte data offset from a slot.
     */
    private long readDataOffset(int slotIndex) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        long offset = 0;
        offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET) & 0xFFL);
        offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 1) & 0xFFL) << 8;
        offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 2) & 0xFFL) << 16;
        offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 3) & 0xFFL) << 24;
        offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 4) & 0xFFL) << 32;
        offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 5) & 0xFFL) << 40;
        return offset;
    }

    /**
     * Checks if the key in the slot matches the given key.
     */
    private boolean keyMatches(int slotIndex, byte[] key) {
        long dataRegionOffset = readDataOffset(slotIndex);
        long entryOffset = slotTableSize + dataRegionOffset;

        // Read key length
        int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        if (keyLen != key.length) {
            return false;
        }

        // Compare key bytes
        for (int i = 0; i < keyLen; i++) {
            byte entryByte = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET + i);
            if (entryByte != key[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if a slot is tombstoned.
     */
    private boolean isSlotTombstoned(int slotIndex) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
        return (flags & FLAG_TOMBSTONE) != 0;
    }

    /**
     * Marks a slot and its entry as tombstoned.
     *
     * <p>Callers must have verified the slot is occupied and not already tombstoned,
     * so this method is the single live-to-tombstoned transition point and is where
     * {@code liveEntryCount} is decremented. {@code entryCount} is unchanged: the slot
     * stays occupied until compaction rebuilds the segment.</p>
     */
    private void markAsTombstoned(int slotIndex) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;

        // Update slot flags
        byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET, (byte) (flags | FLAG_TOMBSTONE));

        // Update entry tombstone byte
        long dataRegionOffset = readDataOffset(slotIndex);
        long entryOffset = slotTableSize + dataRegionOffset;

        // Read key length and value length to find tombstone byte position
        int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        int valueLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);
        long tombstoneOffset = entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen + valueLen;

        segment.set(BYTE_LAYOUT, tombstoneOffset, (byte) 1);

        liveEntryCount--;
    }

    /**
     * Returns a zero-copy MemorySegment slice of the value.
     */
    private MemorySegment getValueSlice(int slotIndex) {
        long dataRegionOffset = readDataOffset(slotIndex);
        long entryOffset = slotTableSize + dataRegionOffset;

        // Read key length and value length
        int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        int valueLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);

        // Create slice pointing to value bytes
        long valueOffset = entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen;
        return segment.asSlice(valueOffset, valueLen);
    }

    // ==================== Snapshot Read Methods ====================

    /**
     * Retrieves a value from the split snapshot for copy-on-write semantics.
     *
     * <p>This method is called when the segment is in SPLITTING state to ensure
     * reads see a consistent pre-split view of the data. The snapshot is an
     * immutable MemorySegment view created at the moment split began.</p>
     *
     * @param key The key to look up
     * @return MemorySegment slice pointing to the value in the snapshot,
     *         or null if not found or tombstoned
     */
    private MemorySegment getFromSnapshot(byte[] key) {
        long keyHash = computeHash(key);
        int slotIndex = findSlotInSnapshot(keyHash, key);

        if (slotIndex < 0) {
            return null; // Not found
        }

        // Check if tombstoned in snapshot
        if (isSlotTombstonedInSnapshot(slotIndex)) {
            return null;
        }

        // Get the value slice from snapshot
        return getValueSliceFromSnapshot(slotIndex);
    }

    /**
     * Finds a slot in the snapshot using Robin Hood hashing.
     *
     * <p>This method performs the same lookup as {@link #findSlot} but reads
     * from the snapshot's read-only MemorySegment instead of the live segment.</p>
     *
     * @param keyHash The 64-bit hash of the key
     * @param key The key bytes
     * @return Slot index if found, -1 if not found
     */
    private int findSlotInSnapshot(long keyHash, byte[] key) {
        MemorySegment snapshotSegment = splitSnapshot.segment;
        int idealSlot = (int) (keyHash & (slotCount - 1));

        for (int distance = 0; distance < MAX_PROBE_LENGTH; distance++) {
            int slotIndex = (idealSlot + distance) & (slotCount - 1);
            long slotOffset = (long) slotIndex * SLOT_SIZE;

            // Check if slot is occupied in snapshot
            byte flags = snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
            if ((flags & FLAG_OCCUPIED) == 0) {
                // Empty slot - key not found
                return -1;
            }

            // Check if we've gone past all entries with smaller distances
            byte slotDistance = snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET);
            if (slotDistance < distance) {
                // Would have found it by now due to Robin Hood ordering
                return -1;
            }

            // Check if hash matches
            long slotKeyHash = snapshotSegment.get(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET);
            if (slotKeyHash != keyHash) {
                continue;
            }

            // Hash matches - verify key bytes in snapshot
            if (keyMatchesInSnapshot(slotIndex, key)) {
                return slotIndex;
            }
        }

        return -1;
    }

    /**
     * Checks if a slot is tombstoned in the snapshot.
     *
     * @param slotIndex The slot index to check
     * @return true if the slot is tombstoned in the snapshot
     */
    private boolean isSlotTombstonedInSnapshot(int slotIndex) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        byte flags = splitSnapshot.segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
        return (flags & FLAG_TOMBSTONE) != 0;
    }

    /**
     * Checks if the key in the snapshot slot matches the given key.
     *
     * @param slotIndex The slot index to check
     * @param key The key to compare against
     * @return true if the keys match
     */
    private boolean keyMatchesInSnapshot(int slotIndex, byte[] key) {
        MemorySegment snapshotSegment = splitSnapshot.segment;
        long dataRegionOffset = readDataOffsetFromSnapshot(slotIndex);
        long entryOffset = slotTableSize + dataRegionOffset;

        // Read key length from snapshot
        int keyLen = snapshotSegment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        if (keyLen != key.length) {
            return false;
        }

        // Compare key bytes in snapshot
        for (int i = 0; i < keyLen; i++) {
            byte entryByte = snapshotSegment.get(BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET + i);
            if (entryByte != key[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Reads the 6-byte data offset from a slot in the snapshot.
     *
     * @param slotIndex The slot index
     * @return The data region offset
     */
    private long readDataOffsetFromSnapshot(int slotIndex) {
        MemorySegment snapshotSegment = splitSnapshot.segment;
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        long offset = 0;
        offset |= (snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET) & 0xFFL);
        offset |= (snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 1) & 0xFFL) << 8;
        offset |= (snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 2) & 0xFFL) << 16;
        offset |= (snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 3) & 0xFFL) << 24;
        offset |= (snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 4) & 0xFFL) << 32;
        offset |= (snapshotSegment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 5) & 0xFFL) << 40;
        return offset;
    }

    /**
     * Returns a zero-copy MemorySegment slice of the value from the snapshot.
     *
     * @param slotIndex The slot index
     * @return MemorySegment slice pointing to the value in the snapshot
     */
    private MemorySegment getValueSliceFromSnapshot(int slotIndex) {
        MemorySegment snapshotSegment = splitSnapshot.segment;
        long dataRegionOffset = readDataOffsetFromSnapshot(slotIndex);
        long entryOffset = slotTableSize + dataRegionOffset;

        // Read key length and value length from snapshot
        int keyLen = snapshotSegment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        int valueLen = snapshotSegment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);

        // Create slice pointing to value bytes in snapshot
        long valueOffset = entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen;
        return snapshotSegment.asSlice(valueOffset, valueLen);
    }

    /**
     * Clears all slots in the slot table.
     */
    private void clearSlotTable() {
        for (int i = 0; i < slotCount; i++) {
            long slotOffset = (long) i * SLOT_SIZE;
            segment.set(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET, 0L);
            segment.set(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET, (byte) 0);
            segment.set(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET, (byte) 0);
            // Data offset can remain uninitialized
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Computes a 64-bit hash for the key using xxHash64.
     *
     * <p>The hash is guaranteed to be positive (in range [0, Long.MAX_VALUE])
     * by masking off the sign bit using {@link SegmentConfig#normalizeKeyHash(long)}.</p>
     *
     * <h3>Domain Correctness</h3>
     * <p>This method ensures the returned hash is always in the range
     * [0, Long.MAX_VALUE] as required by the storage specification.
     * The xxHash64 algorithm produces uniformly distributed 64-bit values,
     * approximately half of which are negative when interpreted as signed longs.
     * By masking off the sign bit, we ensure all hashes are valid key_hash values.</p>
     *
     * @param key The key to hash
     * @return A normalized hash in range [0, Long.MAX_VALUE]
     */
    private long computeHash(byte[] key) {
        long rawHash = XxHash64.hash(key);
        long keyHash = SegmentConfig.normalizeKeyHash(rawHash);

        // Assert invariant: key_hash must be in range [0, Long.MAX_VALUE]
        assert SegmentConfig.isValidKeyHash(keyHash) :
            "key_hash invariant violated: " + keyHash + " is not in range [0, Long.MAX_VALUE]";

        return keyHash;
    }

    /**
     * Rounds an integer up to the nearest power of 2.
     */
    private static int roundToPowerOf2(int n) {
        if (n <= 0) {
            return MIN_SEGMENT_CAPACITY;
        }
        if (n >= MAX_SEGMENT_CAPACITY) {
            return MAX_SEGMENT_CAPACITY;
        }
        int power = Integer.highestOneBit(n);
        if (power < n) {
            power <<= 1;
        }
        return Math.max(power, MIN_SEGMENT_CAPACITY);
    }

    /**
     * Derives slot count from file size (for opening existing segments).
     *
     * <p>Domain constraint: The derived slot count MUST be a power of 2 in range [1024, 1048576].</p>
     *
     * @param fileSize The file size in bytes
     * @return The derived slot count
     * @throws IllegalArgumentException if the derived slot count is invalid
     */
    private static int deriveSlotCount(long fileSize) {
        // Assume 50% of file is slot table
        long estimatedSlots = (fileSize / 2) / SLOT_SIZE;
        int slotCount = roundToPowerOf2((int) Math.min(estimatedSlots, MAX_SEGMENT_CAPACITY));

        // Domain constraint: Capacity MUST be a power of 2 in range [1024, 1048576]
        SegmentConfig.validateCapacity(slotCount);

        return slotCount;
    }

    /**
     * Checks if there's enough space in the data region for the given entry size.
     *
     * @param entrySize The size of the entry to write
     * @return true if there's enough space, false otherwise
     */
    private boolean hasDataSpace(int entrySize) {
        // The actual write position is: slotTableSize + dataOffset
        // We need to check if this + entrySize fits in the segment
        long requiredOffset = slotTableSize + dataOffset + entrySize;
        long segmentSize = segment.byteSize();
        return requiredOffset <= segmentSize;
    }

    private void validateNotClosed() {
        if (closed) {
            throw new IllegalStateException("Segment is closed: " + path);
        }
    }

    /**
     * Validates that the segment is not in SPLITTING state.
     *
     * <p>Writes are rejected during splitting to ensure copy-on-write semantics.
     * Readers continue to see the pre-split snapshot.</p>
     *
     * @throws IllegalStateException if segment is in SPLITTING state
     */
    private void validateNotSplitting() {
        if (splitState == SplitState.SPLITTING) {
            throw new IllegalStateException(
                "Cannot modify segment during split: " + path);
        }
        // A retired parent no longer appears in the directory and its file has been replaced by
        // the left child's, so a mutation applied here lands in an inode nothing can reach and
        // is gone at process exit. A writer that resolved this segment before the split
        // published still holds a reference to it; refusing tells the caller to route again
        // rather than losing the write silently.
        if (splitState == SplitState.SPLIT_COMPLETE) {
            throw new SegmentRetiredException(
                "Segment was replaced by its split children: " + path);
        }
    }

    /**
     * Rejects a mutation the segment can no longer honour.
     *
     * <p>Both conditions are set by holders of this segment's write lock, so this has to be
     * called with that lock held to mean anything. Called before acquiring it, it answers a
     * question about a moment that has passed by the time the write actually happens.
     *
     * <p>Split state is checked first. A retired parent is closed shortly after it is replaced,
     * and "closed" says nothing about where the caller should go instead - while "replaced by
     * its split children" tells the routing layer to resolve again, which is the difference
     * between a failed write and one that lands in the right child.
     *
     * @throws SegmentRetiredException if the segment was replaced by its split children
     * @throws IllegalStateException if the segment is closed or splitting
     */
    private void requireWritable() {
        validateNotSplitting();
        validateNotClosed();
    }

    private void validateKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (key.length > Integer.MAX_VALUE - ENTRY_HEADER_SIZE - ENTRY_TRAILER_SIZE - 4) {
            throw new IllegalArgumentException("Key too large: " + key.length + " bytes");
        }
    }

    private void validateValue(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (value.length > Integer.MAX_VALUE - ENTRY_HEADER_SIZE - ENTRY_TRAILER_SIZE) {
            throw new IllegalArgumentException("Value too large: " + value.length + " bytes");
        }
    }

    /**
     * Validates that a distance value is in the valid range [0, MAX_DISTANCE].
     *
     * <p>Domain correctness constraint: distance must be in range [0, 127]
     * to fit in an unsigned byte.</p>
     *
     * @param distance The distance to validate
     * @throws IllegalStateException if distance is out of range [0, 127]
     */
    private void validateDistance(int distance) {
        if (distance < 0 || distance > MAX_DISTANCE) {
            throw new IllegalStateException(
                "Distance must be in range [0, " + MAX_DISTANCE + "], got: " + distance +
                ". This indicates the hash table is too full or has a pathological collision pattern.");
        }
    }

    @Override
    public String toString() {
        return String.format("Segment{path=%s, slots=%d, entries=%d, loadFactor=%.3f, closed=%s}",
            path, slotCount, entryCount, loadFactor(), closed);
    }

    // ==================== Entry Reading for Compaction ====================

    /**
     * Reads the key for a given entry index.
     *
     * <p>This method is used for compaction operations to retrieve entry
     * keys from the segment. It should only be called when reading entries
     * for maintenance tasks like merging write logs.</p>
     *
     * <p><strong>Thread Safety:</strong> This method uses the read lock,
     * allowing concurrent reads but exclusive writes.</p>
     *
     * @param index The live entry index (0-based, in [0, {@link #liveEntryCount()}))
     * @return Key bytes
     * @throws IndexOutOfBoundsException if index is negative or &gt;= liveEntryCount()
     * @throws IllegalStateException if segment is closed
     */
    public byte[] readEntryKey(int index) {
        lock.readLock().lock();
        try {
            // In a Robin Hood hash table, entries are NOT contiguous in slots 0..N.
            // We must scan all slots and find the Nth occupied (non-tombstoned) one.
            validateLiveIndex(index);
            int slotIndex = findNthOccupiedSlot(index);
            if (slotIndex < 0) {
                throw new IndexOutOfBoundsException(
                    "No live entry at index " + index + " in " + path);
            }
            int dataOffset = readSlotDataOffset(slotIndex);
            long entryOffset = slotTableSize + dataOffset;
            int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
            byte[] key = new byte[keyLen];
            MemorySegment.copy(segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET, key, 0, keyLen);
            return key;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads the value for a given entry index.
     *
     * <p>This method is used for compaction operations to retrieve entry
     * values from the segment. It should only be called when reading entries
     * for maintenance tasks like merging write logs.</p>
     *
     * <p><strong>Thread Safety:</strong> This method uses the read lock,
     * allowing concurrent reads but exclusive writes.</p>
     *
     * @param index The live entry index (0-based, in [0, {@link #liveEntryCount()}))
     * @return Value bytes
     * @throws IndexOutOfBoundsException if index is negative or &gt;= liveEntryCount()
     * @throws IllegalStateException if segment is closed
     */
    public byte[] readEntryValue(int index) {
        lock.readLock().lock();
        try {
            validateLiveIndex(index);
            int slotIndex = findNthOccupiedSlot(index);
            if (slotIndex < 0) {
                throw new IndexOutOfBoundsException(
                    "No live entry at index " + index + " in " + path);
            }
            int dataOffset = readSlotDataOffset(slotIndex);
            long entryOffset = slotTableSize + dataOffset;
            int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
            int valueLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);
            byte[] value = new byte[valueLen];
            MemorySegment.copy(segment, BYTE_LAYOUT,
                entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen, value, 0, valueLen);
            return value;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds the slot index of the Nth occupied (non-tombstoned) entry.
     * Must be called while holding the read lock.
     *
     * @param n The 0-based entry index
     * @return The slot index, or -1 if not found
     */
    private int findNthOccupiedSlot(int n) {
        int found = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            long slotOffset = (long) slot * SLOT_SIZE;
            byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
            if ((flags & FLAG_OCCUPIED) != 0 && (flags & FLAG_TOMBSTONE) == 0) {
                if (found == n) {
                    return slot;
                }
                found++;
            }
        }
        return -1;
    }

    /**
     * Reads the data offset for a given entry index.
     *
     * @param index The entry index (0-based)
     * @return The data offset for the entry
     * @throws IndexOutOfBoundsException if index is out of range
     * @throws IllegalStateException if segment is closed
     */
    private int readEntryDataOffset(int index) {
        // The slot data offset is already the absolute offset within the data region.
        // No accumulation of previous entry sizes is needed.
        return readSlotDataOffset(index);
    }

    /**
     * Reads the entry size for a given entry index.
     *
     * @param dataOffset The data offset of the entry
     * @return Entry size in bytes
     */
    private int readEntrySize(int dataOffset) {
        long keyOffset = slotTableSize + dataOffset;
        int keyLen = segment.get(INT_LAYOUT, keyOffset + ENTRY_KEY_LEN_OFFSET);
        int valueLen = segment.get(INT_LAYOUT, keyOffset + ENTRY_VALUE_LEN_OFFSET);
        return ENTRY_HEADER_SIZE + keyLen + valueLen + ENTRY_TRAILER_SIZE;
    }

    /**
     * Reads the data offset from a slot.
     *
     * @param slotIndex The slot index
     * @return Data offset
     */
    private int readSlotDataOffset(int slotIndex) {
        long dataOffset = readDataOffset(slotIndex);
        return (int) dataOffset;
    }

    /**
     * Reads the entry size from a slot.
     *
     * @param slotIndex The slot index
     * @return Entry size in bytes
     */
    private int readSlotEntrySize(int slotIndex) {
        int dataOffset = readSlotDataOffset(slotIndex);
        return readEntrySize(dataOffset);
    }

    /**
     * Validates an index into the live (non-tombstoned) entry space.
     *
     * @param index The index to validate
     * @throws IndexOutOfBoundsException if the index is outside [0, liveEntryCount)
     */
    private void validateLiveIndex(int index) {
        if (index < 0 || index >= liveEntryCount) {
            throw new IndexOutOfBoundsException(
                "Index " + index + " out of range [0, " + liveEntryCount + ")");
        }
    }

    // ==================== Write Log Integration ====================

    /**
     * Attaches a write-ahead log to this segment for crash recovery.
     *
     * <p>Once attached, all mutations (put, delete) are first appended to the log
     * before updating the in-memory hash map. The log is the source of truth
     * for crash recovery.</p>
     *
     * @param writeLog The write log to attach (must not be null)
     * @throws NullPointerException if writeLog is null
     * @throws IllegalStateException if segment is closed or already has a log
     */
    public void attachWriteLog(WriteLog writeLog) {
        Objects.requireNonNull(writeLog, "WriteLog cannot be null");
        validateNotClosed();

        lock.writeLock().lock();
        try {
            if (this.writeLog != null) {
                throw new IllegalStateException("Segment already has a write log attached");
            }
            this.writeLog = writeLog;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the write-ahead log attached to this segment.
     *
     * @return The write log, or null if none is attached
     */
    public WriteLog writeLog() {
        return writeLog;
    }

    /**
     * Attaches an LSH index to this segment for approximate nearest neighbor pre-filtering.
     *
     * <p>The LSH index is used during Stage 2 of the query pipeline to perform
     * coarse vector similarity filtering, reducing candidates to approximately
     * 10x-100x of the requested top_k before the more expensive HNSW search.</p>
     *
     * @param lshIndex The LSH index to attach (must not be null)
     * @throws NullPointerException if lshIndex is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachLshIndex(excalibur.filter.lsh.LshIndex lshIndex) {
        Objects.requireNonNull(lshIndex, "LshIndex cannot be null");
        validateNotClosed();

        lock.writeLock().lock();
        try {
            this.lshIndex = lshIndex;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the LSH index attached to this segment.
     *
     * <p>The LSH index is used for approximate nearest neighbor pre-filtering
     * during query execution. Returns null if no LSH index has been attached.</p>
     *
     * @return The LSH index, or null if none is attached
     */
    public excalibur.filter.lsh.LshIndex lshIndex() {
        return lshIndex;
    }

    /**
     * Returns the LSH index attached to this segment.
     *
     * <p>This is an alias for {@link #lshIndex()} for API consistency.</p>
     *
     * @return The LSH index, or null if none is attached
     */
    public excalibur.filter.lsh.LshIndex getLshIndex() {
        return lshIndex;
    }

    /**
     * Gives up ownership of the attached LSH index without closing it.
     *
     * <p>For handing a pre-filter to a replacement segment across a compaction. The pre-filter is
     * keyed by HNSW node id, and compaction carries the graph over unchanged, so the ids the
     * index holds still name the same vectors in the replacement. {@link #close()} closes
     * whatever is still attached, so a swap that attached the same index to the replacement and
     * then let the old segment close would leave the replacement holding a closed index - one
     * mapped file per hash table, released underneath a live query path.</p>
     *
     * @return the LSH index that was attached, or null if there was none
     */
    public excalibur.filter.lsh.LshIndex detachLshIndex() {
        excalibur.filter.lsh.LshIndex detached = this.lshIndex;
        this.lshIndex = null;
        return detached;
    }

    /**
     * Attaches a Count-Min Sketch for term frequency estimation.
     *
     * <p>The Count-Min Sketch is used by the query planner to estimate
     * term selectivity, which helps optimize query execution by:</p>
     * <ul>
     *   <li>Prioritizing segments with high selectivity for search terms</li>
     *   <li>Skipping segments with very low selectivity</li>
     *   <li>Ordering filter application based on selectivity</li>
     * </ul>
     *
     * @param sketch The Count-Min Sketch to attach (must not be null)
     * @throws NullPointerException if sketch is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachTermFrequencySketch(excalibur.filter.sketch.CountMinSketch sketch) {
        Objects.requireNonNull(sketch, "CountMinSketch cannot be null");
        validateNotClosed();

        lock.writeLock().lock();
        try {
            this.termFrequencySketch = sketch;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the Count-Min Sketch for term frequency estimation.
     *
     * <p>The sketch contains approximate term frequencies from documents
     * ingested into this segment. Used by the query planner for
     * selectivity estimation.</p>
     *
     * @return The Count-Min Sketch, or null if none is attached
     */
    public excalibur.filter.sketch.CountMinSketch termFrequencySketch() {
        return termFrequencySketch;
    }

    /**
     * Returns the Count-Min Sketch for term frequency estimation.
     *
     * <p>This is an alias for {@link #termFrequencySketch()} for API consistency.</p>
     *
     * @return The Count-Min Sketch, or null if none is attached
     */
    public excalibur.filter.sketch.CountMinSketch getTermFrequencySketch() {
        return termFrequencySketch;
    }

    // ==================== HNSW Graph Methods ====================

    /**
     * Attaches an HNSW graph for approximate nearest neighbor search.
     *
     * <p>The HNSW graph is used for efficient vector similarity search.
     * It is incrementally populated during ingestion as vectors are written
     * to the segment.</p>
     *
     * @param graph The HNSW graph to attach (must not be null)
     * @throws NullPointerException if graph is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachHnswGraph(io.excalibur.vector.hnsw.HnswGraph graph) {
        Objects.requireNonNull(graph, "HnswGraph cannot be null");
        validateNotClosed();

        lock.writeLock().lock();
        try {
            this.hnswGraph = graph;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gives up ownership of the attached HNSW graph without closing it.
     *
     * <p>For handing a graph to a replacement segment. {@link #close()} closes whatever is still
     * attached, so a compaction swap that attaches the same graph to the replacement and then lets
     * the old segment close would leave the replacement holding a closed graph — mappings released
     * underneath a live index. Detaching first makes the transfer explicit.
     *
     * @return the graph that was attached, or null if there was none
     */
    public io.excalibur.vector.hnsw.HnswGraph detachHnswGraph() {
        io.excalibur.vector.hnsw.HnswGraph detached = this.hnswGraph;
        this.hnswGraph = null;
        return detached;
    }

    /**
     * Returns the HNSW graph attached to this segment.
     *
     * <p>The HNSW graph enables efficient approximate nearest neighbor
     * search over vectors stored in this segment. Returns null if no
     * graph has been attached.</p>
     *
     * @return The HNSW graph, or null if none is attached
     */
    public io.excalibur.vector.hnsw.HnswGraph hnswGraph() {
        return hnswGraph;
    }

    /**
     * Returns the HNSW graph attached to this segment.
     *
     * <p>This is an alias for {@link #hnswGraph()} for API consistency.</p>
     *
     * @return The HNSW graph, or null if none is attached
     */
    public io.excalibur.vector.hnsw.HnswGraph getHnswGraph() {
        return hnswGraph;
    }

    /**
     * Attaches a tail offset tracker for the HNSW graph.
     *
     * <p>The tail offset tracker manages the {@code lastIntegratedOffset} which
     * tracks the write log offset up to which all entries have been linked into
     * the HNSW graph. This is critical for the live-indexing tail scan.</p>
     *
     * @param tracker The tail offset tracker to attach (must not be null)
     * @throws NullPointerException if tracker is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachTailOffsetTracker(io.excalibur.vector.hnsw.TailOffsetTracker tracker) {
        Objects.requireNonNull(tracker, "TailOffsetTracker cannot be null");
        validateNotClosed();

        lock.writeLock().lock();
        try {
            this.tailOffsetTracker = tracker;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the tail offset tracker for the HNSW graph.
     *
     * <p>The tracker manages the write log offset up to which entries have
     * been integrated into the HNSW graph. Used for tail scan during queries
     * to find un-integrated entries.</p>
     *
     * @return The tail offset tracker, or null if none is attached
     */
    public io.excalibur.vector.hnsw.TailOffsetTracker tailOffsetTracker() {
        return tailOffsetTracker;
    }

    /**
     * Returns the tail offset tracker for the HNSW graph.
     *
     * <p>This is an alias for {@link #tailOffsetTracker()} for API consistency.</p>
     *
     * @return The tail offset tracker, or null if none is attached
     */
    public io.excalibur.vector.hnsw.TailOffsetTracker getTailOffsetTracker() {
        return tailOffsetTracker;
    }

    /**
     * Returns the current write log head position.
     *
     * <p>This is the byte offset in the write log up to which entries
     * have been written. Used for tail scan to find entries that have
     * not yet been integrated into the HNSW graph.</p>
     *
     * <p>When the write log is attached, this returns the current write
     * offset. When no write log is attached, returns 0.</p>
     *
     * @return The write log head position in bytes
     */
    public long getWriteLogHead() {
        if (writeLog == null) {
            return 0;
        }
        return writeLog.writeOffset();
    }

    /**
     * Recovers segment state from the write log after a crash.
     *
     * <p>This method replays all entries from the write log and rebuilds
     * the in-memory hash map. It should be called during initialization
     * before any concurrent operations are allowed.</p>
     *
     * @throws IOException if reading the log fails
     * @throws IllegalStateException if no write log is attached or segment is closed
     */
    public void recoverFromLog() throws IOException {
        validateNotClosed();
        if (writeLog == null) {
            throw new IllegalStateException("No write log attached to segment");
        }

        lock.writeLock().lock();
        try {
            // Replay all entries from the log
            writeLog.recover(entry -> {
                byte[] key = entry.key();
                byte[] value = entry.value();

                if (entry.opcode() == LogEntry.OPCODE_PUT) {
                    // Direct insert without logging (we're recovering from log)
                    insertEntryForRecovery(entry.keyHash(), key, value);
                } else if (entry.opcode() == LogEntry.OPCODE_DELETE) {
                    // Direct delete without logging
                    deleteInternal(key);
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts an entry during recovery without appending to the write log.
     *
     * <p>This is used by {@link #recoverFromLog()} to replay entries from the
     * write log without creating new log entries.</p>
     *
     * @param keyHash The pre-computed key hash
     * @param key The key bytes
     * @param value The value bytes
     */
    private void insertEntryForRecovery(long keyHash, byte[] key, byte[] value) {
        // Replaying an update of an already-present key adds no entry, so the
        // load-factor guard must not be applied to it - otherwise a segment sitting
        // at its capacity boundary can never replay its own log.
        int existingSlot = findSlot(keyHash, key);
        if (existingSlot >= 0) {
            updateEntry(existingSlot, key, value);
            return;
        }

        if (isFull()) {
            throw new SegmentCapacityExceededException(
                SegmentCapacityExceededException.Reason.LOAD_FACTOR,
                "Recovery would exceed load factor threshold. Segment needs splitting.");
        }

        int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
        requireDataSpace(entrySize);

        insertEntry(keyHash, key, value);
    }

    /**
     * Deletes an entry without logging (internal operation).
     *
     * @param key The key to delete
     * @return true if deleted, false if not found
     */
    private boolean deleteInternal(byte[] key) {
        long keyHash = computeHash(key);
        int slotIndex = findSlot(keyHash, key);

        if (slotIndex < 0 || isSlotTombstoned(slotIndex)) {
            return false;
        }

        markAsTombstoned(slotIndex);
        return true;
    }

    // ==================== Split Operations ====================

    /**
     * Checks if this segment should be split.
     *
     * <p>A segment should split once it can take no further insert under its own threshold —
     * the one it was opened with, which is {@code excalibur.shard.segment-load-factor} in a
     * configured deployment and {@value #LOAD_FACTOR_THRESHOLD} otherwise. This is called by the
     * orchestration layer to determine when to initiate a background split.</p>

     * <p>Deliberately the same predicate the insert path refuses on, and not
     * {@code loadFactor() >= threshold}: see {@link #isFull()} for why those are not the same
     * question and what the difference cost.</p>
     *
     * @return true if the segment should be split
     */
    public boolean shouldSplit() {
        return isFull();
    }

    /**
     * Whether the next insert would be refused for load factor.
     *
     * <p>The one definition of full, because the two that existed disagreed by an entry and the
     * disagreement disabled automatic splitting entirely. Inserts were refused when
     * {@code entryCount + 1} reached {@code ceil(slotCount * threshold)}, so the largest
     * {@code entryCount} a segment could reach was one below that — and {@link #shouldSplit()}
     * asked whether {@code loadFactor() >= threshold}, which that entry count is always just
     * short of. A segment could therefore fill up, refuse every subsequent write, and never once
     * report that it wanted splitting. The {@link SplitWorker} scanned it every 100ms and always
     * saw false.
     *
     * <p>Defining both in terms of the same predicate makes the split trigger fire on exactly
     * the insert after the last accepted one, so the worker has the segment queued before a
     * client is ever told the segment is full.
     *
     * @return true when a further insert would exceed the threshold
     */
    /**
     * Returns the fraction of slots this segment may occupy before it is considered full.
     *
     * <p>Configured per engine and fixed for the life of a segment. A segment created to
     * replace or succeed this one - a split child, a compacted rebuild - has to be given the
     * same value, or the engine's configured threshold silently reverts to the default for
     * every segment created after the first split.</p>
     *
     * @return The load factor threshold, in (0, 1)
     */
    public double loadFactorThreshold() {
        return loadFactorThreshold;
    }

    private boolean isFull() {
        // Integer arithmetic avoids floating-point precision issues at the boundary.
        return entryCount + 1 >= (long) Math.ceil(slotCount * loadFactorThreshold);
    }

    /**
     * Checks if this segment is currently being split.
     *
     * @return true if a split is in progress
     */
    public boolean isSplitting() {
        return splitState == SplitState.SPLITTING;
    }

    /**
     * Checks if this segment's split is complete.
     *
     * @return true if split is complete
     */
    public boolean isSplitComplete() {
        return splitState == SplitState.SPLIT_COMPLETE;
    }

    /**
     * Begins a copy-on-write split operation.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Creates a snapshot of the current state for consistent reads</li>
     *   <li>Transitions the segment to SPLITTING state</li>
     *   <li>Returns the snapshot for the caller to perform the split</li>
     * </ol>
     *
     * <p>After calling this method:</p>
     * <ul>
     *   <li>Reads are served from the snapshot (consistent pre-split view)</li>
     *   <li>Writes are rejected until split completes</li>
     * </ul>
     *
     * @return A snapshot of the segment state for the split operation
     * @throws IllegalStateException if segment is closed or already splitting
     */
    public SegmentSplitSnapshot beginSplit() {
        validateNotClosed();

        lock.writeLock().lock();
        try {
            if (splitState != SplitState.NORMAL) {
                throw new IllegalStateException(
                    "Cannot begin split: segment is in state " + splitState);
            }

            // Create snapshot of current state
            splitSnapshot = new SegmentSnapshot(
                segment,
                entryCount,
                dataOffset,
                modificationEpoch
            );

            // Transition to splitting state
            splitState = SplitState.SPLITTING;

            return new SegmentSplitSnapshot(
                segment,
                slotCount,
                entryCount,
                dataOffset,
                slotTableSize,
                path
            );
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Completes a split operation by setting the child segments.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Stores references to the child segments</li>
     *   <li>Transitions the segment to SPLIT_COMPLETE state</li>
     *   <li>Clears the split snapshot</li>
     * </ol>
     *
     * <p>After this call, the segment is ready for retirement. New reads
     * and writes should be directed to the child segments.</p>
     *
     * @param leftChild  The left child segment (keys whose {@code splitBit} is 0)
     * @param rightChild The right child segment (keys whose {@code splitBit} is 1)
     * @param splitBit   The hash bit the entries were partitioned on. This is the bit
     *                   the router will newly consult after the routing table doubles,
     *                   i.e. {@code Integer.numberOfTrailingZeros(routingSegmentCount)}.
     * @throws IllegalStateException if segment is not in SPLITTING state
     * @throws IllegalArgumentException if splitBit is not in [0, 63]
     * @throws NullPointerException if any child is null
     */
    public void completeSplit(Segment leftChild, Segment rightChild, int splitBit) {
        Objects.requireNonNull(leftChild, "Left child cannot be null");
        Objects.requireNonNull(rightChild, "Right child cannot be null");
        if (splitBit < 0 || splitBit > 63) {
            throw new IllegalArgumentException("Split bit must be in [0, 63], got: " + splitBit);
        }

        lock.writeLock().lock();
        try {
            if (splitState != SplitState.SPLITTING) {
                throw new IllegalStateException(
                    "Cannot complete split: segment is in state " + splitState);
            }

            this.leftChild = leftChild;
            this.rightChild = rightChild;
            this.splitBit = splitBit;
            this.splitSnapshot = null;
            this.splitState = SplitState.SPLIT_COMPLETE;

            modificationEpoch++; // Invalidate any cached slices
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the hash bit this segment's entries were partitioned on.
     *
     * <p>Every consumer that has to pick a child for a key must read this value rather
     * than deriving a bit of its own, so the partitioning and the routing can never
     * disagree.</p>
     *
     * @return The split bit, or -1 if this segment has not completed a split
     */
    public int splitBit() {
        return splitBit;
    }

    /**
     * Returns the left child segment after a split.
     *
     * @return The left child, or null if split is not complete
     */
    public Segment leftChild() {
        return leftChild;
    }

    /**
     * Returns the right child segment after a split.
     *
     * @return The right child, or null if split is not complete
     */
    public Segment rightChild() {
        return rightChild;
    }

    /**
     * Aborts an in-progress split operation.
     *
     * <p>This restores the segment to NORMAL state, allowing writes to resume.
     * The snapshot is discarded.</p>
     *
     * @throws IllegalStateException if segment is not in SPLITTING state
     */
    public void abortSplit() {
        lock.writeLock().lock();
        try {
            if (splitState != SplitState.SPLITTING) {
                throw new IllegalStateException(
                    "Cannot abort split: segment is in state " + splitState);
            }

            splitSnapshot = null;
            splitState = SplitState.NORMAL;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Computes which child segment a key belongs to based on a hash bit.
     *
     * <p>During a split, entries are partitioned based on a bit of their hash.
     * This method determines which child should receive a given key.</p>
     *
     * @param keyHash   The key hash
     * @param splitBit  The bit position to use for partitioning (0 = LSB)
     * @return true for right child, false for left child
     */
    public static boolean shouldGoToRightChild(long keyHash, int splitBit) {
        return ((keyHash >>> splitBit) & 1L) == 1L;
    }

    // ==================== Compaction Support ====================

    /**
     * Returns an iterator over all live (non-tombstoned) entries in this segment.
     *
     * <p>This iterator is used by the compaction worker to traverse entries
     * and merge them into a compacted segment. The iterator provides a
     * consistent snapshot view of the segment at the time of creation.</p>
     *
     * <p><strong>Thread Safety:</strong> The iterator holds a read lock
     * during iteration. Callers should iterate promptly and close the
     * iterator when done.</p>
     *
     * @return An iterator over live entries
     * @throws IllegalStateException if segment is closed
     */
    public EntryIterator liveEntries() {
        validateNotClosed();
        return new EntryIterator(this, false);
    }

    /**
     * Returns an iterator over all entries including tombstoned ones.
     *
     * <p>This is used during compaction to identify and remove tombstoned entries.</p>
     *
     * @return An iterator over all entries
     * @throws IllegalStateException if segment is closed
     */
    public EntryIterator allEntries() {
        validateNotClosed();
        return new EntryIterator(this, true);
    }

    /**
     * Returns the number of tombstoned entries in this segment.
     *
     * <p>This is used by the compaction scheduler to determine which
     * segments would benefit most from compaction.</p>
     *
     * @return The number of tombstoned entries
     */
    public int tombstoneCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (int i = 0; i < slotCount; i++) {
                if (isSlotOccupied(i) && isSlotTombstoned(i)) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if a slot is occupied.
     */
    private boolean isSlotOccupied(int slotIndex) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
        return (flags & FLAG_OCCUPIED) != 0;
    }

    /**
     * Estimates the space that would be reclaimed by compaction.
     *
     * <p>This calculates the bytes occupied by tombstoned entries that
     * would be freed during compaction.</p>
     *
     * @return Estimated reclaimable bytes
     */
    public long estimatedReclaimableBytes() {
        lock.readLock().lock();
        try {
            long bytes = 0;
            for (int i = 0; i < slotCount; i++) {
                if (isSlotOccupied(i) && isSlotTombstoned(i)) {
                    bytes += readSlotEntrySize(i);
                }
            }
            return bytes;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Inner Classes ====================

    /**
     * Split state enumeration for copy-on-write splitting.
     *
     * <p>A segment transitions through these states during a split:</p>
     * <ol>
     *   <li>NORMAL → SPLITTING: When split() is called</li>
     *   <li>SPLITTING → SPLIT_COMPLETE: When split finishes</li>
     * </ol>
     */
    private enum SplitState {
        /** Normal operation, no split in progress. */
        NORMAL,
        /** Split in progress, reads served from snapshot. */
        SPLITTING,
        /** Split complete, segment ready for retirement. */
        SPLIT_COMPLETE
    }

    /**
     * Immutable snapshot of segment state for consistent reads during splitting.
     *
     * <p>When a segment begins splitting, a snapshot is created that captures
     * the current state. All reads during the split see this consistent snapshot,
     * while writes are redirected to the new child segments.</p>
     */
    private static final class SegmentSnapshot {
        private final MemorySegment segment;
        private final int entryCount;
        private final long dataOffset;
        private final long modificationEpoch;

        SegmentSnapshot(MemorySegment segment, int entryCount, long dataOffset, long modificationEpoch) {
            this.segment = segment.asReadOnly(); // Immutable view
            this.entryCount = entryCount;
            this.dataOffset = dataOffset;
            this.modificationEpoch = modificationEpoch;
        }
    }

    /**
     * Slot metadata captured before a Robin Hood displacement overwrites it.
     *
     * <p>The displaced entry keeps its data region record, so restoring the slot
     * restores the entry completely.</p>
     *
     * @param slotIndex        The slot that was overwritten
     * @param keyHash          The displaced entry's key hash
     * @param distance         The displaced entry's distance from its ideal slot
     * @param flags            The displaced entry's slot flags (including tombstone)
     * @param dataRegionOffset Offset of the displaced entry's record in the data region
     */
    private record SlotSnapshot(int slotIndex, long keyHash, int distance,
                                byte flags, long dataRegionOffset) {}

    /**
     * Snapshot of segment state for copy-on-write splitting.
     *
     * <p>This class provides access to the segment's data for the split
     * operation without holding locks. It contains all the information
     * needed to partition entries into child segments.</p>
     *
     * <h2>Thread Safety</h2>
     * <p>This class is immutable and thread-safe for reads. The underlying
     * MemorySegment is a read-only view of the segment data.</p>
     */
    public static final class SegmentSplitSnapshot {

        private final MemorySegment segment;
        private final int slotCount;
        private final int entryCount;
        private final long dataOffset;
        private final long slotTableSize;
        private final Path path;

        /**
         * Creates a split snapshot.
         *
         * @param segment       The memory segment (will be made read-only)
         * @param slotCount     Number of slots
         * @param entryCount    Number of entries
         * @param dataOffset    Current data region offset
         * @param slotTableSize Size of slot table region
         * @param path          Path to the segment file
         */
        SegmentSplitSnapshot(MemorySegment segment, int slotCount, int entryCount,
                             long dataOffset, long slotTableSize, Path path) {
            this.segment = segment.asReadOnly();
            this.slotCount = slotCount;
            this.entryCount = entryCount;
            this.dataOffset = dataOffset;
            this.slotTableSize = slotTableSize;
            this.path = path;
        }

        /**
         * Returns the number of slots in the snapshot.
         */
        public int slotCount() {
            return slotCount;
        }

        /**
         * Returns the number of entries in the snapshot.
         */
        public int entryCount() {
            return entryCount;
        }

        /**
         * Returns the path to the segment file.
         */
        public Path path() {
            return path;
        }

        /**
         * Reads an entry from the snapshot.
         *
         * @param index The entry index (0-based, not slot index)
         * @return The entry, or null if index is out of range or entry is tombstoned
         */
        public SegmentEntry readEntry(int index) {
            if (index < 0 || index >= slotCount) {
                return null;
            }

            long slotOffset = (long) index * SLOT_SIZE;
            byte flags = segment.get(ValueLayout.JAVA_BYTE, slotOffset + SLOT_FLAGS_OFFSET);

            // Check if slot is occupied
            if ((flags & FLAG_OCCUPIED) == 0) {
                return null;
            }

            // Read data offset (6 bytes, unsigned)
            long dataRegionOffset = readDataOffset(slotOffset);
            long entryOffset = slotTableSize + dataRegionOffset;

            // Read entry header
            long keyHash = segment.get(LONG_LAYOUT, entryOffset + ENTRY_KEY_HASH_OFFSET);
            int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
            int valueLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);
            int distance = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_DISTANCE_OFFSET) & 0xFF;

            // Read key bytes
            byte[] key = new byte[keyLen];
            MemorySegment.copy(segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET, key, 0, keyLen);

            // Read value bytes
            byte[] value = new byte[valueLen];
            MemorySegment.copy(segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen, value, 0, valueLen);

            // Check tombstone
            byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen + valueLen);
            boolean tombstoned = (tombstone == 1);

            return new SegmentEntry(keyHash, key, value, distance, tombstoned);
        }

        /**
         * Reads the data offset from a slot offset.
         */
        private long readDataOffset(long slotOffset) {
            long offset = 0;
            offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET) & 0xFFL);
            offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 1) & 0xFFL) << 8;
            offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 2) & 0xFFL) << 16;
            offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 3) & 0xFFL) << 24;
            offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 4) & 0xFFL) << 32;
            offset |= (segment.get(BYTE_LAYOUT, slotOffset + SLOT_DATA_OFFSET_OFFSET + 5) & 0xFFL) << 40;
            return offset;
        }

        /**
         * Iterates over all entries in the snapshot.
         *
         * @param includeTombstones If true, include tombstoned entries
         * @return List of entries
         */
        public List<SegmentEntry> entries(boolean includeTombstones) {
            List<SegmentEntry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < slotCount; i++) {
                SegmentEntry entry = readEntry(i);
                if (entry != null && (includeTombstones || !entry.isTombstoned())) {
                    entries.add(entry);
                }
            }
            return entries;
        }
    }

    /**
     * Iterator over segment entries for compaction.
     *
     * <p>This iterator provides a way to traverse all entries in a segment
     * for compaction purposes. It holds a read lock during iteration to
     * ensure consistency.</p>
     *
     * <h2>Thread Safety</h2>
     * <p>The iterator holds a read lock and is not thread-safe. Only one
     * thread should use an iterator at a time. The iterator must be closed
     * to release the lock.</p>
     *
     * <h2>Domain Correctness</h2>
     * <ul>
     *   <li>Entries are returned in slot order (not sorted by key)</li>
     *   <li>Each entry is returned exactly once</li>
     *   <li>Tombstoned entries may or may not be included based on configuration</li>
     * </ul>
     */
    public static final class EntryIterator implements Iterator<SegmentEntry>, AutoCloseable {

        private final Segment segment;
        private final boolean includeTombstones;
        private final Lock readLock;
        private int currentSlot;
        private SegmentEntry nextEntry;
        private boolean closed;

        /**
         * Creates a new entry iterator.
         *
         * @param segment           The segment to iterate
         * @param includeTombstones Whether to include tombstoned entries
         */
        EntryIterator(Segment segment, boolean includeTombstones) {
            this.segment = segment;
            this.includeTombstones = includeTombstones;
            this.readLock = segment.readLock();
            this.readLock.lock();
            this.currentSlot = 0;
            this.nextEntry = null;
            this.closed = false;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            if (nextEntry != null) {
                return true;
            }
            nextEntry = findNext();
            return nextEntry != null;
        }

        @Override
        public SegmentEntry next() {
            if (closed) {
                throw new IllegalStateException("Iterator is closed");
            }
            if (nextEntry == null) {
                nextEntry = findNext();
            }
            if (nextEntry == null) {
                throw new NoSuchElementException("No more entries");
            }
            SegmentEntry result = nextEntry;
            nextEntry = null;
            return result;
        }

        /**
         * Finds the next entry in slot order.
         */
        private SegmentEntry findNext() {
            while (currentSlot < segment.slotCount) {
                int slotIndex = currentSlot++;

                // Check if slot is occupied
                long slotOffset = (long) slotIndex * SLOT_SIZE;
                byte flags = segment.segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);

                if ((flags & FLAG_OCCUPIED) == 0) {
                    continue; // Empty slot
                }

                // Check tombstone
                boolean isTombstoned = (flags & FLAG_TOMBSTONE) != 0;
                if (!includeTombstones && isTombstoned) {
                    continue; // Skip tombstoned entries
                }

                // Read the entry
                return readEntryAtSlot(slotIndex, isTombstoned);
            }
            return null;
        }

        /**
         * Reads an entry at a specific slot.
         */
        private SegmentEntry readEntryAtSlot(int slotIndex, boolean isTombstoned) {
            long slotOffset = (long) slotIndex * SLOT_SIZE;
            long dataRegionOffset = segment.readDataOffset(slotIndex);
            long entryOffset = segment.slotTableSize + dataRegionOffset;

            // Read entry header
            long keyHash = segment.segment.get(LONG_LAYOUT, entryOffset + ENTRY_KEY_HASH_OFFSET);
            int keyLen = segment.segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
            int valueLen = segment.segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);
            int distance = segment.segment.get(BYTE_LAYOUT, entryOffset + ENTRY_DISTANCE_OFFSET) & 0xFF;

            // Read key bytes
            byte[] key = new byte[keyLen];
            MemorySegment.copy(segment.segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET, key, 0, keyLen);

            // Read value bytes
            byte[] value = new byte[valueLen];
            MemorySegment.copy(segment.segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen, value, 0, valueLen);

            return new SegmentEntry(keyHash, key, value, distance, isTombstoned);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                readLock.unlock();
            }
        }
    }
}
