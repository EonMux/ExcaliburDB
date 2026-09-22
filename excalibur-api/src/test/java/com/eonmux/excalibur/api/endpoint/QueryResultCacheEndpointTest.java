package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.cache.CorpusVersion;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code POST /v1/query} does with a cache in front of it.
 *
 * <p>The pipeline is mocked here because what is under test is the endpoint's decision to
 * call it or not; the invalidation signal itself is driven through a real storage engine in
 * {@code CorpusVersionInvalidationTest} over in excalibur-query, which is where it belongs.</p>
 */
@DisplayName("Query result cache on /v1/query")
class QueryResultCacheEndpointTest {

    private static final CorpusVersion V1 = new CorpusVersion(3L, 0xABCDL, 2);
    private static final CorpusVersion V2 = new CorpusVersion(4L, 0xBEEFL, 2);

    private QueryPipeline queryPipeline;
    private QueryEndpoint endpoint;

    @BeforeEach
    void setUp() {
        queryPipeline = mock(QueryPipeline.class);
        when(queryPipeline.corpusVersion()).thenReturn(V1);
        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(healthy()));

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString(), anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        endpoint = new QueryEndpoint(queryPipeline, null, null, null, embeddingClient);
        // Without chunk storage the node refuses include_text, snippet and explain outright,
        // and the requests that differ only in those fields never reach the cache at all.
        endpoint.setChunkText(chunkId -> "the text behind " + chunkId);
        endpoint.setQueryResultCache(new QueryResultCache(16));
    }

    @Test
    @DisplayName("a repeated identical query is answered without running the pipeline again")
    void repeatedQueryIsServedFromCache() throws IOException {
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));

        verify(queryPipeline, times(1))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("a repeated query is not served once the corpus has moved")
    void corpusChangeSendsTheQueryBackToThePipeline() throws IOException {
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));
        when(queryPipeline.corpusVersion()).thenReturn(V2);
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));

        verify(queryPipeline, times(2))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("a corpus that cannot be fingerprinted is never answered from cache")
    void unreadableCorpusDisablesTheCache() throws IOException {
        when(queryPipeline.corpusVersion()).thenReturn(CorpusVersion.UNREADABLE);
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));

        verify(queryPipeline, times(2))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("requests differing only in offset do not share a cache entry")
    void offsetIsPartOfTheKey() throws IOException {
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5, \"offset\": 0}"));
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5, \"offset\": 5}"));

        verify(queryPipeline, times(2))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("requests differing only in explain do not share a cache entry")
    void explainIsPartOfTheKey() throws IOException {
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5, \"explain\": true}"));

        verify(queryPipeline, times(2))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("requests differing only in snippet_chars do not share a cache entry")
    void snippetCharsIsPartOfTheKey() throws IOException {
        String narrow = "{\"query\": \"compaction\", \"include_text\": true, \"snippet\": true,"
            + " \"snippet_chars\": 64}";
        String wide = "{\"query\": \"compaction\", \"include_text\": true, \"snippet\": true,"
            + " \"snippet_chars\": 256}";
        endpoint.handle(exchange(narrow));
        endpoint.handle(exchange(wide));

        verify(queryPipeline, times(2))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("a degraded answer is not kept, so the next caller searches again")
    void degradedAnswersAreNotKept() throws IOException {
        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(degraded()));

        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));

        verify(queryPipeline, times(2))
            .execute(any(QueryRequest.class), any(CancellationToken.class));
    }

    @Test
    @DisplayName("a node with no cache configured never fingerprints its corpus")
    void cachingOffCostsNothingPerRequest() throws IOException {
        QueryPipeline plain = mock(QueryPipeline.class);
        when(plain.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(healthy()));
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyString(), anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        QueryEndpoint uncached = new QueryEndpoint(plain, null, null, null, embeddings);
        uncached.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));

        verify(plain, never()).corpusVersion();
    }

    @Test
    @DisplayName("the cache reports hits and misses for an operator to read")
    void statisticsRecordWhatHappened() throws IOException {
        QueryResultCache cache = new QueryResultCache(16);
        endpoint.setQueryResultCache(cache);

        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));
        endpoint.handle(exchange("{\"query\": \"compaction\", \"top_k\": 5}"));

        assertThat(cache.stats().misses()).isEqualTo(1);
        assertThat(cache.stats().hits()).isEqualTo(1);
        assertThat(cache.stats().size()).isEqualTo(1);
    }

    private static QueryResponse healthy() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(2)
            .activeSegments(2)
            .segmentsSearched(2)
            .estimatedCorpusCoverage(1.0)
            .degraded(false)
            .build();
        return new QueryResponse(Collections.emptyList(), coverage, 12L, false, false);
    }

    private static QueryResponse degraded() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(2)
            .activeSegments(1)
            .segmentsSearched(1)
            .estimatedCorpusCoverage(0.5)
            .degraded(true)
            .build();
        return new QueryResponse(Collections.emptyList(), coverage, 12L, false, false);
    }

    private static HttpExchange exchange(String body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }
}
