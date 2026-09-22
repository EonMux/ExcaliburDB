package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.store.Segment;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-threaded throughput test for point lookups.
 *
 * <p>This test demonstrates the aggregate throughput achievable with
 * concurrent access across multiple threads and segments.</p>
 *
 * <h2>What is asserted</h2>
 * <p>The property this test can honestly establish is <em>scalability</em>: running the
 * same workload on {@link #NUM_THREADS} threads must deliver more aggregate throughput
 * than running it on one. Comparing the aggregate against the single-threaded
 * point-lookup target instead would compare two different workloads - this one is random
 * access across {@value #NUM_SEGMENTS} segments, while the single-threaded target was set
 * for sequential access within one - so the verdict would say more about cache locality
 * than about concurrency.</p>
 *
 * <p>The single-threaded baseline is measured here, in the same JVM, on exactly the same
 * workload, after a shared warmup. A regression that makes the segment lock serialize
 * readers fails this test; the previous version only asserted that the lookups returned
 * the right values and printed the throughput.</p>
 *
 * <p>Tagged {@code benchmark}; run with
 * {@code ./gradlew :excalibur-bench:benchmarkTest}.</p>
 *
 * @author ExcaliburDB
 */
@Tag("benchmark")
class MultiThreadedPointLookupTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Segment[] segments;
    private byte[][][] keysPerSegment;
    private long[][] hashesPerSegment;

    // Configuration
    private static final int NUM_SEGMENTS = 16;  // Multiple segments for sharding
    private static final int ENTRIES_PER_SEGMENT = 5000;
    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 64;
    private static final int ITERATIONS_PER_THREAD = 10_000_000;
    private static final int NUM_THREADS = Runtime.getRuntime().availableProcessors();

    /** Iterations used to warm the JIT before either measurement. */
    private static final int WARMUP_ITERATIONS = 1_000_000;

    /**
     * Minimum acceptable ratio of multi-threaded aggregate throughput to the
     * single-threaded baseline on the identical workload.
     *
     * <p>1.0 means "concurrency must not make the system slower overall". This is a
     * deliberately weak scalability claim, but it is one the test can establish and it
     * fails outright if reads ever serialize.</p>
     */
    private static final double MIN_SCALING_FACTOR = 1.0;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segments = new Segment[NUM_SEGMENTS];
        keysPerSegment = new byte[NUM_SEGMENTS][ENTRIES_PER_SEGMENT][];
        hashesPerSegment = new long[NUM_SEGMENTS][ENTRIES_PER_SEGMENT];

        for (int s = 0; s < NUM_SEGMENTS; s++) {
            Path segmentFile = Files.createTempFile(tempDir, "segment_" + s + "_", ".bin");

            // Calculate segment size
            int minSlots = (int) Math.ceil(ENTRIES_PER_SEGMENT / Segment.LOAD_FACTOR_THRESHOLD);
            int slotCount = roundToPowerOf2(Math.max(minSlots, Segment.MIN_SEGMENT_CAPACITY));
            long slotTableSize = (long) slotCount * 24;
            long entrySize = 17 + KEY_SIZE + VALUE_SIZE + 1;
            long dataRegionSize = ENTRIES_PER_SEGMENT * entrySize * 10;
            long segmentSize = slotTableSize + dataRegionSize + 1024 * 1024;

            segments[s] = new Segment(segmentFile, segmentSize, arena);

            // Populate segment
            Random random = new Random(42 + s);
            for (int i = 0; i < ENTRIES_PER_SEGMENT; i++) {
                byte[] key = new byte[KEY_SIZE];
                byte[] value = new byte[VALUE_SIZE];

                String keyStr = "seg" + s + "-key-" + String.format("%08d", i);
                byte[] keyBytes = keyStr.getBytes();
                System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, KEY_SIZE));

                random.nextBytes(value);

                keysPerSegment[s][i] = key;
                segments[s].put(key, value);
            }

            // Pre-compute hashes
            for (int i = 0; i < ENTRIES_PER_SEGMENT; i++) {
                hashesPerSegment[s][i] = segments[s].hashKey(keysPerSegment[s][i]);
            }
        }

        System.out.println("Setup complete:");
        System.out.println("  Segments: " + NUM_SEGMENTS);
        System.out.println("  Entries per segment: " + ENTRIES_PER_SEGMENT);
        System.out.println("  Total entries: " + (NUM_SEGMENTS * ENTRIES_PER_SEGMENT));
        System.out.println("  Available processors: " + NUM_THREADS);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Segment segment : segments) {
            if (segment != null) {
                segment.close();
            }
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    void testMultiThreadedThroughput() throws InterruptedException {
        System.out.println("\n=== Multi-Threaded Throughput Test ===");
        System.out.println("  Threads: " + NUM_THREADS);
        System.out.println("  Iterations per thread: " + ITERATIONS_PER_THREAD);

        Workload lockedLookup = (threadId, iterations) -> {
            int found = 0;
            Random random = new Random(123 + threadId);
            for (int i = 0; i < iterations; i++) {
                int segIdx = random.nextInt(NUM_SEGMENTS);
                int keyIdx = random.nextInt(ENTRIES_PER_SEGMENT);
                if (segments[segIdx].getByHash(
                        keysPerSegment[segIdx][keyIdx],
                        hashesPerSegment[segIdx][keyIdx]) != null) {
                    found++;
                }
            }
            return found;
        };

        measure(1, WARMUP_ITERATIONS, lockedLookup, "warmup");
        double baselineOpsPerSec = measure(1, ITERATIONS_PER_THREAD, lockedLookup, "single thread");
        double aggregateOpsPerSec =
            measure(NUM_THREADS, ITERATIONS_PER_THREAD, lockedLookup, NUM_THREADS + " threads");

        assertScales(baselineOpsPerSec, aggregateOpsPerSec, "locked point lookup");
    }

    @Test
    void testMultiThreadedBatchThroughput() throws InterruptedException {
        System.out.println("\n=== Multi-Threaded Batch Throughput Test ===");
        System.out.println("  Threads: " + NUM_THREADS);

        final int batchSize = 100;

        Workload batchedLookup = (threadId, iterations) -> {
            int found = 0;
            Random random = new Random(456 + threadId);
            int batches = iterations / batchSize;
            for (int b = 0; b < batches; b++) {
                int segIdx = random.nextInt(NUM_SEGMENTS);
                segments[segIdx].readLock().lock();
                try {
                    for (int i = 0; i < batchSize; i++) {
                        int keyIdx = random.nextInt(ENTRIES_PER_SEGMENT);
                        if (segments[segIdx].getByHashWithoutLock(
                                keysPerSegment[segIdx][keyIdx],
                                hashesPerSegment[segIdx][keyIdx]) != null) {
                            found++;
                        }
                    }
                } finally {
                    segments[segIdx].readLock().unlock();
                }
            }
            return found;
        };

        System.out.println("  Batch size: " + batchSize);

        measure(1, WARMUP_ITERATIONS, batchedLookup, "warmup");
        double baselineOpsPerSec = measure(1, ITERATIONS_PER_THREAD, batchedLookup, "single thread");
        double aggregateOpsPerSec =
            measure(NUM_THREADS, ITERATIONS_PER_THREAD, batchedLookup, NUM_THREADS + " threads");

        assertScales(baselineOpsPerSec, aggregateOpsPerSec, "batched point lookup");
    }

    // ==================== Measurement Harness ====================

    /**
     * A lookup workload executed by one thread.
     */
    @FunctionalInterface
    private interface Workload {
        /**
         * Performs {@code iterations} lookups.
         *
         * @param threadId identifies the thread, used to seed its key sequence
         * @param iterations how many lookups to perform
         * @return the number of lookups that found their entry
         */
        int run(int threadId, int iterations);
    }

    /**
     * Runs a workload on the given number of threads and returns aggregate throughput.
     *
     * <p>Every lookup targets a key that was inserted, so any miss means the segment
     * lost data under concurrent reads; that is asserted here rather than printed.</p>
     *
     * @param threads how many threads to run
     * @param iterationsPerThread lookups performed by each thread
     * @param workload the workload to run
     * @param label a label for the printed result
     * @return aggregate throughput in ops/sec
     * @throws InterruptedException if waiting for the workers is interrupted
     */
    private double measure(int threads, int iterationsPerThread, Workload workload, String label)
            throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicLong totalFound = new AtomicLong(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    totalFound.addAndGet(workload.run(threadId, iterationsPerThread));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startNanos = System.nanoTime();
        startLatch.countDown();
        doneLatch.await();
        long elapsedNanos = System.nanoTime() - startNanos;

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
            "worker threads must terminate");

        long totalIterations = (long) threads * iterationsPerThread;
        assertEquals(totalIterations, totalFound.get(),
            "every lookup targets an inserted key, so all must succeed");

        double opsPerSec = (totalIterations * 1_000_000_000.0) / elapsedNanos;
        System.out.printf("  %-16s %s (%.3f sec, %.2f ns/op)%n",
            label + ":", formatOpsPerSec(opsPerSec),
            elapsedNanos / 1_000_000_000.0, (double) elapsedNanos / totalIterations);
        return opsPerSec;
    }

    /**
     * Asserts that concurrency increases aggregate throughput.
     */
    private static void assertScales(double baselineOpsPerSec, double aggregateOpsPerSec,
                                     String what) {
        double scaling = aggregateOpsPerSec / baselineOpsPerSec;
        System.out.printf("  Scaling factor across %d threads: %.2fx%n", NUM_THREADS, scaling);

        assertTrue(scaling >= MIN_SCALING_FACTOR, String.format(
            "%s on %d threads must deliver at least %.2fx the single-threaded throughput "
                + "(%s), got %s (%.2fx) - concurrent reads are serializing",
            what, NUM_THREADS, MIN_SCALING_FACTOR,
            formatOpsPerSec(baselineOpsPerSec), formatOpsPerSec(aggregateOpsPerSec), scaling));
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
