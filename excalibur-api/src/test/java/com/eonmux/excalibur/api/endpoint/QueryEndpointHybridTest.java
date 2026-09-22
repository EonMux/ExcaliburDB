package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.query.*;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What QueryEndpoint answers on a node holding both a vector index and a lexical one.
 *
 * <p>The BM25 index over the chunks is written, compacted, erasure-swept and reported
 * on {@code /v1/stats} whether or not the corpus is embedded. A node with both used to
 * read only the vector half of it, which made the lexical index pure cost on exactly
 * the deployments that paid most for it. These tests cover the fused path: the exact
 * term a dense vector blurs is found, the vector signal's own hits survive the fusion,
 * the response says {@code hybrid} so a caller knows which signals answered, and a node
 * that holds only one of the two still reports itself honestly.</p>
 */
@DisplayName("QueryEndpoint hybrid retrieval")
class QueryEndpointHybridTest extends QueryEndpointTestBase {

    @Nested
    @DisplayName("A node holding both signals")
    class Hybrid {

        // The BM25 index over the chunks is written, compacted, erasure-swept and reported on
        // /v1/stats whether or not the corpus is embedded. A node with both used to read only
        // the vector half of it, which made the lexical index pure cost on exactly the
        // deployments that paid most for it.

        private java.util.List<QueryResultItem> denseHits() {
            return java.util.List.of(
                new QueryResultItem("kb:gc.txt:chunk:0", 0.91f, null, 3L, 0L),
                new QueryResultItem("kb:heap.txt:chunk:0", 0.78f, null, 3L, 1L));
        }

        private QueryResponse denseResponse() {
            return new QueryResponse(denseHits(), IndexCoverage.builder()
                .totalSegments(4).activeSegments(4).segmentsSearched(4)
                .estimatedCorpusCoverage(1.0).degraded(false).build(), 12L, false, false);
        }

        private com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex indexWithATermOnlyBm25Finds(
                java.nio.file.Path dir) throws Exception {
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:cve.txt:chunk:0",
                "CVE-2021-44228 affects log4j 2.14.1 through the JNDI lookup substitution");
            index.refresh();
            return index;
        }

