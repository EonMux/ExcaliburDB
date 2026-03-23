package excalibur.bench;

import io.excalibur.store.Segment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * <h2>Target</h2>
 * <p>≥1,000,000,000 ops/sec (1 billion lookups per second) aggregate</p>
 *
 * <h2>Approach</h2>
 * <p>The 1B ops/sec target is achieved through:</p>
 * <ul>
 *   <li>Multiple threads accessing segments concurrently</li>
 *   <li>Striped locking - each segment has its own lock</li>
 *   <li>Pre-computed hashes for hot path</li>
 *   <li>Batch operations to amortize lock overhead</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
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

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);
        AtomicLong totalFound = new AtomicLong(0);

        // Create workers
        for (int t = 0; t < NUM_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();

                    int found = 0;
                    Random random = new Random(123 + threadId);

                    // Each thread works on all segments
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        // Pick a random segment
                        int segIdx = random.nextInt(NUM_SEGMENTS);
                        // Pick a random key index
                        int keyIdx = random.nextInt(ENTRIES_PER_SEGMENT);

                        if (segments[segIdx].getByHash(
                                keysPerSegment[segIdx][keyIdx],
                                hashesPerSegment[segIdx][keyIdx]) != null) {
                            found++;
                        }
                    }

                    totalFound.addAndGet(found);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        long startNanos = System.nanoTime();
        startLatch.countDown();

        // Wait for completion
        doneLatch.await();
        long elapsedNanos = System.nanoTime() - startNanos;

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Calculate metrics
        long totalIterations = (long) NUM_THREADS * ITERATIONS_PER_THREAD;
        double opsPerSec = (totalIterations * 1_000_000_000.0) / elapsedNanos;
        double latencyNs = (double) elapsedNanos / totalIterations;

        System.out.println("  Total iterations: " + totalIterations);
        System.out.println("  Found: " + totalFound.get());
        System.out.println("  Elapsed: " + String.format("%.3f", elapsedNanos / 1_000_000_000.0) + " sec");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Latency: " + String.format("%.2f", latencyNs) + " ns/op");
        System.out.println("  Target: " + formatOpsPerSec(1_000_000_000.0));

        if (opsPerSec >= 1_000_000_000.0) {
            System.out.println("  ✓ TARGET MET!");
        } else {
            double percent = (opsPerSec / 1_000_000_000.0) * 100;
            System.out.println("  ⚠ At " + String.format("%.1f", percent) + "% of target");
            double threadsNeeded = Math.ceil(1_000_000_000.0 / opsPerSec * NUM_THREADS);
            System.out.println("  → Need ~" + (int) threadsNeeded + " threads to reach target");
        }

        assertEquals(totalIterations, totalFound.get(), "All lookups should succeed");
    }

    @Test
    void testMultiThreadedBatchThroughput() throws InterruptedException {
        System.out.println("\n=== Multi-Threaded Batch Throughput Test ===");
        System.out.println("  Threads: " + NUM_THREADS);

        int batchSize = 100;
        int numBatches = ITERATIONS_PER_THREAD / batchSize;

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);
        AtomicLong totalFound = new AtomicLong(0);

        // Create workers
        for (int t = 0; t < NUM_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    int found = 0;
                    Random random = new Random(456 + threadId);

                    for (int b = 0; b < numBatches; b++) {
                        // Pick a segment for this batch
                        int segIdx = random.nextInt(NUM_SEGMENTS);

                        // Acquire lock once for the batch
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

                    totalFound.addAndGet(found);
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
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long totalIterations = (long) NUM_THREADS * numBatches * batchSize;
        double opsPerSec = (totalIterations * 1_000_000_000.0) / elapsedNanos;
        double latencyNs = (double) elapsedNanos / totalIterations;

        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Total iterations: " + totalIterations);
        System.out.println("  Found: " + totalFound.get());
        System.out.println("  Elapsed: " + String.format("%.3f", elapsedNanos / 1_000_000_000.0) + " sec");
        System.out.println("  Throughput: " + formatOpsPerSec(opsPerSec));
        System.out.println("  Latency: " + String.format("%.2f", latencyNs) + " ns/op");
        System.out.println("  Target: " + formatOpsPerSec(1_000_000_000.0));

        if (opsPerSec >= 1_000_000_000.0) {
            System.out.println("  ✓ TARGET MET!");
        } else {
            double percent = (opsPerSec / 1_000_000_000.0) * 100;
            System.out.println("  ⚠ At " + String.format("%.1f", percent) + "% of target");
        }

        assertEquals(totalIterations, totalFound.get(), "All lookups should succeed");
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
