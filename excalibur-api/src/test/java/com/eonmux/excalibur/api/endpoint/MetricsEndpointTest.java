package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.metrics.ResponseCounters;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That this node offers something a monitoring system can scrape.
 *
 * <p>It offered nothing. Seven MBeans were registered, including a full query-latency
 * histogram, and the JMX route they are reachable by needs a
 * {@code -Dcom.sun.management.jmxremote} flag that appears in no build file, script or
 * document in the repository. {@code /v1/stats} carried segments, volumes and filters and no
 * latency, no throughput and no error rate. There was no {@code /metrics} route.</p>
 */
@DisplayName("Metrics exposition")
class MetricsEndpointTest {

    private SegmentCatalog catalog;
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryRegistry queryRegistry;
    private ServiceReadiness readiness;
    private NodeStatsCollector collector;

    @BeforeEach
    void setUp() {
        ResponseCounters.reset();
        catalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        when(indexOrchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
        storageEngine = mock(StorageEngine.class);
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(new VisibilityTracker(catalog));
        when(indexOrchestrator.isRunning()).thenReturn(true);
        when(indexOrchestrator.getHnswQueueDepth()).thenReturn(42L);
        when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100_000);
        when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(900L);
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(storageEngine.segmentsWithoutVectorIndex()).thenReturn(Map.of());
        when(storageEngine.segmentsWithoutVectorIndexCount()).thenReturn(0);

        queryRegistry = new QueryRegistry();
        readiness = new ServiceReadiness();
        collector = new NodeStatsCollector(indexOrchestrator, storageEngine, null);
    }

    @Nested
    @DisplayName("Transport")
    class Transport {

        @Test
        @DisplayName("answers with the content type a scraper negotiates on")
        void servesTheExpositionContentType() throws IOException {
            HttpExchange exchange = exchange("GET");
            endpoint().handle(exchange);

            assertThat(exchange.getResponseHeaders().getFirst("Content-Type"))
                .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
        }

        @Test
        @DisplayName("refuses a method a scraper would not use")
        void refusesOtherMethods() throws IOException {
            assertThat(send("POST").status()).isEqualTo(405);
        }
    }

    @Nested
    @DisplayName("What it exposes")
    class Exposed {

        @Test
        @DisplayName("exposes the latency percentiles that had no caller outside the MBean")
        void exposesLatencyPercentiles() throws IOException {
            Metrics.recordQueryLatency(17);

            String body = send("GET").body();
            assertThat(body).contains("# TYPE excalibur_query_latency_milliseconds summary");
            assertThat(body).contains("excalibur_query_latency_milliseconds{quantile=\"0.5\"}");
            assertThat(body).contains("excalibur_query_latency_milliseconds{quantile=\"0.99\"}");
            assertThat(body).contains("excalibur_query_latency_milliseconds{quantile=\"0.999\"}");
            assertThat(body).contains("excalibur_query_latency_milliseconds_count");
        }

        @Test
        @DisplayName("exposes the request and error rates that were previously only logged")
        void exposesResponseCounts() throws IOException {
            ResponseCounters.record(200);
            ResponseCounters.record(500);
            ResponseCounters.record(429);
            ResponseCounters.record(401);

            String body = send("GET").body();
            assertThat(body).contains("excalibur_http_responses_total{class=\"2xx\"} 1");
            assertThat(body).contains("excalibur_http_responses_total{class=\"5xx\"} 1");
            assertThat(body).contains("excalibur_http_rejections_total{reason=\"throttled\"} 1");
            assertThat(body)
                .contains("excalibur_http_rejections_total{reason=\"unauthenticated\"} 1");
        }

        @Test
        @DisplayName("exposes the query results dropped for want of a chunk identity")
        void exposesUnnamedResultsDropped() throws IOException {
            long before = Metrics.getUnnamedResultsDropped();
            Metrics.recordUnnamedResultsDropped(2);

            String body = send("GET").body();
            assertThat(body).contains("# TYPE excalibur_query_unnamed_results_dropped_total counter")
                .contains("excalibur_query_unnamed_results_dropped_total " + (before + 2));
        }

