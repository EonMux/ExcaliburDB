package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the backpressure mechanism in {@link IndexOrchestrator}.
 */
@DisplayName("IndexOrchestrator Backpressure Tests")
class BackpressureTest {

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("DEFAULT_HNSW_QUEUE_DEPTH_LIMIT should be 100000")
        void defaultQueueDepthLimitShouldBe100000() {
            assertEquals(100_000, IndexOrchestrator.DEFAULT_HNSW_QUEUE_DEPTH_LIMIT);
        }

        @Test
        @DisplayName("DEFAULT_EPOCH_TICK_INTERVAL_MS should be 100")
        void defaultEpochTickIntervalShouldBe100() {
            assertEquals(100L, IndexOrchestrator.DEFAULT_EPOCH_TICK_INTERVAL_MS);
        }
    }

    @Nested
    @DisplayName("awaitBackpressureRelease")
    class AwaitRelease {

        @TempDir
        Path dataDir;

        private Arena arena;
        private StorageEngine storageEngine;
        private SegmentCatalog catalog;
        private IndexOrchestrator orchestrator;

        @BeforeEach
        void setUp() throws IOException {
            arena = Arena.ofShared();
            storageEngine = new StorageEngine(dataDir, arena);
            catalog = new SegmentCatalog();
            orchestrator = new IndexOrchestrator(catalog, storageEngine);
        }

        @AfterEach
        void tearDown() {
            orchestrator.close();
            storageEngine.close();
            catalog.close();
            arena.close();
        }

        @Test
        @DisplayName("returns true at once when no backpressure is applied")
        void returnsImmediatelyWhenNotBackpressured() {
            assertFalse(orchestrator.isBackpressured());
            assertEquals(0L, orchestrator.getHnswQueueDepth());

            assertTrue(orchestrator.awaitBackpressureRelease(0));
            assertTrue(orchestrator.awaitBackpressureRelease(1_000));
        }

        @Test
        @DisplayName("rejects a negative timeout")
        void rejectsNegativeTimeout() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> orchestrator.awaitBackpressureRelease(-1));
            assertTrue(e.getMessage().contains("-1"));
        }
    }
}
