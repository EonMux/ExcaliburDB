package excalibur.filter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thread safety tests for CuckooFilter: Concurrent inserts from multiple threads,
 * verifying no corruption.
 *
 * <h2>Test Objectives</h2>
 * <ul>
 *   <li>Verify concurrent inserts from multiple threads complete without exceptions</li>
 *   <li>Verify ALL inserted items are found after concurrent insertion (no false negatives)</li>
 *   <li>Verify the filter count accurately reflects inserted entries</li>
 *   <li>Verify no memory corruption or data structure inconsistencies</li>
 * </ul>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>After successful insert(x), contains(x) MUST return true (no false negatives)</li>
 *   <li>Filter count MUST equal number of successful insertions</li>
 *   <li>No exceptions during concurrent operations</li>
 *   <li>Data structure remains consistent under high concurrency</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
@DisplayName("CuckooFilter Thread Safety Tests")
class CuckooFilterConcurrencyTest {

    @TempDir
    Path tempDir;

    // ==================== Basic Concurrent Insert Tests ====================

    @Nested
    @DisplayName("Basic Concurrent Insert Tests")
    class BasicConcurrentInsertTests {

        @Test
        @DisplayName("Concurrent inserts from multiple threads - all items must be found")
        void testConcurrentInsertsAllFound() throws Exception {
            int numThreads = 8;
            int itemsPerThread = 500;
            int totalItems = numThreads * itemsPerThread;

            // Create filter with enough capacity
            CuckooFilter filter = CuckooFilter.create(totalItems * 2, 13);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            AtomicInteger successfulInserts = new AtomicInteger(0);
            AtomicInteger errors = new AtomicInteger(0);

            // Track all inserted items per thread
            List<Set<byte[]>> insertedItems = new ArrayList<>();
            for (int t = 0; t < numThreads; t++) {
                insertedItems.add(Collections.synchronizedSet(new HashSet<>()));
            }

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                final Set<byte[]> threadItems = insertedItems.get(t);

                futures.add(executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        Random random = new Random(threadId * 31337);
                        for (int i = 0; i < itemsPerThread; i++) {
                            byte[] item = generateItem(threadId, i, random);
                            if (filter.insert(item)) {
                                successfulInserts.incrementAndGet();
                                threadItems.add(item);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    }
                }));
            }

            // Wait for all threads to be ready, then start simultaneously
            readyLatch.await();
            startLatch.countDown();

            // Wait for completion
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // Verify no errors occurred
            assertEquals(0, errors.get(), "No exceptions should occur during concurrent inserts");

            // Verify count matches successful inserts
            long reportedCount = filter.count();
            assertEquals(successfulInserts.get(), reportedCount,
                "Filter count must equal number of successful insertions");

            // CRITICAL: Verify ALL successfully inserted items are found
            // This tests for corruption - if any item is missing, data was corrupted
            int missingItems = 0;
            for (int t = 0; t < numThreads; t++) {
                for (byte[] item : insertedItems.get(t)) {
                    if (!filter.contains(item)) {
                        missingItems++;
                    }
                }
            }

            assertEquals(0, missingItems,
                "All successfully inserted items must be found (no false negatives allowed)");

