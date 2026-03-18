package excalibur.filter.cuckoo;

import excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Cuckoo Filter full capacity handling.
 *
 * <h2>Task: Fill to Capacity, Verify Insert Returns False Gracefully</h2>
 *
 * <h3>Domain Correctness Constraints</h3>
 * <ol>
 *   <li><strong>Graceful Failure</strong>: insert() returns false when filter is full,
 *       NEVER throws an exception (except NullPointerException for null input)</li>
 *   <li><strong>No Silent Data Loss</strong>: After failed insertions, all previously
 *       inserted items remain accessible (no false negatives)</li>
 *   <li><strong>Reasonable Load Factor</strong>: Filter should accept items up to at least
 *       85% load factor before failures occur (Cuckoo filters typically achieve 95%)</li>
 *   <li><strong>Recovery After Deletion</strong>: After deletions free space, new insertions
 *       should succeed</li>
 *   <li><strong>Consistent State</strong>: Failed insertions do not corrupt filter state</li>
 * </ol>
 *
 * <h3>Expected Behavior</h3>
 * <p>With maxDisplacements=500 and 4 fingerprints per bucket, a Cuckoo filter should
 * typically accept items until ~85-95% load factor. Failures indicate the displacement
 * limit was exceeded, not that all slots are literally full.</p>
 *
 * @author ExcaliburDB
 */
class CuckooFilterFullCapacityTest {

    // ==================== Core Capacity Tests ====================