        private QueryEndpoint hybridEndpoint(java.nio.file.Path dir, Config config)
                throws Exception {
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, config, null, null, embeddingClient);
            hybrid.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                indexWithATermOnlyBm25Finds(dir), null, java.util.List.of()));
            return hybrid;
        }

        @Test
        @DisplayName("fuses BM25 with vector search and says so")
        void fusesBothSignals(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"CVE-2021-44228 log4j\", \"top_k\": 5}", body);

            hybridEndpoint(dir, null).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"retrieval\": \"hybrid\"");
            assertThat(json)
                .as("the exact term a dense vector blurs is what the BM25 signal is there for")
                .contains("kb:cve.txt:chunk:0");
            assertThat(json)
                .as("the vector signal's hits are still in the answer")
                .contains("kb:gc.txt:chunk:0");
        }

        @Test
        @DisplayName("names the signals that returned each result")
        void attributesEachResult(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"CVE-2021-44228 log4j\"}", body);

            hybridEndpoint(dir, null).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("a fused score is not a similarity, and the breakdown is what says so")
                .contains("\"signals\": [\"bm25\"]");
            assertThat(json).contains("\"signals\": [\"vector\"]");
        }

        @Test
        @DisplayName("reports the vector pipeline's coverage, which is what it still measures")
        void coverageComesFromTheVectorPipeline(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"CVE-2021-44228\"}", body);

            hybridEndpoint(dir, null).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"segments_searched\": 4");
            assertThat(json).contains("\"total_segments\": 4");
        }

        @Test
        @DisplayName("an operator can turn the fusion off and get vector-only ranking back")
        void configurationTurnsFusionOff(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            java.util.Map<String, String> props = new java.util.HashMap<>();
            props.put("excalibur.query.hybrid-retrieval", "false");
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"CVE-2021-44228 log4j\"}", body);

            hybridEndpoint(dir, new Config(props)).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"retrieval\": \"vector\"");
            assertThat(json).doesNotContain("kb:cve.txt:chunk:0");
        }

        @Test
        @DisplayName("a node with no lexical index says the answer came from vectors alone")
        void vectorOnlyNodeSaysWhichSignalAnswered() throws Exception {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"heap growth\"}", body);

            endpoint.handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("a field that only appears on some answers is a field nobody reads")
                .contains("\"retrieval\": \"vector\"");
        }

        @Test
        @DisplayName("an embedding endpoint that misses the budget answers from the BM25 half "
            + "instead of returning nothing")
        void anEmbeddingTimeoutFallsBackToTheSignalThatFinished(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // The BM25 search needs no embedding and has already finished by the time the
            // embedding call misses its deadline. Answering empty here throws away a complete
            // answer the node is holding, and reports the corpus as having nothing to say.
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(3_000);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"CVE-2021-44228 log4j\", \"timeout_ms\": 800}", body);

            hybridEndpoint(dir, null).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("one signal answered, and the response names the one that did")
                .contains("\"retrieval\": \"lexical\"")
                .contains("kb:cve.txt:chunk:0");
            assertThat(json)
                .as("the request still missed its deadline, and hiding that would make a "
                    + "degraded answer look like a whole one")
                .contains("\"timed_out\": true");
        }

        @Test
        @DisplayName("an embedding endpoint that fails answers from the BM25 half instead of "
            + "failing the whole query")
        void anEmbeddingFailureFallsBackToTheSignalThatFinished(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // An open circuit breaker, a rejected key, a 429 or a 5xx all arrive here. None of
            // them is a fact about the corpus, and none of them is a reason to throw away the
            // BM25 answer the node is already holding - which is exactly what the degradation
            // table promises for a missing embedding endpoint.
            when(embeddingClient.embed(anyString(), anyString())).thenThrow(
                new com.eonmux.excalibur.llm.LLMException("Circuit breaker is open",
                    com.eonmux.excalibur.llm.LLMException.ErrorType.CIRCUIT_OPEN));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"CVE-2021-44228 log4j\", \"top_k\": 5}", body);

            hybridEndpoint(dir, null).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("one signal down is a weaker answer, not no answer")
                .contains("\"retrieval\": \"lexical\"")
                .contains("kb:cve.txt:chunk:0");
            assertThat(json)
                .as("nothing missed a deadline here, and saying it did would misreport why the "
                    + "answer is degraded")
                .contains("\"timed_out\": false");
            assertThat(json)
                .as("the answer came from fewer signals than this node has")
                .contains("\"degraded\": true");
        }

        @Test
        @DisplayName("an embedding that comes back non-finite falls back too, rather than 503")
        void aNonFiniteEmbeddingFallsBackToo(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // A NaN component makes every distance undefined, so the vector half genuinely
            // cannot answer. The lexical half is unaffected by it.
            when(embeddingClient.embed(anyString(), anyString()))
                .thenReturn(new float[]{0.1f, Float.NaN, 0.3f});
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"CVE-2021-44228 log4j\", \"top_k\": 5}", body);

            hybridEndpoint(dir, null).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"retrieval\": \"lexical\"")
                .contains("kb:cve.txt:chunk:0");
        }

        @Test
        @DisplayName("an embedding failure on a node with no lexical index is still a 500")
        void anEmbeddingFailureWithNothingToFallBackToIsStillAFailure() throws Exception {
            // Degradation needs something to degrade to. With no second signal the request has
            // no answer, and 200 with an empty list would be indistinguishable from "nothing
            // matched".
            when(embeddingClient.embed(anyString(), anyString())).thenThrow(
                new com.eonmux.excalibur.llm.LLMException("Circuit breaker is open",
                    com.eonmux.excalibur.llm.LLMException.ErrorType.CIRCUIT_OPEN));
            HttpExchange exchange = createMockExchange(
                "POST", "{\"query\": \"CVE-2021-44228 log4j\"}");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(500), anyLong());
        }

        @Test
        @DisplayName("a vector pipeline that misses its deadline also falls back to the BM25 "
            + "answer, and does not claim vector search produced it")
        void aPipelineTimeoutFallsBackToo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            // The mirror of the embedding timeout, and the one that used to answer
            // "retrieval": "vector" with an empty list for a query vector search is exactly
            // what failed. Which half missed the deadline does not change what the other half
            // is holding.
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(new CompletableFuture<>());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"CVE-2021-44228 log4j\", \"timeout_ms\": 400}", body);

            hybridEndpoint(dir, null).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"retrieval\": \"lexical\"");
            assertThat(json).contains("kb:cve.txt:chunk:0");
            assertThat(json).contains("\"timed_out\": true");
        }

        @Test
        @DisplayName("the fallback returns no more than the caller asked for")
        void theFallbackHonoursTopK(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            // top_k is a validated bound, checked against excalibur.api.max-top-k, and it sizes
            // what the caller has to receive and parse. A path that over-fetches internally and
            // then renders everything it fetched hands back more than the bound allows.
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            for (int i = 0; i < 6; i++) {
                index.add("kb:cve.txt:chunk:" + i, "CVE-2021-44228 log4j JNDI lookup " + i);
            }
            index.refresh();
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, null, null, null, embeddingClient);
            hybrid.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(3_000);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"CVE-2021-44228 log4j\", \"top_k\": 2, "
                    + "\"timeout_ms\": 800}", body);

            hybrid.handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"retrieval\": \"lexical\"");
            assertThat(json.split("\"document_id\"", -1).length - 1)
                .as("two asked for, two returned")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("the fallback reports the signal that ran, not whether it matched")
        void theFallbackDoesNotDependOnMatching(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // A response shape that changes with whether the query happened to match is one a
            // client cannot branch on. BM25 running and finding nothing is a fact about the
            // corpus; it is reported as such rather than as a vector timeout.
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(3_000);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"nothing in this corpus mentions zzzqqx\", "
                    + "\"timeout_ms\": 800}", body);

            hybridEndpoint(dir, null).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"retrieval\": \"lexical\"");
            assertThat(json).contains("\"results\": []");
            assertThat(json).contains("\"timed_out\": true");
        }

        @Test
        @DisplayName("a pool that will not take the BM25 search degrades to vector search "
            + "rather than failing the query")
        void aRejectedSubmitIsNotAQueryFailure(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // A saturated or shutting-down pool refuses the task outright, which is a different
            // failure from the search itself throwing: it happens before the query has a second
            // signal at all, and it used to reach the handler's catch-all as a 500.
            ExecutorService real = Executors.newVirtualThreadPerTaskExecutor();
            java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(true);
            ExecutorService refusesTheFirstTask = mock(ExecutorService.class);
            when(refusesTheFirstTask.submit(any(java.util.concurrent.Callable.class)))
                .thenAnswer(invocation -> {
                    if (first.getAndSet(false)) {
                        throw new java.util.concurrent.RejectedExecutionException("saturated");
                    }
                    return real.submit((java.util.concurrent.Callable<?>) invocation.getArgument(0));
                });
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, null, refusesTheFirstTask, null, embeddingClient);
            hybrid.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                indexWithATermOnlyBm25Finds(dir), null, java.util.List.of()));
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"CVE-2021-44228\"}", body);

            hybrid.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"retrieval\": \"vector\"")
                .contains("kb:gc.txt:chunk:0");
            real.shutdownNow();
        }

        @Test
        @DisplayName("a broken lexical index degrades to vector search rather than failing "
            + "the query")
        void aLexicalFailureIsNotAQueryFailure(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                indexWithATermOnlyBm25Finds(dir);
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, null, null, null, embeddingClient);
            hybrid.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            index.close();
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(denseResponse()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"CVE-2021-44228\"}", body);

            hybrid.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("one signal down is a weaker answer, not no answer - and the response has "
                    + "to say which signals actually ran")
                .contains("\"retrieval\": \"vector\"");
            assertThat(json).contains("kb:gc.txt:chunk:0");
        }
    }
}
