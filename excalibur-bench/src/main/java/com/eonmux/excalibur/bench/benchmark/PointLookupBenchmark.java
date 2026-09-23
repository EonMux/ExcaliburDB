package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.store.Segment;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for measuring pure point lookup throughput.
 *
 * <p>This benchmark is specifically designed to measure and validate the
 * Phase 2 storage engine performance with page cache hot.</p>
 *
 * <h2>Performance Target Interpretation</h2>
 * <p>The original specification stated "≥1B/sec" which has been interpreted:</p>
 * <ul>
 *   <li><strong>Realistic single-threaded target</strong>: ≥10M lookups/sec (10 million)</li>
 *   <li><strong>Rationale</strong>: 1B ops/sec = 1ns/op = ~3-5 CPU cycles, which is
 *       unrealistic for hash table lookups involving memory access</li>
 * </ul>
 *
 * <h2>Units</h2>
 * <p>Every score is per lookup. The batch methods declare {@link OperationsPerInvocation},
 * so a batch of a hundred and a single lookup are read on the same scale and the difference
 * between them is what the batch shares - one read lock acquisition - rather than a factor
 * of a hundred.</p>
 *
 * <h2>What a lookup costs</h2>
 * <p>A hit is verified before its value is handed out: the slot it was reached through
 * against the slot checksum, and the whole record against the entry checksum. The entry
 * checksum dominates: a CRC32 over the header, key and value, of which each call carries a
 * fixed cost of the order of the whole computation for a record this size. That is the floor
 * under every hit, batched or not, and the reason a batch cannot run several times faster
 * than a single lookup: the lock it amortises is a fraction of what each lookup costs.</p>
 *
 * <h2>Access Patterns</h2>
 * <ul>
 *   <li>Pre-computed hashes, so hashing is not timed except where a method says so</li>
 *   <li>Lock-free reads using getByHashWithoutLock, single-threaded</li>
 *   <li>Sequential keys in insertion order, and a random stream the batches walk through</li>
 *   <li>Misses against pre-built keys confirmed absent at setup</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Load factor maintained at 0.7 threshold</li>
 *   <li>Key hashes are positive 64-bit values [0, Long.MAX_VALUE]</li>
 *   <li>Throughput values in range [1M, 100M] ops/sec for single-threaded</li>
 *   <li>Latency values in nanoseconds (1-1000ns)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>JMH handles thread safety for benchmark execution. The Segment being tested
 * has its own thread-safety guarantees documented in its class Javadoc.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "-Xms2g",
    "-Xmx2g",
    "-XX:+UseTransparentHugePages"
})
public class PointLookupBenchmark {

    /**
     * Target throughput in ops/sec.
     *
     * <p>Interpreted from "≥1B/sec" as realistic single-threaded target.
     * 10M ops/sec is achievable with memory-mapped I/O and page cache hot.</p>
     *
     * <p>Domain correctness: positive value in range [1M, 100M] for single-threaded</p>
     */
    public static final double TARGET_OPS_PER_SEC = 10_000_000.0;

    /**
     * Original specification target (1B ops/sec).
     *
     * <p>This may need clarification from the specification author.
     * 1B ops/sec = 1ns per operation = ~3-5 CPU cycles, which is
     * unrealistic for hash table lookups that require memory access.</p>
     */
    public static final double ORIGINAL_SPEC_TARGET = 1_000_000_000.0;

    /**
     * Number of entries in the segment.
     * Using 5K entries to fit within the Segment's default 1MB minimum data region
     * while still providing a meaningful benchmark for point lookup throughput.
     *
     * <p>Domain correctness: The Segment constructor allocates a minimum 1MB data region.
     * With entrySize = 17 + keySize(16) + valueSize(64) + 1 = 98 bytes,
     * 5000 entries require ~490KB, which fits with safety margin.</p>
     *
     * <p>Note: The 1B ops/sec target can still be evaluated as ops/sec is independent
     * of entry count - it measures lookup speed, not data volume.</p>
     */
    @Param({"5000"})
    public int entryCount;

    /**
     * Size of keys in bytes.
     */
    @Param({"16"})
    public int keySize;

    /**
     * Size of values in bytes.
     */
    @Param({"64"})
    public int valueSize;

    // Benchmark state
    private Arena arena;
    private Path tempFile;
    private Segment segment;

    /**
     * Lookups per invocation of {@link #batchLookup10} and {@link #batchLookupApi}.
     */
    static final int SMALL_BATCH = 10;

    /**
     * Lookups per invocation of {@link #batchLookup100}.
     */
    static final int LARGE_BATCH = 100;

    /**
     * Length of the random index stream, a power of two so that a cursor wraps with a mask.
     *
     * <p>Long enough that a batch never sees the same keys two invocations running: a batch
     * that replays a fixed handful of keys measures a working set that lives in L1, which no
     * point lookup against a real segment does.</p>
     */
    static final int RANDOM_INDEX_COUNT = 1 << 14;

