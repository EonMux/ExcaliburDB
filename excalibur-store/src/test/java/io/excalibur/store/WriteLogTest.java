package io.excalibur.store;

import io.excalibur.store.format.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for WriteLog class.
 */
@DisplayName("WriteLog Tests")
class WriteLogTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path logFile;
    private WriteLog writeLog;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofConfined();
        logFile = tempDir.resolve("test.wal");
        writeLog = new WriteLog(logFile, arena);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null && !writeLog.isClosed()) {
            writeLog.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create WriteLog with valid parameters")
        void shouldCreateWithValidParameters() throws IOException {
            assertThat(writeLog.path()).isEqualTo(logFile);
            assertThat(writeLog.isClosed()).isFalse();
            assertThat(writeLog.nextLsn()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should reject null path")
        void shouldRejectNullPath() {
            assertThatThrownBy(() -> new WriteLog(null, arena))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Log file path cannot be null");
        }

        @Test
        @DisplayName("Should reject null arena")
        void shouldRejectNullArena() {
            assertThatThrownBy(() -> new WriteLog(logFile, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Arena cannot be null");
        }

        @Test
        @DisplayName("Should create parent directories if needed")
        void shouldCreateParentDirectories() throws IOException {
            Path nestedPath = tempDir.resolve("nested/dir/test.wal");
            WriteLog nestedLog = new WriteLog(nestedPath, arena);

            assertThat(Files.exists(nestedPath)).isTrue();
            nestedLog.close();
        }

        @Test
        @DisplayName("Should open existing log file")
        void shouldOpenExistingLogFile() throws IOException {
            // Create and close a log
            writeLog.appendPut("key1".getBytes(), "value1".getBytes());
            writeLog.close();

            // Reopen the same log
            Arena newArena = Arena.ofConfined();
            WriteLog reopenedLog = new WriteLog(logFile, newArena);

            // Should have scanned the existing entry
            assertThat(reopenedLog.nextLsn()).isGreaterThan(0);

            reopenedLog.close();
            newArena.close();
        }
    }

    @Nested
    @DisplayName("Append Tests")
    class AppendTests {

        @Test
        @DisplayName("Should append PUT entry")
        void shouldAppendPut() throws IOException {
            long lsn = writeLog.appendPut("key".getBytes(), "value".getBytes());

            assertThat(lsn).isEqualTo(0);
            assertThat(writeLog.entriesAppended()).isEqualTo(1);
            assertThat(writeLog.nextLsn()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should append DELETE entry")
        void shouldAppendDelete() throws IOException {
            long lsn = writeLog.appendDelete("key".getBytes());

            assertThat(lsn).isEqualTo(0);
            assertThat(writeLog.entriesAppended()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should append with generic append method")
        void shouldAppendGeneric() throws IOException {
            long lsn = writeLog.append(LogEntry.OPCODE_PUT, "key".getBytes(), "value".getBytes());

            assertThat(lsn).isEqualTo(0);
            assertThat(writeLog.bytesWritten()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should reject null key in append")
        void shouldRejectNullKey() {
            assertThatThrownBy(() -> writeLog.append(LogEntry.OPCODE_PUT, null, "value".getBytes()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Key cannot be null");
        }

        @Test
        @DisplayName("Should reject null value in append")
        void shouldRejectNullValue() {
            assertThatThrownBy(() -> writeLog.append(LogEntry.OPCODE_PUT, "key".getBytes(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Value cannot be null");
        }

        @Test
        @DisplayName("Should reject invalid opcode")
        void shouldRejectInvalidOpcode() {
            assertThatThrownBy(() -> writeLog.append((byte) 99, "key".getBytes(), "value".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid opcode");
        }

        @Test
        @DisplayName("Should increment LSN monotonically")
        void shouldIncrementLsnMonotonically() throws IOException {
            long lsn1 = writeLog.appendPut("key1".getBytes(), "value1".getBytes());
            long lsn2 = writeLog.appendPut("key2".getBytes(), "value2".getBytes());
            long lsn3 = writeLog.appendDelete("key1".getBytes());

            assertThat(lsn2).isGreaterThan(lsn1);
            assertThat(lsn3).isGreaterThan(lsn2);
        }

        @Test
        @DisplayName("Should track bytes written")
        void shouldTrackBytesWritten() throws IOException {
            long initial = writeLog.bytesWritten();

            writeLog.appendPut("key".getBytes(), "value".getBytes());

            assertThat(writeLog.bytesWritten()).isGreaterThan(initial);
        }
    }

    @Nested
    @DisplayName("Recovery Tests")
    class RecoveryTests {

        @Test
        @DisplayName("Should recover entries")
        void shouldRecoverEntries() throws IOException {
            writeLog.appendPut("key1".getBytes(), "value1".getBytes());
            writeLog.appendPut("key2".getBytes(), "value2".getBytes());

            List<LogEntry> entries = new ArrayList<>();
            boolean result = writeLog.recover(entries::add);

            assertThat(result).isTrue();
            assertThat(entries).hasSize(2);
            assertThat(writeLog.isDomainCorrect()).isTrue();
        }

        @Test
        @DisplayName("Should reject null handler in recover")
        void shouldRejectNullHandler() {
            assertThatThrownBy(() -> writeLog.recover(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Handler cannot be null");
        }

        @Test
        @DisplayName("Should recover in LSN order")
        void shouldRecoverInLsnOrder() throws IOException {
            for (int i = 0; i < 5; i++) {
                writeLog.appendPut(("key" + i).getBytes(), ("value" + i).getBytes());
            }

            List<Long> lsns = new ArrayList<>();
            writeLog.recover(entry -> lsns.add(entry.lsn()));

            assertThat(lsns).containsExactly(0L, 1L, 2L, 3L, 4L);
        }
    }

    @Nested
    @DisplayName("Read Entry Tests")
    class ReadEntryTests {

        @Test
        @DisplayName("Should read entry at offset")
        void shouldReadEntryAtOffset() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());

            LogEntry entry = writeLog.readEntryAt(0);

            assertThat(entry).isNotNull();
            assertThat(entry.isPut()).isTrue();
        }

        @Test
        @DisplayName("Should return null for negative offset")
        void shouldReturnNullForNegativeOffset() throws IOException {
            LogEntry entry = writeLog.readEntryAt(-1);

            assertThat(entry).isNull();
        }

        @Test
        @DisplayName("Should return null for offset beyond end")
        void shouldReturnNullForOffsetBeyondEnd() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());

            LogEntry entry = writeLog.readEntryAt(Long.MAX_VALUE);

            assertThat(entry).isNull();
        }

        @Test
        @DisplayName("Should get entry size at offset")
        void shouldGetEntrySizeAtOffset() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());

            int size = writeLog.entrySizeAt(0);

            assertThat(size).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should return -1 for invalid offset in entrySizeAt")
        void shouldReturnNegativeOneForInvalidOffset() throws IOException {
            int size = writeLog.entrySizeAt(-1);

            assertThat(size).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("Log Rotation Tests")
    class LogRotationTests {

        @Test
        @DisplayName("Should rotate log")
        void shouldRotateLog() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());
            long initialLsn = writeLog.nextLsn();

            writeLog.rotate();

            assertThat(writeLog.nextLsn()).isEqualTo(0);
            assertThat(writeLog.writeOffset()).isEqualTo(0);
            assertThat(writeLog.getRotatedLogPath()).isNotNull();
        }

        @Test
        @DisplayName("Should get rotated log path")
        void shouldGetRotatedLogPath() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());
            writeLog.rotate();

            Path rotatedPath = writeLog.getRotatedLogPath();

            assertThat(rotatedPath).isNotNull();
            assertThat(Files.exists(rotatedPath)).isTrue();
        }

        @Test
        @DisplayName("Should return null for rotated path when none exists")
        void shouldReturnNullForRotatedPathWhenNoneExists() {
            Path rotatedPath = writeLog.getRotatedLogPath();

            assertThat(rotatedPath).isNull();
        }

        @Test
        @DisplayName("Should delete rotated log")
        void shouldDeleteRotatedLog() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());
            writeLog.rotate();

            boolean deleted = writeLog.deleteRotatedLog();

            assertThat(deleted).isTrue();
            assertThat(writeLog.getRotatedLogPath()).isNull();
        }

        @Test
        @DisplayName("Should return false when no rotated log to delete")
        void shouldReturnFalseWhenNoRotatedLogToDelete() throws IOException {
            boolean deleted = writeLog.deleteRotatedLog();

            assertThat(deleted).isFalse();
        }
    }

    @Nested
    @DisplayName("Integrated Offset Tests")
    class IntegratedOffsetTests {

        @Test
        @DisplayName("Should get and set last integrated offset")
        void shouldGetAndSetLastIntegratedOffset() {
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(0);

            writeLog.setLastIntegratedOffset(100);

            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(100);
            assertThat(writeLog.lastIntegratedOffset()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should increment last integrated offset")
        void shouldIncrementLastIntegratedOffset() {
            writeLog.setLastIntegratedOffset(100);

            writeLog.incrementLastIntegratedOffset(50);

            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(150);
        }
    }

    @Nested
    @DisplayName("Domain Correctness Tests")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Should get and set domain correct flag")
        void shouldGetAndSetDomainCorrectFlag() {
            writeLog.setDomainCorrect(true);

            assertThat(writeLog.isDomainCorrect()).isTrue();

            writeLog.setDomainCorrect(false);

            assertThat(writeLog.isDomainCorrect()).isFalse();
        }
    }

    @Nested
    @DisplayName("Statistics Tests")
    class StatisticsTests {

        @Test
        @DisplayName("Should track file size")
        void shouldTrackFileSize() throws IOException {
            long size = writeLog.fileSize();

            assertThat(size).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should track entries appended")
        void shouldTrackEntriesAppended() throws IOException {
            writeLog.appendPut("key1".getBytes(), "value1".getBytes());
            writeLog.appendPut("key2".getBytes(), "value2".getBytes());

            assertThat(writeLog.entriesAppended()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should track corrupt entries")
        void shouldTrackCorruptEntries() {
            assertThat(writeLog.corruptEntriesFound()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should check should rotate")
        void shouldCheckShouldRotate() throws IOException {
            assertThat(writeLog.shouldRotate()).isFalse();
        }

        @Test
        @DisplayName("Should get write offset")
        void shouldGetWriteOffset() throws IOException {
            long offset = writeLog.writeOffset();

            assertThat(offset).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Close Tests")
    class CloseTests {

        @Test
        @DisplayName("Should close successfully")
        void shouldCloseSuccessfully() throws IOException {
            writeLog.close();

            assertThat(writeLog.isClosed()).isTrue();
        }

        @Test
        @DisplayName("Should handle double close")
        void shouldHandleDoubleClose() throws IOException {
            writeLog.close();
            writeLog.close(); // Should not throw

            assertThat(writeLog.isClosed()).isTrue();
        }

        @Test
        @DisplayName("Should reject append when closed")
        void shouldRejectAppendWhenClosed() throws IOException {
            writeLog.close();

            assertThatThrownBy(() -> writeLog.appendPut("key".getBytes(), "value".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WriteLog is closed");
        }

        @Test
        @DisplayName("Should reject recover when closed")
        void shouldRejectRecoverWhenClosed() throws IOException {
            writeLog.close();

            assertThatThrownBy(() -> writeLog.recover(entry -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WriteLog is closed");
        }

        @Test
        @DisplayName("Should reject rotate when closed")
        void shouldRejectRotateWhenClosed() throws IOException {
            writeLog.close();

            assertThatThrownBy(() -> writeLog.rotate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WriteLog is closed");
        }

        @Test
        @DisplayName("Should reject readEntryAt when closed")
        void shouldRejectReadEntryAtWhenClosed() throws IOException {
            writeLog.close();

            assertThatThrownBy(() -> writeLog.readEntryAt(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WriteLog is closed");
        }

        @Test
        @DisplayName("Should reject entrySizeAt when closed")
        void shouldRejectEntrySizeAtWhenClosed() throws IOException {
            writeLog.close();

            assertThatThrownBy(() -> writeLog.entrySizeAt(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WriteLog is closed");
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should contain key fields")
        void shouldContainKeyFields() throws IOException {
            writeLog.appendPut("key".getBytes(), "value".getBytes());

            String str = writeLog.toString();

            assertThat(str).contains("WriteLog");
            assertThat(str).contains(logFile.toString());
            assertThat(str).contains("entries=1");
        }
    }

    @Nested
    @DisplayName("Path Tests")
    class PathTests {

        @Test
        @DisplayName("Should return log file path")
        void shouldReturnLogFilePath() {
            assertThat(writeLog.path()).isEqualTo(logFile);
        }
    }

    // ==================== lastIntegratedOffset Domain Constraints ====================

    @Nested
    @DisplayName("lastIntegratedOffset Domain Constraints")
    class LastIntegratedOffsetDomainTests {

        @Test
        @DisplayName("Should initialize lastIntegratedOffset to 0")
        void shouldInitializeToZero() {
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(0L);
            assertThat(writeLog.lastIntegratedOffset()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should accept non-negative offset in setLastIntegratedOffset")
        void shouldAcceptNonNegativeOffset() {
            // Initial value is 0, so set to positive value first
            writeLog.setLastIntegratedOffset(100L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(100L);

            // Larger positive is valid
            writeLog.setLastIntegratedOffset(1000L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("Should reject negative offset in setLastIntegratedOffset")
        void shouldRejectNegativeOffset() {
            assertThatThrownBy(() -> writeLog.setLastIntegratedOffset(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("Should enforce monotonicity in setLastIntegratedOffset")
        void shouldEnforceMonotonicity() {
            writeLog.setLastIntegratedOffset(1000L);

            // Attempt to decrease - should fail
            assertThatThrownBy(() -> writeLog.setLastIntegratedOffset(500L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("monotonically increasing");

            // Current value should remain unchanged
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1000L);

            // Same value - should fail (not an increase)
            assertThatThrownBy(() -> writeLog.setLastIntegratedOffset(1000L))
                .isInstanceOf(IllegalStateException.class);

            // Increase - should succeed
            writeLog.setLastIntegratedOffset(1500L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1500L);
        }

        @Test
        @DisplayName("Should accept non-negative delta in incrementLastIntegratedOffset")
        void shouldAcceptNonNegativeDelta() {
            writeLog.setLastIntegratedOffset(1000L);

            // Zero delta is valid (no-op)
            writeLog.incrementLastIntegratedOffset(0L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1000L);

            // Positive delta is valid
            writeLog.incrementLastIntegratedOffset(500L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1500L);
        }

        @Test
        @DisplayName("Should reject negative delta in incrementLastIntegratedOffset")
        void shouldRejectNegativeDelta() {
            writeLog.setLastIntegratedOffset(1000L);

            assertThatThrownBy(() -> writeLog.incrementLastIntegratedOffset(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("Should detect overflow in incrementLastIntegratedOffset")
        void shouldDetectOverflow() {
            writeLog.setLastIntegratedOffset(Long.MAX_VALUE - 10);

            // This should succeed
            writeLog.incrementLastIntegratedOffset(10L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(Long.MAX_VALUE);

            // This should fail due to overflow
            assertThatThrownBy(() -> writeLog.incrementLastIntegratedOffset(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overflow");
        }

        @Test
        @DisplayName("Should handle large offsets correctly")
        void shouldHandleLargeOffsets() {
            // Half of max
            long halfMax = Long.MAX_VALUE / 2;
            writeLog.setLastIntegratedOffset(halfMax);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(halfMax);

            // Increment by same amount (no overflow)
            writeLog.incrementLastIntegratedOffset(halfMax);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(halfMax * 2);
        }

        @Test
        @DisplayName("Should maintain domain constraints across operations")
        void shouldMaintainDomainConstraintsAcrossOperations() {
            // Start at 0
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(0L);

            // Increment multiple times
            writeLog.incrementLastIntegratedOffset(100L);
            writeLog.incrementLastIntegratedOffset(200L);
            writeLog.incrementLastIntegratedOffset(300L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(600L);

            // Set to higher value
            writeLog.setLastIntegratedOffset(1000L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1000L);

            // Increment again
            writeLog.incrementLastIntegratedOffset(500L);
            assertThat(writeLog.getLastIntegratedOffset()).isEqualTo(1500L);

            // Verify all intermediate values were non-negative
            assertThat(writeLog.getLastIntegratedOffset()).isGreaterThanOrEqualTo(0L);
        }
    }
}
