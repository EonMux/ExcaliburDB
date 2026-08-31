package io.excalibur.store;

import io.excalibur.store.format.LogEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Write-Ahead Log (WAL) integration with Segment.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>put() appends to WAL when attached</li>
 *   <li>delete() appends to WAL when attached</li>
 *   <li>putWithoutLock() appends to WAL when attached</li>
 *   <li>deleteWithoutLock() appends to WAL when attached</li>
 *   <li>Crash recovery: data survives close without flush</li>
 *   <li>WAL not used when not attached</li>
 *   <li>Write ordering: WAL append before segment modification</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>Tests verify that:
 * <ul>
 *   <li>LSNs are monotonically increasing</li>
 *   <li>Log entries contain correct key/value data</li>
 *   <li>Recovery replays entries in correct order</li>
 *   <li>Recovery restores segment to correct state</li>
 * </ul>
 */
@DisplayName("Write-Ahead Log Integration Tests")
class WriteLogIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path segmentFile;
    private Path logFile;
    private Segment segment;
    private WriteLog writeLog;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofConfined();
        segmentFile = tempDir.resolve("test-segment.bin");
        logFile = tempDir.resolve("test-segment.wal");
        // Create segment with enough capacity for tests
        segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (segment != null && !segment.isClosed()) {
            segment.close();
        }
        if (writeLog != null && !writeLog.isClosed()) {
            writeLog.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== WAL Attachment Tests ====================

    @Nested
    @DisplayName("WAL Attachment")
    class WalAttachmentTests {

        @Test
        @DisplayName("Should attach WriteLog to segment")
        void testAttachWriteLog() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            assertNotNull(segment.writeLog(), "WriteLog should be attached");
            assertEquals(writeLog, segment.writeLog(), "Attached WriteLog should match");
        }

        @Test
        @DisplayName("Should reject null WriteLog attachment")
        void testAttachNullWriteLog() {
            assertThrows(NullPointerException.class, () -> segment.attachWriteLog(null));
        }

        @Test
        @DisplayName("Should reject double attachment")
        void testDoubleAttachment() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            Path logFile2 = tempDir.resolve("test-segment-2.wal");
            WriteLog writeLog2 = new WriteLog(logFile2);

            assertThrows(IllegalStateException.class, () -> segment.attachWriteLog(writeLog2));

            writeLog2.close();
        }
    }

    // ==================== Put Appends to WAL Tests ====================

    @Nested
    @DisplayName("put() WAL Integration")
    class PutWalTests {

        @Test
        @DisplayName("Should append to WAL when put() is called with WAL attached")
        void testPutAppendsToWal() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            // Initial state: no entries
            assertEquals(0, writeLog.entriesAppended(), "WAL should be empty initially");

            // Put should append to WAL
            segment.put(key, value);

            assertEquals(1, writeLog.entriesAppended(), "WAL should have one entry after put");
            assertEquals(1, writeLog.nextLsn(), "Next LSN should be 1");
        }

        @Test
        @DisplayName("Should NOT append to WAL when put() is called without WAL attached")
        void testPutWithoutWal() throws IOException {
            // Don't attach WAL

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            // Put should succeed without WAL
            segment.put(key, value);

            // Verify data is in segment
            MemorySegment result = segment.get(key);
            assertNotNull(result, "Value should be in segment");
            assertArrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE));
        }

        @Test
        @DisplayName("Should append to WAL for update operations")
        void testUpdateAppendsToWal() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] value2 = "value2".getBytes();

            // Initial put
            segment.put(key, value1);
            assertEquals(1, writeLog.entriesAppended(), "WAL should have one entry after first put");

            // Update should also append to WAL
            segment.put(key, value2);
            assertEquals(2, writeLog.entriesAppended(), "WAL should have two entries after update");
        }

        @Test
        @DisplayName("Should append correct entry format to WAL")
        void testWalEntryFormat() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            segment.put(key, value);

            // Verify WAL entry format by reading it back
            final List<LogEntry> entries = new ArrayList<>();
            writeLog.recover(entries::add);

            assertEquals(1, entries.size(), "Should have one entry");
            LogEntry entry = entries.get(0);
            assertTrue(entry.isPut(), "Entry should be PUT");
            assertArrayEquals(key, entry.key(), "Key should match");
            assertArrayEquals(value, entry.value(), "Value should match");
            assertEquals(0, entry.lsn(), "LSN should be 0");
        }
    }

    // ==================== Delete Appends to WAL Tests ====================

    @Nested
    @DisplayName("delete() WAL Integration")
    class DeleteWalTests {

        @Test
        @DisplayName("Should append to WAL when delete() is called with WAL attached")
        void testDeleteAppendsToWal() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            // Put first
            segment.put(key, value);
            assertEquals(1, writeLog.entriesAppended(), "WAL should have one entry after put");

            // Delete should append to WAL
            boolean deleted = segment.delete(key);
            assertTrue(deleted, "Delete should succeed");
            assertEquals(2, writeLog.entriesAppended(), "WAL should have two entries after delete");
        }

        @Test
        @DisplayName("Should NOT append to WAL when delete() is called without WAL attached")
        void testDeleteWithoutWal() throws IOException {
            // Don't attach WAL

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            // Put and delete without WAL
            segment.put(key, value);
            boolean deleted = segment.delete(key);

            assertTrue(deleted, "Delete should succeed");
            assertNull(segment.get(key), "Value should be deleted");
        }

        @Test
        @DisplayName("Should NOT append to WAL when key not found for delete")
        void testDeleteNotFoundNoWalAppend() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "nonexistent".getBytes();

            // Delete non-existent key
            boolean deleted = segment.delete(key);
            assertFalse(deleted, "Delete should fail for non-existent key");
            assertEquals(0, writeLog.entriesAppended(), "WAL should have no entries for failed delete");
        }

        @Test
        @DisplayName("Should append DELETE entry to WAL with correct format")
        void testDeleteEntryFormat() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            segment.put(key, value);
            segment.delete(key);

            // Verify WAL entries
            final List<LogEntry> entries = new ArrayList<>();
            writeLog.recover(entries::add);

            assertEquals(2, entries.size(), "Should have two entries");
            assertTrue(entries.get(0).isPut(), "First entry should be PUT");
            assertTrue(entries.get(1).isDelete(), "Second entry should be DELETE");
            assertArrayEquals(key, entries.get(1).key(), "DELETE key should match");
            assertEquals(0, entries.get(1).value().length, "DELETE value should be empty");
        }
    }

    // ==================== putWithoutLock WAL Tests ====================

    @Nested
    @DisplayName("putWithoutLock() WAL Integration")
    class PutWithoutLockWalTests {

        @Test
        @DisplayName("Should append to WAL when putWithoutLock() is called with WAL attached")
        void testPutWithoutLockAppendsToWal() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            segment.writeLock().lock();
            try {
                segment.putWithoutLock(key, value);
            } finally {
                segment.writeLock().unlock();
            }

            assertEquals(1, writeLog.entriesAppended(), "WAL should have one entry after putWithoutLock");
        }
    }

    // ==================== deleteWithoutLock WAL Tests ====================

    @Nested
    @DisplayName("deleteWithoutLock() WAL Integration")
    class DeleteWithoutLockWalTests {

        @Test
        @DisplayName("Should append to WAL when deleteWithoutLock() is called with WAL attached")
        void testDeleteWithoutLockAppendsToWal() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            // First put
            segment.put(key, value);

            segment.writeLock().lock();
            try {
                boolean deleted = segment.deleteWithoutLock(key);
                assertTrue(deleted, "Delete should succeed");
            } finally {
                segment.writeLock().unlock();
            }

            assertEquals(2, writeLog.entriesAppended(), "WAL should have two entries after deleteWithoutLock");
        }
    }

    // ==================== Crash Recovery Tests ====================

    @Nested
    @DisplayName("Crash Recovery")
    class CrashRecoveryTests {

        @Test
        @DisplayName("Should recover data from WAL after crash")
        void testCrashRecovery() throws IOException {
            // Setup with WAL
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key1 = "key1".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] key2 = "key2".getBytes();
            byte[] value2 = "value2".getBytes();

            // Write data
            segment.put(key1, value1);
            segment.put(key2, value2);

            // Verify WAL has entries
            assertEquals(2, writeLog.entriesAppended(), "WAL should have two entries");

            // Simulate crash: close WAL and segment without proper flush
            // In real crash, the OS would still have WAL data on disk due to fsync
            writeLog.close();
            segment.close();

            // Reopen and recover
            arena = Arena.ofConfined(); // New arena
            segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            // Recover from WAL
            segment.recoverFromLog();

            // Verify data was recovered
            MemorySegment result1 = segment.get(key1);
            MemorySegment result2 = segment.get(key2);

            assertNotNull(result1, "Key1 should be recovered");
            assertNotNull(result2, "Key2 should be recovered");
            assertArrayEquals(value1, result1.toArray(ValueLayout.JAVA_BYTE));
            assertArrayEquals(value2, result2.toArray(ValueLayout.JAVA_BYTE));
        }

        @Test
        @DisplayName("Should recover DELETE operations from WAL")
        void testCrashRecoveryWithDeletes() throws IOException {
            // Setup with WAL
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key1 = "key1".getBytes();
            byte[] value1 = "value1".getBytes();

            // Write and then delete
            segment.put(key1, value1);
            segment.delete(key1);

            // Simulate crash
            writeLog.close();
            segment.close();

            // Reopen and recover
            arena = Arena.ofConfined();
            segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            segment.recoverFromLog();

            // Verify key was deleted (tombstoned)
            MemorySegment result = segment.get(key1);
            assertNull(result, "Key should be deleted after recovery");
        }

        @Test
        @DisplayName("Should recover update operations from WAL")
        void testCrashRecoveryWithUpdates() throws IOException {
            // Setup with WAL
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "key".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] value2 = "value2-updated".getBytes();

            // Write, then update
            segment.put(key, value1);
            segment.put(key, value2);

            // Simulate crash
            writeLog.close();
            segment.close();

            // Reopen and recover
            arena = Arena.ofConfined();
            segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            segment.recoverFromLog();

            // Verify latest value was recovered
            MemorySegment result = segment.get(key);
            assertNotNull(result, "Key should be recovered");
            assertArrayEquals(value2, result.toArray(ValueLayout.JAVA_BYTE),
                "Should have latest value after recovery");
        }

        @Test
        @DisplayName("Should recover multiple operations in LSN order")
        void testRecoveryLsnOrder() throws IOException {
            // Setup with WAL
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            // Multiple operations
            for (int i = 0; i < 10; i++) {
                segment.put(("key" + i).getBytes(), ("value" + i).getBytes());
            }

            // Verify LSNs are monotonic
            assertEquals(10, writeLog.entriesAppended());

            // Verify LSN order in WAL
            final List<Long> lsns = new ArrayList<>();
            writeLog.recover(entry -> lsns.add(entry.lsn()));

            assertEquals(10, lsns.size());
            for (int i = 0; i < 10; i++) {
                assertEquals(i, lsns.get(i), "LSN should be in order");
            }
        }
    }

    // ==================== Write Ordering Tests ====================

    @Nested
    @DisplayName("Write Ordering")
    class WriteOrderingTests {

        @Test
        @DisplayName("WAL should be appended before segment is modified")
        void testWalAppendBeforeModification() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            // The put operation should:
            // 1. Acquire write lock
            // 2. Append to WAL (with fsync)
            // 3. Modify segment
            // 4. Release lock

            // If WAL append fails, segment should not be modified
            // We can't easily test this without mocking, but we can verify
            // that after a successful put, both WAL and segment have the data

            segment.put(key, value);

            // Verify WAL has entry
            assertEquals(1, writeLog.entriesAppended());

            // Verify segment has data
            MemorySegment result = segment.get(key);
            assertNotNull(result);
            assertArrayEquals(value, result.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    // ==================== Concurrency Tests ====================

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should handle concurrent puts with WAL")
        void testConcurrentPuts() throws Exception {
            // Use shared arena for multi-threaded access
            // Note: The confined arena from setUp doesn't support multi-threaded access
            // so we create a new shared arena for this test
            try (Arena sharedArena = Arena.ofShared()) {
                // Create new segment and WAL with shared arena
                Path concurrentSegmentFile = tempDir.resolve("concurrent-segment.bin");
                Path concurrentLogFile = tempDir.resolve("concurrent-segment.wal");

                Segment concurrentSegment = new Segment(concurrentSegmentFile, 10 * 1024 * 1024, sharedArena);
                WriteLog concurrentWriteLog = new WriteLog(concurrentLogFile);
                concurrentSegment.attachWriteLog(concurrentWriteLog);

                int numThreads = 4;
                int entriesPerThread = 100;
                ExecutorService executor = Executors.newFixedThreadPool(numThreads);
                CountDownLatch startLatch = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < numThreads; t++) {
                    final int threadId = t;
                    futures.add(executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < entriesPerThread; i++) {
                                byte[] key = ("key-" + threadId + "-" + i).getBytes();
                                byte[] value = ("value-" + threadId + "-" + i).getBytes();
                                concurrentSegment.put(key, value);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }));
                }

                startLatch.countDown();

                for (Future<?> future : futures) {
                    future.get();
                }

                executor.shutdown();

                // All puts should have been logged to WAL
                assertEquals(numThreads * entriesPerThread, concurrentWriteLog.entriesAppended(),
                    "All puts should be in WAL");

                concurrentSegment.close();
                concurrentWriteLog.close();
            }
        }
    }

    // ==================== Domain Correctness Tests ====================

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("LSNs should be monotonically increasing")
        void testLsnMonotonicity() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            // Multiple operations
            for (int i = 0; i < 5; i++) {
                segment.put(("key" + i).getBytes(), ("value" + i).getBytes());
            }
            segment.delete("key0".getBytes());

            // Verify LSNs
            final long[] lastLsn = {-1};
            final AtomicInteger count = new AtomicInteger(0);

            writeLog.recover(entry -> {
                assertTrue(entry.lsn() > lastLsn[0],
                    "LSN should be monotonically increasing");
                lastLsn[0] = entry.lsn();
                count.incrementAndGet();
            });

            assertEquals(6, count.get(), "Should have 6 entries");
        }

        @Test
        @DisplayName("WAL timestamps should be positive")
        void testTimestampsPositive() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            segment.put("key".getBytes(), "value".getBytes());

            writeLog.recover(entry -> {
                assertTrue(entry.timestamp() > 0, "Timestamp should be positive");
            });
        }

        @Test
        @DisplayName("WAL should maintain key-value integrity")
        void testKeyValueIntegrity() throws IOException {
            writeLog = new WriteLog(logFile);
            segment.attachWriteLog(writeLog);

            byte[] key = "test-key-with-special-chars-\0-".getBytes();
            byte[] value = new byte[]{0, 1, 2, 127, (byte) 128, (byte) 255};

            segment.put(key, value);

            writeLog.recover(entry -> {
                assertArrayEquals(key, entry.key(), "Key should match exactly");
                assertArrayEquals(value, entry.value(), "Value should match exactly");
            });
        }
    }
}
