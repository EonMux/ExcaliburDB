package excalibur.filter.sketch;

import io.excalibur.core.hash.XxHash64;

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
 *   <li>Each row has an independent hash function</li>
 *   <li><strong>Insert:</strong> Increment counters at hash_i(item) % w for all rows</li>
 *   <li><strong>Estimate:</strong> Return minimum of all counters at hash positions</li>
 * </ul>
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

    /** Current serialization version. */
    private static final int VERSION = 1;

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
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative: " + count);
        }
        if (count == 0) {
            return;
        }

        // Use striped locking based on first hash (bitmask for guaranteed non-negative)
        int lockIndex = (int) ((hashItem(item, 0) & 0x7FFFFFFFFFFFFFFFL) % NUM_LOCKS);
        locks[lockIndex].lock();
        try {
            for (int row = 0; row < depth; row++) {
                int column = hashToColumn(item, row);
                counters[row].addAndGet(column, count);
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

        long min = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            int column = hashToColumn(item, row);
            long count = counters[row].get(column);
            min = Math.min(min, count);
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
            throw new IllegalArgumentException("Unsupported version: " + version);
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
     * Computes the hash for an item at a specific row.
     * Uses double hashing: hash1 + row * hash2
     *
     * @param item The item to hash
     * @param row  The row index
     * @return The hash value
     */
    private long hashItem(byte[] item, int row) {
        // Use different seeds for each row to get independent hash functions
        long hash1 = XxHash64.hash(item, seed);
        long hash2 = XxHash64.hash(item, seed ^ (row * 0x9E3779B97F4A7C15L));
        return hash1 + row * hash2;
    }

    /**
     * Maps an item to a column index for a specific row.
     *
     * @param item The item to hash
     * @param row  The row index
     * @return The column index (0 to width-1)
     */
    private int hashToColumn(byte[] item, int row) {
        long hash = hashItem(item, row);
        // Use bitmask to guarantee non-negative value (Math.abs fails for Long.MIN_VALUE)
        return (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % width);
    }

    @Override
    public String toString() {
        return String.format("CountMinSketch[width=%d, depth=%d, totalCount=%d]",
            width, depth, totalCount.get());
    }
}
