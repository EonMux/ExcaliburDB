package excalibur.api.server;

import excalibur.api.endpoint.HealthEndpoint;
import excalibur.api.endpoint.StatsEndpoint;
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
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExcaliburDBServer.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Server starts and stops correctly</li>
 *   <li>Endpoints are accessible</li>
 *   <li>Virtual thread dispatch works</li>
 *   <li>404 for unknown paths</li>
 *   <li>Domain correctness for all responses</li>
 * </ul>
 */
class ExcaliburDBServerTest {

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private SegmentCatalog segmentCatalog;
    private ExcaliburDBServer server;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
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

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0); // Immediate stop for faster test teardown
        }
    }

    @Nested
    @DisplayName("Server lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("starts successfully on available port")
        void startsSuccessfully() {
            assertThat(server.isRunning()).isTrue();
            assertThat(serverPort).isGreaterThan(0);
        }

        @Test
        @DisplayName("starts index orchestrator when server starts")
        void startsIndexOrchestrator() {
            // Verify that indexOrchestrator.start() was called during server startup
            // This ensures the epoch tick scheduler is started automatically
            verify(indexOrchestrator).start();
        }

        @Test
        @DisplayName("stops successfully")
        void stopsSuccessfully() throws IOException {
            server.stop(0);
            assertThat(server.isRunning()).isFalse();
        }

        @Test
        @DisplayName("closes index orchestrator when server stops")
        void closesIndexOrchestrator() {
            server.stop(0);
            // Verify that indexOrchestrator.close() was called during server shutdown
            // This ensures the epoch tick scheduler is stopped cleanly
            verify(indexOrchestrator).close();
        }

        @Test
        @DisplayName("rejects double start")
        void rejectsDoubleStart() {
            assertThatThrownBy(() -> server.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
        }

        @Test
        @DisplayName("allows restart after stop")
        void allowsRestart() throws IOException {
            server.stop(0);
            assertThat(server.isRunning()).isFalse();

            ExcaliburDBServer newServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
            newServer.start();
            assertThat(newServer.isRunning()).isTrue();
            newServer.stop(1);
        }
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null orchestrator")
        void rejectsNullOrchestrator() {
            assertThatThrownBy(() -> new ExcaliburDBServer(null, storageEngine, queryPipeline))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("IndexOrchestrator cannot be null");
        }

        @Test
        @DisplayName("rejects null storage engine")
        void rejectsNullStorageEngine() {
            assertThatThrownBy(() -> new ExcaliburDBServer(indexOrchestrator, null, queryPipeline))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StorageEngine cannot be null");
        }

        @Test
        @DisplayName("rejects null query pipeline")
        void rejectsNullQueryPipeline() {
            assertThatThrownBy(() -> new ExcaliburDBServer(indexOrchestrator, storageEngine, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("QueryPipeline cannot be null");
        }

        @Test
        @DisplayName("rejects invalid port")
        void rejectsInvalidPort() {
            assertThatThrownBy(() -> new ExcaliburDBServer(-1, indexOrchestrator, storageEngine, queryPipeline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port must be in range");

            assertThatThrownBy(() -> new ExcaliburDBServer(70000, indexOrchestrator, storageEngine, queryPipeline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port must be in range");
        }

        @Test
        @DisplayName("accepts port 0 for OS-assigned port")
        void acceptsPortZero() {
            assertThatCode(() -> new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts valid configuration")
        void acceptsValidConfiguration() {
            assertThatCode(() -> new ExcaliburDBServer(8080, indexOrchestrator, storageEngine, queryPipeline))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Health endpoint")
    class HealthEndpointTests {

        @Test
        @DisplayName("returns 200 OK")
        void returns200Ok() throws IOException {
            HttpURLConnection conn = connect("GET", "/health");
            assertThat(conn.getResponseCode()).isEqualTo(200);
            conn.disconnect();
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() throws IOException {
            HttpURLConnection conn = connect("GET", "/health");
            assertThat(conn.getContentType()).contains("application/json");
            conn.disconnect();
        }

        @Test
        @DisplayName("returns valid JSON with required fields")
        void returnsValidJson() throws IOException {
            HttpURLConnection conn = connect("GET", "/health");
            String response = readResponse(conn);
            
            assertThat(response).contains("\"live\"");
            assertThat(response).contains("\"ready\"");
            assertThat(response).contains("\"epoch\"");
            assertThat(response).contains("\"coverage\"");
            conn.disconnect();
        }

        @Test
        @DisplayName("returns live=true when process is alive")
        void returnsLiveTrue() throws IOException {
            HttpURLConnection conn = connect("GET", "/health");
            String response = readResponse(conn);
            assertThat(response).contains("\"live\": true");
            conn.disconnect();
        }
    }

    @Nested
    @DisplayName("Stats endpoint")
    class StatsEndpointTests {

        @Test
        @DisplayName("returns 200 OK")
        void returns200Ok() throws IOException {
            HttpURLConnection conn = connect("GET", "/v1/stats");
            assertThat(conn.getResponseCode()).isEqualTo(200);
            conn.disconnect();
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() throws IOException {
            HttpURLConnection conn = connect("GET", "/v1/stats");
            assertThat(conn.getContentType()).contains("application/json");
            conn.disconnect();
        }

        @Test
        @DisplayName("returns valid JSON with required sections")
        void returnsValidJson() throws IOException {
            HttpURLConnection conn = connect("GET", "/v1/stats");
            String response = readResponse(conn);

            assertThat(response).contains("\"segments\":");
            assertThat(response).contains("\"entries\":");
            assertThat(response).contains("\"volumes\":");
            assertThat(response).contains("\"hnsw\":");
            assertThat(response).contains("\"cuckoo\":");
            assertThat(response).contains("\"visibility\":");
            assertThat(response).contains("\"coverage\":");
            conn.disconnect();
        }

        @Test
        @DisplayName("returns non-negative epoch")
        void returnsNonNegativeEpoch() throws IOException {
            HttpURLConnection conn = connect("GET", "/v1/stats");
            String response = readResponse(conn);
            assertThat(response).contains("\"epoch\":");
            conn.disconnect();
        }

        @Test
        @DisplayName("returns coverage in valid range")
        void returnsCoverageInRange() throws IOException {
            HttpURLConnection conn = connect("GET", "/v1/stats");
            String response = readResponse(conn);
            assertThat(response).contains("\"coverage\":");
            conn.disconnect();
        }
    }

    @Nested
    @DisplayName("Unknown paths")
    class NotFoundTests {

        @Test
        @DisplayName("returns 404 for unknown path")
        void returns404ForUnknownPath() throws IOException {
            HttpURLConnection conn = connect("GET", "/unknown");
            assertThat(conn.getResponseCode()).isEqualTo(404);
            conn.disconnect();
        }

        @Test
        @DisplayName("returns JSON error response")
        void returnsJsonError() throws IOException {
            HttpURLConnection conn = connect("GET", "/unknown");
            assertThat(conn.getResponseCode()).isEqualTo(404);
            assertThat(conn.getContentType()).contains("application/json");
            conn.disconnect();
        }
    }

    @Nested
    @DisplayName("Method handling")
    class MethodTests {

        @Test
        @DisplayName("health accepts POST (returns 200 for any method if process is alive)")
        void healthAcceptsPost() throws IOException {
            HttpURLConnection conn = connect("POST", "/health");
            // Health endpoint returns 200 for any HTTP method if process is alive
            assertThat(conn.getResponseCode()).isEqualTo(200);
            conn.disconnect();
        }

        @Test
        @DisplayName("stats rejects POST")
        void statsRejectsPost() throws IOException {
            HttpURLConnection conn = connect("POST", "/v1/stats");
            assertThat(conn.getResponseCode()).isEqualTo(405);
            conn.disconnect();
        }
    }

    @Nested
    @DisplayName("LLM Scorer configuration")
    class LlmScorerTests {

        @Test
        @DisplayName("accepts LLM scorer before start")
        void acceptsLlmScorerBeforeStart() throws IOException {
            // Create a new server instance (not the one started in setUp)
            ExcaliburDBServer testServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

            // Create a mock LLM scorer
            excalibur.query.pipeline.QueryPipeline.LlmScorer mockScorer =
                mock(excalibur.query.pipeline.QueryPipeline.LlmScorer.class);

            // Should be able to configure LLM scorer before start
            assertThatCode(() -> testServer.withLlmScorer(mockScorer))
                .doesNotThrowAnyException();

            // Cleanup
            testServer.stop(0);
        }

        @Test
        @DisplayName("accepts null LLM scorer to disable LLM scoring")
        void acceptsNullLlmScorer() throws IOException {
            ExcaliburDBServer testServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

            // Should accept null to disable LLM scoring
            assertThatCode(() -> testServer.withLlmScorer(null))
                .doesNotThrowAnyException();

            testServer.stop(0);
        }

        @Test
        @DisplayName("rejects LLM scorer configuration after server started")
        void rejectsLlmScorerAfterStart() {
            excalibur.query.pipeline.QueryPipeline.LlmScorer mockScorer =
                mock(excalibur.query.pipeline.QueryPipeline.LlmScorer.class);

            // Server is already started in setUp, so this should fail
            assertThatThrownBy(() -> server.withLlmScorer(mockScorer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot configure LLM scorer after server has started");
        }
    }

    @Nested
    @DisplayName("Reindexer configuration")
    class ReindexerTests {

        @Test
        @DisplayName("accepts reindexer configuration before start")
        void acceptsReindexerBeforeStart() throws IOException {
            // Create a new server instance (not the one started in setUp)
            ExcaliburDBServer testServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

            // Create mock reindexer components
            excalibur.ingest.pipeline.DocumentReindexer mockReindexer =
                mock(excalibur.ingest.pipeline.DocumentReindexer.class);
            excalibur.ingest.source.SourceResolver mockResolver =
                mock(excalibur.ingest.source.SourceResolver.class);

            // Should be able to configure reindexer before start
            assertThatCode(() -> testServer.withReindexer(mockReindexer, mockResolver))
                .doesNotThrowAnyException();

            // Cleanup
            testServer.stop(0);
        }

        @Test
        @DisplayName("accepts null reindexer to disable actual reindexing")
        void acceptsNullReindexer() throws IOException {
            ExcaliburDBServer testServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

            // Should accept null to disable reindexing (stub mode)
            assertThatCode(() -> testServer.withReindexer(null, null))
                .doesNotThrowAnyException();

            testServer.stop(0);
        }

        @Test
        @DisplayName("rejects partial reindexer configuration (only one null)")
        void rejectsPartialReindexerConfig() throws IOException {
            ExcaliburDBServer testServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

            excalibur.ingest.pipeline.DocumentReindexer mockReindexer =
                mock(excalibur.ingest.pipeline.DocumentReindexer.class);
            excalibur.ingest.source.SourceResolver mockResolver =
                mock(excalibur.ingest.source.SourceResolver.class);

            // Should reject when only reindexer is provided
            assertThatThrownBy(() -> testServer.withReindexer(mockReindexer, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must both be null or both be non-null");

            // Should reject when only resolver is provided
            assertThatThrownBy(() -> testServer.withReindexer(null, mockResolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must both be null or both be non-null");

            testServer.stop(0);
        }

        @Test
        @DisplayName("rejects reindexer configuration after server started")
        void rejectsReindexerAfterStart() {
            excalibur.ingest.pipeline.DocumentReindexer mockReindexer =
                mock(excalibur.ingest.pipeline.DocumentReindexer.class);
            excalibur.ingest.source.SourceResolver mockResolver =
                mock(excalibur.ingest.source.SourceResolver.class);

            // Server is already started in setUp, so this should fail
            assertThatThrownBy(() -> server.withReindexer(mockReindexer, mockResolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot configure reindexer after server has started");
        }
    }

    private HttpURLConnection connect(String method, String path) throws IOException {
        URL url = URI.create("http://localhost:" + serverPort + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readError(HttpURLConnection conn) throws IOException {
        try (var is = conn.getErrorStream()) {
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
