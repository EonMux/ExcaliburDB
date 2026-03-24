package excalibur.api.endpoint;

import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.SourceResolver;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.EpochTickScheduler;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for ReindexEndpoint with shared source registry.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>Sources registered via /v1/ingest are visible to /v1/reindex</li>
 *   <li>Actual reindex execution occurs when DocumentReindexer is configured</li>
 *   <li>Proper status tracking throughout the reindex lifecycle</li>
 * </ul>
 */
class ReindexIntegrationTest {

    private static final Path TEST_DOCS_DIR = Path.of("/tmp/test-docs");

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private SegmentCatalog segmentCatalog;
    private excalibur.api.server.ExcaliburDBServer server;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        // Create test directory for filesystem source validation
        Files.createDirectories(TEST_DOCS_DIR);

        segmentCatalog = new SegmentCatalog();

        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);

        var visibilityTracker = new excalibur.shard.VisibilityTracker(segmentCatalog);
        var epochTickScheduler = mock(EpochTickScheduler.class);

        when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(epochTickScheduler);
        when(epochTickScheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
        when(indexOrchestrator.getHnswQueueDepth()).thenReturn(0L);
        when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
        when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(0L);
        when(indexOrchestrator.getPendingCuckooDeletions()).thenReturn(0);
        when(indexOrchestrator.getTotalCuckooDeletions()).thenReturn(0L);

        server = new excalibur.api.server.ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(1);
        }
        // Clean up test directory
        if (Files.exists(TEST_DOCS_DIR)) {
            Files.deleteIfExists(TEST_DOCS_DIR);
        }
    }

    @Nested
    @DisplayName("Shared source registry integration")
    class SharedSourceRegistryTests {

        @Test
        @DisplayName("source registered via /v1/ingest is visible to /v1/reindex")
        void sourceRegisteredViaIngestIsVisibleToReindex() throws Exception {
            // First, try to reindex a non-existent source - should get 404
            HttpURLConnection reindexConn = connect("POST", "/v1/reindex",
                "{\"source_id\": \"nonexistent\"}");
            assertThat(reindexConn.getResponseCode()).isEqualTo(404);
            reindexConn.disconnect();

            // Now register a source via /v1/ingest
            HttpURLConnection ingestConn = connect("POST", "/v1/ingest",
                "{\"source_type\": \"filesystem\", \"config\": {" +
                "\"source_id\": \"test-source\"," +
                "\"root_path\": \"/tmp/test-docs\"" +
                "}}");
            assertThat(ingestConn.getResponseCode()).isEqualTo(200);
            String ingestResponse = readResponse(ingestConn);
            assertThat(ingestResponse).contains("\"status\": \"registered\"");
            ingestConn.disconnect();

            // Now try to reindex the same source - should get 202 (accepted, even in stub mode)
            HttpURLConnection reindexConn2 = connect("POST", "/v1/reindex",
                "{\"source_id\": \"test-source\"}");
            assertThat(reindexConn2.getResponseCode()).isEqualTo(202);
            String reindexResponse = readResponse(reindexConn2);
            assertThat(reindexResponse).contains("\"status\": \"reindexing\"");
            reindexConn2.disconnect();
        }

        @Test
        @DisplayName("reindex endpoint returns correct status for existing source")
        void reindexReturnsCorrectStatusForExistingSource() throws Exception {
            // Register a source
            HttpURLConnection ingestConn = connect("POST", "/v1/ingest",
                "{\"source_type\": \"filesystem\", \"config\": {" +
                "\"source_id\": \"status-test-source\"," +
                "\"root_path\": \"/tmp/test-docs\"" +
                "}}");
            assertThat(ingestConn.getResponseCode()).isEqualTo(200);
            ingestConn.disconnect();

            // Reindex should return 202 Accepted
            HttpURLConnection reindexConn = connect("POST", "/v1/reindex",
                "{\"source_id\": \"status-test-source\"}");
            assertThat(reindexConn.getResponseCode()).isEqualTo(202);
            assertThat(reindexConn.getContentType()).contains("application/json");

            String response = readResponse(reindexConn);
            assertThat(response).contains("\"source_id\": \"status-test-source\"");
            assertThat(response).contains("\"status\": \"reindexing\"");
            assertThat(response).contains("\"message\": \"Reindex started in background\"");
            reindexConn.disconnect();
        }
    }

    @Nested
    @DisplayName("With DocumentReindexer configured")
    class WithReindexerTests {

        @Test
        @DisplayName("actual reindex execution occurs when DocumentReindexer is configured")
        void actualReindexExecutionOccurs() throws Exception {
            // Create mock reindexer that tracks whether reindexAll was called
            AtomicBoolean reindexCalled = new AtomicBoolean(false);
            AtomicReference<String> reindexedSourceId = new AtomicReference<>();

            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            when(mockReindexer.reindexAll(any())).thenAnswer(invocation -> {
                reindexCalled.set(true);
                Source source = invocation.getArgument(0);
                reindexedSourceId.set(source.getSourceId());
                return new DocumentReindexer.ReindexResult.Builder("test-source")
                    .documentsIndexed(5)
                    .chunksCreated(25)
                    .build();
            });

            // Create source resolver that returns a mock source
            SourceResolver mockResolver = sourceId -> {
                Source mockSource = mock(Source.class);
                when(mockSource.getSourceId()).thenReturn(sourceId);
                try {
                    when(mockSource.crawl()).thenReturn(java.util.stream.Stream.empty());
                } catch (SourceException e) {
                    // Mock setup should not throw, wrap in unchecked exception
                    throw new RuntimeException(e);
                }
                return mockSource;
            };

            // Stop the current server and create a new one with reindexer configured
            server.stop(1);

            excalibur.api.server.ExcaliburDBServer serverWithReindexer =
                new excalibur.api.server.ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            serverWithReindexer.withReindexer(mockReindexer, mockResolver);
            serverWithReindexer.start();
            int portWithReindexer = serverWithReindexer.getPort();

            try {
                // Register a source via /v1/ingest
                HttpURLConnection ingestConn = connectTo(portWithReindexer, "POST", "/v1/ingest",
                    "{\"source_type\": \"filesystem\", \"config\": {" +
                    "\"source_id\": \"reindex-exec-test\"," +
                    "\"root_path\": \"/tmp/test-docs\"" +
                    "}}");
                assertThat(ingestConn.getResponseCode()).isEqualTo(200);
                ingestConn.disconnect();

                // Trigger reindex
                HttpURLConnection reindexConn = connectTo(portWithReindexer, "POST", "/v1/reindex",
                    "{\"source_id\": \"reindex-exec-test\"}");
                assertThat(reindexConn.getResponseCode()).isEqualTo(202);
                reindexConn.disconnect();

                // Wait for async reindex to complete (with timeout)
                Thread.sleep(500);

                // Verify reindexAll was called
                assertThat(reindexCalled.get()).isTrue();
                assertThat(reindexedSourceId.get()).isEqualTo("reindex-exec-test");
            } finally {
                serverWithReindexer.stop(1);
            }
        }
    }

    private HttpURLConnection connect(String method, String path) throws IOException {
        return connectTo(serverPort, method, path);
    }

    private HttpURLConnection connectTo(int port, String method, String path) throws IOException {
        return connectTo(port, method, path, null);
    }

    private HttpURLConnection connectTo(int port, String method, String path, String body) throws IOException {
        var url = URI.create("http://localhost:" + port + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/json");

        if (body != null) {
            conn.setDoOutput(true);
            try (var os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        conn.connect();
        return conn;
    }

    private HttpURLConnection connect(String method, String path, String body) throws IOException {
        return connectTo(serverPort, method, path, body);
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
