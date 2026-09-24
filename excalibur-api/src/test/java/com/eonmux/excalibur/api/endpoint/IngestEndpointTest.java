package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;
import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.IngestTargetValidator;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FilesystemSource;
import com.eonmux.excalibur.ingest.source.HttpSource;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.store.SegmentCatalog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        assertTrue(registry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should get source by ID")
    void testRegistryGetSource() {
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        com.eonmux.excalibur.ingest.source.Source retrieved = registry.get("test-source");
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
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        assertTrue(registry.remove("test-source"));
        assertFalse(registry.isRegistered("test-source"));
        assertFalse(registry.remove("non-existent"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should return all source IDs")
    void testRegistryGetAllSourceIds() {
        com.eonmux.excalibur.ingest.source.Source source1 = createMockSource("source-1");
        com.eonmux.excalibur.ingest.source.Source source2 = createMockSource("source-2");
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
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("12345");
        registry.register(mockSource);

        // Verify source is registered with scheduler
        assertEquals(1, scheduler.getRegisteredSourceCount(), "Source should be registered with scheduler");
        assertNotNull(scheduler.getNextReindexTime("12345"), "Source should have scheduled reindex time");

        // Cleanup
        scheduler.stop(java.time.Duration.ofSeconds(10));
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
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("my-docs-source");
        assertDoesNotThrow(() -> registry.register(mockSource));

        // Source should be in the registry and in the schedule, addressable by that same name
        assertTrue(registry.isRegistered("my-docs-source"), "Source should be in registry");
        assertEquals(1, scheduler.getRegisteredSourceCount(), "Source should be in scheduler");
        assertNotNull(scheduler.getNextReindexTime("my-docs-source"),
            "the schedule has to be addressable by the name /v1/ingest accepted");

        // Cleanup
        scheduler.stop(java.time.Duration.ofSeconds(10));
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
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("42");
        registry.register(mockSource);

        // Verify the scheduler has the source
        assertEquals(1, scheduler.getRegisteredSourceCount());

        // Cleanup
        scheduler.stop(java.time.Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should work without scheduler configured")
    void testNoSchedulerConfigured() {
        // Register a source without scheduler - should not throw
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("12345");
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
            new com.eonmux.excalibur.ingest.processor.TextChunker(),
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
                public void setSkipped(long entriesSkipped) {}
                @Override
                public void setDisallowed(long entriesDisallowed) {}
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
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("test-source-with-orchestrator");
        registry.register(mockSource);

        // Wait for ingestion to be triggered
        boolean triggered = ingestionStartedLatch.await(5, TimeUnit.SECONDS);

        // Verify ingestion was triggered with the correct source
        assertTrue(triggered, "Ingestion should be triggered when orchestrator is configured");
        assertEquals("test-source-with-orchestrator", capturedSourceId.get(),
            "Orchestrator should receive the correct source ID");
    }

    @Test
    @DisplayName("InMemorySourceRegistry registers a source without an orchestrator configured")
    void registersWithoutOrchestrator() {
        // No orchestrator: the source is recorded even though nothing will crawl it
        com.eonmux.excalibur.ingest.source.Source mockSource = createMockSource("stub-source");
        assertDoesNotThrow(() -> registry.register(mockSource));

        // Source should be in the registry
        assertTrue(registry.isRegistered("stub-source"));
    }

    // Helper to create test EmbeddingClient
    private com.eonmux.excalibur.llm.embedding.EmbeddingClient createTestEmbeddingClient() {
        return new com.eonmux.excalibur.llm.embedding.EmbeddingClient(
            com.eonmux.excalibur.llm.client.LLMClientConfig.builder()
                .baseUrl("http://localhost:9999")
                .apiKey("test-key")
                .embeddingModel("test-model")
                .embeddingDimension(1536)
                .build()
        );
    }

    // Helper to create test StorageEngine
    private com.eonmux.excalibur.store.StorageEngine createTestStorageEngine() {
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test-storage");
            com.eonmux.excalibur.store.StorageEngine engine = new com.eonmux.excalibur.store.StorageEngine(tempDir);
            engine.start();
            return engine;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test storage engine", e);
        }
    }

    // Helper to create test IndexOrchestrator
    private com.eonmux.excalibur.shard.IndexOrchestrator createTestIndexOrchestrator() {
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test-orchestrator");
            com.eonmux.excalibur.store.SegmentCatalog catalog = new com.eonmux.excalibur.store.SegmentCatalog(
                tempDir.resolve("catalog.dat"));
            com.eonmux.excalibur.store.StorageEngine storageEngine = createTestStorageEngine();
            com.eonmux.excalibur.shard.allocator.VolumeManager volumeManager = new com.eonmux.excalibur.shard.allocator.VolumeManager(catalog);

            return new com.eonmux.excalibur.shard.IndexOrchestrator(
                catalog,
                storageEngine,
                volumeManager,
                100000, // hnswQueueDepthLimit
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

    @Nested
    @DisplayName("Filesystem walk filters")
    class WalkFilters {

        @Test
        @DisplayName("keeps them on a filesystem source")
        void keepsThemOnAFilesystemSource(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"included_extensions\": [\".md\"], "
                + "\"excluded_directories\": [\".git\"]}}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.isRegistered("docs")).isTrue();
        }

        @Test
        @DisplayName("refuses included_extensions on an HTTP crawl rather than dropping it")
        void refusesIncludedExtensionsOnACrawl(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"included_extensions\": [\".md\"]}}");

            assertThat(response.status())
                .as("accepted and silently discarded, the caller reads 200 registered and then "
                    + "gets the whole site crawled with nothing saying the filter was dropped")
                .isEqualTo(400);
            assertThat(response.body()).contains("included_extensions").contains("filesystem");
            assertThat(registry.isRegistered("site")).isFalse();
        }

        @Test
        @DisplayName("refuses excluded_directories on an HTTP crawl")
        void refusesExcludedDirectoriesOnACrawl(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"excluded_directories\": [\"drafts\"]}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("excluded_directories");
            assertThat(registry.isRegistered("site")).isFalse();
        }

        @Test
        @DisplayName("leaves an HTTP crawl that sends neither alone")
        void acceptsACrawlWithoutFilters(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\"}}");

            assertThat(response.status()).isNotEqualTo(400);
        }
    }

    @Nested
    @DisplayName("Crawl tunables")
    class CrawlTunables {

        @Test
        @DisplayName("a filesystem walk accepts the settings that shape it")
        void filesystemSettingsAreAccepted(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"max_depth\": 3, \"follow_symlinks\": false, "
                + "\"include_hidden\": true, \"max_file_bytes\": 1048576}}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.isRegistered("docs")).isTrue();
        }

        @Test
        @DisplayName("an HTTP crawl accepts the settings that shape it")
        void crawlSettingsAreAccepted(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"max_depth\": 2, \"max_documents\": 500, "
                + "\"max_response_bytes\": 1048576, \"timeout_seconds\": 15, "
                + "\"included_path_patterns\": [\"/docs\"], "
                + "\"excluded_path_patterns\": [\"/admin\"], "
                + "\"user_agent\": \"AcmeBot/1.0\"}}");

            assertThat(response.status()).isNotEqualTo(400);
        }

        /**
         * The list is recorded and restored comma-separated, so an element containing a comma
         * would come back as two: {@code ["/a,b/"]} would exclude {@code /a} and {@code b/},
         * far more of the crawl than was asked for, and the caller would be told the source
         * registered fine.
         */
        @Test
        @DisplayName("a comma inside a list element is refused, not silently split")
        void listElementsCannotCarryACommma(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"excluded_path_patterns\": [\"/a,b/\"]}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("excluded_path_patterns");
        }

        @Test
        @DisplayName("a depth past the bound this node allows is refused, not clamped")
        void depthIsBounded(@TempDir Path root) throws IOException {
            // Clamping would answer 200 and crawl to a depth the caller did not ask for, which
            // reads back as a crawl that stopped early for no stated reason.
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"max_depth\": 100000}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("max_depth").contains("1000");
            assertThat(registry.isRegistered("docs")).isFalse();
        }

        @Test
        @DisplayName("a bounded setting sent as null takes its default, not its minimum")
        void explicitNullIsTheDefault(@TempDir Path root) throws IOException {
            // Null means "not set" everywhere else in the API. Read as a number, it became the
            // bound's floor: a walk one level deep that reads no file larger than one byte.
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"max_depth\": null, \"max_file_bytes\": null, \"timeout_seconds\": null}}");

            assertThat(response.status()).isEqualTo(200);
            FilesystemSource source = (FilesystemSource) registry.get("docs");
            FilesystemSource defaults = FilesystemSource.builder()
                .sourceId("defaults").rootPath(root).build();
            assertThat(source.getMaxDepth()).isEqualTo(defaults.getMaxDepth());
            assertThat(source.getMaxFileSize()).isEqualTo(defaults.getMaxFileSize());
        }

        @Test
        @DisplayName("a file size bound past what ingestion can index is refused")
        void fileSizeStopsAtTheEngineLimit(@TempDir Path root) throws IOException {
            // A walk told to read further than the engine indexes reads the file into memory
            // only for ingestion to discard it, and reports a failure for a file the setting
            // said would be read.
            long limit = DocumentLimits.MAX_DOCUMENT_BYTES;
            Response refused = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"max_file_bytes\": " + (limit + 1) + "}}");
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body()).contains("max_file_bytes").contains(Long.toString(limit));

            Response accepted = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"max_file_bytes\": " + limit + "}}");
            assertThat(accepted.status()).isEqualTo(200);
        }

        @Test
        @DisplayName("a response size bound past what ingestion can index is refused")
        void responseSizeStopsAtTheEngineLimit(@TempDir Path root) throws IOException {
            long limit = DocumentLimits.MAX_DOCUMENT_BYTES;
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"max_response_bytes\": " + (limit + 1) + "}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("max_response_bytes")
                .contains(Long.toString(limit));
        }

        @Test
        @DisplayName("a file size bound of zero is refused")
        void fileSizeHasAFloor(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"max_file_bytes\": 0}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("max_file_bytes");
        }

        @Test
        @DisplayName("a User-Agent carrying a newline is refused as header forgery")
        void userAgentCannotForgeAHeader(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"user_agent\": \"Acme\\r\\nX-Injected: yes\"}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("user_agent");
            assertThat(registry.isRegistered("site")).isFalse();
        }

        @Test
        @DisplayName("a crawl setting on a filesystem walk is refused rather than dropped")
        void crawlSettingsAreRefusedOnAWalk(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"timeout_seconds\": 5}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("timeout_seconds");
            assertThat(registry.isRegistered("docs")).isFalse();
        }

        @Test
        @DisplayName("a walk setting on an HTTP crawl is refused rather than dropped")
        void walkSettingsAreRefusedOnACrawl(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"follow_symlinks\": true}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("follow_symlinks");
            assertThat(registry.isRegistered("site")).isFalse();
        }
    }

    /**
     * The registration side of robots.txt: whether a crawl obeys it is the caller's to say, it
     * is on unless they say otherwise, and what they said survives a restart.
     *
     * <p>Against an IP literal, which the allow-list check can see is public without asking DNS,
     * so these tests build a real crawler without needing the network.</p>
     */
    @Nested
    @DisplayName("respect_robots_txt")
    class RespectRobotsTxt {

        private static final String HOST = "93.184.215.14";

        @Test
        @DisplayName("a crawl registered without the setting obeys robots.txt")
        void onByDefault() throws IOException {
            Response response = crawl("");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registered().isRespectingRobotsTxt()).isTrue();
        }

        @Test
        @DisplayName("false switches it off, and the catalog records that so a restart agrees")
        void falseIsRecorded(@TempDir Path dataDir) throws IOException {
            registry.setSourceCatalog(SourceCatalog.open(dataDir));

            Response response = crawl(", \"respect_robots_txt\": false");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registered().isRespectingRobotsTxt()).isFalse();
            assertThat(SourceCatalog.open(dataDir).all())
                .singleElement()
                .satisfies(descriptor -> assertThat(descriptor.get(
                    SourceFactory.RESPECT_ROBOTS_TXT)).isEqualTo("false"));
        }

        @Test
        @DisplayName("an explicit null is the default, not a way to switch it off")
        void nullIsTheDefault(@TempDir Path dataDir) throws IOException {
            // A client that serialises every unset option as null must get the crawl it would
            // have got by leaving the field out.
            registry.setSourceCatalog(SourceCatalog.open(dataDir));

            Response response = crawl(", \"respect_robots_txt\": null");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registered().isRespectingRobotsTxt()).isTrue();
            assertThat(SourceCatalog.open(dataDir).all())
                .singleElement()
                .satisfies(descriptor -> assertThat(descriptor.get(
                    SourceFactory.RESPECT_ROBOTS_TXT))
                    .as("recorded as unset, so a restore applies the same default")
                    .isNull());
        }

        @Test
        @DisplayName("a value that is not a boolean is refused rather than guessed at")
        void aNonBooleanIsRefused() throws IOException {
            Response response = crawl(", \"respect_robots_txt\": \"no\"");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("respect_robots_txt");
            assertThat(registry.isRegistered("site")).isFalse();
        }

        @Test
        @DisplayName("a filesystem walk refuses it, having no robots.txt to read")
        void aWalkRefusesIt(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"respect_robots_txt\": false}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("respect_robots_txt");
            assertThat(registry.isRegistered("docs")).isFalse();
        }

        @Test
        @DisplayName("a registration recorded before the setting existed is restored obeying it")
        void anOlderRecordRestoresObeying() {
            SourceFactory factory = new SourceFactory(new IngestTargetValidator(security()));

            Source restored = factory.create(new SourceDescriptor(SourceFactory.TYPE_HTTP,
                "site", Map.of(SourceFactory.BASE_URL, "https://" + HOST + "/docs")));
            Source optedOut = factory.create(new SourceDescriptor(SourceFactory.TYPE_HTTP,
                "site", Map.of(SourceFactory.BASE_URL, "https://" + HOST + "/docs",
                    SourceFactory.RESPECT_ROBOTS_TXT, "false")));

            assertThat(((HttpSource) restored).isRespectingRobotsTxt()).isTrue();
            assertThat(((HttpSource) optedOut).isRespectingRobotsTxt()).isFalse();
        }

        private Response crawl(String extraConfig) throws IOException {
            return post(security(), null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://" + HOST + "/docs\"" + extraConfig + "}}", null);
        }

        private ApiSecurityConfig security() {
            return ApiSecurityConfig.builder().allowIngestHost(HOST).build();
        }

        private HttpSource registered() {
            assertThat(registry.get("site")).isInstanceOf(HttpSource.class);
            return (HttpSource) registry.get("site");
        }
    }

    @Nested
    @DisplayName("Declared encoding")
    class DeclaredEncoding {

        @Test
        @DisplayName("a filesystem walk reads unmarked files in the encoding it declares")
        void aDeclaredCharsetReachesTheSource(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"charset\": \"windows-1252\"}}");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.get("docs"))
                .isInstanceOfSatisfying(com.eonmux.excalibur.ingest.source.FilesystemSource.class,
                    source -> assertThat(source.getCharset())
                        .isEqualTo(java.nio.charset.Charset.forName("windows-1252")));
        }

        @Test
        @DisplayName("the catalog records the encoding under its canonical name")
        void theCatalogRecordsTheCanonicalName(@TempDir Path root, @TempDir Path dataDir)
                throws IOException {
            // Recorded so a restart restores the same walk; canonical so the config view shows
            // the encoding the node will use rather than whichever alias was sent.
            registry.setSourceCatalog(SourceCatalog.open(dataDir));

            assertThat(post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"charset\": \"latin1\"}}").status()).isEqualTo(200);

            assertThat(SourceCatalog.open(dataDir).all())
                .singleElement()
                .satisfies(descriptor -> assertThat(descriptor.get(SourceFactory.CHARSET))
                    .isEqualTo("ISO-8859-1"));
        }

        @Test
        @DisplayName("an encoding this node does not support is refused with a 400 naming the field")
        void anUnknownCharsetIsRefused(@TempDir Path root) throws IOException {
            // Falling back to UTF-8 would register fine and then index a replacement character
            // in place of every accented letter the declared encoding would have decoded.
            Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root + "\", "
                + "\"charset\": \"klingon-8\"}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("charset").contains("klingon-8");
            assertThat(registry.isRegistered("docs")).isFalse();
        }

        @Test
        @DisplayName("a malformed or blank encoding name is refused")
        void aMalformedCharsetIsRefused(@TempDir Path root) throws IOException {
            for (String name : new String[] {"utf 8!", "  "}) {
                Response response = post(root, null, "{\"source_type\": \"filesystem\", "
                    + "\"config\": {\"source_id\": \"docs\", \"root_path\": \"" + root
                    + "\", \"charset\": \"" + name + "\"}}");

                assertThat(response.status()).as(name).isEqualTo(400);
                assertThat(response.body()).as(name).contains("charset");
            }
            assertThat(registry.isRegistered("docs")).isFalse();
        }

        @Test
        @DisplayName("an HTTP crawl is refused an encoding, because every response names its own")
        void anHttpCrawlTakesNoCharset(@TempDir Path root) throws IOException {
            Response response = post(root, null, "{\"source_type\": \"http\", "
                + "\"config\": {\"source_id\": \"site\", "
                + "\"base_url\": \"https://example.com/docs\", "
                + "\"charset\": \"UTF-8\"}}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("charset");
            assertThat(registry.isRegistered("site")).isFalse();
        }

        @Test
        @DisplayName("a catalog entry naming an unknown encoding is refused on restore too")
        void theFactoryRechecksARestoredCharset(@TempDir Path root) {
            // The catalog is a file on disk: a hand-edit must not build a walk that silently
            // reads everything as UTF-8.
            SourceFactory factory = new SourceFactory(
                new IngestTargetValidator(ApiSecurityConfig.builder().allowIngestRoot(root).build()));
            SourceDescriptor descriptor = new SourceDescriptor(SourceFactory.TYPE_FILESYSTEM,
                "docs", Map.of(SourceFactory.ROOT_PATH, root.toString(),
                    SourceFactory.CHARSET, "klingon-8"));

            assertThatThrownBy(() -> factory.create(descriptor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.charset");
        }
    }

    @Nested
    @DisplayName("Crawling over supplied documents")
    class SuppliedDocuments {

        @Test
        @DisplayName("refuses a crawl registered over an id documents were supplied under")
        void refusesCrawlOverSuppliedSource(@TempDir Path root) throws IOException {
            InlineSourceIds supplied = new InlineSourceIds(256);
            supplied.remember("notes");

            Response response = register(root, "notes", supplied);

            assertThat(response.status()).isEqualTo(409);
            assertThat(response.body()).contains("notes")
                .contains("\"code\": \"source_kind_conflict\"");
            // Not registered: a crawl that was refused must not be crawling.
            assertThat(registry.isRegistered("notes")).isFalse();
        }

        @Test
        @DisplayName("undoes a registration that raced a supplied batch and won")
        void undoesARegistrationThatRacedASuppliedBatch(@TempDir Path root) throws IOException {
            // The pre-check sees nothing; the batch records its claim; the post-check sees it.
            // Without the second read the crawl would stay registered and delete the documents
            // on its first pass, which is the loss both halves of this guard exist to prevent.
            InlineSourceIds supplied = new InlineSourceIds(256);
            // The batch claims the id while this registration is inside register(), which is
            // exactly the window the pre-check cannot see.
            registry = new InMemorySourceRegistry() {
                @Override
                public void register(Source source,
                        com.eonmux.excalibur.ingest.source.SourceDescriptor descriptor) {
                    supplied.remember(source.getSourceId());
                    super.register(source, descriptor);
                }
            };

            Response response = register(root, "notes", supplied);

            assertThat(response.status()).isEqualTo(409);
            assertThat(registry.isRegistered("notes"))
                .as("a registration that is refused must not be left crawling")
                .isFalse();
        }

        @Test
        @DisplayName("registers an id nothing was supplied under")
        void registersUnrelatedSource(@TempDir Path root) throws IOException {
            InlineSourceIds supplied = new InlineSourceIds(256);
            supplied.remember("notes");

            Response response = register(root, "articles", supplied);

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.isRegistered("articles")).isTrue();
        }
    }

    /**
     * A source belongs to the namespace that registered it, and to no other.
     *
     * <p>{@code X-Excalibur-Namespace} is the same header the graph routes read, named once in
     * {@link GraphTenants#NAMESPACE_HEADER}. Registration is where a source's namespace is
     * settled, because it is the only moment the caller and the crawl are both present: every
     * document the crawl later publishes is staged into the namespace named here, including the
     * documents a re-crawl publishes after a restart.</p>
     */
    @Nested
    @DisplayName("The namespace a source is registered in")
    class Namespaces {

        @Test
        @DisplayName("refuses a namespace header that is not a namespace id, and names the rule")
        void refusesAnIllegalNamespace(@TempDir Path root) throws IOException {
            Response response = post(root, null, body(root, "docs"), "Not A Namespace");

            assertThat(response.status())
                .as("a caller who asked for a namespace this node cannot name must not be "
                    + "quietly given the shared one: its documents would cross a tenancy "
                    + "boundary and nothing in the answer would say so")
                .isEqualTo(400);
            assertThat(response.body()).contains("[a-z0-9]");
            assertThat(registry.getAllSourceIds()).isEmpty();
        }

        @Test
        @DisplayName("registers a source in the namespace its header named")
        void registersAgainstTheNamespaceTheHeaderNamed(@TempDir Path root) throws IOException {
            Response response = post(root, null, body(root, "docs"), "acme");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.namespaceOf("docs"))
                .as("the graph sink routes on this: without it the header is honoured by the "
                    + "query routes and nothing can ever put a fact into the namespace")
                .contains(NamespaceId.of("acme"));
        }

        @Test
        @DisplayName("a registration that names no namespace belongs to the shared one")
        void noHeaderMeansTheSharedNamespace(@TempDir Path root) throws IOException {
            Response response = post(root, null, body(root, "docs"), null);

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.namespaceOf("docs"))
                .as("a node that never sees the header behaves as a single-tenant node")
                .contains(NamespaceId.SHARED);
        }

        @Test
        @DisplayName("writes the namespace down, so a re-crawl after a restart stages where it did")
        void recordsTheNamespaceInTheCatalog(@TempDir Path root, @TempDir Path dataDir)
                throws IOException {
            SourceCatalog catalog = SourceCatalog.open(dataDir);
            registry.setSourceCatalog(catalog);

            assertThat(post(root, null, body(root, "docs"), "acme").status()).isEqualTo(200);

            assertThat(SourceCatalog.open(dataDir).all())
                .extracting(SourceDescriptor::sourceId, SourceDescriptor::namespace)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("docs", NamespaceId.of("acme")));
        }

        @Test
        @DisplayName("naming the shared namespace registers into it, as naming none does")
        void acceptsTheSharedNamespaceByName(@TempDir Path root) throws IOException {
            // Leaving the header off already lands in 'shared', so refusing the explicit name
            // would protect nothing and break a client that stamps the header on every request.
            Response response = post(root, null, body(root, "docs"), "shared");

            assertThat(response.status()).isEqualTo(200);
            assertThat(registry.namespaceOf("docs")).contains(NamespaceId.SHARED);
        }

        @Test
        @DisplayName("admits the namespace it registers into, before registering")
        void admitsTheNamespace(@TempDir Path root) throws IOException {
            List<NamespaceId> admitted = new ArrayList<>();

            Response response = post(ApiSecurityConfig.builder().allowIngestRoot(root).build(),
                null, body(root, "docs"), "acme", admitted::add);

            assertThat(response.status()).isEqualTo(200);
            assertThat(admitted).containsExactly(NamespaceId.of("acme"));
        }

        @Test
        @DisplayName("a namespace the node refuses registers nothing, and says why")
        void aRefusedNamespaceRegistersNothing(@TempDir Path root) throws IOException {
            // Refused at registration, not when the crawl publishes: by then the caller has been
            // told its source is registered, and its documents would be staged nowhere.
            for (NamespaceRefusedException.Reason reason : NamespaceRefusedException.Reason.values()) {
                Response response = post(ApiSecurityConfig.builder().allowIngestRoot(root).build(),
                    null, body(root, "docs"), "acmee", namespace -> {
                        throw new NamespaceRefusedException(namespace, reason, "refused " + reason);
                    });

                assertThat(response.status()).as(reason.name()).isEqualTo(reason.statusCode());
                assertThat(response.body())
                    .contains("\"code\": \"" + reason.code().wireName() + "\"")
                    .contains("refused " + reason);
                assertThat(registry.getAllSourceIds()).isEmpty();
            }
        }

        /** A filesystem registration for {@code root} under {@code sourceId}. */
        private String body(Path root, String sourceId) {
            return "{\"source_type\": \"filesystem\", \"config\": {"
                + "\"source_id\": \"" + sourceId + "\", "
                + "\"root_path\": \"" + root + "\"}}";
        }
    }

    /** One captured HTTP response. */
    private record Response(int status, String body) {}

    /**
     * Posts a filesystem registration for {@code root}, which the endpoint under test allows.
     */
    private Response register(Path root, String sourceId) throws IOException {
        return register(root, sourceId, null);
    }

    private Response register(Path root, String sourceId, InlineSourceIds supplied)
            throws IOException {
        return post(root, supplied, "{\"source_type\": \"filesystem\", \"config\": {"
            + "\"source_id\": \"" + sourceId + "\", "
            + "\"root_path\": \"" + root + "\"}}");
    }

    /**
     * Posts {@code requestBody} to an endpoint whose allow-list holds {@code root}.
     */
    private Response post(Path root, InlineSourceIds supplied, String requestBody)
            throws IOException {
        return post(root, supplied, requestBody, null);
    }

    /**
     * Posts {@code requestBody} with the namespace header a caller would send, or without one
     * when {@code namespace} is null.
     */
    private Response post(Path root, InlineSourceIds supplied, String requestBody,
                          String namespace) throws IOException {
        return post(ApiSecurityConfig.builder().allowIngestRoot(root).build(), supplied,
            requestBody, namespace);
    }

    /**
     * Posts {@code requestBody} to an endpoint enforcing {@code security}.
     */
    private Response post(ApiSecurityConfig security, InlineSourceIds supplied,
                          String requestBody, String namespace) throws IOException {
        return post(security, supplied, requestBody, namespace, NamespaceAdmission.ANY);
    }

    /**
     * Posts {@code requestBody} to an endpoint enforcing {@code security} that admits namespaces
     * through {@code admission}.
     */
    private Response post(ApiSecurityConfig security, InlineSourceIds supplied,
                          String requestBody, String namespace, NamespaceAdmission admission)
            throws IOException {
        IngestEndpoint endpoint = new IngestEndpoint(registry, null, null,
            new IngestTargetValidator(security), supplied, IngestConfig.DEFAULT_CRAWL_DELAY_MS,
            admission);

        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        Headers requestHeaders = new Headers();
        if (namespace != null) {
            requestHeaders.set(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/ingest"));
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
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
    private com.eonmux.excalibur.ingest.source.Source createMockSource(String sourceId) {
        return new com.eonmux.excalibur.ingest.source.Source() {
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
            public com.eonmux.excalibur.ingest.source.FetchedDocument fetch(String id) {
                return null;
            }
        };
    }
}