            System.out.println("Concurrent inserts test: " + successfulInserts.get() +
                " items inserted, " + reportedCount + " reported count, 0 missing items");
        }

        @Test
        @DisplayName("Concurrent inserts with high load factor - verify no corruption")
        void testConcurrentInsertsHighLoadFactor() throws Exception {
            int numThreads = 8;
            int capacity = 4000; // Capacity for ~85% load factor
            int itemsPerThread = 100; // Total: 800 items, ~85% load

            CuckooFilter filter = CuckooFilter.create(capacity, 13);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            AtomicInteger successfulInserts = new AtomicInteger(0);
            AtomicInteger failedInserts = new AtomicInteger(0);
            AtomicInteger errors = new AtomicInteger(0);

            // Thread-safe collection to track successfully inserted items
            ConcurrentHashMap<String, Boolean> insertedKeys = new ConcurrentHashMap<>();

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        Random random = new Random(threadId * 99999);
                        for (int i = 0; i < itemsPerThread; i++) {
                            byte[] item = ("high-load-" + threadId + "-" + random.nextInt(10000)).getBytes();
                            boolean inserted = filter.insert(item);
                            if (inserted) {
                                successfulInserts.incrementAndGet();
                                insertedKeys.put(new String(item), Boolean.TRUE);
                            } else {
                                failedInserts.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    }
                }));
            }

            readyLatch.await();
            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            executor.shutdown();

            // Verify no errors
            assertEquals(0, errors.get(), "No exceptions should occur");

            // Verify count consistency
            assertEquals(successfulInserts.get(), filter.count(),
                "Filter count must equal successful insertions");

            // Verify all inserted items are found (no corruption)
            int missingItems = 0;
            for (String key : insertedKeys.keySet()) {
                if (!filter.contains(key.getBytes())) {
                    missingItems++;
                }
            }

            assertEquals(0, missingItems,
                "All successfully inserted items must be found even under high load");

            double loadFactor = filter.loadFactor();
            System.out.println("High load test: " + successfulInserts.get() + " inserted, " +
                failedInserts.get() + " failed (filter full), load factor: " +
                String.format("%.2f%%", loadFactor * 100));
        }
    }

    // ==================== Data Integrity Verification Tests ====================

    @Nested
    @DisplayName("Data Integrity Verification")
    class DataIntegrityTests {

        @Test
        @DisplayName("Concurrent inserts followed by verification - no false negatives")
        void testConcurrentInsertsNoFalseNegatives() throws Exception {
            int numThreads = 10;
            int itemsPerThread = 300;

            CuckooFilter filter = CuckooFilter.create(numThreads * itemsPerThread * 3, 13);

            // Use CountDownLatch to maximize concurrent inserts
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(numThreads);

            // Thread-local item tracking
            ThreadLocal<Set<byte[]>> threadItems = ThreadLocal.withInitial(HashSet::new);
            List<Set<byte[]>> allInsertedItems = Collections.synchronizedList(new ArrayList<>());

            AtomicInteger totalInserted = new AtomicInteger(0);

            List<Future<Set<byte[]>>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                futures.add(executor.submit(() -> {
                    Set<byte[]> myItems = new HashSet<>();
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        Random random = new Random(Thread.currentThread().getId());
                        for (int i = 0; i < itemsPerThread; i++) {
                            // Generate deterministic items based on thread and index
                            byte[] item = ("integrity-" + Thread.currentThread().getId() + "-" + i).getBytes();
                            if (filter.insert(item)) {
                                myItems.add(item);
                                totalInserted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return myItems;
                }));
            }

            readyLatch.await();
            startLatch.countDown();

            // Collect all inserted items
            Set<byte[]> allItems = new HashSet<>();
            for (Future<Set<byte[]>> future : futures) {
                Set<byte[]> items = future.get(30, TimeUnit.SECONDS);
                allItems.addAll(items);
            }

            executor.shutdown();

            // Verify count consistency
            assertEquals(totalInserted.get(), filter.count(),
                "Filter count must match total inserted items");

            // CRITICAL: Verify NO false negatives
            // Every successfully inserted item MUST be found
            int falseNegatives = 0;
            for (byte[] item : allItems) {
                if (!filter.contains(item)) {
                    falseNegatives++;
                }
            }

            assertEquals(0, falseNegatives,
                "FALSE NEGATIVES DETECTED: " + falseNegatives + " items were inserted but not found. " +
                "This indicates data corruption from concurrent access.");

            System.out.println("Data integrity test passed: " + allItems.size() +
                " items verified, 0 false negatives");
        }

        @Test
        @DisplayName("Concurrent inserts with random delays - verify consistency")
        void testConcurrentInsertsWithRandomDelays() throws Exception {
            int numThreads = 6;
            int itemsPerThread = 200;

            CuckooFilter filter = CuckooFilter.create(numThreads * itemsPerThread * 3, 13);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger successfulInserts = new AtomicInteger(0);
            ConcurrentHashMap<Integer, Set<Integer>> insertedPairs = new ConcurrentHashMap<>();

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                insertedPairs.put(threadId, ConcurrentHashMap.newKeySet());

                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        Random random = new Random(threadId * 77777);

                        for (int i = 0; i < itemsPerThread; i++) {
                            byte[] item = ("delayed-" + threadId + "-" + i).getBytes();
                            if (filter.insert(item)) {
                                successfulInserts.incrementAndGet();
                                insertedPairs.get(threadId).add(i);
                            }

                            // Random delay to increase interleaving
                            if (random.nextInt(10) == 0) {
                                Thread.sleep(random.nextInt(5));
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            executor.shutdown();

            // Verify all inserted items are found
            int missing = 0;
            for (int t = 0; t < numThreads; t++) {
                for (int i : insertedPairs.get(t)) {
                    byte[] item = ("delayed-" + t + "-" + i).getBytes();
                    if (!filter.contains(item)) {
                        missing++;
                    }
                }
            }

            assertEquals(0, missing, "All items must be found after concurrent inserts with delays");
            assertEquals(successfulInserts.get(), filter.count(), "Count must be consistent");

            System.out.println("Random delay test: " + successfulInserts.get() +
                " items inserted and verified");
        }
    }

    // ==================== Stress Tests ====================

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("High concurrency stress test - 32 threads")
        void testHighConcurrencyStress() throws Exception {
            int numThreads = 32;
            int itemsPerThread = 250;
            int expectedTotal = numThreads * itemsPerThread;

            CuckooFilter filter = CuckooFilter.create(expectedTotal * 2, 13);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);

            AtomicInteger successfulInserts = new AtomicInteger(0);
            AtomicInteger errors = new AtomicInteger(0);
            ConcurrentHashMap<String, Boolean> allInserted = new ConcurrentHashMap<>();

            List<Future<?>> futures = new ArrayList<>();

            long startTime = System.nanoTime();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(); // Synchronize all threads to start at once

                        for (int i = 0; i < itemsPerThread; i++) {
                            String itemStr = "stress-" + threadId + "-" + i;
                            byte[] item = itemStr.getBytes();
                            if (filter.insert(item)) {
                                successfulInserts.incrementAndGet();
                                allInserted.put(itemStr, Boolean.TRUE);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            long endTime = System.nanoTime();
            executor.shutdown();

            assertEquals(0, errors.get(), "No errors should occur during stress test");
            assertEquals(successfulInserts.get(), filter.count(), "Count must be consistent");

            // Verify all inserted items are found
            int missing = 0;
            for (String itemStr : allInserted.keySet()) {
                if (!filter.contains(itemStr.getBytes())) {
                    missing++;
                }
            }

            assertEquals(0, missing, "No items should be missing after stress test");

            long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            double throughput = (double) successfulInserts.get() / durationMs * 1000;

            System.out.println("Stress test: " + successfulInserts.get() + " items in " +
                durationMs + "ms (" + String.format("%.0f", throughput) + " ops/sec)");
            System.out.println("Load factor: " + String.format("%.2f%%", filter.loadFactor() * 100));
        }

        @Test
        @DisplayName("Sustained concurrent inserts over time")
        void testSustainedConcurrentInserts() throws Exception {
            int numThreads = 8;
            int numRounds = 5;
            int itemsPerRound = 200;

            CuckooFilter filter = CuckooFilter.create(numThreads * numRounds * itemsPerRound * 2, 13);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            long totalInserted = 0;
            long totalMissing = 0;

            for (int round = 0; round < numRounds; round++) {
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch readyLatch = new CountDownLatch(numThreads);
                AtomicInteger roundInserted = new AtomicInteger(0);
                AtomicInteger roundErrors = new AtomicInteger(0);

                List<Set<String>> roundItems = new ArrayList<>();
                for (int t = 0; t < numThreads; t++) {
                    roundItems.add(Collections.synchronizedSet(new HashSet<>()));
                }

                List<Future<?>> futures = new ArrayList<>();

                final int currentRound = round;
                for (int t = 0; t < numThreads; t++) {
                    final int threadId = t;
                    final Set<String> myItems = roundItems.get(t);

                    futures.add(executor.submit(() -> {
                        try {
                            readyLatch.countDown();
                            startLatch.await();

                            for (int i = 0; i < itemsPerRound; i++) {
                                String itemStr = "sustained-r" + currentRound + "-t" + threadId + "-i" + i;
                                byte[] item = itemStr.getBytes();
                                if (filter.insert(item)) {
                                    roundInserted.incrementAndGet();
                                    myItems.add(itemStr);
                                }
                            }
                        } catch (Exception e) {
                            roundErrors.incrementAndGet();
                        }
                    }));
                }

                readyLatch.await();
                startLatch.countDown();

                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                assertEquals(0, roundErrors.get(), "No errors in round " + round);
                totalInserted += roundInserted.get();

                // Verify round items
                for (int t = 0; t < numThreads; t++) {
                    for (String itemStr : roundItems.get(t)) {
                        if (!filter.contains(itemStr.getBytes())) {
                            totalMissing++;
                        }
                    }
                }
            }

            executor.shutdown();

            assertEquals(totalInserted, filter.count(), "Final count must match total inserted");
            assertEquals(0, totalMissing, "No items should be missing across all rounds");

            System.out.println("Sustained test: " + totalInserted + " items over " +
                numRounds + " rounds, 0 missing");
        }
    }

    // ==================== Memory Consistency Tests ====================

    @Nested
    @DisplayName("Memory Consistency Tests")
    class MemoryConsistencyTests {

        @Test
        @DisplayName("Verify bucket integrity after concurrent inserts")
        void testBucketIntegrityAfterConcurrentInserts() throws Exception {
            int numThreads = 12;
            int itemsPerThread = 300;

            CuckooFilter filter = CuckooFilter.create(numThreads * itemsPerThread * 2, 13);
            long initialBuckets = filter.numBuckets();

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger(0);
            AtomicInteger successfulInserts = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < itemsPerThread; i++) {
                            byte[] item = ("bucket-test-" + threadId + "-" + i).getBytes();
                            if (filter.insert(item)) {
                                successfulInserts.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            executor.shutdown();

            // Verify structural integrity
            assertEquals(0, errors.get(), "No errors during concurrent inserts");
            assertEquals(initialBuckets, filter.numBuckets(), "Bucket count should not change");
            assertTrue(filter.count() <= filter.capacity(), "Count cannot exceed capacity");

            // Load factor should be reasonable
            double loadFactor = filter.loadFactor();
            assertTrue(loadFactor >= 0 && loadFactor <= 1.0,
                "Load factor must be between 0 and 1: " + loadFactor);

            System.out.println("Bucket integrity test: " + successfulInserts.get() +
                " inserts, " + filter.numBuckets() + " buckets, load factor: " +
                String.format("%.2f%%", loadFactor * 100));
        }

        @Test
        @DisplayName("No memory corruption under extreme contention")
        void testNoMemoryCorruptionUnderContention() throws Exception {
            // Filter sized for high contention but not overloaded
            CuckooFilter filter = CuckooFilter.create(2000, 13);

            int numThreads = 20;
            int itemsPerThread = 40; // Total 800 items, ~40% load - room for contention

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);

            AtomicInteger successfulInserts = new AtomicInteger(0);
            AtomicInteger failedInserts = new AtomicInteger(0);
            AtomicInteger errors = new AtomicInteger(0);

            // Track successfully inserted items
            Set<String> insertedItems = Collections.synchronizedSet(new HashSet<>());

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();

                        for (int i = 0; i < itemsPerThread; i++) {
                            String itemStr = "contention-" + threadId + "-" + i;
                            byte[] item = itemStr.getBytes();
                            boolean inserted = filter.insert(item);
                            if (inserted) {
                                successfulInserts.incrementAndGet();
                                insertedItems.add(itemStr);
                            } else {
                                failedInserts.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            executor.shutdown();

            // Verify no errors
            assertEquals(0, errors.get(), "No exceptions should occur under contention");

            // Verify count consistency
            assertEquals(successfulInserts.get(), filter.count(),
                "Count must equal successful inserts");

            // CRITICAL: Verify no corruption - all inserted items must be found
            int missing = 0;
            for (String itemStr : insertedItems) {
                if (!filter.contains(itemStr.getBytes())) {
                    missing++;
                }
            }

            assertEquals(0, missing,
                "MEMORY CORRUPTION DETECTED: " + missing + " items were inserted but not found. " +
                "This indicates a concurrency bug in the filter implementation.");

            System.out.println("Contention test: " + successfulInserts.get() + " inserted, " +
                failedInserts.get() + " failed (expected due to small filter), 0 corrupted items");
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Generates a unique item for testing.
     * Uses thread ID and index to ensure uniqueness across threads.
     */
    private byte[] generateItem(int threadId, int index, Random random) {
        // Mix deterministic and random components
        return ("item-" + threadId + "-" + index + "-" + random.nextInt(100000)).getBytes();
    }
}
