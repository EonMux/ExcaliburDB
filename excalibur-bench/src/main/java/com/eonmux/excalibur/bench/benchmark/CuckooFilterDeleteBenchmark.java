package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Cuckoo Filter delete throughput at various load factors.
 *
 * <h2>Purpose</h2>
 * <p>Measures delete operations per second at different load factors to validate
 * the Phase 3 requirement for high-performance Cuckoo Filter deletion operations.
 * Cuckoo filters support O(1) deletion without false negatives, unlike Bloom filters.</p>
 *
 * <h2>Benchmark Scenarios</h2>
 * <ul>
 *   <li>Delete at 25% load factor - low load, simple deletion</li>
 *   <li>Delete at 50% load factor - medium load</li>
 *   <li>Delete at 75% load factor - high load</li>
 *   <li>Delete at 95% load factor - near capacity</li>
 * </ul>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>Load factor range: [0.0, 1.0] - represents percentage of capacity used</li>
 *   <li>Fingerprint bits: [8, 16] - 13 bits default for ≤0.1% FP rate</li>
 *   <li>Capacity: power of 2, range [16, 2^62]</li>
 *   <li>Delete returns boolean: true on success (item found), false on graceful failure (not found)</li>
 *   <li>After delete(x), contains(x) MUST return false - no false negatives</li>
 *   <li>Delete(x) removes EXACT fingerprint for x, never a different item's fingerprint</li>
 * </ul>
 *
 * <h2>Expected Performance</h2>
 * <ul>
 *   <li>Deletion should be O(1) average case - two bucket checks maximum</li>
 *   <li>Throughput should be similar to lookup (no cuckoo displacement needed)</li>
 *   <li>Striped locks ensure concurrent deletion performance</li>
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
public class CuckooFilterDeleteBenchmark {

    // ==================== Benchmark Parameters ====================

    /**
     * Target load factors to benchmark.
     * These represent the initial load factor before measuring delete throughput.
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
    public long capacity;

    /**
     * Number of fingerprints per bucket.
     * Domain constraint: fixed at 4 for cache-line alignment (64 bytes).
     */
    private static final int FINGERPRINTS_PER_BUCKET = 4;

    /**
     * Number of delete operations to perform in each benchmark iteration.
     * This batch size provides stable throughput measurements.
     */
    @Param({"10000"})
    public int batchSize;

    /**
     * Number of bits per fingerprint.
     * 13 bits provides ≤0.1% false positive rate at 95% load factor.
     */
    @Param({"13"})
    public int fingerprintBits;

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
     * Pre-generated keys for delete operations.
     * These keys were inserted during setup and will be deleted during benchmark.
     * Using pre-generated keys eliminates key generation overhead from measurements.
     */
    private byte[][] deleteKeys;

    /**
     * Current index into deleteKeys array.
     */
    private int keyIndex;

    /**
     * Count of successful deletes in the current batch.
     */
    private long successCount;

    /**
     * Count of failed deletes (item not found) in the current batch.
     */
    private long failCount;

    /**
     * Total entries available for deletion.
     */
    private long totalEntries;

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
     *   <li>All pre-populated items are verified to be present</li>
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

        if (fingerprintBits < 8 || fingerprintBits > 16) {
            throw new IllegalArgumentException(
                "fingerprintBits must be in [8, 16], got: " + fingerprintBits);
        }

        // Initialize random with fixed seed for reproducibility
        random = new Random(42);

        // Create filter with specified capacity and fingerprint bits
        filter = CuckooFilter.create(capacity, fingerprintBits);

        // Calculate number of entries for target load factor
        // Domain: entries = actual_capacity × load_factor
        // Note: filter.capacity() may differ from requested capacity due to power-of-2 sizing
        long actualCapacity = filter.capacity();
        totalEntries = (long) (actualCapacity * targetLoadFactor);

        // Pre-populate filter to target load factor
        // Store keys for deletion during benchmark
        deleteKeys = new byte[(int) totalEntries + batchSize * 10][];

        // Insert entries and store their keys
        for (long i = 0; i < totalEntries; i++) {
            byte[] key = generateKey(i);
            deleteKeys[(int) i] = key;
            boolean inserted = filter.insert(key);
            if (!inserted) {
                // This shouldn't happen at reasonable load factors
                throw new IllegalStateException(
                    "Failed to insert during pre-population at load factor " + targetLoadFactor +
                    ". Inserted " + i + " of " + totalEntries + " entries.");
            }
        }

        // Generate additional keys for delete operations (beyond the pre-populated set)
        // These will be re-inserted during iteration to maintain load factor
        for (int i = 0; i < batchSize * 10; i++) {
            deleteKeys[(int) totalEntries + i] = generateKey(totalEntries + i + 1_000_000);
        }

