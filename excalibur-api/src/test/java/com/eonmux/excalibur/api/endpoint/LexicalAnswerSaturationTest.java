package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.retrieval.LexicalSearchPermits;
import com.eonmux.excalibur.api.retrieval.ResultText;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;

import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the lexical-only query route is bounded by the same permits the hybrid route is.
 *
 * <p>A node with no embedding client answers every query from BM25, which is a supported
 * deployment. A BM25 search does not stop when the request's deadline passes - it runs inside
 * the lexical engine, where interrupting is neither reliable nor safe - so a caller sending
 * {@code timeout_ms: 1} is answered at once and leaves a full search running. Without a bound,
 * that work accumulates faster than it drains and the node saturates at the rate the request
 * guard allows.</p>
 */
@DisplayName("The lexical-only route is bounded")
class LexicalAnswerSaturationTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private int held;

    @AfterEach
    void releaseHeldPermits() {
        for (int i = 0; i < held; i++) {
            LexicalSearchPermits.release();
        }
        held = 0;
        executor.shutdownNow();
    }

    /** The parser the query route builds, with the compiled-in defaults. */
    private static QueryRequestParser parser() {
        return new QueryRequestParser(null, ApiSecurityConfig.defaults(), 5000L);
    }

    /** The presentation fields the query route resolves, over a node with no chunk storage. */
    private static QueryContexts contexts() {
        return new QueryContexts(ApiSecurityConfig.defaults(), new ResultText(4096));
    }

    @Test
    @DisplayName("a query is refused rather than started when every permit is taken")
    void refusesWhenSaturated() throws IOException {
        while (LexicalSearchPermits.tryAcquire()) {
            held++;
        }
        assertThat(held)
            .as("there must be a bound to exhaust in the first place")
            .isGreaterThan(0);

        LexicalQueryService lexical = mock(LexicalQueryService.class);
        LexicalAnswer answer = new LexicalAnswer(parser(), executor, null, contexts(),
            mock(QueryPipeline.class), new RelevancyScoring(executor, 4096));

        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(
            "{\"query\": \"anything\"}".getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(responseBody);

        answer.answer(exchange, lexical, null, "q-1", "c-1", System.nanoTime());

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(status.getValue())
            .as("a saturated node refuses rather than piling another search on")
            .isEqualTo(503);
        verify(lexical, never()).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("the permit is returned once the search has finished")
    void releasesThePermit() throws IOException {
        LexicalQueryService lexical = mock(LexicalQueryService.class);
        when(lexical.search(anyString(), anyInt())).thenReturn(List.of());
        when(lexical.stats()).thenReturn(null);

        int before = LexicalSearchPermits.available();
        LexicalAnswer answer = new LexicalAnswer(parser(), executor, null, contexts(),
            mock(QueryPipeline.class), new RelevancyScoring(executor, 4096));

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(
            "{\"query\": \"anything\"}".getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());

        answer.answer(exchange, lexical, null, "q-2", "c-2", System.nanoTime());

        assertThat(LexicalSearchPermits.available())
            .as("a permit held past the end of its search is a permanent loss of capacity")
            .isEqualTo(before);
    }
}
