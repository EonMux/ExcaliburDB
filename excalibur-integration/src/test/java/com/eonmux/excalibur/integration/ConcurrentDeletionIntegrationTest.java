package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.filter.CuckooFilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.eonmux.excalibur.integration.DeletionTestFilters.filter;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thread safety of Cuckoo filter deletion.
 *
 * <p>Deletion mutates buckets in place, so two threads deleting different keys can land
 * on the same bucket and a lost update there does not throw — it leaves a fingerprint
 * behind that {@code contains} will keep answering yes to, or removes one that belonged
 * to a key nobody deleted. These tests run deletes from several threads at once, and
 * deletes interleaved with inserts, and then check the surviving set exactly rather
 * than only checking that nothing blew up.</p>
 */
class ConcurrentDeletionIntegrationTest {

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
    }

    @Nested
    @DisplayName("Concurrent Deletion Safety")
    class ConcurrentDeletionSafety {

        /**
         * Test: Concurrent deletes from multiple threads are safe.
         *
         * <p>Domain correctness: No data corruption or race conditions when
         * multiple threads delete entries concurrently.</p>
         */
        @Test
        @DisplayName("concurrent deletes from multiple threads are safe")
        void concurrentDeletesFromMultipleThreadsAreSafe() throws Exception {
            CuckooFilter filter = filter(10000);

            // Insert keys
            Set<String> allKeys = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < 2000; i++) {
                String key = "concurrent-key-" + i;
                filter.insert(key.getBytes(StandardCharsets.UTF_8));
                allKeys.add(key);
            }

            // Verify all inserted
            assertThat(filter.count()).isEqualTo(2000);

            // Partition keys for concurrent deletion
            List<String> keyList = new ArrayList<>(allKeys);
            Collections.shuffle(keyList, random);

            int numThreads = 4;
            List<List<String>> partitions = new ArrayList<>();
            for (int i = 0; i < numThreads; i++) {
                partitions.add(new ArrayList<>());
            }
            for (int i = 0; i < keyList.size(); i++) {
                partitions.get(i % numThreads).add(keyList.get(i));
            }

            // Concurrent deletion
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger totalDeleted = new AtomicInteger(0);

            try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
                for (int t = 0; t < numThreads; t++) {
                    final List<String> partition = partitions.get(t);
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (String key : partition) {
                                if (filter.delete(key.getBytes(StandardCharsets.UTF_8))) {
                                    totalDeleted.incrementAndGet();
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
                boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
                assertThat(finished).as("Concurrent deletions should complete in time").isTrue();
            }

            // Verify all deletions succeeded
            assertThat(totalDeleted.get()).isEqualTo(2000);
            assertThat(filter.count()).isEqualTo(0);

            // Verify no false positives after deletion
            int falsePositives = 0;
            for (String key : allKeys) {
                if (filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    falsePositives++;
                }
            }
            assertThat(falsePositives)
                .as("No false positives after concurrent deletion")
                .isEqualTo(0);

            System.out.println("Concurrent deletion: " + totalDeleted.get() +
                " deleted by " + numThreads + " threads, " + falsePositives + " false positives");
        }

        /**
         * Test: Concurrent insert and delete operations are safe.
         *
         * <p>The two threads work on disjoint key ranges. They used to share one: the insert
         * thread added a key to its expected-set <em>after</em> inserting it, so a delete of
         * that key landing in between was recorded in the filter and then undone in the
         * expected-set — and the test reported a key "missing" that it had itself asked to be
         * deleted. That is the test's own bookkeeping racing, not the filter, and it made a
         * genuine concurrency test fail about one run in six for a reason that had nothing to
         * do with concurrency in the code under test.
         */
        @Test
        @DisplayName("concurrent insert and delete operations are safe")
        void concurrentInsertAndDeleteOperationsAreSafe() throws Exception {
            CuckooFilter filter = filter(10000);

            int numOperations = 1000;
            int preloaded = 500;

            // Deleted concurrently with the inserts below, and never inserted by that thread,
            // so each key has exactly one writer and the expectations are unambiguous.
            Set<String> toDelete = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < preloaded; i++) {
                String key = "predeleted-key-" + i;
                if (filter.insert(key.getBytes(StandardCharsets.UTF_8))) {
                    toDelete.add(key);
                }
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicInteger insertCount = new AtomicInteger(0);
            AtomicInteger deleteCount = new AtomicInteger(0);
            Set<String> insertedKeys = ConcurrentHashMap.newKeySet();
            Set<String> deletedKeys = ConcurrentHashMap.newKeySet();

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < numOperations; i++) {
                            String key = "mixed-key-" + i;
                            if (filter.insert(key.getBytes(StandardCharsets.UTF_8))) {
                                insertedKeys.add(key);
                                insertCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });

                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (String key : toDelete) {
                            if (filter.delete(key.getBytes(StandardCharsets.UTF_8))) {
                                deletedKeys.add(key);
                                deleteCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
                assertThat(finished).as("Concurrent operations should complete in time").isTrue();
            }

            System.out.println("Mixed operations: " + insertCount.get() + " inserts, "
                + deleteCount.get() + " deletes, filter count=" + filter.count());

            assertThat(deleteCount.get())
                .as("every preloaded key must be deletable while inserts run alongside")
                .isEqualTo(toDelete.size());

            int missing = 0;
            for (String key : insertedKeys) {
                if (!filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    missing++;
                }
            }
            assertThat(missing)
                .as("a key the filter accepted must still be found; losing one is a false "
                    + "negative, which is the one answer a filter may never give")
                .isEqualTo(0);

            // Bounded, not zero. A thousand other keys were inserted alongside, and any of
            // their fingerprints may land in a bucket a deleted key also hashes to -- that is
            // an ordinary false positive, which the filter is allowed and expected to give. The
            // guarantee runs the other way: never a false negative.
            int stillMatching = 0;
            for (String key : deletedKeys) {
                if (filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    stillMatching++;
                }
            }
            assertThat(stillMatching)
                .as("a deleted key may still collide with a live one, but only rarely")
                .isLessThan(Math.max(1, deletedKeys.size() / 50));
        }
    }
}