        @Test
        @DisplayName("exposes the segment lifecycle as one labelled family")
        void exposesSegments() throws IOException {
            addActiveSegment(1);
            addActiveSegment(2);

            String body = send("GET").body();
            assertThat(body).contains("excalibur_segments{state=\"active\"} 2");
            assertThat(body).contains("excalibur_segments{state=\"tombstoned\"} 0");
            assertThat(body).contains("excalibur_segments_total 2");
        }

        @Test
        @DisplayName("exposes the queue depths, the filters and the disk")
        void exposesTheRestOfWhatIsMeasured() throws IOException {
            String body = send("GET").body();
            assertThat(body).contains("excalibur_hnsw_queue_depth 42");
            assertThat(body).contains("excalibur_hnsw_queue_depth_limit 100000");
            assertThat(body).contains("excalibur_cuckoo_saturated 0");
            assertThat(body).contains("excalibur_volume_bytes_available");
            assertThat(body).contains("excalibur_coverage_ratio");
        }

        @Test
        @DisplayName("exposes the partial failures that /v1/stats now names")
        void exposesPartialFailures() throws IOException {
            when(storageEngine.segmentsWithoutVectorIndexCount()).thenReturn(3);
            when(storageEngine.segmentsWithoutVectorIndex())
                .thenReturn(Map.of(1L, "dimension mismatch"));

            String body = send("GET").body();
            assertThat(body).contains("excalibur_segments_without_vector_index 3");
            assertThat(body).contains("excalibur_index_orchestrator_running 1");
        }

        @Test
        @DisplayName("exposes the circuit breaker as a number an alert can compare")
        void exposesTheCircuitBreaker() throws IOException {
            collector.withEmbeddingBreaker(() -> "OPEN");

            String body = send("GET").body();
            assertThat(body).contains("excalibur_circuit_breaker_state{breaker=\"embedding\"} 2");
        }

        @Test
        @DisplayName("omits the breaker entirely on a node that has none")
        void omitsAnAbsentBreaker() throws IOException {
            assertThat(send("GET").body()).doesNotContain("excalibur_circuit_breaker_state");
        }

        @Test
        @DisplayName("exposes the query cache as counters an alert can rate over time")
        void exposesTheQueryCache() throws IOException {
            com.eonmux.excalibur.query.cache.QueryResultCache cache =
                new com.eonmux.excalibur.query.cache.QueryResultCache(8);
            collector.withQueryResultCache(cache);

            String body = send("GET").body();
            assertThat(body).contains("excalibur_query_cache_capacity 8");
            assertThat(body).contains("excalibur_query_cache_entries 0");
            assertThat(body).contains("excalibur_query_cache_lookups_total{result=\"hit\"} 0");
            assertThat(body).contains("excalibur_query_cache_lookups_total{result=\"miss\"} 0");
            assertThat(body).contains("excalibur_query_cache_evictions_total 0");
            assertThat(body).contains("excalibur_query_cache_invalidations_total 0");
        }

        @Test
        @DisplayName("exposes chunks dropped for corrupt text as a counter an alert can fire on")
        void exposesUnreadableChunksDropped() throws IOException {
            // Each drop takes a chunk out of keyword search until its document is reindexed, and
            // means storage holds damage: an operator has to hear of it without reading logs.
            com.eonmux.excalibur.ingest.lexical.ChunkIndex index =
                mock(com.eonmux.excalibur.ingest.lexical.ChunkIndex.class);
            when(index.stats()).thenReturn(new com.eonmux.excalibur.ingest.lexical.ChunkIndexStats(
                "native", 10L, 1, 8L, 2L, 0, java.util.Optional.empty(), false, 3L));
            collector.withLexicalIndex(index);

            String body = send("GET").body();
            assertThat(body)
                .contains("# TYPE excalibur_lexical_unreadable_chunks_dropped_total counter")
                .contains("excalibur_lexical_unreadable_chunks_dropped_total 3");
        }

