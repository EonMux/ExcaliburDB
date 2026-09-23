package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Cuckoo Filter lookup throughput at various load factors.
 *
 * <h2>Overview</h2>
 * <p>Measures the throughput of Cuckoo Filter contains operations under different
 * load factors to understand performance characteristics as the filter fills up.</p>
 *
 * <h2>Benchmark Scenarios</h2>
 * <ul>
 *   <li>Load factors: 0.50, 0.75, 0.90, 0.95</li>
 *   <li>Hit lookups: querying items that were inserted</li>
 *   <li>Miss lookups: querying items that were NOT inserted</li>
 *   <li>Mixed lookups: 50% hit, 50% miss</li>
 * </ul>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>Load factor values in range [0.0, 1.0]</li>
 *   <li>Throughput values expected in range [1M, 500M] ops/sec</li>
 *   <li>Lookup latency expected in range [2, 500] nanoseconds</li>
 *   <li>False positive rate at 95% load factor should be ≤0.1%</li>
 *   <li>No false negatives ever (contains returns true for all inserted items)</li>
 * </ul>
 *
 * <h2>Expected Performance Characteristics</h2>
 * <ul>
 *   <li>Lookup throughput should remain relatively stable across load factors</li>
 *   <li>O(1) average case - two bucket checks per lookup</li>
 *   <li>Cache-line aligned buckets (64 bytes) optimize for CPU cache hits</li>
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
public class CuckooFilterBenchmark {

    /**
     * Load factor targets to test.
     * These represent realistic operating ranges for the filter.
     * - 0.50: Lightly loaded, plenty of room for insertions
     * - 0.75: Moderate load, typical operating point
     * - 0.90: Heavy load, approaching capacity
     * - 0.95: Near-full, maximum practical utilization
     */
    @Param({"0.50", "0.75", "0.90", "0.95"})
    public double targetLoadFactor;

    /**
     * Capacity the filter is created for.
     *
     * <p>This is the capacity requested from {@link CuckooFilter#create(long, int)}, not the
     * one the filter ends up with: the filter rounds its bucket count up to a power of two and
     * keeps twice the buckets the request strictly needs, so 1M requested entries become
     * 2,097,152 slots. Load factors are always taken against {@link CuckooFilter#capacity()}.</p>
     */
    @Param({"1000000"})
    public long capacity;

    /**
     * Number of bits per fingerprint.
     * 13 bits provides ≤0.1% false positive rate at 95% load factor.
     * (Per spec: "Target FP rate ≤0.1%")
     */
    @Param({"13"})
    public int fingerprintBits;

    // The filter under test
    private CuckooFilter filter;

    // Keys that were inserted (for hit lookups)
    private byte[][] insertedKeys;

    // Keys that were NOT inserted (for miss lookups)
    private byte[][] nonInsertedKeys;

    // Number of entries actually inserted
    private long insertedCount;

    // Current lookup index for cycling through keys
    private int hitIndex;
    private int missIndex;

    // Random number generator with fixed seed for reproducibility
    private static final Random RANDOM = new Random(42);

    // How far the filled load factor may sit from the target before the trial is refused.
    private static final double LOAD_FACTOR_TOLERANCE = 0.001;

