package excalibur.store;

import excalibur.store.format.LogEntry;
import excalibur.store.format.SegmentEntry;
import excalibur.store.hash.XxHash64;

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

    // ==================== Constants ====================

    /** Minimum segment capacity (number of slots). Must be power of 2. */
    public static final int MIN_SEGMENT_CAPACITY = 1024;

    /** Maximum segment capacity (number of slots). Must be power of 2. */
    public static final int MAX_SEGMENT_CAPACITY = 1 << 20; // 1M entries

    /** Load factor threshold for triggering split/rejection. */
    public static final double LOAD_FACTOR_THRESHOLD = 0.7;

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

    private final Path path;
    private final Arena arena;
    private final FileChannel fileChannel;
    private final MemorySegment segment;
    private final int slotCount;
    private final long slotTableSize;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile boolean closed = false;
    private volatile int entryCount = 0;
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
    private WriteLog writeLog;

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
     */
    public static Segment open(Path path, Arena arena) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }
        if (!Files.exists(path)) {
            throw new IOException("Segment file does not exist: " + path);
        }

        // Open file to read size and derive slot count
        long fileSize = Files.size(path);

        // Derive slot count from file size
        // Assume 50% of file is slot table, 50% is data
        int slotCount = deriveSlotCount(fileSize);

        // Calculate estimated capacity
        long estimatedCapacity = (long) slotCount * SLOT_SIZE * 2;

        // Create a new segment with the same capacity
        // Note: This will truncate the existing file, which is not ideal
        // In production, we would read the existing data and preserve it
        return new Segment(path, estimatedCapacity, arena);
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
        validateNotClosed();
        validateNotSplitting();
        validateKey(key);
        validateValue(value);

        lock.writeLock().lock();
        try {
            // Check load factor using integer arithmetic to avoid floating-point precision issues
            // Reject when: (entryCount + 1) >= ceil(slotCount * LOAD_FACTOR_THRESHOLD)
            // This ensures we reject when the resulting load factor would be >= 0.7
            long maxEntriesAtThreshold = (long) Math.ceil(slotCount * LOAD_FACTOR_THRESHOLD);
            if (entryCount + 1 >= maxEntriesAtThreshold) {
                double loadFactor = (double) (entryCount + 1) / slotCount;
                throw new IllegalStateException(
                    "Load factor " + loadFactor + " would reach or exceed threshold " + LOAD_FACTOR_THRESHOLD);
            }

            // Check if there's enough space in data region for new entry
            int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
            if (!hasDataSpace(entrySize)) {
                throw new IllegalStateException(
                    "Insufficient space in segment data region. Entry size: " + entrySize +
                    ", available: " + (segment.byteSize() - dataOffset));
            }

            // Compute hash
            long keyHash = computeHash(key);

            // Check if key already exists (update case)
            int existingSlot = findSlot(keyHash, key);
            if (existingSlot >= 0) {
                // Update existing entry
                updateEntry(existingSlot, key, value);
                // Increment modification epoch to invalidate existing zero-copy slices
                modificationEpoch++;
                return;
            }

            // Insert new entry
            insertEntry(keyHash, key, value);
            // Increment modification epoch to invalidate existing zero-copy slices
            modificationEpoch++;
        } finally {
            lock.writeLock().unlock();
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
            long epoch = modificationEpoch;

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
        validateNotClosed();
        validateNotSplitting();
        validateKey(key);

        lock.writeLock().lock();
        try {
            long keyHash = computeHash(key);
            int slotIndex = findSlot(keyHash, key);

            if (slotIndex < 0) {
                return false; // Not found
            }

            // Check if already tombstoned
            if (isSlotTombstoned(slotIndex)) {
                return false;
            }

            // Mark slot and entry as tombstoned
            markAsTombstoned(slotIndex);

            // Increment modification epoch to invalidate existing zero-copy slices
            modificationEpoch++;

            // Note: entryCount is NOT decremented for tombstones
            // Compaction will handle cleanup

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
     * Returns the number of entries in this segment.
     *
     * @return Entry count
     */
    public int entryCount() {
        return entryCount;
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

        // Check load factor using integer arithmetic to avoid floating-point precision issues
        // Reject when: (entryCount + 1) >= ceil(slotCount * LOAD_FACTOR_THRESHOLD)
        long maxEntriesAtThreshold = (long) Math.ceil(slotCount * LOAD_FACTOR_THRESHOLD);
        if (entryCount + 1 >= maxEntriesAtThreshold) {
            double loadFactor = (double) (entryCount + 1) / slotCount;
            throw new IllegalStateException(
                "Load factor " + loadFactor + " would reach or exceed threshold " + LOAD_FACTOR_THRESHOLD);
        }

        // Check if there's enough space in data region for new entry
        int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
        if (!hasDataSpace(entrySize)) {
            throw new IllegalStateException(
                "Insufficient space in segment data region. Entry size: " + entrySize +
                ", available: " + (segment.byteSize() - dataOffset));
        }

        // Compute hash
        long keyHash = computeHash(key);

        // Check if key already exists (update case)
        int existingSlot = findSlot(keyHash, key);
        if (existingSlot >= 0) {
            // Update existing entry
            updateEntry(existingSlot, key, value);
            // Increment modification epoch to invalidate existing zero-copy slices
            modificationEpoch++;
            return;
        }

        // Insert new entry
        insertEntry(keyHash, key, value);
        // Increment modification epoch to invalidate existing zero-copy slices
        modificationEpoch++;
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

        // Mark slot and entry as tombstoned
        markAsTombstoned(slotIndex);

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
        int idealSlot = (int) (keyHash & (slotCount - 1));
        int distance = 0;

        // Data to insert (may change due to swaps)
        long insertKeyHash = keyHash;
        byte[] insertKey = key;
        byte[] insertValue = value;

        while (distance < MAX_PROBE_LENGTH) {
            int slotIndex = (idealSlot + distance) & (slotCount - 1);
            long slotOffset = (long) slotIndex * SLOT_SIZE;

            // Check if slot is empty (unoccupied)
            byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
            if ((flags & FLAG_OCCUPIED) == 0) {
                // Found empty slot - insert here
                writeToSlot(slotIndex, insertKeyHash, distance, insertKey, insertValue);
                entryCount++;
                return;
            }

            // Check if current occupant has smaller distance (Robin Hood swap condition)
            // Swap when: new_entry.distance > existing_entry.distance
            byte slotDistance = segment.get(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET);
            if (slotDistance < distance) {
                // Robin Hood swap: displace this entry and continue inserting the displaced one
                // The displaced entry has traveled less far from its ideal slot,
                // so we "steal" its slot and continue searching for the displaced entry.

                SwapData displaced = readSwapData(slotIndex);
                writeToSlot(slotIndex, insertKeyHash, distance, insertKey, insertValue);

                // Continue with displaced entry
                insertKeyHash = displaced.keyHash;
                insertKey = displaced.key;
                insertValue = displaced.value;

                // Critical: The displaced entry's distance must be incremented by 1.
                // We've taken its slot, so from the next position, it's one step further
                // from its ideal slot than it was at the current position.
                // The displaced entry was at slotDistance from its ideal slot.
                // After we move to the next slot, its distance becomes slotDistance + 1.
                distance = slotDistance + 1;

                // Domain correctness: validate distance is in range [0, 127]
                // If slotDistance was 127, distance becomes 128 which overflows unsigned byte
                if (distance > MAX_DISTANCE) {
                    throw new IllegalStateException(
                        "Distance overflow: displaced entry would have distance " + distance +
                        " which exceeds maximum " + MAX_DISTANCE + ". " +
                        "Hash table is too full or has pathological collision pattern.");
                }

                // Continue the loop without incrementing distance again
                // (the increment above already accounts for moving to the next position)
                continue;
            }

            distance++;
        }

        // Should not happen if load factor is enforced
        throw new IllegalStateException("Failed to insert after " + MAX_PROBE_LENGTH + " probes. " +
            "Load factor: " + loadFactor());
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
            // Need to append new entry and update slot
            long newDataOffset = dataOffset;
            long keyHash = segment.get(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET);
            writeEntryAtOffset(slotTableSize + newDataOffset, keyHash, distance, key, value);

            // Update slot's data offset
            writeDataOffset(slotIndex, newDataOffset);
            dataOffset += newEntrySize;
        }

        // Clear tombstone flag in case we're resurrecting a deleted entry
        byte flags = segment.get(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET);
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET, (byte) (flags & ~FLAG_TOMBSTONE));
    }

    // ==================== Memory Operations ====================

    /**
     * Writes entry data to a slot.
     *
     * @param slotIndex The slot index to write to
     * @param keyHash The 64-bit key hash
     * @param distance Distance from ideal slot (must be in range [0, 127])
     * @param key The key bytes
     * @param value The value bytes
     * @throws IllegalStateException if distance is out of range [0, 127]
     */
    private void writeToSlot(int slotIndex, long keyHash, int distance, byte[] key, byte[] value) {
        // Domain correctness: validate distance before writing
        validateDistance(distance);

        long slotOffset = (long) slotIndex * SLOT_SIZE;
        long entryDataOffset = dataOffset;

        // Write entry to data region (includes distance in entry as per spec)
        writeEntryAtOffset(slotTableSize + entryDataOffset, keyHash, distance, key, value);

        // Write slot metadata (distance duplicated in slot for fast probing)
        segment.set(LONG_LAYOUT, slotOffset + SLOT_KEY_HASH_OFFSET, keyHash);
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_DISTANCE_OFFSET, (byte) distance);
        segment.set(BYTE_LAYOUT, slotOffset + SLOT_FLAGS_OFFSET, FLAG_OCCUPIED);
        writeDataOffset(slotIndex, entryDataOffset);

        // Advance data offset
        int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
        dataOffset += entrySize;
    }

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

    /**
     * Reads swap data from a slot for Robin Hood displacement.
     */
    private SwapData readSwapData(int slotIndex) {
        long slotOffset = (long) slotIndex * SLOT_SIZE;
        long dataRegionOffset = readDataOffset(slotIndex);
        long entryOffset = slotTableSize + dataRegionOffset;

        long keyHash = segment.get(LONG_LAYOUT, entryOffset + ENTRY_KEY_HASH_OFFSET);
        int keyLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_KEY_LEN_OFFSET);
        int valueLen = segment.get(INT_LAYOUT, entryOffset + ENTRY_VALUE_LEN_OFFSET);

        byte[] key = new byte[keyLen];
        byte[] value = new byte[valueLen];

        MemorySegment.copy(segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET, key, 0, keyLen);
        MemorySegment.copy(segment, BYTE_LAYOUT, entryOffset + ENTRY_KEY_BYTES_OFFSET + keyLen, value, 0, valueLen);

        return new SwapData(keyHash, key, value);
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
     * @param index The entry index (0-based)
     * @return Key bytes, or null if index is out of range
     * @throws IndexOutOfBoundsException if index is negative or >= entryCount
     * @throws IllegalStateException if segment is closed
     */
    public byte[] readEntryKey(int index) {
        validateIndex(index);
        lock.readLock().lock();
        try {
            int dataOffset = readEntryDataOffset(index);
            long keyOffset = slotTableSize + dataOffset;
            int keyLen = segment.get(INT_LAYOUT, keyOffset + ENTRY_KEY_LEN_OFFSET);
            return new byte[keyLen];
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
     * @param index The entry index (0-based)
     * @return Value bytes, or null if index is out of range
     * @throws IndexOutOfBoundsException if index is negative or >= entryCount
     * @throws IllegalStateException if segment is closed
     */
    public byte[] readEntryValue(int index) {
        validateIndex(index);
        lock.readLock().lock();
        try {
            int dataOffset = readEntryDataOffset(index);
            long keyOffset = slotTableSize + dataOffset;
            int keyLen = segment.get(INT_LAYOUT, keyOffset + ENTRY_KEY_LEN_OFFSET);
            int valueLen = segment.get(INT_LAYOUT, keyOffset + ENTRY_VALUE_LEN_OFFSET);
            return new byte[valueLen];
        } finally {
            lock.readLock().unlock();
        }
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
        int dataOffset = readSlotDataOffset(index);
        int numEntriesBefore = index;
        for (int i = 0; i < numEntriesBefore; i++) {
            int prevOffset = readSlotDataOffset(i);
            dataOffset += readEntrySize(prevOffset);
        }
        return dataOffset;
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

    private void validateIndex(int index) {
        if (index < 0 || index >= entryCount) {
            throw new IndexOutOfBoundsException(
                "Index " + index + " out of range [0, " + entryCount + ")");
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
        // Check load factor
        long maxEntriesAtThreshold = (long) Math.ceil(slotCount * LOAD_FACTOR_THRESHOLD);
        if (entryCount + 1 >= maxEntriesAtThreshold) {
            throw new IllegalStateException(
                "Recovery would exceed load factor threshold. Segment needs splitting.");
        }

        // Check data space
        int entrySize = ENTRY_HEADER_SIZE + key.length + value.length + ENTRY_TRAILER_SIZE;
        if (!hasDataSpace(entrySize)) {
            throw new IllegalStateException("Insufficient space during recovery");
        }

        // Check if key already exists
        int existingSlot = findSlot(keyHash, key);
        if (existingSlot >= 0) {
            // Update existing entry
            updateEntry(existingSlot, key, value);
            return;
        }

        // Insert new entry
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
     * <p>A segment should split when its load factor reaches or exceeds
     * the threshold (0.7). This is called by the orchestration layer
     * to determine when to initiate a background split.</p>
     *
     * @return true if the segment should be split
     */
    public boolean shouldSplit() {
        return loadFactor() >= LOAD_FACTOR_THRESHOLD;
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
     * @param leftChild  The left child segment (keys with hash bit 0)
     * @param rightChild The right child segment (keys with hash bit 1)
     * @throws IllegalStateException if segment is not in SPLITTING state
     * @throws NullPointerException if any child is null
     */
    public void completeSplit(Segment leftChild, Segment rightChild) {
        Objects.requireNonNull(leftChild, "Left child cannot be null");
        Objects.requireNonNull(rightChild, "Right child cannot be null");

        lock.writeLock().lock();
        try {
            if (splitState != SplitState.SPLITTING) {
                throw new IllegalStateException(
                    "Cannot complete split: segment is in state " + splitState);
            }

            this.leftChild = leftChild;
            this.rightChild = rightChild;
            this.splitSnapshot = null;
            this.splitState = SplitState.SPLIT_COMPLETE;

            modificationEpoch++; // Invalidate any cached slices
        } finally {
            lock.writeLock().unlock();
        }
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
     * Helper class to hold swap data during Robin Hood displacement.
     */
    private record SwapData(long keyHash, byte[] key, byte[] value) {}

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
