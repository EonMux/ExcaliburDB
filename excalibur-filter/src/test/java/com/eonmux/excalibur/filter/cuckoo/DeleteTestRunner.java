package com.eonmux.excalibur.filter.cuckoo;

import com.eonmux.excalibur.filter.CuckooFilter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple test runner for Cuckoo Filter deletion correctness.
 * Tests the core invariants without requiring JUnit.
 */
public class DeleteTestRunner {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cuckoo Filter Deletion Correctness Tests ===\n");

        // Basic delete operations
        testDeleteReturnsTrueForInsertedItem();
        testDeleteReturnsFalseForNonExistentItem();
        testDeleteReturnsFalseForAlreadyDeletedItem();
        testDeleteIsIdempotent();

        // Critical deletion correctness tests
        testDeleteOnlyRemovesExactFingerprint();
        testSequentialDeletionsMaintainCorrectness();
        testDeleteWithCollisionStressTest();

        // Edge cases
        testDeleteHandlesReinsertion();
        testDeleteSingleByteItems();

        // Thread safety
        testConcurrentDeletionsAreThreadSafe();

        // Statistics
        testCountDecreasesAfterDeletion();

        System.out.println("\n=== Test Summary ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Total:  " + (passed + failed));

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testDeleteReturnsTrueForInsertedItem() {
        String name = "delete() returns true for inserted item";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            byte[] item = "test-item".getBytes(StandardCharsets.UTF_8);

            assertTrue(filter.insert(item), "insert should succeed");
            assertTrue(filter.contains(item), "contains after insert");
            assertTrue(filter.delete(item), "delete should return true");
            assertFalse(filter.contains(item), "contains after delete");

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteReturnsFalseForNonExistentItem() {
        String name = "delete() returns false for non-existent item";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            byte[] item = "never-inserted".getBytes(StandardCharsets.UTF_8);

            assertFalse(filter.delete(item), "delete should return false for non-existent");

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteReturnsFalseForAlreadyDeletedItem() {
        String name = "delete() returns false for already deleted item";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            byte[] item = "delete-twice".getBytes(StandardCharsets.UTF_8);

            assertTrue(filter.insert(item), "insert should succeed");
            assertTrue(filter.delete(item), "first delete should succeed");
            assertFalse(filter.delete(item), "second delete should fail");

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteIsIdempotent() {
        String name = "delete() is idempotent";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            byte[] item = "idempotent-test".getBytes(StandardCharsets.UTF_8);

            filter.insert(item);
            assertTrue(filter.delete(item), "first delete");

            for (int i = 0; i < 10; i++) {
                assertFalse(filter.delete(item), "subsequent delete " + (i + 2));
            }

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteOnlyRemovesExactFingerprint() {
        String name = "CRITICAL: delete(x) removes only x's fingerprint";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);

            byte[] itemA = "item-A-unique-content".getBytes(StandardCharsets.UTF_8);
            byte[] itemB = "item-B-different-content".getBytes(StandardCharsets.UTF_8);
            byte[] itemC = "item-C-another-content".getBytes(StandardCharsets.UTF_8);

            assertTrue(filter.insert(itemA), "insert A");
            assertTrue(filter.insert(itemB), "insert B");
            assertTrue(filter.insert(itemC), "insert C");

            assertTrue(filter.contains(itemA), "contains A before delete");
            assertTrue(filter.contains(itemB), "contains B before delete");
            assertTrue(filter.contains(itemC), "contains C before delete");

            assertTrue(filter.delete(itemB), "delete B");

            // CRITICAL ASSERTIONS
            assertTrue(filter.contains(itemA), "CRITICAL: A should still be present after deleting B");
            assertFalse(filter.contains(itemB), "B should be absent after deletion");
            assertTrue(filter.contains(itemC), "CRITICAL: C should still be present after deleting B");

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testSequentialDeletionsMaintainCorrectness() {
        String name = "Sequential deletions maintain correctness";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            int numItems = 100;
            List<byte[]> items = new ArrayList<>();

            for (int i = 0; i < numItems; i++) {
                byte[] item = ("sequential-item-" + i).getBytes(StandardCharsets.UTF_8);
                items.add(item);
                assertTrue(filter.insert(item), "insert " + i);
            }

            // Delete every other item
            for (int i = 0; i < numItems; i += 2) {
                assertTrue(filter.delete(items.get(i)), "delete " + i);
            }

            // Verify state
            for (int i = 0; i < numItems; i++) {
                boolean expectedPresent = (i % 2 == 1);
                assertEquals(expectedPresent, filter.contains(items.get(i)),
                    "item " + i + " should be " + (expectedPresent ? "present" : "absent"));
            }

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteWithCollisionStressTest() {
        String name = "Delete with collision stress test";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            int numItems = 500;
            List<byte[]> items = new ArrayList<>();

            for (int i = 0; i < numItems; i++) {
                byte[] item = ("collision-stress-" + String.format("%04d", i)).getBytes(StandardCharsets.UTF_8);
                items.add(item);
                filter.insert(item);
            }

            Random random = new Random(42);
            List<Integer> toDelete = new ArrayList<>();
            for (int i = 0; i < numItems; i++) toDelete.add(i);
            Collections.shuffle(toDelete, random);

            for (int i = 0; i < numItems / 2; i++) {
                int idx = toDelete.get(i);
                assertTrue(filter.delete(items.get(idx)), "delete " + idx);
            }

            // Verify
            for (int i = 0; i < numItems; i++) {
                boolean wasDeleted = false;
                for (int j = 0; j < numItems / 2; j++) {
                    if (toDelete.get(j) == i) {
                        wasDeleted = true;
                        break;
                    }
                }
                assertEquals(!wasDeleted, filter.contains(items.get(i)),
                    "item " + i + " should be " + (wasDeleted ? "absent" : "present"));
            }

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteHandlesReinsertion() {
        String name = "delete() handles re-insertion after deletion";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            byte[] item = "reinsert-test".getBytes(StandardCharsets.UTF_8);

            for (int cycle = 0; cycle < 5; cycle++) {
                assertTrue(filter.insert(item), "insert cycle " + cycle);
                assertTrue(filter.contains(item), "contains cycle " + cycle);
                assertTrue(filter.delete(item), "delete cycle " + cycle);
                assertFalse(filter.contains(item), "not contains cycle " + cycle);
            }

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testDeleteSingleByteItems() {
        String name = "delete() with single byte items";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);

            for (int b = 0; b < 256; b++) {
                byte[] item = new byte[]{(byte) b};
                filter.insert(item);
            }

            for (int b = 0; b < 256; b += 2) {
                byte[] item = new byte[]{(byte) b};
                assertTrue(filter.delete(item), "delete byte " + b);
            }

            for (int b = 0; b < 256; b++) {
                byte[] item = new byte[]{(byte) b};
                boolean expectedPresent = (b % 2 == 1);
                assertEquals(expectedPresent, filter.contains(item),
                    "byte " + b + " should be " + (expectedPresent ? "present" : "absent"));
            }

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testConcurrentDeletionsAreThreadSafe() throws Exception {
        String name = "Concurrent deletions are thread-safe";
        try {
            CuckooFilter filter = CuckooFilter.create(2000, 12);
            int numItems = 1000;
            int numThreads = 8;
            List<byte[]> items = new ArrayList<>();

            for (int i = 0; i < numItems; i++) {
                byte[] item = ("concurrent-delete-" + i).getBytes(StandardCharsets.UTF_8);
                items.add(item);
                filter.insert(item);
            }

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successfulDeletes = new AtomicInteger(0);

            int itemsPerThread = numItems / numThreads;

            for (int t = 0; t < numThreads; t++) {
                final int threadStart = t * itemsPerThread;
                final int threadEnd = (t == numThreads - 1) ? numItems : threadStart + itemsPerThread;

                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = threadStart; i < threadEnd; i++) {
                            if (filter.delete(items.get(i))) {
                                successfulDeletes.incrementAndGet();
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

            assertEquals(numItems, successfulDeletes.get(), "all items should be deleted");

            for (byte[] item : items) {
                assertFalse(filter.contains(item), "item should be absent after concurrent delete");
            }

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    private static void testCountDecreasesAfterDeletion() {
        String name = "count decreases after deletion";
        try {
            CuckooFilter filter = CuckooFilter.create(1000, 12);
            int numItems = 50;

            for (int i = 0; i < numItems; i++) {
                filter.insert(("count-test-" + i).getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(numItems, filter.count(), "count after insert");

            for (int i = 0; i < numItems; i++) {
                filter.delete(("count-test-" + i).getBytes(StandardCharsets.UTF_8));
                assertEquals(numItems - i - 1, filter.count(), "count after deleting " + (i + 1));
            }

            assertEquals(0, filter.count(), "count should be zero");

            pass(name);
        } catch (Throwable e) {
            fail(name, e);
        }
    }

    // ==================== Assertion Helpers ====================

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Expected true: " + message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError("Expected false: " + message);
        }
    }

    private static void assertEquals(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " - expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " - expected: " + expected + ", actual: " + actual);
        }
    }

    private static void pass(String name) {
        System.out.println("✓ PASS: " + name);
        passed++;
    }

    private static void fail(String name, Throwable e) {
        System.out.println("✗ FAIL: " + name);
        System.out.println("  Error: " + e.getMessage());
        e.printStackTrace(System.out);
        failed++;
    }
}
