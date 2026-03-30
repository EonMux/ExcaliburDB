package io.excalibur.store.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for WriteLog read operations.
 * Covers readFrom, readTail, and related methods.
 */
@DisplayName("WriteLog Read Operations Tests")
class WriteLogReadOperationsTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private WriteLog log;

    @BeforeEach
    void setUp() throws IOException {
        logPath = tempDir.resolve("test-read.log");
        log = new WriteLog(logPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (log != null) {
            log.close();
        }
    }

    @Nested
    @DisplayName("readFrom Operation")
    class ReadFromOperationTests {

        @Test
        @DisplayName("Should read entries from beginning offset")
        void shouldReadEntriesFromBeginningOffset() throws IOException {
            // Append some entries
            log.appendPut("key1", "value1");
            log.appendPut("key2", "value2");
            log.appendPut("key3", "value3");
            log.flush();

            // Read from offset 0
            List<LogEntry> entries = log.readFrom(0);

            assertThat(entries).hasSize(3);
        }

        @Test
        @DisplayName("Should read entries from middle offset")
        void shouldReadEntriesFromMiddleOffset() throws IOException {
            log.appendPut("key1", "value1");
            long secondOffset = log.writeHead();
            log.appendPut("key2", "value2");
            log.appendPut("key3", "value3");
            log.flush();

            // Read from second entry offset
            List<LogEntry> entries = log.readFrom(secondOffset);

            assertThat(entries).hasSize(2);
        }

        @Test
        @DisplayName("Should return empty list when offset at end")
        void shouldReturnEmptyListWhenOffsetAtEnd() throws IOException {
            log.appendPut("key1", "value1");
            long endOffset = log.writeHead();
            log.flush();

            List<LogEntry> entries = log.readFrom(endOffset);

            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("Should handle reading with delete entries")
        void shouldHandleReadingWithDeleteEntries() throws IOException {
            log.appendPut("key1", "value1");
            log.appendDelete("key1");
            log.appendPut("key2", "value2");
            log.flush();

            List<LogEntry> entries = log.readFrom(0);

            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).isPut()).isTrue();
            assertThat(entries.get(1).isDelete()).isTrue();
            assertThat(entries.get(2).isPut()).isTrue();
        }

        @Test
        @DisplayName("Should handle reading with barrier entries")
        void shouldHandleReadingWithBarrierEntries() throws IOException {
            log.appendPut("key1", "value1");
            log.appendBarrier();
            log.appendPut("key2", "value2");
            log.flush();

            List<LogEntry> entries = log.readFrom(0);

            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).isPut()).isTrue();
            assertThat(entries.get(1).isBarrier()).isTrue();
            assertThat(entries.get(2).isPut()).isTrue();
        }

        @Test
        @DisplayName("Should read entries with empty key and value")
        void shouldReadEntriesWithEmptyKeyAndValue() throws IOException {
            log.appendPut(new byte[0], new byte[0]);
            log.flush();

            List<LogEntry> entries = log.readFrom(0);

            assertThat(entries).hasSize(1);
            // Entry should exist even with empty key/value
            LogEntry entry = entries.get(0);
            assertThat(entry.isPut()).isTrue();
        }

        @Test
        @DisplayName("Should read entries with large payload")
        void shouldReadEntriesWithLargePayload() throws IOException {
            byte[] largeKey = "large-key".getBytes(StandardCharsets.UTF_8);
            byte[] largeValue = new byte[10000];
            for (int i = 0; i < largeValue.length; i++) {
                largeValue[i] = (byte) (i % 256);
            }

            log.appendPut(largeKey, largeValue);
            log.flush();

            List<LogEntry> entries = log.readFrom(0);

            assertThat(entries).hasSize(1);
            LogEntry.PutEntry putEntry = entries.get(0).asPutEntry();
            assertThat(putEntry.getValue()).isEqualTo(largeValue);
        }
    }

    @Nested
    @DisplayName("readTail Operation")
    class ReadTailOperationTests {

        @Test
        @DisplayName("Should read tail entries")
        void shouldReadTailEntries() throws IOException {
            for (int i = 0; i < 10; i++) {
                log.appendPut("key" + i, "value" + i);
            }
            log.flush();

            List<LogEntry> tail = log.readTail(3);

            assertThat(tail).hasSize(3);
            assertThat(tail.get(0).asPutEntry().getKeyAsString()).isEqualTo("key7");
            assertThat(tail.get(1).asPutEntry().getKeyAsString()).isEqualTo("key8");
            assertThat(tail.get(2).asPutEntry().getKeyAsString()).isEqualTo("key9");
        }

        @Test
        @DisplayName("Should handle requesting more entries than available")
        void shouldHandleRequestingMoreEntriesThanAvailable() throws IOException {
            log.appendPut("key1", "value1");
            log.appendPut("key2", "value2");
            log.flush();

            List<LogEntry> tail = log.readTail(10);

            assertThat(tail).hasSize(2);
        }

        @Test
        @DisplayName("Should return empty list for empty log")
        void shouldReturnEmptyListForEmptyLog() throws IOException {
            List<LogEntry> tail = log.readTail(5);

            assertThat(tail).isEmpty();
        }

        @Test
        @DisplayName("Should handle zero count")
        void shouldHandleZeroCount() throws IOException {
            log.appendPut("key1", "value1");
            log.flush();

            List<LogEntry> tail = log.readTail(0);

            assertThat(tail).isEmpty();
        }

        @Test
        @DisplayName("Should read tail with mixed entry types")
        void shouldReadTailWithMixedEntryTypes() throws IOException {
            log.appendPut("key1", "value1");
            log.appendDelete("key2");
            log.appendBarrier();
            log.appendPut("key3", "value3");
            log.flush();

            List<LogEntry> tail = log.readTail(2);

            assertThat(tail).hasSize(2);
            assertThat(tail.get(0).isBarrier()).isTrue();
            assertThat(tail.get(1).isPut()).isTrue();
        }
    }

    @Nested
    @DisplayName("scanRange Operation")
    class ScanRangeOperationTests {

        @Test
        @DisplayName("Should scan range from disk")
        void shouldScanRangeFromDisk() throws IOException {
            log.appendPut("key1", "value1");
            log.appendPut("key2", "value2");
            log.appendPut("key3", "value3");
            long endOffset = log.writeHead();
            log.flush();

            // Scan from beginning to end
            List<LogEntry> entries = log.scanRange(0, endOffset);

            assertThat(entries).hasSize(3);
        }

        @Test
        @DisplayName("Should scan partial range")
        void shouldScanPartialRange() throws IOException {
            log.appendPut("key1", "value1");
            long midOffset = log.writeHead();
            log.appendPut("key2", "value2");
            long endOffset = log.writeHead();
            log.flush();

            // Scan from middle to end
            List<LogEntry> entries = log.scanRange(midOffset, endOffset);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).asPutEntry().getKeyAsString()).isEqualTo("key2");
        }

        @Test
        @DisplayName("Should return empty for invalid range")
        void shouldReturnEmptyForInvalidRange() throws IOException {
            log.appendPut("key1", "value1");
            log.flush();

            List<LogEntry> entries = log.scanRange(1000, 2000);

            assertThat(entries).isEmpty();
        }
    }

    @Nested
    @DisplayName("parseDelete Operation")
    class ParseDeleteOperationTests {

        @Test
        @DisplayName("Should parse delete entry from raw bytes")
        void shouldParseDeleteEntryFromRawBytes() throws IOException {
            log.appendDelete("deleteKey");
            log.flush();

            List<LogEntry> entries = log.readFrom(0);
            assertThat(entries).hasSize(1);

            LogEntry entry = entries.get(0);
            LogEntry.DeleteEntry deleteEntry = entry.asDeleteEntry();

            assertThat(deleteEntry).isNotNull();
            assertThat(deleteEntry.getKeyAsString()).isEqualTo("deleteKey");
        }

        @Test
        @DisplayName("Should parse delete with empty key")
        void shouldParseDeleteWithEmptyKey() throws IOException {
            log.appendDelete(new byte[0]);
            log.flush();

            List<LogEntry> entries = log.readFrom(0);
            assertThat(entries).hasSize(1);

            LogEntry.DeleteEntry deleteEntry = entries.get(0).asDeleteEntry();
            assertThat(deleteEntry).isNotNull();
            assertThat(deleteEntry.getKey()).isEmpty();
        }
    }
}