    @Setup(Level.Trial)
    public void setup() {
        // Create the filter with specified capacity and fingerprint bits
        filter = CuckooFilter.create(capacity, fingerprintBits);

        // Load factor is count over the filter's real capacity, which is larger than the
        // requested one (see the capacity parameter). Sizing the fill from the request left
        // every trial at under half its named load factor.
        insertedCount = (long) (filter.capacity() * targetLoadFactor);

        // Pre-generate keys for insertion
        // Keys are 16 bytes (128 bits) to represent realistic hash keys
        insertedKeys = new byte[(int) insertedCount][];
        for (int i = 0; i < insertedCount; i++) {
            insertedKeys[i] = generateKey(i);
        }

        // Miss keys continue the index sequence past the last inserted key, so the two sets
        // cannot overlap however the fill is sized.
        nonInsertedKeys = new byte[(int) insertedCount][];
        for (int i = 0; i < insertedCount; i++) {
            nonInsertedKeys[i] = generateKey((int) insertedCount + i);
        }

        // Insert all keys into the filter
        int successfulInserts = 0;
        for (int i = 0; i < insertedCount; i++) {
            if (filter.insert(insertedKeys[i])) {
                successfulInserts++;
            } else {
                // This shouldn't happen at these load factors with proper sizing
                System.err.println("Warning: Insert failed at index " + i +
                    " (load factor would be " + ((double) i / filter.capacity()) + ")");
                break;
            }
        }

        // The fill is sized from the filter's own capacity, so the only gap left is the
        // truncation of one entry; anything wider means inserts were refused.
        double actualLoadFactor = filter.loadFactor();
        if (Math.abs(actualLoadFactor - targetLoadFactor) > LOAD_FACTOR_TOLERANCE) {
            throw new IllegalStateException(
                "Actual load factor " + actualLoadFactor +
                " differs from target " + targetLoadFactor + " by more than "
                + LOAD_FACTOR_TOLERANCE);
        }

        // Verify no false negatives (all inserted items must be found)
        // This is a CRITICAL domain correctness check
        for (int i = 0; i < Math.min(1000, successfulInserts); i++) {
            if (!filter.contains(insertedKeys[i])) {
                throw new IllegalStateException(
                    "False negative detected! Inserted key at index " + i + " not found.");
            }
        }

        // Measure actual false positive rate (domain correctness verification)
        int falsePositives = 0;
        int fpSampleSize = Math.min(10000, nonInsertedKeys.length);
        for (int i = 0; i < fpSampleSize; i++) {
            if (filter.contains(nonInsertedKeys[i])) {
                falsePositives++;
            }
        }
        double measuredFPRate = (double) falsePositives / fpSampleSize;

        // Log benchmark setup information
        System.out.println("========================================");
        System.out.println("Cuckoo Filter Benchmark Setup");
        System.out.println("========================================");
        System.out.println("  Target load factor: " + targetLoadFactor);
        System.out.println("  Actual load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  Requested capacity: " + capacity);
        System.out.println("  Filter capacity: " + filter.capacity());
        System.out.println("  Inserted entries: " + successfulInserts);
        System.out.println("  Fingerprint bits: " + fingerprintBits);
        System.out.println("  Filter size: " + (filter.byteSize() / 1024) + " KB");
        System.out.println("  Measured FP rate: " + String.format("%.4f%%", measuredFPRate * 100));
        System.out.println("  FP rate target: ≤0.1%");
        System.out.println("========================================");

        // Initialize indices
        hitIndex = 0;
        missIndex = 0;
    }

    /**
     * Generates a deterministic 16-byte key from an index.
     * Keys are human-readable for debugging purposes.
     *
     * @param index The key index
     * @return A 16-byte key
     */
    private byte[] generateKey(int index) {
        byte[] key = new byte[16];
        // First 8 bytes: "key-" prefix + numeric index
        String prefix = "key-" + String.format("%011d", index);
        byte[] prefixBytes = prefix.getBytes();
        System.arraycopy(prefixBytes, 0, key, 0, Math.min(prefixBytes.length, 16));
        // Remaining bytes: random but deterministic
        RANDOM.setSeed(index + 12345L);
        for (int i = prefixBytes.length; i < 16; i++) {
            key[i] = (byte) (RANDOM.nextInt(256) - 128);
        }
        return key;
    }

    /**
     * Benchmark: Lookup of items that ARE present (hit path).
     * Measures the best-case throughput when all lookups succeed.
     *
     * <p>Expected: Very high throughput (100M+ ops/sec) since:
     * <ul>
     *   <li>Only 2 bucket checks needed on average</li>
     *   <li>Lock-free reads via VarHandle atomic operations</li>
     *   <li>Cache-line aligned bucket storage</li>
     * </ul>
     */
    @Benchmark
    public boolean lookupHit(Blackhole bh) {
        int idx = hitIndex % insertedKeys.length;
        hitIndex++;
        return filter.contains(insertedKeys[idx]);
    }

    /**
     * Benchmark: Lookup of items that are NOT present (miss path).
     * Measures throughput when all lookups fail.
     *
     * <p>Expected: Similar throughput to hit path since:
     * <ul>
     *   <li>Same 2 bucket checks needed</li>
     *   <li>No additional work for misses</li>
     * </ul>
     *
     * <p>Note: Some percentage of misses will return true due to false positives.
     * At 95% load factor with 13-bit fingerprints, expect ~0.1% false positives.
     */
    @Benchmark
    public boolean lookupMiss(Blackhole bh) {
        int idx = missIndex % nonInsertedKeys.length;
        missIndex++;
        return filter.contains(nonInsertedKeys[idx]);
    }

