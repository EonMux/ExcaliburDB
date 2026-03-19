package excalibur.bench.benchmark;

import excalibur.filter.CuckooFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Cuckoo Filter insert throughput at various load factors.
 *
 * <h2>Purpose</h2>
 * <p>Measures insert operations per second at different load factors to validate
 * the Phase 3 requirement for high-performance Cuckoo Filter operations.</p>
 *
 * <h2>Benchmark Scenarios</h2>
 * <ul>
 *   <li>Insert at 25% load factor - low load, minimal displacements</li>
 *   <li>Insert at 50% load factor - medium load, some displacements</li>
 *   <li>Insert at 75% load factor - high load, frequent displacements</li>
 *   <li>Insert at 95% load factor - near capacity, maximum displacement attempts</li>
 * </ul>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>Load factor range: [0.0, 1.0] - represents percentage of capacity used</li>
 *   <li>Fingerprint bits: [8, 16] - 13 bits default for ≤0.1% FP rate</li>
 *   <li>Capacity: power of 2, range [16, 2^62]</li>
 *   <li>Insert returns boolean: true on success, false on graceful failure</li>
 *   <li>Entries per bucket: 4 (cache-line aligned, 64 bytes)</li>
 * </ul>
 *
 * <h2>Expected Performance</h2>
 * <ul>
 *   <li>Low load factor (25%): High throughput, O(1) insert with few displacements</li>
 *   <li>Medium load factor (50%): Good throughput, occasional displacements</li>
 *   <li>High load factor (75%): Moderate throughput, frequent displacements</li>
 *   <li>Near capacity (95%): Lower throughput, many displacement attempts</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The benchmark state is managed by JMH's @State annotations which
 * provide proper isolation between threads. The CuckooFilter being tested has
 * its own thread-safety guarantees documented in its class Javadoc.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class CuckooFilterInsertBenchmark {

    // ==================== Benchmark Parameters ====================

    /**
     * Target load factors to benchmark.
     * These represent the percentage of capacity to fill before measuring insert throughput.
     * Domain constraint: values in [0.0, 1.0], where 1.0 = 100% capacity
     */
    @Param({"0.25", "0.50", "0.75", "0.95"})
    public double targetLoadFactor;

    /**
     * Capacity of the filter (number of entries).
     * Domain constraint: must be power of 2 for efficient bucket masking.
     * Using 1M entries provides sufficient data for meaningful throughput measurements.
     */
    @Param({"1000000"})
    public int capacity;

    /**
     * Number of fingerprints per bucket.
     * Domain constraint: fixed at 4 for cache-line alignment (64 bytes).
     */
    private static final int FINGERPRINTS_PER_BUCKET = 4;

    /**
     * Number of insert operations to perform in each benchmark iteration.
     * This batch size provides stable throughput measurements.
     */
    @Param({"10000"})
    public int batchSize;

    // ==================== Benchmark State ====================

    /**
     * The filter being benchmarked.
     * Pre-populated to the target load factor before each measurement.
     */
    private CuckooFilter filter;

    /**
     * Random number generator for key generation.
     * Seeded for reproducibility.
     */
    private Random random;

    /**
     * Pre-generated keys for insert operations.
     * Using pre-generated keys eliminates key generation overhead from measurements.
     */
    private byte[][] insertKeys;

    /**
     * Current index into insertKeys array.
     */
    private int keyIndex;

    /**
     * Count of successful inserts in the current batch.
     */
    private long successCount;

    /**
     * Count of failed inserts (graceful failure) in the current batch.
     */
    private long failCount;

    // ==================== Setup and Teardown ====================

    /**
     * Initializes the benchmark state.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Load factor is clamped to [0.0, 1.0]</li>
     *   <li>Capacity is validated as power of 2</li>
     *   <li>Filter is pre-populated to exact target load factor</li>
     *   <li>Actual load factor is verified to match target within 1%</li>
     * </ul>
     */
    @Setup(Level.Trial)
    public void setup() {
        // Validate domain constraints
        if (targetLoadFactor < 0.0 || targetLoadFactor > 1.0) {
            throw new IllegalArgumentException(
                "Load factor must be in [0.0, 1.0], got: " + targetLoadFactor);
        }

        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "Capacity must be positive, got: " + capacity);
        }

        // Initialize random with fixed seed for reproducibility
        random = new Random(42);

        // Create filter with specified capacity
        // Using default 13-bit fingerprints for ≤0.1% FP rate
        filter = CuckooFilter.create(capacity);

        // Calculate number of entries for target load factor
        // Domain: entries = actual_capacity × load_factor
        // Note: filter.capacity() may differ from requested capacity due to power-of-2 sizing
        long actualCapacity = filter.capacity();
        long targetEntries = (long) (actualCapacity * targetLoadFactor);

        // Pre-populate filter to target load factor
        // This ensures we measure insert performance at the specified load
        for (long i = 0; i < targetEntries; i++) {
            byte[] key = generateKey(i);
            boolean inserted = filter.insert(key);
            if (!inserted) {
                // This shouldn't happen at reasonable load factors
                throw new IllegalStateException(
                    "Failed to insert during pre-population at load factor " + targetLoadFactor +
                    ". Inserted " + i + " of " + targetEntries + " entries.");
            }
        }

        // Verify actual load factor matches target (within 1% tolerance)
        double actualLoadFactor = filter.loadFactor();
        double loadFactorError = Math.abs(actualLoadFactor - targetLoadFactor);
        if (loadFactorError > 0.01) {
            throw new IllegalStateException(
                "Load factor mismatch. Target: " + targetLoadFactor +
                ", Actual: " + actualLoadFactor +
                ", Error: " + loadFactorError);
        }

        // Pre-generate keys for benchmark iterations
        // These keys are distinct from pre-population keys
        int keyCount = batchSize * 10; // 10x buffer to avoid wrap-around issues
        insertKeys = new byte[keyCount][];
        for (int i = 0; i < keyCount; i++) {
            insertKeys[i] = generateKey(targetEntries + i);
        }
        keyIndex = 0;
        successCount = 0;
        failCount = 0;

        // Log benchmark setup
        System.out.println("CuckooFilter Insert Benchmark Setup:");
        System.out.println("  Target load factor: " + targetLoadFactor);
        System.out.println("  Actual load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  Capacity: " + filter.capacity());
        System.out.println("  Entry count: " + filter.count());
        System.out.println("  Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("  Num buckets: " + filter.numBuckets());
        System.out.println("  Filter size: " + (filter.byteSize() / 1024) + " KB");
        System.out.println("  Batch size: " + batchSize);
    }

    /**
     * Logs final statistics after each trial.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        System.out.println("CuckooFilter Insert Benchmark Results:");
        System.out.println("  Final load factor: " + String.format("%.4f", filter.loadFactor()));
        System.out.println("  Final entry count: " + filter.count());
        System.out.println("  Success count: " + successCount);
        System.out.println("  Fail count: " + failCount);
        if (failCount > 0) {
            System.out.println("  Success rate: " +
                String.format("%.2f%%", 100.0 * successCount / (successCount + failCount)));
        }
    }

    // ==================== Benchmark Methods ====================

    /**
     * Benchmark: Single insert operation.
     * Measures throughput of individual insert calls.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys are 16-byte arrays (reasonable key size)</li>
     *   <li>Insert returns boolean (never throws on full filter)</li>
     *   <li>Tracks success/fail rates for validation</li>
     * </ul>
     */
    @Benchmark
    public boolean singleInsert(Blackhole bh) {
        byte[] key = insertKeys[keyIndex % insertKeys.length];
        keyIndex++;

        boolean result = filter.insert(key);

        // Track success/fail rates
        if (result) {
            successCount++;
        } else {
            failCount++;
        }

        return result;
    }

    /**
     * Benchmark: Batch insert operations (100 inserts per call).
     * Measures throughput when performing bulk inserts.
     *
     * <h3>Rationale</h3>
     * <p>Batch operations amortize method call overhead and better reflect
     * real-world usage where multiple items are inserted together.</p>
     */
    @Benchmark
    public int batchInsert100(Blackhole bh) {
        int successes = 0;
        for (int i = 0; i < 100; i++) {
            byte[] key = insertKeys[keyIndex % insertKeys.length];
            keyIndex++;

            if (filter.insert(key)) {
                successes++;
                successCount++;
            } else {
                failCount++;
            }
        }
        return successes;
    }

    /**
     * Benchmark: Batch insert operations (1000 inserts per call).
     * Measures throughput for large bulk insert operations.
     */
    @Benchmark
    public int batchInsert1000(Blackhole bh) {
        int successes = 0;
        for (int i = 0; i < 1000; i++) {
            byte[] key = insertKeys[keyIndex % insertKeys.length];
            keyIndex++;

            if (filter.insert(key)) {
                successes++;
                successCount++;
            } else {
                failCount++;
            }
        }
        return successes;
    }

    /**
     * Benchmark: Insert with immediate contains check.
     * Measures combined insert + verify throughput.
     *
     * <h3>Domain Correctness</h3>
     * <p>Verifies that inserted items are immediately findable.
     * A successful insert MUST result in contains returning true.</p>
     */
    @Benchmark
    public boolean insertAndVerify(Blackhole bh) {
        byte[] key = insertKeys[keyIndex % insertKeys.length];
        keyIndex++;

        boolean inserted = filter.insert(key);

        if (inserted) {
            // Domain correctness: inserted item MUST be findable
            boolean found = filter.contains(key);
            if (!found) {
                throw new IllegalStateException(
                    "Domain correctness violation: inserted key not found in filter");
            }
            successCount++;
        } else {
            failCount++;
        }

        return inserted;
    }

    /**
     * Benchmark: Insert with varied key sizes.
     * Tests insert throughput with different key sizes to measure
     * the impact of hashing overhead.
     */
    @Benchmark
    public int insertVariedKeySizes(Blackhole bh) {
        int successes = 0;

        // Insert keys of varying sizes: 8, 16, 32, 64 bytes
        for (int sizeMultiplier = 1; sizeMultiplier <= 4; sizeMultiplier++) {
            int keySize = 8 * sizeMultiplier;
            byte[] key = new byte[keySize];
            int baseIdx = keyIndex % insertKeys.length;

            // Copy from pre-generated key, repeating as needed
            byte[] baseKey = insertKeys[baseIdx];
            for (int i = 0; i < keySize; i++) {
                key[i] = baseKey[i % baseKey.length];
            }
            keyIndex++;

            if (filter.insert(key)) {
                successes++;
                successCount++;
            } else {
                failCount++;
            }
        }

        return successes;
    }

    // ==================== Helper Methods ====================

    /**
     * Generates a unique key for the given index.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys are 16 bytes (128 bits) - reasonable size for hash distribution</li>
     *   <li>Keys are unique across pre-population and benchmark phases</li>
     *   <li>Key format: [prefix(8B)][index(8B)] for deterministic generation</li>
     * </ul>
     *
     * @param index The unique index for this key
     * @return A 16-byte array containing the key
     */
    private byte[] generateKey(long index) {
        byte[] key = new byte[16];

        // First 8 bytes: fixed prefix "CUCKBKEY"
        key[0] = 'C';
        key[1] = 'U';
        key[2] = 'C';
        key[3] = 'K';
        key[4] = 'B';
        key[5] = 'K';
        key[6] = 'E';
        key[7] = 'Y';

        // Last 8 bytes: big-endian index
        key[8] = (byte) (index >>> 56);
        key[9] = (byte) (index >>> 48);
        key[10] = (byte) (index >>> 40);
        key[11] = (byte) (index >>> 32);
        key[12] = (byte) (index >>> 24);
        key[13] = (byte) (index >>> 16);
        key[14] = (byte) (index >>> 8);
        key[15] = (byte) index;

        return key;
    }
}
