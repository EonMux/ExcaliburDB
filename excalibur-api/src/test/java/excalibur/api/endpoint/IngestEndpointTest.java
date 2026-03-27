package excalibur.api.endpoint;

import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.source.Source;
import excalibur.shard.worker.SegmentReindexScheduler;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IngestEndpoint and related classes.
 */
class IngestEndpointTest {

    private IngestEndpoint.InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new IngestEndpoint.InMemorySourceRegistry();
    }

    @Test
    @DisplayName("InMemorySourceRegistry should register and check sources")
    void testRegistryRegisterAndCheck() {
        assertFalse(registry.isRegistered("test-source"));

        excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        assertTrue(registry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should get source by ID")
    void testRegistryGetSource() {
        excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        excalibur.ingest.source.Source retrieved = registry.get("test-source");
        assertNotNull(retrieved);
        assertEquals("test-source", retrieved.getSourceId());
    }

    @Test
    @DisplayName("InMemorySourceRegistry should return null for unknown source")
    void testRegistryGetUnknownSource() {
        assertNull(registry.get("unknown"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should remove sources")
    void testRegistryRemove() {
        excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        assertTrue(registry.remove("test-source"));
        assertFalse(registry.isRegistered("test-source"));
        assertFalse(registry.remove("non-existent"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should return all source IDs")
    void testRegistryGetAllSourceIds() {
        excalibur.ingest.source.Source source1 = createMockSource("source-1");
        excalibur.ingest.source.Source source2 = createMockSource("source-2");
        registry.register(source1);
        registry.register(source2);

        assertEquals(2, registry.getAllSourceIds().size());
        assertTrue(registry.getAllSourceIds().contains("source-1"));
        assertTrue(registry.getAllSourceIds().contains("source-2"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should reject null source")
    void testRegistryNullSource() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    @DisplayName("IngestEndpoint should be created with default constructor")
    void testEndpointCreation() {
        IngestEndpoint endpoint = new IngestEndpoint();
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("IngestEndpoint should be created with custom registry")
    void testEndpointWithCustomRegistry() {
        IngestEndpoint endpoint = new IngestEndpoint(registry);
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("IngestEndpoint should reject null registry")
    void testEndpointNullRegistry() {
        assertThrows(NullPointerException.class, () -> new IngestEndpoint(null));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should register source with scheduler when configured")
    void testSchedulerRegistrationWhenSourceRegistered(@TempDir Path tempDir) {
        // Create a mock catalog and scheduler
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        AtomicBoolean reindexTriggered = new AtomicBoolean(false);
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(
            catalog,
            sourceId -> {
                reindexTriggered.set(true);
                return true;
            }
        );

        // Configure registry with scheduler
        registry.setReindexScheduler(scheduler, 60000L);

        // Register a source with numeric ID
        excalibur.ingest.source.Source mockSource = createMockSource("12345");
        registry.register(mockSource);

        // Verify source is registered with scheduler
        assertEquals(1, scheduler.getRegisteredSourceCount(), "Source should be registered with scheduler");
        assertNotNull(scheduler.getNextReindexTime(12345L), "Source should have scheduled reindex time");

        // Cleanup
        scheduler.stop();
    }

    @Test
    @DisplayName("InMemorySourceRegistry should handle non-numeric source IDs gracefully")
    void testNonNumericSourceIdWithScheduler(@TempDir Path tempDir) {
        // Create a mock catalog and scheduler
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(
            catalog,
            sourceId -> true
        );

        // Configure registry with scheduler
        registry.setReindexScheduler(scheduler, 60000L);

        // Register a source with non-numeric ID - should not throw
        excalibur.ingest.source.Source mockSource = createMockSource("my-docs-source");
        assertDoesNotThrow(() -> registry.register(mockSource));

        // Source should be in the registry but NOT in the scheduler
        assertTrue(registry.isRegistered("my-docs-source"), "Source should be in registry");
        assertEquals(0, scheduler.getRegisteredSourceCount(), "Non-numeric source should not be in scheduler");

        // Cleanup
        scheduler.stop();
    }

    @Test
    @DisplayName("InMemorySourceRegistry should use configured reindex interval")
    void testCustomReindexInterval(@TempDir Path tempDir) {
        // Create a mock catalog and scheduler
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        long customIntervalMs = 1800000L; // 30 minutes
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(
            catalog,
            sourceId -> true
        );

        // Configure registry with custom interval
        registry.setReindexScheduler(scheduler, customIntervalMs);

        // Register a source with numeric ID
        excalibur.ingest.source.Source mockSource = createMockSource("42");
        registry.register(mockSource);

        // Verify the scheduler has the source
        assertEquals(1, scheduler.getRegisteredSourceCount());

        // Cleanup
        scheduler.stop();
    }

    @Test
    @DisplayName("InMemorySourceRegistry should work without scheduler configured")
    void testNoSchedulerConfigured() {
        // Register a source without scheduler - should not throw
        excalibur.ingest.source.Source mockSource = createMockSource("12345");
        assertDoesNotThrow(() -> registry.register(mockSource));

        // Source should be in the registry
        assertTrue(registry.isRegistered("12345"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should trigger ingestion when orchestrator is configured")
    void testOrchestratorTriggeredOnRegistration() throws InterruptedException {
        // Create a latch to track when ingestion is started
        CountDownLatch ingestionStartedLatch = new CountDownLatch(1);
        AtomicReference<String> capturedSourceId = new AtomicReference<>();

        // Create a mock orchestrator using Mockito-style anonymous class
        // Since IngestionOrchestrator has complex dependencies, we test the wiring at the registry level
        IngestionOrchestrator mockOrchestrator = new IngestionOrchestrator(
            // Required dependencies - using test doubles
            new excalibur.ingest.processor.TextChunker(),
            // Create embedding client with test config
            createTestEmbeddingClient(),
            // Create minimal storage engine
            createTestStorageEngine(),
            // Create minimal index orchestrator
            createTestIndexOrchestrator(),
            // Status registry
            sourceId -> new IngestionOrchestrator.IngestionStatus() {
                @Override
                public void update(String state, double progress, long entriesIngested, long entriesPending) {}
                @Override
                public void setError(String error) {}
            }
        ) {
            @Override
            public void startIngestion(Source source) {
                capturedSourceId.set(source.getSourceId());
                ingestionStartedLatch.countDown();
                // Don't call super - just capture the call for test verification
            }
        };

        // Configure registry with orchestrator
        registry.setIngestionOrchestrator(mockOrchestrator);

        // Register a source
        excalibur.ingest.source.Source mockSource = createMockSource("test-source-with-orchestrator");
        registry.register(mockSource);

        // Wait for ingestion to be triggered
        boolean triggered = ingestionStartedLatch.await(5, TimeUnit.SECONDS);

        // Verify ingestion was triggered with the correct source
        assertTrue(triggered, "Ingestion should be triggered when orchestrator is configured");
        assertEquals("test-source-with-orchestrator", capturedSourceId.get(),
            "Orchestrator should receive the correct source ID");
    }

    @Test
    @DisplayName("InMemorySourceRegistry should work without orchestrator configured (stub mode)")
    void testStubModeWithoutOrchestrator() {
        // Don't configure orchestrator - should use stub mode
        excalibur.ingest.source.Source mockSource = createMockSource("stub-source");
        assertDoesNotThrow(() -> registry.register(mockSource));

        // Source should be in the registry
        assertTrue(registry.isRegistered("stub-source"));
    }

    // Helper to create test EmbeddingClient
    private excalibur.llm.embedding.EmbeddingClient createTestEmbeddingClient() {
        return new excalibur.llm.embedding.EmbeddingClient(
            excalibur.llm.client.LLMClientConfig.builder()
                .baseUrl("http://localhost:9999")
                .apiKey("test-key")
                .embeddingModel("test-model")
                .embeddingDimension(1536)
                .build()
        );
    }

    // Helper to create test StorageEngine
    private io.excalibur.store.StorageEngine createTestStorageEngine() {
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test-storage");
            io.excalibur.store.StorageEngine engine = new io.excalibur.store.StorageEngine(tempDir);
            engine.start();
            return engine;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test storage engine", e);
        }
    }

    // Helper to create test IndexOrchestrator
    private excalibur.shard.IndexOrchestrator createTestIndexOrchestrator() {
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test-orchestrator");
            io.excalibur.store.SegmentCatalog catalog = new io.excalibur.store.SegmentCatalog(
                tempDir.resolve("catalog.dat"));
            io.excalibur.store.StorageEngine storageEngine = createTestStorageEngine();
            excalibur.shard.allocator.VolumeManager volumeManager = new excalibur.shard.allocator.VolumeManager(catalog);

            return new excalibur.shard.IndexOrchestrator(
                catalog,
                storageEngine,
                volumeManager,
                100000, // hnswQueueDepthLimit
                60000,  // cuckooMaintenanceIntervalMs
                60000,  // reindexCheckIntervalMs
                100     // epochTickMs
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test index orchestrator", e);
        }
    }

    // Helper to create mock Source
    private excalibur.ingest.source.Source createMockSource(String sourceId) {
        return new excalibur.ingest.source.Source() {
            @Override
            public String getSourceId() {
                return sourceId;
            }

            @Override
            public String getSourceType() {
                return "mock";
            }

            @Override
            public java.util.stream.Stream<String> crawl() {
                return java.util.stream.Stream.empty();
            }

            @Override
            public boolean hasChanged(java.time.Instant since) {
                return false;
            }

            @Override
            public excalibur.ingest.source.FetchedDocument fetch(String id) {
                return null;
            }
        };
    }
}
