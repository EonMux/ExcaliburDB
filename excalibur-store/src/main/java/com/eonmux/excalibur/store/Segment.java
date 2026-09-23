package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentEntry;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SegmentLayout;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One disk-backed key-value segment: a memory-mapped Robin Hood hash table.
 *
 * <p>A segment owns a file, the mapping over it, the lock that guards it, the counters that
 * describe it, and the optional indexes attached to it. It is the unit the engine routes to,
 * splits, compacts and retires.</p>
 *
 * <h2>How this class is put together</h2>
 * <p>This class is the published surface and the shared state; the work is done by
 * package-private collaborators, each of which documents the reasoning behind its own part:</p>
 * <ul>
 *   <li>{@link SegmentSlots} - the slot table, Robin Hood probing, and the slot checksum</li>
 *   <li>{@link SegmentRecords} - the data region, the entry records, and their checksum</li>
 *   <li>{@link SegmentLookups} - every way of reading a key back out</li>
 *   <li>{@link SegmentMutations} - every way of changing it, and the write-ahead log that has
 *       to agree with the result</li>
 *   <li>{@link SegmentSnapshotReads} - the read path used while a split is in flight</li>
 *   <li>{@link SegmentSplitControl} - the split state machine, and when a segment is full</li>
 *   <li>{@link SegmentFile} - creating and opening the file, detecting its format, recovering
 *       what it holds, and letting it go</li>
 * </ul>
 *
 * <p>They are collaborators rather than utilities because they all work on the same mapping
 * under the same lock. Each holds a reference to its segment and reads and writes its fields
 * directly, which is why those fields are package-private: routing shared, concurrently
 * mutated state through accessors would add nothing but a second chance to get a memory
 * operation wrong.</p>
 *
 * <h2>Layout, integrity and file versions</h2>
 * <p>The file is a fixed-size slot table followed by a variable-length data region.
 * {@link SegmentLayout} defines every offset in both; {@link SegmentSlots} and
 * {@link SegmentRecords} explain what each region holds and what is covered by a checksum;
 * {@link SegmentFile} explains how a file written by an earlier build is recognised and then
 * read and written at its own version rather than upgraded in place. A record or a slot that
 * fails its checksum raises {@link CorruptSegmentEntryException} rather than reading as
 * absent, because a corrupt entry and a missing one are different facts and callers act on
 * them differently. {@link #verifyIntegrity()} surveys the whole file and reports every fault
 * at once, which is what damage-repair tooling is built on.</p>
 *
 * <h2>Thread safety</h2>
 * <p><strong>This class is fully thread-safe.</strong> Each segment has its own
 * {@link ReentrantReadWriteLock}, so work on different segments never contends:
 * {@link #get(byte[])} takes the read lock, and {@link #put(byte[], byte[])},
 * {@link #delete(byte[])} and {@link #close()} take the write lock. {@link #readLock()} and
 * {@link #writeLock()} expose that lock so a caller can hold it across a run of operations
 * and use the {@code WithoutLock} variants inside it.</p>
 *
 * <h2>Zero-copy reads</h2>
 * <p>{@link #get(byte[])} returns a slice of the mapping itself, valid until this segment is
 * modified or closed or its arena is closed. {@link #getWithEpoch(byte[])} stamps the slice
 * with {@link #currentEpoch()} so that {@link ZeroCopySlice#isValid()} can tell a caller
 * whether any of that has happened.</p>
 *
 * <h2>Domain correctness</h2>
 * <ul>
 *   <li>key_hash is in [0, Long.MAX_VALUE]; key_len and value_len in [0, Integer.MAX_VALUE - 17]</li>
 *   <li>distance is in [0, {@value #MAX_DISTANCE}]; tombstone is exactly 0 (live) or 1 (deleted)</li>
 *   <li>slot count is a power of two in [{@value #MIN_SEGMENT_CAPACITY}, 1048576]</li>
 *   <li>load factor is entry_count / slot_count, and inserts are refused at the segment's own
 *       threshold, {@value #LOAD_FACTOR_THRESHOLD} unless one was configured</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see SegmentConfig
 */
public class Segment implements AutoCloseable {

    static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(Segment.class.getName());

    // ==================== Constants ====================

    /** Minimum segment capacity (number of slots). Must be power of 2. */
    public static final int MIN_SEGMENT_CAPACITY = 1024;

    /** Maximum segment capacity (number of slots). Must be power of 2. */
    public static final int MAX_SEGMENT_CAPACITY = 1 << 20; // 1M entries

    /** Load factor threshold for triggering split/rejection. */
    public static final double LOAD_FACTOR_THRESHOLD = 0.7;

    /**
     * This segment's own threshold, defaulting to {@link #LOAD_FACTOR_THRESHOLD}.
     *
     * <p>Per-segment rather than static so that it can be configured; the constant remains the
     * default and the documented value.</p>
     */
    final double loadFactorThreshold;

    /** Maximum probe length before giving up. */
    public static final int MAX_PROBE_LENGTH = 128;

    /** Greatest Robin Hood distance a slot can hold; 128 would overflow the unsigned byte. */
    public static final int MAX_DISTANCE = 127;

    /** Size of each slot in the hash table (bytes). */
    static final int SLOT_SIZE = SegmentLayout.SLOT_SIZE;

    /** Size of entry trailer (tombstone byte). */
    static final int ENTRY_TRAILER_SIZE = SegmentLayout.ENTRY_TRAILER_SIZE;

    // ==================== Segment ID Generation ====================

    /** Source of the per-instance id that zero-copy slice validity is checked against. */
    static final AtomicLong SEGMENT_ID_GENERATOR = new AtomicLong(0);

    // Entry field offsets (in data region), defined once in SegmentLayout so that the
    // integrity checker and any repair tooling read the same bytes this class writes.
    static final int ENTRY_KEY_HASH_OFFSET = SegmentLayout.ENTRY_KEY_HASH_OFFSET;
    static final int ENTRY_KEY_LEN_OFFSET = SegmentLayout.ENTRY_KEY_LEN_OFFSET;
    static final int ENTRY_VALUE_LEN_OFFSET = SegmentLayout.ENTRY_VALUE_LEN_OFFSET;
    static final int ENTRY_DISTANCE_OFFSET = SegmentLayout.ENTRY_DISTANCE_OFFSET;

    // Slot field offsets
    static final int SLOT_KEY_HASH_OFFSET = SegmentLayout.SLOT_KEY_HASH_OFFSET;
    static final int SLOT_DISTANCE_OFFSET = SegmentLayout.SLOT_DISTANCE_OFFSET;
    static final int SLOT_FLAGS_OFFSET = SegmentLayout.SLOT_FLAGS_OFFSET;
    static final int SLOT_DATA_OFFSET_OFFSET = SegmentLayout.SLOT_DATA_OFFSET_OFFSET;

    // Flag bits
    static final byte FLAG_OCCUPIED = SegmentLayout.FLAG_OCCUPIED;
    static final byte FLAG_TOMBSTONE = SegmentLayout.FLAG_TOMBSTONE;

    // ValueLayouts for memory access (little-endian as per spec)
    static final ValueLayout.OfLong LONG_LAYOUT = SegmentLayout.LONG_LAYOUT;
    static final ValueLayout.OfInt INT_LAYOUT = SegmentLayout.INT_LAYOUT;
    static final ValueLayout.OfByte BYTE_LAYOUT = SegmentLayout.BYTE_LAYOUT;

    // ==================== Instance Fields ====================

    /**
     * Path to the backing file.
     *
     * <p>Not final: compaction and split publication rename the backing file into its
     * canonical {@code segment_<index>.dat} name while this segment stays mapped, and
     * {@link #renamedTo(Path)} then records the new name.</p>
     */
    volatile Path path;

    final Arena arena;
    final FileChannel fileChannel;
    final MemorySegment segment;
    final int slotCount;
    final long slotTableSize;

    /**
     * On-disk entry format of the backing file, fixed for the life of the mapping.
     *
     * <p>The entry header width is baked into every record, so one file cannot hold records
     * of two shapes; {@link SegmentFile} decides this once, on create or on open.</p>
     */
    final SegmentFormat format;

    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    volatile boolean closed = false;

    /**
     * Number of occupied slots, tombstoned or not, which is what the probe budget is measured
     * against because a tombstoned slot is never reused. See {@link #liveEntryCount}.
     */
    volatile int entryCount = 0;

    /** Number of occupied slots that are not tombstoned. */
    volatile int liveEntryCount = 0;

    volatile long dataOffset = 0; // Current write offset in data region

    /** Bumped by every put and delete; zero-copy slice validity is judged against it. */
    volatile long modificationEpoch = 0;

    /** Identifies this instance, so a slice can be told whether it came from this segment. */
    final long segmentId;

    /** Write-ahead log this segment's mutations have to agree with; see {@link SegmentMutations}. */
    volatile WriteLog writeLog;

    /** Guards {@link SegmentMutations#warnUnlogged()} so the warning is issued once per segment. */
    final java.util.concurrent.atomic.AtomicBoolean unloggedWarningIssued =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Coarse vector pre-filter, populated during ingestion and used ahead of the HNSW search
     * to cut the candidate set to roughly 10x-100x top_k.
     */
    volatile com.eonmux.excalibur.filter.lsh.LshIndex lshIndex;

    /** Vector index for this segment, attached at initialisation; volatile so attachment publishes. */
    volatile com.eonmux.excalibur.vector.hnsw.HnswGraph hnswGraph;

    /**
     * How far into the write log the HNSW graph has been brought up to date; a query tail-scans
     * from there. Volatile so attachment publishes.
     */
    volatile com.eonmux.excalibur.vector.hnsw.TailOffsetTracker tailOffsetTracker;

    /** Where this segment is in the split state machine; see {@link SegmentSplitControl}. */
    volatile SplitState splitState = SplitState.NORMAL;

    /** Pre-split view reads are served from while {@link #splitState} is SPLITTING. */
    volatile SegmentSnapshot splitSnapshot;

    /** Children this segment's entries were partitioned into; set when the split completes. */
    volatile Segment leftChild;
    volatile Segment rightChild;

    /** Hash bit the children were partitioned on; -1 until {@link #completeSplit} has run. */
    volatile int splitBit = -1;

    // ==================== Collaborators ====================

    /** The slot table and its Robin Hood probing. */
    final SegmentSlots slots = new SegmentSlots(this);

    /** The data region and the entry records in it. */
    final SegmentRecords records = new SegmentRecords(this);

    /** The read path used while this segment is being split. */
    final SegmentSnapshotReads snapshotReads = new SegmentSnapshotReads(this);

    /** Every way of reading a key out of this segment. */
    final SegmentLookups lookups = new SegmentLookups(this);

    /** Every way of changing this segment, and the log that has to agree with it. */
    final SegmentMutations mutations = new SegmentMutations(this);

    /** The split state machine. */
    final SegmentSplitControl split = new SegmentSplitControl(this);

    /** Opening, recovering and closing the backing file. */
    final SegmentFile file = new SegmentFile(this);

    // ==================== Constructors ====================

    /**
     * Creates a segment backed by a new memory-mapped file, at the default load factor.
     *
     * <p>The file is created or truncated, sized, mapped into the given arena and stamped
     * with the current format, and its slot table is cleared; {@link SegmentFile#create} says
     * how the byte figure becomes a geometry. The arena owns the mapping, so closing it
     * releases the segment's memory.</p>
     *
     * @param path          Path to the backing file (must not be null)
     * @param capacityBytes Requested capacity, rounded into a power-of-two slot table
     * @param arena         Arena for memory management (must not be null)
     * @throws IOException              if the file cannot be created or mapped
     * @throws IllegalArgumentException if path or arena is null, or the capacity is invalid
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
        SegmentFile.validateLoadFactorThreshold(loadFactorThreshold);
        this.loadFactorThreshold = loadFactorThreshold;

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

        SegmentFile.Created created = SegmentFile.create(path, capacityBytes, arena);
        this.slotCount = created.slotCount();
        this.slotTableSize = created.slotTableSize();
        this.fileChannel = created.channel();
        this.segment = created.mapping();
        this.format = SegmentFormat.CURRENT;

        // dataOffset tracks the offset within the data region, not the absolute position in
        // the segment, so it starts at the first offset an entry record may occupy.
        this.dataOffset = format.firstEntryOffset();

        slots.clearSlotTable();
    }

    /**
     * Opens an existing segment file.
     *
     * @param path  Path to the existing segment file
     * @param arena Arena for memory management
     * @return The opened segment
     * @throws IOException if the file cannot be opened or is invalid
     * @deprecated Use {@link #openExisting(Path, Arena)} instead to preserve existing data
     */
    @Deprecated
    public static Segment open(Path path, Arena arena) throws IOException {
        return openExisting(path, arena);
    }

    /**
     * Opens an existing segment file without truncating it, at the default load factor.
     *
     * <p>The preferred way to open a segment at startup: the file is mapped as it stands, its
     * geometry and format are derived from it, and its entry counts and write offset are
     * recovered by scanning the slot table. {@link SegmentFile} explains how a file written by
     * an earlier build is recognised and then left byte-for-byte alone.</p>
     *
     * @param path  Path to the existing segment file (must exist)
     * @param arena Arena for memory management (must not be null)
     * @return The opened segment, with its state recovered
     * @throws com.eonmux.excalibur.store.format.UnsupportedSegmentFormatException
     *                                  if a newer build wrote the file; it is left untouched
     * @throws IOException              if the file cannot be opened or is invalid
     * @throws IllegalArgumentException if path or arena is null, or the file does not exist
     */
    public static Segment openExisting(Path path, Arena arena) throws IOException {
        return openExisting(path, arena, LOAD_FACTOR_THRESHOLD);
    }

    /**
     * Opens an existing segment with an explicit load-factor threshold.
     *
     * <p>The threshold is not in the file - it governs when to stop accepting inserts, not how
     * the file is laid out - so it has to be supplied on every open. Defaulting it meant a
     * segment filled under a configured 0.9 was reopened at 0.7 and then refused the replay of
     * entries it had already acknowledged, which fails the start and keeps failing it.</p>
     *
     * @param path                Segment file
     * @param arena               Arena owning the mapping
     * @param loadFactorThreshold Fraction of slots that may be occupied, in (0, 1)
     * @return The opened segment
     * @throws com.eonmux.excalibur.store.format.UnsupportedSegmentFormatException
     *                     if a newer build wrote the file; it is left untouched
     * @throws IOException if the file cannot be opened
     */
    public static Segment openExisting(Path path, Arena arena, double loadFactorThreshold)
            throws IOException {
        return SegmentFile.openExisting(path, arena, loadFactorThreshold);
    }

    /**
     * Derives the slot count of an existing file from its size.
     *
     * <p>A segment file is a power-of-two slot table followed by a data region of at least one
     * megabyte, so the geometry can be recovered from the size rather than stored.</p>
     *
     * @param fileSize The file size in bytes
     * @return The derived slot count
     */
    public static int deriveSlotCountForRecovery(long fileSize) {
        return SegmentFile.deriveSlotCountForRecovery(fileSize);
    }

    /**
     * Wraps a file that {@link SegmentFile} has already opened, mapped and measured.
     *
     * <p>Leaves {@code entryCount} and {@code dataOffset} at zero; the caller recovers them by
     * scanning the slot table before the segment is published to anyone.</p>
     */
    Segment(Path path, Arena arena, FileChannel fileChannel, MemorySegment segment,
                    int slotCount, long slotTableSize, double loadFactorThreshold,
                    SegmentFormat format) {
        SegmentFile.validateLoadFactorThreshold(loadFactorThreshold);
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
        this.format = Objects.requireNonNull(format, "format");
        // entryCount and dataOffset will be recovered by recoverStateFromSlotTable()
    }

    // ==================== Public API ====================

    /**
     * Inserts or updates a key-value pair, taking the write lock.
     *
     * <p>An existing key is overwritten. A new key is refused once the segment is full at its
     * own load-factor threshold; {@link SegmentMutations} explains how the write and the
     * write-ahead log are ordered so that neither can outlive the other.</p>
     *
     * @param key   The key (must not be null)
     * @param value The value (must not be null)
     * @throws IllegalStateException    if the segment is closed, splitting, or full
     * @throws IllegalArgumentException if key or value is null, or too large
     */
    public void put(byte[] key, byte[] value) {
        mutations.put(key, value);
    }

    /**
     * Retrieves a key's value as a zero-copy slice of the mapping, taking the read lock.
     *
     * <p>The result is a native slice of the memory-mapped file, not a copy on the Java heap:
     * {@code result.heapBase()} is empty. It stays valid until this segment is modified or
     * closed or its arena is closed, and {@link #getWithEpoch(byte[])} is the variant that
     * lets a caller check that.</p>
     *
     * @param key The key to look up
     * @return An off-heap slice holding the value, or null if absent or tombstoned
     * @throws IllegalStateException        if the segment is closed
     * @throws IllegalArgumentException     if key is null
     * @throws CorruptSegmentEntryException if the record, or the slot naming it, is damaged
     */
    public MemorySegment get(byte[] key) {
        return lookups.get(key);
    }

    // ==================== Zero-Copy Slice Validity API ====================

    /**
     * Retrieves a key's value as a zero-copy slice stamped with the epoch it was read at.
     *
     * <p>The slice is valid until this segment is modified by any thread, closed, or its
     * arena is closed. {@link ZeroCopySlice#isValid()} and {@link #isSliceValid(ZeroCopySlice)}
     * report that by comparing the stamp against {@link #currentEpoch()}, so a caller holding
     * a slice across other work can tell whether it is still looking at live bytes.</p>
     *
     * @param key The key to look up
     * @return The value and the epoch it was read at, or a not-found slice
     * @throws IllegalStateException    if the segment is closed
     * @throws IllegalArgumentException if key is null
     * @see #isSliceValid(ZeroCopySlice)
     */
    public ZeroCopySlice getWithEpoch(byte[] key) {
        return lookups.getWithEpoch(key);
    }

    /**
     * Reports whether a zero-copy slice still describes live, unchanged bytes.
     *
     * <p>True only if this segment is open, the slice came from this segment, and no write
     * has landed since it was taken. Thread-safe, but the answer is only true of the instant
     * it was asked: another thread may modify the segment immediately afterwards.</p>
     *
     * @param slice The slice to check
     * @return true if the slice is still valid
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
     * Returns the modification epoch, which every put and delete increments.
     *
     * <p>Starts at 0 and only rises, so an unchanged value across two reads means no write
     * landed in between. That is what zero-copy slice validity is judged on.</p>
     *
     * @return The current modification epoch
     */
    public long currentEpoch() {
        return modificationEpoch;
    }

    /**
     * Returns this instance's unique identifier, which tells whose slices are whose.
     *
     * @return The segment identifier (non-negative)
     */
    public long segmentId() {
        return segmentId;
    }

    /**
     * Marks a key tombstoned, taking the write lock.
     *
     * <p>The record stays on disk and its slot stays occupied until a compaction rebuilds the
     * file, so a delete frees nothing immediately.</p>
     *
     * @param key The key to delete
     * @return true if the key was found and deleted, false if not found
     * @throws IllegalStateException if segment is closed
     * @throws IllegalArgumentException if key is null
     */
    public boolean delete(byte[] key) {
        return mutations.delete(key);
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
     * Returns the number of occupied slots, tombstoned entries included.
     *
     * <p>A tombstoned slot stays occupied until compaction rebuilds the segment, so it still
     * costs probe budget and still counts towards the load factor. See
     * {@link #liveEntryCount()} for the number of readable entries.</p>
     *
     * @return Occupied slot count
     */
    public int entryCount() {
        return entryCount;
    }

    /**
     * Returns the number of readable entries, which is the index space
     * {@link #readEntryKey(int)} and {@link #readEntryValue(int)} accept.
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
     * Records that the backing file has already been renamed.
     *
     * <p>Compaction and split publication move the file into its canonical
     * {@code segment_<index>.dat} name with an atomic rename while this segment stays mapped.
     * The mapping and the descriptor follow the inode, so only the recorded path changes.</p>
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
     * Returns the arena that owns the mapping; closing it releases this segment's memory.
     *
     * @return The arena
     */
    public Arena arena() {
        return arena;
    }

    /**
     * Returns the whole mapping. Reading it directly bypasses every lock this class takes.
     *
     * @return The memory-mapped region
     */
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public void close() {
        file.close();
    }

    // ==================== Striped Lock Accessors ====================

    /**
     * Returns this segment's read lock, for holding across a run of reads.
     *
     * <p>Take it and use the {@code WithoutLock} read variants inside, so that the run sees
     * one consistent state and pays for the lock once. The lock is this segment's alone:
     * holding it blocks nothing on any other segment in the keyspace.</p>
     *
     * @return The read lock for this segment
     * @see #getWithoutLock(byte[])
     */
    public Lock readLock() {
        return lock.readLock();
    }

    /**
     * Returns this segment's write lock, for holding across a run of writes.
     *
     * <p>Take it and use the {@code WithoutLock} write variants inside, so that the run is
     * applied as one and pays for the lock once. The lock is this segment's alone: holding it
     * blocks nothing on any other segment in the keyspace.</p>
     *
     * @return The write lock for this segment
     * @see #putWithoutLock(byte[], byte[])
     */
    public Lock writeLock() {
        return lock.writeLock();
    }

    /**
     * Retrieves a key's value without taking any lock.
     *
     * <p><strong>The caller must already hold {@link #readLock()} or {@link #writeLock()}.</strong>
     * For bulk reads that take the lock once and then run.</p>
     *
     * @param key The key to look up
     * @return A slice holding the value, or null if absent or tombstoned
     * @throws IllegalStateException    if the segment is closed
     * @throws IllegalArgumentException if key is null
     * @see #readLock()
     */
    public MemorySegment getWithoutLock(byte[] key) {
        return lookups.getWithoutLock(key);
    }

    // ==================== High-Performance Lookup Methods ====================

    /**
     * Retrieves a key's value from a hash the caller already has, taking the read lock.
     *
     * <p>For throughput paths that cache hashes; the caller is responsible for the hash
     * matching the key, and {@link #hashKey(byte[])} is what produces it.</p>
     *
     * <p>Reads the live mapping even while the segment is splitting, and finds what
     * {@link #get(byte[])} finds: the split snapshot is a view of these same bytes, and no
     * write can reach a splitting segment.</p>
     *
     * @param key     The key to look up (must not be null)
     * @param keyHash Pre-computed hash of the key
     * @return A slice holding the value, or null if absent
     * @throws IllegalStateException if the segment is closed
     */
    public MemorySegment getByHash(byte[] key, long keyHash) {
        return lookups.getByHash(key, keyHash);
    }

    /**
     * Retrieves a key's value from a pre-computed hash without taking any lock.
     *
     * <p><strong>The caller must already hold {@link #readLock()}.</strong> The shortest
     * lookup path there is, for benchmarks and batch work that manage concurrency
     * themselves.</p>
     *
     * <p>Reads the live mapping even while the segment is splitting, and finds what
     * {@link #get(byte[])} finds: the split snapshot is a view of these same bytes, and no
     * write can reach a splitting segment.</p>
     *
     * @param key     The key to look up (must not be null)
     * @param keyHash Pre-computed hash of the key
     * @return A slice holding the value, or null if absent
     * @throws IllegalStateException if the segment is closed
     */
    public MemorySegment getByHashWithoutLock(byte[] key, long keyHash) {
        return lookups.getByHashWithoutLock(key, keyHash);
    }

    /**
     * Computes the normalised, non-negative hash the {@code getByHash} methods expect.
     *
     * @param key The key to hash
     * @return The hash, in [0, Long.MAX_VALUE]
     * @throws IllegalArgumentException if key is null
     */
    public long hashKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        return records.computeHash(key);
    }

    /**
     * Looks up many keys under one acquisition of the read lock.
     *
     * <p>Reads the live mapping even while the segment is splitting, and finds what
     * {@link #get(byte[])} finds: the split snapshot is a view of these same bytes, and no
     * write can reach a splitting segment.</p>
     *
     * @param keys   Keys to look up
     * @param hashes Their pre-computed hashes, positionally matched to {@code keys}
     * @return One slice per key, null where the key is absent
     * @throws IllegalStateException    if the segment is closed
     * @throws IllegalArgumentException if either array is null or they differ in length
     */
    public MemorySegment[] batchGetByHash(byte[][] keys, long[] hashes) {
        return lookups.batchGetByHash(keys, hashes);
    }

    /**
     * Inserts or updates a key-value pair without taking any lock.
     *
     * <p><strong>The caller must already hold {@link #writeLock()}.</strong> For bulk writes
     * that take the lock once and then run.</p>
     *
     * @param key   The key (must not be null)
     * @param value The value (must not be null)
     * @throws SegmentRetiredException  if the segment was replaced by its split children
     * @throws IllegalStateException    if the segment is closed, splitting, or full
     * @throws IllegalArgumentException if key or value is null, or too large
     * @see #writeLock()
     */
    public void putWithoutLock(byte[] key, byte[] value) {
        mutations.putWithoutLock(key, value);
    }

    /**
     * Marks a key tombstoned without taking any lock.
     *
     * <p><strong>The caller must already hold {@link #writeLock()}.</strong></p>
     *
     * @param key The key to delete
     * @return true if the key was found and deleted, false if not found
     * @throws SegmentRetiredException  if the segment was replaced by its split children
     * @throws IllegalStateException    if the segment is closed or splitting
     * @throws IllegalArgumentException if key is null
     * @see #writeLock()
     */
    public boolean deleteWithoutLock(byte[] key) {
        return mutations.deleteWithoutLock(key);
    }

    /**
     * Returns the on-disk entry format of the backing file.
     *
     * <p>Anything short of {@link SegmentFormat#CURRENT} means the file predates some of the
     * checksums this build writes, and {@link #verifyIntegrity()} reports whatever cannot be
     * checked as unverifiable rather than as sound. Such a file is upgraded by being
     * rewritten, which a split or a compaction does.</p>
     *
     * @return The format, never null
     */
    public SegmentFormat format() {
        return format;
    }

    /**
     * Walks every slot and every record in the file and reports what does not hold up.
     *
     * <p>The entry point damage-repair tooling is built on. A read stops at the first damaged
     * entry, which is right for a caller that wanted one key and wrong for an operator
     * deciding what to do about a segment; this runs to the end and names every fault.
     * Structural damage is reported for any format, as is a slot whose key hash disagrees
     * with the record it names - the one way a damaged slot can be found in a file too old to
     * carry a slot checksum. A checksum mismatch can only be reported where a checksum was
     * stored, and what was not stored comes back as unverifiable, which is a different
     * statement from sound.</p>
     *
     * @return What the walk found, never null
     * @throws IllegalStateException if the segment is closed
     */
    public SegmentIntegrityReport verifyIntegrity() {
        validateNotClosed();
        lock.readLock().lock();
        try {
            return SegmentIntegrityChecker.verify(segment, slotCount, slotTableSize, format, path);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * The {@code capacityBytes} that produces exactly {@code slots} slots.
     *
     * <p>The constructor reads its byte figure as "half slot table, half data" and adds a
     * fixed data region of its own on top, so a caller reproducing an existing segment's
     * geometry has to pass the slot table alone. Adding a data region to the figure sizes the
     * next slot table from it, and a segment compacted or split repeatedly then doubles its
     * table every generation while the data region it is meant to serve stays as big as it
     * was.</p>
     *
     * @param slots How many slots the segment must have
     * @return The byte figure to construct it with
     */
    public static long capacityForSlots(int slots) {
        return SegmentFile.capacityForSlots(slots);
    }

    void validateNotClosed() {
        if (closed) {
            throw new IllegalStateException("Segment is closed: " + path);
        }
    }

    @Override
    public String toString() {
        return String.format("Segment{path=%s, slots=%d, entries=%d, loadFactor=%.3f, closed=%s}",
            path, slotCount, entryCount, loadFactor(), closed);
    }

    // ==================== Entry Reading for Compaction ====================

    /**
     * Reads one live entry's key by position, taking the read lock.
     *
     * <p>For maintenance work such as merging a write log, which walks entries by index
     * rather than by key.</p>
     *
     * @param index The live entry index, in [0, {@link #liveEntryCount()})
     * @return Key bytes
     * @throws IndexOutOfBoundsException if index is out of range
     * @throws IllegalStateException     if the segment is closed
     */
    public byte[] readEntryKey(int index) {
        return records.readEntryKey(index);
    }

    /**
     * Reads one live entry's value by position, taking the read lock.
     *
     * @param index The live entry index, in [0, {@link #liveEntryCount()})
     * @return Value bytes
     * @throws IndexOutOfBoundsException if index is out of range
     * @throws IllegalStateException     if the segment is closed
     */
    public byte[] readEntryValue(int index) {
        return records.readEntryValue(index);
    }

    // ==================== Write Log Integration ====================

    /**
     * Attaches the write-ahead log this segment's mutations must agree with.
     *
     * <p>{@link SegmentMutations} explains the ordering, which is not simply "log first":
     * what can still refuse decides which of the two goes first.</p>
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
     * @return The log, or null if none is attached
     */
    public WriteLog writeLog() {
        return writeLog;
    }

    /**
     * Attaches the coarse vector pre-filter used ahead of the HNSW search.
     *
     * <p>It cuts the candidate set to roughly 10x-100x top_k before the more expensive stage
     * runs.</p>
     *
     * @param lshIndex The LSH index to attach (must not be null)
     * @throws NullPointerException if lshIndex is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachLshIndex(com.eonmux.excalibur.filter.lsh.LshIndex lshIndex) {
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
     * Returns the coarse vector pre-filter attached to this segment.
     *
     * @return The LSH index, or null if none is attached
     */
    public com.eonmux.excalibur.filter.lsh.LshIndex lshIndex() {
        return lshIndex;
    }

    /**
     * An alias for {@link #lshIndex()}, kept for API consistency.
     *
     * @return The LSH index, or null if none is attached
     */
    public com.eonmux.excalibur.filter.lsh.LshIndex getLshIndex() {
        return lshIndex;
    }

    /**
     * Gives up ownership of the attached pre-filter without closing it.
     *
     * <p>For handing it to a replacement segment across a compaction: the ids it holds are
     * HNSW node ids and compaction carries the graph over unchanged, so they still name the
     * same vectors. {@link #close()} closes whatever is still attached, so a swap that let
     * the old segment close over a shared index would release mapped files underneath a live
     * query path.</p>
     *
     * @return The LSH index that was attached, or null if there was none
     */
    public com.eonmux.excalibur.filter.lsh.LshIndex detachLshIndex() {
        com.eonmux.excalibur.filter.lsh.LshIndex detached = this.lshIndex;
        this.lshIndex = null;
        return detached;
    }

    // ==================== HNSW Graph Methods ====================

    /**
     * Attaches the vector index used for approximate nearest neighbour search.
     *
     * <p>It is populated incrementally during ingestion, as vectors are written.</p>
     *
     * @param graph The HNSW graph to attach (must not be null)
     * @throws NullPointerException if graph is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachHnswGraph(com.eonmux.excalibur.vector.hnsw.HnswGraph graph) {
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
     * Gives up ownership of the attached graph without closing it.
     *
     * <p>For handing it to a replacement segment. {@link #close()} closes whatever is still
     * attached, so a compaction swap that let the old segment close over a shared graph would
     * release its mappings underneath a live index; detaching first makes the transfer
     * explicit.</p>
     *
     * @return The graph that was attached, or null if there was none
     */
    public com.eonmux.excalibur.vector.hnsw.HnswGraph detachHnswGraph() {
        com.eonmux.excalibur.vector.hnsw.HnswGraph detached = this.hnswGraph;
        this.hnswGraph = null;
        return detached;
    }

    /**
     * Returns the vector index over what this segment holds.
     *
     * @return The HNSW graph, or null if none is attached
     */
    public com.eonmux.excalibur.vector.hnsw.HnswGraph hnswGraph() {
        return hnswGraph;
    }

    /**
     * An alias for {@link #hnswGraph()}, kept for API consistency.
     *
     * @return The HNSW graph, or null if none is attached
     */
    public com.eonmux.excalibur.vector.hnsw.HnswGraph getHnswGraph() {
        return hnswGraph;
    }

    /**
     * Attaches the tracker that records how far the HNSW graph has caught up with the log.
     *
     * <p>Its {@code lastIntegratedOffset} is where a live-indexing tail scan starts.</p>
     *
     * @param tracker The tail offset tracker to attach (must not be null)
     * @throws NullPointerException if tracker is null
     * @throws IllegalStateException if segment is closed
     */
    public void attachTailOffsetTracker(com.eonmux.excalibur.vector.hnsw.TailOffsetTracker tracker) {
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
     * Returns how far into the log the HNSW graph has been brought up to date.
     *
     * @return The tail offset tracker, or null if none is attached
     */
    public com.eonmux.excalibur.vector.hnsw.TailOffsetTracker tailOffsetTracker() {
        return tailOffsetTracker;
    }

    /**
     * An alias for {@link #tailOffsetTracker()}, kept for API consistency.
     *
     * @return The tail offset tracker, or null if none is attached
     */
    public com.eonmux.excalibur.vector.hnsw.TailOffsetTracker getTailOffsetTracker() {
        return tailOffsetTracker;
    }

    /**
     * Returns how far the write log has been written to, which is where a tail scan stops.
     *
     * @return The log's write offset in bytes, or 0 when no log is attached
     */
    public long getWriteLogHead() {
        if (writeLog == null) {
            return 0;
        }
        return writeLog.writeOffset();
    }

    /**
     * Replays the write log into this segment, during startup and before any other thread.
     *
     * <p>Opening the log has already established that every record up to its head reads
     * cleanly, so a record that will not read during the replay means the log changed while
     * it was being read. The segment then holds some prefix of the state its log describes
     * and there is no telling which, so this fails rather than returning, and the engine
     * treats that as a reason not to start.</p>
     *
     * @throws CorruptLogException if the log could not be replayed in full
     * @throws IOException if reading the log fails
     * @throws IllegalStateException if no write log is attached or segment is closed
     */
    public void recoverFromLog() throws IOException {
        mutations.recoverFromLog();
    }

    // ==================== Split Operations ====================

    /**
     * Whether this segment can take no further insert, which is when it should be split.
     *
     * <p>Judged against the threshold this segment was opened with -
     * {@code excalibur.shard.segment-load-factor} where it is configured, and
     * {@value #LOAD_FACTOR_THRESHOLD} otherwise. Deliberately the very predicate the insert
     * path refuses on rather than a second one that looks equivalent; when they were defined
     * separately they disagreed by one entry and automatic splitting stopped happening. See
     * {@link SegmentSplitControl#isFull()}.</p>
     *
     * @return true if the segment should be split
     */
    public boolean shouldSplit() {
        return split.isFull();
    }

    /**
     * Whether a segment of this geometry could hold this entry if it held nothing else.
     *
     * <p>Splitting is how a full segment makes room, and every segment a split produces has its
     * parent's geometry. So an entry that fails this can never be stored by splitting, however
     * many times the split is repeated - each repetition only deepens the routing directory.
     * The write path asks this before it splits for a write.</p>
     *
     * @param key   The key
     * @param value The value
     * @return true if an empty segment of this geometry could take the entry
     */
    public boolean fitsWhenEmpty(byte[] key, byte[] value) {
        return records.fitsEmptySegment(key.length, value.length);
    }

    /**
     * Returns the fraction of slots this segment may occupy before it counts as full.
     *
     * <p>Fixed for the life of a segment. A segment created to succeed this one - a split
     * child, a compacted rebuild - has to be given the same value, or a configured threshold
     * silently reverts to the default for everything created after the first split.</p>
     *
     * @return The load factor threshold, in (0, 1)
     */
    public double loadFactorThreshold() {
        return loadFactorThreshold;
    }

    /**
     * @return true while a split is in progress on this segment
     */
    public boolean isSplitting() {
        return splitState == SplitState.SPLITTING;
    }

    /**
     * @return true once this segment's split has completed and it is awaiting retirement
     */
    public boolean isSplitComplete() {
        return splitState == SplitState.SPLIT_COMPLETE;
    }

    /**
     * Begins a copy-on-write split, taking a read-only snapshot of the current state.
     *
     * <p>From here until the split completes or is abandoned, reads are served from that
     * snapshot and writes are refused. The snapshot is what the caller partitions into the
     * two children.</p>
     *
     * @return The pre-split view of this segment
     * @throws IllegalStateException if the segment is closed or already splitting
     */
    public SegmentSplitSnapshot beginSplit() {
        return split.beginSplit();
    }

    /**
     * Completes a split by recording its children, leaving this segment ready to retire.
     *
     * <p>The snapshot is released and further reads and writes belong to the children; this
     * segment refuses them with {@link SegmentRetiredException} so the router resolves the
     * key again.</p>
     *
     * @param leftChild  The left child segment (keys whose {@code splitBit} is 0)
     * @param rightChild The right child segment (keys whose {@code splitBit} is 1)
     * @param splitBit   The hash bit the entries were partitioned on: the lowest bit in which
     *                   the directory slots this segment owned differ, or the bit a doubling
     *                   of the directory newly exposes when it owned a single slot
     * @throws IllegalStateException if segment is not in SPLITTING state
     * @throws IllegalArgumentException if splitBit is not in [0, 63]
     * @throws NullPointerException if any child is null
     */
    public void completeSplit(Segment leftChild, Segment rightChild, int splitBit) {
        split.completeSplit(leftChild, rightChild, splitBit);
    }

    /**
     * Returns the hash bit this segment's entries were partitioned on.
     *
     * <p>Anything picking a child for a key reads this rather than deriving a bit of its own,
     * so the partitioning and the routing cannot disagree.</p>
     *
     * @return The split bit, or -1 if this segment has not completed a split
     */
    public int splitBit() {
        return splitBit;
    }

    /**
     * @return The left child, holding keys whose split bit is 0, or null before the split
     *         completes
     */
    public Segment leftChild() {
        return leftChild;
    }

    /**
     * @return The right child, holding keys whose split bit is 1, or null before the split
     *         completes
     */
    public Segment rightChild() {
        return rightChild;
    }

    /**
     * Abandons an in-progress split, discarding the snapshot and letting writes resume.
     *
     * @throws IllegalStateException if no split is in progress
     */
    public void abortSplit() {
        split.abortSplit();
    }

    /**
     * Decides which child of a split a key belongs to, from one bit of its hash.
     *
     * @param keyHash  The key hash
     * @param splitBit The bit position to partition on (0 = least significant)
     * @return true for the right child, false for the left
     */
    public static boolean shouldGoToRightChild(long keyHash, int splitBit) {
        return ((keyHash >>> splitBit) & 1L) == 1L;
    }

    // ==================== Compaction Support ====================

    /**
     * Returns an iterator over the live entries, for compaction to copy forward.
     *
     * <p>It holds the read lock from construction until it is closed, so iterate promptly
     * and close it.</p>
     *
     * @return An iterator over live entries
     * @throws IllegalStateException if the segment is closed
     */
    public EntryIterator liveEntries() {
        validateNotClosed();
        return new EntryIterator(this, false);
    }

    /**
     * Returns an iterator over every occupied slot, tombstoned entries included.
     *
     * @return An iterator over all entries
     * @throws IllegalStateException if the segment is closed
     */
    public EntryIterator allEntries() {
        validateNotClosed();
        return new EntryIterator(this, true);
    }

    /**
     * Returns how many occupied slots are tombstoned, which is what the compaction
     * scheduler ranks segments by.
     *
     * @return The number of tombstoned entries
     */
    public int tombstoneCount() {
        return slots.tombstoneCount();
    }

    /**
     * Estimates the bytes a compaction would free, being those the tombstoned records hold.
     *
     * @return Estimated reclaimable bytes
     */
    public long estimatedReclaimableBytes() {
        return slots.estimatedReclaimableBytes();
    }

    // ==================== Inner Classes ====================

    /** Where a segment is in the copy-on-write split; see {@link SegmentSplitControl}. */
    enum SplitState {
        /** Normal operation, no split in progress. */
        NORMAL,
        /** Split in progress, reads served from snapshot. */
        SPLITTING,
        /** Split complete, segment ready for retirement. */
        SPLIT_COMPLETE
    }

    /**
     * The read-only view of a segment taken when its split begins.
     *
     * <p>Reads are served from it for as long as the split lasts, so they see one consistent
     * pre-split state however far the children have got. See {@link SegmentSnapshotReads}.</p>
     */
    static final class SegmentSnapshot {
        /** Read-only view of the mapping as it stood when the split began. */
        final MemorySegment segment;

        /** The epoch a slice handed out of this snapshot is validated against. */
        final long modificationEpoch;

        SegmentSnapshot(MemorySegment segment, long modificationEpoch) {
            this.segment = segment.asReadOnly(); // Immutable view
            this.modificationEpoch = modificationEpoch;
        }
    }

    /**
     * The pre-split contents of a segment, handed to whoever performs the split.
     *
     * <p>A split reads every entry out of here and writes it into one of two children, and it
     * does so outside every lock the parent holds: this is a read-only view fixed at the
     * instant the split began, so nothing in it can change while the split runs and no reader
     * has to be excluded meanwhile. Every entry is verified on the way out, because a split
     * that copied damage forward would give it a checksum asserting it was sound.</p>
     *
     * <p><strong>Thread safety:</strong> immutable, and safe for concurrent readers.</p>
     *
     * @see SplitSnapshotData
     */
    public static final class SegmentSplitSnapshot {

        /** The frozen contents, and the walk over them. */
        private final SplitSnapshotData data;

        /**
         * Creates a split snapshot.
         *
         * @param segment       The memory segment (will be made read-only)
         * @param slotCount     Number of slots
         * @param entryCount    Number of entries
         * @param slotTableSize Size of slot table region
         * @param path          Path to the segment file
         * @param format        On-disk entry format of the file being split
         */
        SegmentSplitSnapshot(MemorySegment segment, int slotCount, int entryCount,
                             long slotTableSize, Path path, SegmentFormat format) {
            this.data = new SplitSnapshotData(segment, slotCount, entryCount,
                slotTableSize, path, format);
        }

        /**
         * Returns the number of slots in the snapshot.
         *
         * @return Slot count
         */
        public int slotCount() {
            return data.slotCount();
        }

        /**
         * Returns the number of entries in the snapshot.
         *
         * @return Entry count
         */
        public int entryCount() {
            return data.entryCount();
        }

        /**
         * Returns the path to the segment file.
         *
         * @return File path
         */
        public Path path() {
            return data.path();
        }

        /**
         * Reads an entry from the snapshot, verifying it before copying it out.
         *
         * @param index The entry index (0-based, not slot index)
         * @return The entry, or null if index is out of range or the slot is empty
         * @throws CorruptSegmentEntryException if the record, or the slot that names it,
         *                                      does not match its checksum
         */
        public SegmentEntry readEntry(int index) {
            return data.readEntry(index);
        }

        /**
         * Iterates over all entries in the snapshot.
         *
         * @param includeTombstones If true, include tombstoned entries
         * @return List of entries
         * @throws CorruptSegmentEntryException if any entry fails verification
         */
        public List<SegmentEntry> entries(boolean includeTombstones) {
            return data.entries(includeTombstones);
        }
    }

    /**
     * An iterator over a segment's entries, in slot order, for compaction to copy forward.
     *
     * <p>Holds the segment's read lock from construction until {@link #close()}, so that the
     * whole walk sees one unchanging state; writers are excluded for the duration, which is
     * why a caller should iterate promptly and close on every path. Each entry is returned
     * exactly once, and tombstoned ones are included only if the factory method asked for
     * them.</p>
     *
     * <p>An entry that fails verification is reported by {@link #hasNext()} or {@link #next()}
     * throwing {@link CorruptSegmentEntryException}, and the walk has already moved past it
     * when they do. A caller that must not copy damage forward stops there; one that can do
     * without the entry - a derived index being rebuilt - catches it and carries on, and the
     * next call resumes at the slot after the damaged one.</p>
     *
     * <p><strong>Thread safety:</strong> one thread per iterator.</p>
     *
     * @see SegmentEntryCursor
     */
    public static final class EntryIterator implements Iterator<SegmentEntry>, AutoCloseable {

        /** The walk, which owns the read lock. */
        private final SegmentEntryCursor cursor;

        /**
         * Creates a new entry iterator, taking the segment's read lock.
         *
         * @param segment           The segment to iterate
         * @param includeTombstones Whether to include tombstoned entries
         */
        EntryIterator(Segment segment, boolean includeTombstones) {
            this.cursor = new SegmentEntryCursor(segment, includeTombstones);
        }

        @Override
        public boolean hasNext() {
            return cursor.hasNext();
        }

        @Override
        public SegmentEntry next() {
            return cursor.next();
        }

        @Override
        public void close() {
            cursor.close();
        }
    }
}
