package excalibur.bench;

import io.excalibur.store.Segment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import excalibur.bench.metric.ThroughputValidator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick throughput test for point lookups.
 *
 * <p>This test validates the optimized lookup path and measures
 * approximate throughput to verify the ≥1B lookups/sec target is achievable.</p>
 *
 * <h2>What is enforced</h2>
 * <p>Every measurement is printed against the PROJECT GOAL of 1,000,000,000 ops/sec and against
 * {@link ThroughputValidator#TARGET_POINT_LOOKUP}, the realistic target the benchmark registry
 * is written against, so the distance from both stays visible. What is asserted is
 * {@link ThroughputValidator#POINT_LOOKUP_REGRESSION_FLOOR} - a target measured in wall-clock
 * time inside an ordinary JVM is not a pass/fail threshold, because the host moves it further
 * than a code change does. That constant records the measurement behind the distinction.</p>
 *
 * <p>These tests are tagged {@code benchmark} and excluded from ordinary builds because
 * they take tens of seconds. Run them with {@code ./gradlew :excalibur-bench:benchmarkTest}.</p>
 *
 * @author ExcaliburDB
 */
@Tag("benchmark")
class PointLookupThroughputTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path tempFile;
    private Segment segment;

    private byte[][] keys;
    private long[] keyHashes;

    // Note: Using 5000 entries to fit within Segment's default 1MB data region
    // Each entry is ~98 bytes (17 header + 16 key + 64 value + 1 tombstone)
    // 5000 * 98 = ~490KB, leaving room for overhead
    private static final int ENTRY_COUNT = 5_000;
    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 64;
    private static final int WARMUP_ITERATIONS = 10_000;
    /**
     * Iterations in one timed round.
     *
     * <p>Ten million, not fifty: the reading is now the best of {@link SteadyState#ROUNDS}
     * rounds rather than one pass, so the length of a single pass no longer has to be large
     * enough to swamp the JIT's ramp - which is a thing it could not reliably be anyway.</p>
     */
    private static final int MEASUREMENT_ITERATIONS = 10_000_000;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        tempFile = Files.createTempFile(tempDir, "throughput_test_", ".bin");

        // Calculate segment size with generous margins
        int minSlots = (int) Math.ceil(ENTRY_COUNT / Segment.LOAD_FACTOR_THRESHOLD);
        int slotCount = roundToPowerOf2(Math.max(minSlots, Segment.MIN_SEGMENT_CAPACITY));
        long slotTableSize = (long) slotCount * 24;
        long entrySize = 17 + KEY_SIZE + VALUE_SIZE + 1;
        // Allocate 50x the estimated size to ensure we have enough space
        long dataRegionSize = ENTRY_COUNT * entrySize * 50;
        long segmentSize = slotTableSize + dataRegionSize + 100 * 1024 * 1024; // +100MB extra

        segment = new Segment(tempFile, segmentSize, arena);

        // Populate segment
        Random random = new Random(42);
        keys = new byte[ENTRY_COUNT][];
        keyHashes = new long[ENTRY_COUNT];

        for (int i = 0; i < ENTRY_COUNT; i++) {
            byte[] key = new byte[KEY_SIZE];
            byte[] value = new byte[VALUE_SIZE];

            String keyStr = "key-" + String.format("%08d", i);
            byte[] keyBytes = keyStr.getBytes();
            System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, KEY_SIZE));

            random.nextBytes(value);

            keys[i] = key;
            segment.put(key, value);
        }

        // Pre-compute hashes
        for (int i = 0; i < ENTRY_COUNT; i++) {
            keyHashes[i] = segment.hashKey(keys[i]);
        }

        System.out.println("Setup complete:");
        System.out.println("  Entry count: " + segment.entryCount());
        System.out.println("  Slot count: " + segment.slotCount());
        System.out.println("  Load factor: " + String.format("%.3f", segment.loadFactor()));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    void testThroughputSequentialFastPath() {
        System.out.println("\n=== Sequential Lookup Fast Path ===");

        // Warmup
        segment.readLock().lock();
        try {
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                int idx = i % ENTRY_COUNT;
                segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
            }
        } finally {
            segment.readLock().unlock();
        }

        // Measurement - lock-free path with pre-acquired lock
        segment.readLock().lock();
        try {
            double opsPerSec = SteadyState.bestOpsPerSec(MEASUREMENT_ITERATIONS, () -> {
                long found = 0;
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    int idx = i % ENTRY_COUNT;
                    if (segment.getByHashWithoutLock(keys[idx], keyHashes[idx]) != null) {
                        found++;
                    }
                }
                // Returned rather than asserted here: SteadyState fails the round that did not
                // perform the lookups it was asked for, which is also the check that every
                // lookup found its entry.
                return found;
            });

            report(opsPerSec, MEASUREMENT_ITERATIONS);
            assertNoThroughputRegression(opsPerSec, "lock-free point lookup");
        } finally {
            segment.readLock().unlock();
        }
    }

    @Test
    void testThroughputWithLockAcquisition() {
        System.out.println("\n=== Lookup With Lock Acquisition ===");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            int idx = i % ENTRY_COUNT;
            segment.getByHash(keys[idx], keyHashes[idx]);
        }

        // Measurement - with lock acquisition. The same round length as every other gate here:
        // a tenth of it used to be the cheapest way to keep the pass short, and it bought a
        // reading a tenth of the way up the JIT's ramp.
        double opsPerSec = SteadyState.bestOpsPerSec(MEASUREMENT_ITERATIONS, () -> {
            long found = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                int idx = i % ENTRY_COUNT;
                if (segment.getByHash(keys[idx], keyHashes[idx]) != null) {
                    found++;
                }
            }
            return found;
        });

        report(opsPerSec, MEASUREMENT_ITERATIONS);
        assertNoThroughputRegression(opsPerSec, "point lookup with lock acquisition");
    }

    @Test
    void testBatchThroughput() {
        System.out.println("\n=== Batch Lookup Throughput ===");
        int batchSize = 100;
        int numBatches = MEASUREMENT_ITERATIONS / batchSize;

        // Warmup
        for (int b = 0; b < 100; b++) {
            segment.readLock().lock();
            try {
                for (int i = 0; i < batchSize; i++) {
                    int idx = (b * batchSize + i) % ENTRY_COUNT;
                    segment.getByHashWithoutLock(keys[idx], keyHashes[idx]);
                }
            } finally {
                segment.readLock().unlock();
            }
        }

        // Measurement
        int totalIterations = numBatches * batchSize;
        double opsPerSec = SteadyState.bestOpsPerSec(totalIterations, () -> {
            long found = 0;
            for (int b = 0; b < numBatches; b++) {
                segment.readLock().lock();
                try {
                    for (int i = 0; i < batchSize; i++) {
                        int idx = (b * batchSize + i) % ENTRY_COUNT;
                        if (segment.getByHashWithoutLock(keys[idx], keyHashes[idx]) != null) {
                            found++;
                        }
                    }
                } finally {
                    segment.readLock().unlock();
                }
            }
            return found;
        });

        System.out.println("  Batch size: " + batchSize);
        report(opsPerSec, totalIterations);
        assertNoThroughputRegression(opsPerSec, "batched point lookup");
    }

    @Test
    void testBatchApiThroughput() {
        System.out.println("\n=== Batch API Throughput ===");
        int batchSize = 100;
        int numBatches = 100_000;

        // Prepare batches
        byte[][][] batchKeys = new byte[numBatches][batchSize][];
        long[][] batchHashes = new long[numBatches][batchSize];

        Random random = new Random(123);
        for (int b = 0; b < numBatches; b++) {
            for (int i = 0; i < batchSize; i++) {
                int idx = random.nextInt(ENTRY_COUNT);
                batchKeys[b][i] = keys[idx];
                batchHashes[b][i] = keyHashes[idx];
            }
        }

        // Warmup
        for (int b = 0; b < 100; b++) {
            segment.batchGetByHash(batchKeys[b], batchHashes[b]);
        }

        // Measurement
        int totalIterations = numBatches * batchSize;
        double opsPerSec = SteadyState.bestOpsPerSec(totalIterations, () -> {
            long found = 0;
            for (int b = 0; b < numBatches; b++) {
                var results = segment.batchGetByHash(batchKeys[b], batchHashes[b]);
                for (var r : results) {
                    if (r != null) {
                        found++;
                    }
                }
            }
            return found;
        });

        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Num batches: " + numBatches);
        report(opsPerSec, totalIterations);
        assertNoThroughputRegression(opsPerSec, "batch API point lookup");
    }

    /**
     * Prints what a gate measured, in the shape every gate here prints it.
     *
     * @param opsPerSec  The steady-state throughput
     * @param operations How many operations one round performed
     */
    private static void report(double opsPerSec, long operations) {
        System.out.println("  Iterations per round: " + operations);
        System.out.println("  Rounds: " + SteadyState.ROUNDS + " (best taken)");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Latency: " + String.format("%.2f", 1_000_000_000.0 / opsPerSec)
            + " ns/op");
        System.out.println("  Target: "
            + formatOpsPerSec(ThroughputValidator.TARGET_POINT_LOOKUP)
            + (opsPerSec >= ThroughputValidator.TARGET_POINT_LOOKUP ? " (met)" : " (not met)"));
        System.out.println("  Enforced floor: "
            + formatOpsPerSec(ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR));
    }

    /**
     * Fails the test when measured throughput has fallen far enough to be a regression.
     *
     * <p>Without this the class measured a number, printed it, and asserted only that
     * the lookups returned the right values - so a throughput collapse could not fail
     * anything.</p>
     *
     * @param opsPerSec the measured throughput
     * @param what a label for the measurement
     */
    private static void assertNoThroughputRegression(double opsPerSec, String what) {
        assertTrue(opsPerSec >= ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR,
            String.format("%s throughput must be at least %s, got %s",
                what,
                formatOpsPerSec(ThroughputValidator.POINT_LOOKUP_REGRESSION_FLOOR),
                formatOpsPerSec(opsPerSec)));
    }

    private static String formatOpsPerSec(double ops) {
        if (ops >= 1_000_000_000) {
            return String.format("%.2f B ops/sec", ops / 1_000_000_000);
        } else if (ops >= 1_000_000) {
            return String.format("%.2f M ops/sec", ops / 1_000_000);
        } else if (ops >= 1_000) {
            return String.format("%.2f K ops/sec", ops / 1_000);
        }
        return String.format("%.0f ops/sec", ops);
    }

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
