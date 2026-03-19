package io.excalibur.store.log;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for WriteLog class.
 */
@DisplayName("WriteLog Tests")
class WriteLogTest {

    @TempDir
    Path tempDir;

    private Path logPath;

    @BeforeEach
    void setUp() throws IOException {
        logPath = tempDir.resolve("test.log");
    }

    @Nested
    @DisplayName("Memory-Only Constructor")
    class MemoryOnlyConstructorTests {

        @Test
        @DisplayName("Should create memory-only log")
        void shouldCreateMemoryOnlyLog() throws IOException {
            WriteLog log = new WriteLog(100);

            assertThat(log.getPath()).isNull();
            assertThat(log.getMaxInMemory()).isEqualTo(100);
            assertThat(log.fileSize()).isEqualTo(0);

            log.close();
        }

        @Test
        @DisplayName("Should close memory-only log without error")
        void shouldCloseMemoryOnlyLog() throws IOException {
            WriteLog log = new WriteLog(100);
            log.close();
        }
    }

    @Nested
    @DisplayName("File-Based Constructor")
    class FileBasedConstructorTests {

        @Test
        @DisplayName("Should create file-based log")
        void shouldCreateFileBasedLog() throws IOException {
            WriteLog log = new WriteLog(logPath);

            assertThat(log.getPath()).isEqualTo(logPath);
            assertThat(log.getMaxInMemory()).isEqualTo(100000);
            assertThat(Files.exists(logPath)).isTrue();

            log.close();
        }

        @Test
        @DisplayName("Should create file-based log with custom memory limit")
        void shouldCreateFileBasedLogWithCustomLimit() throws IOException {
            WriteLog log = new WriteLog(logPath, 500);

            assertThat(log.getMaxInMemory()).isEqualTo(500);

            log.close();
        }

        @Test
        @DisplayName("Should pre-allocate file space")
        void shouldPreAllocateFileSpace() throws IOException {
            WriteLog log = new WriteLog(logPath);

            // File should have some initial size from pre-allocation
            assertThat(log.fileSize()).isGreaterThan(0);

            log.close();
        }
    }

    @Nested
    @DisplayName("Append Operations")
    class AppendOperationTests {

        @Test
        @DisplayName("Should append PUT entry with strings")
        void shouldAppendPutWithStrings() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendPut("key1", "value1");

                assertThat(id).isGreaterThanOrEqualTo(0);
                assertThat(log.inMemoryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should append PUT entry with bytes")
        void shouldAppendPutWithBytes() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                byte[] key = "key1".getBytes(StandardCharsets.UTF_8);
                byte[] value = "value1".getBytes(StandardCharsets.UTF_8);

                long id = log.appendPut(key, value);

                assertThat(id).isGreaterThanOrEqualTo(0);
                assertThat(log.inMemoryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should append DELETE entry with string")
        void shouldAppendDeleteWithString() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendDelete("key1");

                assertThat(id).isGreaterThanOrEqualTo(0);
                assertThat(log.inMemoryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should append DELETE entry with bytes")
        void shouldAppendDeleteWithBytes() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                byte[] key = "key1".getBytes(StandardCharsets.UTF_8);

                long id = log.appendDelete(key);

                assertThat(id).isGreaterThanOrEqualTo(0);
                assertThat(log.inMemoryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should append barrier entry")
        void shouldAppendBarrier() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendBarrier();

                assertThat(id).isGreaterThanOrEqualTo(0);
                assertThat(log.inMemoryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should increment write head after append")
        void shouldIncrementWriteHead() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long initialHead = log.writeHead();

                log.appendPut("key1", "value1");

                assertThat(log.writeHead()).isGreaterThan(initialHead);
            }
        }

        @Test
        @DisplayName("Should append multiple entries")
        void shouldAppendMultipleEntries() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendPut("key2", "value2");
                log.appendDelete("key1");

                assertThat(log.inMemoryCount()).isEqualTo(3);
            }
        }
    }

    @Nested
    @DisplayName("Entry ID Management")
    class EntryIdManagementTests {

        @Test
        @DisplayName("Should return highest entry ID")
        void shouldReturnHighestEntryId() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendPut("key2", "value2");

                assertThat(log.highestEntryId()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should reset counter")
        void shouldResetCounter() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.resetCounter(100);

                assertThat(log.highestEntryId()).isGreaterThanOrEqualTo(100);
            }
        }

        @Test
        @DisplayName("Should return correct entry count")
        void shouldReturnCorrectEntryCount() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendPut("key2", "value2");