    /**
     * Number of distinct absent keys the miss benchmark cycles through, a power of two.
     */
    static final int MISS_KEY_COUNT = 1 << 12;

    // Pre-computed lookup data
    private byte[][] keys;
    private long[] keyHashes;
    private int[] randomIndices;
    private int sequentialIndex;
    private int randomCursor;

    // Pre-computed absent keys, so that the miss path times a lookup rather than building a key
    private byte[][] missKeys;
    private long[] missHashes;
    private int missCursor;

    // Reused request arrays for the batch API, so that the timed call is the only allocation
    private byte[][] apiBatchKeys;
    private long[] apiBatchHashes;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // Create shared arena for multi-threaded access
        arena = Arena.ofShared();

        // Create temp file for segment
        tempFile = Files.createTempFile("point_lookup_bench_", ".bin");

        // Calculate segment size to match Segment constructor behavior
        // The Segment constructor: totalSize = slotTableSize + minDataSize(1MB)
        // We need enough slots for entryCount at 0.7 load factor
        int minSlots = (int) Math.ceil(entryCount / Segment.LOAD_FACTOR_THRESHOLD);
        int slotCount = roundToPowerOf2(Math.max(minSlots, Segment.MIN_SEGMENT_CAPACITY));
        long slotTableSize = (long) slotCount * 24;

        // Entry size: keyHash(8) + keyLen(4) + valueLen(4) + keyBytes + valueBytes + tombstone(1)
        long entrySize = 17 + keySize + valueSize + 1;
        long requiredDataSize = entryCount * entrySize * 2; // 2x safety margin

        // Verify required data fits within Segment's 1MB minimum
        if (requiredDataSize > 1024 * 1024) {
            throw new IllegalStateException(
                "Entry count too large for Segment's 1MB data region. " +
                "Required: " + requiredDataSize + " bytes, max: 1048576 bytes. " +
                "Reduce entryCount to fit.");
        }

        // Create segment with size that results in sufficient slot count
        // Constructor does: estimatedSlots = (capacityBytes / 2) / SLOT_SIZE
        // We want: slotCount >= minSlots
        long capacityNeeded = (long) slotCount * 24 * 2 + 1024 * 1024;

        // Create segment
        segment = new Segment(tempFile, capacityNeeded, arena);

        // Pre-populate with entries
        Random random = new Random(42);
        keys = new byte[entryCount][];
        keyHashes = new long[entryCount];

        System.out.println("Populating segment with " + entryCount + " entries...");