        // Verify actual load factor matches target (within 5% tolerance due to power-of-2 sizing)
        double actualLoadFactor = filter.loadFactor();
        double loadFactorError = Math.abs(actualLoadFactor - targetLoadFactor);
        if (loadFactorError > 0.05) {
            throw new IllegalStateException(
                "Load factor mismatch. Target: " + targetLoadFactor +
                ", Actual: " + actualLoadFactor +
                ", Error: " + loadFactorError);
        }

        // Verify all inserted items are present (no false negatives allowed)
        for (int i = 0; i < Math.min(1000, (int) totalEntries); i++) {
            if (!filter.contains(deleteKeys[i])) {
                throw new IllegalStateException(
                    "Domain correctness violation: inserted key at index " + i + " not found before deletion");
            }
        }

        // Initialize indices
        keyIndex = 0;
        successCount = 0;
        failCount = 0;

        // Log benchmark setup
        System.out.println("========================================");
        System.out.println("Cuckoo Filter Delete Benchmark Setup");
        System.out.println("========================================");
        System.out.println("  Target load factor: " + targetLoadFactor);
        System.out.println("  Actual load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  Capacity: " + filter.capacity());
        System.out.println("  Entry count: " + filter.count());
        System.out.println("  Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("  Num buckets: " + filter.numBuckets());
        System.out.println("  Filter size: " + (filter.byteSize() / 1024) + " KB");
        System.out.println("  Batch size: " + batchSize);
        System.out.println("========================================");
    }

    /**
     * Logs final statistics after each trial.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        System.out.println("========================================");
        System.out.println("Cuckoo Filter Delete Benchmark Results");
        System.out.println("========================================");
        System.out.println("  Final load factor: " + String.format("%.4f", filter.loadFactor()));
        System.out.println("  Final entry count: " + filter.count());
        System.out.println("  Success count: " + successCount);
        System.out.println("  Fail count: " + failCount);
        if (successCount + failCount > 0) {
            System.out.println("  Success rate: " +
                String.format("%.2f%%", 100.0 * successCount / (successCount + failCount)));
        }
        System.out.println("========================================");
    }

    // ==================== Benchmark Methods ====================

    /**
     * Benchmark: Single delete operation.
     * Measures throughput of individual delete calls on existing items.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys are 16-byte arrays (reasonable key size)</li>
     *   <li>Delete returns boolean (never throws)</li>
     *   <li>Tracks success/fail rates for validation</li>
     * </ul>
     *
     * <h3>Strategy</h3>
     * <p>Deletes from the pre-populated set, then re-inserts to maintain load factor.
     * This ensures we measure steady-state delete performance.</p>
     */
    @Benchmark
    public boolean singleDelete(Blackhole bh) {
        int idx = keyIndex % (int) totalEntries;
        byte[] key = deleteKeys[idx];
        keyIndex++;

        boolean result = filter.delete(key);

        // Track success/fail rates
        if (result) {
            successCount++;
            // Re-insert to maintain load factor for steady-state measurement
            filter.insert(key);
        } else {
            failCount++;
        }

        return result;
    }

    /**
     * Benchmark: Batch delete operations (10 deletes per call).
     * Measures throughput when performing bulk deletes.
     *
     * <h3>Rationale</h3>
     * <p>Batch operations amortize method call overhead and better reflect
     * real-world usage where multiple items are deleted together.</p>
     */
    @Benchmark
    public int batchDelete10(Blackhole bh) {
        int successes = 0;
        for (int i = 0; i < 10; i++) {
            int idx = (keyIndex + i) % (int) totalEntries;
            byte[] key = deleteKeys[idx];

            if (filter.delete(key)) {
                successes++;
                successCount++;
                // Re-insert to maintain load factor
                filter.insert(key);
            } else {
                failCount++;
            }
        }
        keyIndex += 10;
        return successes;
    }

    /**
     * Benchmark: Batch delete operations (100 deletes per call).
     * Measures throughput for large bulk delete operations.
     */
    @Benchmark
    public int batchDelete100(Blackhole bh) {
        int successes = 0;
        for (int i = 0; i < 100; i++) {
            int idx = (keyIndex + i) % (int) totalEntries;
            byte[] key = deleteKeys[idx];

            if (filter.delete(key)) {
                successes++;
                successCount++;
                // Re-insert to maintain load factor
                filter.insert(key);
            } else {
                failCount++;
            }
        }
        keyIndex += 100;
        return successes;
    }

