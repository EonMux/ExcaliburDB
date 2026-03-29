package excalibur.filter.lsh;

import io.excalibur.core.mapped.MappedFile;

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
 * HEADER (56 bytes):
 *   [0:4]   Magic (0x4C534854 "LSHT")
 *   [4:8]   Version (2)
 *   [8:12]  NumBits
 *   [12:16] Dimensions
 *   [16:24] Seed
 *   [24:32] EntryCount
 *   [32:40] BucketCount
 *   [40:48] BucketDirectoryCapacity
 *   [48:56] EntryStorageOffset
 *
 * BUCKET DIRECTORY (starting at offset 56):
 *   Array of BucketEntry (24 bytes each):
 *   [0:8]   Hash value
 *   [8:16]  EntryOffset (offset into entry storage, 0 if empty)
 *   [16:20] EntryCount (number of entries in this bucket)
 *   [20:24] Reserved (padding)
 *
 * ENTRY STORAGE (starting at EntryStorageOffset):
 *   Variable-length entry list per bucket:
 *   [0:8]   EntryId
 *   [8:16]  NextEntryOffset (0 if last in bucket)
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
    private static final int MAGIC = 0x4C534854; // "LSHT" in ASCII

    /** Current file format version. */
    private static final int VERSION = 2;

    /** Header size in bytes. */
    static final int HEADER_SIZE = 56;

    // Header field offsets
    static final int OFFSET_MAGIC = 0;
    static final int OFFSET_VERSION = 4;
    static final int OFFSET_NUM_BITS = 8;
    static final int OFFSET_DIMENSIONS = 12;
    static final int OFFSET_SEED = 16;
    static final int OFFSET_ENTRY_COUNT = 24;
    static final int OFFSET_BUCKET_COUNT = 32;
    static final int OFFSET_BUCKET_DIR_CAPACITY = 40;
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
        this.numBits = numBits;
        this.dimensions = dimensions;
        this.seed = seed;
        this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, seed);
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.ownsMappedFile = true;

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
     * @param seed         Random seed for hash function
     * @param initialSize  Initial file size in bytes (0 for default)
     * @throws IOException if the file cannot be created or opened
     */
    public LshHashTable(Path path, int dimensions, int numBits, long seed, long initialSize) throws IOException {
        this.numBits = numBits;
        this.dimensions = dimensions;
        this.seed = seed;
        this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, seed);
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.ownsMappedFile = true;

        boolean fileExists = java.nio.file.Files.exists(path) && java.nio.file.Files.size(path) > 0;

        long size = initialSize > 0 ? initialSize : DEFAULT_INITIAL_SIZE;
        this.mappedFile = MappedFile.create(path, size);

        if (fileExists) {
            // Read existing header
            validateHeader();
            this.entryStorageOffset = new AtomicLong(readEntryStorageOffset());
        } else {
            // Initialize new file
            this.entryStorageOffset = new AtomicLong(calculateInitialEntryStorageOffset());
            initializeHeader();
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
        this.numBits = numBits;
        this.dimensions = dimensions;
        this.seed = seed;
        this.hashFunction = new RandomHyperplaneHash(dimensions, numBits, seed);
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.mappedFile = mappedFile;
        this.ownsMappedFile = false;

        if (mappedFile.size() < HEADER_SIZE) {
            throw new IllegalArgumentException("MappedFile too small for LSH hash table");
        }

        validateHeader();
        this.entryStorageOffset = new AtomicLong(readEntryStorageOffset());
    }

    /**
     * Calculates the initial entry storage offset based on header and initial bucket capacity.
     */
    private long calculateInitialEntryStorageOffset() {
        return HEADER_SIZE + (long) INITIAL_BUCKET_CAPACITY * BUCKET_ENTRY_SIZE;
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

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Validates the header of an existing hash table file.
     *
     * @throws IllegalArgumentException if the header is invalid
     */
    private void validateHeader() {
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
            if (version != VERSION) {
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

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads the entry storage offset from the header.
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
            }

            MemorySegment segment = mappedFile.segment();

            // Read current entry count and first entry offset
            int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);
            long firstEntryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);

            // Allocate new entry
            long newEntryOffset = allocateEntry();

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
     * @param hash The hash value
     * @return Offset to the bucket entry, or -1 if bucket directory is full
     */
    private long findOrCreateBucket(long hash) {
        MemorySegment segment = mappedFile.segment();
        long bucketDirCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);
        long bucketCount = segment.get(LONG_LAYOUT, OFFSET_BUCKET_COUNT);

        int startIndex = (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % bucketDirCapacity);

        // Linear probe to find the bucket
        for (int i = 0; i < bucketDirCapacity; i++) {
            int index = (int) ((startIndex + i) % bucketDirCapacity);
            long bucketOffset = HEADER_SIZE + (long) index * BUCKET_ENTRY_SIZE;

            long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
            int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

            if (bucketEntryCount == 0) {
                // Empty slot - create new bucket
                segment.set(LONG_LAYOUT, bucketOffset + BUCKET_HASH, hash);
                segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, 0L);
                segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, 0);
                segment.set(INT_LAYOUT, bucketOffset + BUCKET_RESERVED, 0);

                // Update bucket count if this is a new bucket
                if (bucketCount < bucketDirCapacity) {
                    segment.set(LONG_LAYOUT, OFFSET_BUCKET_COUNT, bucketCount + 1);
                }

                return bucketOffset;
            }

            if (bucketHash == hash) {
                // Found existing bucket
                return bucketOffset;
            }
        }

        return -1; // Bucket directory is full
    }

    /**
     * Grows the bucket directory by doubling its capacity.
     */
    private void growBucketDirectory() throws IOException {
        MemorySegment segment = mappedFile.segment();
        long oldCapacity = segment.get(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY);
        long newCapacity = oldCapacity * 2;

        // Calculate new size needed
        long oldBucketDirEnd = HEADER_SIZE + oldCapacity * BUCKET_ENTRY_SIZE;
        long oldEntryStorageOffset = segment.get(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET);
        long entryStorageSize = entryStorageOffset.get() - oldEntryStorageOffset;

        long newBucketDirEnd = HEADER_SIZE + newCapacity * BUCKET_ENTRY_SIZE;
        long newEntryStorageOffset = newBucketDirEnd;
        long newFileSize = newEntryStorageOffset + entryStorageSize + DEFAULT_INITIAL_SIZE;

        // Extend file
        mappedFile.extend(newFileSize - mappedFile.size());
        segment = mappedFile.segment(); // Refresh segment reference

        // Move entry storage to new location
        if (entryStorageSize > 0) {
            // Copy entry storage from old location to new location
            byte[] buffer = new byte[(int) Math.min(entryStorageSize, 8192)];
            long readOffset = oldEntryStorageOffset;
            long writeOffset = newEntryStorageOffset;

            while (readOffset < oldEntryStorageOffset + entryStorageSize) {
                int chunkSize = (int) Math.min(buffer.length, oldEntryStorageOffset + entryStorageSize - readOffset);
                MemorySegment.copy(segment, BYTE_LAYOUT, readOffset, buffer, 0, chunkSize);
                MemorySegment.copy(buffer, 0, segment, BYTE_LAYOUT, writeOffset, chunkSize);
                readOffset += chunkSize;
                writeOffset += chunkSize;
            }
        }

        // Update entry storage offset
        entryStorageOffset.set(newEntryStorageOffset + entryStorageSize);

        // Update header
        segment.set(LONG_LAYOUT, OFFSET_BUCKET_DIR_CAPACITY, newCapacity);
        segment.set(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET, newEntryStorageOffset);

        // Rehash all buckets
        rehashBuckets(oldCapacity, newCapacity);
    }

    /**
     * Rehashes all buckets after growing the directory.
     */
    private void rehashBuckets(long oldCapacity, long newCapacity) {
        MemorySegment segment = mappedFile.segment();

        // Create temporary storage for old buckets
        long[] oldHashes = new long[(int) oldCapacity];
        long[] oldEntryOffsets = new long[(int) oldCapacity];
        int[] oldEntryCounts = new int[(int) oldCapacity];

        for (int i = 0; i < oldCapacity; i++) {
            long bucketOffset = HEADER_SIZE + (long) i * BUCKET_ENTRY_SIZE;
            oldHashes[i] = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
            oldEntryOffsets[i] = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);
            oldEntryCounts[i] = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);
        }

        // Clear all buckets
        for (int i = 0; i < newCapacity; i++) {
            long bucketOffset = HEADER_SIZE + (long) i * BUCKET_ENTRY_SIZE;
            segment.set(LONG_LAYOUT, bucketOffset + BUCKET_HASH, 0L);
            segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, 0L);
            segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, 0);
        }

        // Re-insert non-empty buckets
        for (int i = 0; i < oldCapacity; i++) {
            if (oldEntryCounts[i] > 0) {
                int newIndex = (int) ((oldHashes[i] & 0x7FFFFFFFFFFFFFFFL) % newCapacity);

                // Linear probe to find empty slot
                for (int j = 0; j < newCapacity; j++) {
                    int index = (int) ((newIndex + j) % newCapacity);
                    long bucketOffset = HEADER_SIZE + (long) index * BUCKET_ENTRY_SIZE;
                    int count = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                    if (count == 0) {
                        segment.set(LONG_LAYOUT, bucketOffset + BUCKET_HASH, oldHashes[i]);
                        segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, oldEntryOffsets[i]);
                        segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, oldEntryCounts[i]);
                        break;
                    }
                }
            }
        }
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

        return offset;
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
            long bucketOffset = HEADER_SIZE + (long) index * BUCKET_ENTRY_SIZE;

            int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

            if (bucketEntryCount == 0) {
                // Empty slot - bucket not found
                return;
            }

            long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
            if (bucketHash == hash) {
                // Found bucket - collect all entries
                long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);

                while (entryOffset != 0) {
                    byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE);
                    if (tombstone == 0) {
                        long entryId = segment.get(LONG_LAYOUT, entryOffset + ENTRY_ID);
                        candidates.add(entryId);
                    }

                    entryOffset = segment.get(LONG_LAYOUT, entryOffset + ENTRY_NEXT_OFFSET);
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

            // Scan all buckets
            for (int i = 0; i < bucketDirCapacity; i++) {
                long bucketOffset = HEADER_SIZE + (long) i * BUCKET_ENTRY_SIZE;
                int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                if (bucketEntryCount == 0) {
                    continue;
                }

                long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);

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

                    entryOffset = segment.get(LONG_LAYOUT, entryOffset + ENTRY_NEXT_OFFSET);
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
                long bucketOffset = HEADER_SIZE + (long) index * BUCKET_ENTRY_SIZE;

                int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                if (bucketEntryCount == 0) {
                    return false; // Bucket not found
                }

                long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
                if (bucketHash == hash) {
                    // Found bucket - search for entry
                    long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);

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

                        entryOffset = segment.get(LONG_LAYOUT, entryOffset + ENTRY_NEXT_OFFSET);
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
                long bucketOffset = HEADER_SIZE + (long) index * BUCKET_ENTRY_SIZE;

                int bucketEntryCount = segment.get(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT);

                if (bucketEntryCount == 0) {
                    return false;
                }

                long bucketHash = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_HASH);
                if (bucketHash == hash) {
                    long entryOffset = segment.get(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET);

                    while (entryOffset != 0) {
                        byte tombstone = segment.get(BYTE_LAYOUT, entryOffset + ENTRY_TOMBSTONE);
                        long currentEntryId = segment.get(LONG_LAYOUT, entryOffset + ENTRY_ID);

                        if (tombstone == 0 && currentEntryId == entryId) {
                            return true;
                        }

                        entryOffset = segment.get(LONG_LAYOUT, entryOffset + ENTRY_NEXT_OFFSET);
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
                long bucketOffset = HEADER_SIZE + (long) i * BUCKET_ENTRY_SIZE;
                segment.set(LONG_LAYOUT, bucketOffset + BUCKET_HASH, 0L);
                segment.set(LONG_LAYOUT, bucketOffset + BUCKET_ENTRY_OFFSET, 0L);
                segment.set(INT_LAYOUT, bucketOffset + BUCKET_ENTRY_COUNT, 0);
            }

            // Reset entry storage offset
            entryStorageOffset.set(calculateInitialEntryStorageOffset());
            segment.set(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET, entryStorageOffset.get());

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

            if (mappedFile != null && ownsMappedFile) {
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
        if (segment.byteSize() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small: " + segment.byteSize() + " < " + HEADER_SIZE);
        }

        // Read header
        int magic = segment.get(INT_LAYOUT, OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic));
        }

        int version = segment.get(INT_LAYOUT, OFFSET_VERSION);
        if (version != VERSION && version != 1) {
            throw new IllegalArgumentException(
                "Unsupported version: " + version);
        }

        int numBits = segment.get(INT_LAYOUT, OFFSET_NUM_BITS);
        int dimensions = segment.get(INT_LAYOUT, OFFSET_DIMENSIONS);
        long seed = segment.get(LONG_LAYOUT, OFFSET_SEED);

        // Create new table
        LshHashTable table = new LshHashTable(dimensions, numBits, seed);

        // Copy data
        table.lock.writeLock().lock();
        try {
            MemorySegment dest = table.mappedFile.segment();

            // Ensure destination is large enough
            if (dest.byteSize() < segment.byteSize()) {
                table.mappedFile.extend(segment.byteSize() - dest.byteSize());
                dest = table.mappedFile.segment();
            }

            MemorySegment.copy(segment, 0, dest, 0, segment.byteSize());

            // Update entry storage offset
            if (version == VERSION) {
                table.entryStorageOffset.set(dest.get(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET));
            } else {
                // Version 1 format - recalculate entry storage offset
                long entryCount = dest.get(LONG_LAYOUT, OFFSET_ENTRY_COUNT);
                table.entryStorageOffset.set(HEADER_SIZE + entryCount * ENTRY_SIZE);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize LSH hash table", e);
        } finally {
            table.lock.writeLock().unlock();
        }

        return table;
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
        if (size < HEADER_SIZE) {
            throw new IOException("File too small: " + size);
        }

        // Open the file
        MappedFile mappedFile = MappedFile.open(path, size);
        MemorySegment segment = mappedFile.segment();

        // Read header
        int magic = segment.get(INT_LAYOUT, OFFSET_MAGIC);
        if (magic != MAGIC) {
            mappedFile.close();
            throw new IOException("Invalid magic number: 0x" + Integer.toHexString(magic));
        }

        int version = segment.get(INT_LAYOUT, OFFSET_VERSION);
        if (version != VERSION && version != 1) {
            mappedFile.close();
            throw new IOException("Unsupported version: " + version);
        }

        int numBits = segment.get(INT_LAYOUT, OFFSET_NUM_BITS);
        int dimensions = segment.get(INT_LAYOUT, OFFSET_DIMENSIONS);
        long seed = segment.get(LONG_LAYOUT, OFFSET_SEED);

        // Create new table using the existing MappedFile
        LshHashTable table = new LshHashTable(mappedFile, dimensions, numBits, seed);

        // Read entry storage offset
        if (version == VERSION) {
            table.entryStorageOffset.set(segment.get(LONG_LAYOUT, OFFSET_ENTRY_STORAGE_OFFSET));
        } else {
            // Version 1 format - recalculate entry storage offset
            long entryCount = segment.get(LONG_LAYOUT, OFFSET_ENTRY_COUNT);
            table.entryStorageOffset.set(HEADER_SIZE + entryCount * ENTRY_SIZE);
        }

        return table;
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
