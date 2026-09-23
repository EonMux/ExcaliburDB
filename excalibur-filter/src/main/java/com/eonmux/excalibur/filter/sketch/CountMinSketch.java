package com.eonmux.excalibur.filter.sketch;

import com.eonmux.excalibur.core.hash.XxHash64;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A Count-Min Sketch for estimating element frequencies.
 *
 * <h2>Overview</h2>
 * <p>Count-Min Sketch is a probabilistic data structure that provides approximate
 * frequency counts for elements. It uses sub-linear space at the cost of some
 * over-counting (never under-counting).</p>
 *
 * <h2>Use Case in ExcaliburDB</h2>
 * <p>Used by the query planner to estimate term/token selectivity. Helps determine
 * which filters and indices to use for query optimization.</p>
 *
 * <h2>Algorithm</h2>
 * <ul>
 *   <li>Uses d rows of w counters each</li>
 *   <li>Each row has its own hash function, derived from one hash of the item</li>
 *   <li><strong>Insert:</strong> Increment counters at hash_i(item) % w for all rows</li>
 *   <li><strong>Estimate:</strong> Return minimum of all counters at hash positions</li>
 * </ul>
 *
 * <h2>One hash per item</h2>
 * <p>The item's bytes are hashed once, by {@link #itemHash(byte[])}, and every row's column is
 * derived from that one value by double hashing (Kirsch and Mitzenmacher): the value is mixed
 * with this sketch's seed into two 32-bit halves {@code h1} and {@code h2}, and row {@code i}
 * uses column {@code (start + i * step) mod w}, where {@code start} is {@code h1 mod w} and
 * {@code step} is drawn by {@code h2} from the residues coprime to the width. That keeps the
 * error bounds below while hashing the bytes once rather than once per row - which matters
 * because the sketch is fed on the storage write path. It also lets a caller hash an item where
 * it is cheap to - outside a lock, say - and hand the sketch the value through
 * {@link #insertHashed(long, long)}. The item hash is seed-independent, so one value serves every
 * sketch; the seed still gives each sketch its own columns.</p>
 *
 * <h2>Why the step is coprime to the width</h2>
 * <p>The error bound assumes the rows are independent chances to escape a collision, and double
 * hashing only delivers that when every row lands in a column of its own. A step sharing a factor
 * {@code g} with the width revisits a column every {@code w / g} rows: with a power-of-two width,
 * which is what segments keep, a raw {@code h2} divisible by the width put every row of roughly
 * one item in a thousand in the same column (or its mirror), so its estimate was one counter read
 * several times, and a half-width {@code h2} paired the rows off. A step coprime to the width is
 * a unit modulo it, so rows {@code 0 .. w - 1} visit {@code w} distinct columns: an item's first
 * {@code min(depth, width)} rows never share one. Drawing the step from a table of those residues
 * gives the guarantee for every width - forcing {@code h2} odd would give it only for powers of
 * two - at a cost of one table read per item. The table holds at most one int per column, which
 * is at most half the space of a single row of counters.</p>
 *
 * <h2>Error Bounds</h2>
 * <p>With probability at least (1 - δ), the error is at most ε * N where:</p>
 * <ul>
 *   <li>N = total count of all items</li>
 *   <li>ε = 2/w (width controls error rate)</li>
 *   <li>δ = 1/2^d (depth controls confidence)</li>
 * </ul>
 *
 * <h2>Parameter Selection</h2>
 * <p>Default parameters target ε = 0.001 (0.1% error) and δ = 0.001 (99.9% confidence):</p>
 * <ul>
 *   <li>width = 2000 (2/ε)</li>
 *   <li>depth = 10 (ln(1/δ)/ln(2))</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. Uses striped locks (64 stripes) for write
 * operations and lock-free reads via atomic counter arrays.</p>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * [magic(4B)|version(4B)|width(4B)|depth(4B)|seed(8B)|totalCount(8B)|counters...]
 * </pre>
 *
 * @author ExcaliburDB
 */
public class CountMinSketch {

    /** Magic number for serialization validation. */
    private static final int MAGIC = 0x434D534B; // "CMSK" in ASCII

    /**
     * Current serialization version.
     *
     * <p>The counters are only meaningful under the column mapping that filled them, so the
     * version names that mapping as well as the layout. Version 1 hashed each row separately,
     * and version 2 stepped through the rows by the raw upper half of the mixed hash, which
     * could share a factor with the width; see "Why the step is coprime to the width". A sketch
     * written under either reads back as unsupported rather than as counters for other
     * terms.</p>
     */
    private static final int VERSION = 3;

    /** Seed of {@link #itemHash(byte[])}, fixed so every sketch agrees on an item's hash. */
    private static final long ITEM_HASH_SEED = 0x2545F4914F6CDD1DL;

    /** Header size in bytes. */
    private static final int HEADER_SIZE = 32;

    // Header field offsets
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_WIDTH = 8;
    private static final int OFFSET_DEPTH = 12;
    private static final int OFFSET_SEED = 16;
    private static final int OFFSET_TOTAL_COUNT = 24;

    /** Default width (controls error rate: ε ≈ 2/width). */
    public static final int DEFAULT_WIDTH = 2000;

    /** Default depth (controls confidence: δ ≈ 2^-depth). */
    public static final int DEFAULT_DEPTH = 10;

    /** Number of hash functions (depth). */
    private final int depth;

    /** Number of counters per row (width). */
    private final int width;

    /** Random seed for hash functions. */
    private final long seed;

    /** Counter arrays - one AtomicLongArray per row for thread-safe updates. */
    private final AtomicLongArray[] counters;

    /** Total count of all items inserted. */
    private final AtomicLong totalCount;

    /**
     * The residues modulo {@link #width} that are coprime to it, in ascending order: the steps a
     * row sequence may take. See "Why the step is coprime to the width".
     */
    private final int[] steps;

    /** Striped locks for write operations. */
    private final ReentrantLock[] locks;

    /** Number of lock stripes. */
    private static final int NUM_LOCKS = 64;

    /**
     * Creates a new CountMinSketch with default parameters.
     * Default: width=2000, depth=10 (ε≈0.001, δ≈0.001).
     */
    public CountMinSketch() {
        this(DEFAULT_WIDTH, DEFAULT_DEPTH);
    }

    /**
     * Creates a new CountMinSketch with specified parameters.
     *
     * @param width Number of counters per row (controls error rate)
     * @param depth Number of rows/hash functions (controls confidence)
     * @throws IllegalArgumentException if width or depth is not positive
     */
    public CountMinSketch(int width, int depth) {
        this(width, depth, System.nanoTime());
    }

    /**
     * Creates a new CountMinSketch with specified parameters and seed.
     *
     * @param width Number of counters per row
     * @param depth Number of rows
     * @param seed  Random seed for hash functions
     * @throws IllegalArgumentException if width or depth is not positive
     */
    public CountMinSketch(int width, int depth, long seed) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("depth must be positive: " + depth);
        }

        this.width = width;
        this.depth = depth;
        this.seed = seed;
        this.totalCount = new AtomicLong(0);
        this.steps = unitsModulo(width);

        // Initialize counter arrays
        this.counters = new AtomicLongArray[depth];
        for (int i = 0; i < depth; i++) {
            counters[i] = new AtomicLongArray(width);
        }

        // Initialize striped locks
        this.locks = new ReentrantLock[NUM_LOCKS];
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Creates a CountMinSketch optimized for a specific error rate.
     *
     * @param epsilon Maximum relative error (e.g., 0.001 for 0.1%)
     * @param delta   Failure probability (e.g., 0.001 for 99.9% confidence)
     * @return A new CountMinSketch with appropriate parameters
     * @throws IllegalArgumentException if epsilon or delta not in (0, 1)
     */
    public static CountMinSketch createForErrorRate(double epsilon, double delta) {
        if (epsilon <= 0 || epsilon >= 1) {
            throw new IllegalArgumentException("epsilon must be in (0, 1): " + epsilon);
        }
        if (delta <= 0 || delta >= 1) {
            throw new IllegalArgumentException("delta must be in (0, 1): " + delta);
        }

        int width = (int) Math.ceil(2.0 / epsilon);
        int depth = (int) Math.ceil(Math.log(1.0 / delta) / Math.log(2));

        return new CountMinSketch(width, depth);
    }

    /**
     * Inserts an item with count 1.
     *
     * @param item The item to insert
     * @throws NullPointerException if item is null
     */
    public void insert(byte[] item) {
        insert(item, 1);
    }

    /**
     * Inserts an item with the specified count.
     *
     * @param item  The item to insert
     * @param count The count to add (must be non-negative)
     * @throws NullPointerException     if item is null
     * @throws IllegalArgumentException if count is negative
     */
    public void insert(byte[] item, long count) {
        if (item == null) {
            throw new NullPointerException("item cannot be null");
        }
        insertHashed(itemHash(item), count);
    }

    /**
     * Returns the hash an item is counted and estimated under.
     *
     * <p>The same for every sketch, whatever its seed, so an item can be hashed once - where it
     * is cheap to - and the value handed to {@link #insertHashed(long, long)} or
     * {@link #estimateHashed(long)} of any sketch.</p>
     *
     * @param item The item
     * @return Its hash
     * @throws NullPointerException if item is null
     */
    public static long itemHash(byte[] item) {
        if (item == null) {
            throw new NullPointerException("item cannot be null");
        }
        return XxHash64.hash(item, ITEM_HASH_SEED);
    }

    /**
     * Inserts an item, given as its {@link #itemHash(byte[])}, with the specified count.
     *
     * <p>Exactly what {@link #insert(byte[], long)} does with the item's bytes.</p>
     *
     * @param itemHash The item's hash
     * @param count    The count to add (must be non-negative)
     * @throws IllegalArgumentException if count is negative
     */
    public void insertHashed(long itemHash, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative: " + count);
        }
        if (count == 0) {
            return;
        }

        long mixed = mix(itemHash);
        int lockIndex = (int) ((mixed >>> 1) % NUM_LOCKS);
        locks[lockIndex].lock();
        try {
            for (int row = 0; row < depth; row++) {
                counters[row].addAndGet(column(mixed, row), count);
            }
            totalCount.addAndGet(count);
        } finally {
            locks[lockIndex].unlock();
        }
    }

    /**
     * Estimates the count of an item.
     *
     * <p>Returns the minimum counter value across all rows, which is an
     * upper bound on the true count. Never under-estimates.</p>
     *
     * @param item The item to estimate
     * @return The estimated count (≥ true count with high probability)
     * @throws NullPointerException if item is null
     */
    public long estimate(byte[] item) {
        if (item == null) {
            throw new NullPointerException("item cannot be null");
        }
        return estimateHashed(itemHash(item));
    }

    /**
     * Estimates the count of an item given as its {@link #itemHash(byte[])}.
     *
     * @param itemHash The item's hash
     * @return The estimated count (≥ true count with high probability)
     */
    public long estimateHashed(long itemHash) {
        long mixed = mix(itemHash);
        long min = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            min = Math.min(min, counters[row].get(column(mixed, row)));
        }
        return min;
    }

    /**
     * Estimates the relative frequency of an item.
     *
     * @param item The item to estimate
     * @return The estimated frequency (0.0 to 1.0)
     * @throws NullPointerException if item is null
     */
    public double estimateFrequency(byte[] item) {
        if (totalCount.get() == 0) {
            return 0.0;
        }
        return (double) estimate(item) / totalCount.get();
    }

    /**
     * Returns the total count of all items inserted.
     *
     * @return The total count
     */
    public long totalCount() {
        return totalCount.get();
    }

    /**
     * Returns the seed that places items in this sketch's columns.
     *
     * @return The seed
     */
    public long seed() {
        return seed;
    }

    /**
     * Returns the width (number of counters per row).
     *
     * @return The width
     */
    public int width() {
        return width;
    }

    /**
     * Returns the depth (number of rows/hash functions).
     *
     * @return The depth
     */
    public int depth() {
        return depth;
    }

    /**
     * Clears all counters.
     */
    public void clear() {
        // Acquire all locks
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i].lock();
        }
        try {
            for (int row = 0; row < depth; row++) {
                for (int col = 0; col < width; col++) {
                    counters[row].set(col, 0);
                }
            }
            totalCount.set(0);
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
        // Each counter is a long (8 bytes)
        return HEADER_SIZE + (long) width * depth * 8;
    }

    /**
     * Serializes this sketch to a MemorySegment.
     *
     * @param arena The arena to allocate from
     * @return The memory segment containing serialized data
     */
    public MemorySegment toMemorySegment(Arena arena) {
        long totalSize = HEADER_SIZE + (long) width * depth * 8;
        MemorySegment segment = arena.allocate(totalSize);

        // Write header
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC, MAGIC);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION, VERSION);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_WIDTH, width);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_DEPTH, depth);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED, seed);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_TOTAL_COUNT, totalCount.get());

        // Write counters
        long offset = HEADER_SIZE;
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), offset, counters[row].get(col));
                offset += 8;
            }
        }

        return segment;
    }

    /**
     * Deserializes a CountMinSketch from a MemorySegment.
     *
     * @param segment The memory segment to read from
     * @return A new CountMinSketch instance
     * @throws IllegalArgumentException if the segment is invalid
     */
    public static CountMinSketch fromMemorySegment(MemorySegment segment) {
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
            throw new IllegalArgumentException("Unsupported version: " + version + " (this build "
                + "reads version " + VERSION + "; the counters of another version sit in columns "
                + "this build does not look in)");
        }

        int width = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_WIDTH);
        int depth = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_DEPTH);
        long seed = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED);
        long totalCount = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_TOTAL_COUNT);

        // Validate segment size
        long expectedSize = HEADER_SIZE + (long) width * depth * 8;
        if (segment.byteSize() < expectedSize) {
            throw new IllegalArgumentException(
                "Segment size " + segment.byteSize() + " < expected " + expectedSize);
        }

        CountMinSketch sketch = new CountMinSketch(width, depth, seed);
        sketch.totalCount.set(totalCount);

        // Read counters
        long offset = HEADER_SIZE;
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                long count = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), offset);
                sketch.counters[row].set(col, count);
                offset += 8;
            }
        }

        return sketch;
    }

    /**
     * Returns this sketch in the layout {@link #toMemorySegment(Arena)} writes, as a heap array.
     *
     * <p>The counters and the total are read with every write stripe held, so the result is a
     * state the sketch was actually in: a concurrent insert lands entirely before or entirely
     * after it. {@link #toMemorySegment(Arena)} reads without the stripes, which is fine for a
     * sketch nothing is writing to and not for one that is persisted while it is live.</p>
     *
     * @return The serialized sketch, header included
     */
    public byte[] toBytes() {
        for (ReentrantLock stripe : locks) {
            stripe.lock();
        }
        try (Arena scratch = Arena.ofConfined()) {
            return toMemorySegment(scratch).toArray(ValueLayout.JAVA_BYTE);
        } finally {
            for (ReentrantLock stripe : locks) {
                stripe.unlock();
            }
        }
    }

    /**
     * Rebuilds a sketch from the bytes {@link #toBytes()} produced.
     *
     * <p>Stricter than {@link #fromMemorySegment(MemorySegment)} about the length: the bytes must
     * hold exactly one sketch of the shape the header declares. A caller reading them back from a
     * file has nothing else to tell it where the sketch ends, and bytes left over mean the header
     * is describing something other than what follows it.</p>
     *
     * @param bytes The serialized sketch
     * @return A new sketch with the stored shape, seed, counters and total
     * @throws IllegalArgumentException if the bytes are null, are not a sketch this build
     *         writes, or are not exactly as long as the declared shape requires
     */
    public static CountMinSketch fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null");
        }
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "Sketch bytes too short: " + bytes.length + " < " + HEADER_SIZE);
        }
        MemorySegment segment = MemorySegment.ofArray(bytes);
        int width = segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_WIDTH);
        int depth = segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_DEPTH);
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException(
                "Sketch declares an impossible shape: width " + width + ", depth " + depth);
        }
        long expected = HEADER_SIZE + (long) width * depth * Long.BYTES;
        if (bytes.length != expected) {
            throw new IllegalArgumentException("Sketch of width " + width + " and depth " + depth
                + " occupies " + expected + " bytes, not " + bytes.length);
        }
        // The layout's accessors are aligned, which a byte[]-backed segment cannot satisfy, so
        // the bytes are read out of an aligned native copy.
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment aligned = scratch.allocate(bytes.length, Long.BYTES);
            aligned.copyFrom(segment);
            return fromMemorySegment(aligned);
        }
    }

    /**
     * Mixes an item hash with this sketch's seed, giving the value its columns are derived from.
     *
     * <p>The SplitMix64 finaliser, so that every bit of the seed and of the item hash reaches
     * both halves the rows are derived from.</p>
     *
     * @param itemHash The item's hash
     * @return The mixed value
     */
    private long mix(long itemHash) {
        long z = itemHash ^ seed;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The column a row counts an item in: {@code (start + row * step) mod width}, with
     * {@code start} taken from the lower half of the mixed hash and {@code step} drawn by the
     * upper half from the residues coprime to the width.
     *
     * <p>Worked in {@code long} over residues already reduced below the width, so nothing wraps
     * and the sequence is an exact arithmetic progression modulo the width: distinct for the
     * first {@code width} rows because the step is a unit. Package-private so a test can hand it
     * a mixed value with a chosen upper half.</p>
     *
     * @param mixed The item's mixed hash
     * @param row   The row index
     * @return The column index (0 to width-1)
     */
    int column(long mixed, int row) {
        long start = Integer.toUnsignedLong((int) mixed) % width;
        long step = steps[(int) ((mixed >>> 32) % steps.length)];
        return (int) ((start + row * step) % width);
    }

    /**
     * The residues modulo {@code width} that are coprime to it.
     *
     * <p>A width of one has the single residue zero, which is coprime to one; every larger width
     * has at least the residue one.</p>
     *
     * @param width The sketch width, positive
     * @return The units modulo {@code width}, in ascending order
     */
    private static int[] unitsModulo(int width) {
        if (width == 1) {
            return new int[] {0};
        }
        int[] units = new int[width];
        int count = 0;
        for (int residue = 1; residue < width; residue++) {
            if (gcd(residue, width) == 1) {
                units[count++] = residue;
            }
        }
        return java.util.Arrays.copyOf(units, count);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    @Override
    public String toString() {
        return String.format("CountMinSketch[width=%d, depth=%d, totalCount=%d]",
            width, depth, totalCount.get());
    }
}
