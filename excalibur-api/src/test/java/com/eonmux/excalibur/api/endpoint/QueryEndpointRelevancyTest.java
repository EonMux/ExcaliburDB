package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.cache.CorpusVersion;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relevancy scoring and the query cache on the routes the pipeline does not answer alone.
 *
 * <p>{@code enable_llm_scoring}, {@code llm_threshold} and {@code oversampling_factor} are
 * accepted on every route, and the cache is configured for the whole node. These pin down that
 * both are acted on whichever signals answer: a BM25 answer is scored and cached, a fused answer
 * is scored after fusion and cached whole, and every answer says whether it was scored and which
 * fields it did not act on.</p>
 */
@DisplayName("QueryEndpoint relevancy scoring and caching across routes")
class QueryEndpointRelevancyTest extends QueryEndpointTestBase {

    private static final CorpusVersion V1 = new CorpusVersion(3L, 0xABCDL, 2);

    private static final ChunkIndexStats STATS = new ChunkIndexStats("native", 10, 1, 10, 0, 0,
        Optional.empty(), false);

    private LexicalQueryService lexical;

    /** How deep each BM25 search was asked to rank, in order. */
    private final List<Integer> searchedDepths = new CopyOnWriteArrayList<>();

    /** The identifiers each scoring call was handed, in order. */
    private final List<List<String>> judged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUpLexical() {
        lexical = mock(LexicalQueryService.class);
        when(lexical.search(anyString(), anyInt(), any())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1);
            searchedDepths.add(limit);
            List<LexicalQueryService.Hit> hits = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, 10); i++) {
                hits.add(new LexicalQueryService.Hit("c" + i, 1.0 / (61 + i)));
            }
            return hits;
        });
        when(lexical.stats()).thenReturn(STATS);
        when(lexical.rrfK()).thenReturn(60);
        when(lexical.searchVersion()).thenReturn(OptionalLong.of(1L));
        when(queryPipeline.corpusVersion()).thenReturn(V1);
    }

    /** A model that judges the candidates it has a verdict for and leaves the rest alone. */
    private QueryPipeline.LlmScorer judging(Map<String, Float> verdicts) {
        return (query, results, threshold) -> {
            List<String> ids = new ArrayList<>();
            List<QueryResultItem> out = new ArrayList<>();
            for (QueryResultItem item : results) {
                ids.add(item.externalId());
                Float verdict = verdicts.get(item.externalId());
                if (verdict == null) {
                    out.add(item);
                } else if (verdict >= threshold) {
                    out.add(item.withScore(verdict));
                }
            }
            judged.add(ids);
            return out;
        };
    }

    private QueryEndpoint lexicalNode(QueryPipeline.LlmScorer scorer) {
        when(queryPipeline.getLlmScorer()).thenReturn(scorer);
        QueryEndpoint node = new QueryEndpoint(queryPipeline, null, null, null, null);
        node.setLexicalQueryService(lexical);
        node.setChunkText(id -> "the text of " + id);
        return node;
    }

    private QueryEndpoint hybridNode(QueryPipeline.LlmScorer scorer) {
        when(queryPipeline.getLlmScorer()).thenReturn(scorer);
        QueryEndpoint node = new QueryEndpoint(queryPipeline, null, null, null, embeddingClient);
        node.setLexicalQueryService(lexical);
        node.setChunkText(id -> "the text of " + id);
        return node;
    }

    private static QueryResponse dense(int count) {
        List<QueryResultItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new QueryResultItem("d" + i, 0.9f - i * 0.01f, null, 3L, i));
        }
        return new QueryResponse(items, IndexCoverage.builder()
            .totalSegments(2).activeSegments(2).segmentsSearched(2)
            .estimatedCorpusCoverage(1.0).degraded(false).build(), 12L, false, false);
    }

    /** Stubs the pipeline to answer as deep as it is asked, and records what it was asked. */
    private ArgumentCaptor<QueryRequest> pipelineAnswersAsAsked() {
        ArgumentCaptor<QueryRequest> asked = ArgumentCaptor.forClass(QueryRequest.class);
        when(queryPipeline.execute(asked.capture(), any(CancellationToken.class)))
            .thenAnswer(invocation -> {
                QueryRequest request = invocation.getArgument(0);
                return CompletableFuture.completedFuture(dense(request.topK()));
            });
        return asked;
    }

    private String ask(QueryEndpoint node, String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpExchange exchange = createMockExchangeWithBody("POST", body, out);
        node.handle(exchange);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("an answer from BM25 alone")
    class Lexical {

        @Test
        @DisplayName("is scored: ranked oversampling_factor deep, ordered by the model, cut to "
            + "the page")
        void isScored() throws IOException {
            QueryEndpoint node = lexicalNode(judging(Map.of("c5", 0.95f, "c1", 0.9f)));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2, "
                + "\"oversampling_factor\": 3}");

            assertThat(searchedDepths).containsExactly(6);
            assertThat(judged).hasSize(1);
            assertThat(judged.get(0)).hasSize(6);
            assertThat(json).contains("\"retrieval\": \"lexical\"")
                .contains("\"llm_scored\": true")
                .contains("\"ignored_fields\": []");
            assertThat(json.indexOf("\"c5\"")).isLessThan(json.indexOf("\"c1\""));
            assertThat(json).contains("\"score\": 0.950000")
                .doesNotContain("\"c0\"")
                .contains("\"returned\": 2");
        }

        @Test
        @DisplayName("keeps the BM25 order when the model fails, and is not cached")
        void fallsBackWhenTheModelFails() throws IOException {
            QueryPipeline.LlmScorer failing = (query, results, threshold) -> {
                throw new IllegalStateException("breaker open");
            };
            QueryEndpoint node = lexicalNode(failing);
            node.setQueryResultCache(new QueryResultCache(16));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            assertThat(json).contains("\"llm_scored\": false")
                .contains("\"from_cache\": false");
            assertThat(json.indexOf("\"c0\"")).isLessThan(json.indexOf("\"c1\""));
            assertThat(searchedDepths)
                .as("an answer whose scorer fell back is a moment's trouble, not an answer")
                .hasSize(2);
        }

        @Test
        @DisplayName("names the scoring fields when there is no model, and ranks only as deep "
            + "as the page")
        void namesScoringFieldsWithoutAModel() throws IOException {
            QueryEndpoint node = lexicalNode(null);

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2, "
                + "\"enable_llm_scoring\": true, \"llm_threshold\": 0.6, "
                + "\"oversampling_factor\": 3}");

            assertThat(searchedDepths).containsExactly(2);
            assertThat(json).contains("\"llm_scored\": false")
                .contains("\"ignored_fields\": [\"enable_llm_scoring\", \"llm_threshold\", "
                    + "\"oversampling_factor\"]");
        }

        @Test
        @DisplayName("is answered from the cache while the lexical index has not moved")
        void isCached() throws IOException {
            QueryEndpoint node = lexicalNode(null);
            node.setQueryResultCache(new QueryResultCache(16));

            String first = ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            String second = ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            assertThat(searchedDepths).hasSize(1);
            assertThat(first).contains("\"from_cache\": false");
            assertThat(second).contains("\"from_cache\": true")
                .contains("\"c0\"").contains("\"c1\"")
                .contains("\"ignored_fields\": []");
        }

        @Test
        @DisplayName("is searched again once the lexical index has moved")
        void isInvalidatedWhenTheIndexMoves() throws IOException {
            QueryEndpoint node = lexicalNode(null);
            node.setQueryResultCache(new QueryResultCache(16));

            ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            when(lexical.searchVersion()).thenReturn(OptionalLong.of(2L));
            String second = ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            assertThat(searchedDepths).hasSize(2);
            assertThat(second).contains("\"from_cache\": false");
        }

        @Test
        @DisplayName("is never cached by an index that cannot say what it would read")
        void neverCachedWithoutAVersion() throws IOException {
            when(lexical.searchVersion()).thenReturn(OptionalLong.empty());
            QueryEndpoint node = lexicalNode(null);
            node.setQueryResultCache(new QueryResultCache(16));

            ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            assertThat(searchedDepths).hasSize(2);
        }

        @Test
        @DisplayName("is never cached by an index whose version cannot be read at all")
        void neverCachedWhenTheVersionThrows() throws IOException {
            when(lexical.searchVersion()).thenThrow(new IllegalStateException("closed"));
            QueryEndpoint node = lexicalNode(null);
            node.setQueryResultCache(new QueryResultCache(16));

            ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            assertThat(searchedDepths).hasSize(2);
        }
    }

    @Nested
    @DisplayName("a fused answer")
    class Fused {

        @Test
        @DisplayName("is scored after fusion: the pipeline is asked for the candidate set "
            + "unscored, and the model judges both signals' candidates")
        void isScoredAfterFusion() throws IOException {
            ArgumentCaptor<QueryRequest> asked = pipelineAnswersAsAsked();
            QueryEndpoint node = hybridNode(judging(Map.of("c2", 0.99f)));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2, "
                + "\"oversampling_factor\": 3}");

            QueryRequest request = asked.getValue();
            assertThat(request.enableLlmScoring())
                .as("scoring the vector half before fusion would let reciprocal rank undo it")
                .isFalse();
            assertThat(request.topK()).isEqualTo(6);
            assertThat(request.oversamplingFactor()).isEqualTo(3);
            assertThat(searchedDepths).containsExactly(6);
            assertThat(judged).hasSize(1);
            assertThat(judged.get(0)).hasSize(6).contains("c0", "d0");
            assertThat(json).contains("\"retrieval\": \"hybrid\"")
                .contains("\"llm_scored\": true")
                .contains("\"returned\": 2")
                .contains("\"ignored_fields\": []");
            assertThat(json.indexOf("\"c2\""))
                .as("the model's pick, sixth after fusion, leads the page")
                .isGreaterThan(0)
                .isLessThan(json.indexOf("\"score\": 0.0"));
        }

        @Test
        @DisplayName("is answered whole from the cache: no embedding, no pipeline, no BM25")
        void isCachedWhole() throws IOException {
            pipelineAnswersAsAsked();
            QueryEndpoint node = hybridNode(judging(Map.of("c1", 0.99f)));
            node.setQueryResultCache(new QueryResultCache(16));

            ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            String second = ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            verify(queryPipeline, times(1))
                .execute(any(QueryRequest.class), any(CancellationToken.class));
            verify(embeddingClient, times(1)).embed(anyString(), anyString());
            assertThat(searchedDepths).hasSize(1);
            assertThat(judged).hasSize(1);
            assertThat(second).contains("\"retrieval\": \"hybrid\"")
                .contains("\"from_cache\": true")
                .contains("\"llm_scored\": true");
        }

        @Test
        @DisplayName("is searched again once the lexical index has moved, though the vector "
            + "corpus has not")
        void isInvalidatedByTheLexicalIndex() throws IOException {
            pipelineAnswersAsAsked();
            QueryEndpoint node = hybridNode(null);
            node.setQueryResultCache(new QueryResultCache(16));

            ask(node, "{\"query\": \"q\", \"top_k\": 2}");
            when(lexical.searchVersion()).thenReturn(OptionalLong.of(7L));
            ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            verify(queryPipeline, times(2))
                .execute(any(QueryRequest.class), any(CancellationToken.class));
        }

        @Test
        @DisplayName("that BM25 failed to join is answered from vector search, scored there, "
            + "and not cached under the fused key")
        void vectorAloneWhenBm25Fails() throws IOException {
            ArgumentCaptor<QueryRequest> asked = pipelineAnswersAsAsked();
            when(lexical.search(anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("index closed"));
            QueryEndpoint node = hybridNode(judging(Map.of("d4", 0.97f)));
            node.setQueryResultCache(new QueryResultCache(16));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2, "
                + "\"oversampling_factor\": 3}");
            ask(node, "{\"query\": \"q\", \"top_k\": 2, \"oversampling_factor\": 3}");

            assertThat(asked.getAllValues().get(0).topK()).isEqualTo(6);
            assertThat(json).contains("\"retrieval\": \"vector\"")
                .contains("\"llm_scored\": true")
                .contains("\"returned\": 2");
            assertThat(json.indexOf("\"d4\"")).isGreaterThan(0)
                .isLessThan(json.indexOf("\"d0\""));
            verify(queryPipeline, times(2))
                .execute(any(QueryRequest.class), any(CancellationToken.class));
        }

        @Test
        @DisplayName("whose vector half failed is answered from BM25, scored, cut to the page, "
            + "and not cached under the fused key")
        void bm25AloneWhenVectorFails() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.failedFuture(
                    new IllegalStateException("pipeline broke")));
            QueryEndpoint node = hybridNode(judging(Map.of("c4", 0.9f)));
            node.setQueryResultCache(new QueryResultCache(16));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2, \"threshold\": 0.3, "
                + "\"oversampling_factor\": 3}");
            ask(node, "{\"query\": \"q\", \"top_k\": 2, \"threshold\": 0.3, "
                + "\"oversampling_factor\": 3}");

            assertThat(json).contains("\"retrieval\": \"lexical\"")
                .contains("\"llm_scored\": true")
                .contains("\"returned\": 2")
                .contains("\"ignored_fields\": [\"threshold\"]");
            assertThat(json.indexOf("\"c4\"")).isGreaterThan(0)
                .isLessThan(json.indexOf("\"c0\""));
            assertThat(searchedDepths).containsExactly(6, 6);
        }
    }

    @Nested
    @DisplayName("an answer from vector search alone")
    class Vector {

        private QueryEndpoint vectorNode(QueryPipeline.LlmScorer scorer) {
            when(queryPipeline.getLlmScorer()).thenReturn(scorer);
            QueryEndpoint node = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient);
            node.setChunkText(id -> "the text of " + id);
            return node;
        }

        @Test
        @DisplayName("leaves scoring to the pipeline, and reports what the pipeline did")
        void pipelineScores() throws IOException {
            ArgumentCaptor<QueryRequest> asked = ArgumentCaptor.forClass(QueryRequest.class);
            when(queryPipeline.execute(asked.capture(), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(dense(2).withLlmScored(true)));
            QueryEndpoint node = vectorNode(judging(Map.of()));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2}");

            assertThat(asked.getValue().enableLlmScoring()).isTrue();
            assertThat(asked.getValue().topK()).isEqualTo(2);
            assertThat(judged).as("the endpoint does not score what Stage 4 already did")
                .isEmpty();
            assertThat(json).contains("\"retrieval\": \"vector\"")
                .contains("\"llm_scored\": true")
                .contains("\"ignored_fields\": []");
        }

        @Test
        @DisplayName("gives a floor, not an exact count, once a threshold has chosen from the "
            + "candidates")
        void scoredTotalIsAFloor() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(dense(1).withLlmScored(true)));
            QueryEndpoint node = vectorNode(judging(Map.of()));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 5}");

            assertThat(json).contains("\"relation\": \"at_least\"");
        }

        @Test
        @DisplayName("gives an exact count when the threshold chose from everything retrieval "
            + "found")
        void scoredTotalIsExactWhenRetrievalRanOut() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(
                    dense(1).withLlmScored(true).withRetrievalExhausted(true)));
            QueryEndpoint node = vectorNode(judging(Map.of()));

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 5}");

            assertThat(json).contains("\"result_total\": {\"value\": 1, \"relation\": \"exact\"}");
        }

        @Test
        @DisplayName("names the scoring fields on a node with no model")
        void namesScoringFieldsWithoutAModel() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(dense(2)));
            QueryEndpoint node = vectorNode(null);

            String json = ask(node, "{\"query\": \"q\", \"top_k\": 2, "
                + "\"enable_llm_scoring\": true, \"threshold\": 0.2, "
                + "\"oversampling_factor\": 2}");

            assertThat(json).contains("\"llm_scored\": false")
                .contains("\"ignored_fields\": [\"enable_llm_scoring\"]");
        }
    }
}
