package io.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for striped locking behavior across multiple Segment instances.
 *
 * <p>The striped locking pattern means each Segment has its own independent
 * ReentrantReadWriteLock. This allows:
 * <ul>
 *   <li>Concurrent operations on different segments without contention</li>
 *   <li>Concurrent reads on the same segment</li>
 *   <li>Exclusive writes to a segment (blocking only that segment)</li>
 * </ul>
 *
 * <p>These tests verify that:
 * <ul>
 *   <li>Multiple segments can be accessed concurrently without blocking</li>
 *   <li>Each segment's lock is independent</li>
 *   <li>The lock accessor methods work correctly</li>
 *   <li>The *WithoutLock methods work when the lock is held externally</li>
 * </ul>
 */
@DisplayName("Striped Lock Tests")
class StripedLockTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private List<Segment> segments;
    private List<Path> segmentFiles;

    @BeforeEach
    void setUp() throws IOException {
        // Use shared arena for multi-threaded access tests
        arena = Arena.ofShared();
        segments = new ArrayList<>();
        segmentFiles = new ArrayList<>();

        // Create multiple segments to test striped locking
        for (int i = 0; i < 4; i++) {
            Path file = tempDir.resolve("segment-" + i + ".bin");
            segmentFiles.add(file);
            Segment segment = new Segment(file, 2 * 1024 * 1024, arena);
            segments.add(segment);

            // Pre-populate each segment with some data
            for (int j = 0; j < 50; j++) {
                segment.put(("seg" + i + "-key" + j).getBytes(),
                           ("seg" + i + "-value" + j).getBytes());
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (Segment segment : segments) {
            if (segment != null && !segment.isClosed()) {
                segment.close();
            }
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== Lock Accessor Tests ====================

    @Nested
    @DisplayName("Lock Accessor Methods")
    class LockAccessorTests {

        @Test
        @DisplayName("readLock() should return a non-null Lock")
        void testReadLockNotNull() {
            Segment segment = segments.get(0);
            Lock readLock = segment.readLock();
            assertNotNull(readLock, "readLock() should return a non-null Lock");
        }

        @Test
        @DisplayName("writeLock() should return a non-null Lock")
        void testWriteLockNotNull() {
            Segment segment = segments.get(0);
            Lock writeLock = segment.writeLock();
            assertNotNull(writeLock, "writeLock() should return a non-null Lock");
        }

        @Test
        @DisplayName("readLock() and writeLock() should be different objects")
        void testReadAndWriteLocksAreDifferent() {
            Segment segment = segments.get(0);
            Lock readLock = segment.readLock();
            Lock writeLock = segment.writeLock();
            assertNotSame(readLock, writeLock,
                "readLock() and writeLock() should return different lock objects");
        }

        @Test
        @DisplayName("Each segment should have independent locks")
        void testIndependentLocksPerSegment() {
            Lock readLock0 = segments.get(0).readLock();
            Lock readLock1 = segments.get(1).readLock();
            Lock writeLock0 = segments.get(0).writeLock();
            Lock writeLock1 = segments.get(1).writeLock();

            // All should be different objects
            assertNotSame(readLock0, readLock1,
                "Different segments should have different read locks");
            assertNotSame(writeLock0, writeLock1,
                "Different segments should have different write locks");
            assertNotSame(readLock0, writeLock0,
                "Read and write locks of same segment should be different");
        }

        @Test
        @DisplayName("Locks should be usable for manual lock/unlock")
        void testManualLockUnlock() {
            Segment segment = segments.get(0);
            Lock readLock = segment.readLock();

            readLock.lock();
            try {
                // Should be able to read while holding the lock
                MemorySegment value = segment.getWithoutLock("seg0-key0".getBytes());
                assertNotNull(value);
            } finally {
                readLock.unlock();
            }
        }
    }

    // ==================== Striped Concurrency Tests ====================

    @Nested
    @DisplayName("Striped Concurrency")
    class StripedConcurrencyTests {

        @Test
        @DisplayName("Operations on different segments should not block each other")
        void testNoCrossSegmentBlocking() throws Exception {
            int numSegments = segments.size();
            int operationsPerSegment = 100;

            ExecutorService executor = Executors.newFixedThreadPool(numSegments);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch segmentReadyLatch = new CountDownLatch(numSegments);
            AtomicInteger completedSegments = new AtomicInteger(0);
            List<Future<Long>> futures = new ArrayList<>();

            // Each thread will perform operations on a different segment
            for (int i = 0; i < numSegments; i++) {
                final int segmentIndex = i;
                futures.add(executor.submit(() -> {
                    Segment segment = segments.get(segmentIndex);
                    segmentReadyLatch.countDown();
                    startLatch.await();

                    long startTime = System.nanoTime();
                    for (int op = 0; op < operationsPerSegment; op++) {
                        // Mix of reads
                        segment.get(("seg" + segmentIndex + "-key" + (op % 50)).getBytes());
                    }
                    completedSegments.incrementAndGet();
                    return System.nanoTime() - startTime;
                }));
            }

            // Wait for all threads to be ready
            segmentReadyLatch.await();
            long testStart = System.nanoTime();
            startLatch.countDown();

            // Wait for completion
            for (Future<Long> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            long testEnd = System.nanoTime();

            executor.shutdown();

            // All segments should have completed
            assertEquals(numSegments, completedSegments.get(),
                "All segments should complete their operations");

            // The total test time should be roughly the time for one segment
            // (not numSegments times longer) because operations run in parallel
            // This is a weak assertion - we're just checking it doesn't take too long
            long testDurationMs = TimeUnit.NANOSECONDS.toMillis(testEnd - testStart);
            assertTrue(testDurationMs < 2000,
                "Concurrent operations across segments should complete quickly, took " + testDurationMs + "ms");
        }

        @Test
        @DisplayName("Write to one segment should not block reads on another")
        void testWriteDoesNotBlockOtherSegmentReads() throws Exception {
            Segment segment0 = segments.get(0);
            Segment segment1 = segments.get(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch writeStarted = new CountDownLatch(1);
            CountDownLatch readCompleted = new CountDownLatch(1);
            AtomicInteger readErrors = new AtomicInteger(0);

            // Thread 1: Hold write lock on segment0 for a while
            Future<?> writeFuture = executor.submit(() -> {
                Lock writeLock = segment0.writeLock();
                writeLock.lock();
                try {
                    writeStarted.countDown();
                    // Hold the lock for a bit
                    Thread.sleep(100);
                    segment0.putWithoutLock("new-key".getBytes(), "new-value".getBytes());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    writeLock.unlock();
                }
            });

            // Thread 2: Read from segment1 (should not be blocked by segment0's write lock)
            Future<?> readFuture = executor.submit(() -> {
                try {
                    writeStarted.await();
                    long start = System.nanoTime();
                    MemorySegment value = segment1.get("seg1-key0".getBytes());
                    long duration = System.nanoTime() - start;

                    assertNotNull(value, "Read from segment1 should succeed");
                    // Read should complete quickly (not blocked by segment0's write)
                    assertTrue(duration < 50_000_000, // 50ms
                        "Read on segment1 should not be blocked by write on segment0, took " +
                        TimeUnit.NANOSECONDS.toMillis(duration) + "ms");
                    readCompleted.countDown();
                } catch (Exception e) {
                    readErrors.incrementAndGet();
                }
            });

            writeFuture.get(2, TimeUnit.SECONDS);
            readFuture.get(2, TimeUnit.SECONDS);

            executor.shutdown();

            assertEquals(0, readErrors.get(), "No read errors should occur");
            assertEquals(0, readCompleted.getCount(), "Read should have completed");
        }

        @Test
        @DisplayName("Multiple segments should support concurrent writes")
        void testConcurrentWritesOnDifferentSegments() throws Exception {
            int numSegments = segments.size();
            int writesPerSegment = 20;

            ExecutorService executor = Executors.newFixedThreadPool(numSegments);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < numSegments; i++) {
                final int segmentIndex = i;
                futures.add(executor.submit(() -> {
                    try {
                        Segment segment = segments.get(segmentIndex);
                        startLatch.await();

                        for (int w = 0; w < writesPerSegment; w++) {
                            segment.put(
                                ("concurrent-" + segmentIndex + "-key" + w).getBytes(),
                                ("concurrent-" + segmentIndex + "-value" + w).getBytes()
                            );
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            executor.shutdown();

            assertEquals(0, errorCount.get(), "No errors should occur during concurrent writes");
            assertEquals(numSegments * writesPerSegment, successCount.get(),
                "All writes should succeed");

            // Verify data integrity
            for (int i = 0; i < numSegments; i++) {
                Segment segment = segments.get(i);
                for (int w = 0; w < writesPerSegment; w++) {
                    MemorySegment value = segment.get(
                        ("concurrent-" + i + "-key" + w).getBytes());
                    assertNotNull(value, "Data should be retrievable after concurrent writes");
                }
            }
        }
    }

    // ==================== *WithoutLock Method Tests ====================

    @Nested
    @DisplayName("*WithoutLock Methods")
    class WithoutLockMethodsTests {

        @Test
        @DisplayName("getWithoutLock should work when read lock is held")
        void testGetWithoutLockWithReadLock() {
            Segment segment = segments.get(0);
            Lock readLock = segment.readLock();

            readLock.lock();
            try {
                MemorySegment value = segment.getWithoutLock("seg0-key0".getBytes());
                assertNotNull(value, "getWithoutLock should return value when read lock is held");

                byte[] bytes = value.toArray(ValueLayout.JAVA_BYTE);
                assertArrayEquals("seg0-value0".getBytes(), bytes,
                    "Value should match expected");
            } finally {
                readLock.unlock();
            }
        }

        @Test
        @DisplayName("putWithoutLock should work when write lock is held")
        void testPutWithoutLockWithWriteLock() {
            Segment segment = segments.get(0);
            Lock writeLock = segment.writeLock();

            writeLock.lock();
            try {
                segment.putWithoutLock("without-lock-key".getBytes(), "without-lock-value".getBytes());

                // Verify it was inserted
                MemorySegment value = segment.getWithoutLock("without-lock-key".getBytes());
                assertNotNull(value, "Value should be inserted");
                assertArrayEquals("without-lock-value".getBytes(),
                    value.toArray(ValueLayout.JAVA_BYTE));
            } finally {
                writeLock.unlock();
            }
        }

        @Test
        @DisplayName("deleteWithoutLock should work when write lock is held")
        void testDeleteWithoutLockWithWriteLock() {
            Segment segment = segments.get(0);

            // First insert something
            segment.put("delete-test-key".getBytes(), "delete-test-value".getBytes());
            assertNotNull(segment.get("delete-test-key".getBytes()));

            // Now delete using deleteWithoutLock with write lock held
            Lock writeLock = segment.writeLock();
            writeLock.lock();
            try {
                boolean deleted = segment.deleteWithoutLock("delete-test-key".getBytes());
                assertTrue(deleted, "deleteWithoutLock should return true");

                // Verify it was deleted
                MemorySegment value = segment.getWithoutLock("delete-test-key".getBytes());
                assertNull(value, "Value should be null after deletion");
            } finally {
                writeLock.unlock();
            }
        }

        @Test
        @DisplayName("Multiple *WithoutLock operations should work in single lock scope")
        void testMultipleOperationsWithoutLock() {
            Segment segment = segments.get(0);
            Lock writeLock = segment.writeLock();

            writeLock.lock();
            try {
                // Multiple writes
                for (int i = 0; i < 10; i++) {
                    segment.putWithoutLock(
                        ("bulk-key-" + i).getBytes(),
                        ("bulk-value-" + i).getBytes());
                }

                // Then multiple reads (switching to read lock would be better in real code,
                // but for testing we use write lock to hold both)
                for (int i = 0; i < 10; i++) {
                    MemorySegment value = segment.getWithoutLock(("bulk-key-" + i).getBytes());
                    assertNotNull(value, "Value should be found for bulk-key-" + i);
                }

                // Then delete half
                for (int i = 0; i < 5; i++) {
                    boolean deleted = segment.deleteWithoutLock(("bulk-key-" + i).getBytes());
                    assertTrue(deleted, "Delete should succeed for bulk-key-" + i);
                }
            } finally {
                writeLock.unlock();
            }

            // Verify final state
            for (int i = 0; i < 5; i++) {
                assertNull(segment.get(("bulk-key-" + i).getBytes()),
                    "Deleted key should not be found: bulk-key-" + i);
            }
            for (int i = 5; i < 10; i++) {
                assertNotNull(segment.get(("bulk-key-" + i).getBytes()),
                    "Non-deleted key should be found: bulk-key-" + i);
            }
        }

        @Test
        @DisplayName("getWithoutLock should return null for non-existent key")
        void testGetWithoutLockNonExistent() {
            Segment segment = segments.get(0);
            Lock readLock = segment.readLock();

            readLock.lock();
            try {
                MemorySegment value = segment.getWithoutLock("nonexistent-key".getBytes());
                assertNull(value, "getWithoutLock should return null for non-existent key");
            } finally {
                readLock.unlock();
            }
        }
    }

    // ==================== Lock Consistency Tests ====================

    @Nested
    @DisplayName("Lock Consistency")
    class LockConsistencyTests {

        @Test
        @DisplayName("Read lock should allow concurrent reads on same segment")
        void testConcurrentReadsWithReadLock() throws Exception {
            Segment segment = segments.get(0);
            int numReaders = 4;
            int readsPerThread = 100;

            ExecutorService executor = Executors.newFixedThreadPool(numReaders);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < numReaders; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        Lock readLock = segment.readLock();
                        readLock.lock();
                        try {
                            startLatch.await();
                            for (int r = 0; r < readsPerThread; r++) {
                                MemorySegment value = segment.getWithoutLock(
                                    ("seg0-key" + (r % 50)).getBytes());
                                if (value != null) {
                                    successCount.incrementAndGet();
                                }
                            }
                        } finally {
                            readLock.unlock();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            executor.shutdown();

            assertEquals(0, errorCount.get(), "No errors should occur");
            assertEquals(numReaders * readsPerThread, successCount.get(),
                "All reads should succeed");
        }

        @Test
        @DisplayName("Write lock should block other write lock attempts")
        void testWriteLockMutualExclusion() throws Exception {
            Segment segment = segments.get(0);

            CountDownLatch writeStarted = new CountDownLatch(1);
            CountDownLatch writeBlocked = new CountDownLatch(1);
            AtomicInteger concurrentWrites = new AtomicInteger(0);
            AtomicInteger maxConcurrentWrites = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Thread 1: Acquire write lock and hold it
            Future<?> f1 = executor.submit(() -> {
                Lock writeLock = segment.writeLock();
                writeLock.lock();
                try {
                    int current = concurrentWrites.incrementAndGet();
                    maxConcurrentWrites.updateAndGet(max -> Math.max(max, current));
                    writeStarted.countDown();
                    Thread.sleep(100); // Hold lock
                    concurrentWrites.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    writeLock.unlock();
                }
            });

            // Thread 2: Try to acquire write lock (should block)
            Future<?> f2 = executor.submit(() -> {
                try {
                    writeStarted.await();
                    writeBlocked.countDown(); // Signal we're about to try

                    Lock writeLock = segment.writeLock();
                    writeLock.lock();
                    try {
                        int current = concurrentWrites.incrementAndGet();
                        maxConcurrentWrites.updateAndGet(max -> Math.max(max, current));
                        Thread.sleep(50);
                        concurrentWrites.decrementAndGet();
                    } finally {
                        writeLock.unlock();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            });

            f1.get(2, TimeUnit.SECONDS);
            f2.get(2, TimeUnit.SECONDS);
            executor.shutdown();

            // Max concurrent writes should be 1 (mutual exclusion)
            assertEquals(1, maxConcurrentWrites.get(),
                "Only one write should be active at a time");
        }
    }
}
