package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StatsEndpoint.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Response format is correct JSON</li>
 *   <li>All segment counts are non-negative</li>
 *   <li>Coverage is in range [0.0, 1.0]</li>
 *   <li>Epoch is non-negative</li>
 *   <li>Volume bytes are non-negative</li>
 *   <li>HNSW queue depth is accurate</li>
 * </ul>
 */
class StatsEndpointTest {

    private VisibilityTracker visibilityTracker;
    private SegmentCatalog segmentCatalog;
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();
        visibilityTracker = new VisibilityTracker(segmentCatalog);
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);

        when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
        when(indexOrchestrator.getHnswQueueDepth()).thenReturn(0L);
        when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
        when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(0L);
        when(indexOrchestrator.getPendingCuckooDeletions()).thenReturn(0);
        when(indexOrchestrator.getTotalCuckooDeletions()).thenReturn(0L);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null orchestrator")
        void rejectsNullOrchestrator() {
            assertThatThrownBy(() -> new StatsEndpoint((IndexOrchestrator) null, storageEngine))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("IndexOrchestrator cannot be null");
        }

        @Test
        @DisplayName("rejects null storage engine")
        void rejectsNullStorageEngine() {
            assertThatThrownBy(() -> new StatsEndpoint(indexOrchestrator, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StorageEngine cannot be null");
        }

        @Test
        @DisplayName("rejects null visibility tracker in explicit constructor")
        void rejectsNullVisibilityTracker() {
            assertThatThrownBy(() -> new StatsEndpoint(null, segmentCatalog, indexOrchestrator, storageEngine))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("VisibilityTracker cannot be null");
        }

        @Test
        @DisplayName("rejects null segment catalog in explicit constructor")
        void rejectsNullSegmentCatalog() {
            assertThatThrownBy(() -> new StatsEndpoint(visibilityTracker, null, indexOrchestrator, storageEngine))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SegmentCatalog cannot be null");
        }

        @Test
        @DisplayName("accepts all required dependencies")
        void acceptsRequiredDependencies() {
            assertThatCode(() -> new StatsEndpoint(indexOrchestrator, storageEngine))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("GET /v1/stats handling")
    class HandleTests {

        @Test
        @DisplayName("returns 200 for GET request")
        void returns200ForGet() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("returns 405 for non-GET request")
        void returns405ForNonGet() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("POST");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            Headers headers = new Headers();
            when(exchange.getResponseHeaders()).thenReturn(headers);

            endpoint.handle(exchange);

            assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("returns segment counts")
        void returnsSegmentCounts() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addIndexingSegment(segmentCatalog, 2);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"segments\":");
            assertThat(response).contains("\"total\": 2");
            assertThat(response).contains("\"active\": 1");
            assertThat(response).contains("\"indexing\": 1");
        }

        @Test
        @DisplayName("returns entry count")
        void returnsEntryCount() throws IOException {
            var metadata = segmentCatalog.add(1, Path.of("/tmp/segment1"));
            metadata.transitionToIndexing();
            metadata.setEntryCount(5000);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"entries\":");
            assertThat(response).contains("\"total\": 5000");
        }

        @Test
        @DisplayName("returns HNSW queue depth")
        void returnsHnswQueueDepth() throws IOException {
            when(indexOrchestrator.getHnswQueueDepth()).thenReturn(25000L);
            when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
            when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(1000000L);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"hnsw\":");
            assertThat(response).contains("\"queueDepth\": 25000");
            assertThat(response).contains("\"queueDepthLimit\": 100000");
            assertThat(response).contains("\"vectorsIntegrated\": 1000000");
        }

        @Test
        @DisplayName("returns Cuckoo filter stats")
        void returnsCuckooStats() throws IOException {
            when(indexOrchestrator.getPendingCuckooDeletions()).thenReturn(50);
            when(indexOrchestrator.getTotalCuckooDeletions()).thenReturn(500L);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"cuckoo\":");
            assertThat(response).contains("\"pendingDeletions\": 50");
            assertThat(response).contains("\"totalDeletions\": 500");
            assertThat(response).contains("\"avgFpRate\":");
            assertThat(response).contains("\"maxFpRate\":");
        }

        @Test
        @DisplayName("returns visibility epoch")
        void returnsVisibilityEpoch() throws IOException {
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"visibility\":");
            assertThat(response).contains("\"epoch\": 3");
        }

        @Test
        @DisplayName("returns coverage for empty catalog")
        void returnsCoverageForEmptyCatalog() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 0.0000");
        }

        @Test
        @DisplayName("returns coverage for full catalog")
        void returnsCoverageForFullCatalog() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addActiveSegment(segmentCatalog, 2);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 1.0000");
        }

        @Test
        @DisplayName("returns coverage for partial catalog")
        void returnsCoverageForPartialCatalog() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addIndexingSegment(segmentCatalog, 2);
            addTombstonedSegment(segmentCatalog, 3);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 0.6667");
        }

        @Test
        @DisplayName("includes tombstoned segments in coverage denominator")
        void includesTombstonedInCoverageDenominator() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addTombstonedSegment(segmentCatalog, 2);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 0.5000");
        }

        @Test
        @DisplayName("includes all queryable states in coverage")
        void includesAllQueryableStatesInCoverage() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addIndexingSegment(segmentCatalog, 2);
            addWarmingSegment(segmentCatalog, 3);
            addCompactingSegment(segmentCatalog, 4);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 1.0000");
        }
    }

    @Nested
    @DisplayName("Record validation - combined")
    class RecordValidationTests {

        @Test
        @DisplayName("validates all record constraints in one test")
        void validatesAllRecordConstraints() {
            // SegmentStats - negative values rejected
            assertThatThrownBy(() -> new StatsEndpoint.SegmentStats(-1, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SegmentStats(0, -1, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.SegmentStats(0, 0, 0, 0, 0, 0, 0))
                .doesNotThrowAnyException();

            // EntryStats - negative rejected
            assertThatThrownBy(() -> new StatsEndpoint.EntryStats(-1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.EntryStats(0))
                .doesNotThrowAnyException();

            // VolumeStats - all fields validated
            assertThatThrownBy(() -> new StatsEndpoint.VolumeStats(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.VolumeStats(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.VolumeStats(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.VolumeStats(0, 0, 0))
                .doesNotThrowAnyException();

            // HnswStats - all fields validated
            assertThatThrownBy(() -> new StatsEndpoint.HnswStats(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.HnswStats(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.HnswStats(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.HnswStats(0, 0, 0))
                .doesNotThrowAnyException();

            // CuckooStats - all fields validated
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(-1, 0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, -1, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, 0, -0.1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, 0, 0.0, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, 0, 1.1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, 0, 0.0, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, 0, Double.NaN, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.CuckooStats(0, 0, 0.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.CuckooStats(0, 0, 0.0, 0.0))
                .doesNotThrowAnyException();
            assertThatCode(() -> new StatsEndpoint.CuckooStats(0, 0, 1.0, 1.0))
                .doesNotThrowAnyException();

            // VisibilityStats - all fields validated
            assertThatThrownBy(() -> new StatsEndpoint.VisibilityStats(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.VisibilityStats(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.VisibilityStats(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.VisibilityStats(0, 0, 0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validates SystemStats constraints")
        void validatesSystemStatsConstraints() {
            StatsEndpoint.SegmentStats segments = new StatsEndpoint.SegmentStats(0, 0, 0, 0, 0, 0, 0);
            StatsEndpoint.EntryStats entries = new StatsEndpoint.EntryStats(0);
            StatsEndpoint.VolumeStats volumes = new StatsEndpoint.VolumeStats(0, 0, 0);
            StatsEndpoint.HnswStats hnsw = new StatsEndpoint.HnswStats(0, 0, 0);
            StatsEndpoint.CuckooStats cuckoo = new StatsEndpoint.CuckooStats(0, 0, 0.0, 0.0);
            StatsEndpoint.VisibilityStats visibility = new StatsEndpoint.VisibilityStats(0, 0, 0);

            // Coverage validation
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 0.0))
                .doesNotThrowAnyException();
            assertThatCode(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 1.0))
                .doesNotThrowAnyException();

            // Null validation
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                null, entries, volumes, hnsw, cuckoo, visibility, 0.5))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, null, volumes, hnsw, cuckoo, visibility, 0.5))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, null, hnsw, cuckoo, visibility, 0.5))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, null, cuckoo, visibility, 0.5))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, null, visibility, 0.5))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StatsEndpoint.SystemStats(
                segments, entries, volumes, hnsw, cuckoo, null, 0.5))
                .isInstanceOf(NullPointerException.class);
        }
    }

    private HttpExchange createMockExchange(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/stats"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private void addActiveSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }

    private void addIndexingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
    }

    private void addWarmingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
    }

    private void addCompactingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToCompacting();
    }

    private void addTombstonedSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }
}
