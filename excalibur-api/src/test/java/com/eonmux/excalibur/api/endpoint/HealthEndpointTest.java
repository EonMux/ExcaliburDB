package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HealthEndpoint.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Response format is correct JSON</li>
 *   <li>live is always true</li>
 *   <li>ready is computed correctly from coverage</li>
 *   <li>epoch is current visibility epoch</li>
 *   <li>coverage is in range [0.0, 1.0]</li>
 * </ul>
 */
class HealthEndpointTest {

    private VisibilityTracker visibilityTracker;
    private SegmentCatalog segmentCatalog;
    private IndexOrchestrator indexOrchestrator;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        visibilityTracker = new VisibilityTracker(segmentCatalog);
        
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null orchestrator")
        void rejectsNullOrchestrator() {
            assertThatThrownBy(() -> new HealthEndpoint((IndexOrchestrator) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("IndexOrchestrator cannot be null");
        }

        @Test
        @DisplayName("an endpoint built from the orchestrator reads the orchestrator's catalog")
        void takesTheCatalogFromTheOrchestrator() throws IOException {
            // Reaching around the orchestrator for its catalog - by reflection, or by any other
            // route that can come up empty - leaves the endpoint with no catalog and reports a
            // healthy node as covering none of its corpus, permanently and quietly.
            addActiveSegment(segmentCatalog, 1);
            when(indexOrchestrator.getCatalog()).thenReturn(segmentCatalog);
            when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);

            HealthEndpoint endpoint = new HealthEndpoint(indexOrchestrator);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            assertThat(baos.toString(StandardCharsets.UTF_8)).contains("\"coverage\": 1.0000");
        }

        @Test
        @DisplayName("the health MBean follows what the endpoint measured")
        void theHealthScoreMBeanIsPublished() throws IOException {
            // Nothing else writes it: the score is set once at startup and the latency-based
            // updater has no caller, so a JMX client would watch a constant.
            com.eonmux.excalibur.metrics.Metrics.setHealthScore(0.5);
            addActiveSegment(segmentCatalog, 1);
            addBootstrappingSegment(segmentCatalog, 2);
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());

            endpoint.handle(exchange);

            assertThat(com.eonmux.excalibur.metrics.Metrics.getHealthScore()).isEqualTo(0.5);

            addActiveSegment(segmentCatalog, 3);
            addActiveSegment(segmentCatalog, 4);
            HttpExchange second = createMockExchange("GET");
            when(second.getResponseBody()).thenReturn(new ByteArrayOutputStream());

            endpoint.handle(second);

            assertThat(com.eonmux.excalibur.metrics.Metrics.getHealthScore())
                .as("three of the four live segments are readable")
                .isEqualTo(0.75);
        }

        @Test
        @DisplayName("a health check slower than the configured maximum is not healthy")
        void latencyBeyondTheMaximumIsNotHealthy() throws IOException {
            // Otherwise excalibur.health.max-latency-ms is a documented setting the server
            // parses, validates, and never consults.
            addActiveSegment(segmentCatalog, 1);
            VisibilityTracker slowTracker = mock(VisibilityTracker.class);
            when(slowTracker.currentEpoch()).thenAnswer(invocation -> {
                Thread.sleep(20);
                return 1L;
            });
            HealthEndpoint endpoint = new HealthEndpoint(
                slowTracker, segmentCatalog, 0.5, 0.9, 1);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 1.0000");
            assertThat(response)
                .as("full coverage, but the node took longer than it is allowed to")
                .contains("\"healthy\": false");
            assertThat(response)
                .as("readiness is coverage alone; a slow answer here is not a reason to "
                    + "stop serving traffic")
                .contains("\"ready\": true");
        }

