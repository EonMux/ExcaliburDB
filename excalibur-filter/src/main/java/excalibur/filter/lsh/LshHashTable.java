package excalibur.filter.lsh;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single hash table for LSH with buckets storing entry IDs.
 *
 * <h2>Overview</h2>
 * <p>Each LshHashTable uses one hash function (composed of multiple hyperplanes)
 * to hash vectors into buckets. Similar vectors tend to hash to the same bucket.
 * Multiple tables are combined in an LshIndex to improve recall.</p>
 *
 * <h2>Memory Layout</h2>
 * <p>Buckets are stored in a ConcurrentHashMap for thread-safe access:</p>
 * <ul>
 *   <li>Key: Hash value (long)</li>
 *   <li>Value: Set of entry IDs (Set&lt;Long&gt;)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class uses striped locks (64 stripes) for write operations and
 * lock-free reads via ConcurrentHashMap. Thread-safe for all operations.</p>
 *
 * @author ExcaliburDB
 */
public class LshHashTable {

    /** Magic number for serialization validation. */
    private static final int MAGIC = 0x4C534854; // "LSHT" in ASCII

    /** Current serialization version. */
    private static final int VERSION = 1;

    /** Header size in bytes. */
    private static final int HEADER_SIZE = 32;

    // Header field offsets
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_NUM_BITS = 8;
    private static final int OFFSET_DIMENSIONS = 12;
    private static final int OFFSET_SEED = 16;
    private static final int OFFSET_ENTRY_COUNT = 24;

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

    /** Buckets: hash value -> set of entry IDs. */
    private final ConcurrentHashMap<Long, Set<Long>> buckets;

    /** Entry counter. */
    private final AtomicLong entryCount;

    /** Striped locks for write operations. */
    private final ReentrantLock[] locks;

    /** Number of lock stripes. */
    private static final int NUM_LOCKS = 64;

    /**
     * Creates a new LshHashTable with the specified parameters.
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
        this.buckets = new ConcurrentHashMap<>();
        this.entryCount = new AtomicLong(0);

        // Initialize striped locks
        this.locks = new ReentrantLock[NUM_LOCKS];
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Creates an LshHashTable from an existing hash function.
     *
     * @param hashFunction The hash function to use
     */
    public LshHashTable(RandomHyperplaneHash hashFunction) {
        this.numBits = hashFunction.numBits();
        this.dimensions = hashFunction.dimensions();
        this.seed = 0; // Not applicable when using existing hash function
        this.hashFunction = hashFunction;
        this.probeGenerator = new MultiProbeGenerator(numBits);
        this.buckets = new ConcurrentHashMap<>();
        this.entryCount = new AtomicLong(0);

        // Initialize striped locks
        this.locks = new ReentrantLock[NUM_LOCKS];
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Inserts an entry into the hash table.
     *
     * @param vector  The vector to hash
     * @param entryId The entry ID to store
     * @throws IllegalArgumentException if vector is null or wrong size
     */
    public void insert(float[] vector, long entryId) {
        if (vector == null) {
            throw new IllegalArgumentException("vector cannot be null");
        }
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != dimensions " + dimensions);
        }

        long hash = hashFunction.hash(vector);

        acquireLock(hash);
        try {
            buckets.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet())
                   .add(entryId);
            entryCount.incrementAndGet();
        } finally {
            releaseLock(hash);
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
        for (long probeHash : probes) {
            Set<Long> bucket = buckets.get(probeHash);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }

        return candidates;
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
        Set<Long> bucket = buckets.get(hash);
        return bucket != null ? new HashSet<>(bucket) : new HashSet<>();
    }

    /**
     * Removes an entry ID from all buckets.
     * Used for tombstone propagation.
     *
     * @param entryId The entry ID to remove
     * @return true if the entry was found and removed
     */
    public boolean delete(long entryId) {
        boolean found = false;

        for (long hash : buckets.keySet()) {
            acquireLock(hash);
            try {
                Set<Long> bucket = buckets.get(hash);
                if (bucket != null && bucket.remove(entryId)) {
                    found = true;
                    entryCount.decrementAndGet();

                    // Remove empty buckets to save memory
                    if (bucket.isEmpty()) {
                        buckets.remove(hash);
                    }
                }
            } finally {
                releaseLock(hash);
            }
        }

        return found;
    }

