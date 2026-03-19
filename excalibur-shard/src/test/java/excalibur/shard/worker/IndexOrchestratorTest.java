package excalibur.shard.worker;

import io.excalibur.store.SegmentCatalog;
import excalibur.shard.IndexOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for background indexing workers.
 *
 * <p>These tests verify the worker classes compile and have correct basic behavior.
 * Full integration tests require a StorageEngine instance which is tested separately.</p>
 */
class IndexOrchestratorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexOrchestratorTest.class);

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
            segmentId -> {});

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
            segmentId -> {});

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
            segmentId -> {}, 10000, 5000, 0.005);

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

        scheduler.registerSource(1L);
        assertEquals(1, scheduler.getRegisteredSourceCount());

        scheduler.registerSource(2L, 1800000L);
        assertEquals(2, scheduler.getRegisteredSourceCount());

        scheduler.unregisterSource(1L);
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
            new CuckooMaintenanceWorker(catalog, segmentId -> {}, 100, 1000, 0.001));

        // Invalid max deletions
        assertThrows(IllegalArgumentException.class, () ->
            new CuckooMaintenanceWorker(catalog, segmentId -> {}, 5000, 10, 0.001));

        // Invalid FP rate threshold
        assertThrows(IllegalArgumentException.class, () ->
            new CuckooMaintenanceWorker(catalog, segmentId -> {}, 5000, 1000, 0.5));

        catalog.close();
    }
}
