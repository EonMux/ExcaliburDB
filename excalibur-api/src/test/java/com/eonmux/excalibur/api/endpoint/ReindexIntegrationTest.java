package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

    /**
     * Per-test crawl root. A fixed shared directory under /tmp is visible to every
     * concurrently running fork, and deleting it in @AfterEach pulls it out from under
     * a sibling that is still using it.
     */
    @TempDir
    Path testDocsDir;

    private ApiSecurityConfig security;
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private SegmentCatalog segmentCatalog;
    private com.eonmux.excalibur.api.server.ExcaliburDBServer server;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        // Filesystem ingest is allow-list driven: an empty allow-list disables it, so
        // the test grants exactly the temporary root it crawls.
        security = ApiSecurityConfig.builder()
            .allowIngestRoot(testDocsDir)
            .build();

        segmentCatalog = new SegmentCatalog();

        indexOrchestrator = mock(IndexOrchestrator.class);
        when(indexOrchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);

        var visibilityTracker = new com.eonmux.excalibur.shard.VisibilityTracker(segmentCatalog);
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

        server = new com.eonmux.excalibur.api.server.ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(security);
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(1);
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
                "\"root_path\": \"" + testDocsDir + "\"" +
                "}}");
            assertThat(ingestConn.getResponseCode()).isEqualTo(200);
            String ingestResponse = readResponse(ingestConn);
            assertThat(ingestResponse).contains("\"status\": \"registered\"");
            ingestConn.disconnect();

            // The source is now visible to /v1/reindex: the answer is no longer 404.
            // Without a DocumentReindexer the request is refused with 503 rather than
            // accepted with 202 - reporting success while nothing runs would leave the
            // source marked "reindexing" for the lifetime of the process and block
            // every later request for it.
            HttpURLConnection reindexConn2 = connect("POST", "/v1/reindex",
                "{\"source_id\": \"test-source\"}");
            assertThat(reindexConn2.getResponseCode()).isEqualTo(503);
            reindexConn2.disconnect();
        }

        @Test
        @DisplayName("reindex without a configured reindexer is refused, not silently accepted")
        void reindexWithoutReindexerIsRefused() throws Exception {
            // Register a source
            HttpURLConnection ingestConn = connect("POST", "/v1/ingest",
                "{\"source_type\": \"filesystem\", \"config\": {" +
                "\"source_id\": \"status-test-source\"," +
                "\"root_path\": \"" + testDocsDir + "\"" +
                "}}");
            assertThat(ingestConn.getResponseCode()).isEqualTo(200);
            ingestConn.disconnect();

            // No DocumentReindexer is wired, so the endpoint reports that reindexing
            // is unavailable instead of accepting work it will never do.
            HttpURLConnection reindexConn = connect("POST", "/v1/reindex",
                "{\"source_id\": \"status-test-source\"}");
            assertThat(reindexConn.getResponseCode()).isEqualTo(503);
            assertThat(reindexConn.getContentType()).contains("application/json");
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
            // Two arguments now: the run publishes its per-document counts through the
            // progress listener it is handed, which is what /v1/reindex/status serves.
            when(mockReindexer.reindexAll(any(), any(), any())).thenAnswer(invocation -> {
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

            com.eonmux.excalibur.api.server.ExcaliburDBServer serverWithReindexer =
                new com.eonmux.excalibur.api.server.ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            serverWithReindexer.withSecurity(security);
            serverWithReindexer.withReindexer(mockReindexer, mockResolver);
            serverWithReindexer.start();
            int portWithReindexer = serverWithReindexer.getPort();

            try {
                // Register a source via /v1/ingest
                HttpURLConnection ingestConn = connectTo(portWithReindexer, "POST", "/v1/ingest",
                    "{\"source_type\": \"filesystem\", \"config\": {" +
                    "\"source_id\": \"reindex-exec-test\"," +
                    "\"root_path\": \"" + testDocsDir + "\"" +
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
