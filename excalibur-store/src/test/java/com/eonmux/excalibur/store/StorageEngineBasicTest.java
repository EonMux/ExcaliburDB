package com.eonmux.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Basic tests for StorageEngine operations.
 */
@DisplayName("StorageEngine Basic Tests")
class StorageEngineBasicTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path dataDir;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        dataDir = tempDir.resolve("storage-basic-test");
        Files.createDirectories(dataDir);
        engine = new StorageEngine(dataDir, arena);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    /**
     * Copies a returned zero-copy slice onto the heap so its bytes can be compared.
     *
     * @param slice The slice returned by the engine
     * @return The bytes it points at
     */
    private static byte[] bytesOf(MemorySegment slice) {
        return slice.toArray(ValueLayout.JAVA_BYTE);
    }

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Should create storage engine")
        void shouldCreateStorageEngine() {
            assertThat(engine).isNotNull();
        }

        @Test
        @DisplayName("Should start storage engine")
        void shouldStartStorageEngine() throws IOException {
            engine.start();
            assertThat(engine.isClosed()).isFalse();
        }

        @Test
        @DisplayName("Should handle multiple start calls")
        void shouldHandleMultipleStartCalls() throws IOException {
            engine.start();
            engine.start(); // Should be idempotent
            assertThat(engine.isClosed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Put Operations")
    class PutOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should put key-value pair")
        void shouldPutKeyValue() {
            byte[] key = "test-key".getBytes();
            byte[] value = "test-value".getBytes();

            assertThatCode(() -> engine.put(key, value)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should overwrite existing key")
        void shouldOverwriteExistingKey() {
            byte[] key = "overwrite-key".getBytes();
            byte[] value1 = "value1".getBytes();
            byte[] value2 = "value2".getBytes();

            engine.put(key, value1);
            engine.put(key, value2);

            MemorySegment result = engine.get(key);
            assertThat(result).isNotNull();
            assertThat(bytesOf(result))
                .as("overwrite must replace the stored value, not keep the first one")
                .isEqualTo(value2);
        }

        @Test
        @DisplayName("Should report which segment a write landed in")
        void shouldReportTheTargetSegment() {
            // The engine routes by key hash, so a caller that wants to record something about a
            // written key per segment - a membership filter, a per-segment index - cannot work
            // out where it went. Without this the ingestion path has no segment id to file a
            // term under and the pre-filter it would populate stays empty.
            byte[] key = "routed-key".getBytes();

            long segmentId = engine.putReportingSegment(key, "value".getBytes());

            Segment landed = engine.getSegments().get(segmentId);
            assertThat(landed)
                .as("the reported id must be a directory index this engine holds")
                .isNotNull();
            assertThat(landed.get(key))
                .as("and the segment at that index must hold the key")
                .isNotNull();
            assertThat(engine.getCatalog().get(segmentId))
                .as("the id is the catalog index everything keyed per segment uses")
                .isNotNull();
            assertThat(engine.putReportingSegment(key, "value2".getBytes()))
                .as("the same key routes to the same segment")
                .isEqualTo(segmentId);
        }

        @Test
        @DisplayName("Should handle empty value")
        void shouldHandleEmptyValue() {
            byte[] key = "empty-value-key".getBytes();
            byte[] value = new byte[0];

            assertThatCode(() -> engine.put(key, value)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle large value")
        void shouldHandleLargeValue() {
            byte[] key = "large-value-key".getBytes();
            byte[] value = new byte[10000];
            for (int i = 0; i < value.length; i++) {
                value[i] = (byte) (i % 256);
            }

            assertThatCode(() -> engine.put(key, value)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Get Operations")
    class GetOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get existing key")
        void shouldGetExistingKey() {
            byte[] key = "get-key".getBytes();
            byte[] value = "get-value".getBytes();

            engine.put(key, value);
            MemorySegment result = engine.get(key);

            assertThat(result).isNotNull();
            assertThat(bytesOf(result)).isEqualTo(value);
        }

        @Test
        @DisplayName("Should return empty segment for missing key")
        void shouldReturnEmptySegmentForMissingKey() {
            byte[] key = "missing-key".getBytes();

            MemorySegment result = engine.get(key);

            // A key that was never written must not resolve to any value.
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should get with correct data after multiple puts")
        void shouldGetCorrectDataAfterMultiplePuts() {
            for (int i = 0; i < 100; i++) {
                engine.put(("key" + i).getBytes(), ("value" + i).getBytes());
            }

            for (int i = 0; i < 100; i++) {
                MemorySegment result = engine.get(("key" + i).getBytes());
                assertThat(result).as("key%d must be readable", i).isNotNull();
                assertThat(bytesOf(result))
                    .as("key%d must resolve to its own value", i)
                    .isEqualTo(("value" + i).getBytes());
            }
        }
    }

    @Nested
    @DisplayName("Delete Operations")
    class DeleteOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should delete existing key")
        void shouldDeleteExistingKey() {
            byte[] key = "delete-key".getBytes();
            byte[] value = "delete-value".getBytes();

            engine.put(key, value);
            boolean deleted = engine.delete(key);

            assertThat(deleted).isTrue();
            assertThat(engine.get(key)).isNull();
        }

        @Test
        @DisplayName("Should return false for deleting missing key")
        void shouldReturnFalseForDeletingMissingKey() {
            byte[] key = "missing-delete-key".getBytes();

            boolean deleted = engine.delete(key);

            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("Should handle delete after overwrite")
        void shouldHandleDeleteAfterOverwrite() {
            byte[] key = "overwrite-delete-key".getBytes();

            engine.put(key, "value1".getBytes());
            engine.put(key, "value2".getBytes());
            boolean deleted = engine.delete(key);

            assertThat(deleted).isTrue();
            assertThat(engine.get(key)).isNull();
        }
    }

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get statistics")
        void shouldGetStatistics() {
            StorageEngine.Statistics stats = engine.getStatistics();

            assertThat(stats).isNotNull();
        }

        @Test
        @DisplayName("Should track write operations")
        void shouldTrackWriteOperations() {
            long initialWrites = engine.getStatistics().getWriteOperations();

            engine.put("key1".getBytes(), "value1".getBytes());
            engine.put("key2".getBytes(), "value2".getBytes());

            assertThat(engine.getStatistics().getWriteOperations()).isEqualTo(initialWrites + 2);
        }

        @Test
        @DisplayName("Should track delete operations")
        void shouldTrackDeleteOperations() {
            engine.put("key1".getBytes(), "value1".getBytes());
            long initialDeletes = engine.getStatistics().getDeleteOperations();

            engine.delete("key1".getBytes());

            assertThat(engine.getStatistics().getDeleteOperations()).isEqualTo(initialDeletes + 1);
        }

        @Test
        @DisplayName("Should track read operations")
        void shouldTrackReadOperations() {
            engine.put("key1".getBytes(), "value1".getBytes());
            long initialReads = engine.getStatistics().getReadOperations();

            engine.get("key1".getBytes());

            assertThat(engine.getStatistics().getReadOperations()).isEqualTo(initialReads + 1);
        }

        @Test
        @DisplayName("Statistics toString should contain data")
        void statisticsToStringShouldContainData() {
            engine.put("key1".getBytes(), "value1".getBytes());
            engine.get("key1".getBytes());

            String str = engine.getStatistics().toString();

            assertThat(str).contains("writes");
            assertThat(str).contains("reads");
        }
    }

    @Nested
    @DisplayName("Query Snapshot")
    class QuerySnapshotTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should capture query snapshot")
        void shouldCaptureQuerySnapshot() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertThat(snapshot).isNotNull();
        }

        @Test
        @DisplayName("Should get segments from snapshot")
        void shouldGetSegmentsFromSnapshot() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertThat(snapshot.segments()).isNotNull();
        }

        @Test
        @DisplayName("Should get epoch from snapshot")
        void shouldGetEpochFromSnapshot() {
            StorageEngine.QuerySnapshot snapshot = engine.captureSnapshot();

            assertThat(snapshot.epoch()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Segment Operations")
    class SegmentOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get segment count")
        void shouldGetSegmentCount() {
            int count = engine.getSegmentCount();

            assertThat(count).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should get segments")
        void shouldGetSegments() {
            var segments = engine.getSegments();

            assertThat(segments).isNotNull();
        }

        @Test
        @DisplayName("Should get total entry count")
        void shouldGetTotalEntryCount() {
            long count = engine.getTotalEntryCount();

            assertThat(count).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Epoch Operations")
    class EpochOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get current epoch")
        void shouldGetCurrentEpoch() {
            long epoch = engine.currentEpoch();

            assertThat(epoch).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should increment epoch")
        void shouldIncrementEpoch() {
            long initialEpoch = engine.currentEpoch();

            engine.incrementEpoch();

            assertThat(engine.currentEpoch()).isGreaterThan(initialEpoch);
        }
    }

    @Nested
    @DisplayName("Queryable Segments")
    class QueryableSegmentsTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get queryable segments")
        void shouldGetQueryableSegments() {
            List<Segment> segments = engine.getQueryableSegments();

            assertThat(segments).isNotNull();
        }

        @Test
        @DisplayName("Should get queryable segments at epoch")
        void shouldGetQueryableSegmentsAtEpoch() {
            long epoch = engine.currentEpoch();
            List<Segment> segments = engine.getQueryableSegmentsAtEpoch(epoch);

            assertThat(segments).isNotNull();
        }
    }

    @Nested
    @DisplayName("Flush Operations")
    class FlushOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should flush without error")
        void shouldFlushWithoutError() {
            engine.put("key1".getBytes(), "value1".getBytes());

            assertThatCode(() -> engine.flush()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("GetWithEpoch Operations")
    class GetWithEpochOperationTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get with epoch")
        void shouldGetWithEpoch() {
            byte[] key = "epoch-key".getBytes();
            byte[] value = "epoch-value".getBytes();

            engine.put(key, value);
            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertThat(slice).isNotNull();
            assertThat(slice.isFound()).isTrue();
        }

        @Test
        @DisplayName("Should return not found for missing key with epoch")
        void shouldReturnNotFoundForMissingKeyWithEpoch() {
            byte[] key = "missing-epoch-key".getBytes();

            ZeroCopySlice slice = engine.getWithEpoch(key);

            assertThat(slice.isFound()).isFalse();
        }
    }

    @Nested
    @DisplayName("Visibility Tracker")
    class VisibilityTrackerTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get visibility tracker")
        void shouldGetVisibilityTracker() {
            VisibilityTracker tracker = engine.getVisibilityTracker();

            assertThat(tracker).isNotNull();
        }

        @Test
        @DisplayName("Should set visibility tracker")
        void shouldSetVisibilityTracker() {
            VisibilityTracker customTracker = new VisibilityTracker.Default();

            assertThatCode(() -> engine.setVisibilityTracker(customTracker)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Data Directory")
    class DataDirectoryTests {

        @Test
        @DisplayName("Should get data directory")
        void shouldGetDataDirectory() {
            Path dir = engine.dataDir();

            assertThat(dir).isNotNull();
            assertThat(dir).isEqualTo(dataDir);
        }
    }

    @Nested
    @DisplayName("Arena")
    class ArenaTests {

        @Test
        @DisplayName("Should get arena")
        void shouldGetArena() {
            Arena engineArena = engine.arena();

            assertThat(engineArena).isNotNull();
            assertThat(engineArena).isEqualTo(arena);
        }
    }

    @Nested
    @DisplayName("Catalog")
    class CatalogTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get catalog")
        void shouldGetCatalog() {
            SegmentCatalog catalog = engine.getCatalog();

            assertThat(catalog).isNotNull();
        }
    }

    @Nested
    @DisplayName("Lifecycle Manager")
    class LifecycleManagerTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get lifecycle manager")
        void shouldGetLifecycleManager() {
            SegmentLifecycleManager manager = engine.getLifecycleManager();

            assertThat(manager).isNotNull();
        }

        @Test
        @DisplayName("Should start lifecycle manager")
        void shouldStartLifecycleManager() {
            assertThatCode(() -> engine.startLifecycleManager()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should stop lifecycle manager")
        void shouldStopLifecycleManager() {
            engine.startLifecycleManager();

            assertThat(engine.stopBackgroundWork(Duration.ofSeconds(10)))
                .as("an idle lifecycle manager and split worker end as soon as they are asked")
                .isTrue();
            assertThat(engine.stopBackgroundWork(Duration.ZERO))
                .as("asking again finds them already ended")
                .isTrue();
            assertThatThrownBy(() -> engine.stopBackgroundWork(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Split Worker")
    class SplitWorkerTests {

        @BeforeEach
        void startEngine() throws IOException {
            engine.start();
        }

        @Test
        @DisplayName("Should get split worker")
        void shouldGetSplitWorker() {
            SplitWorker worker = engine.getSplitWorker();

            assertThat(worker).isNotNull();
        }
    }
}
