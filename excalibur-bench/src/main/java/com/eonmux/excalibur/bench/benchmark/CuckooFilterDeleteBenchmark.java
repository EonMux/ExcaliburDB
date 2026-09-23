package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.bench.kernel.FalsePositiveTally;
import com.eonmux.excalibur.filter.CuckooFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

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
 *   <li>Delete of an inserted key always succeeds - no false negatives</li>
 *   <li>Delete(x) removes an entry carrying x's fingerprint from one of x's two buckets</li>
 * </ul>
 *
 * <h2>What a correct filter may still answer</h2>
 * <p>A cuckoo filter stores fingerprints, not keys, so two answers about deletion are false
 * positives by construction. Deleting a key that was never inserted succeeds when a resident
 * entry in one of its buckets carries the same fingerprint - exactly the case in which a lookup
 * of it would be a false positive - and a deleted key still reads as present when some other
 * entry in one of its buckets does. Neither is a defect, so neither may be asserted away with a
 * hard check that a long enough trial is certain to trip:</p>
 * <ul>
 *   <li>The absent-key benchmarks use keys the filter was verified to rule out when the trial
 *       started. Those benchmarks never change the filter, so the verification holds for the
 *       whole trial, and a delete that then succeeds is a real defect.</li>
 *   <li>{@link #deleteAndVerify} tallies deleted keys that still read as present and fails the
 *       trial only if that rate is significantly above {@link #stillVisibleRate}, the false
 *       positive rate of a full filter. A delete that removed the wrong entry, or an alternate
 *       index that failed to round-trip, would put it orders of magnitude above that.</li>
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
     * Size of each absent-key pool. A power of two, so the cursor wraps with a mask.
     */
    private static final int ABSENT_POOL_SIZE = 1 << 16;

    /**
     * First index of the absent-key sequence, far past any resident key's index.
     */
    private static final long ABSENT_KEY_BASE = 1L << 40;

    /**
     * Key sizes {@link #deleteVariedKeySizes} cycles through, one delete each per call.
     */
    private static final int[] VARIED_KEY_SIZES = {8, 16, 32, 64};

    /**
     * How far the filled load factor may sit from the target before the trial is refused.
     */
    private static final double LOAD_FACTOR_TOLERANCE = 0.001;

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
     * Pre-generated keys for delete operations.
     * These keys were inserted during setup and will be deleted during benchmark.
     * Using pre-generated keys eliminates key generation overhead from measurements.
     */
    private byte[][] deleteKeys;

    /**
     * Keys that were never inserted and that the filter rules out, for the delete-miss path.
     */
    private byte[][] absentKeys;

    /**
     * Never-inserted keys of 8, 16, 32 and 64 bytes that the filter rules out, in that order.
     */
    private byte[][] variedAbsentKeys;

    /**
     * Current index into the absent key pools.
     */
    private int absentIndex;

    /**
     * Deleted keys {@link #deleteAndVerify} found still reported present, over all it checked.
     */
    private FalsePositiveTally stillVisible;

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
     *   <li>Actual load factor is verified to match target within 0.1%</li>
     *   <li>All pre-populated items are verified to be present</li>
     *   <li>Absent keys are screened so none is a false positive of the filled filter</li>
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

        // Create filter with specified capacity and fingerprint bits
        filter = CuckooFilter.create(capacity, fingerprintBits);

        // Calculate number of entries for target load factor
        // Domain: entries = actual_capacity × load_factor
        // Note: filter.capacity() may differ from requested capacity due to power-of-2 sizing
        long actualCapacity = filter.capacity();
        totalEntries = (long) (actualCapacity * targetLoadFactor);

        // Pre-populate filter to target load factor
        // Store keys for deletion during benchmark
        deleteKeys = new byte[(int) totalEntries][];

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

        absentKeys = screenAbsent(ABSENT_KEY_BASE, 16);
        variedAbsentKeys = screenAbsentVaried(ABSENT_KEY_BASE << 1);

        // Verify actual load factor matches target
        double actualLoadFactor = filter.loadFactor();
        double loadFactorError = Math.abs(actualLoadFactor - targetLoadFactor);
        if (loadFactorError > LOAD_FACTOR_TOLERANCE) {
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
        absentIndex = 0;
        successCount = 0;
        failCount = 0;
        stillVisible = new FalsePositiveTally(stillVisibleRate(fingerprintBits));

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
        System.out.println("========================================");
    }

    /**
     * Logs final statistics after each trial, and fails the trial if deleted keys stayed
     * visible significantly more often than fingerprint collisions account for.
     *
     * @throws IllegalStateException if {@link #deleteAndVerify} saw such an excess
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
        if (stillVisible.queries() > 0) {
            System.out.println("  Deleted keys still visible: " + stillVisible.falsePositives()
                + " of " + stillVisible.queries() + String.format(" (%.4f%%, budget %.4f%%)",
                    stillVisible.rate() * 100, stillVisible.specification() * 100));
        }
        System.out.println("========================================");

        // Each pass over the resident keys re-checks the same keys, so at most one pass is a
        // sample, and within it collisions come in groups: two resident keys sharing a
        // fingerprint and a bucket pair each keep the other visible. Half the resident keys is
        // therefore the independent sample.
        long independent = Math.min(stillVisible.queries(), totalEntries / 2);
        if (stillVisible.significantlyExceedsSpecification(Math.max(1, independent))) {
            throw new IllegalStateException(String.format(
                "Domain correctness violation: %d of %d deleted keys stayed visible (%.4f%%), "
                    + "significantly above the %.4f%% fingerprint collision rate",
                stillVisible.falsePositives(), stillVisible.queries(),
                stillVisible.rate() * 100, stillVisible.specification() * 100));
        }
    }

    /**
     * Upper bound on the expected chance that a deleted key still reads as present in a correct filter.
     *
     * <p>The key stays visible when another resident entry carries its fingerprint in one of its
     * two buckets. Such an entry's alternate bucket is derived from the same bucket and the same
     * fingerprint, so it shares the deleted key's bucket pair exactly: every resident key with
     * that fingerprint and pair is confined to those two buckets and is found, however full they
     * are. The rate is therefore that of a lookup false positive - about {@code 2b * load} other
     * keys' worth of chances at {@code 1 / (2^f - 1)} each, fingerprints being non-zero - and its
     * expectation is at most the rate of a completely full filter, which this returns. It is not the collision
     * chance of the seven slots left beside the deleted entry: conditioning on the key's own
     * slot does not remove the keys that share its pair, and a bound built that way is exceeded
     * by a correct filter at 95% load.</p>
     *
     * @param fingerprintBits The filter's fingerprint width
     * @return The false positive rate of a filter with every slot occupied
     */
    public static double stillVisibleRate(int fingerprintBits) {
        double perSlot = 1.0 / ((1 << fingerprintBits) - 1);
        return 1.0 - Math.pow(1.0 - perSlot, 2 * FINGERPRINTS_PER_BUCKET);
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
     * Measures throughput when performing bulk deletes; the score is per delete.
     *
     * <h3>Rationale</h3>
     * <p>Batch operations amortize method call overhead and better reflect
     * real-world usage where multiple items are deleted together.</p>
     */
    @Benchmark
    @OperationsPerInvocation(10)
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
     * Measures throughput for large bulk delete operations; the score is per delete.
     */
    @Benchmark
    @OperationsPerInvocation(100)
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
     * <p>A delete of a resident key must succeed. Whether the key then reads as absent depends
     * on the other entries in its buckets: another entry with the same fingerprint keeps it
     * visible, which is a false positive rather than a defect. Each check is therefore tallied,
     * and {@link #tearDown()} fails the trial if the rate is significantly above
     * {@link #stillVisibleRate}.</p>
     */
    @Benchmark
    public boolean deleteAndVerify(Blackhole bh) {
        int idx = keyIndex % (int) totalEntries;
        byte[] key = deleteKeys[idx];
        keyIndex++;

        boolean deleted = filter.delete(key);

        if (deleted) {
            stillVisible.record(filter.contains(key) ? 1 : 0, 1);
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
     * <p>Every key comes from {@link #absentKeys}, screened at setup to be neither inserted nor
     * a false positive of the filled filter. A delete that misses leaves the filter unchanged,
     * so the screening holds for the whole trial and a delete that succeeds is a defect.</p>
     */
    @Benchmark
    public boolean deleteNonExistent(Blackhole bh) {
        byte[] key = absentKeys[absentIndex++ & (ABSENT_POOL_SIZE - 1)];

        boolean result = filter.delete(key);

        if (result) {
            throw new IllegalStateException(
                "Domain correctness violation: delete succeeded for a key the filter ruled out");
        }
        failCount++;

        return result;
    }

    /**
     * Benchmark: Delete misses with varied key sizes.
     * Tests delete-miss throughput with 8, 16, 32 and 64 byte keys, one of each per call, to
     * measure the impact of hashing overhead. The score is per delete.
     *
     * <h3>Domain Correctness</h3>
     * <p>The keys are pre-generated and screened like {@link #deleteNonExistent}'s, so building
     * them is not timed and none of them may be deleted.</p>
     */
    @Benchmark
    @OperationsPerInvocation(4)
    public int deleteVariedKeySizes(Blackhole bh) {
        int successes = 0;

        for (int i = 0; i < VARIED_KEY_SIZES.length; i++) {
            byte[] key = variedAbsentKeys[absentIndex++ & (ABSENT_POOL_SIZE - 1)];
            if (filter.delete(key)) {
                throw new IllegalStateException("Domain correctness violation: delete succeeded "
                    + "for a " + key.length + "-byte key the filter ruled out");
            }
            failCount++;
        }

        return successes;
    }

    // ==================== Helper Methods ====================

    /**
     * Collects never-inserted keys the filled filter rules out.
     *
     * @param firstIndex Index of the first candidate key
     * @param keySize    Length of every key
     * @return {@link #ABSENT_POOL_SIZE} keys for which {@code contains} is false
     */
    private byte[][] screenAbsent(long firstIndex, int keySize) {
        byte[][] pool = new byte[ABSENT_POOL_SIZE][];
        long candidate = firstIndex;
        for (int i = 0; i < pool.length; candidate++) {
            byte[] key = sizedKey(candidate, keySize);
            if (!filter.contains(key)) {
                pool[i++] = key;
            }
        }
        return pool;
    }

    /**
     * Collects never-inserted keys of every size in {@link #VARIED_KEY_SIZES}, cycling through
     * the sizes, that the filled filter rules out.
     *
     * @param firstIndex Index of the first candidate key
     * @return {@link #ABSENT_POOL_SIZE} keys for which {@code contains} is false
     */
    private byte[][] screenAbsentVaried(long firstIndex) {
        byte[][] pool = new byte[ABSENT_POOL_SIZE][];
        long candidate = firstIndex;
        for (int i = 0; i < pool.length; candidate++) {
            byte[] key = sizedKey(candidate, VARIED_KEY_SIZES[i % VARIED_KEY_SIZES.length]);
            if (!filter.contains(key)) {
                pool[i++] = key;
            }
        }
        return pool;
    }

    /**
     * Builds a key of the given length whose last eight bytes are the big-endian index.
     *
     * <p>Every other byte is a fixed filler, so two keys of the same length differ exactly when
     * their indices do, and keys of different lengths never coincide.</p>
     *
     * @param index   The unique index for this key
     * @param keySize The key length, at least 8
     * @return The key
     */
    private static byte[] sizedKey(long index, int keySize) {
        byte[] key = new byte[keySize];
        for (int i = 0; i < keySize - 8; i++) {
            key[i] = (byte) ('A' + i % 26);
        }
        for (int i = 0; i < 8; i++) {
            key[keySize - 8 + i] = (byte) (index >>> (56 - 8 * i));
        }
        return key;
    }

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
