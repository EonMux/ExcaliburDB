package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import excalibur.api.security.ApiSecurityConfig;
import excalibur.api.security.IngestTargetValidator;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.source.Source;
import excalibur.shard.worker.SegmentReindexScheduler;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for IngestEndpoint and related classes.
 */
class IngestEndpointTest {

    private InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemorySourceRegistry();
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
        assertNotNull(scheduler.getNextReindexTime("12345"), "Source should have scheduled reindex time");

        // Cleanup
        scheduler.stop();
    }

    @Test
    @DisplayName("InMemorySourceRegistry should schedule a source under the name it was given")
    void testNonNumericSourceIdWithScheduler(@TempDir Path tempDir) {
        // Create a mock catalog and scheduler
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(
            catalog,
            sourceId -> true
        );

        // Configure registry with scheduler
        registry.setReindexScheduler(scheduler, 60000L);

        // Register a source under the kind of name a caller actually picks
        excalibur.ingest.source.Source mockSource = createMockSource("my-docs-source");
        assertDoesNotThrow(() -> registry.register(mockSource));

        // Source should be in the registry and in the schedule, addressable by that same name
        assertTrue(registry.isRegistered("my-docs-source"), "Source should be in registry");
        assertEquals(1, scheduler.getRegisteredSourceCount(), "Source should be in scheduler");
        assertNotNull(scheduler.getNextReindexTime("my-docs-source"),
            "the schedule has to be addressable by the name /v1/ingest accepted");

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

    /**
     * A source id is not free text. Registration writes it into the source catalog and into every
     * document's registry record, so its length is multiplied by the size of the corpus rather
     * than by the number of requests, and it is the name the crawl thread runs under. Nothing
     * that identifies a source needs more characters than a name.
     */
    @Nested
    @DisplayName("Source id length")
    class SourceIdLength {

        @Test
        @DisplayName("refuses a source id longer than the bound, and names the bound")
        void refusesAnOversizedSourceId(@TempDir Path root) throws IOException {
            Response response = register(root, "x".repeat(Source.MAX_ID_LENGTH + 1));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .as("refused at the boundary, not inside a crawler constructor: that failure is "
                    + "answered with a deliberately opaque 'Invalid source configuration', which "
                    + "leaves the caller with nothing to correct")
                .contains("source_id")
                .contains(String.valueOf(Source.MAX_ID_LENGTH));
            assertThat(registry.getAllSourceIds()).isEmpty();
        }

        @Test
        @DisplayName("accepts a source id exactly at the bound")
        void acceptsASourceIdAtTheBound(@TempDir Path root) throws IOException {
            String sourceId = "x".repeat(Source.MAX_ID_LENGTH);

            Response response = register(root, sourceId);

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.isRegistered(sourceId)).isTrue();
        }
    }

    /** One captured HTTP response. */
    private record Response(int status, String body) {}

    /**
     * Posts a filesystem registration for {@code root}, which the endpoint under test allows.
     */
    private Response register(Path root, String sourceId) throws IOException {
        IngestEndpoint endpoint = new IngestEndpoint(registry, null, null,
            new IngestTargetValidator(ApiSecurityConfig.builder().allowIngestRoot(root).build()));
        String requestBody = "{\"source_type\": \"filesystem\", \"config\": {"
            + "\"source_id\": \"" + sourceId + "\", "
            + "\"root_path\": \"" + root + "\"}}";

        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/ingest"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(responseBody);

        endpoint.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), responseBody.toString(StandardCharsets.UTF_8));
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