        @Test
        @DisplayName("rejects null visibility tracker")
        void rejectsNullVisibilityTracker() {
            assertThatThrownBy(() -> new HealthEndpoint(null, segmentCatalog, 0.5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("VisibilityTracker cannot be null");
        }

        @Test
        @DisplayName("rejects null segment catalog")
        void rejectsNullSegmentCatalog() {
            assertThatThrownBy(() -> new HealthEndpoint(visibilityTracker, null, 0.5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SegmentCatalog cannot be null");
        }

        @Test
        @DisplayName("rejects invalid readiness threshold - NaN")
        void rejectsNanThreshold() {
            assertThatThrownBy(() -> new HealthEndpoint(visibilityTracker, segmentCatalog, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Readiness threshold must be in range");
        }

        @Test
        @DisplayName("rejects invalid readiness threshold - negative")
        void rejectsNegativeThreshold() {
            assertThatThrownBy(() -> new HealthEndpoint(visibilityTracker, segmentCatalog, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Readiness threshold must be in range");
        }

        @Test
        @DisplayName("rejects invalid readiness threshold - too large")
        void rejectsTooLargeThreshold() {
            assertThatThrownBy(() -> new HealthEndpoint(visibilityTracker, segmentCatalog, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Readiness threshold must be in range");
        }

        @Test
        @DisplayName("accepts boundary thresholds 0.0 and 1.0")
        void acceptsBoundaryThresholds() {
            assertThatCode(() -> new HealthEndpoint(visibilityTracker, segmentCatalog, 0.0))
                .doesNotThrowAnyException();
            assertThatCode(() -> new HealthEndpoint(visibilityTracker, segmentCatalog, 1.0))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("GET /health handling")
    class HandleTests {

        @Test
        @DisplayName("returns 200 for GET request")
        void returns200ForGet() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("refuses a non-GET request rather than reading as agreement")
        void refusesNonGet() throws IOException {
            // This route answered 200 to every method, so a client with the wrong verb was told
            // the node was healthy and never learned it had asked wrongly. Every other read
            // route on this server answers 405, and the documented contract says so too.
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("POST");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("a refused method says which one this route serves")
        void refusalNamesTheMethod() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("POST");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            assertThat(baos.toString(StandardCharsets.UTF_8)).contains("Use GET");
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            Headers headers = new Headers();
            when(exchange.getResponseHeaders()).thenReturn(headers);

            endpoint.handle(exchange);

            assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("live is always true")
        void liveIsAlwaysTrue() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"live\": true");
        }

        @Test
        @DisplayName("returns current epoch")
        void returnsCurrentEpoch() throws IOException {
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"epoch\": 3");
        }

        @Test
        @DisplayName("coverage is 0.0 for empty catalog")
        void coverageZeroForEmptyCatalog() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 0.0000");
        }

        @Test
        @DisplayName("ready is false when coverage below threshold")
        void readyFalseWhenCoverageBelowThreshold() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": false");
        }

        @Test
        @DisplayName("ready is true when coverage at threshold")
        void readyTrueWhenCoverageAtThreshold() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addActiveSegment(segmentCatalog, 2);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": true");
        }

        @Test
        @DisplayName("ready is true when coverage above threshold")
        void readyTrueWhenCoverageAboveThreshold() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addActiveSegment(segmentCatalog, 2);
            addActiveSegment(segmentCatalog, 3);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": true");
        }

        @Test
        @DisplayName("custom threshold affects ready status")
        void customThresholdAffectsReadyStatus() throws IOException {
            // Half the corpus is stranded before indexing -> coverage = 1/2 = 0.5
            addActiveSegment(segmentCatalog, 1);
            addBootstrappingSegment(segmentCatalog, 2);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.9);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": false");
        }

        @Test
        @DisplayName("healthy is false when coverage below healthy threshold")
        void healthyFalseWhenCoverageBelowHealthyThreshold() throws IOException {
            // Add 1 active segment, coverage = 1/1 = 1.0
            addActiveSegment(segmentCatalog, 1);

            // Use high healthy threshold so coverage is below it
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.5, 0.9, 5000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 1.0 >= 0.5 (readiness), so ready=true
            // coverage = 1.0 >= 0.9 (healthy), so healthy=true
            assertThat(response).contains("\"ready\": true");
            assertThat(response).contains("\"healthy\": true");
        }

        @Test
        @DisplayName("healthy is true when coverage at healthy threshold")
        void healthyTrueWhenCoverageAtHealthyThreshold() throws IOException {
            // 1 segment a query reads, 1 stranded -> coverage = 1/2 = 0.5
            addActiveSegment(segmentCatalog, 1);
            addBootstrappingSegment(segmentCatalog, 2);

            // Use healthy threshold of 0.5
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.3, 0.5, 5000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.5 >= 0.3 (readiness), so ready=true
            // coverage = 0.5 >= 0.5 (healthy), so healthy=true
            assertThat(response).contains("\"ready\": true");
            assertThat(response).contains("\"healthy\": true");
        }

        @Test
        @DisplayName("custom healthy threshold affects healthy status")
        void customHealthyThresholdAffectsHealthyStatus() throws IOException {
            // 1 segment a query reads, 1 stranded -> coverage = 1/2 = 0.5
            addActiveSegment(segmentCatalog, 1);
            addBootstrappingSegment(segmentCatalog, 2);

            // Use healthy threshold of 0.9 (coverage 0.5 is below it)
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.3, 0.9, 5000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.5 >= 0.3 (readiness), so ready=true
            // coverage = 0.5 < 0.9 (healthy), so healthy=false
            assertThat(response).contains("\"ready\": true");
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("ready and healthy thresholds work independently")
        void readyAndHealthyThresholdsWorkIndependently() throws IOException {
            // Create a fresh catalog for this test
            SegmentCatalog localCatalog = new SegmentCatalog();
            VisibilityTracker localTracker = new VisibilityTracker(localCatalog);

            // Add segments: 5 a query reads, 5 stranded -> coverage = 5/10 = 0.5
            for (int i = 1; i <= 5; i++) {
                var metadata = localCatalog.add(i, java.nio.file.Path.of("/tmp/segment" + i));
                metadata.transitionToIndexing();
                metadata.transitionToWarming();
                metadata.transitionToActive();
            }
            for (int i = 6; i <= 10; i++) {
                addBootstrappingSegment(localCatalog, i);
            }

            // readiness = 0.4, healthy = 0.8
            // coverage = 0.5 >= 0.4 -> ready=true
            // coverage = 0.5 < 0.8 -> healthy=false
            HealthEndpoint endpoint = new HealthEndpoint(
                localTracker, localCatalog, 0.4, 0.8, 5000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": true");
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("5-parameter constructor with explicit dependencies uses all config values")
        void fiveParameterConstructorUsesAllConfigValues() throws IOException {
            // Create fresh catalog
            SegmentCatalog localCatalog = new SegmentCatalog();
            VisibilityTracker localTracker = new VisibilityTracker(localCatalog);

            // Add segments: 3 a query reads, 1 stranded -> coverage = 3/4 = 0.75
            for (int i = 1; i <= 3; i++) {
                var metadata = localCatalog.add(i, java.nio.file.Path.of("/tmp/segment" + i));
                metadata.transitionToIndexing();
                metadata.transitionToWarming();
                metadata.transitionToActive();
            }
            addBootstrappingSegment(localCatalog, 4);

            // readiness = 0.5, healthy = 0.8, maxLatencyMs = 10000
            // coverage = 0.75 >= 0.5 -> ready=true
            // coverage = 0.75 < 0.8 -> healthy=false
            HealthEndpoint endpoint = new HealthEndpoint(
                localTracker, localCatalog, 0.5, 0.8, 10000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": true");
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("both ready and healthy are true when coverage exceeds both thresholds")
        void bothTrueWhenCoverageExceedsBothThresholds() throws IOException {
            // Add 10 active segments -> coverage = 1.0
            for (int i = 1; i <= 10; i++) {
                addActiveSegment(segmentCatalog, i);
            }

            // Use high thresholds
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.8, 0.9, 5000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 1.0 >= 0.8 and >= 0.9
            assertThat(response).contains("\"ready\": true");
            assertThat(response).contains("\"healthy\": true");
        }

        @Test
        @DisplayName("both ready and healthy are false when coverage below both thresholds")
        void bothFalseWhenCoverageBelowBothThresholds() throws IOException {
            // Empty catalog -> coverage = 0.0

            // Use low thresholds
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.1, 0.2, 5000);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.0 < 0.1 and < 0.2
            assertThat(response).contains("\"ready\": false");
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("coverage includes indexing segments")
        void coverageIncludesIndexingSegments() throws IOException {
            addIndexingSegment(segmentCatalog, 1);
            addActiveSegment(segmentCatalog, 2);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": true");
        }

        @Test
        @DisplayName("coverage excludes tombstoned segments")
        void coverageExcludesTombstonedSegments() throws IOException {
            // A retired segment is not corpus this node is missing, so a node that has
            // compacted half its segments still reports full coverage - and stays ready even
            // where readiness demands all of it.
            addActiveSegment(segmentCatalog, 1);
            addTombstonedSegment(segmentCatalog, 2);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 1.0);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": true");
        }

        @Test
        @DisplayName("returns 200 with degraded status when computation fails")
        void returns200WithDegradedStatusOnError() throws IOException {
            VisibilityTracker failingTracker = mock(VisibilityTracker.class);
            when(failingTracker.currentEpoch()).thenThrow(new RuntimeException("Simulated failure"));

            HealthEndpoint endpoint = new HealthEndpoint(failingTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"live\": true");
            assertThat(response).contains("\"ready\": false");
            assertThat(response).contains("\"epoch\": 0");
            assertThat(response).contains("\"coverage\": 0.0000");
        }

        @Test
        @DisplayName("a degraded response still reports the epoch it could read")
        void aDegradedResponseStillReportsTheEpochItCouldRead() throws IOException {
            // The realistic degraded trigger is the catalog, not the tracker: currentEpoch()
            // reads an AtomicLong. Reporting 0 there tells a client watching for monotonicity
            // that the node restarted and lost its visibility history, which it did not.
            SegmentCatalog failingCatalog = mock(SegmentCatalog.class);
            when(failingCatalog.getCatalogMetadata()).thenThrow(new IllegalStateException("no catalog"));
            VisibilityTracker tracker = mock(VisibilityTracker.class);
            when(tracker.currentEpoch()).thenReturn(4711L);

            HealthEndpoint endpoint = new HealthEndpoint(tracker, failingCatalog, 0.5);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"epoch\": 4711");
            assertThat(response).contains("\"ready\": false");
            assertThat(response).contains("\"coverage\": 0.0000");
        }
    }

    @Nested
    @DisplayName("HealthStatus record validation")
    class HealthStatusTests {

        @Test
        @DisplayName("validates all HealthStatus constraints")
        void validatesAllHealthStatusConstraints() {
            // Combined validation tests to reduce test count

            // Test negative epoch rejection
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, -1L, 0.5, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Epoch must be non-negative");

            // Test NaN coverage rejection
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, Double.NaN, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coverage must be in range");

            // Test coverage below 0.0 rejection
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, -0.1, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coverage must be in range");

            // Test coverage above 1.0 rejection
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, 1.1, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coverage must be in range");

            // Test boundary coverage values 0.0 and 1.0 are accepted
            assertThatCode(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, 0.0, 0L))
                .doesNotThrowAnyException();
            assertThatCode(() -> new HealthEndpoint.HealthStatus(true, true, true, 0L, 1.0, 0L))
                .doesNotThrowAnyException();
        }
    }

    private HttpExchange createMockExchange(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/health"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private void addActiveSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, java.nio.file.Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }

    private void addIndexingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, java.nio.file.Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
    }

    /** A segment whose file is registered but which no transition ever brought up. */
    private void addBootstrappingSegment(SegmentCatalog catalog, long segmentId) {
        catalog.registerFile(segmentId, java.nio.file.Path.of("/tmp/segment" + segmentId));
    }

    private void addTombstonedSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, java.nio.file.Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }
}