                assertThat(log.entryCount()).isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Checksum Operations")
    class ChecksumOperationTests {

        @Test
        @DisplayName("Should verify checksum")
        void shouldVerifyChecksum() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
                long checksum = 12345L;

                boolean valid = log.verifyChecksum(LogEntry.TYPE_PUT, 1L, payload, checksum);

                assertThat(valid).isFalse(); // Wrong checksum
            }
        }
    }

    @Nested
    @DisplayName("Sync and Flush Operations")
    class SyncFlushOperationTests {

        @Test
        @DisplayName("Should sync log")
        void shouldSyncLog() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.sync();
            }
        }

        @Test
        @DisplayName("Should flush log")
        void shouldFlushLog() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.flush();
            }
        }
    }

    @Nested
    @DisplayName("Truncate Operations")
    class TruncateOperationTests {

        @Test
        @DisplayName("Should truncate log")
        void shouldTruncateLog() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.sync();

                long size = log.fileSize();
                log.truncate(size / 2);

                assertThat(log.fileSize()).isLessThanOrEqualTo(size / 2);
            }
        }

        @Test
        @DisplayName("Should reject truncate beyond file size")
        void shouldRejectTruncateBeyondFileSize() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.sync();

                assertThatThrownBy(() -> log.truncate(Long.MAX_VALUE))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    @DisplayName("Recovery Operations")
    class RecoveryOperationTests {

        @Test
        @DisplayName("Should get last barrier offset")
        void shouldGetLastBarrierOffset() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendBarrier();
                log.appendPut("key2", "value2");

                long barrierOffset = log.getLastBarrierOffset();

                assertThat(barrierOffset).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should return -1 when no barrier")
        void shouldReturnMinusOneWhenNoBarrier() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");

                long barrierOffset = log.getLastBarrierOffset();

                assertThat(barrierOffset).isEqualTo(-1);
            }
        }
    }

    @Nested
    @DisplayName("Integration Offset")
    class IntegrationOffsetTests {

        @Test
        @DisplayName("Should get last integrated offset")
        void shouldGetLastIntegratedOffset() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                assertThat(log.getLastIntegratedOffset()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should set last integrated offset")
        void shouldSetLastIntegratedOffset() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.setLastIntegratedOffset(100);
                // No exception expected
            }
        }
    }

    @Nested
    @DisplayName("Memory Eviction")
    class MemoryEvictionTests {

        @Test
        @DisplayName("Should evict old entries when limit exceeded")
        void shouldEvictOldEntries() throws IOException {
            // The eviction logic happens after adding, so we might briefly exceed the limit
            try (WriteLog log = new WriteLog(logPath, 5)) {
                for (int i = 0; i < 20; i++) {
                    log.appendPut("key" + i, "value" + i);
                }

                // After many appends, eviction should have occurred
                // The limit might be exceeded temporarily but should eventually stabilize
                assertThat(log.inMemoryCount()).isLessThanOrEqualTo(15);
            }
        }
    }

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("Should return file size")
        void shouldReturnFileSize() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long size = log.fileSize();
                assertThat(size).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("Should return write head")
        void shouldReturnWriteHead() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long head = log.writeHead();
                assertThat(head).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should return in-memory count")
        void shouldReturnInMemoryCount() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendPut("key2", "value2");

                assertThat(log.inMemoryCount()).isEqualTo(2);
                assertThat(log.getInMemoryEntriesCount()).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Memory-Only Operations")
    class MemoryOnlyOperationTests {

        @Test
        @DisplayName("Memory-only log should have zero file size")
        void memoryOnlyShouldHaveZeroFileSize() throws IOException {
            WriteLog log = new WriteLog(100);

            assertThat(log.fileSize()).isEqualTo(0);

            log.close();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle close on memory-only log")
        void shouldHandleCloseOnMemoryOnlyLog() throws IOException {
            WriteLog log = new WriteLog(100);
            log.close(); // Should not throw
        }

        @Test
        @DisplayName("Should handle append after many entries")
        void shouldHandleAppendAfterManyEntries() throws IOException {
            try (WriteLog log = new WriteLog(logPath, 10)) {
                for (int i = 0; i < 50; i++) {
                    log.appendPut("key" + i, "value" + i);
                }

                assertThat(log.entryCount()).isGreaterThanOrEqualTo(50);
            }
        }

        @Test
        @DisplayName("Should handle delete with large key")
        void shouldHandleDeleteWithLargeKey() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                byte[] largeKey = new byte[200];
                for (int i = 0; i < largeKey.length; i++) {
                    largeKey[i] = (byte) (i % 256);
                }

                long id = log.appendDelete(largeKey);

                assertThat(id).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Entry Type Constants")
    class EntryTypeConstantsTests {

        @Test
        @DisplayName("TYPE_PUT should be 1")
        void typePut() {
            assertThat(WriteLog.TYPE_PUT).isEqualTo((byte) 1);
        }

        @Test
        @DisplayName("TYPE_DELETE should be 2")
        void typeDelete() {
            assertThat(WriteLog.TYPE_DELETE).isEqualTo((byte) 2);
        }

        @Test
        @DisplayName("TYPE_BARRIER should be 3")
        void typeBarrier() {
            assertThat(WriteLog.TYPE_BARRIER).isEqualTo((byte) 3);
        }
    }

    @Nested
    @DisplayName("File Operations")
    class FileOperationTests {

        @Test
        @DisplayName("Should return correct file size after appends")
        void shouldReturnCorrectFileSizeAfterAppends() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long initialSize = log.fileSize();
                assertThat(initialSize).isGreaterThan(0); // Pre-allocated

                log.appendPut("key1", "value1");
                log.sync();

                long newSize = log.fileSize();
                assertThat(newSize).isGreaterThanOrEqualTo(initialSize);
            }
        }

        @Test
        @DisplayName("Should handle sync after append")
        void shouldHandleSyncAfterAppend() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.sync();
                // Should not throw
            }
        }

        @Test
        @DisplayName("Should handle multiple syncs")
        void shouldHandleMultipleSyncs() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.sync();
                log.appendPut("key1", "value1");
                log.sync();
                log.appendPut("key2", "value2");
                log.sync();
                // Should not throw
            }
        }

        @Test
        @DisplayName("Should handle flush")
        void shouldHandleFlush() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.flush();
                // Should not throw
            }
        }
    }

    @Nested
    @DisplayName("Memory-Only Log Operations")
    class MemoryOnlyLogOperationTests {

        @Test
        @DisplayName("Should return null path for memory-only log")
        void shouldReturnNullPathForMemoryOnlyLog() throws IOException {
            WriteLog log = new WriteLog(100);

            assertThat(log.getPath()).isNull();
            log.close();
        }
    }

    @Nested
    @DisplayName("Barrier Operations")
    class BarrierOperationTests {

        @Test
        @DisplayName("Should append barrier entry")
        void shouldAppendBarrierEntry() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendBarrier();

                assertThat(id).isGreaterThanOrEqualTo(0);
                assertThat(log.inMemoryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should get last barrier offset")
        void shouldGetLastBarrierOffset() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendBarrier();
                log.appendPut("key2", "value2");

                long offset = log.getLastBarrierOffset();

                assertThat(offset).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should return -1 when no barrier exists")
        void shouldReturnMinusOneWhenNoBarrierExists() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendPut("key1", "value1");
                log.appendPut("key2", "value2");

                long offset = log.getLastBarrierOffset();

                assertThat(offset).isEqualTo(-1);
            }
        }

        @Test
        @DisplayName("Should find last barrier among multiple")
        void shouldFindLastBarrierAmongMultiple() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                log.appendBarrier();
                log.appendPut("key1", "value1");
                log.appendBarrier();
                log.appendPut("key2", "value2");

                long offset = log.getLastBarrierOffset();

                assertThat(offset).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("Byte Array Operations")
    class ByteArrayOperationTests {

        @Test
        @DisplayName("Should handle empty key in put")
        void shouldHandleEmptyKeyInPut() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendPut(new byte[0], "value".getBytes());

                assertThat(id).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should handle empty value in put")
        void shouldHandleEmptyValueInPut() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendPut("key".getBytes(), new byte[0]);

                assertThat(id).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should handle empty key in delete")
        void shouldHandleEmptyKeyInDelete() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                long id = log.appendDelete(new byte[0]);

                assertThat(id).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should handle large value")
        void shouldHandleLargeValue() throws IOException {
            try (WriteLog log = new WriteLog(logPath)) {
                byte[] largeValue = new byte[1000];
                for (int i = 0; i < largeValue.length; i++) {
                    largeValue[i] = (byte) (i % 256);
                }

                long id = log.appendPut("key".getBytes(), largeValue);

                assertThat(id).isGreaterThanOrEqualTo(0);
            }
        }
    }
}
