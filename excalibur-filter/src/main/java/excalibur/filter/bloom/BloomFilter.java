package excalibur.filter.bloom;

import io.excalibur.core.hash.XxHash64;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A Bloom Filter implementation for probabilistic set membership testing.
 *
 * <h2>Overview</h2>
 * <p>A Bloom Filter is a space-efficient probabilistic data structure used to test
 * whether an element is a member of a set. False positive matches are possible,
 * but false negatives are not. Elements can be added to the set, but not removed.</p>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li>{@code numBits}: Number of bits in the filter bit array</li>
 *   <li>{@code numHashFunctions}: Number of hash functions used</li>
 *   <li>{@code seed}: Random seed for hash generation</li>
 * </ul>
 *
 * <h2>False Positive Rate</h2>
 * <p>The false positive rate depends on:</p>
 * <pre>
 *   FP_rate ≈ (1 - e^(-kn/m))^k
 * </pre>
 * <p>where:</p>
 * <ul>
 *   <li>k = number of hash functions</li>
 *   <li>n = number of inserted elements</li>
 *   <li>m = number of bits</li>
 * </ul>
 *
 * <h2>Optimal Parameters</h2>
 * <p>Given expected insertions (n) and target FP rate (p):</p>
 * <pre>
 *   numBits (m) = -n * ln(p) / (ln(2)^2)
 *   numHashFunctions (k) = (m / n) * ln(2)
 * </pre>
 *
 * <h2>Double Hashing Technique</h2>
 * <p>Instead of computing k independent hash functions, we use the double hashing
 * technique with XXHash64:</p>
 * <pre>
 *   h_i(x) = h1(x) + i * h2(x)  (mod m)
 * </pre>
 * <p>where h1 and h2 are derived from XXHash64 with different seeds.</p>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * [magic(4B)|version(4B)|numBits(8B)|numHashFunctions(4B)|seed(8B)|bitCount(8B)|bits(numBits/8 rounded up)]
 * </pre>
 * <p>Header offsets:</p>
 * <ul>
 *   <li>offset 0: magic (4 bytes) - 0x424C4F4D ("BLOM")</li>
 *   <li>offset 4: version (4 bytes) - format version</li>
 *   <li>offset 8: numBits (8 bytes) - number of bits in filter</li>
 *   <li>offset 16: numHashFunctions (4 bytes) - number of hash functions</li>
 *   <li>offset 20: seed (8 bytes) - hash seed</li>
 *   <li>offset 28: bitCount (8 bytes) - number of set bits</li>
 *   <li>offset 36: bit data begins</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Reads: Thread-safe via volatile reads</li>
 *   <li>Writes: Protected by ReentrantReadWriteLock</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>False positive rate must be in range (0, 1)</li>
 *   <li>Expected insertions must be positive</li>
 *   <li>False negatives are NEVER allowed</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class BloomFilter {

    // Magic number for serialization validation
    private static final int MAGIC = 0x424C4F4D; // "BLOM" in ASCII

    // Current version for serialization
    private static final int VERSION = 1;

    // Header layout:
    // offset 0: magic (4 bytes)
    // offset 4: version (4 bytes)
    // offset 8: numBits (8 bytes)
    // offset 16: numHashFunctions (4 bytes)
    // offset 20: padding (4 bytes)
    // offset 24: seed (8 bytes)
    // offset 32: bitCount (8 bytes)
    // offset 40: bit data begins
    private static final int HEADER_SIZE = 40;

    // Header field offsets
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_NUM_BITS = 8;
    private static final int OFFSET_NUM_HASH_FUNCTIONS = 16;
    private static final int OFFSET_SEED = 24;
    private static final int OFFSET_BIT_COUNT = 32;
    private static final int OFFSET_BIT_DATA = 40;

    // Default false positive rate if not specified
    private static final double DEFAULT_FP_RATE = 0.01; // 1%

    // Configuration
    private final int numBits;
    private final int numHashFunctions;
    private final long seed;

    // Bit storage
    private final BitSet bits;
    private final long[] bitArray; // For memory-mapped serialization

    // Entry counter (approximate, for statistics)
    private volatile long count;

    // Read-write lock for thread safety
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates a new BloomFilter with the specified expected insertions and default FP rate.
     *
     * @param expectedInsertions Expected number of elements to insert
     * @return A new BloomFilter instance
     * @throws IllegalArgumentException if expectedInsertions is not positive
     */
    public static BloomFilter create(int expectedInsertions) {
        return create(expectedInsertions, DEFAULT_FP_RATE);
    }

    /**
     * Creates a new BloomFilter with the specified expected insertions and FP rate.
     *
     * @param expectedInsertions Expected number of elements to insert
     * @param falsePositiveRate Target false positive rate (0, 1)
     * @return A new BloomFilter instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static BloomFilter create(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException(
                "expectedInsertions must be positive, got: " + expectedInsertions);
        }
        if (falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException(
                "falsePositiveRate must be in range (0, 1), got: " + falsePositiveRate);
        }

        // Calculate optimal number of bits
        // m = -n * ln(p) / (ln(2)^2)
        double ln2 = Math.log(2);
        int numBits = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2));

        // Ensure minimum size and round up to next power of 2 for efficient indexing
        numBits = Math.max(64, numBits);
        numBits = Integer.highestOneBit(numBits) << 1;

        // Calculate optimal number of hash functions
        // k = (m / n) * ln(2)
        int numHashFunctions = (int) Math.ceil((double) numBits / expectedInsertions * ln2);
        numHashFunctions = Math.max(1, Math.min(numHashFunctions, 32)); // Clamp to [1, 32]

        // Generate random seed
        long seed = System.nanoTime() ^ (System.currentTimeMillis() << 32);

        return new BloomFilter(numBits, numHashFunctions, seed);
    }

    /**
     * Creates a BloomFilter with the specified parameters.
     *
     * @param numBits Number of bits in the filter
     * @param numHashFunctions Number of hash functions
     * @param seed Random seed for hash generation
     */
    private BloomFilter(int numBits, int numHashFunctions, long seed) {
        this.numBits = numBits;
        this.numHashFunctions = numHashFunctions;
        this.seed = seed;
        this.bits = new BitSet(numBits);
        this.bitArray = new long[(numBits + 63) / 64];
        this.count = 0;
    }

    /**
     * Creates a BloomFilter from a MemorySegment.
     *
     * @param segment The memory segment containing serialized filter data
     * @throws IllegalArgumentException if the segment is invalid or corrupted
     */
    public BloomFilter(MemorySegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("MemorySegment cannot be null");
        }

        // Validate minimum size
        if (segment.byteSize() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small: " + segment.byteSize() + " < " + HEADER_SIZE);
        }

        // Read and validate header
        int magic = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic) +
                ", expected 0x" + Integer.toHexString(MAGIC));
        }

        int version = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION);
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "Unsupported version: " + version + ", expected " + VERSION);
        }

        this.numBits = (int) segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_BITS);
        this.numHashFunctions = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_HASH_FUNCTIONS);
        this.seed = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED);
        long storedCount = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_BIT_COUNT);

        // Validate parameters
        if (numBits <= 0) {
            throw new IllegalArgumentException("Invalid numBits: " + numBits);
        }
        if (numHashFunctions <= 0 || numHashFunctions > 32) {
            throw new IllegalArgumentException("Invalid numHashFunctions: " + numHashFunctions);
        }

        // Validate segment size
        int numLongs = (numBits + 63) / 64;
        long expectedSize = HEADER_SIZE + numLongs * 8L;
        if (segment.byteSize() < expectedSize) {
            throw new IllegalArgumentException(
                "Segment size " + segment.byteSize() + " < expected " + expectedSize);
        }

        // Read bit data
        this.bitArray = new long[numLongs];
        for (int i = 0; i < numLongs; i++) {
            bitArray[i] = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN),
                OFFSET_BIT_DATA + i * 8L);
        }

        // Create BitSet from long array
        this.bits = BitSet.valueOf(bitArray);
        this.count = storedCount;
    }

    /**
     * Inserts a key into the filter.
     *
     * <p>Domain correctness: After insertion, mightContain(key) MUST return true.</p>
     *
     * @param key The key to insert
     * @return true (always succeeds, Bloom filters have no capacity limit in the traditional sense)
     * @throws NullPointerException if key is null
     */
    public boolean put(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        // Compute two base hashes using XXHash64 with different seeds
        long h1 = XxHash64.hash(key, seed);
        long h2 = XxHash64.hash(key, seed ^ 0xDEADBEEFL);

        // Ensure h2 is non-zero to avoid degenerate case
        if (h2 == 0) {
            h2 = 1;
        }

        lock.writeLock().lock();
        try {
            boolean wasNew = false;

            // Apply double hashing: h_i = h1 + i * h2
            for (int i = 0; i < numHashFunctions; i++) {
                int bitIndex = (int) ((h1 + (long) i * h2) & 0x7FFFFFFFL) % numBits;

                if (!bits.get(bitIndex)) {
                    wasNew = true;
                    bits.set(bitIndex);

                    // Update bit array for serialization
                    int longIndex = bitIndex / 64;
                    int bitOffset = bitIndex % 64;
                    bitArray[longIndex] |= (1L << bitOffset);
                }
            }

            if (wasNew) {
                count++;
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a key might be in the filter.
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>If put(key) was called, mightContain(key) MUST return true (no false negatives)</li>
     *   <li>If put(key) was NOT called, mightContain(key) may return true (false positive) or false</li>
     * </ul>
     *
     * @param key The key to check
     * @return true if the key might be present, false if definitely not present
     * @throws NullPointerException if key is null
     */
    public boolean mightContain(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        // Compute two base hashes using XXHash64 with different seeds
        long h1 = XxHash64.hash(key, seed);
        long h2 = XxHash64.hash(key, seed ^ 0xDEADBEEFL);

        // Ensure h2 is non-zero to avoid degenerate case
        if (h2 == 0) {
            h2 = 1;
        }

        lock.readLock().lock();
        try {
            // Apply double hashing: h_i = h1 + i * h2
            for (int i = 0; i < numHashFunctions; i++) {
                int bitIndex = (int) ((h1 + (long) i * h2) & 0x7FFFFFFFL) % numBits;

                if (!bits.get(bitIndex)) {
                    return false; // Definitely not present
                }
            }

            return true; // Might be present (could be false positive)
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Serializes this filter to a MemorySegment.
     *
     * @param arena The arena to allocate the segment in
     * @return A MemorySegment containing the serialized filter
     */
    public MemorySegment toMemorySegment(Arena arena) {
        int numLongs = (numBits + 63) / 64;
        long size = HEADER_SIZE + numLongs * 8L;

        MemorySegment segment = arena.allocate(size);

        // Write header
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC, MAGIC);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION, VERSION);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_BITS, numBits);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_HASH_FUNCTIONS, numHashFunctions);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED, seed);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_BIT_COUNT, count);

        // Write bit data
        lock.readLock().lock();
        try {
            for (int i = 0; i < numLongs; i++) {
                segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN),
                    OFFSET_BIT_DATA + i * 8L, bitArray[i]);
            }
        } finally {
            lock.readLock().unlock();
        }

        return segment;
    }

    /**
     * Deserializes a BloomFilter from a MemorySegment.
     *
     * @param segment The memory segment containing serialized filter data
     * @return A new BloomFilter instance
     * @throws IllegalArgumentException if the segment is invalid or corrupted
     */
    public static BloomFilter fromMemorySegment(MemorySegment segment) {
        return new BloomFilter(segment);
    }

    /**
     * Gets the number of bits in the filter.
     *
     * @return The number of bits
     */
    public int size() {
        return numBits;
    }

    /**
     * Gets the number of set bits in the filter.
     *
     * @return The number of set bits
     */
    public int bitCount() {
        lock.readLock().lock();
        try {
            return bits.cardinality();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the approximate number of elements inserted.
     *
     * @return The approximate element count
     */
    public long count() {
        return count;
    }

    /**
     * Gets the number of hash functions.
     *
     * @return The number of hash functions
     */
    public int numHashFunctions() {
        return numHashFunctions;
    }

    /**
     * Gets the seed used for hash generation.
     *
     * @return The seed value
     */
    public long seed() {
        return seed;
    }

    /**
     * Estimates the current false positive rate based on the number of insertions.
     *
     * <p>Formula: FP_rate ≈ (1 - e^(-kn/m))^k</p>
     *
     * @param numInsertions The number of insertions to estimate for
     * @return The estimated false positive rate (0.0 to 1.0)
     */
    public double expectedFalsePositiveRate(int numInsertions) {
        if (numInsertions <= 0) {
            return 0.0;
        }

        // k = numHashFunctions, n = numInsertions, m = numBits
        double k = numHashFunctions;
        double n = numInsertions;
        double m = numBits;

        // Probability that a specific bit is still 0 after n insertions:
        // P(bit = 0) = (1 - 1/m)^(kn) ≈ e^(-kn/m)
        double p0 = Math.exp(-k * n / m);

        // FP rate: probability that all k bits are set for a non-inserted element
        // FP = (1 - p0)^k
        return Math.pow(1.0 - p0, k);
    }

    /**
     * Gets the current fill ratio (fraction of bits set).
     *
     * @return The fill ratio (0.0 to 1.0)
     */
    public double fillRatio() {
        lock.readLock().lock();
        try {
            return (double) bits.cardinality() / numBits;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears all bits in the filter, removing all elements.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            bits.clear();
            for (int i = 0; i < bitArray.length; i++) {
                bitArray[i] = 0;
            }
            count = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the size in bytes of this filter when serialized.
     *
     * @return The size in bytes
     */
    public long byteSize() {
        return HEADER_SIZE + bitArray.length * 8L;
    }

    @Override
    public String toString() {
        return String.format("BloomFilter[count=%d, numBits=%d, numHashFunctions=%d, fillRatio=%.4f, expectedFPRate=%.6f]",
            count, numBits, numHashFunctions, fillRatio(), expectedFalsePositiveRate((int) count));
    }
}
