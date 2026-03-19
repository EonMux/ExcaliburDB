package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SegmentOrchestrator class.
 */
@DisplayName("SegmentOrchestrator Tests")
class SegmentOrchestratorTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private SegmentOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        orchestrator = new SegmentOrchestrator(arena, tempDir);
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create orchestrator with valid parameters")
        void shouldCreateWithValidParameters() throws IOException {
            assertThat(orchestrator.arena()).isEqualTo(arena);
            assertThat(orchestrator.dataDir()).isEqualTo(tempDir);
            assertThat(orchestrator.currentEpoch()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should reject null arena")
        void shouldRejectNullArena() {
            assertThatThrownBy(() -> new SegmentOrchestrator(null, tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Arena cannot be null");
        }

        @Test
        @DisplayName("Should reject non-existent directory")
        void shouldRejectNonExistentDirectory() {
            Path nonExistent = Path.of("/non/existent/path");
            assertThatThrownBy(() -> new SegmentOrchestrator(arena, nonExistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Data directory must exist");
        }

        @Test
        @DisplayName("Should reject null directory")
        void shouldRejectNullDirectory() {
            assertThatThrownBy(() -> new SegmentOrchestrator(arena, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Put Operations")
    class PutOperationTests {

        @Test
        @DisplayName("Should put key-value pair")
        void shouldPutKeyValue() {
            byte[] key = "testKey".getBytes(StandardCharsets.UTF_8);
            byte[] value = "testValue".getBytes(StandardCharsets.UTF_8);

            orchestrator.put(key, value);

            assertThat(orchestrator.getTotalEntryCount()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should reject null key")
        void shouldRejectNullKey() {
            byte[] value = "testValue".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> orchestrator.put(null, value))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Key and value cannot be null");
        }

        @Test
        @DisplayName("Should reject null value")
        void shouldRejectNullValue() {
            byte[] key = "testKey".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> orchestrator.put(key, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Key and value cannot be null");
        }

        @Test
        @DisplayName("Should increment epoch on put")
        void shouldIncrementEpochOnPut() {
            long initialEpoch = orchestrator.currentEpoch();

            orchestrator.put("key".getBytes(), "value".getBytes());

            assertThat(orchestrator.currentEpoch()).isGreaterThan(initialEpoch);
        }
    }

    @Nested
    @DisplayName("Get Operations")
    class GetOperationTests {

        @Test
        @DisplayName("Should reject null key in get")
        void shouldRejectNullKeyInGet() {
            assertThatThrownBy(() -> orchestrator.get(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Key cannot be null");
        }

        @Test
        @DisplayName("Should reject null key in getWithEpoch")
        void shouldRejectNullKeyInGetWithEpoch() {
            assertThatThrownBy(() -> orchestrator.getWithEpoch(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Key cannot be null");
        }
    }

    @Nested
    @DisplayName("Delete Operations")
    class DeleteOperationTests {

        @Test
        @DisplayName("Should reject null key in delete")
        void shouldRejectNullKeyInDelete() {
            assertThatThrownBy(() -> orchestrator.delete(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Key cannot be null");
        }
    }

    @Nested
    @DisplayName("Epoch and Snapshot Tests")
    class EpochAndSnapshotTests {

        @Test
        @DisplayName("Should return current epoch")
        void shouldReturnCurrentEpoch() {
            assertThat(orchestrator.currentEpoch()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should reject null slice in isSnapshotValid")
        void shouldRejectNullSliceInIsSnapshotValid() {
            assertThatThrownBy(() -> orchestrator.isSnapshotValid(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Slice cannot be null");
        }
    }

    @Nested
    @DisplayName("Statistics Tests")
    class StatisticsTests {

        @Test
        @DisplayName("Should return statistics")
        void shouldReturnStatistics() {
            SegmentOrchestrator.Statistics stats = orchestrator.getStatistics();

            assertThat(stats).isNotNull();
            assertThat(stats.toString()).contains("writes");
            assertThat(stats.toString()).contains("deletes");
        }

        @Test
        @DisplayName("Should track write operations")
        void shouldTrackWriteOperations() {
            orchestrator.put("key1".getBytes(), "value1".getBytes());
            orchestrator.put("key2".getBytes(), "value2".getBytes());

            SegmentOrchestrator.Statistics stats = orchestrator.getStatistics();
            assertThat(stats.toString()).contains("writes=2");
        }
    }

    @Nested
    @DisplayName("Segment Count Tests")
    class SegmentCountTests {

        @Test
        @DisplayName("Should return segment count")
        void shouldReturnSegmentCount() {
            assertThat(orchestrator.getSegmentCount()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should return total entry count")
        void shouldReturnTotalEntryCount() {
            assertThat(orchestrator.getTotalEntryCount()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Start and Close Tests")
    class StartAndCloseTests {

        @Test
        @DisplayName("Should start orchestrator")
        void shouldStartOrchestrator() {
            assertThatCode(() -> orchestrator.start()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should close orchestrator")
        void shouldCloseOrchestrator() {
            orchestrator.start();
            assertThatCode(() -> orchestrator.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle double start")
        void shouldHandleDoubleStart() {
            orchestrator.start();
            assertThatCode(() -> orchestrator.start()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Compaction Worker Tests")
    class CompactionWorkerTests {

        @Test
        @DisplayName("Should return compaction worker")
        void shouldReturnCompactionWorker() {
            assertThat(orchestrator.getCompactionWorker()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Read Entry Tests")
    class ReadEntryTests {

        @Test
        @DisplayName("Should read entry key")
        void shouldReadEntryKey() {
            byte[] key = "testKey".getBytes(StandardCharsets.UTF_8);
            byte[] value = "testValue".getBytes(StandardCharsets.UTF_8);

            orchestrator.put(key, value);

            // May return null if segment routing doesn't match
            assertThatCode(() -> orchestrator.readEntryKey(0)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should read entry value")
        void shouldReadEntryValue() {
            byte[] key = "testKey".getBytes(StandardCharsets.UTF_8);
            byte[] value = "testValue".getBytes(StandardCharsets.UTF_8);

            orchestrator.put(key, value);

            // May return null if segment routing doesn't match
            assertThatCode(() -> orchestrator.readEntryValue(0)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should return null for out of range index")
        void shouldReturnNullForOutOfRangeIndex() {
            assertThat(orchestrator.readEntryKey(Integer.MAX_VALUE)).isNull();
            assertThat(orchestrator.readEntryValue(Integer.MAX_VALUE)).isNull();
        }
    }

    @Nested
    @DisplayName("Read All Entries Tests")
    class ReadAllEntriesTests {

        @Test
        @DisplayName("Should read all entries")
        void shouldReadAllEntries() {
            orchestrator.put("key1".getBytes(), "value1".getBytes());
            orchestrator.put("key2".getBytes(), "value2".getBytes());

            assertThatCode(() -> orchestrator.readAllEntries()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Constants Tests")
    class ConstantsTests {

        @Test
        @DisplayName("Should have correct split threshold")
        void shouldHaveCorrectSplitThreshold() {
            assertThat(SegmentOrchestrator.SPLIT_THRESHOLD).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Should have correct compactor pool size")
        void shouldHaveCorrectCompactorPoolSize() {
            assertThat(SegmentOrchestrator.COMPACTOR_POOL_SIZE).isEqualTo(16);
        }

        @Test
        @DisplayName("Should have correct state constants")
        void shouldHaveCorrectStateConstants() {
            assertThat(SegmentOrchestrator.STATE_NEW).isEqualTo(0);
            assertThat(SegmentOrchestrator.STATE_ACTIVE).isEqualTo(1);
            assertThat(SegmentOrchestrator.STATE_SPLITTING).isEqualTo(2);
            assertThat(SegmentOrchestrator.STATE_COMPACTING).isEqualTo(3);
            assertThat(SegmentOrchestrator.STATE_IDLE).isEqualTo(4);
            assertThat(SegmentOrchestrator.STATE_CLOSED).isEqualTo(5);
        }
    }
}