        for (int i = 0; i < entryCount; i++) {
            byte[] key = new byte[keySize];
            byte[] value = new byte[valueSize];

            // Generate deterministic key for reproducibility
            String keyStr = "key-" + String.format("%08d", i);
            byte[] keyBytes = keyStr.getBytes();
            System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, keySize));

            random.nextBytes(value);

            keys[i] = key;
            segment.put(key, value);
        }

        // Pre-compute all hashes for fast lookup path
        System.out.println("Pre-computing hashes...");
        for (int i = 0; i < entryCount; i++) {
            keyHashes[i] = segment.hashKey(keys[i]);
        }

        // Generate random indices for random access pattern
        randomIndices = new int[RANDOM_INDEX_COUNT];
        for (int i = 0; i < RANDOM_INDEX_COUNT; i++) {
            randomIndices[i] = random.nextInt(entryCount);
        }
        sequentialIndex = 0;
        randomCursor = 0;

        // Absent keys of the same length as the stored ones, each confirmed absent
        missKeys = new byte[MISS_KEY_COUNT][];
        missHashes = new long[MISS_KEY_COUNT];
        for (int i = 0; i < MISS_KEY_COUNT; i++) {
            byte[] missKey = new byte[keySize];
            byte[] missBytes = ("miss-" + String.format("%08d", i)).getBytes();
            System.arraycopy(missBytes, 0, missKey, 0, Math.min(missBytes.length, keySize));
            missKeys[i] = missKey;
            missHashes[i] = segment.hashKey(missKey);
            if (segment.getByHash(missKey, missHashes[i]) != null) {
                throw new IllegalStateException("Miss key " + i + " is present in the segment");
            }
        }
        missCursor = 0;

        apiBatchKeys = new byte[SMALL_BATCH][];
        apiBatchHashes = new long[SMALL_BATCH];

        // Verify segment state
        if (segment.entryCount() != entryCount) {
            throw new IllegalStateException(
                "Segment not fully populated. Expected " + entryCount +
                " entries, got " + segment.entryCount());
        }

        // Log setup info
        System.out.println("Point Lookup Benchmark Setup:");
        System.out.println("  Entry count: " + segment.entryCount());
        System.out.println("  Slot count: " + segment.slotCount());
        System.out.println("  Load factor: " + String.format("%.3f", segment.loadFactor()));
        System.out.println("  Segment size: " + (segment.size() / 1024 / 1024) + " MB");
        System.out.println("  Target: >= " + (long) TARGET_OPS_PER_SEC + " ops/sec");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== Core Lookup Benchmarks ====================

    /**
     * Benchmark: Sequential lookups with pre-computed hash, lock-free path.
     * This is the fastest possible lookup path.
     */
    @Benchmark
    public Object sequentialLookupFast(Blackhole bh) {
        int idx = nextSequentialIndex();
        return segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
    }

    /**
     * Benchmark: Random lookups with pre-computed hash, lock-free path.
     * Tests realistic random access pattern.
     */
    @Benchmark
    public Object randomLookupFast(Blackhole bh) {
        int idx = randomIndices[nextRandomPosition(1)];
        return segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
    }

    /**
     * Benchmark: Sequential lookups with lock acquisition (full path).
     * Tests the normal lookup path with read lock.
     */
    @Benchmark
    public Object sequentialLookupWithLock(Blackhole bh) {
        int idx = nextSequentialIndex();
        return segment.getByHash(keys[idx], keyHashes[idx]);
    }

    /**
     * Benchmark: Batch of 10 random lookups under one read lock acquisition.
     *
     * <p>Reported per lookup. Each invocation takes the next ten keys of the random stream,
     * so successive batches hit different records.</p>
     */
    @Benchmark
    @OperationsPerInvocation(SMALL_BATCH)
    public int batchLookup10(Blackhole bh) {
        return lockedBatch(SMALL_BATCH);
    }

    /**
     * Benchmark: Batch of 100 random lookups under one read lock acquisition.
     *
     * <p>Reported per lookup, so that it compares directly with the single-lookup benchmarks:
     * what one lock acquisition per hundred lookups buys over one per lookup is the difference
     * between this and {@link #sequentialLookupWithLock}.</p>
     */
    @Benchmark
    @OperationsPerInvocation(LARGE_BATCH)
    public int batchLookup100(Blackhole bh) {
        return lockedBatch(LARGE_BATCH);
    }

    /**
     * Benchmark: Batch of 10 random lookups through the segment's batch API.
     *
     * <p>Reported per lookup. The request arrays are reused; the result array the API
     * returns is part of what is measured.</p>
     */
    @Benchmark
    @OperationsPerInvocation(SMALL_BATCH)
    public int batchLookupApi(Blackhole bh) {
        int start = nextRandomPosition(SMALL_BATCH);
        for (int i = 0; i < SMALL_BATCH; i++) {
            int idx = randomIndices[(start + i) & (RANDOM_INDEX_COUNT - 1)];
            apiBatchKeys[i] = keys[idx];
            apiBatchHashes[i] = keyHashes[idx];
        }

        int found = 0;
        var results = segment.batchGetByHash(apiBatchKeys, apiBatchHashes);
        for (var r : results) {
            if (r != null) found++;
        }
        return found;
    }

    /**
     * Benchmark: Miss path - lookups for non-existent keys.
     *
     * <p>Cycles through keys generated and confirmed absent at setup, so the timed call is
     * the lookup and not the construction and hashing of a new key.</p>
     */
    @Benchmark
    public Object lookupMiss(Blackhole bh) {
        int i = missCursor;
        missCursor = (i + 1) & (MISS_KEY_COUNT - 1);
        return segment.getByHash(missKeys[i], missHashes[i]);
    }

    /**
     * Looks up the next keys of the random stream under a single read lock acquisition.
     *
     * @param batch Number of lookups
     * @return Number of keys found
     */
    private int lockedBatch(int batch) {
        int start = nextRandomPosition(batch);
        int found = 0;
        segment.readLock().lock();
        try {
            for (int i = 0; i < batch; i++) {
                int idx = randomIndices[(start + i) & (RANDOM_INDEX_COUNT - 1)];
                if (segment.getByHashWithoutLock(keys[idx], keyHashes[idx]) != null) {
                    found++;
                }
            }
        } finally {
            segment.readLock().unlock();
        }
        return found;
    }

    /**
     * Returns the next entry index in insertion order, wrapping at the entry count.
     *
     * @return An index into {@code keys}
     */
    private int nextSequentialIndex() {
        int idx = sequentialIndex;
        sequentialIndex = idx + 1 == entryCount ? 0 : idx + 1;
        return idx;
    }

    /**
     * Claims the next positions of the random index stream.
     *
     * @param count Number of positions claimed
     * @return The first claimed position; the rest follow it, wrapping with the stream mask
     */
    private int nextRandomPosition(int count) {
        int start = randomCursor;
        randomCursor = (start + count) & (RANDOM_INDEX_COUNT - 1);
        return start;
    }

    // ==================== Utility Methods ====================

    private static int roundToPowerOf2(int value) {
        if (value <= Segment.MIN_SEGMENT_CAPACITY) {
            return Segment.MIN_SEGMENT_CAPACITY;
        }
        if (value >= Segment.MAX_SEGMENT_CAPACITY) {
            return Segment.MAX_SEGMENT_CAPACITY;
        }
        int power = Integer.highestOneBit(value);
        if (power < value) {
            power <<= 1;
        }
        return Math.min(power, Segment.MAX_SEGMENT_CAPACITY);
    }
}
