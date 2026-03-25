package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
        @DisplayName("returns 200 for non-GET request (process is alive)")
        void returns200ForNonGet() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("POST");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("live is true even for non-GET request")
        void liveIsTrueForNonGet() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
            HttpExchange exchange = createMockExchange("POST");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"live\": true");
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
            addActiveSegment(segmentCatalog, 1);
            addTombstonedSegment(segmentCatalog, 2);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.9);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"ready\": false");
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
            addActiveSegment(segmentCatalog, 1);
            addTombstonedSegment(segmentCatalog, 2);

            HealthEndpoint endpoint = new HealthEndpoint(visibilityTracker, segmentCatalog, 0.5);
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
    }

    @Nested
    @DisplayName("HealthStatus record")
    class HealthStatusTests {

        @Test
        @DisplayName("rejects negative epoch")
        void rejectsNegativeEpoch() {
            // HealthStatus(live, ready, healthy, epoch, coverage, latencyMs)
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, -1L, 0.5, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Epoch must be non-negative");
        }

        @Test
        @DisplayName("rejects NaN coverage")
        void rejectsNanCoverage() {
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, Double.NaN, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coverage must be in range");
        }

        @Test
        @DisplayName("rejects coverage below 0.0")
        void rejectsCoverageBelowZero() {
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, -0.1, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coverage must be in range");
        }

        @Test
        @DisplayName("rejects coverage above 1.0")
        void rejectsCoverageAboveOne() {
            assertThatThrownBy(() -> new HealthEndpoint.HealthStatus(true, false, false, 0L, 1.1, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coverage must be in range");
        }

        @Test
        @DisplayName("accepts boundary coverage values 0.0 and 1.0")
        void acceptsBoundaryCoverage() {
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

    private void addTombstonedSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, java.nio.file.Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }
}
