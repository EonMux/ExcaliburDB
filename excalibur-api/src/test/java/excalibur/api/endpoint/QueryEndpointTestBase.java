package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.llm.embedding.EmbeddingClient;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.pipeline.QueryPipeline;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.mockito.Mockito.*;

/**
 * Shared fixture for the {@link QueryEndpoint} suites.
 *
 * <p>Every suite drives the endpoint the way the HTTP server does — by handing it a
 * mocked {@link HttpExchange} carrying a real request body and capturing what it writes
 * back — so the code under test is the actual parsing, dispatch and serialization rather
 * than a method call with pre-built arguments. The pipeline behind the endpoint and the
 * embedding client in front of it are mocks, because each suite needs to make them
 * behave differently: succeed, hang past the deadline, fail, or be absent entirely.</p>
 *
 * <p>The default {@code endpoint} is a fully configured node — a pipeline and a working
 * embedding client, no {@code Config} and no lexical index. A suite that needs something
 * else constructs its own endpoint over the same mocks.</p>
 */
abstract class QueryEndpointTestBase {

    QueryPipeline queryPipeline;
    EmbeddingClient embeddingClient;
    QueryEndpoint endpoint;

    @BeforeEach
    void setUp() {
        queryPipeline = mock(QueryPipeline.class);
        embeddingClient = mock(EmbeddingClient.class);
        // Default: successful embedding returning a valid 3-dim vector
        when(embeddingClient.embed(anyString(), anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingClient.embed(anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        endpoint = new QueryEndpoint(queryPipeline, null, null, null, embeddingClient);
    }

    HttpExchange createMockExchange(String method, String body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    HttpExchange createMockExchangeWithBody(String method, String body,
            ByteArrayOutputStream responseBody) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(responseBody);
        return exchange;
    }

    QueryResponse createMockResponse() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(10)
            .activeSegments(8)
            .indexingSegments(1)
            .warmingSegments(1)
            .estimatedCorpusCoverage(0.8)
            .tailScanEntries(0)
            .degraded(false)
            .segmentsSearched(7)
            .segmentsExcluded(1)
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            42L,
            false,
            false
        );
    }
}