    /**
     * Removes an entry ID from a specific hash bucket.
     *
     * @param hash    The hash value
     * @param entryId The entry ID to remove
     * @return true if the entry was found and removed
     */
    public boolean deleteFromBucket(long hash, long entryId) {
        acquireLock(hash);
        try {
            Set<Long> bucket = buckets.get(hash);
            if (bucket != null && bucket.remove(entryId)) {
                entryCount.decrementAndGet();

                if (bucket.isEmpty()) {
                    buckets.remove(hash);
                }
                return true;
            }
            return false;
        } finally {
            releaseLock(hash);
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
        Set<Long> bucket = buckets.get(hash);
        return bucket != null && bucket.contains(entryId);
    }

    /**
     * Returns the number of entries in this table.
     *
     * @return The entry count
     */
    public long entryCount() {
        return entryCount.get();
    }

    /**
     * Returns the number of non-empty buckets.
     *
     * @return The bucket count
     */
    public int bucketCount() {
        return buckets.size();
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
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i].lock();
        }
        try {
            buckets.clear();
            entryCount.set(0);
        } finally {
            for (int i = 0; i < NUM_LOCKS; i++) {
                locks[i].unlock();
            }
        }
    }

    /**
     * Serializes this hash table to a byte array.
     * Note: This serializes the configuration and data, but not the hash function hyperplanes.
     * Use toBytesWithHyperplanes() for complete serialization.
     *
     * @return The serialized bytes
     */
    public byte[] toBytes() {
        // Estimate size: header + entry count + entries
        // Each entry: hash (8) + entryId (8) = 16 bytes
        long count = entryCount.get();
        int estimatedSize = HEADER_SIZE + 8 + (int) (count * 16);
        ByteBuffer buffer = ByteBuffer.allocate(estimatedSize)
            .order(ByteOrder.LITTLE_ENDIAN);

        // Write header
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(numBits);
        buffer.putInt(dimensions);
        buffer.putLong(seed);
        buffer.putLong(count);

        // Write entries
        for (var entry : buckets.entrySet()) {
            long hash = entry.getKey();
            for (long entryId : entry.getValue()) {
                if (buffer.remaining() < 16) {
                    // Resize buffer
                    ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2)
                        .order(ByteOrder.LITTLE_ENDIAN);
                    buffer.flip();
                    newBuffer.put(buffer);
                    buffer = newBuffer;
                }
                buffer.putLong(hash);
                buffer.putLong(entryId);
            }
        }

        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    /**
     * Serializes this hash table including hyperplanes to a MemorySegment.
     *
     * @param arena The arena to allocate from
     * @return The memory segment containing serialized data
     */
    public MemorySegment toMemorySegment(Arena arena) {
        byte[] data = toBytes();
        MemorySegment segment = arena.allocate(data.length);
        segment.copyFrom(MemorySegment.ofArray(data));
        return segment;
    }

    /**
     * Deserializes a hash table from a MemorySegment.
     *
     * @param segment The memory segment to read from
     * @return A new LshHashTable instance
     * @throws IllegalArgumentException if the segment is invalid
     */
    public static LshHashTable fromMemorySegment(MemorySegment segment) {
        if (segment.byteSize() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small: " + segment.byteSize() + " < " + HEADER_SIZE);
        }

        // Read header
        int magic = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic));
        }

        int version = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_VERSION);
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "Unsupported version: " + version);
        }

        int numBits = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_NUM_BITS);
        int dimensions = segment.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_DIMENSIONS);
        long seed = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_SEED);
        long entryCount = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_ENTRY_COUNT);

        LshHashTable table = new LshHashTable(dimensions, numBits, seed);

        // Read entries
        long offset = HEADER_SIZE;
        for (long i = 0; i < entryCount; i++) {
            if (offset + 16 > segment.byteSize()) {
                break; // Incomplete entry
            }
            long hash = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
            long entryId = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 8);
            offset += 16;

            table.buckets.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet())
                        .add(entryId);
        }

        table.entryCount.set(entryCount);
        return table;
    }

    /**
     * Acquires the striped lock for a hash value.
     */
    private void acquireLock(long hash) {
        locks[(int) Math.abs(hash % NUM_LOCKS)].lock();
    }

    /**
     * Releases the striped lock for a hash value.
     */
    private void releaseLock(long hash) {
        locks[(int) Math.abs(hash % NUM_LOCKS)].unlock();
    }

    @Override
    public String toString() {
        return String.format("LshHashTable[numBits=%d, buckets=%d, entries=%d]",
            numBits, buckets.size(), entryCount.get());
    }
}
