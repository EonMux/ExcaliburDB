package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.query.cache.QueryResultCache;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether the cache is helping has to be visible from outside the process.
 *
 * <p>A cache is the one component whose failure mode is invisible in every other figure: a
 * node with a cache that never hits is slower than one without, and a node with a cache that
 * keeps being invalidated is paying for a fingerprint on every request to store answers
 * nothing ever reads. Neither shows up in latency percentiles as anything but noise.</p>
 */
@DisplayName("Query cache statistics on /v1/stats")
class QueryCacheStatsReportingTest {

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;

    @BeforeEach
    void setUp() {
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        SegmentCatalog catalog = mock(SegmentCatalog.class);
        VisibilityTracker visibility = mock(VisibilityTracker.class);

        lenient().when(storageEngine.getCatalog()).thenReturn(catalog);
        lenient().when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibility);
        lenient().when(catalog.getCatalogMetadata())
            .thenReturn(new SegmentCatalog.CatalogMetadata());
        lenient().when(visibility.getStats())
            .thenReturn(new VisibilityTracker.VisibilityStats(1L, 0, 0, 0));
        lenient().when(indexOrchestrator.getHierarchicalCuckooFilter())
            .thenReturn(mock(HierarchicalCuckooFilter.class));
        lenient().when(storageEngine.segmentsWithoutVectorIndex()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("a node with no cache configured reports no cache block at all")
    void noCacheMeansNoBlock() throws IOException {
        NodeStatsCollector collector =
            new NodeStatsCollector(indexOrchestrator, storageEngine, null);

        assertThat(collector.collect().queryCache())
            .as("absent and empty are different statements about a cache")
            .isNull();
        assertThat(statsBody(collector)).doesNotContain("queryCache");
    }

    @Test
    @DisplayName("a configured cache reports hits, misses, evictions and size")
    void aConfiguredCacheIsReported() throws IOException {
        QueryResultCache cache = new QueryResultCache(64);
        NodeStatsCollector collector =
            new NodeStatsCollector(indexOrchestrator, storageEngine, null);
        collector.withQueryResultCache(cache);

        assertThat(collector.collect().queryCache()).isNotNull();

        String body = statsBody(collector);
        assertThat(body).contains("\"queryCache\"");
        assertThat(body).contains("\"hits\": 0");
        assertThat(body).contains("\"misses\": 0");
        assertThat(body).contains("\"evictions\": 0");
        assertThat(body).contains("\"invalidations\": 0");
        assertThat(body).contains("\"size\": 0");
        assertThat(body).contains("\"capacity\": 64");
        assertThat(body).contains("\"hitRatio\": 0.0000");
    }

    @Test
    @DisplayName("the reported figures are the cache's own, not a fresh zero")
    void theFiguresAreTheCachesOwn() throws IOException {
        QueryResultCache cache = new QueryResultCache(64);
        excalibur.query.cache.CorpusVersion version =
            new excalibur.query.cache.CorpusVersion(1L, 2L, 1);
        cache.get(key(), version);

        NodeStatsCollector collector =
            new NodeStatsCollector(indexOrchestrator, storageEngine, null);
        collector.withQueryResultCache(cache);

        assertThat(collector.collect().queryCache().misses()).isEqualTo(1);
        assertThat(statsBody(collector)).contains("\"misses\": 1");
    }

    private static excalibur.query.cache.QueryCacheKey key() {
        return excalibur.query.cache.QueryCacheKey.builder()
            .retrievalMode("vector")
            .queryText("anything")
            .topK(1)
            .snippetChars(320)
            .build();
    }

    private static String statsBody(NodeStatsCollector collector) throws IOException {
        StatsEndpoint endpoint = new StatsEndpoint(collector);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("GET");
        lenient().when(exchange.getRequestURI()).thenReturn(URI.create("/v1/stats"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(body);
        lenient().when(exchange.getRequestBody())
            .thenReturn(new ByteArrayInputStream(new byte[0]));
        endpoint.handle(exchange);
        return body.toString(StandardCharsets.UTF_8);
    }
}
