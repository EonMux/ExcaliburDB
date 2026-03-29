package excalibur.filter.lsh;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main LSH index combining multiple hash tables for approximate nearest neighbor search.
 *
 * <h2>Overview</h2>
 * <p>The LshIndex is the main entry point for LSH-based approximate nearest neighbor search.
 * It combines multiple hash tables, each with independent hash functions, to achieve
 * high recall while maintaining sub-linear query time.</p>
 *
 * <h2>Algorithm</h2>
 * <ul>
 *   <li><strong>Insert</strong>: Insert vector into all hash tables</li>
 *   <li><strong>Query</strong>: Query all tables, union candidates, return unique entry IDs</li>
 *   <li><strong>Delete</strong>: Remove entry ID from all buckets (for tombstone propagation)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Uses striped locking (64 stripes) for write operations,
 * following the same pattern as CuckooFilter. Reads are lock-free.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li><strong>Hash tables</strong>: Each table uses a different random seed for independent hashing</li>
 *   <li><strong>Candidate union</strong>: Queries return union of candidates from all tables</li>
 *   <li><strong>Entry IDs</strong>: Non-negative long values representing document/entry identifiers</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class LshIndex {

    /** Magic number for serialization validation. */
    private static final int MAGIC = 0x4C534849; // "LSHI" in ASCII

    /** Current serialization version. */
    private static final int VERSION = 1;

    /** Configuration for this index. */
    private final LshConfig config;

    /** Hash tables. */
    private final LshHashTable[] tables;

    /** Entry counter. */
    private final AtomicLong entryCount;

    /** Striped locks for write operations (shared across all tables). */
    private final ReentrantLock[] locks;

    /** Number of lock stripes. */
    private static final int NUM_LOCKS = 64;

    /**
     * Creates a new LshIndex with the specified configuration.
     *
     * @param config The LSH configuration
     * @throws IllegalArgumentException if config is null
     */
    public LshIndex(LshConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        this.config = config;
        this.tables = new LshHashTable[config.numHashTables()];
        this.entryCount = new AtomicLong(0);

        // Initialize hash tables with different seeds
        // Each table gets a unique seed derived from the base seed
        for (int i = 0; i < config.numHashTables(); i++) {
            long tableSeed = config.seed() + i * 0x9E3779B97F4A7C15L; // Golden ratio constant
            tables[i] = new LshHashTable(
                config.dimensions(),
                config.numHashFunctions(),
                tableSeed
            );
        }

        // Initialize striped locks
        this.locks = new ReentrantLock[NUM_LOCKS];
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Creates a new LshIndex with default configuration for the specified dimensions.
     *
     * @param dimensions Vector dimensionality (e.g., 768 for OpenAI embeddings)
     * @return A new LshIndex instance
     */
    public static LshIndex create(int dimensions) {
        return new LshIndex(new LshConfig(dimensions));
    }

    /**
     * Creates a new LshIndex with the specified dimensions and number of tables.
     *
     * @param dimensions    Vector dimensionality
     * @param numHashTables Number of hash tables
     * @return A new LshIndex instance
     */
    public static LshIndex create(int dimensions, int numHashTables) {
        return new LshIndex(LshConfig.builder(dimensions)
            .numHashTables(numHashTables)
            .build());
    }

    /**
     * Inserts a vector into the index with the specified entry ID.
     *
     * @param vector  The vector to insert (must have length == dimensions)
     * @param entryId The entry ID (must be non-negative)
     * @throws IllegalArgumentException if vector is null, wrong size, or entryId is negative
     */
    public void insert(float[] vector, long entryId) throws IOException {
        validateVector(vector);
        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative, got: " + entryId);
        }

        int lockIndex = (int) (entryId % NUM_LOCKS);
        locks[lockIndex].lock();
        try {
            for (LshHashTable table : tables) {
                table.insert(vector, entryId);
            }
            entryCount.incrementAndGet();
        } finally {
            locks[lockIndex].unlock();
        }
    }

    /**
     * Queries for candidate entry IDs using all hash tables.
     * Returns the union of candidates from all tables.
     *
     * @param vector The query vector
     * @return Set of candidate entry IDs (may be empty)
     * @throws IllegalArgumentException if vector is null or wrong size
     */
    public Set<Long> query(float[] vector) {
        return query(vector, config.numProbes());
    }

    /**
     * Queries for candidate entry IDs with a specific number of probes.
     *
     * @param vector    The query vector
     * @param numProbes Number of multi-probe candidates per table
     * @return Set of candidate entry IDs (union from all tables)
     * @throws IllegalArgumentException if vector is null or wrong size
     */
    public Set<Long> query(float[] vector, int numProbes) {
        validateVector(vector);

        Set<Long> candidates = new HashSet<>();
        for (LshHashTable table : tables) {
            candidates.addAll(table.query(vector, numProbes));
        }

        return candidates;
    }

    /**
     * Deletes an entry from all hash tables.
     * Used for tombstone propagation when entries are removed.
     *
     * @param entryId The entry ID to delete
     * @return true if the entry was found and deleted from at least one table
     */
    public boolean delete(long entryId) {
        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative, got: " + entryId);
        }

        int lockIndex = (int) (entryId % NUM_LOCKS);
        locks[lockIndex].lock();
        try {
            boolean found = false;
            for (LshHashTable table : tables) {
                if (table.delete(entryId)) {
                    found = true;
                }
            }
            if (found) {
                entryCount.decrementAndGet();
            }
            return found;
        } finally {
            locks[lockIndex].unlock();
        }
    }

    /**
     * Deletes an entry from a specific hash bucket across all tables.
     * More efficient than delete() when the hash is known.
     *
     * @param hash    The hash value
     * @param entryId The entry ID to delete
     * @return true if the entry was found and deleted
     */
    public boolean deleteFromBucket(long hash, long entryId) {
        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative");
        }

        int lockIndex = (int) (entryId % NUM_LOCKS);
        locks[lockIndex].lock();
        try {
            boolean found = false;
            for (LshHashTable table : tables) {
                if (table.deleteFromBucket(hash, entryId)) {
                    found = true;
                }
            }
            if (found) {
                entryCount.decrementAndGet();
            }
            return found;
        } finally {
            locks[lockIndex].unlock();
        }
    }

    /**
     * Checks if an entry might be in the index by checking all tables.
     *
     * @param vector  The vector to check
     * @param entryId The entry ID to look for
     * @return true if the entry might be present
     */
    public boolean mightContain(float[] vector, long entryId) {
        validateVector(vector);
        if (entryId < 0) {
            return false;
        }

        for (LshHashTable table : tables) {
            long hash = table.hashFunction().hash(vector);
            if (table.contains(hash, entryId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the number of entries in this index.
     *
     * @return The entry count
     */
    public long entryCount() {
        return entryCount.get();
    }

    /**
     * Returns the number of hash tables.
     *
     * @return The number of tables
     */
    public int numTables() {
        return tables.length;
    }

    /**
     * Returns the configuration for this index.
     *
     * @return The configuration
     */
    public LshConfig config() {
        return config;
    }

    /**
     * Returns the vector dimensionality.
     *
     * @return The dimensions
     */
    public int dimensions() {
        return config.dimensions();
    }

    /**
     * Returns a specific hash table.
     *
     * @param index The table index
     * @return The hash table
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public LshHashTable getTable(int index) {
        if (index < 0 || index >= tables.length) {
            throw new IndexOutOfBoundsException(
                "Table index " + index + " out of range [0, " + (tables.length - 1) + "]");
        }
        return tables[index];
    }

    /**
     * Clears all entries from this index.
     */
    public void clear() {
        // Acquire all locks
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i].lock();
        }
        try {
            for (LshHashTable table : tables) {
                table.clear();
            }
            entryCount.set(0);
        } finally {
            for (int i = 0; i < NUM_LOCKS; i++) {
                locks[i].unlock();
            }
        }
    }

    /**
     * Estimates memory usage in bytes.
     *
     * @return Estimated memory usage
     */
    public long estimatedMemoryUsage() {
        // Hyperplanes: tables * functions * dimensions * 4 bytes
        long hyperplaneMemory = config.estimatedHyperplaneMemory();

        // Buckets: approximate based on entry count
        // Each entry: ~24 bytes (HashSet overhead) + 16 bytes (Long objects)
        long bucketMemory = entryCount.get() * 40;

        return hyperplaneMemory + bucketMemory;
    }

    /**
     * Validates a vector for insertion or query.
     */
    private void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector cannot be null");
        }
        if (vector.length != config.dimensions()) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != dimensions " + config.dimensions());
        }
    }

    /** Header size in bytes for the main index. */
    private static final int HEADER_SIZE = 40;

    /** Header field offsets. */
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_DIMENSIONS = 8;
    private static final int OFFSET_NUM_HASH_TABLES = 12;
    private static final int OFFSET_NUM_HASH_FUNCTIONS = 16;
    private static final int OFFSET_NUM_PROBES = 20;
    private static final int OFFSET_SEED = 24;
    private static final int OFFSET_ENTRY_COUNT = 32;
    private static final int OFFSET_TABLE_DATA_START = 40;

    /**
     * Serializes this index to a MemorySegment.
     * The format includes the header and all table data with hyperplanes.
     *
     * <p>Format:
     * <ul>
     *   <li>Header (40 bytes): magic, version, config, entryCount</li>
     *   <li>For each table: length(4) + serialized LshHashTable data</li>
     * </ul>
     *
     * @param arena The arena to allocate from
     * @return The memory segment containing serialized data
     */
    public MemorySegment toMemorySegment(Arena arena) {
        // First, serialize all tables to get their sizes
        MemorySegment[] tableSegments = new MemorySegment[tables.length];
        long totalTableSize = 0;

        for (int i = 0; i < tables.length; i++) {
            tableSegments[i] = tables[i].toMemorySegment(arena);
            totalTableSize += 4 + tableSegments[i].byteSize(); // 4 bytes for length prefix
        }

        // Total size: header + table data
        long totalSize = HEADER_SIZE + totalTableSize;
        MemorySegment segment = arena.allocate(totalSize);

        // Write header
        long offset = 0;
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, MAGIC);
        offset += 4;
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, VERSION);
        offset += 4;
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, config.dimensions());
        offset += 4;
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, config.numHashTables());
        offset += 4;
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, config.numHashFunctions());
        offset += 4;
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, config.numProbes());
        offset += 4;
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, config.seed());
        offset += 8;
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, entryCount.get());
        offset += 8;

        // Write each table with length prefix
        for (int i = 0; i < tables.length; i++) {
            int tableSize = (int) tableSegments[i].byteSize();
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, tableSize);
            offset += 4;

            // Copy table data
            MemorySegment.copy(tableSegments[i], 0, segment, offset, tableSize);
            offset += tableSize;
        }

        return segment.asSlice(0, offset);
    }

    /**
     * Deserializes an LshIndex from a MemorySegment.
     *
     * @param segment The memory segment containing serialized data
     * @return A new LshIndex instance
     * @throws IllegalArgumentException if the segment is invalid or corrupted
     */
    public static LshIndex fromMemorySegment(MemorySegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("segment cannot be null");
        }
        if (segment.byteSize() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small: " + segment.byteSize() + " < " + HEADER_SIZE);
        }

        // Read and validate header
        int magic = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic) +
                " (expected 0x" + Integer.toHexString(MAGIC) + ")");
        }

        int version = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_VERSION);
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "Unsupported version: " + version + " (expected " + VERSION + ")");
        }

        // Read config
        int dimensions = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_DIMENSIONS);
        int numHashTables = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_NUM_HASH_TABLES);
        int numHashFunctions = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_NUM_HASH_FUNCTIONS);
        int numProbes = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_NUM_PROBES);
        long seed = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_SEED);
        long entryCount = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_ENTRY_COUNT);

        // Create config and index
        LshConfig config = LshConfig.builder(dimensions)
            .numHashTables(numHashTables)
            .numHashFunctions(numHashFunctions)
            .numProbes(numProbes)
            .seed(seed)
            .build();

        LshIndex index = new LshIndex(config);

        // Read each table
        long offset = HEADER_SIZE;
        for (int t = 0; t < numHashTables; t++) {
            if (offset + 4 > segment.byteSize()) {
                throw new IllegalArgumentException(
                    "Unexpected end of segment reading table " + t + " size");
            }

            int tableSize = segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
            offset += 4;

            if (offset + tableSize > segment.byteSize()) {
                throw new IllegalArgumentException(
                    "Unexpected end of segment reading table " + t + " data");
            }

            // Create a slice for the table data and deserialize
            MemorySegment tableSlice = segment.asSlice(offset, tableSize);
            LshHashTable deserializedTable = LshHashTable.fromMemorySegment(tableSlice);

            // Replace the empty table with the deserialized one
            index.tables[t] = deserializedTable;
            offset += tableSize;
        }

        // Restore entry count (table deserialization sets individual counts, but we need the total)
        index.entryCount.set(entryCount);

        return index;
    }

    @Override
    public String toString() {
        return String.format("LshIndex[dimensions=%d, tables=%d, functions=%d, probes=%d, entries=%d]",
            config.dimensions(), config.numHashTables(), config.numHashFunctions(),
            config.numProbes(), entryCount.get());
    }
}
