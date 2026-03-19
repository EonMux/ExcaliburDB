package excalibur.filter.lsh;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
    public void insert(float[] vector, long entryId) {
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

    /**
     * Serializes this index to a MemorySegment.
     * Note: This is a basic implementation for persistence.
     *
     * @param arena The arena to allocate from
     * @return The memory segment containing serialized data
     */
    public MemorySegment toMemorySegment(Arena arena) {
        // Calculate total size needed
        // Header: magic(4) + version(4) + config fields (5 * 8 = 40) + entryCount(8) = 56 bytes
        int headerSize = 56;

        // For each table: table header + entries
        long totalEntries = entryCount.get();
        long estimatedSize = headerSize + (tables.length * 32L) + (totalEntries * 16L * tables.length);

        MemorySegment segment = arena.allocate(estimatedSize);
        long offset = 0;

        // Write header
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, MAGIC);
        offset += 4;
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, VERSION);
        offset += 4;

        // Write config
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, config.dimensions());
        offset += 4;
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, config.numHashTables());
        offset += 4;
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, config.numHashFunctions());
        offset += 4;
        segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, offset, config.numProbes());
        offset += 4;
        segment.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, offset, config.seed());
        offset += 8;
        segment.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, offset, entryCount.get());
        offset += 8;

        // Note: Full serialization would include each table's data
        // For now, this provides a header for file-based persistence

        return segment.asSlice(0, offset);
    }

    @Override
    public String toString() {
        return String.format("LshIndex[dimensions=%d, tables=%d, functions=%d, probes=%d, entries=%d]",
            config.dimensions(), config.numHashTables(), config.numHashFunctions(),
            config.numProbes(), entryCount.get());
    }
}