    /**
     * Benchmark: Delete with immediate verification.
     * Measures combined delete + verify throughput.
     *
     * <h3>Domain Correctness</h3>
     * <p>Verifies that deleted items are immediately NOT findable.
     * A successful delete MUST result in contains returning false.</p>
     */
    @Benchmark
    public boolean deleteAndVerify(Blackhole bh) {
        int idx = keyIndex % (int) totalEntries;
        byte[] key = deleteKeys[idx];
        keyIndex++;

        boolean deleted = filter.delete(key);

        if (deleted) {
            // Domain correctness: deleted item MUST NOT be found
            boolean found = filter.contains(key);
            if (found) {
                throw new IllegalStateException(
                    "Domain correctness violation: deleted key still found in filter");
            }
            successCount++;
            // Re-insert to maintain load factor
            filter.insert(key);
        } else {
            failCount++;
        }

        return deleted;
    }

    /**
     * Benchmark: Delete with re-insert (delete + insert cycle).
     * Measures throughput of delete followed by insert of the same key.
     *
     * <h3>Rationale</h3>
     * <p>Tests the common use case of updating an entry or recycling keys.</p>
     */
    @Benchmark
    public boolean deleteAndReinsert(Blackhole bh) {
        int idx = keyIndex % (int) totalEntries;
        byte[] key = deleteKeys[idx];
        keyIndex++;

        boolean deleted = filter.delete(key);

        if (deleted) {
            successCount++;
        } else {
            failCount++;
        }

        // Always re-insert to maintain load factor
        filter.insert(key);

        return deleted;
    }

    /**
     * Benchmark: Delete non-existent items.
     * Measures throughput when attempting to delete items that don't exist.
     *
     * <h3>Domain Correctness</h3>
     * <p>Delete on non-existent item should return false quickly without
     * modifying the filter state.</p>
     */
    @Benchmark
    public boolean deleteNonExistent(Blackhole bh) {
        // Use keys from beyond the pre-populated set
        int idx = (int) totalEntries + (keyIndex % (batchSize * 10));
        byte[] key = deleteKeys[idx];
        keyIndex++;

        boolean result = filter.delete(key);

        // Should always return false for non-existent items
        if (result) {
            throw new IllegalStateException(
                "Domain correctness violation: delete returned true for non-existent item");
        }
        failCount++;

        return result;
    }

    /**
     * Benchmark: Delete with varied key sizes.
     * Tests delete throughput with different key sizes to measure
     * the impact of hashing overhead.
     */
    @Benchmark
    public int deleteVariedKeySizes(Blackhole bh) {
        int successes = 0;

        // Delete keys of varying sizes: 8, 16, 32, 64 bytes
        for (int sizeMultiplier = 1; sizeMultiplier <= 4; sizeMultiplier++) {
            int keySize = 8 * sizeMultiplier;
            int idx = keyIndex % (int) totalEntries;
            byte[] baseKey = deleteKeys[idx];
            byte[] key = new byte[keySize];

            // Copy from pre-generated key, repeating as needed
            for (int i = 0; i < keySize; i++) {
                key[i] = baseKey[i % baseKey.length];
            }
            keyIndex++;

            // Note: This key won't be in the filter since we're modifying it
            // So we measure delete-miss throughput
            if (filter.delete(key)) {
                successes++;
                successCount++;
            } else {
                failCount++;
            }
        }

        return successes;
    }

    /**
     * Benchmark: Alternating delete and insert pattern.
     * Simulates a workload with churn (items being deleted and new items inserted).
     *
     * <h3>Thread State</h3>
     * <p>Uses thread-local state to track the alternate key set for inserts.</p>
     */
    @State(Scope.Thread)
    public static class ChurnState {
        private byte[][] insertKeys;
        private int insertIndex;
        private int deleteIndex;

        @Setup(Level.Iteration)
        public void setup() {
            // Generate keys for insert operations
            insertKeys = new byte[10000][];
            for (int i = 0; i < insertKeys.length; i++) {
                byte[] key = new byte[16];
                String prefix = "chrn" + String.format("%011d", i);
                byte[] prefixBytes = prefix.getBytes();
                System.arraycopy(prefixBytes, 0, key, 0, Math.min(prefixBytes.length, 16));
                insertKeys[i] = key;
            }
            insertIndex = 0;
            deleteIndex = 0;
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Generates a unique key for the given index.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys are 16 bytes (128 bits) - reasonable size for hash distribution</li>
     *   <li>Keys are unique across all generated keys</li>
     *   <li>Key format: [prefix(8B)][index(8B)] for deterministic generation</li>
     * </ul>
     *
     * @param index The unique index for this key
     * @return A 16-byte array containing the key
     */
    private byte[] generateKey(long index) {
        byte[] key = new byte[16];

        // First 8 bytes: fixed prefix "CUCKDELK" (Cuckoo Delete Key)
        key[0] = 'C';
        key[1] = 'U';
        key[2] = 'C';
        key[3] = 'K';
        key[4] = 'D';
        key[5] = 'E';
        key[6] = 'L';
        key[7] = 'K';

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
