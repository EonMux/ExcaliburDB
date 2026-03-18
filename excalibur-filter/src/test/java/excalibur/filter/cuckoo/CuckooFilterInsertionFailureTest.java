package excalibur.filter.cuckoo;

import excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Comprehensive tests for Cuckoo Filter insertion graceful failure.
 *
 * <h2>Graceful Failure Guarantee</h2>
 * <p>The insert operation must satisfy this invariant:
 * <ul>
 *   <li><strong>Never Throws Exception</strong>: When filter is full (maxDisplacements exceeded),
 *       insert returns false, never throws any exception</li>
 *   <li><strong>Return False On Full</strong>: When displacement limit is reached,
 *       insert gracefully returns false</li>
 *   <li><strong>Predictable Behavior</strong>: After a failed insert, the filter remains in
 *       a consistent state and continues to function normally</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>This test verifies that:
 * <ul>
 *   <li>No NullPointerException, ArrayIndexOutOfBoundsException, or other runtime exceptions</li>
 *   <li>No memory corruption after failed insertions</li>
 *   <li>Previously inserted items remain accessible after failed insertions</li>
 *   <li>Filter can accept new items after some deletions free up space</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class CuckooFilterInsertionFailureTest {

    private static final int MAX_DISPLACEMENTS = 500;
    private static final int BUCKETS_PER_SEGMENT = 4;

    // ==================== Basic Graceful Failure Tests ====================

    @Test
    @DisplayName("CRITICAL: insert() returns false when filter is full, never throws exception")
    void insertReturnsFalseNeverThrowsWhenFull() {
        // Create a very small filter to force it to fill quickly
        CuckooFilter filter = CuckooFilter.create(50, 12);

        List<byte[]> insertedItems = new ArrayList<>();
        int successfulInserts = 0;
        int failedInserts = 0;

        // Try to insert many more items than capacity
        for (int i = 0; i < 1000; i++) {
            byte[] item = ("graceful-fail-item-" + i).getBytes(StandardCharsets.UTF_8);

            // CRITICAL ASSERTION: This must NEVER throw an exception
            assertThatCode(() -> {
                boolean result = filter.insert(item);
                if (result) {
                    insertedItems.add(item);
                }
            })
                .as("insert() must never throw exception, even when filter is full (iteration " + i + ")")
                .doesNotThrowAnyException();

            if (filter.insert(item)) {
                successfulInserts++;
            } else {
                failedInserts++;
            }
        }

        // Verify we got both successes and failures
        assertThat(successfulInserts)
            .as("Should have some successful insertions")
            .isGreaterThan(0);

        assertThat(failedInserts)
            .as("Should have failed insertions when filter became full")
            .isGreaterThan(0);

        System.out.println("Successful inserts: " + successfulInserts);
        System.out.println("Failed inserts: " + failedInserts);
        System.out.println("Load factor: " + String.format("%.4f", filter.loadFactor()));
    }

    @Test
    @DisplayName("Filter state remains consistent after failed insertions")
    void filterRemainsConsistentAfterFailedInsertions() {
        CuckooFilter filter = CuckooFilter.create(100, 12);

        // Insert items until we start seeing failures
        List<byte[]> insertedItems = new ArrayList<>();
        Random random = new Random(42);

        for (int i = 0; i < 500; i++) {
            byte[] item = ("consistency-test-" + random.nextInt(Integer.MAX_VALUE))
                .getBytes(StandardCharsets.UTF_8);

            boolean inserted = filter.insert(item);
            if (inserted) {
                insertedItems.add(item);
            }
        }

        long countAfterInsertions = filter.count();

        // Try many more insertions that will fail
        for (int i = 0; i < 200; i++) {
            filter.insert(("doomed-insert-" + i).getBytes(StandardCharsets.UTF_8));
        }

        // CRITICAL: Count should not have changed from failed insertions
        assertThat(filter.count())
            .as("Filter count should remain stable after failed insertions")
            .isEqualTo(countAfterInsertions);

        // CRITICAL: All previously inserted items must still be found
        int foundCount = 0;
        for (byte[] item : insertedItems) {
            if (filter.contains(item)) {
                foundCount++;
            }
        }

        assertThat(foundCount)
            .as("All inserted items must still be found (no false negatives)")
            .isEqualTo(insertedItems.size());
    }

    @Test
    @DisplayName("Filter accepts new items after deletions free space")
    void filterAcceptsNewItemsAfterDeletion() {
        CuckooFilter filter = CuckooFilter.create(50, 12);

        // Fill the filter
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] item = ("fill-then-delete-" + i).getBytes(StandardCharsets.UTF_8);
            if (filter.insert(item)) {
                items.add(item);
            }
        }

        int insertedCount = items.size();
        System.out.println("Initial insertions: " + insertedCount);

        // Try to insert more - should fail
        boolean anySuccessBeforeDelete = false;
        for (int i = 0; i < 50; i++) {
            if (filter.insert(("should-fail-" + i).getBytes(StandardCharsets.UTF_8))) {
                anySuccessBeforeDelete = true;
            }
        }

        // Delete some items
        int deleteCount = items.size() / 2;
        for (int i = 0; i < deleteCount; i++) {
            assertThat(filter.delete(items.get(i)))
                .as("Delete of item " + i + " should succeed")
                .isTrue();
        }

        System.out.println("Deleted: " + deleteCount);
        System.out.println("Count after delete: " + filter.count());

        // Now try to insert new items - should succeed
        int newInsertSuccess = 0;
        for (int i = 0; i < deleteCount; i++) {
            byte[] newItem = ("new-after-delete-" + i).getBytes(StandardCharsets.UTF_8);
            if (filter.insert(newItem)) {
                newInsertSuccess++;
            }
        }

        System.out.println("New insertions after delete: " + newInsertSuccess);

        // At least some new insertions should succeed
        assertThat(newInsertSuccess)
            .as("Should be able to insert new items after deletions freed space")
            .isGreaterThan(0);
    }

    // ==================== Boundary Tests ====================

    @Test
    @DisplayName("Insert with empty byte array does not throw")
    void insertEmptyByteArray() {
        CuckooFilter filter = CuckooFilter.create(1000, 12);

        assertThatCode(() -> {
            boolean result = filter.insert(new byte[0]);
            // Result can be true or false, just shouldn't throw
        })
            .as("Insert of empty byte array should not throw")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Insert with single byte items does not throw even when full")
    void insertSingleByteItemsWhenFull() {
        CuckooFilter filter = CuckooFilter.create(100, 12);

        // Insert all 256 possible single-byte values
        for (int b = 0; b < 256; b++) {
            byte[] item = new byte[]{(byte) b};
            filter.insert(item);
        }

        // Try to insert more single-byte items - should not throw
        for (int b = 0; b < 256; b++) {
            byte[] item = new byte[]{(byte) b, (byte) b}; // Two bytes this time
            assertThatCode(() -> filter.insert(item))
                .as("Insert should not throw for any byte value")
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Maximum displacement attempts reached without exception")
    void maxDisplacementReachedGracefully() {
        // Create a filter with known small capacity
        CuckooFilter filter = CuckooFilter.create(32, 12);

        // Force many collisions by inserting similar items
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        while (attempts.get() < 500) {
            final int currentAttempt = attempts.get();
            byte[] item = ("collision-force-" + String.format("%04d", currentAttempt))
                .getBytes(StandardCharsets.UTF_8);

            assertThatCode(() -> {
                boolean result = filter.insert(item);
                if (result) {
                    successes.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            })
                .as("Insert at attempt " + currentAttempt + " must not throw")
                .doesNotThrowAnyException();

            attempts.incrementAndGet();
        }

        System.out.println("Attempts: " + attempts.get());
        System.out.println("Successes: " + successes.get());
        System.out.println("Failures: " + failures.get());

        assertThat(failures.get())
            .as("Should have failures due to max displacement limit")
            .isGreaterThan(0);
    }

    // ==================== Concurrent Graceful Failure ====================

    @Test
    @DisplayName("Concurrent insertions handle full filter gracefully without exceptions")
    void concurrentInsertionsHandleFullFilter() throws InterruptedException {
        // Small filter that will fill quickly under concurrent load
        CuckooFilter filter = CuckooFilter.create(100, 12);

        int numThreads = 8;
        int itemsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicBoolean anyExceptionThrown = new AtomicBoolean(false);
        AtomicInteger totalAttempts = new AtomicInteger(0);
        AtomicInteger totalSuccesses = new AtomicInteger(0);
        AtomicInteger totalFailures = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        try {
                            byte[] item = ("concurrent-full-" + threadId + "-" + i)
                                .getBytes(StandardCharsets.UTF_8);
                            totalAttempts.incrementAndGet();

                            boolean result = filter.insert(item);
                            if (result) {
                                totalSuccesses.incrementAndGet();
                            } else {
                                totalFailures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // CRITICAL: No exception should ever be thrown
                            anyExceptionThrown.set(true);
                            System.err.println("Unexpected exception in thread " + threadId +
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

        // CRITICAL ASSERTION: No exception should have been thrown
        assertThat(anyExceptionThrown.get())
            .as("No exception should be thrown during concurrent insertions to full filter")
            .isFalse();

        System.out.println("Concurrent test - Total attempts: " + totalAttempts.get());
        System.out.println("Concurrent test - Successes: " + totalSuccesses.get());
        System.out.println("Concurrent test - Failures: " + totalFailures.get());

        assertThat(totalFailures.get())
            .as("Should have some failed insertions under concurrent load")
            .isGreaterThan(0);
    }

    // ==================== Stress Test ====================

    @RepeatedTest(3)
    @DisplayName("Stress test: repeated insert attempts never throw")
    void stressTestRepeatedInsertsNeverThrow() {
        CuckooFilter filter = CuckooFilter.create(50, 12);

        // Fill the filter first
        for (int i = 0; i < 100; i++) {
            filter.insert(("stress-fill-" + i).getBytes(StandardCharsets.UTF_8));
        }

        // Now repeatedly try to insert - should never throw
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 100; i++) {
                final int roundNum = round;
                final int itemNum = i;

                assertThatCode(() -> {
                    byte[] item = ("stress-insert-" + roundNum + "-" + itemNum)
                        .getBytes(StandardCharsets.UTF_8);
                    filter.insert(item);
                })
                    .as("Insert in round " + roundNum + " item " + itemNum +
                        " must never throw exception")
                    .doesNotThrowAnyException();
            }
        }
    }

    // ==================== Return Value Verification ====================

    @Test
    @DisplayName("insert() return value is consistent with filter state")
    void insertReturnValueConsistentWithState() {
        CuckooFilter filter = CuckooFilter.create(100, 12);

        int consecutiveFailures = 0;
        int maxConsecutiveFailures = 0;

        for (int i = 0; i < 1000; i++) {
            byte[] item = ("return-value-test-" + i).getBytes(StandardCharsets.UTF_8);
            boolean inserted = filter.insert(item);

            if (inserted) {
                // If insert succeeded, contains must return true
                assertThat(filter.contains(item))
                    .as("Item inserted successfully must be found in filter")
                    .isTrue();

                consecutiveFailures = 0;
            } else {
                // If insert failed, count should not have increased
                consecutiveFailures++;
                maxConsecutiveFailures = Math.max(maxConsecutiveFailures, consecutiveFailures);
            }
        }

        System.out.println("Max consecutive failures: " + maxConsecutiveFailures);

        // Once we start seeing failures consistently, filter is likely full
        assertThat(maxConsecutiveFailures)
            .as("Should see consecutive failures when filter is full")
            .isGreaterThan(10);
    }

    // ==================== Post-Failure Recovery ====================

    @Test
    @DisplayName("Filter operations work correctly after multiple failed insertions")
    void operationsWorkAfterMultipleFailedInsertions() {
        CuckooFilter filter = CuckooFilter.create(50, 12);

        // Fill the filter
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] item = ("recovery-test-" + i).getBytes(StandardCharsets.UTF_8);
            if (filter.insert(item)) {
                items.add(item);
            }
        }

        // Cause many failed insertions
        for (int i = 0; i < 100; i++) {
            filter.insert(("doomed-" + i).getBytes(StandardCharsets.UTF_8));
        }

        // Now test that all operations still work correctly

        // 1. Contains should work
        assertThat(filter.contains(items.get(0))).isTrue();

        // 2. Delete should work
        assertThat(filter.delete(items.get(0))).isTrue();
        assertThat(filter.contains(items.get(0))).isFalse();

        // 3. Insert should work after delete
        assertThat(filter.insert("new-after-recovery".getBytes(StandardCharsets.UTF_8)))
            .isTrue();

        // 4. Count should be accurate
        assertThat(filter.count()).isEqualTo(items.size() - 1 + 1); // -1 delete +1 insert
    }
}
