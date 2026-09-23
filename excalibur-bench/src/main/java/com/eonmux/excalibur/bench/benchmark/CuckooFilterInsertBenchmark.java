package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

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
 * <h2>Holding the load factor</h2>
 * <p>An insert changes what it measures: every one raises the load factor, and a time-bounded
 * JMH iteration performs millions of them - far more than the filter has free slots. Left to
 * run, a trial fills the filter within its first second and then times refusals, each of which
 * walks a full displacement chain and rolls it back at around a hundred times the cost of an
 * insert. Cycling a small key set does the same through duplicates, which fill a key's two
 * buckets after eight copies.</p>
 *
 * <p>So every insert here is of a key not in the filter, and a {@link Level#Invocation}
 * teardown deletes whatever the invocation inserted, off the clock. Every timed insert
 * therefore sees the filter at exactly the target load factor. Invocation-level fixtures make
 * JMH timestamp each call, which adds a few tens of nanoseconds to a call as short as one
 * insert; {@link #batchInsert100} and {@link #batchInsert1000} spread that over many inserts,
 * and every method reports per-insert throughput through {@link OperationsPerInvocation}, so
 * the gap between {@link #singleInsert} and the batches is that overhead.</p>
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
     * Capacity the filter is created for.
     *
     * <p>The filter rounds its bucket count up to a power of two and keeps twice the buckets
     * the request strictly needs, so load factors are taken against
     * {@link CuckooFilter#capacity()}, not this parameter.</p>
     */
    @Param({"1000000"})
    public int capacity;

    /**
     * Keys in each fresh-key pool. A power of two, so the cursor wraps with a mask, and large
     * enough that successive invocations land in different buckets rather than re-warming the
     * same few cache lines.
     */
    private static final int KEY_POOL_SIZE = 1 << 16;

    /**
     * First index of the fresh-key sequence, far past any pre-population key's index.
     */
    private static final long FRESH_KEY_BASE = 1L << 40;

    /**
     * Key sizes {@link #insertVariedKeySizes} cycles through, one insert each per call.
     */
    private static final int[] VARIED_KEY_SIZES = {8, 16, 32, 64};

    /**
     * Most inserts any benchmark method makes in one invocation.
     */
    private static final int MAX_INSERTS_PER_INVOCATION = 1000;

    /**
     * How far the filled load factor may sit from the target before the trial is refused.
     */
    private static final double LOAD_FACTOR_TOLERANCE = 0.001;

    // ==================== Benchmark State ====================

    /**
     * The filter being benchmarked, held at the target load factor between invocations.
     */
    private CuckooFilter filter;

    /**
     * 16-byte keys that are not in the filter at the start of any invocation.
     */
    private byte[][] freshKeys;

    /**
     * Keys of 8, 16, 32 and 64 bytes, in that order, not in the filter at the start of any
     * invocation.
     */
    private byte[][] variedKeys;

    /**
     * Next position in the fresh-key pools.
     */
    private int keyCursor;

    /**
     * Keys the current invocation inserted, for {@link #restoreLoadFactor()} to delete.
     */
    private final byte[][] insertedThisInvocation = new byte[MAX_INSERTS_PER_INVOCATION][];

    /**
     * How many entries of {@link #insertedThisInvocation} are in use.
     */
    private int insertedCount;

    /**
     * Count of successful inserts over the trial.
     */
    private long successCount;

    /**
     * Count of refused inserts (graceful failure) over the trial.
     */
    private long failCount;

    // ==================== Setup and Teardown ====================

    /**
     * Initializes the benchmark state.
     *
     * <h3>Domain Correctness Verification</h3>
     * <ul>
     *   <li>Load factor is validated to lie in [0.0, 1.0]</li>
     *   <li>Filter is pre-populated to the target load factor of its real capacity</li>
     *   <li>Actual load factor is verified to match target within 0.1%</li>
     * </ul>
     */
    @Setup(Level.Trial)
    public void setup() {
        if (targetLoadFactor < 0.0 || targetLoadFactor > 1.0) {
            throw new IllegalArgumentException(
                "Load factor must be in [0.0, 1.0], got: " + targetLoadFactor);
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "Capacity must be positive, got: " + capacity);
        }

        // Using default 13-bit fingerprints for <=0.1% FP rate
        filter = CuckooFilter.create(capacity);

        long targetEntries = (long) (filter.capacity() * targetLoadFactor);
        for (long i = 0; i < targetEntries; i++) {
            if (!filter.insert(generateKey(i, 16))) {
                throw new IllegalStateException(
                    "Failed to insert during pre-population at load factor " + targetLoadFactor +
                    ". Inserted " + i + " of " + targetEntries + " entries.");
            }
        }

        double actualLoadFactor = filter.loadFactor();
        double loadFactorError = Math.abs(actualLoadFactor - targetLoadFactor);
        if (loadFactorError > LOAD_FACTOR_TOLERANCE) {
            throw new IllegalStateException(
                "Load factor mismatch. Target: " + targetLoadFactor +
                ", Actual: " + actualLoadFactor +
                ", Error: " + loadFactorError);
        }

        freshKeys = new byte[KEY_POOL_SIZE][];
        variedKeys = new byte[KEY_POOL_SIZE][];
        for (int i = 0; i < KEY_POOL_SIZE; i++) {
            freshKeys[i] = generateKey(FRESH_KEY_BASE + i, 16);
            variedKeys[i] = generateKey(FRESH_KEY_BASE + KEY_POOL_SIZE + i,
                VARIED_KEY_SIZES[i % VARIED_KEY_SIZES.length]);
        }
        keyCursor = 0;
        insertedCount = 0;
        successCount = 0;
        failCount = 0;

        System.out.println("CuckooFilter Insert Benchmark Setup:");
        System.out.println("  Target load factor: " + targetLoadFactor);
        System.out.println("  Actual load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  Capacity: " + filter.capacity());
        System.out.println("  Entry count: " + filter.count());
        System.out.println("  Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("  Num buckets: " + filter.numBuckets());
        System.out.println("  Filter size: " + (filter.byteSize() / 1024) + " KB");
    }

    /**
     * Deletes every key the invocation inserted, returning the filter to the target load
     * factor before the next timed call.
     *
     * <p>Only keys whose insert succeeded are deleted: deleting one that was refused could
     * remove another resident entry that happens to share its fingerprint and bucket pair.</p>
     *
     * @throws IllegalStateException if a key inserted moments ago cannot be deleted, which
     *         would be a false negative
     */
    @TearDown(Level.Invocation)
    public void restoreLoadFactor() {
        for (int i = 0; i < insertedCount; i++) {
            if (!filter.delete(insertedThisInvocation[i])) {
                throw new IllegalStateException(
                    "Domain correctness violation: a key inserted by this invocation could not "
                        + "be deleted");
            }
            insertedThisInvocation[i] = null;
        }
        insertedCount = 0;
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
     * Measures throughput of individual insert calls at the target load factor.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys are 16-byte arrays not already in the filter</li>
     *   <li>Insert returns boolean (never throws on full filter)</li>
     *   <li>Tracks success/fail rates for validation</li>
     * </ul>
     */
    @Benchmark
    public boolean singleInsert(Blackhole bh) {
        return insertFresh(nextFreshKey());
    }

    /**
     * Benchmark: Batch insert operations (100 inserts per call).
     * Measures throughput when performing bulk inserts; the score is per insert.
     *
     * <h3>Rationale</h3>
     * <p>Batch operations amortize method call overhead and better reflect
     * real-world usage where multiple items are inserted together.</p>
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int batchInsert100(Blackhole bh) {
        return insertBatch(100);
    }

    /**
     * Benchmark: Batch insert operations (1000 inserts per call).
     * Measures throughput for large bulk insert operations; the score is per insert.
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public int batchInsert1000(Blackhole bh) {
        return insertBatch(1000);
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
        byte[] key = nextFreshKey();
        boolean inserted = insertFresh(key);
        if (inserted && !filter.contains(key)) {
            throw new IllegalStateException(
                "Domain correctness violation: inserted key not found in filter");
        }
        return inserted;
    }

    /**
     * Benchmark: Insert with varied key sizes.
     * Tests insert throughput with 8, 16, 32 and 64 byte keys, one of each per call, to
     * measure the impact of hashing overhead. The score is per insert.
     *
     * <p>The keys are pre-generated and distinct, so building them is not timed and none of
     * them is a duplicate insert.</p>
     */
    @Benchmark
    @OperationsPerInvocation(4)
    public int insertVariedKeySizes(Blackhole bh) {
        int successes = 0;
        for (int i = 0; i < VARIED_KEY_SIZES.length; i++) {
            if (insertFresh(variedKeys[keyCursor++ & (KEY_POOL_SIZE - 1)])) {
                successes++;
            }
        }
        return successes;
    }

    // ==================== Helper Methods ====================

    /**
     * Takes the next key from the fresh-key pool.
     *
     * @return A 16-byte key not in the filter
     */
    private byte[] nextFreshKey() {
        return freshKeys[keyCursor++ & (KEY_POOL_SIZE - 1)];
    }

    /**
     * Inserts {@code count} fresh keys.
     *
     * @param count How many keys to insert
     * @return How many inserts succeeded
     */
    private int insertBatch(int count) {
        int successes = 0;
        for (int i = 0; i < count; i++) {
            if (insertFresh(nextFreshKey())) {
                successes++;
            }
        }
        return successes;
    }

    /**
     * Inserts one key, recording it for {@link #restoreLoadFactor()} if the filter took it.
     *
     * @param key A key not in the filter
     * @return Whether the insert succeeded
     */
    private boolean insertFresh(byte[] key) {
        if (filter.insert(key)) {
            insertedThisInvocation[insertedCount++] = key;
            successCount++;
            return true;
        }
        failCount++;
        return false;
    }

    /**
     * Generates a unique key for the given index.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Keys of one length differ exactly when their indices do</li>
     *   <li>Key format: [prefix][index(8B)], the prefix "CUCKBKEY" repeated to fill</li>
     * </ul>
     *
     * @param index   The unique index for this key
     * @param keySize The key length, at least 8
     * @return The key
     */
    private static byte[] generateKey(long index, int keySize) {
        byte[] prefix = {'C', 'U', 'C', 'K', 'B', 'K', 'E', 'Y'};
        byte[] key = new byte[keySize];
        for (int i = 0; i < keySize - 8; i++) {
            key[i] = prefix[i % prefix.length];
        }
        for (int i = 0; i < 8; i++) {
            key[keySize - 8 + i] = (byte) (index >>> (56 - 8 * i));
        }
        return key;
    }
}