        @Test
        @DisplayName("exposes chunks no query can reach, and the segments holding them")
        void exposesUnsearchableChunks() throws IOException {
            // live + erased + unsearchable is everything on disk, so a dashboard summing the
            // family sees the corpus whole and one charting live sees what keyword search has.
            com.eonmux.excalibur.ingest.lexical.ChunkIndex index =
                mock(com.eonmux.excalibur.ingest.lexical.ChunkIndex.class);
            when(index.stats()).thenReturn(new com.eonmux.excalibur.ingest.lexical.ChunkIndexStats(
                "native", 10L, 3, 7L, 1L, 0, java.util.Optional.empty(), true, 0L,
                java.util.List.of(new com.eonmux.excalibur.ingest.lexical.ChunkIndexStats
                    .UnreadableSegment("seg-1", 2, true, "damaged")), 4));
            collector.withLexicalIndex(index);

            String body = send("GET").body();
            assertThat(body)
                .contains("excalibur_lexical_chunks{state=\"live\"} 7")
                .contains("excalibur_lexical_chunks{state=\"unsearchable\"} 2")
                .contains("# TYPE excalibur_lexical_unreadable_segments gauge")
                .contains("excalibur_lexical_unreadable_segments 1")
                .contains("# TYPE excalibur_lexical_outdated_segments gauge")
                .contains("excalibur_lexical_outdated_segments 4");
        }

        @Test
        @DisplayName("omits the query cache entirely on a node that has none")
        void omitsAnAbsentQueryCache() throws IOException {
            assertThat(send("GET").body()).doesNotContain("excalibur_query_cache");
        }

        @Test
        @DisplayName("exposes what is running now and what readiness says")
        void exposesQueriesAndReadiness() throws IOException {
            queryRegistry.register("q-1", new CancellationToken());
            readiness.beginDrain("rolling restart");

            String body = send("GET").body();
            assertThat(body).contains("excalibur_queries_active 1");
            assertThat(body).contains("excalibur_queries_registered_total 1");
            assertThat(body).contains("excalibur_node_draining 1");
        }

        @Test
        @DisplayName("names the build it is, so a scrape can be attributed to a version")
        void exposesBuildInfo() throws IOException {
            assertThat(send("GET").body())
                .contains("excalibur_build_info{jvm=\"" + BuildInfo.jvm()
                    + "\",version=\"" + BuildInfo.version() + "\"} 1");
        }
    }

    @Test
    @DisplayName("declares each metric family once, or a scraper drops the whole scrape")
    void declaresEachFamilyOnce() throws IOException {
        String body = send("GET").body();

        List<String> declared = body.lines()
            .filter(line -> line.startsWith("# TYPE "))
            .map(line -> line.split(" ")[2])
            .toList();
        assertThat(declared).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("writes every sample as name, optional labels and one number")
    void everySampleIsWellFormed() throws IOException {
        Metrics.recordQueryLatency(3);
        String body = send("GET").body();

        assertThat(body.lines().filter(line -> !line.startsWith("#")))
            .isNotEmpty()
            .allMatch(line -> line.matches(
                "[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[^}]*})? -?([0-9.eE+]+|NaN|\\+Inf|-Inf)"));
    }

    private MetricsEndpoint endpoint() {
        return new MetricsEndpoint(collector, queryRegistry, readiness);
    }

    private void addActiveSegment(long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }

    private record Answer(int status, String body) {
    }

    private static HttpExchange exchange(String method) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/metrics"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private Answer send(String method) throws IOException {
        HttpExchange exchange = exchange(method);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);

        endpoint().handle(exchange);

        var status = forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(),
            org.mockito.ArgumentMatchers.anyLong());
        return new Answer(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }
}
