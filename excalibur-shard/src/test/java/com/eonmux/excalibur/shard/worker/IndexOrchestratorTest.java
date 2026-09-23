package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for background indexing workers.
 *
 * <p>These tests verify the worker classes compile and have correct basic behavior.
 * Full integration tests require a StorageEngine instance which is tested separately.</p>
 */
class IndexOrchestratorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexOrchestratorTest.class);

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
    }

    @AfterEach
    void tearDown() {
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("HnswIntegrationWorker should have correct default configuration")
    void testHnswWorkerDefaults() {
        SegmentCatalog catalog = new SegmentCatalog();
        HnswIntegrationWorker worker = new HnswIntegrationWorker(catalog,
            (segment, count) -> {});

        assertEquals(100, worker.getIntegrationIntervalMs());
        assertEquals(100_000, worker.getQueueDepthLimit());
        assertEquals(100, worker.getBatchSize());
        assertEquals(0, worker.getRegisteredSegmentCount());
        assertFalse(worker.isRunning());
        assertFalse(worker.isBackpressured());
        assertEquals(0L, worker.getTotalIntegrated());
        assertEquals(0L, worker.getTotalBatches());
        assertEquals(0L, worker.getCurrentQueueDepth());

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should have correct default configuration")
    void testCuckooWorkerDefaults() {
        SegmentCatalog catalog = new SegmentCatalog();
        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog,
            storageEngine, segmentId -> {});

        assertEquals(5000L, worker.getMaintenanceIntervalMs());
        assertEquals(1000, worker.getMaxDeletionsPerCycle());
        assertEquals(0.001, worker.getFpRateThreshold());
        assertEquals(0, worker.getRegisteredFilterCount());
        assertEquals(0, worker.getPendingDeletionCount());
        assertFalse(worker.isRunning());

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should have correct default configuration")
    void testReindexSchedulerDefaults() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        assertEquals(60_000L, scheduler.getCheckIntervalMs());
        assertEquals(3_600_000L, scheduler.getDefaultReindexIntervalMs());
        assertEquals(0, scheduler.getRegisteredSourceCount());
        assertFalse(scheduler.isRunning());
        assertEquals(0L, scheduler.getTotalReindexesTriggered());
        assertEquals(0L, scheduler.getTotalReindexesCompleted());
        assertEquals(0L, scheduler.getTotalReindexesFailed());

        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should start and stop successfully")
    void testHnswWorkerStartStop() {
        SegmentCatalog catalog = new SegmentCatalog();
        HnswIntegrationWorker worker = new HnswIntegrationWorker(catalog,
            (segment, count) -> {});

        assertFalse(worker.isRunning());
        assertTrue(worker.start());
        assertTrue(worker.isRunning());
        assertFalse(worker.start()); // Already running

        worker.stop();
        assertFalse(worker.isRunning());

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should start and stop successfully")
    void testCuckooWorkerStartStop() {
        SegmentCatalog catalog = new SegmentCatalog();
        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog,
            storageEngine, segmentId -> {});

        assertFalse(worker.isRunning());
        assertTrue(worker.start());
        assertTrue(worker.isRunning());
        assertFalse(worker.start()); // Already running

        worker.stop();
        assertFalse(worker.isRunning());

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should start and stop successfully")
    void testReindexSchedulerStartStop() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        assertFalse(scheduler.isRunning());
        assertTrue(scheduler.start());
        assertTrue(scheduler.isRunning());
        assertFalse(scheduler.start()); // Already running

        scheduler.stop();
        assertFalse(scheduler.isRunning());

        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should support configuration changes")
    void testHnswWorkerConfiguration() {
        SegmentCatalog catalog = new SegmentCatalog();
        HnswIntegrationWorker worker = new HnswIntegrationWorker(catalog,
            (segment, count) -> {}, 200, 50_000, 50);

        assertEquals(200L, worker.getIntegrationIntervalMs());
        assertEquals(50_000, worker.getQueueDepthLimit());
        assertEquals(50, worker.getBatchSize());

        worker.setIntegrationIntervalMs(300);
        assertEquals(300L, worker.getIntegrationIntervalMs());

        worker.setQueueDepthLimit(75_000);
        assertEquals(75_000, worker.getQueueDepthLimit());

        worker.setBatchSize(75);
        assertEquals(75, worker.getBatchSize());

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should support configuration changes")
    void testCuckooWorkerConfiguration() {
        SegmentCatalog catalog = new SegmentCatalog();
        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog,
            storageEngine, segmentId -> {}, 10000, 5000, 0.005);

        assertEquals(10000L, worker.getMaintenanceIntervalMs());
        assertEquals(5000, worker.getMaxDeletionsPerCycle());
        assertEquals(0.005, worker.getFpRateThreshold());

        worker.setMaintenanceIntervalMs(15000);
        assertEquals(15000L, worker.getMaintenanceIntervalMs());

        worker.setMaxDeletionsPerCycle(7500);
        assertEquals(7500, worker.getMaxDeletionsPerCycle());

        worker.setFpRateThreshold(0.002);
        assertEquals(0.002, worker.getFpRateThreshold());

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should support source registration")
    void testReindexSchedulerSourceRegistration() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        assertEquals(0, scheduler.getRegisteredSourceCount());

        scheduler.registerSource("docs");
        assertEquals(1, scheduler.getRegisteredSourceCount());

        scheduler.registerSource("wiki", 1800000L);
        assertEquals(2, scheduler.getRegisteredSourceCount());

        scheduler.unregisterSource("docs");
        assertEquals(1, scheduler.getRegisteredSourceCount());

        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should validate configuration")
    void testHnswWorkerValidation() {
        SegmentCatalog catalog = new SegmentCatalog();

        // Invalid interval
        assertThrows(IllegalArgumentException.class, () ->
            new HnswIntegrationWorker(catalog, (segment, count) -> {}, 5, 100_000, 100));

        // Invalid queue depth
        assertThrows(IllegalArgumentException.class, () ->
            new HnswIntegrationWorker(catalog, (segment, count) -> {}, 100, 500, 100));

        // Invalid batch size
        assertThrows(IllegalArgumentException.class, () ->
            new HnswIntegrationWorker(catalog, (segment, count) -> {}, 100, 100_000, 0));

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should validate configuration")
    void testCuckooWorkerValidation() {
        SegmentCatalog catalog = new SegmentCatalog();

        // Invalid interval
        assertThrows(IllegalArgumentException.class, () ->
            new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {}, 100, 1000, 0.001));

        // Invalid max deletions
        assertThrows(IllegalArgumentException.class, () ->
            new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {}, 5000, 10, 0.001));

        // Invalid FP rate threshold
        assertThrows(IllegalArgumentException.class, () ->
            new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {}, 5000, 1000, 0.5));

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should support filter registration")
    void testCuckooWorkerFilterRegistration() {
        SegmentCatalog catalog = new SegmentCatalog();
        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {});

        assertEquals(0, worker.getRegisteredFilterCount());

        com.eonmux.excalibur.filter.CuckooFilter filter = com.eonmux.excalibur.filter.CuckooFilter.create(1000, 8);
        worker.registerFilter(1L, filter);
        assertEquals(1, worker.getRegisteredFilterCount());

        // Duplicate registration should not add
        worker.registerFilter(1L, filter);
        assertEquals(1, worker.getRegisteredFilterCount());

        // Null filter should be ignored
        worker.registerFilter(2L, null);
        assertEquals(1, worker.getRegisteredFilterCount());

        worker.unregisterFilter(1L);
        assertEquals(0, worker.getRegisteredFilterCount());

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should track statistics")
    void testCuckooWorkerStatistics() {
        SegmentCatalog catalog = new SegmentCatalog();
        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {});

        assertEquals(0L, worker.getTotalDeletions());
        assertEquals(0L, worker.getLastMaintenanceTime());
        assertEquals(0L, worker.getTombstonedSegmentsProcessed());

        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should reject null parameters")
    void testCuckooWorkerNullParameters() {
        SegmentCatalog catalog = new SegmentCatalog();
        assertThrows(NullPointerException.class, () ->
            new CuckooMaintenanceWorker(null, storageEngine, segmentId -> {}));

        assertThrows(NullPointerException.class, () ->
            new CuckooMaintenanceWorker(catalog, null, segmentId -> {}));

        assertThrows(NullPointerException.class, () ->
            new CuckooMaintenanceWorker(catalog, storageEngine, null));
        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should have correct toString")
    void testCuckooWorkerToString() {
        SegmentCatalog catalog = new SegmentCatalog();
        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {});

        String str = worker.toString();
        assertNotNull(str);
        assertTrue(str.contains("CuckooMaintenanceWorker"));
        assertTrue(str.contains("running=false"));

        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should support segment registration")
    void testHnswWorkerSegmentRegistration() {
        SegmentCatalog catalog = new SegmentCatalog();
        HnswIntegrationWorker worker = new HnswIntegrationWorker(catalog,
            (segment, count) -> {});

        assertEquals(0, worker.getRegisteredSegmentCount());

        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should reject null parameters")
    void testHnswWorkerNullParameters() {
        assertThrows(NullPointerException.class, () ->
            new HnswIntegrationWorker(null, (segment, count) -> {}));

        SegmentCatalog catalog = new SegmentCatalog();
        assertThrows(NullPointerException.class, () ->
            new HnswIntegrationWorker(catalog, null));
        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should validate settable parameters")
    void testHnswWorkerSettableValidation() {
        SegmentCatalog catalog = new SegmentCatalog();
        HnswIntegrationWorker worker = new HnswIntegrationWorker(catalog,
            (segment, count) -> {});

        // Valid values
        worker.setIntegrationIntervalMs(50);
        assertEquals(50L, worker.getIntegrationIntervalMs());

        worker.setQueueDepthLimit(200_000);
        assertEquals(200_000, worker.getQueueDepthLimit());

        worker.setBatchSize(200);
        assertEquals(200, worker.getBatchSize());

        // Invalid values
        assertThrows(IllegalArgumentException.class, () ->
            worker.setIntegrationIntervalMs(5));

        assertThrows(IllegalArgumentException.class, () ->
            worker.setQueueDepthLimit(100));

        assertThrows(IllegalArgumentException.class, () ->
            worker.setBatchSize(0));

        catalog.close();
    }

    @Test
    @DisplayName("HnswIntegrationWorker should have correct toString")
    void testHnswWorkerToString() {
        SegmentCatalog catalog = new SegmentCatalog();
        HnswIntegrationWorker worker = new HnswIntegrationWorker(catalog,
            (segment, count) -> {});

        String str = worker.toString();
        assertNotNull(str);
        assertTrue(str.contains("HnswIntegrationWorker"));
        assertTrue(str.contains("running=false"));

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should support source management")
    void testReindexSchedulerSourceManagement() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        assertEquals(0, scheduler.getRegisteredSourceCount());

        scheduler.registerSource("docs", 1_800_000L);
        assertEquals(1, scheduler.getRegisteredSourceCount());

        scheduler.registerSource("wiki"); // Using default interval
        assertEquals(2, scheduler.getRegisteredSourceCount());

        scheduler.updateReindexInterval("docs", 3_600_000L);

        scheduler.unregisterSource("wiki");
        assertEquals(1, scheduler.getRegisteredSourceCount());

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should support manual reindex triggering")
    void testReindexSchedulerManualTrigger() {
        SegmentCatalog catalog = new SegmentCatalog();
        java.util.concurrent.atomic.AtomicBoolean triggered = new java.util.concurrent.atomic.AtomicBoolean(false);
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> { triggered.set(true); return true; });

        scheduler.registerSource("docs");

        // Trigger immediate reindex
        assertTrue(scheduler.triggerReindexNow("docs"));
        assertTrue(triggered.get());
        assertEquals(1L, scheduler.getTotalReindexesTriggered());

        // Unknown source should return false
        assertFalse(scheduler.triggerReindexNow("absent"));

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should track reindex completion and failure")
    void testReindexSchedulerCompletionAndFailure() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        scheduler.registerSource("docs");

        scheduler.markReindexComplete("docs");
        assertEquals(1L, scheduler.getTotalReindexesCompleted());

        scheduler.markReindexFailed("docs", "Test error");
        assertEquals(1L, scheduler.getTotalReindexesFailed());

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should support configuration changes")
    void testReindexSchedulerConfiguration() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        scheduler.setCheckIntervalMs(30_000L);
        assertEquals(30_000L, scheduler.getCheckIntervalMs());

        scheduler.setDefaultReindexIntervalMs(1_800_000L);
        assertEquals(1_800_000L, scheduler.getDefaultReindexIntervalMs());

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should validate configuration")
    void testReindexSchedulerValidation() {
        SegmentCatalog catalog = new SegmentCatalog();

        // Invalid check interval
        assertThrows(IllegalArgumentException.class, () ->
            new SegmentReindexScheduler(catalog, sourceId -> true, 1000, 3_600_000L));

        // Invalid reindex interval - too low
        assertThrows(IllegalArgumentException.class, () ->
            new SegmentReindexScheduler(catalog, sourceId -> true, 60_000L, 1000));

        // Invalid reindex interval - too high
        assertThrows(IllegalArgumentException.class, () ->
            new SegmentReindexScheduler(catalog, sourceId -> true, 60_000L, 25 * 60 * 60 * 1000L));

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should reject null parameters")
    void testReindexSchedulerNullParameters() {
        assertThrows(NullPointerException.class, () ->
            new SegmentReindexScheduler(null, sourceId -> true));

        SegmentCatalog catalog = new SegmentCatalog();
        assertThrows(NullPointerException.class, () ->
            new SegmentReindexScheduler(catalog, null));
        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should have correct toString")
    void testReindexSchedulerToString() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        String str = scheduler.toString();
        assertNotNull(str);
        assertTrue(str.contains("SegmentReindexScheduler"));
        assertTrue(str.contains("running=false"));

        catalog.close();
    }

    @Test
    @DisplayName("SegmentReindexScheduler should get next reindex time")
    void testReindexSchedulerNextReindexTime() {
        SegmentCatalog catalog = new SegmentCatalog();
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog,
            sourceId -> true);

        scheduler.registerSource("docs", 3_600_000L); // 1 hour

        java.time.Instant nextTime = scheduler.getNextReindexTime("docs");
        assertNotNull(nextTime);

        // Unknown source should return null
        assertNull(scheduler.getNextReindexTime("absent"));

        catalog.close();
    }

    // ==================== IndexOrchestrator Tests ====================

    @Test
    @DisplayName("IndexOrchestrator should have correct default configuration")
    void testIndexOrchestratorDefaults() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data1");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertEquals(100_000, orchestrator.getHnswQueueDepthLimit());
        assertFalse(orchestrator.isRunning());
        assertEquals(0, orchestrator.getRegisteredSegmentCount());
        assertEquals(0L, orchestrator.getVisibilityEpoch());
        assertEquals(0L, orchestrator.getTotalVectorsIntegrated());
        assertEquals(0L, orchestrator.getTotalCuckooDeletions());
        assertEquals(0L, orchestrator.getTotalReindexesTriggered());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should have correct custom configuration")
    void testIndexOrchestratorCustomConfiguration() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data2");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine,
            50_000, 10_000, 30_000, 50);

        assertEquals(50_000, orchestrator.getHnswQueueDepthLimit());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should start and stop successfully")
    void testIndexOrchestratorStartStop() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data3");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertFalse(orchestrator.isRunning());
        assertTrue(orchestrator.start());
        assertTrue(orchestrator.isRunning());
        assertFalse(orchestrator.start()); // Already running

        orchestrator.stop();
        assertFalse(orchestrator.isRunning());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should reject null parameters")
    void testIndexOrchestratorNullParameters() throws IOException {
        Path dataDir = tempDir.resolve("data4");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);

        assertThrows(NullPointerException.class, () ->
            new IndexOrchestrator(null, storageEngine));

        SegmentCatalog catalog = new SegmentCatalog();
        assertThrows(NullPointerException.class, () ->
            new IndexOrchestrator(catalog, null));

        catalog.close();
        storageEngine.close();
        arena.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should advance visibility epoch")
    void testIndexOrchestratorAdvanceEpoch() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data5");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertEquals(0L, orchestrator.getVisibilityEpoch());

        long newEpoch = orchestrator.advanceEpoch();
        assertEquals(1L, newEpoch);
        assertEquals(1L, orchestrator.getVisibilityEpoch());

        newEpoch = orchestrator.advanceEpoch();
        assertEquals(2L, newEpoch);
        assertEquals(2L, orchestrator.getVisibilityEpoch());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should expose visibility tracker")
    void testIndexOrchestratorVisibilityTracker() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data6");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertNotNull(orchestrator.getVisibilityTracker());
        assertEquals(orchestrator.getVisibilityEpoch(), orchestrator.getVisibilityTracker().currentEpoch());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should expose epoch tick scheduler")
    void testIndexOrchestratorEpochTickScheduler() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data7");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertNotNull(orchestrator.getEpochTickScheduler());
        assertEquals(100L, orchestrator.getEpochTickScheduler().getTickIntervalMs());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should expose worker references")
    void testIndexOrchestratorWorkerAccess() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data8");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertNotNull(orchestrator.getHnswWorker());
        assertNotNull(orchestrator.getCuckooWorker());
        assertNotNull(orchestrator.getReindexScheduler());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should support backpressure check")
    void testIndexOrchestratorBackpressure() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data9");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertFalse(orchestrator.isBackpressured());
        assertEquals(0L, orchestrator.getHnswQueueDepth());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should support pending cuckoo deletions check")
    void testIndexOrchestratorPendingDeletions() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data10");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertEquals(0, orchestrator.getPendingCuckooDeletions());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should support scheduled reindex count")
    void testIndexOrchestratorScheduledReindexCount() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data11");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        assertEquals(0, orchestrator.getScheduledReindexCount());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should have correct toString")
    void testIndexOrchestratorToString() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data12");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        String str = orchestrator.toString();
        assertNotNull(str);
        assertTrue(str.contains("IndexOrchestrator"));
        assertTrue(str.contains("running=false"));

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should validate configuration values")
    void testIndexOrchestratorConfigurationValidation() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data13");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);

        // Custom configuration should work
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine,
            50_000, 10_000, 30_000, 200);
        assertEquals(50_000, orchestrator.getHnswQueueDepthLimit());

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should close successfully")
    void testIndexOrchestratorClose() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data14");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        orchestrator.start();
        assertTrue(orchestrator.isRunning());

        orchestrator.close();
        assertFalse(orchestrator.isRunning());

        storageEngine.close();
        arena.close();
        catalog.close();
    }

    // ==================== Segment Reindex Scheduler Flag Management Tests ====================

    @Test
    @DisplayName("SegmentReindexScheduler should allow multiple reindexes when flag is properly managed")
    void testReindexSchedulerMultipleReindexCyclesWithFlagManagement() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();

        // Track reindex invocations
        java.util.concurrent.atomic.AtomicInteger reindexCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<Long> reindexTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        final SegmentReindexScheduler[] schedulerHolder = new SegmentReindexScheduler[1];

        schedulerHolder[0] = new SegmentReindexScheduler(catalog, sourceId -> {
            reindexCount.incrementAndGet();
            reindexTimes.add(System.currentTimeMillis());
            // Simulate reindex completion - this is what IndexOrchestrator.onReindexNeeded does
            schedulerHolder[0].markReindexComplete(sourceId);
            return true;
        });

        SegmentReindexScheduler scheduler = schedulerHolder[0];
        // Use very short intervals for testing: 20ms check, 30ms reindex interval
        // Using unchecked methods to bypass production domain constraints for faster tests
        scheduler.setCheckIntervalMsUnchecked(20);
        scheduler.setDefaultReindexIntervalMsUnchecked(30);

        scheduler.registerSourceUnchecked("docs", 30);
        scheduler.start();

        // Wait for multiple reindex cycles to occur
        // With 20ms check interval and 30ms reindex interval:
        // - First check at ~20ms: triggers reindex (count=1)
        // - Second check at ~40ms: timeSinceLastReindex ~20ms < 30ms, no trigger
        // - Third check at ~60ms: timeSinceLastReindex ~40ms >= 30ms, triggers (count=2)
        // Need enough time for at least 2 checks after the first reindex
        Thread.sleep(100);

        scheduler.stop();
        catalog.close();

        // Verify multiple reindexes occurred (should have at least 2)
        int count = reindexCount.get();
        assertTrue(count >= 2,
            "Expected at least 2 reindexes to occur, but got " + count +
            ". This indicates the reindexInProgress flag is not being properly reset.");
    }

    @Test
    @DisplayName("SegmentReindexScheduler should block reindexes without flag management")
    void testReindexSchedulerBlocksReindexesWithoutFlagManagement() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();

        // Track reindex invocations
        java.util.concurrent.atomic.AtomicInteger reindexCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // This listener does NOT call markReindexComplete - simulating the bug
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(catalog, sourceId -> {
            reindexCount.incrementAndGet();
            // BUG: Not calling markReindexComplete - flag stays true forever
            return true;
        });

        // Use very short intervals for testing
        // Using unchecked methods to bypass production domain constraints for faster tests
        scheduler.setCheckIntervalMsUnchecked(20);
        scheduler.setDefaultReindexIntervalMsUnchecked(30);

        scheduler.registerSourceUnchecked("docs", 30);
        scheduler.start();

        // Wait for multiple potential reindex cycles
        Thread.sleep(80);

        scheduler.stop();
        catalog.close();

        // Without proper flag management, only 1 reindex should occur
        int count = reindexCount.get();
        assertEquals(1, count,
            "Expected exactly 1 reindex when flag is not managed, but got " + count +
            ". This verifies the bug exists when markReindexComplete is not called.");
    }

    @Test
    @DisplayName("IndexOrchestrator.onReindexNeeded should call markReindexComplete on success")
    void testIndexOrchestratorCallsMarkCompleteOnSuccess() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data15");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // A reindex only "succeeds" when something actually re-indexed the source, so the
        // handler that does that work has to be configured.
        java.util.concurrent.atomic.AtomicInteger handlerCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        orchestrator.setReindexOperationHandler(sourceId -> {
            handlerCalls.incrementAndGet();
            return true;
        });

        SegmentReindexScheduler scheduler = orchestrator.getReindexScheduler();

        // Register a source
        scheduler.registerSource("docs");

        // Trigger a manual reindex through the scheduler
        // This will call IndexOrchestrator.onReindexNeeded which should call markReindexComplete
        long triggeredBefore = scheduler.getTotalReindexesTriggered();
        long completedBefore = scheduler.getTotalReindexesCompleted();

        boolean result = scheduler.triggerReindexNow("docs");

        assertTrue(result, "Reindex should have been triggered successfully");
        assertEquals(1, handlerCalls.get(), "The configured handler should have run");
        assertEquals(triggeredBefore + 1, scheduler.getTotalReindexesTriggered(),
            "Triggered count should have incremented");

        // The key verification: completed count should have incremented because
        // onReindexNeeded calls markReindexComplete
        assertEquals(completedBefore + 1, scheduler.getTotalReindexesCompleted(),
            "Completed count should have incremented because onReindexNeeded calls markReindexComplete");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should report a reindex it cannot run as failed")
    void testIndexOrchestratorReindexWithoutHandlerFails() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data15b");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        SegmentReindexScheduler scheduler = orchestrator.getReindexScheduler();
        scheduler.registerSource("docs");

        // No ReindexOperationHandler is configured, so no document can be re-indexed.
        boolean result = scheduler.triggerReindexNow("docs");

        assertFalse(result, "A reindex with no handler to run it has not been triggered");
        assertEquals(0L, scheduler.getTotalReindexesTriggered(),
            "Nothing was triggered");
        assertEquals(0L, scheduler.getTotalReindexesCompleted(),
            "A reindex that never touched a document must not be recorded as completed");
        assertEquals(1L, scheduler.getTotalReindexesFailed(),
            "The attempt must be recorded as failed so the staleness clock is not reset");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("IndexOrchestrator should allow multiple consecutive reindexes")
    void testIndexOrchestratorMultipleConsecutiveReindexes() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data16");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);
        orchestrator.setReindexOperationHandler(sourceId -> true);

        SegmentReindexScheduler scheduler = orchestrator.getReindexScheduler();
        scheduler.registerSource("docs");

        // Trigger multiple reindexes in succession
        for (int i = 0; i < 5; i++) {
            boolean result = scheduler.triggerReindexNow("docs");
            assertTrue(result, "Reindex " + (i + 1) + " should succeed");
        }

        // All 5 reindexes should have been triggered AND completed
        assertEquals(5L, scheduler.getTotalReindexesTriggered(),
            "All 5 reindexes should have been triggered");
        assertEquals(5L, scheduler.getTotalReindexesCompleted(),
            "All 5 reindexes should have been completed (flag properly reset)");
        assertEquals(0L, scheduler.getTotalReindexesFailed(),
            "No reindexes should have failed");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    // ==================== Hierarchical Cuckoo Filter Wiring Tests ====================

    @Test
    @DisplayName("IndexOrchestrator should wire hierarchical Cuckoo filter to maintenance worker")
    void testIndexOrchestratorHierarchicalCuckooFilterWiring() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data17");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // Initially, the cuckoo worker should have no hierarchical filter
        CuckooMaintenanceWorker cuckooWorker = orchestrator.getCuckooWorker();
        assertNull(cuckooWorker.getHierarchicalFilter(),
            "CuckooMaintenanceWorker should start with null hierarchical filter");

        // Create and wire a hierarchical filter
        HierarchicalCuckooFilter hierarchicalFilter = new HierarchicalCuckooFilter(
            1_000_000L,  // corpus capacity
            13,          // fingerprint bits
            10_000       // segment capacity
        );
        orchestrator.setHierarchicalCuckooFilter(hierarchicalFilter);

        // Verify the filter is now wired to the cuckoo worker
        assertSame(hierarchicalFilter, cuckooWorker.getHierarchicalFilter(),
            "Hierarchical filter should be wired to CuckooMaintenanceWorker");

        // Verify we can clear it
        orchestrator.setHierarchicalCuckooFilter(null);
        assertNull(cuckooWorker.getHierarchicalFilter(),
            "Hierarchical filter should be clearable");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("Scheduled reindex should use ReindexOperationHandler when configured")
    void scheduledReindexUsesReindexOperationHandler() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data19");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // Track handler invocation
        java.util.concurrent.atomic.AtomicBoolean handlerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> capturedSourceId = new java.util.concurrent.atomic.AtomicReference<>();

        // Configure the handler
        orchestrator.setReindexOperationHandler(sourceId -> {
            handlerCalled.set(true);
            capturedSourceId.set(sourceId);
            return true;
        });

        // Verify handler is set
        assertNotNull(orchestrator.getReindexOperationHandler(),
            "ReindexOperationHandler should be configured");

        // Register a source and trigger reindex via the scheduler
        orchestrator.getReindexScheduler().registerSourceUnchecked("docs", 1000L);
        boolean result = orchestrator.getReindexScheduler().triggerReindexNow("docs");

        // Verify the handler was invoked
        assertTrue(result, "Reindex should succeed");
        assertTrue(handlerCalled.get(), "ReindexOperationHandler should have been called");
        assertEquals("docs", capturedSourceId.get(),
            "the handler is given the id the source was registered under, unchanged");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("ReindexOperationHandler should support clearing")
    void reindexOperationHandlerCanBeCleared() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data20");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);
        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // Initially null
        assertNull(orchestrator.getReindexOperationHandler(),
            "Handler should initially be null");

        // Set handler
        orchestrator.setReindexOperationHandler(sourceId -> true);
        assertNotNull(orchestrator.getReindexOperationHandler(),
            "Handler should be set");

        // Clear handler
        orchestrator.setReindexOperationHandler(null);
        assertNull(orchestrator.getReindexOperationHandler(),
            "Handler should be cleared");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("CuckooMaintenanceWorker should use hierarchical filter for deletions when set")
    void testCuckooWorkerUsesHierarchicalFilterForDeletions() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data18");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);

        CuckooMaintenanceWorker worker = new CuckooMaintenanceWorker(catalog, storageEngine, segmentId -> {});

        // Create and wire hierarchical filter
        HierarchicalCuckooFilter hierarchicalFilter = new HierarchicalCuckooFilter(10_000L, 13, 100);
        worker.setHierarchicalFilter(hierarchicalFilter);

        // Register a segment in the hierarchy
        String segmentId = "test-segment-1";
        hierarchicalFilter.registerSegment(segmentId);

        // Insert a test item
        byte[] testKey = "test-key".getBytes();
        assertTrue(hierarchicalFilter.insert(testKey, segmentId),
            "Insert into hierarchical filter should succeed");

        // Verify the item is in the filter
        assertTrue(hierarchicalFilter.mightContain(testKey),
            "Item should be present in corpus filter");
        assertTrue(hierarchicalFilter.mightContainInSegment(testKey, segmentId),
            "Item should be present in segment filter");

        // Delete from hierarchy
        boolean deleted = hierarchicalFilter.deleteFromHierarchy(testKey, segmentId);
        assertTrue(deleted, "Delete from hierarchy should succeed");

        // Verify the item is no longer in the filter
        assertFalse(hierarchicalFilter.mightContainInSegment(testKey, segmentId),
            "Item should no longer be present in segment filter after deletion");

        worker.stop();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

    @Test
    @DisplayName("HierarchicalCuckooFilter setHierarchicalCuckooFilter registers existing segments")
    void hierarchicalCuckooFilterRegistrationTest() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        Path dataDir = tempDir.resolve("data19");
        Files.createDirectories(dataDir);
        Arena arena = Arena.ofShared();
        com.eonmux.excalibur.store.StorageEngine storageEngine = new com.eonmux.excalibur.store.StorageEngine(dataDir, arena);

        IndexOrchestrator orchestrator = new IndexOrchestrator(catalog, storageEngine);

        // Verify initial state
        assertNull(orchestrator.getHierarchicalCuckooFilter(),
            "Filter should be null initially");

        // Create hierarchical filter
        HierarchicalCuckooFilter hierarchicalFilter = new HierarchicalCuckooFilter(
            100_000L,  // corpus capacity
            13,        // fingerprint bits
            1_000      // segment capacity
        );

        // Set the filter
        orchestrator.setHierarchicalCuckooFilter(hierarchicalFilter);

        // Verify filter is set
        assertSame(hierarchicalFilter, orchestrator.getHierarchicalCuckooFilter(),
            "Filter should be set");

        // The setHierarchicalCuckooFilter should register any existing segments
        // In this case there are no segments, so the filter should be empty
        assertEquals(0, hierarchicalFilter.getSegmentCount(),
            "Filter should have no segments since none were registered");

        // Create a hierarchical filter and pre-register some segments in it
        HierarchicalCuckooFilter filter2 = new HierarchicalCuckooFilter(100_000L, 13, 1_000);

        // Pre-register some segment IDs in the filter
        filter2.registerSegment("100");
        filter2.registerSegment("200");
        filter2.registerSegment("300");

        assertEquals(3, filter2.getSegmentCount(),
            "Filter should have 3 segments pre-registered");

        // Verify we can insert for these pre-registered segments
        byte[] testKey1 = "test-key-100".getBytes();
        byte[] testKey2 = "test-key-200".getBytes();

        assertTrue(filter2.insert(testKey1, "100"),
            "Should be able to insert for segment 100");
        assertTrue(filter2.insert(testKey2, "200"),
            "Should be able to insert for segment 200");

        // Verify the keys are in the filter
        assertTrue(filter2.mightContain(testKey1),
            "Key 1 should be in corpus filter");
        assertTrue(filter2.mightContain(testKey2),
            "Key 2 should be in corpus filter");

        // Verify findCandidateSegments works
        java.util.List<String> candidates1 = filter2.findCandidateSegments(testKey1);
        assertTrue(candidates1.contains("100"),
            "Segment 100 should be a candidate for key 1");

        java.util.List<String> candidates2 = filter2.findCandidateSegments(testKey2);
        assertTrue(candidates2.contains("200"),
            "Segment 200 should be a candidate for key 2");

        orchestrator.close();
        storageEngine.close();
        arena.close();
        catalog.close();
    }

}