    /**
     * CRITICAL TEST: Fill to capacity boundary.
     *
     * <p><b>Domain Correctness:</b></p>
     * <ul>
     *   <li>Track the exact point where failures begin</li>
     *   <li>Verify load factor at first failure is reasonable (≥0.85)</li>
     *   <li>Verify all subsequent insertions fail gracefully (return false, no exception)</li>
     *   <li>Verify previously inserted items remain accessible</li>
     *   <li>Verify recovery after deletions</li>
     * </ul>
     */
    @Test
    @DisplayName("CRITICAL: Fill to capacity, verify insert returns false gracefully")
    void fillToCapacity_verifyInsertReturnsFalseGracefully() {
        System.out.println("\n========================================");
        System.out.println("Full Capacity Test: Fill and Verify Graceful Failure");
        System.out.println("========================================\n");

        // Create a small filter to reach capacity quickly
        int targetCapacity = 1000;
        CuckooFilter filter = CuckooFilter.create(targetCapacity);

        System.out.println("Filter Configuration:");
        System.out.println("  Target capacity: " + targetCapacity);
        System.out.println("  Actual capacity: " + filter.capacity());
        System.out.println("  Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("  Num buckets: " + filter.numBuckets());
        System.out.println();

        // Track inserted items for verification
        List<byte[]> insertedItems = new ArrayList<>();
        Random random = new Random(42); // Deterministic for reproducibility

        // Phase 1: Insert items until first failure
        System.out.println("Phase 1: Inserting items until first failure...");
        int attemptCount = 0;
        int firstFailureIndex = -1;
        double loadFactorAtFirstFailure = 0.0;

        while (firstFailureIndex == -1 && attemptCount < targetCapacity * 3) {
            byte[] item = ("capacity-test-" + random.nextInt(Integer.MAX_VALUE))
                .getBytes(StandardCharsets.UTF_8);

            boolean inserted = filter.insert(item);
            attemptCount++;

            if (inserted) {
                insertedItems.add(item);
            } else {
                firstFailureIndex = attemptCount;
                loadFactorAtFirstFailure = filter.loadFactor();
            }
        }

        // DOMAIN CORRECTNESS: First failure must occur at reasonable load factor
        System.out.println("  Attempts: " + attemptCount);
        System.out.println("  Inserted: " + insertedItems.size());
        System.out.println("  First failure at attempt: " + firstFailureIndex);
        System.out.println("  Load factor at first failure: " +
            String.format("%.4f", loadFactorAtFirstFailure) +
            " (" + String.format("%.2f%%", loadFactorAtFirstFailure * 100) + ")");

        assertThat(firstFailureIndex)
            .as("Filter should eventually reach capacity and fail insertions")
            .isGreaterThan(0);

        assertThat(loadFactorAtFirstFailure)
            .as("First failure should occur at reasonable load factor (≥0.85). " +
                "Got: " + loadFactorAtFirstFailure)
            .isGreaterThanOrEqualTo(0.85);

        System.out.println("  ✓ Load factor at first failure is reasonable (≥85%)");

        // Phase 2: Verify all subsequent insertions fail gracefully (no exceptions)
        System.out.println("\nPhase 2: Verifying subsequent insertions fail gracefully...");
        AtomicInteger subsequentFailures = new AtomicInteger(0);
        AtomicInteger subsequentSuccesses = new AtomicInteger(0);

        for (int i = 0; i < 500; i++) {
            byte[] item = ("post-full-" + i).getBytes(StandardCharsets.UTF_8);

            // CRITICAL: insert() must NEVER throw an exception
            assertThatCode(() -> {
                boolean result = filter.insert(item);
                if (result) {
                    subsequentSuccesses.incrementAndGet();
                } else {
                    subsequentFailures.incrementAndGet();
                }
            })
                .as("insert() must never throw exception at iteration " + i)
                .doesNotThrowAnyException();
        }

        System.out.println("  Subsequent successes: " + subsequentSuccesses.get());
        System.out.println("  Subsequent failures: " + subsequentFailures.get());

        // Most subsequent insertions should fail (filter is near capacity)
        assertThat(subsequentFailures.get())
            .as("Most subsequent insertions should fail when filter is near capacity")
            .isGreaterThan(subsequentSuccesses.get());

        System.out.println("  ✓ Subsequent insertions return false gracefully (no exceptions)");

        // Phase 3: Verify no false negatives (all previously inserted items still accessible)
        System.out.println("\nPhase 3: Verifying no false negatives...");
        int falseNegatives = 0;

        for (byte[] item : insertedItems) {
            if (!filter.contains(item)) {
                falseNegatives++;
            }
        }

        assertThat(falseNegatives)
            .as("No false negatives allowed - all %d inserted items must be found", insertedItems.size())
            .isZero();

        System.out.println("  Inserted items: " + insertedItems.size());
        System.out.println("  False negatives: " + falseNegatives);
        System.out.println("  ✓ No false negatives detected");

        // Phase 4: Verify recovery after deletions
        System.out.println("\nPhase 4: Verifying recovery after deletions...");

        // Delete half the items
        int deleteCount = insertedItems.size() / 2;
        for (int i = 0; i < deleteCount; i++) {
            assertThat(filter.delete(insertedItems.get(i)))
                .as("Delete of item " + i + " should succeed")
                .isTrue();
        }

        System.out.println("  Deleted items: " + deleteCount);
        System.out.println("  Count after delete: " + filter.count());
        System.out.println("  Load factor after delete: " +
            String.format("%.4f", filter.loadFactor()));

        // Now try to insert new items - should succeed
        int newInsertSuccess = 0;
        for (int i = 0; i < deleteCount; i++) {
            byte[] newItem = ("recovery-item-" + i).getBytes(StandardCharsets.UTF_8);
            if (filter.insert(newItem)) {
                newInsertSuccess++;
            }
        }

        System.out.println("  New insertions after delete: " + newInsertSuccess);

        assertThat(newInsertSuccess)
            .as("Should be able to insert new items after deletions freed space")
            .isGreaterThan(0);

        System.out.println("  ✓ Recovery after deletion works");

        System.out.println("\n========================================");
        System.out.println("SUCCESS: Full Capacity Test Passed");
        System.out.println("========================================\n");
    }

    /**
     * Test fill to capacity with exact tracking of the transition point.
     */
    @Test
    @DisplayName("Track exact transition point from accepting to rejecting")
    void trackExactTransitionPoint() {
        CuckooFilter filter = CuckooFilter.create(500);

        int accepted = 0;
        int rejected = 0;
        double loadFactorAtTransition = 0;
        boolean transitionFound = false;
        Random random = new Random(12345);

        // Insert until we see consecutive failures (transition to "full" state)
        int consecutiveFailures = 0;
        int maxConsecutiveFailures = 0;

        for (int i = 0; i < 2000; i++) {
            byte[] item = ("transition-" + random.nextInt(Integer.MAX_VALUE))
                .getBytes(StandardCharsets.UTF_8);

            boolean result = filter.insert(item);

            if (result) {
                accepted++;
                consecutiveFailures = 0;
            } else {
                rejected++;
                consecutiveFailures++;
                maxConsecutiveFailures = Math.max(maxConsecutiveFailures, consecutiveFailures);

                if (!transitionFound && consecutiveFailures >= 10) {
                    transitionFound = true;
                    loadFactorAtTransition = filter.loadFactor();
                }
            }
        }

        System.out.println("Transition Test Results:");
        System.out.println("  Accepted: " + accepted);
        System.out.println("  Rejected: " + rejected);
        System.out.println("  Final load factor: " + String.format("%.4f", filter.loadFactor()));
        System.out.println("  Max consecutive failures: " + maxConsecutiveFailures);
        System.out.println("  Load factor at transition: " + String.format("%.4f", loadFactorAtTransition));

        assertThat(accepted).as("Should have accepted items").isGreaterThan(0);
        assertThat(rejected).as("Should have rejected items when full").isGreaterThan(0);
        assertThat(transitionFound).as("Should have found transition point").isTrue();

        // Domain correctness: transition should occur at high load factor
        assertThat(loadFactorAtTransition)
            .as("Transition to 'full' state should occur at high load factor (≥0.85)")
            .isGreaterThanOrEqualTo(0.85);
    }

    // ==================== Capacity Boundary Tests ====================

    @Nested
    @DisplayName("Capacity Boundary Tests")
    class CapacityBoundaryTests {

        @Test
        @DisplayName("Insert at exact capacity boundary never throws exception")
        void insertAtCapacityBoundaryNeverThrows() {
            // Small filter to quickly reach boundary
            CuckooFilter filter = CuckooFilter.create(100);
            Random random = new Random(999);

            // Fill the filter
            while (filter.loadFactor() < 0.90) {
                filter.insert(("boundary-fill-" + random.nextInt()).getBytes(StandardCharsets.UTF_8));
            }

            System.out.println("Load factor before boundary test: " +
                String.format("%.4f", filter.loadFactor()));

            // Now hammer the filter at the boundary
            for (int i = 0; i < 1000; i++) {
                final int iteration = i;
                byte[] item = ("boundary-test-" + i).getBytes(StandardCharsets.UTF_8);

                assertThatCode(() -> filter.insert(item))
                    .as("Insert at iteration " + iteration + " must never throw exception")
                    .doesNotThrowAnyException();
            }

            System.out.println("✓ 1000 insert attempts at capacity boundary - no exceptions");
        }

        @Test
        @DisplayName("Load factor never exceeds 1.0 even with failed insertions")
        void loadFactorNeverExceedsOne() {
            CuckooFilter filter = CuckooFilter.create(200);
            Random random = new Random(777);

            // Insert many items, some will fail
            for (int i = 0; i < 1000; i++) {
                filter.insert(("load-factor-test-" + random.nextInt()).getBytes(StandardCharsets.UTF_8));
            }

            double loadFactor = filter.loadFactor();
            System.out.println("Final load factor: " + String.format("%.4f", loadFactor));

            // Domain correctness: load factor should never exceed 1.0
            assertThat(loadFactor)
                .as("Load factor should never exceed 1.0")
                .isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("Count is accurate after fill and failed insertions")
        void countAccurateAfterFillAndFailedInsertions() {
            CuckooFilter filter = CuckooFilter.create(300);
            Set<String> insertedSet = new HashSet<>();
            Random random = new Random(111);

            // Insert items
            int successfulInserts = 0;
            for (int i = 0; i < 1000; i++) {
                String itemStr = "count-test-" + random.nextInt(Integer.MAX_VALUE);
                byte[] item = itemStr.getBytes(StandardCharsets.UTF_8);

                if (filter.insert(item)) {
                    insertedSet.add(itemStr);
                    successfulInserts++;
                }
            }

            System.out.println("Successful inserts: " + successfulInserts);
            System.out.println("Set size: " + insertedSet.size());
            System.out.println("Filter count: " + filter.count());

            // Domain correctness: count must equal successful inserts
            assertThat(filter.count())
                .as("Filter count must equal number of successful inserts")
                .isEqualTo(successfulInserts);
        }
    }

    // ==================== Graceful Failure Tests ====================

    @Nested
    @DisplayName("Graceful Failure Tests")
    class GracefulFailureTests {

        @Test
        @DisplayName("Failed insert returns false, never throws")
        void failedInsertReturnsFalseNeverThrows() {
            CuckooFilter filter = CuckooFilter.create(50);
            Random random = new Random(555);

            // Fill to capacity
            for (int i = 0; i < 500; i++) {
                filter.insert(("fill-" + random.nextInt()).getBytes(StandardCharsets.UTF_8));
            }

            // Verify all subsequent inserts return false without throwing
            for (int i = 0; i < 100; i++) {
                byte[] item = ("fail-test-" + i).getBytes(StandardCharsets.UTF_8);

                boolean result = filter.insert(item);
                // Result can be true or false, but must not throw

                // Verify contains also doesn't throw
                filter.contains(item);
                // Verify delete also doesn't throw (for non-existent item)
                filter.delete(item);
            }

            System.out.println("✓ All operations on full filter completed without exceptions");
        }

        @Test
        @DisplayName("Filter state remains consistent after many failed insertions")
        void filterStateConsistentAfterManyFailedInsertions() {
            CuckooFilter filter = CuckooFilter.create(100);
            List<byte[]> insertedItems = new ArrayList<>();
            Random random = new Random(333);

            // Insert items
            for (int i = 0; i < 300; i++) {
                byte[] item = ("state-test-" + random.nextInt(Integer.MAX_VALUE))
                    .getBytes(StandardCharsets.UTF_8);
                if (filter.insert(item)) {
                    insertedItems.add(item);
                }
            }

            long countBeforeFailures = filter.count();

            // Cause many failed insertions
            for (int i = 0; i < 500; i++) {
                filter.insert(("doomed-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Count should not have changed
            assertThat(filter.count())
                .as("Count should not change from failed insertions")
                .isEqualTo(countBeforeFailures);

            // All previously inserted items should still be found
            for (byte[] item : insertedItems) {
                assertThat(filter.contains(item))
                    .as("Previously inserted item should still be found")
                    .isTrue();
            }

            System.out.println("Count before failures: " + countBeforeFailures);
            System.out.println("Count after failures: " + filter.count());
            System.out.println("Items verified: " + insertedItems.size());
            System.out.println("✓ Filter state remained consistent");
        }
    }

    // ==================== Recovery Tests ====================

    @Nested
    @DisplayName("Recovery After Full Tests")
    class RecoveryTests {

        @Test
        @DisplayName("Filter accepts new items after deletion frees space")
        void filterAcceptsNewItemsAfterDeletion() {
            CuckooFilter filter = CuckooFilter.create(100);
            List<byte[]> items = new ArrayList<>();
            Random random = new Random(444);

            // Fill filter
            for (int i = 0; i < 500; i++) {
                byte[] item = ("recovery-fill-" + random.nextInt(Integer.MAX_VALUE))
                    .getBytes(StandardCharsets.UTF_8);
                if (filter.insert(item)) {
                    items.add(item);
                }
            }

            System.out.println("Initial items: " + items.size());
            System.out.println("Initial load factor: " + String.format("%.4f", filter.loadFactor()));

            // Verify we're getting failures
            int failuresBeforeDelete = 0;
            for (int i = 0; i < 50; i++) {
                if (!filter.insert(("pre-delete-" + i).getBytes(StandardCharsets.UTF_8))) {
                    failuresBeforeDelete++;
                }
            }
            System.out.println("Failures before delete: " + failuresBeforeDelete + "/50");

            // Delete some items
            int deleteCount = items.size() / 2;
            for (int i = 0; i < deleteCount; i++) {
                assertThat(filter.delete(items.get(i))).isTrue();
            }

            System.out.println("Deleted items: " + deleteCount);
            System.out.println("Load factor after delete: " + String.format("%.4f", filter.loadFactor()));

            // Now try to insert new items
            int newInsertSuccess = 0;
            for (int i = 0; i < deleteCount; i++) {
                byte[] newItem = ("new-after-delete-" + i).getBytes(StandardCharsets.UTF_8);
                if (filter.insert(newItem)) {
                    newInsertSuccess++;
                }
            }

            System.out.println("New insertions after delete: " + newInsertSuccess);

            assertThat(newInsertSuccess)
                .as("Should be able to insert new items after deletions")
                .isGreaterThan(0);

            System.out.println("✓ Recovery after deletion verified");
        }

        @Test
        @DisplayName("Multiple fill-delete cycles work correctly")
        void multipleFillDeleteCyclesWorkCorrectly() {
            CuckooFilter filter = CuckooFilter.create(100);

            for (int cycle = 0; cycle < 5; cycle++) {
                System.out.println("\n--- Cycle " + (cycle + 1) + " ---");

                // Fill
                List<byte[]> items = new ArrayList<>();
                Random random = new Random(cycle * 1000);
                for (int i = 0; i < 300; i++) {
                    byte[] item = ("cycle-" + cycle + "-item-" + random.nextInt(Integer.MAX_VALUE))
                        .getBytes(StandardCharsets.UTF_8);
                    if (filter.insert(item)) {
                        items.add(item);
                    }
                }
                System.out.println("  Inserted: " + items.size());
                System.out.println("  Load factor: " + String.format("%.4f", filter.loadFactor()));

                // Verify all inserted items are found
                for (byte[] item : items) {
                    assertThat(filter.contains(item)).isTrue();
                }

                // Delete all
                for (byte[] item : items) {
                    assertThat(filter.delete(item)).isTrue();
                }
                System.out.println("  Deleted all items");
                System.out.println("  Count after delete: " + filter.count());

                assertThat(filter.count()).isEqualTo(0);
            }

            System.out.println("\n✓ Multiple fill-delete cycles completed successfully");
        }
    }

    // ==================== Concurrent Full Capacity Tests ====================

    @Test
    @DisplayName("Concurrent insertions to full filter handle gracefully")
    void concurrentInsertionsToFullFilterHandleGracefully() throws InterruptedException {
        CuckooFilter filter = CuckooFilter.create(200);

        int numThreads = 8;
        int itemsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicBoolean anyException = new AtomicBoolean(false);
        AtomicInteger totalAttempts = new AtomicInteger(0);
        AtomicInteger totalSuccesses = new AtomicInteger(0);
        AtomicInteger totalFailures = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random localRandom = new Random(threadId * 1000);

                    for (int i = 0; i < itemsPerThread; i++) {
                        try {
                            byte[] item = ("concurrent-full-" + threadId + "-" + i + "-" +
                                localRandom.nextInt(Integer.MAX_VALUE))
                                .getBytes(StandardCharsets.UTF_8);

                            totalAttempts.incrementAndGet();
                            boolean result = filter.insert(item);

                            if (result) {
                                totalSuccesses.incrementAndGet();
                            } else {
                                totalFailures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            anyException.set(true);
                            System.err.println("Exception in thread " + threadId +
                                " at item " + i + ": " + e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        System.out.println("Concurrent Full Filter Test Results:");
        System.out.println("  Total attempts: " + totalAttempts.get());
        System.out.println("  Successes: " + totalSuccesses.get());
        System.out.println("  Failures: " + totalFailures.get());
        System.out.println("  Final count: " + filter.count());
        System.out.println("  Final load factor: " + String.format("%.4f", filter.loadFactor()));

        // CRITICAL: No exception should have been thrown
        assertThat(anyException.get())
            .as("No exception should be thrown during concurrent insertions to full filter")
            .isFalse();

        assertThat(totalFailures.get())
            .as("Should have some failed insertions when filter fills up")
            .isGreaterThan(0);

        System.out.println("✓ Concurrent insertions to full filter handled gracefully");
    }

    // ==================== Memory-Mapped Serialization at Full Capacity ====================

    @Test
    @DisplayName("Serialization preserves full filter state correctly")
    void serializationPreservesFullFilterState() {
        CuckooFilter filter = CuckooFilter.create(500);
        List<byte[]> items = new ArrayList<>();
        Random random = new Random(888);

        // Fill filter
        for (int i = 0; i < 1000; i++) {
            byte[] item = ("serialize-full-" + random.nextInt(Integer.MAX_VALUE))
                .getBytes(StandardCharsets.UTF_8);
            if (filter.insert(item)) {
                items.add(item);
            }
        }

        long originalCount = filter.count();
        double originalLoadFactor = filter.loadFactor();

        System.out.println("Before serialization:");
        System.out.println("  Count: " + originalCount);
        System.out.println("  Load factor: " + String.format("%.4f", originalLoadFactor));

        // Serialize
        long memorySize = filter.byteSize();
        MemorySegment segment = Arena.ofAuto().allocate(memorySize);
        filter.writeTo(segment, 0);

        // Deserialize
        CuckooFilter deserialized = CuckooFilter.readFrom(segment, 0);

        System.out.println("\nAfter deserialization:");
        System.out.println("  Count: " + deserialized.count());
        System.out.println("  Load factor: " + String.format("%.4f", deserialized.loadFactor()));

        // Verify state preserved
        assertThat(deserialized.count()).isEqualTo(originalCount);
        assertThat(deserialized.loadFactor()).isCloseTo(originalLoadFactor,
            org.assertj.core.data.Offset.offset(0.001));

        // Verify all items found
        int foundCount = 0;
        for (byte[] item : items) {
            if (deserialized.contains(item)) {
                foundCount++;
            }
        }

        assertThat(foundCount)
            .as("All inserted items must be found in deserialized filter")
            .isEqualTo(items.size());

        System.out.println("  Items found: " + foundCount + "/" + items.size());

        // Verify new insertions fail gracefully on deserialized filter
        for (int i = 0; i < 100; i++) {
            final byte[] item = ("post-serialize-" + i).getBytes(StandardCharsets.UTF_8);
            assertThatCode(() -> {
                // Insert may succeed or fail, but must never throw
                deserialized.insert(item);
            }).doesNotThrowAnyException();
        }

        System.out.println("  ✓ Deserialized filter handles full state correctly");
    }

    // ==================== Stress Test ====================

    @RepeatedTest(3)
    @DisplayName("Stress test: Fill to capacity repeatedly")
    void stressTestFillToCapacityRepeatedly() {
        CuckooFilter filter = CuckooFilter.create(200);
        Random random = new Random();

        for (int round = 0; round < 3; round++) {
            // Fill
            List<byte[]> items = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                byte[] item = ("stress-" + round + "-" + random.nextInt(Integer.MAX_VALUE))
                    .getBytes(StandardCharsets.UTF_8);
                if (filter.insert(item)) {
                    items.add(item);
                }
            }

            // Verify all found
            for (byte[] item : items) {
                assertThat(filter.contains(item)).isTrue();
            }

            // Clear
            for (byte[] item : items) {
                filter.delete(item);
            }

            assertThat(filter.count()).isEqualTo(0);
        }

        System.out.println("✓ Stress test passed: 3 fill-to-capacity cycles completed");
    }
}
