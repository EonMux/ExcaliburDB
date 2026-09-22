package com.eonmux.excalibur.filter.lsh;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.mapped.MappedFile;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A memory-mapped LSH hash table with buckets storing entry IDs.
 *
 * <h2>Overview</h2>
 * <p>Each LshHashTable uses one hash function (composed of multiple hyperplanes)
 * to hash vectors into buckets. Similar vectors tend to hash to the same bucket.
 * Multiple tables are combined in an LshIndex to improve recall.</p>
 *
 * <h2>Memory Layout</h2>
 * <p>The hash table is stored in a memory-mapped file with the following layout:</p>
 * <pre>
 * HEADER (128 bytes in version 3, 56 in version 2):
 *   [0:4]   Magic (0x4C534854 "LSHT")
 *   [4:8]   Version (3)
 *   [8:12]  NumBits
 *   [12:16] Dimensions
 *   [16:24] Seed
 *   [24:32] EntryCount
 *   [32:40] BucketCount
 *   [40:48] BucketDirectoryCapacity
 *   [48:56] EntryStorageEnd - the bump allocator's cursor, the end of live entry storage
 *   [56:128] Integrity block, version 3 only; see {@link LshHashTableIntegrity}
 *
 * BUCKET DIRECTORY (starting immediately behind the header):
 *   Array of BucketEntry (24 bytes each):
 *   [0:8]   Hash value
 *   [8:16]  EntryOffset (absolute file offset of the first entry, 0 if empty)
 *   [16:20] EntryCount (number of entries in this bucket)
 *   [20:24] Reserved (padding)
 *
 * ENTRY STORAGE (starting immediately behind the bucket directory, ending at
 * EntryStorageEnd):
 *   Variable-length entry list per bucket:
 *   [0:8]   EntryId
 *   [8:16]  NextEntryOffset (absolute file offset, 0 if last in bucket)
 *   [16]    Tombstone (0=active, 1=tombstoned)
 *   [17:24] Reserved (padding)
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Read operations use a read lock - multiple threads can read concurrently</li>
 *   <li>Write operations use a write lock - exclusive access during writes</li>
 *   <li>All reads/writes go directly to the memory-mapped segment</li>
 *   <li>The entryOffset counter uses atomic operations for bump allocation</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li><strong>Hash values</strong>: 64-bit values computed from random hyperplane projections</li>
 *   <li><strong>Entry IDs</strong>: Non-negative long values representing document/entry identifiers</li>
 *   <li><strong>Offsets</strong>: All offsets are validated to be within the mapped segment</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class LshHashTable implements AutoCloseable {

    /** Magic number for file validation. */
    static final int MAGIC = 0x4C534854; // "LSHT" in ASCII

    /**
     * Current file format version. Version 3 added the integrity block described on
     * {@link LshHashTableIntegrity} and moved the bucket directory from offset 56 to 128.
     */
    static final int VERSION = 3;

    /** The version written before the header carried checksums. */
    static final int VERSION_WITHOUT_CHECKSUM = 2;

    /** Header size in bytes, and the offset the bucket directory starts at, in this version. */
    static final int HEADER_SIZE = 128;

    /** Header size in bytes of a version 1 or 2 file. */
    static final int HEADER_SIZE_V2 = 56;

    // Header field offsets
    static final int OFFSET_MAGIC = 0;
    static final int OFFSET_VERSION = 4;
    static final int OFFSET_NUM_BITS = 8;
    static final int OFFSET_DIMENSIONS = 12;
    static final int OFFSET_SEED = 16;
    static final int OFFSET_ENTRY_COUNT = 24;
    static final int OFFSET_BUCKET_COUNT = 32;
    static final int OFFSET_BUCKET_DIR_CAPACITY = 40;
    /**
     * Where the bump allocator's cursor lives: the offset entry storage currently ends at.
     *
     * <p>The end rather than the start. The start is not stored anywhere, because it is
     * always {@code bucketDirOffset + bucketDirCapacity * BUCKET_ENTRY_SIZE} and both of
     * those are in the header already - whereas the end is the one thing about the table's
     * layout that cannot be derived, and a reopen that restores the allocator to the wrong
     * place writes new records over the records already there.</p>
     */
    static final int OFFSET_ENTRY_STORAGE_OFFSET = 48;

    /** Size of each bucket directory entry in bytes. */
    static final int BUCKET_ENTRY_SIZE = 24;

    /** Size of each entry in entry storage in bytes. */
    static final int ENTRY_SIZE = 24;

    // Bucket entry field offsets (relative to bucket entry start)
    static final int BUCKET_HASH = 0;
    static final int BUCKET_ENTRY_OFFSET = 8;
    static final int BUCKET_ENTRY_COUNT = 16;
    static final int BUCKET_RESERVED = 20;

    // Entry storage field offsets (relative to entry start)
    static final int ENTRY_ID = 0;
    static final int ENTRY_NEXT_OFFSET = 8;
    static final int ENTRY_TOMBSTONE = 16;

    /** Initial capacity for bucket directory. */
    private static final int INITIAL_BUCKET_CAPACITY = 1024;

    /** Default initial file size (64KB). */
    private static final long DEFAULT_INITIAL_SIZE = 64 * 1024;

    /** Load factor threshold for growing bucket directory. */
    private static final float LOAD_FACTOR_THRESHOLD = 0.7f;

    /** Value layouts for memory access. */
    private static final ValueLayout.OfInt INT_LAYOUT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    /** Hash function for this table. */
    private final RandomHyperplaneHash hashFunction;

    /** Multi-probe generator for this table. */
    private final MultiProbeGenerator probeGenerator;

    /** Number of hash bits. */
    private final int numBits;

    /** Vector dimensions. */
    private final int dimensions;

    /** Random seed used for hash function. */
    private final long seed;

    /**
     * Where the bucket directory starts in the backing file.
     *
     * <p>Fixed by the file's format version, not by this build: a version 2 file keeps its
     * directory at 56 and a version 3 file at 128. Reading one at the other's offset walks
     * bucket rows as entry records, which is why this is carried per table.</p>
     */
    private final long bucketDirOffset;

    /** The memory-mapped file backing this hash table. */
    private MappedFile mappedFile;

    /** Read-write lock for thread safety. */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Flag indicating if this instance owns the MappedFile. */
    private final boolean ownsMappedFile;

    /** Atomic counter for bump allocation of new entries. */
    private final AtomicLong entryStorageOffset;

    /** Flag indicating if closed. */
    private volatile boolean closed = false;

    /**
     * Creates a new in-memory LshHashTable (not persisted to disk).
     * Uses a temporary memory segment for storage.
     *
     * @param dimensions Vector dimensionality
     * @param numBits    Number of hash bits
     * @param seed       Random seed for hash function
     */
    public LshHashTable(int dimensions, int numBits, long seed) {
        this(dimensions, numBits, seed, HEADER_SIZE);
    }

    /**
     * Creates a new in-memory LshHashTable laid out for a given format version.
     *
     * <p>The directory offset is a parameter rather than a constant because a table built to
     * receive a copy of another table's image must be laid out the way that image is: a
     * version 2 image copied over a version 3 layout puts the copy's bucket rows where this
     * table looks for entry records, and every query comes back empty.</p>
     *
     * @param dimensions      Vector dimensionality
     * @param numBits         Number of hash bits
     * @param seed            Random seed for hash function
     * @param bucketDirOffset Where the bucket directory starts, fixed by the format version
     */
    private LshHashTable(int dimensions, int numBits, long seed, long bucketDirOffset) {
        this.numBits = numBits;
        this.dimensions = dimensions;
        this.seed = seed;
        this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, seed);
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.ownsMappedFile = true;
        this.bucketDirOffset = bucketDirOffset;

        // Create a temporary file for memory-mapped storage
        try {
            Path tempFile = java.nio.file.Files.createTempFile("lsh_hashtable_", ".dat");
            tempFile.toFile().deleteOnExit();
            this.mappedFile = MappedFile.create(tempFile, DEFAULT_INITIAL_SIZE);
            this.entryStorageOffset = new AtomicLong(calculateInitialEntryStorageOffset());
            initializeHeader();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory-mapped LSH hash table", e);
        }
    }

    /**
     * Creates an LshHashTable from an existing hash function.
     *
     * @param hashFunction The hash function to use
     */
    public LshHashTable(RandomHyperplaneHash hashFunction) {
        this(hashFunction.dimensions(), hashFunction.numBits(), 0L);
        // Note: We recreate the hash function with the stored seed, which may differ
    }

    /**
     * Creates or opens a memory-mapped LSH hash table at the specified path.
     *
     * @param path         Path to the file
     * @param dimensions   Vector dimensionality
     * @param numBits      Number of hash bits
     * @param seed         Random seed for hash function. Ignored when the file already
     *                     exists: the seed stored in its header wins, because the
     *                     persisted buckets were filled using that seed.
     * @param initialSize  Initial file size in bytes (0 for default)
     * @throws IOException if the file cannot be created or opened
     */
    public LshHashTable(Path path, int dimensions, int numBits, long seed, long initialSize) throws IOException {
        this.numBits = numBits;
        this.dimensions = dimensions;
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.ownsMappedFile = true;

        boolean fileExists = java.nio.file.Files.exists(path) && java.nio.file.Files.size(path) > 0;
        this.bucketDirOffset = fileExists ? bucketDirOffsetOf(path) : HEADER_SIZE;

        // Never map less than the file already holds: a short mapping hides the tail, and the
        // next extend() would truncate the file back down to the mapped length. Never map
        // less than an empty table needs either: a caller asking for less than the bucket
        // directory occupies gets a table whose first insert probes past the end of its own
        // mapping, and whose header describes a layout the file cannot hold.
        long requestedSize = Math.max(initialSize > 0 ? initialSize : DEFAULT_INITIAL_SIZE,
            calculateInitialEntryStorageOffset());
        long size = fileExists ? Math.max(requestedSize, java.nio.file.Files.size(path)) : requestedSize;
        this.mappedFile = MappedFile.create(path, size);

        if (fileExists) {
            // Read existing header, and refuse a damaged one before the bucket directory is
            // walked. A table that has rotted drops candidates silently, and a short answer
            // is indistinguishable from a correct one.
            LshHashTableIntegrity.requireIntact(
                mappedFile.segment(), path, validateHeader(), bucketDirOffset);
            // Rebuild the hash function from the persisted seed. Hashing with the
            // caller's seed instead would send every vector to a different bucket
            // than the one holding its entries, silently emptying every query.
            this.seed = mappedFile.segment().get(LONG_LAYOUT, OFFSET_SEED);
            this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, this.seed);
            this.entryStorageOffset = new AtomicLong(readEntryStorageOffset());
            breakSeal();
        } else {
            // Initialize new file
            this.seed = seed;
            this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, seed);
            this.entryStorageOffset = new AtomicLong(calculateInitialEntryStorageOffset());
            initializeHeader();
        }
    }

    /**
     * Reads the format version out of an existing table file to place its bucket directory.
     *
     * <p>Read from the file rather than assumed, and before the mapping is made: the version
     * decides where every bucket row is, so guessing it would misparse the whole table.</p>
     *
     * @param path An existing table file
     * @return The offset its bucket directory starts at
     * @throws IOException if the file cannot be read, or is not a table this build knows
     */
    private static long bucketDirOffsetOf(Path path) throws IOException {
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer probe = java.nio.ByteBuffer.allocate(Integer.BYTES * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
            while (probe.hasRemaining()) {
                if (channel.read(probe, probe.position()) < 0) {
                    throw new IOException("LSH table file " + path + " is too short to hold a "
                        + "header: " + channel.size() + " bytes");
                }
            }
            if (probe.getInt(OFFSET_MAGIC) != MAGIC) {
                throw new IOException("LSH table file " + path
                    + " does not start with the table magic number");
            }
            return LshTableHeader.bucketDirOffsetFor(probe.getInt(OFFSET_VERSION));
        }
    }

    /**
     * Creates or opens a memory-mapped LSH hash table at the specified path.
     *
     * @param path       Path to the file
     * @param dimensions Vector dimensionality
     * @param numBits    Number of hash bits
     * @param seed       Random seed for hash function
     * @throws IOException if the file cannot be created or opened
     */
    public LshHashTable(Path path, int dimensions, int numBits, long seed) throws IOException {
        this(path, dimensions, numBits, seed, 0);
    }

    /**
     * Creates a new LshHashTable backed by an existing MappedFile.
     *
     * @param mappedFile The mapped file to use
     * @param dimensions Vector dimensionality
     * @param numBits    Number of hash bits
     * @param seed       Random seed for hash function
     */
    public LshHashTable(MappedFile mappedFile, int dimensions, int numBits, long seed) {
        this(mappedFile, dimensions, numBits, seed, false);
    }

    /**
     * Creates a new LshHashTable backed by an existing MappedFile, stating who owns it.
     *
     * @param mappedFile     The mapped file to use
     * @param dimensions     Vector dimensionality
     * @param numBits        Number of hash bits
     * @param seed           Random seed for hash function
     * @param ownsMappedFile true when {@link #close()} must release the mapping
     */
    private LshHashTable(MappedFile mappedFile, int dimensions, int numBits, long seed,
                         boolean ownsMappedFile) {
        this.numBits = numBits;
        this.dimensions = dimensions;
        this.seed = seed;
        this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, seed);
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.mappedFile = mappedFile;
        this.ownsMappedFile = ownsMappedFile;

        if (mappedFile.size() < HEADER_SIZE_V2) {
            throw new IllegalArgumentException("MappedFile too small for LSH hash table");
        }
        this.bucketDirOffset = LshTableHeader.bucketDirOffsetFor(
            mappedFile.segment().get(INT_LAYOUT, OFFSET_VERSION));

        requireIntact(validateHeader());
        this.entryStorageOffset = new AtomicLong(readEntryStorageOffset());
        breakSeal();
    }

    /**
     * Calculates the initial entry storage offset based on header and initial bucket capacity.
     */
    private long calculateInitialEntryStorageOffset() {
        return bucketDirOffset + (long) INITIAL_BUCKET_CAPACITY * BUCKET_ENTRY_SIZE;
    }

    /**
     * Initializes the header of a new hash table file.
     */
    private void initializeHeader() {
        lock.writeLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();

            segment.set(INT_LAYOUT, OFFSET_MAGIC, MAGIC);
            segment.set(INT_LAYOUT, OFFSET_VERSION, VERSION);
            segment.set(INT_LAYOUT, OFFSET_NUM_BITS, numBits);
            segment.set(INT_LAYOUT, OFFSET_DIMENSIONS, dimensions);
            segment.set(LONG_LAYOUT, OFFSET_SEED, seed);
            segment.set(LONG_LAYOUT, OFFSET_ENTRY_COUNT, 0L);
            segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, 0L);
            segment.set(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY, INITIAL_BUCKET_CAPACITY);
            segment.set(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET, entryStorageOffset.get());
            if (bucketDirOffset == HEADER_SIZE) {
                LshHashTableIntegrity.markOpen(segment);
            }

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Validates the header of an existing hash table file.
     *
     * @return The format version the file is written at
     * @throws IllegalArgumentException if the header is invalid
     */
    private int validateHeader() {
        lock.readLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();

            int magic = segment.get(INT_LAYOUT, OFFSET_MAGIC);
            if (magic != MAGIC) {
                throw new IllegalArgumentException(
                    "Invalid magic number: 0x" + Integer.toHexString(magic) +
                    " (expected 0x" + Integer.toHexString(MAGIC) + ")");
            }

            int version = segment.get(INT_LAYOUT, OFFSET_VERSION);
            if (!LshTableHeader.isSupportedVersion(version)) {
                throw new IllegalArgumentException(
                    "Unsupported version: " + version + " (expected " + VERSION + ")");
            }

            int storedNumBits = segment.get(INT_LAYOUT, OFFSET_NUM_BITS);
            if (storedNumBits != numBits) {
                throw new IllegalArgumentException(
                    "NumBits mismatch: " + storedNumBits + " (expected " + numBits + ")");
            }

            int storedDimensions = segment.get(INT_LAYOUT, OFFSET_DIMENSIONS);
            if (storedDimensions != dimensions) {
                throw new IllegalArgumentException(
                    "Dimensions mismatch: " + storedDimensions + " (expected " + dimensions + ")");
            }

            return version;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads the allocator cursor - the end of live entry storage - from the header.
     */
    private long readEntryStorageOffset() {
        MemorySegment segment = mappedFile.segment();
        return segment.get(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET);
    }

    /**
     * Inserts an entry into the hash table.
     *
     * @param vector  The vector to hash
     * @param entryId The entry ID to store
     * @throws IllegalArgumentException if vector is null or wrong size
     * @throws IOException if the insert fails due to I/O error
     */
    public void insert(float[] vector, long entryId) throws IOException {
        if (vector == null) {
            throw new IllegalArgumentException("vector cannot be null");
        }
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != dimensions " + dimensions);
        }
        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative, got: " + entryId);
        }

        long hash = hashFunction.hash(vector);

        lock.writeLock().lock();
        try {
            ensureCapacityForInsert();

            // Find or create bucket
            long bucketOffset = findOrCreateBucket(hash);

            if (bucketOffset < 0) {
                // Bucket directory is full, need to grow
                growBucketDirectory();
                bucketOffset = findOrCreateBucket(hash);

                if (bucketOffset < 0) {
                    // Doubling always exposes a never-occupied slot, so this cannot happen;
                    // fail loudly rather than write the entry at a negative offset.
                    throw new IllegalStateException(
                        "Bucket directory still full after growing it");
                }
            }

            // Allocate first: allocateEntry() can extend the file, and MappedFile.extend()
            // installs a brand new mapping, which closes the arena backing any segment
            // captured before the call. Every access below therefore uses a fresh segment.
            long newEntryOffset = allocateEntry();

            MemorySegment segment = mappedFile.segment();

            // Read current entry count and first entry offset
            int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);
            long firstEntryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);

            // Write new entry
            segment.set(LONG_LAYOUT, newEntryOffset + ENTRY_ID, entryId);
            segment.set(LONG_LAYOUT, newEntryOffset + ENTRY_NEXT_OFFSET, firstEntryOffset);
            segment.set(BYTE_LAYOUT, newEntryOffset + ENTRY_TOMBSTONE, (byte) 0);

            // Update bucket to point to new entry
            segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, newEntryOffset);
            segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, bucketEntryCount + 1);

            // Update header entry count
            long currentEntryCount = segment.get(LONG_LAYOUT, OFFSET_ENTRY_COUNT);
            segment.set(LONG_LAYOUT, OFFSET_ENTRY_COUNT, currentEntryCount + 1);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Finds or creates a bucket for the given hash.
     *
     * <p>The probe run for a hash ends at the first never-occupied slot
     * ({@code hash == 0 && count == 0}), which is exactly the terminator every read path
     * uses. A slot emptied by deletion keeps its hash and must therefore not be claimed
     * for a different hash until the run has been walked to that terminator - claiming it
     * early would leave two directory slots carrying the same hash, and the readers stop
     * at the first one.</p>
     *
     * @param hash The hash value
     * @return Offset to the bucket entry, or -1 if bucket directory is full
     */
    private long findOrCreateBucket(long hash) {
        MemorySegment segment = mappedFile.segment();
        long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);
        long bucketCount = segment.get(LONG_LAYOUT, OFFSET_BUCKET_COUNT);

        int startIndex = (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % bucketDirCapacity);
        long reusableOffset = -1; // First slot emptied by deletion, reused only as a fallback

        // Linear probe to find the bucket
        for (int i = 0; i < bucketDirCapacity; i++) {
            int index = (int) ((startIndex + i) % bucketDirCapacity);
            long bucketOffset = bucketDirOffset + (long) index * BUCKET_ENTRY_SIZE;

            long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
            int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

            if (bucketHash == 0 && bucketEntryCount == 0) {
                // Never-occupied slot: the hash is provably absent from this probe run,
                // so a slot emptied earlier in the run can now be safely recycled.
                long target = reusableOffset >= 0 ? reusableOffset : bucketOffset;
                initializeBucket(segment, target, hash);

                if (bucketCount < bucketDirCapacity) {
                    segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, bucketCount + 1);
                }

                return target;
            }

            if (bucketHash == hash) {
                // Found existing bucket. When it was emptied by deletion it is about to
                // become occupied again, so it counts as a live bucket once more.
                if (bucketEntryCount == 0 && bucketCount < bucketDirCapacity) {
                    segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, bucketCount + 1);
                }
                return bucketOffset;
            }

            if (bucketEntryCount == 0 && reusableOffset < 0) {
                // Emptied by deletion - remember it, but keep probing for a real match.
                reusableOffset = bucketOffset;
            }
        }

        // The run never reached a never-occupied slot. Recycle an emptied slot if the
        // scan found one, otherwise the directory is genuinely full.
        if (reusableOffset >= 0) {
            initializeBucket(segment, reusableOffset, hash);
            if (bucketCount < bucketDirCapacity) {
                segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, bucketCount + 1);
            }
            return reusableOffset;
        }

        return -1; // Bucket directory is full
    }

    /**
     * Claims a directory slot for a hash, discarding whatever it held before.
     */
    private static void initializeBucket(MemorySegment segment, long bucketOffset, long hash) {
        segment.set(LONG_LAYOUT, bucketOffset + BUCKET_HASH, hash);
        segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, 0L);
        segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, 0);
        segment.set(INT_LAYOUT, bucketOffset + BUCKET_RESERVED, 0);
    }

    /**
     * Doubles the bucket directory, moving the entry-storage region out of its way.
     *
     * <p>The write lock must be held: the file describes no valid table between the first
     * write of the move and the last. See {@link BucketDirectoryGrowth} for what the move
     * has to repair.</p>
     */
    private void growBucketDirectory() throws IOException {
        entryStorageOffset.set(BucketDirectoryGrowth.doubleCapacity(
            mappedFile, bucketDirOffset, entryStorageOffset.get(), DEFAULT_INITIAL_SIZE));
    }

    /**
     * Allocates space for a new entry.
     *
     * @return Offset to the new entry
     */
    private long allocateEntry() throws IOException {
        long offset = entryStorageOffset.getAndAdd(ENTRY_SIZE);
        long requiredSize = offset + ENTRY_SIZE;

        if (requiredSize > mappedFile.size()) {
            // Grow file
            long newSize = Math.max(requiredSize * 2, mappedFile.size() * 2);
            mappedFile.extend(newSize - mappedFile.size());
        }

        // Allocating is not complete until the cursor is durable. It is the only record of
        // where entry storage ends, nothing else in the file implies it, and a reopen that
        // restores it from anywhere else hands the next insert an offset on top of a record
        // already written there. Stamped after the extend, because extend() installs a new
        // mapping and closes the arena behind any segment captured before it.
        mappedFile.segment().set(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET, requiredSize);

        return offset;
    }

    /**
     * The most records a chain may visit before it is provably not a chain.
     *
     * <p>Every link is an absolute file offset. Damage that never reaches a checksum - a
     * version 2 file, a file a crash left unsealed, a growth step interrupted halfway - can
     * point one of them back into the chain, and an unbounded walk over that does not return
     * wrong results, it does not return at all: the query thread spins holding the read
     * lock, and the next close() waits on it for good. The entry region cannot hold more
     * records than this, so a walk that passes the bound is following a cycle.</p>
     *
     * @param segment The table's mapping
     * @return The bound, in records
     */
    private long chainLimit(MemorySegment segment) {
        long capacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);
        long regionStart = bucketDirOffset + capacity * BUCKET_ENTRY_SIZE;
        return Math.max(0, (segment.byteSize() - regionStart) / ENTRY_SIZE);
    }

    /**
     * Follows one link of a bucket's entry chain, refusing a chain that cannot be honest.
     *
     * @param segment     The table's mapping
     * @param entryOffset The record the chain is at
     * @param visited     How many records the walk has already passed
     * @param limit       The bound from {@link #chainLimit(MemorySegment)}
     * @return The next record's offset, or 0 at the end of the chain
     * @throws IllegalStateException if the walk has passed the bound
     */
    private long nextInChain(MemorySegment segment, long entryOffset, long visited,
                             long limit) {
        if (visited >= limit) {
            throw new IllegalStateException("LSH table " + path() + " has a bucket chain "
                + "longer than the " + limit + " records its entry region can hold, so the "
                + "chain loops back on itself. The table is corrupt; the query is refused "
                + "rather than left to spin on it. Restore the file from a backup or "
                + "reindex this shard.");
        }
        return segment.get(LONG_LAYOUT, entryOffset + ENTRY_NEXT_OFFSET);
    }

    /**
     * Ensures there is capacity for a new insert.
     */
    private void ensureCapacityForInsert() throws IOException {
        MemorySegment segment = mappedFile.segment();
        long bucketCount = segment.get(LONG_LAYOUT, OFFSET_BUCKET_COUNT);
        long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);

        if (bucketCount >= bucketDirCapacity * LOAD_FACTOR_THRESHOLD) {
            growBucketDirectory();
        }
    }

    /**
     * Queries for candidate entry IDs using multi-probe.
     *
     * @param vector    The query vector
     * @param numProbes Number of probes (including base hash)
     * @return Set of candidate entry IDs
     * @throws IllegalArgumentException if vector is null or wrong size
     */
    public Set<Long> query(float[] vector, int numProbes) {
        if (vector == null) {
            throw new IllegalArgumentException("vector cannot be null");
        }
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != dimensions " + dimensions);
        }

        long baseHash = hashFunction.hash(vector);
        List<Long> probes = probeGenerator.generateProbingSequence(baseHash, numProbes);

        Set<Long> candidates = new HashSet<>();

        lock.readLock().lock();
        try {
            for (long probeHash : probes) {
                collectEntriesFromBucket(probeHash, candidates);
            }
        } finally {
            lock.readLock().unlock();
        }

        return candidates;
    }

    /**
     * Collects entries from a bucket matching the given hash.
     */
    private void collectEntriesFromBucket(long hash, Set<Long> candidates) {
        MemorySegment segment = mappedFile.segment();
        long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);

        int startIndex = (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % bucketDirCapacity);

        // Linear probe to find the bucket
        for (int i = 0; i < bucketDirCapacity; i++) {
            int index = (int) ((startIndex + i) % bucketDirCapacity);
            long bucketOffset = bucketDirOffset + (long) index * BUCKET_ENTRY_SIZE;

            long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
            int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

            if (bucketHash == 0 && bucketEntryCount == 0) {
                // Never-occupied slot - bucket not found.
                // Note: a slot with hash!=0 but count==0 was emptied by deletion
                // and must NOT terminate the probe (items may exist past it).
                return;
            }
            if (bucketHash == hash) {
                // Found bucket - collect all entries
                long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);
                long limit = chainLimit(segment);
                long visited = 0;

                while (entryOffset != 0) {
                    byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE);
                    if (tombstone == 0) {
                        long entryId = segment.get(LONG_LAYOUT, entryOffset + ENTRY_ID);
                        candidates.add(entryId);
                    }

                    entryOffset = nextInChain(segment, entryOffset, visited++, limit);
                }

                return;
            }
        }
    }

    /**
     * Queries for candidates using only the exact hash (no multi-probe).
     *
     * @param vector The query vector
     * @return Set of candidate entry IDs (may be empty)
     */
    public Set<Long> queryExact(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector cannot be null");
        }
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != dimensions " + dimensions);
        }

        long hash = hashFunction.hash(vector);
        Set<Long> candidates = new HashSet<>();

        lock.readLock().lock();
        try {
            collectEntriesFromBucket(hash, candidates);
        } finally {
            lock.readLock().unlock();
        }

        return candidates;
    }

    /**
     * Removes an entry ID from all buckets.
     * Used for tombstone propagation.
     *
     * @param entryId The entry ID to remove
     * @return true if the entry was found and removed
     */
    public boolean delete(long entryId) {
        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative, got: " + entryId);
        }

        lock.writeLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();
            long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);
            long bucketCount = segment.get(LONG_LAYOUT, OFFSET_BUCKET_COUNT);
            boolean found = false;
            int deletedCount = 0;
            long limit = chainLimit(segment);

            // Scan all buckets
            for (int i = 0; i < bucketDirCapacity; i++) {
                long bucketOffset = bucketDirOffset + (long) i * BUCKET_ENTRY_SIZE;
                int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                if (bucketEntryCount == 0) {
                    continue;
                }

                long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);
                long visited = 0;

                while (entryOffset != 0) {
                    byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE);
                    long currentEntryId = segment.get(LONG_LAYOUT, entryOffset + ENTRY_ID);

                    if (tombstone == 0 && currentEntryId == entryId) {
                        // Mark as tombstone
                        segment.set(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE, (byte) 1);
                        found = true;
                        deletedCount++;

                        // Update bucket entry count
                        int newBucketEntryCount = bucketEntryCount - 1;
                        segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, newBucketEntryCount);
                        bucketEntryCount = newBucketEntryCount;

                        // If bucket is now empty, decrement bucket count
                        if (newBucketEntryCount == 0) {
                            bucketCount--;
                        }
                    }

                    entryOffset = nextInChain(segment, entryOffset, visited++, limit);
                }
            }

            if (deletedCount > 0) {
                // Update global entry count
                long currentEntryCount = segment.get(LONG_LAYOUT, OFFSET_ENTRY_COUNT);
                segment.set(LONG_LAYOUT, OFFSET_ENTRY_COUNT, Math.max(0, currentEntryCount - deletedCount));

                // Update bucket count if buckets became empty
                segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, bucketCount);
            }

            return found;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes an entry ID from a specific hash bucket.
     *
     * @param hash    The hash value
     * @param entryId The entry ID to remove
     * @return true if the entry was found and removed
     */
    public boolean deleteFromBucket(long hash, long entryId) {
        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative, got: " + entryId);
        }

        lock.writeLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();
            long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);

            int startIndex = (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % bucketDirCapacity);

            // Linear probe to find the bucket
            for (int i = 0; i < bucketDirCapacity; i++) {
                int index = (int) ((startIndex + i) % bucketDirCapacity);
                long bucketOffset = bucketDirOffset + (long) index * BUCKET_ENTRY_SIZE;

                long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
                int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                if (bucketHash == 0 && bucketEntryCount == 0) {
                    return false; // Never-occupied slot - bucket not found
                }

                if (bucketHash == hash) {
                    // Found bucket - search for entry
                    long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);
                    long limit = chainLimit(segment);
                    long visited = 0;

                    while (entryOffset != 0) {
                        byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE);
                        long currentEntryId = segment.get(LONG_LAYOUT, entryOffset + ENTRY_ID);

                        if (tombstone == 0 && currentEntryId == entryId) {
                            // Mark as tombstone
                            segment.set(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE, (byte) 1);

                            // Update bucket entry count
                            int newBucketEntryCount = bucketEntryCount - 1;
                            segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, newBucketEntryCount);

                            // If bucket is now empty, decrement bucket count
                            if (newBucketEntryCount == 0) {
                                long bucketCount = segment.get(LONG_LAYOUT, OFFSET_BUCKET_COUNT);
                                segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, bucketCount - 1);
                            }

                            // Update global entry count
                            long currentEntryCount = segment.get(LONG_LAYOUT, OFFSET_ENTRY_COUNT);
                            segment.set(LONG_LAYOUT, OFFSET_ENTRY_COUNT, Math.max(0, currentEntryCount - 1));

                            return true;
                        }

                        entryOffset = nextInChain(segment, entryOffset, visited++, limit);
                    }

                    return false;
                }
            }

            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a specific hash bucket contains an entry.
     *
     * @param hash    The hash value
     * @param entryId The entry ID to check
     * @return true if the entry exists in the bucket
     */
    public boolean contains(long hash, long entryId) {
        lock.readLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();
            long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);

            int startIndex = (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % bucketDirCapacity);

            for (int i = 0; i < bucketDirCapacity; i++) {
                int index = (int) ((startIndex + i) % bucketDirCapacity);
                long bucketOffset = bucketDirOffset + (long) index * BUCKET_ENTRY_SIZE;

                long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
                int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                if (bucketHash == 0 && bucketEntryCount == 0) {
                    return false; // Never-occupied slot
                }

                if (bucketHash == hash) {
                    long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);
                    long limit = chainLimit(segment);
                    long visited = 0;

                    while (entryOffset != 0) {
                        byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE);
                        long currentEntryId = segment.get(LONG_LAYOUT, entryOffset + ENTRY_ID);

                        if (tombstone == 0 && currentEntryId == entryId) {
                            return true;
                        }

                        entryOffset = nextInChain(segment, entryOffset, visited++, limit);
                    }

                    return false;
                }
            }

            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of entries in this table.
     *
     * @return The entry count
     */
    public long entryCount() {
        lock.readLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();
            return segment.get(LONG_LAYOUT, OFFSET_ENTRY_COUNT);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of non-empty buckets.
     *
     * @return The bucket count
     */
    public int bucketCount() {
        lock.readLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();
            return (int) segment.get(LONG_LAYOUT, OFFSET_BUCKET_COUNT);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the hash function used by this table.
     *
     * @return The hash function
     */
    public RandomHyperplaneHash hashFunction() {
        return hashFunction;
    }

    /**
     * Returns the number of hash bits.
     *
     * @return The number of bits
     */
    public int numBits() {
        return numBits;
    }

    /**
     * Returns the vector dimensions.
     *
     * @return The dimensions
     */
    public int dimensions() {
        return dimensions;
    }

    /**
     * Clears all entries from this table.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            MemorySegment segment = mappedFile.segment();

            // Reset entry count
            segment.set(LONG_LAYOUT, OFFSET_ENTRY_COUNT, 0L);
            segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, 0L);

            // Clear bucket directory
            long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);
            for (int i = 0; i < bucketDirCapacity; i++) {
                long bucketOffset = bucketDirOffset + (long) i * BUCKET_ENTRY_SIZE;
                segment.set(LONG_LAYOUT, bucketOffset + BUCKET_HASH, 0L);
                segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, 0L);
                segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, 0);
            }

            // Restart the bump allocator behind the CURRENT directory. The directory may
            // have been grown past its initial capacity, and resetting to the initial end
            // would hand out entry offsets that land on top of live directory slots.
            long entryStorageBase = bucketDirOffset + bucketDirCapacity * BUCKET_ENTRY_SIZE;
            entryStorageOffset.set(entryStorageBase);
            segment.set(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET, entryStorageBase);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Flushes all changes to the underlying storage.
     */
    public void flush() {
        lock.writeLock().lock();
        try {
            if (mappedFile != null && !mappedFile.isClosed()) {
                mappedFile.flush();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Closes this hash table and releases all resources.
     */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            if (mappedFile == null || mappedFile.isClosed()) {
                return;
            }
            // Sealing is not tied to ownership, because breaking the seal is not either.
            // Attaching to a mapping this table does not own still marks the file open, so
            // walking away without re-sealing leaves it reported unsealed for good - the
            // borrower downgrades a file it was only reading. Whoever broke it restores it;
            // only the close of the mapping itself belongs to the owner.
            sealIntegrity();
            if (ownsMappedFile) {
                mappedFile.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if this hash table is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Verifies a table file on disk without mapping it or attaching a table to it.
     *
     * <p>The checking entry point for this format. It reads the header and streams the bucket
     * directory and entry storage past a digest, so a file too damaged to serve can still be
     * judged - which is what a checking tool needs and what an open cannot offer.</p>
     *
     * @param path The table file to verify
     * @return What the check concluded, including whether the file carries a checksum at all
     * @throws NullPointerException if path is null
     */
    public static IntegrityReport verifyIntegrity(Path path) {
        return LshHashTableIntegrity.verify(path);
    }

    /**
     * Refuses a mapping whose stored checksums no longer describe it.
     *
     * @param version The format version read from the header
     * @throws IllegalArgumentException wrapping the refusal, because the constructors that
     *         reach this point cannot declare a checked exception
     */
    private void requireIntact(int version) {
        try {
            LshHashTableIntegrity.requireIntact(
                mappedFile.segment(), mappedFile.path(), version, bucketDirOffset);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Records that this process is writing to the table, so its stored checksums lapse.
     *
     * <p>Once per attach rather than on every insert: an insert touches a bucket row, an
     * entry record and three counters, and a checksum cannot be held current against that.
     * A table left open is reported unsealed rather than intact.</p>
     */
    private void breakSeal() {
        if (bucketDirOffset != HEADER_SIZE || mappedFile == null || mappedFile.isReadOnly()) {
            return;
        }
        LshHashTableIntegrity.markOpen(mappedFile.segment());

        // Forced while the mapping is still clean, so the cleared seal cannot reach storage
        // after the writes it disclaims. A crash in that window would leave a sound table
        // claiming a checksum it no longer matches, which the next open would refuse.
        mappedFile.flush();
    }

    /**
     * Covers the table with checksums a later open can check.
     *
     * <p>On close rather than on flush. Sealing digests the whole live region, and flush runs
     * on the ingestion path, so sealing there would put a full pass over the table into every
     * batch. A version 2 file is left alone: it has no integrity block, and writing one now
     * would vouch for bytes that have never been covered by a checksum.</p>
     */
    private void sealIntegrity() {
        if (bucketDirOffset != HEADER_SIZE || mappedFile.isReadOnly()) {
            return;
        }
        LshHashTableIntegrity.seal(
            mappedFile.segment(), bucketDirOffset, entryStorageOffset.get());
    }

    /**
     * Returns the underlying MemorySegment for direct access.
     * Use with caution - this bypasses thread-safety mechanisms.
     *
     * @return The memory segment
     */
    public MemorySegment segment() {
        lock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("LshHashTable is closed");
            }
            return mappedFile.segment();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the path to the backing file, or null if in-memory.
     *
     * @return The file path
     */
    public Path path() {
        return mappedFile != null ? mappedFile.path() : null;
    }

    /**
     * Serializes this hash table to a MemorySegment.
     * Note: This creates a copy of the data, not a direct reference.
     *
     * @param arena The arena to allocate from
     * @return The memory segment containing serialized data
     */
    public MemorySegment toMemorySegment(Arena arena) {
        lock.readLock().lock();
        try {
            MemorySegment source = mappedFile.segment();
            long size = entryStorageOffset.get();
            MemorySegment dest = arena.allocate(size);
            MemorySegment.copy(source, 0, dest, 0, size);
            if (bucketDirOffset == HEADER_SIZE) {
                LshHashTableIntegrity.seal(dest, bucketDirOffset, size);
            }
            return dest;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Deserializes a hash table from a MemorySegment into a new in-memory table.
     *
     * @param segment The memory segment to read from
     * @return A new LshHashTable instance
     * @throws IllegalArgumentException if the segment is invalid
     */
    public static LshHashTable fromMemorySegment(MemorySegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("segment cannot be null");
        }
        LshTableHeader header = LshTableHeader.read(segment);

        // The image toMemorySegment writes is sealed. Checking that seal here is what makes
        // writing it worth anything: without this, a flipped byte round-trips accepted and
        // the checksum is decoration.
        try {
            LshHashTableIntegrity.requireIntact(
                segment, null, header.version(), header.bucketDirOffset());
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }

        // Laid out the way the source is, not the way this build writes: the copy below
        // lands the source's bucket directory at the source's offset, and a destination
        // that looks for it anywhere else reads bucket rows out of nothing.
        LshHashTable table = new LshHashTable(header.dimensions(), header.numBits(),
            header.seed(), header.bucketDirOffset());
        table.copyImageIn(segment);
        return table;
    }

    /**
     * Overwrites this table with an image laid out the way this table is.
     *
     * @param source The image to copy in
     * @throws RuntimeException if the backing file cannot be grown to hold the image
     */
    private void copyImageIn(MemorySegment source) {
        lock.writeLock().lock();
        try {
            MemorySegment dest = mappedFile.segment();
            if (dest.byteSize() < source.byteSize()) {
                mappedFile.extend(source.byteSize() - dest.byteSize());
                dest = mappedFile.segment();
            }

            MemorySegment.copy(source, 0, dest, 0, source.byteSize());

            // The allocator cursor comes from the header, at every version. Deriving one
            // from the entry count instead puts it inside the bucket directory, where the
            // next insert overwrites bucket rows and turns a chain head into a cycle.
            entryStorageOffset.set(dest.get(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET));

            // This table is now live over the copy, so the seal the copy carried no longer
            // describes it.
            breakSeal();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize LSH hash table", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Saves this hash table to the specified path.
     * Creates a copy of the current state in a new file.
     *
     * @param path The path to save to
     * @throws IOException if the save fails
     */
    public void save(Path path) throws IOException {
        lock.readLock().lock();
        try {
            // Create parent directories if needed
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Get current data size
            MemorySegment source = mappedFile.segment();
            long size = entryStorageOffset.get();

            // Create new file and copy data
            try (MappedFile newFile = MappedFile.create(path, size)) {
                MemorySegment dest = newFile.segment();
                MemorySegment.copy(source, 0, dest, 0, size);

                // The copy is sealed, not this table: this table stays live, and a checksum
                // over it would be stale before the next insert returns.
                if (bucketDirOffset == HEADER_SIZE) {
                    LshHashTableIntegrity.seal(dest, bucketDirOffset, size);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Loads a hash table from the specified path.
     *
     * @param path The path to load from
     * @return A new LshHashTable instance
     * @throws IOException if the load fails
     */
    public static LshHashTable load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }

        long size = Files.size(path);
        if (size < HEADER_SIZE_V2) {
            throw new IOException("File too small: " + size);
        }

        // Open the file. Every failure from here on has to release it: the constructor
        // below throws for a header this build will not accept, and an exception that
        // escapes with the mapping still open leaks the mapping and the descriptor.
        MappedFile mappedFile = MappedFile.open(path, size);
        try {
            LshTableHeader header = LshTableHeader.read(mappedFile.segment());

            // The existing MappedFile is handed over: load() opened it, so the returned
            // table owns it and close() must release the mapping and descriptor. The
            // constructor reads the allocator cursor out of the header, at every version.
            return new LshHashTable(mappedFile, header.dimensions(), header.numBits(),
                header.seed(), true);
        } catch (RuntimeException e) {
            mappedFile.close();
            throw new IOException("Cannot load " + LshHashTableIntegrity.FORMAT + " file "
                + path + ": " + e.getMessage(), e);
        }
    }


    /**
     * Serializes this hash table to a byte array.
     * Note: This creates a copy of the data.
     *
     * @return The serialized bytes
     */
    public byte[] toBytes() {
        lock.readLock().lock();
        try {
            MemorySegment source = mappedFile.segment();
            long size = entryStorageOffset.get();
            return source.asSlice(0, size).toArray(BYTE_LAYOUT);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return String.format("LshHashTable[numBits=%d, buckets=%d, entries=%d, path=%s]",
            numBits, bucketCount(), entryCount(),
            mappedFile != null ? mappedFile.path() : "memory");
    }
}
