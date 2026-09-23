package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.HealthEndpoint;
import com.eonmux.excalibur.api.endpoint.StatsEndpoint;
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
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
        when(indexOrchestrator.shutdown(any())).thenReturn(true);
        when(queryPipeline.shutdown(any())).thenReturn(true);
        when(storageEngine.stopBackgroundWork(any())).thenReturn(true);

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
            // Verify that indexOrchestrator.close(Duration) was called during server shutdown
            // This ensures the epoch tick scheduler is stopped cleanly
            verify(indexOrchestrator).close(any());
        }

        @Test
        @DisplayName("a clean stop releases storage and the chunk index")
        void aCleanStopReleasesEverything() {
            server.stop(0);

            assertThat(server.isStorageStillInUse()).isFalse();
            assertThat(server.isChunkIndexStillInUse()).isFalse();
            assertThat(server.isGraphsStillInUse()).isFalse();
        }

        @Test
        @DisplayName("segment workers that did not stop hold storage and nothing else")
        void workersStillRunningHoldStorage() {
            when(indexOrchestrator.shutdown(any())).thenReturn(false);

            server.stop(0);

            assertThat(server.isStorageStillInUse()).isTrue();
            assertThat(server.isChunkIndexStillInUse()).isFalse();
            assertThat(server.isGraphsStillInUse()).isFalse();
        }

        @Test
        @DisplayName("a split or lifecycle check that did not stop holds storage and nothing else")
        void storageBackgroundWorkStillRunningHoldsStorage() {
            when(storageEngine.stopBackgroundWork(any())).thenReturn(false);

            server.stop(0);

            assertThat(server.isStorageStillInUse()).isTrue();
            assertThat(server.isChunkIndexStillInUse()).isFalse();
            assertThat(server.isGraphsStillInUse()).isFalse();
        }

        @Test
        @DisplayName("a drain step that throws leaves storage and the chunk index held")
        void aFailedDrainHoldsEverything() {
            // Whatever that step was meant to stop may still be running; closing storage
            // underneath it would crash the process, where leaving it open costs a log replay.
            doThrow(new IllegalStateException("workers refused to stop"))
                .when(indexOrchestrator).shutdown(any());

            assertThatThrownBy(() -> server.stop(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workers refused to stop");
            assertThat(server.isRunning()).isFalse();
            assertThat(server.isStorageStillInUse()).isTrue();
            assertThat(server.isChunkIndexStillInUse()).isTrue();
            assertThat(server.isGraphsStillInUse()).isTrue();
        }

        /**
         * An orchestrator stubbed as the one this class starts over, for a server that is to
         * fail its own start.
         */
        private IndexOrchestrator orchestratorFor() {
            IndexOrchestrator orchestrator = mock(IndexOrchestrator.class);
            when(orchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
            when(orchestrator.getVisibilityTracker())
                .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(segmentCatalog));
            return orchestrator;
        }

        @Test
        @DisplayName("a failed start whose workers did not stop leaves storage held")
        void aFailedStartHoldsStorageForWorkersStillRunning() {
            // A failed start never sets running, so the stop that follows drains nothing and
            // would leave the flags at their defaults - storage free - with workers the start
            // brought up still reading segments mapped out of the arena about to close.
            IndexOrchestrator orchestrator = orchestratorFor();
            when(orchestrator.getEpochTickScheduler())
                .thenThrow(new IllegalStateException("scheduler missing"));
            when(orchestrator.shutdown(any())).thenReturn(false);
            ExcaliburDBServer failing =
                new ExcaliburDBServer(0, orchestrator, storageEngine, queryPipeline);

            assertThatThrownBy(failing::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler missing");
            failing.stop(0);

            assertThat(failing.isRunning()).isFalse();
            assertThat(failing.isStorageStillInUse()).isTrue();
            // Waited for as a stop would have waited for them - out of a default budget's
            // share for drains - and no longer, however long a worker takes.
            Duration drainsShare = Duration.ofSeconds(ShutdownBudget.DEFAULT_TIMEOUT_SECONDS)
                .minus(ShutdownBudget.DURABILITY_RESERVE);
            ArgumentCaptor<Duration> shutdownWait = ArgumentCaptor.forClass(Duration.class);
            ArgumentCaptor<Duration> closeWait = ArgumentCaptor.forClass(Duration.class);
            verify(orchestrator).shutdown(shutdownWait.capture());
            verify(orchestrator).close(closeWait.capture());
            assertThat(shutdownWait.getValue()).isPositive().isLessThanOrEqualTo(drainsShare);
            assertThat(closeWait.getValue()).isLessThanOrEqualTo(shutdownWait.getValue());
        }

        @Test
        @DisplayName("a failed start whose workers stopped leaves storage free")
        void aFailedStartReleasesStorageOnceTheWorkersEnd() {
            IndexOrchestrator orchestrator = orchestratorFor();
            when(orchestrator.getEpochTickScheduler())
                .thenThrow(new IllegalStateException("scheduler missing"));
            when(orchestrator.shutdown(any())).thenReturn(true);
            ExcaliburDBServer failing =
                new ExcaliburDBServer(0, orchestrator, storageEngine, queryPipeline);

            assertThatThrownBy(failing::start).isInstanceOf(IllegalStateException.class);

            assertThat(failing.isStorageStillInUse()).isFalse();
            verify(orchestrator).close(any());
        }

        @Test
        @DisplayName("an orchestrator whose own start threw part-way is still stopped")
        void aPartialOrchestratorStartIsStopped() {
            // Workers ahead of the one that failed are already running.
            IndexOrchestrator orchestrator = orchestratorFor();
            when(orchestrator.start()).thenThrow(new IllegalStateException("worker failed"));
            when(orchestrator.shutdown(any())).thenReturn(false);
            ExcaliburDBServer failing =
                new ExcaliburDBServer(0, orchestrator, storageEngine, queryPipeline);

            assertThatThrownBy(failing::start).hasMessageContaining("worker failed");

            verify(orchestrator).shutdown(any());
            verify(orchestrator).close(any());
            assertThat(failing.isStorageStillInUse()).isTrue();
        }

        @Test
        @DisplayName("a stop that throws after a failed start is attached, not substituted")
        void aThrowingStopIsSuppressed() {
            IndexOrchestrator orchestrator = orchestratorFor();
            when(orchestrator.getEpochTickScheduler())
                .thenThrow(new IllegalStateException("scheduler missing"));
            when(orchestrator.shutdown(any())).thenThrow(new IllegalStateException("stop failed"));
            doThrow(new IllegalStateException("close failed")).when(orchestrator).close(any());
            ExcaliburDBServer failing =
                new ExcaliburDBServer(0, orchestrator, storageEngine, queryPipeline);

            assertThatThrownBy(failing::start)
                .hasMessageContaining("scheduler missing")
                .satisfies(e -> assertThat(e.getSuppressed())
                    .extracting(Throwable::getMessage)
                    .containsExactly("stop failed", "close failed"));
            assertThat(failing.isStorageStillInUse())
                .as("nothing said the workers ended")
                .isTrue();
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
        @DisplayName("health rejects POST, like every other read route")
        void healthRejectsPost() throws IOException {
            HttpURLConnection conn = connect("POST", "/health");
            assertThat(conn.getResponseCode()).isEqualTo(405);
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
            com.eonmux.excalibur.query.pipeline.QueryPipeline.LlmScorer mockScorer =
                mock(com.eonmux.excalibur.query.pipeline.QueryPipeline.LlmScorer.class);

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
            com.eonmux.excalibur.query.pipeline.QueryPipeline.LlmScorer mockScorer =
                mock(com.eonmux.excalibur.query.pipeline.QueryPipeline.LlmScorer.class);

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
            com.eonmux.excalibur.ingest.pipeline.DocumentReindexer mockReindexer =
                mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class);
            com.eonmux.excalibur.ingest.source.SourceResolver mockResolver =
                mock(com.eonmux.excalibur.ingest.source.SourceResolver.class);

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

            // Null disables reindexing; POST /v1/reindex then answers 503
            assertThatCode(() -> testServer.withReindexer(null, null))
                .doesNotThrowAnyException();

            testServer.stop(0);
        }

        @Test
        @DisplayName("rejects partial reindexer configuration (only one null)")
        void rejectsPartialReindexerConfig() throws IOException {
            ExcaliburDBServer testServer = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

            com.eonmux.excalibur.ingest.pipeline.DocumentReindexer mockReindexer =
                mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class);
            com.eonmux.excalibur.ingest.source.SourceResolver mockResolver =
                mock(com.eonmux.excalibur.ingest.source.SourceResolver.class);

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
            com.eonmux.excalibur.ingest.pipeline.DocumentReindexer mockReindexer =
                mock(com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.class);
            com.eonmux.excalibur.ingest.source.SourceResolver mockResolver =
                mock(com.eonmux.excalibur.ingest.source.SourceResolver.class);

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