    /**
     * Benchmark: Mixed lookup pattern (50% hit, 50% miss).
     * Represents realistic query patterns where some items exist and some don't.
     * Each call makes two lookups, and the score is per lookup.
     */
    @Benchmark
    @OperationsPerInvocation(2)
    public int lookupMixed(Blackhole bh) {
        int found = 0;
        // Alternate between hit and miss lookups
        int hitIdx = hitIndex % insertedKeys.length;
        int missIdx = missIndex % nonInsertedKeys.length;
        hitIndex++;
        missIndex++;

        if (filter.contains(insertedKeys[hitIdx])) {
            found++;
        }
        if (filter.contains(nonInsertedKeys[missIdx])) {
            found++; // This will be a false positive
        }
        return found;
    }

    /**
     * Benchmark: Batch lookup (10 lookups per call).
     * Measures throughput when performing multiple lookups in a tight loop.
     * The score is per lookup, so it compares directly with {@link #lookupHit}.
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public int batchLookupHit(Blackhole bh) {
        int found = 0;
        for (int i = 0; i < 10; i++) {
            int idx = (hitIndex + i) % insertedKeys.length;
            if (filter.contains(insertedKeys[idx])) {
                found++;
            }
        }
        hitIndex += 10;
        return found;
    }

    /**
     * Benchmark: Sequential lookup pattern.
     * Tests cache-friendly access where consecutive lookups access nearby keys.
     * This simulates a scenario where keys are accessed in insertion order.
     */
    @Benchmark
    public boolean sequentialLookupHit(Blackhole bh) {
        // Access keys in order, which may have better cache behavior
        // since keys with similar hashes may land in nearby buckets
        int idx = hitIndex % insertedKeys.length;
        hitIndex++;
        return filter.contains(insertedKeys[idx]);
    }

    /**
     * Benchmark: Random lookup pattern.
     * Tests realistic access where keys are accessed in random order.
     * This is the default behavior of lookupHit since we cycle through all keys.
     */
    @Benchmark
    public boolean randomLookupHit(Blackhole bh) {
        // Use a pseudo-random access pattern based on index
        // This creates more cache misses than sequential access
        int idx = ((hitIndex * 7919) % insertedKeys.length + insertedKeys.length) % insertedKeys.length;
        hitIndex++;
        return filter.contains(insertedKeys[idx]);
    }

    /**
     * Benchmark: Combined insert + contains operations.
     * Measures the overhead of mixed read-write workload.
     * Note: This creates a separate filter to avoid polluting the main benchmark.
     *
     * <p>The workload cycles through its keys many times per iteration, so it records each key
     * with {@link CuckooFilter#insertIfAbsent} - the membership insert production code uses.
     * A plain insert would add another copy of every key on every pass; a key's two buckets
     * hold eight copies at most, after which every insert of it walks a full displacement
     * chain and fails, and the benchmark would be timing that instead.</p>
     */
    @State(Scope.Thread)
    public static class MixedWorkloadState {
        private CuckooFilter mixedFilter;
        private byte[][] keys;
        private int index;

        @Setup(Level.Iteration)
        public void setup() {
            // Create a fresh filter for each iteration
            mixedFilter = CuckooFilter.create(100000, 13);
            keys = new byte[200000][];
            for (int i = 0; i < keys.length; i++) {
                byte[] key = new byte[16];
                String prefix = "mix-" + String.format("%011d", i);
                byte[] prefixBytes = prefix.getBytes();
                System.arraycopy(prefixBytes, 0, key, 0, Math.min(prefixBytes.length, 16));
                keys[i] = key;
            }
            index = 0;
        }
    }

    /**
     * Benchmark: Mixed insert and contains operations.
     * Simulates a realistic workload where data is being added while queries occur.
     */
    @Benchmark
    public boolean mixedInsertContains(MixedWorkloadState state) {
        int idx = state.index % state.keys.length;
        state.index++;

        // Alternate between insert and contains
        if (idx % 10 == 0) {
            state.mixedFilter.insertIfAbsent(state.keys[idx]);
        }
        return state.mixedFilter.contains(state.keys[idx % (state.keys.length / 2)]);
    }
}
