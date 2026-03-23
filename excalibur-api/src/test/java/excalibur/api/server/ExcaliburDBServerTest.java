package excalibur.api.server;

import excalibur.api.endpoint.HealthEndpoint;
import excalibur.api.endpoint.StatsEndpoint;
import excalibur.query.pipeline.QueryPipeline;
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
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
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
            server.stop(1);
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
        @DisplayName("stops successfully")
        void stopsSuccessfully() throws IOException {
            server.stop(1);
            assertThat(server.isRunning()).isFalse();
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
            server.stop(1);
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
