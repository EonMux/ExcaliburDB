package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That {@code /v1/stats} shows the partial failures a node keeps serving through.
 *
 * <p>Three of them were measured and reported nowhere. A segment whose vector index could not
 * be attached stays in service and answers no vector search, so a query over it comes back
 * short rather than empty or failed - the hardest kind of fault to notice. A dead index
 * orchestrator leaves the HNSW queue growing while coverage stays at one and health stays
 * {@code healthy}. An open embedding circuit breaker silently drops the node to lexical-only
 * retrieval. In all three the node answers 200 to everything, which is why none of them can
 * be found without a field that says so.</p>
 */
@DisplayName("Stats: partial failures")
class StatsPartialFailureTest {

    private SegmentCatalog segmentCatalog;
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new VisibilityTracker(segmentCatalog));
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
        when(storageEngine.segmentsWithoutVectorIndex()).thenReturn(Map.of());
        when(storageEngine.segmentsWithoutVectorIndexCount()).thenReturn(0);
        when(indexOrchestrator.isRunning()).thenReturn(true);
    }

    @Test
    @DisplayName("reports a healthy node as having every vector index attached")
    void reportsNoVectorIndexFailures() throws IOException {
        assertThat(body()).contains("\"vectorIndex\": {\"segmentsWithoutIndex\": 0}");
    }

    @Test
    @DisplayName("names each segment serving without a vector index, and why")
    void namesSegmentsWithoutAVectorIndex() throws IOException {
        Map<Long, String> failures = new LinkedHashMap<>();
        failures.put(7L, "dimension mismatch: graph is 384, segment is 768");
        failures.put(2L, "graph file unreadable");
        when(storageEngine.segmentsWithoutVectorIndex()).thenReturn(failures);
        when(storageEngine.segmentsWithoutVectorIndexCount()).thenReturn(2);

        String body = body();
        assertThat(body).contains("\"segmentsWithoutIndex\": 2");
        // Sorted, so two reads of the same state produce the same document.
        assertThat(body).contains("\"2\": \"graph file unreadable\"");
        assertThat(body).contains("\"7\": \"dimension mismatch: graph is 384, segment is 768\"");
        assertThat(body.indexOf("\"2\":")).isLessThan(body.indexOf("\"7\":"));
    }

    @Test
    @DisplayName("says whether the worker that drains the HNSW queue is alive")
    void reportsTheIndexWorker() throws IOException {
        assertThat(body()).contains("\"workers\": {\"indexOrchestratorRunning\": true}");

        when(indexOrchestrator.isRunning()).thenReturn(false);
        assertThat(body()).contains("\"workers\": {\"indexOrchestratorRunning\": false}");
    }

    @Test
    @DisplayName("omits the breaker block on a node with no embedding client")
    void omitsBreakersWithoutAnEmbeddingClient() throws IOException {
        assertThat(body()).doesNotContain("\"breakers\"");
    }

    @Test
    @DisplayName("reports the embedding breaker's state, the node's main degradation mode")
    void reportsTheEmbeddingBreaker() throws IOException {
        StatsEndpoint endpoint = endpoint();
        endpoint.withEmbeddingBreaker(() -> "OPEN");
        assertThat(bodyOf(endpoint)).contains("\"breakers\": {\"embedding\": \"OPEN\"}");
    }

    @Test
    @DisplayName("keeps answering when the storage engine cannot list its vector failures")
    void survivesAFailingStorageEngine() throws IOException {
        when(storageEngine.segmentsWithoutVectorIndex())
            .thenThrow(new IllegalStateException("engine closed"));
        when(storageEngine.segmentsWithoutVectorIndexCount())
            .thenThrow(new IllegalStateException("engine closed"));

        // The rest of the response is what most callers came for; losing all of it to one
        // subsystem that could not answer is the worse outcome. The block is absent rather
        // than reporting a zero it did not measure.
        String body = body();
        assertThat(body).contains("\"segments\"");
        assertThat(body).doesNotContain("\"vectorIndex\"");
    }

    private StatsEndpoint endpoint() {
        return new StatsEndpoint(indexOrchestrator, storageEngine);
    }

    private String body() throws IOException {
        return bodyOf(endpoint());
    }

    private String bodyOf(StatsEndpoint endpoint) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/stats"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);
        endpoint.handle(exchange);
        return out.toString(StandardCharsets.UTF_8);
    }
}
