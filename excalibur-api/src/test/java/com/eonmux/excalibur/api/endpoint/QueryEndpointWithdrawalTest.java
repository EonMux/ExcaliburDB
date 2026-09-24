package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a query answers when the caller withdraws it, and when the vector half cannot answer.
 *
 * <p>{@code DELETE /v1/query/{id}} answers {@code "cancelled": true} exactly when it withdrew a
 * running query, so that query must answer {@code "cancelled": true} with nothing in it, on every
 * route and however late the withdrawal arrived. These withdraw each query at the latest moment
 * the route offers - after its search has finished and its answer is being put together - since
 * that is where an answer used to be written in full after the caller had been told it was
 * withdrawn.</p>
 *
 * <p>The rest pin down the vector route's fallback: an embedding outage on a node that keeps a
 * lexical index is no dense signal, not a failed query, whether or not fusion is turned on.</p>
 */
@DisplayName("QueryEndpoint withdrawal and lexical fallback")
class QueryEndpointWithdrawalTest extends QueryEndpointTestBase {

    private static final ChunkIndexStats STATS = new ChunkIndexStats("native", 10, 1, 10, 0, 0,
        Optional.empty(), false);

    private QueryRegistry registry;
    private LexicalQueryService lexical;

    @BeforeEach
    void setUpRegistryAndLexical() {
        registry = new QueryRegistry();
        lexical = mock(LexicalQueryService.class);
        when(lexical.retrieve(anyString(), anyInt(), any(), any(), anyLong())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1);
            List<LexicalQueryService.Hit> hits = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, 5); i++) {
                hits.add(new LexicalQueryService.Hit("c" + i, 1.0 / (61 + i)));
            }
            return new LexicalQueryService.Ranking(hits, null);
        });
        when(lexical.stats()).thenReturn(STATS);
        when(lexical.rrfK()).thenReturn(60);
        when(lexical.searchVersion(anyLong())).thenReturn(OptionalLong.of(1L));
    }

    private static QueryResponse dense(int count) {
        List<QueryResultItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new QueryResultItem("d" + i, 0.9f - i * 0.01f, null, 3L, i));
        }
        return new QueryResponse(items, IndexCoverage.builder()
            .totalSegments(1).activeSegments(1).segmentsSearched(1)
            .estimatedCorpusCoverage(1.0).degraded(false).build(), 5L, false, false);
    }

    /** Withdraws the one running query, the way {@code DELETE /v1/query/{id}} does. */
    private boolean withdrawTheRunningQuery() {
        assertThat(registry.getActiveQueries()).hasSize(1);
        return registry.cancel(registry.getActiveQueries().get(0).queryId(), "test");
    }

    private QueryEndpoint vectorNode(Config config) {
        return new QueryEndpoint(queryPipeline, config, null, registry, embeddingClient);
    }

    private QueryEndpoint hybridNode(Config config) {
        QueryEndpoint node = vectorNode(config);
        node.setLexicalQueryService(lexical);
        return node;
    }

    private QueryEndpoint lexicalNode() {
        QueryEndpoint node = new QueryEndpoint(queryPipeline, null, null, registry, null);
        node.setLexicalQueryService(lexical);
        return node;
    }

    private static Config fusionOff() {
        return new Config(Map.of("excalibur.query.hybrid-retrieval", "false"));
    }

    private record Answer(HttpExchange exchange, String json) { }

    private Answer ask(QueryEndpoint node, String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpExchange exchange = createMockExchangeWithBody("POST", body, out);
        node.handle(exchange);
        return new Answer(exchange, out.toString(StandardCharsets.UTF_8));
    }

    private static void assertWithdrawn(Answer answer) throws IOException {
        verify(answer.exchange()).sendResponseHeaders(eq(200), anyLong());
        assertThat(answer.json())
            .contains("\"cancelled\": true")
            .contains("\"timed_out\": false")
            .contains("\"degraded\": true")
            .as("a withdrawn question is not answered, not even with what had been found")
            .doesNotContain("\"d0\"")
            .doesNotContain("\"c0\"");
    }

    @Nested
    @DisplayName("A query withdrawn after its search finished")
    class WithdrawnLate {

        @Test
        @DisplayName("on the vector route answers cancelled with no results")
        void vectorRoute() throws IOException {
            AtomicBoolean confirmed = new AtomicBoolean();
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenAnswer(invocation -> {
                    confirmed.set(withdrawTheRunningQuery());
                    return CompletableFuture.completedFuture(dense(3));
                });

            Answer answer = ask(vectorNode(null), "{\"query\": \"q\", \"top_k\": 3}");

            assertThat(confirmed).as("the cancel route told the caller it withdrew the query")
                .isTrue();
            assertWithdrawn(answer);
            assertThat(registry.getActiveQueryCount()).isZero();
        }

        @Test
        @DisplayName("on the hybrid route answers cancelled, with neither signal's results")
        void hybridRoute() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(dense(3)));
            // Read while the two rankings are fused, which is after both signals finished.
            AtomicBoolean confirmed = new AtomicBoolean();
            when(lexical.rrfK()).thenAnswer(invocation -> {
                confirmed.set(withdrawTheRunningQuery());
                return 60;
            });

            Answer answer = ask(hybridNode(null), "{\"query\": \"q\", \"top_k\": 3}");

            assertThat(confirmed).isTrue();
            assertWithdrawn(answer);
        }

        @Test
        @DisplayName("on the lexical route answers cancelled with no results")
        void lexicalRoute() throws IOException {
            AtomicBoolean searched = new AtomicBoolean();
            AtomicBoolean confirmed = new AtomicBoolean();
            when(lexical.retrieve(anyString(), anyInt(), any(), any(), anyLong())).thenAnswer(invocation -> {
                searched.set(true);
                return new LexicalQueryService.Ranking(
                    List.of(new LexicalQueryService.Hit("c0", 0.5)), null);
            });
            // Read for the coverage block, once the search has returned its hits.
            when(lexical.stats()).thenAnswer(invocation -> {
                if (searched.get() && !confirmed.get()) {
                    confirmed.set(withdrawTheRunningQuery());
                }
                return STATS;
            });

            Answer answer = ask(lexicalNode(), "{\"query\": \"q\", \"top_k\": 3}");

            assertThat(confirmed).isTrue();
            assertWithdrawn(answer);
            assertThat(registry.getActiveQueryCount()).isZero();
        }
    }

    @Nested
    @DisplayName("A query nobody withdrew")
    class NotWithdrawn {

        @Test
        @DisplayName("is not reported cancelled when its pipeline missed the deadline")
        void aTimeoutIsNotACancellation() throws IOException {
            ArgumentCaptor<CancellationToken> pipelineToken =
                ArgumentCaptor.forClass(CancellationToken.class);
            when(queryPipeline.execute(any(QueryRequest.class), pipelineToken.capture()))
                .thenReturn(new CompletableFuture<>());

            Answer answer = ask(vectorNode(null), "{\"query\": \"q\", \"timeout_ms\": 200}");

            assertThat(pipelineToken.getValue().isCancelled())
                .as("the abandoned pipeline is still told to stop")
                .isTrue();
            assertThat(answer.json()).contains("\"timed_out\": true")
                .contains("\"cancelled\": false");
            assertThat(registry.getTotalQueriesCancelled()).isZero();
            assertThat(registry.getActiveQueryCount()).isZero();
        }
    }

    @Nested
    @DisplayName("A vector-only node whose embedding endpoint is down")
    class VectorOnlyFallback {

        @Test
        @DisplayName("answers from BM25 when the node keeps a lexical index")
        void answersFromBm25() throws IOException {
            when(embeddingClient.embed(anyString(), anyString()))
                .thenThrow(new LLMException("breaker open", LLMException.ErrorType.CIRCUIT_OPEN));

            Answer answer = ask(hybridNode(fusionOff()), "{\"query\": \"q\", \"top_k\": 3}");

            verify(answer.exchange()).sendResponseHeaders(eq(200), anyLong());
            assertThat(answer.json())
                .contains("\"retrieval\": \"lexical\"")
                .contains("\"c0\"")
                .as("one signal of the node's configured search is missing, and it says so")
                .contains("\"degraded\": true");
            verify(queryPipeline, never())
                .execute(any(QueryRequest.class), any(CancellationToken.class));
        }

        @Test
        @DisplayName("answers from BM25 when the embedding it got back is unusable")
        void answersFromBm25OnAnUnusableVector() throws IOException {
            when(embeddingClient.embed(anyString(), anyString()))
                .thenReturn(new float[] {0.1f, Float.NaN, 0.3f});

            Answer answer = ask(hybridNode(fusionOff()), "{\"query\": \"q\"}");

            verify(answer.exchange()).sendResponseHeaders(eq(200), anyLong());
            assertThat(answer.json()).contains("\"retrieval\": \"lexical\"").contains("\"c0\"");
        }

        @Test
        @DisplayName("answers from BM25 when the pipeline fails")
        void answersFromBm25WhenThePipelineFails() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broke")));

            Answer answer = ask(hybridNode(fusionOff()), "{\"query\": \"q\"}");

            verify(answer.exchange()).sendResponseHeaders(eq(200), anyLong());
            assertThat(answer.json()).contains("\"retrieval\": \"lexical\"");
        }

        @Test
        @DisplayName("still fails when BM25 cannot answer either")
        void failsWhenBm25CannotAnswer() throws IOException {
            when(embeddingClient.embed(anyString(), anyString()))
                .thenThrow(new LLMException("breaker open", LLMException.ErrorType.CIRCUIT_OPEN));
            when(lexical.retrieve(anyString(), anyInt(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("index closed"));

            Answer answer = ask(hybridNode(fusionOff()), "{\"query\": \"q\"}");

            verify(answer.exchange()).sendResponseHeaders(eq(500), anyLong());
        }

        @Test
        @DisplayName("still fails when it keeps no lexical index")
        void failsWithoutALexicalIndex() throws IOException {
            when(embeddingClient.embed(anyString(), anyString()))
                .thenThrow(new LLMException("breaker open", LLMException.ErrorType.CIRCUIT_OPEN));

            Answer answer = ask(vectorNode(null), "{\"query\": \"q\"}");

            verify(answer.exchange()).sendResponseHeaders(eq(500), anyLong());
        }

        @Test
        @DisplayName("does not start BM25 once the deadline has gone")
        void noFallbackAfterATimeout() throws IOException {
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(3_000);
                return new float[] {0.1f, 0.2f, 0.3f};
            });

            Answer answer = ask(hybridNode(fusionOff()),
                "{\"query\": \"q\", \"timeout_ms\": 200}");

            assertThat(answer.json()).contains("\"retrieval\": \"vector\"")
                .contains("\"timed_out\": true");
            verify(lexical, never()).retrieve(anyString(), anyInt(), any(), any(), anyLong());
        }
    }

    @Test
    @DisplayName("an embedding timeout reports the page the caller asked for")
    void embeddingTimeoutEchoesTheOffset() throws IOException {
        when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(3_000);
            return new float[] {0.1f, 0.2f, 0.3f};
        });

        Answer answer = ask(vectorNode(null),
            "{\"query\": \"q\", \"top_k\": 4, \"offset\": 5, \"timeout_ms\": 200}");

        assertThat(answer.json())
            .contains("\"page\": {\"offset\": 5, \"limit\": 4, \"returned\": 0, "
                + "\"next_offset\": null}");
    }
}
