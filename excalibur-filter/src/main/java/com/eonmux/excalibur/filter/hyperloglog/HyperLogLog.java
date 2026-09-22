package com.eonmux.excalibur.filter.hyperloglog;

import com.eonmux.excalibur.core.hash.XxHash64;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HyperLogLog (HLL) for cardinality estimation of distinct elements.
 *
 * <h2>Overview</h2>
 * <p>HyperLogLog is a probabilistic algorithm for approximating the cardinality
 * (number of distinct elements) of very large data sets using significantly less
 * memory than exact counting methods.</p>
 *
 * <h2>Use Case in ExcaliburDB</h2>
 * <p>Used for estimating unique term counts in segments, helping the query planner
 * understand data distribution and selectivity for query optimization.</p>
 *
 * <h2>Algorithm</h2>
 * <ul>
 *   <li>Uses m = 2^precision registers</li>
 *   <li>Each register stores the maximum leading zero count seen</li>
 *   <li>Hash determines register index (first precision bits) and leading zeros (remaining bits)</li>
 *   <li>Estimate computed via harmonic mean with bias correction</li>
 * </ul>
 *
 * <h2>Error Bounds</h2>
 * <p>Standard error is approximately 1.04 / sqrt(m). Common precision values:</p>
 * <ul>
 *   <li>precision=10 (1024 registers): ~3.25% error</li>
 *   <li>precision=14 (16384 registers): ~1.625% error (default)</li>
 *   <li>precision=16 (65536 registers): ~0.81% error</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. Uses striped locks (64 stripes) for write
 * operations and lock-free reads via volatile register access.</p>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * [magic(4B)|version(4B)|seed(8B)|precision(4B)|reserved(4B)|registers...]
 * </pre>
 * Header is 24 bytes (8-byte aligned for the long seed value).
 *
 * @author ExcaliburDB
 */
public class HyperLogLog {

    /** Magic number for serialization validation. */
    private static final int MAGIC = 0x484C4C4B; // "HLLK" in ASCII

    /** Current serialization version. */
    private static final int VERSION = 1;

    /** Header size in bytes (24 bytes, 8-byte aligned). */
    private static final int HEADER_SIZE = 24;

    // Header field offsets - arranged for proper alignment
    private static final int OFFSET_MAGIC = 0;      // 4 bytes
    private static final int OFFSET_VERSION = 4;    // 4 bytes
    private static final int OFFSET_SEED = 8;       // 8 bytes (8-byte aligned)
    private static final int OFFSET_PRECISION = 16; // 4 bytes
    private static final int OFFSET_RESERVED = 20;  // 4 bytes (padding)

    /** Default precision (14 bits = 16384 registers, ~1.625% error). */
    public static final int DEFAULT_PRECISION = 14;

    /** Minimum precision (4 bits = 16 registers, ~26% error). */
    public static final int MIN_PRECISION = 4;

    /** Maximum precision (16 bits = 65536 registers, ~0.81% error). */
    public static final int MAX_PRECISION = 16;

    /**
     * Hash seed used by every sketch that is not given one explicitly.
     *
     * <p>Fixed, not random. Two sketches can only be merged if the same term lands in the same
     * register in both, which means they must hash it the same way; seeding each instance
     * differently makes {@link #merge(HyperLogLog)} take the per-register maximum of two
     * unrelated hashings and report a union that is neither sketch's contents. It over-counts by
     * roughly the overlap, silently, because the result is still a plausible number.</p>
     *
     * <p>A fixed seed also makes an estimate reproducible across restarts, which a planner
     * statistic has to be: the same corpus answering a different cardinality on Tuesday is not
     * something anyone can debug. The seed is written into the serialised header regardless, so
     * it was never secret and randomising it bought nothing.</p>
     */
    public static final long DEFAULT_SEED = 0x9E3779B97F4A7C15L;

    /** Number of bits used to select the register. */
    private final int precision;

    /** Number of registers (m = 2^precision). */
    private final int numRegisters;

    /** Registers storing max leading zero counts. */
    private final byte[] registers;

    /** Random seed for hash function. */
    private final long seed;

    /** Striped locks for write operations. */
    private final ReentrantLock[] locks;

    /** Number of lock stripes. */
    private static final int NUM_LOCKS = 64;

    /** Alpha constant for bias correction, depends on number of registers. */
    private final double alpha;

    /**
     * Creates a new HyperLogLog with default precision (14).
     * This provides ~1.625% standard error with 16384 registers.
     */
    public HyperLogLog() {
        this(DEFAULT_PRECISION);
    }

    /**
     * Creates a new HyperLogLog with specified precision.
     *
     * @param precision Number of bits for register addressing (4-16)
     * @throws IllegalArgumentException if precision is not in [4, 16]
     */
    public HyperLogLog(int precision) {
        this(precision, DEFAULT_SEED);
    }

