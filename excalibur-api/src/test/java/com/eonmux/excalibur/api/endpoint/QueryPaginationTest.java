package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code POST /v1/query} says about how many results there are and how to reach the rest.
 *
 * <p>A page of {@code top_k} identifiers with nothing beside it cannot be shown as "10 of 240",
 * cannot be paged through, and reads the same whether the corpus held nothing or the query was
 * never allowed to look. The total and the page block are what separate those, and the total has
 * to be honest about which of the two it is: a query that could not see the whole corpus can
 * only ever report a lower bound.</p>
 */
@DisplayName("Query pagination and result total")
class QueryPaginationTest {

    private QueryPipeline queryPipeline;
    private EmbeddingClient embeddingClient;
    private QueryEndpoint endpoint;

    @BeforeEach
    void setUp() {
        queryPipeline = mock(QueryPipeline.class);
        embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString(), anyString()))
            .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        endpoint = new QueryEndpoint(queryPipeline, null, null, null, embeddingClient);
    }

    /** Coverage of a query that searched everything it was supposed to. */
    private static IndexCoverage whole() {
        return IndexCoverage.builder().totalSegments(1).activeSegments(1)
            .segmentsSearched(1).estimatedCorpusCoverage(1.0).build();
    }

    /** Coverage of a query that reached only part of the catalog. */
    private static IndexCoverage partial() {
        return IndexCoverage.builder().totalSegments(4).activeSegments(4)
            .segmentsSearched(1).segmentsTimedOut(3).estimatedCorpusCoverage(0.25)
            .degraded(true).build();
    }

    private static QueryResponse answerOf(int hits, IndexCoverage coverage) {
        List<QueryResultItem> items = new ArrayList<>(hits);
        for (int i = 0; i < hits; i++) {
            items.add(new QueryResultItem("docs:guide.md:chunk:" + i, 0.9f - i * 0.01f,
                null, 3L, i));
        }
        return new QueryResponse(items, coverage, 12L, false, false);
    }

    private String answer(String requestJson, QueryResponse pipelineAnswer) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange(requestJson, body);
        when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
            .thenReturn(CompletableFuture.completedFuture(pipelineAnswer));
        endpoint.handle(exchange);
        return body.toString(StandardCharsets.UTF_8);
    }

    private HttpExchange exchange(String requestJson, ByteArrayOutputStream body)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(requestJson.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }

    /**
     * Not opt-in. A caller cannot branch on whether a field is there to find out how many
     * results there were, so the block is on every answer this route gives.
     */
    @Test
    @DisplayName("every answer carries the page it returned")
    void everyAnswerCarriesItsPage() throws IOException {
        String json = answer("{\"query\": \"oom\", \"top_k\": 10}", answerOf(3, whole()));

        assertThat(json).contains("\"page\": {\"offset\": 0, \"limit\": 10, \"returned\": 3, "
            + "\"next_offset\": null}");
    }

    /**
     * The engine was asked for ten and produced three, so there is no eleventh it declined to
     * mention: it ran out of matches. That is the one case where the count is a fact rather
     * than a floor, and saying "exact" is what lets a caller render "3 results" at all.
     */
    @Test
    @DisplayName("a page the engine could not fill reports an exact total")
    void anUnfilledPageIsAnExactTotal() throws IOException {
        String json = answer("{\"query\": \"oom\", \"top_k\": 10}", answerOf(3, whole()));

        assertThat(json).contains("\"result_total\": {\"value\": 3, \"relation\": \"exact\"}");
    }

    /**
     * A full page says nothing about what lies past it. Approximate nearest neighbour search
     * returns the depth it was asked for and stops; counting the rest would mean scanning the
     * corpus, so the number that can be reported is a floor and is named as one.
     */
    @Test
    @DisplayName("a page the engine filled reports a lower bound, not a count")
    void aFullPageIsALowerBound() throws IOException {
        String json = answer("{\"query\": \"oom\", \"top_k\": 3}", answerOf(3, whole()));

        assertThat(json).contains("\"result_total\": {\"value\": 3, \"relation\": \"at_least\"}");
        assertThat(json).contains("\"next_offset\": 3");
    }

    /**
     * The distinction {@code coverage.degraded} already draws, carried into the total. A query
     * that searched a quarter of the catalog and found nothing has not established that the
     * corpus holds nothing, and a precise-looking zero would say that it had.
     */
    @Test
    @DisplayName("a degraded answer can only ever report a lower bound")
    void aDegradedAnswerIsNeverExact() throws IOException {
        String json = answer("{\"query\": \"oom\", \"top_k\": 10}", answerOf(0, partial()));

        assertThat(json).contains("\"result_total\": {\"value\": 0, \"relation\": \"at_least\"}");
    }

    /**
     * The other half of the same distinction. Nothing matched, the query looked everywhere, and
     * a caller can say so rather than showing an empty page that might be a failure.
     */
    @Test
    @DisplayName("an empty answer from a whole-corpus search reports an exact zero")
    void anEmptyWholeCorpusAnswerIsExactlyZero() throws IOException {
        String json = answer("{\"query\": \"oom\", \"top_k\": 10}", answerOf(0, whole()));

        assertThat(json).contains("\"result_total\": {\"value\": 0, \"relation\": \"exact\"}");
    }

    /**
     * Paging is not free on a ranked index: the second page is the tail of a deeper search, so
     * the engine has to be asked for offset plus page size and the prefix dropped. Asking it
     * for the page size alone would return the first page again under a different offset.
     */
    @Test
    @DisplayName("an offset makes the engine search deep enough to skip past it")
    void anOffsetDeepensTheSearch() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange("{\"query\": \"oom\", \"top_k\": 2, \"offset\": 4}", body);
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        when(queryPipeline.execute(captor.capture(), any(CancellationToken.class)))
            .thenReturn(CompletableFuture.completedFuture(answerOf(6, whole())));

        endpoint.handle(exchange);

        assertThat(captor.getValue().topK())
            .as("offset 4 with a page of 2 needs the top 6")
            .isEqualTo(6);
        String json = body.toString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\": {\"offset\": 4, \"limit\": 2, \"returned\": 2, "
            + "\"next_offset\": 6}");
        assertThat(json)
            .as("the first four are the previous pages and are not returned again")
            .doesNotContain("docs:guide.md:chunk:0")
            .contains("docs:guide.md:chunk:4")
            .contains("docs:guide.md:chunk:5");
    }

    /** An offset past what the engine found is an empty page, not an error. */
    @Test
    @DisplayName("an offset past the end returns an empty page and no next offset")
    void anOffsetPastTheEndIsEmpty() throws IOException {
        String json = answer("{\"query\": \"oom\", \"top_k\": 2, \"offset\": 8}",
            answerOf(3, whole()));

        assertThat(json).contains("\"returned\": 0, \"next_offset\": null");
        assertThat(json).contains("\"result_total\": {\"value\": 3, \"relation\": \"exact\"}");
    }

    /**
     * {@code offset} deepens the search the same way {@code top_k} widens it, so it is bounded
     * by the same ceiling and for the same reason: the depth is what sizes the priority queues
     * inside the pipeline, and a caller that can set it without limit sets the allocation.
     */
    @Test
    @DisplayName("an offset that pushes the search past the top_k ceiling is refused")
    void anOffsetPastTheCeilingIsRefused() throws IOException {
        QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null,
            embeddingClient, ApiSecurityConfig.builder().maxTopK(10).build());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange("{\"query\": \"oom\", \"top_k\": 5, \"offset\": 9}", body);

        bounded.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("offset");
    }

    /** A negative offset is not a page, and is refused rather than clamped to the first one. */
    @Test
    @DisplayName("a negative offset is refused")
    void aNegativeOffsetIsRefused() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = exchange("{\"query\": \"oom\", \"offset\": -1}", body);

        endpoint.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("offset");
    }
}