    /**
     * Creates a new HyperLogLog with specified precision and seed.
     *
     * @param precision Number of bits for register addressing (4-16)
     * @param seed      Random seed for hash function
     * @throws IllegalArgumentException if precision is not in [4, 16]
     */
    public HyperLogLog(int precision, long seed) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException(
                "precision must be in [" + MIN_PRECISION + ", " + MAX_PRECISION + "]: " + precision);
        }

        this.precision = precision;
        this.numRegisters = 1 << precision; // 2^precision
        this.seed = seed;
        this.registers = new byte[numRegisters];
        this.alpha = computeAlpha(numRegisters);

        // Initialize striped locks
        this.locks = new ReentrantLock[NUM_LOCKS];
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Computes the alpha constant for bias correction based on number of registers.
     * Alpha values from the HyperLogLog paper for specific register counts.
     *
     * @param m Number of registers
     * @return Alpha constant
     */
    private static double computeAlpha(int m) {
        return switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1.0 + 1.079 / m);
        };
    }

    /**
     * Returns the precision (number of bits for register addressing).
     *
     * @return The precision value
     */
    public int precision() {
        return precision;
    }

    /**
     * Returns the number of registers.
     *
     * @return Number of registers (2^precision)
     */
    public int numRegisters() {
        return numRegisters;
    }

    /**
     * Inserts an item into the sketch.
     *
     * <p>The item is hashed, and the first 'precision' bits determine which
     * register to update. The remaining bits are used to count leading zeros,
     * and the register is updated if this count is larger than the current value.</p>
     *
     * @param item The item to insert
     * @throws NullPointerException if item is null
     */
    public void insert(byte[] item) {
        if (item == null) {
            throw new NullPointerException("item cannot be null");
        }

        // Hash the item using xxHash64
        // Note: XxHash64 normalizes to positive values, clearing bit 63.
        // To get full 64-bit entropy, we compute two hashes and combine them.
        long hash1 = XxHash64.hash(item, seed);
        long hash2 = XxHash64.hash(item, seed ^ 0x9E3779B97F4A7C15L);

        // Combine hashes to get full 64-bit entropy
        // Use hash2's MSB to restore the bit that was cleared by normalization
        long hash = hash1 | ((hash2 & 0x4000000000000000L) << 1); // Move bit 62 to bit 63

        // Extract register index from first 'precision' bits (MSB side)
        int registerIndex = (int) (hash >>> (64 - precision));

        // Extract remaining bits (lower 64-precision bits) and count leading zeros
        // We shift left by precision to put remaining bits in MSB position,
        // then use Long.numberOfLeadingZeros
        long remainingBits = hash << precision;
        int leadingZeros = Long.numberOfLeadingZeros(remainingBits) + 1;

        // Clamp to max value: for (64-precision) remaining bits, rho ranges
        // from 1 (first bit is 1) to (65-precision) (all bits are zero).
        int maxRegisterValue = 65 - precision;
        leadingZeros = Math.min(leadingZeros, maxRegisterValue);

        // Update register with striped lock
        int lockIndex = registerIndex % NUM_LOCKS;
        locks[lockIndex].lock();
        try {
            int currentValue = registers[registerIndex] & 0xFF;
            if (leadingZeros > currentValue) {
                registers[registerIndex] = (byte) leadingZeros;
            }
        } finally {
            locks[lockIndex].unlock();
        }
    }

    /**
     * Estimates the cardinality (number of distinct elements).
     *
     * <p>Uses the harmonic mean of register values with bias correction.
     * Handles edge cases for small and large cardinalities using
     * linear counting and large range correction respectively.</p>
     *
     * @return Estimated number of distinct elements
     */
    public long estimate() {
        // Acquire all locks for consistent snapshot
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i].lock();
        }
        try {
            // Compute harmonic mean denominator: sum(2^(-register))
            double sumInverse = 0.0;
            int zeroCount = 0;

            for (int i = 0; i < numRegisters; i++) {
                int regValue = registers[i] & 0xFF; // unsigned byte
                if (regValue == 0) {
                    zeroCount++;
                    sumInverse += 1.0; // 2^0 = 1
                } else {
                    sumInverse += Math.pow(2.0, -regValue);
                }
            }

            // If all registers are zero, cardinality is 0
            if (sumInverse == 0.0 || zeroCount == numRegisters) {
                return 0;
            }

            // Raw estimate using harmonic mean
            double rawEstimate = (alpha * numRegisters * numRegisters) / sumInverse;

            // Linear counting correction for small cardinalities
            if (rawEstimate <= 2.5 * numRegisters) {
                if (zeroCount > 0) {
                    return Math.round(linearCount(numRegisters, zeroCount));
                }
            }

            // Large range correction for very large cardinalities
            if (rawEstimate > (1L << 32) / 30.0) {
                return Math.round(-twoPow32() * Math.log(1.0 - rawEstimate / twoPow32()));
            }

            return Math.round(rawEstimate);
        } finally {
            for (int i = 0; i < NUM_LOCKS; i++) {
                locks[i].unlock();
            }
        }
    }

    /**
     * Linear counting for small cardinalities.
     *
     * @param m         Number of registers
     * @param zeroCount Number of zero registers
     * @return Estimated cardinality
     */
    private double linearCount(int m, int zeroCount) {
        return m * Math.log((double) m / zeroCount);
    }

    /**
     * Returns 2^32 as a double.
     */
    private double twoPow32() {
        return 4294967296.0;
    }

    /**
     * Merges another HyperLogLog sketch into this one.
     *
     * <p>Takes the maximum of each register pair, which preserves the
     * cardinality estimate of the union of both sets.</p>
     *
     * @param other The sketch to merge into this one
     * @throws IllegalArgumentException if precisions don't match
     * @throws NullPointerException     if other is null
     */
    public void merge(HyperLogLog other) {
        if (other == null) {
            throw new NullPointerException("other cannot be null");
        }
        if (other.precision != this.precision) {
            throw new IllegalArgumentException(
                "Cannot merge HyperLogLog with different precision: " +
                other.precision + " != " + this.precision);
        }
        if (other.seed != this.seed) {
            // Registers are only comparable between sketches that hash identically. Merging
            // across seeds produces a number rather than an error, and that number is wrong in
            // the direction that looks reasonable, so it has to be refused here.
            throw new IllegalArgumentException(
                "Cannot merge HyperLogLog with different seed: " +
                other.seed + " != " + this.seed);
        }

        // Acquire all locks in both sketches to prevent deadlock
        // Always acquire in same order (this first, then other)
        for (int i = 0; i < NUM_LOCKS; i++) {
            this.locks[i].lock();
        }
        try {
            for (int i = 0; i < other.NUM_LOCKS; i++) {
                other.locks[i].lock();
            }
            try {
                for (int i = 0; i < numRegisters; i++) {
                    int thisValue = this.registers[i] & 0xFF;
                    int otherValue = other.registers[i] & 0xFF;
                    if (otherValue > thisValue) {
                        this.registers[i] = (byte) otherValue;
                    }
                }
            } finally {
                for (int i = 0; i < other.NUM_LOCKS; i++) {
                    other.locks[i].unlock();
                }
            }
        } finally {
            for (int i = 0; i < NUM_LOCKS; i++) {
                this.locks[i].unlock();
            }
        }
    }

    /**
     * Clears all registers, resetting the sketch to empty state.
     */
    public void clear() {
        // Acquire all locks
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i].lock();
        }
        try {
            for (int i = 0; i < numRegisters; i++) {
                registers[i] = 0;
            }
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
        return HEADER_SIZE + numRegisters;
    }

    /**
     * Serializes this sketch to a MemorySegment.
     *
     * @param arena The arena to allocate from
     * @return The memory segment containing serialized data
     */
    public MemorySegment toMemorySegment(Arena arena) {
        long totalSize = HEADER_SIZE + numRegisters;
        MemorySegment segment = arena.allocate(totalSize);

        // Acquire all locks for consistent snapshot
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i].lock();
        }
        try {
            // Write header - note: seed is at offset 8 for proper alignment
            segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC, MAGIC);
            segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION, VERSION);
            segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED, seed);
            segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_PRECISION, precision);
            segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_RESERVED, 0); // reserved/padding

            // Write registers
            MemorySegment.copy(registers, 0, segment, ValueLayout.JAVA_BYTE, HEADER_SIZE, numRegisters);
        } finally {
            for (int i = 0; i < NUM_LOCKS; i++) {
                locks[i].unlock();
            }
        }

        return segment;
    }

    /**
     * Deserializes a HyperLogLog from a MemorySegment.
     *
     * @param segment The memory segment to read from
     * @return A new HyperLogLog instance
     * @throws IllegalArgumentException if the segment is invalid
     */
    public static HyperLogLog fromMemorySegment(MemorySegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("segment cannot be null");
        }
        if (segment.byteSize() < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Segment too small: " + segment.byteSize() + " < " + HEADER_SIZE);
        }

        // Read and validate header
        int magic = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic));
        }

        int version = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION);
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        long seed = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED);
        int precision = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_PRECISION);

        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("Invalid precision in segment: " + precision);
        }

        int numRegisters = 1 << precision;

        // Validate segment size
        long expectedSize = HEADER_SIZE + numRegisters;
        if (segment.byteSize() < expectedSize) {
            throw new IllegalArgumentException(
                "Segment size " + segment.byteSize() + " < expected " + expectedSize);
        }

        HyperLogLog hll = new HyperLogLog(precision, seed);

        // Read registers
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, HEADER_SIZE, hll.registers, 0, numRegisters);

        return hll;
    }

    /**
     * Returns the standard error for the given precision.
     *
     * @param precision The precision value
     * @return Approximate standard error as a percentage
     */
    public static double standardError(int precision) {
        int m = 1 << precision;
        return 1.04 / Math.sqrt(m) * 100;
    }

    @Override
    public String toString() {
        return String.format("HyperLogLog[precision=%d, registers=%d, error=%.2f%%]",
            precision, numRegisters, standardError(precision));
    }
}
