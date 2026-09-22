package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.query.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What QueryEndpoint answers on a node that cannot embed a query.
 *
 * <p>Two nodes fail that test for different reasons, and they must not be answered the
 * same way. A node with no embedding endpoint has no retrieval capability at all: it
 * answers 503, names the configuration key to set so the operator is not left guessing
 * between a dozen faults, says nothing about the inside of the process, and refuses
 * before reading the body so a malformed one changes nothing. A node with a lexical
 * index does have a capability, so refusing would misreport it: it answers 200 from
 * BM25, reports the same coverage fields as vector search over the segments it actually
 * holds, says the answer came from fewer signals than a full node would use, and names
 * the request fields it could not honour instead of dropping them silently.</p>
 */
@DisplayName("QueryEndpoint without embeddings")
class QueryEndpointLexicalTest extends QueryEndpointTestBase {

    @Nested
    @DisplayName("A node with no embedding endpoint")
    class Unconfigured {

        // The most common way to stand this server up wrong is to start it without an API key,
        // and every query then fails identically forever. The server already worked out why and
        // wrote it to the startup log; the client used to be told only "Query service is not
        // available", which is true of a dozen different faults and actionable for none of them.

        private QueryEndpoint unconfigured() {
            return new QueryEndpoint(queryPipeline, null, null, null, null);
        }

        @Test
        @DisplayName("answers 503 rather than failing the query some other way")
        void answers503() throws IOException {
            HttpExchange exchange = createMockExchange("POST", "{\"query\": \"anything\"}");

            unconfigured().handle(exchange);

            verify(exchange).sendResponseHeaders(eq(503), anyLong());
        }

        @Test
        @DisplayName("names the key to set, in the response body and not only the log")
        void bodyNamesTheKey() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"anything\"}", body);

            unconfigured().handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("an operator reading the response should not have to go to the logs")
                .contains("excalibur.llm.api-key")
                .contains("EXCALIBUR_LLM_API_KEY");
        }

        @Test
        @DisplayName("says nothing about the inside of the process")
        void leaksNothing() throws IOException {
            // A 503 body is the one error an unauthenticated-by-default deployment hands out
            // most readily, so it carries a configuration key and nothing else.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"anything\"}", body);

            unconfigured().handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("/");
        }

        @Test
        @DisplayName("refuses before reading the body, so a malformed one changes nothing")
        void refusesBeforeParsing() throws IOException {
            // The gap is a property of the deployment, not of the request. Answering 400 for a
            // bad body here would send someone editing their JSON over a missing API key.
            HttpExchange exchange = createMockExchange("POST", "not json at all");

            unconfigured().handle(exchange);

            verify(exchange).sendResponseHeaders(eq(503), anyLong());
        }
    }

    @Nested
    @DisplayName("A node with a lexical index and no embeddings")
    class LexicalOnly {

        // The payoff of not requiring the corpus to be embedded: this node has an index and can
        // answer from it, so refusing with 503 would be reporting a capability gap it does not
        // have.

        private QueryEndpoint withLexical(java.nio.file.Path dir) throws Exception {
            return withLexical(dir, null);
        }

        private QueryEndpoint withLexical(java.nio.file.Path dir,
                com.eonmux.excalibur.retrieval.expand.QueryExpander expander) throws Exception {
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0",
                "the kernel OOM killer sent SIGKILL after the cgroup memory limit was exceeded");
            index.refresh();
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null, null);
            endpoint.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, expander, java.util.List.of()));
            return endpoint;
        }

        /** An expander that widens short queries and hands longer ones through as typed. */
        private com.eonmux.excalibur.retrieval.expand.QueryExpander cappedAt(int chars) {
            return new com.eonmux.excalibur.retrieval.expand.QueryExpander() {
                @Override
                public java.util.List<com.eonmux.excalibur.retrieval.expand.SearchFormulation> expand(
                        String query) {
                    return java.util.List.of(
                        com.eonmux.excalibur.retrieval.expand.SearchFormulation.original(query));
                }

                @Override
                public int maxExpandableChars() {
                    return chars;
                }
            };
        }

        @Test
        @DisplayName("answers 200 from BM25 instead of refusing")
        void answersFromLexicalIndex(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"OOM killer SIGKILL\", \"top_k\": 3}", body);

            withLexical(dir).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"retrieval\": \"lexical\"");
            assertThat(json)
                .as("the hit is named by a chunk id a caller can resolve")
                .contains("kb:oom.txt:chunk:0");
        }

        @Test
        @DisplayName("refuses include_text before searching, not after")
        void refusesTextBeforeSearching(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // The point of resolving the reader first. Refusing after the search has run spends
            // a full BM25 pass on an answer that is thrown away, and on a node with no chunk
            // storage that is every request that asks for text.
            ExecutorService searches = mock(ExecutorService.class);
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0", "the kernel OOM killer sent SIGKILL");
            index.refresh();
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, searches, null, null);
            endpoint.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"OOM killer\", \"include_text\": true}", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("include_text");
            verify(searches, never()).submit(any(java.util.concurrent.Callable.class));
        }

        @Test
        @DisplayName("a pool that refuses the search leaves nothing behind in the registry")
        void aRefusedSearchDoesNotLeakARegistration(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // The query is registered before the search is submitted, so that DELETE can find
            // it for the whole time it runs. A submit that is refused between the two used to
            // leave the entry behind for the life of the process, and DELETE would report a
            // query that never started as live.
            com.eonmux.excalibur.query.executor.QueryRegistry registry =
                new com.eonmux.excalibur.query.executor.QueryRegistry();
            ExecutorService refusesEverything = mock(ExecutorService.class);
            when(refusesEverything.submit(any(java.util.concurrent.Callable.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0", "the kernel OOM killer sent SIGKILL");
            index.refresh();
            QueryEndpoint endpoint = new QueryEndpoint(
                queryPipeline, null, refusesEverything, registry, null);
            endpoint.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            HttpExchange exchange = createMockExchange("POST", "{\"query\": \"OOM killer\"}");

            endpoint.handle(exchange);

            assertThat(registry.getActiveQueryCount())
                .as("a query that never started is not an active query")
                .isZero();
            verify(exchange).sendResponseHeaders(eq(503), anyLong());
        }

        @Test
        @DisplayName("says the answer came from fewer signals than a full node would use")
        void reportsDegradedCoverage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\"}", body);

            withLexical(dir).handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("a caller must be able to tell 'all there was' from 'all we looked at'")
                .contains("\"degraded\": true");
        }

        @Test
        @DisplayName("reports the same coverage fields as vector search, so a client need not "
            + "branch on which path answered")
        void coverageIsTheSameShapeAsVectorSearch(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\"}", body);

            withLexical(dir).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json)
                .as("without these an empty answer reads the same whether the node searched "
                    + "the whole corpus and found nothing or never looked")
                .contains("\"segments_searched\": ")
                .contains("\"segments_excluded\": ")
                .contains("\"segments_with_errors\": ")
                .contains("\"segments_timed_out\": ")
                .contains("\"total_segments\": ")
                .contains("\"active_segments\": ")
                .contains("\"tail_scan_entries\": ");
        }

        @Test
        @DisplayName("says it searched the segment it actually holds, not a hardcoded 1.0")
        void coverageDescribesTheIndexItSearched(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\"}", body);

            withLexical(dir).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"segments_searched\": 1");
            assertThat(json).contains("\"total_segments\": 1");
        }

        @Test
        @DisplayName("an empty index reports it covered nothing rather than everything")
        void anEmptyIndexDoesNotClaimFullCoverage(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null, null);
            endpoint.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\"}", body);

            endpoint.handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("an empty answer at full coverage would read as proof the corpus lacks "
                    + "the term")
                .contains("\"estimated_corpus_coverage\": 0");
        }

        @Test
        @DisplayName("a malformed body is still a 400, not a silent empty result")
        void malformedBodyStillRejected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            HttpExchange exchange = createMockExchange("POST", "{\"top_k\": 3}");

            withLexical(dir).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("names the fields it could not honour instead of dropping them silently")
        void namesTheFieldsItCouldNotHonour(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            // threshold is validated, accepted, and then not applied: BM25 scores are not
            // cosine similarities and are not bounded by 1.0, so the number a caller tuned on
            // the vector path means nothing here. Accepting it and answering as though it had
            // been applied is the part that misleads.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"memory limit\", \"threshold\": 0.9, "
                    + "\"enable_llm_scoring\": true}", body);

            withLexical(dir).handle(exchange);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"ignored_fields\": [\"threshold\", \"enable_llm_scoring\"]");
        }

        @Test
        @DisplayName("says nothing was ignored when the request asked for nothing it cannot do")
        void reportsNoIgnoredFieldsForAPlainRequest(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\", \"top_k\": 3}", body);

            withLexical(dir).handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("top_k is honoured, so an empty list is the honest answer")
                .contains("\"ignored_fields\": []");
        }

        @Test
        @DisplayName("says so when a query was too long for the expansion this node applies")
        void namesExpansionWhenTheQueryIsTooLongForIt(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // A query past the expander's bound is answered by plain BM25 while every shorter
            // query is answered by an expanded search. Nothing in a result list shows that, so
            // a caller comparing the two is comparing retrieval strategies unknowingly.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST",
                "{\"query\": \"the kernel OOM killer sent SIGKILL after the cgroup memory "
                    + "limit was exceeded\"}", body);

            withLexical(dir, cappedAt(32)).handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"ignored_fields\": [\"query_expansion\"]");
        }

        @Test
        @DisplayName("says nothing about expansion when the query was short enough for it")
        void saysNothingWhenExpansionRan(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\"}", body);

            withLexical(dir, cappedAt(32)).handle(exchange);

            assertThat(body.toString(StandardCharsets.UTF_8))
                .contains("\"ignored_fields\": []");
        }

        @Test
        @DisplayName("a running lexical query can be found and cancelled")
        void aRunningLexicalQueryCanBeCancelled() throws Exception {
            // The lexical path returned before the query was ever registered, so
            // DELETE /v1/query/{id} answered 404 "not found or already completed" for a query
            // that was neither.
            java.util.concurrent.CountDownLatch searching = new java.util.concurrent.CountDownLatch(1);
            com.eonmux.excalibur.api.retrieval.LexicalQueryService blocking =
                mock(com.eonmux.excalibur.api.retrieval.LexicalQueryService.class);
            when(blocking.stats()).thenReturn(new com.eonmux.excalibur.ingest.lexical.ChunkIndexStats(
                "test", 1, 1, 1, 0, 0, java.util.Optional.empty(), false));
            when(blocking.search(anyString(), anyInt())).thenAnswer(invocation -> {
                searching.countDown();
                Thread.sleep(30_000);
                return java.util.List.of();
            });
            com.eonmux.excalibur.query.executor.QueryRegistry registry =
                new com.eonmux.excalibur.query.executor.QueryRegistry();
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, registry, null);
            endpoint.setLexicalQueryService(blocking);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\"}", body);

            Thread request = new Thread(() -> {
                try {
                    endpoint.handle(exchange);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            request.start();
            assertThat(searching.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(registry.getActiveQueryCount())
                .as("a query nobody can name is a query nobody can cancel")
                .isEqualTo(1);
            String queryId = registry.getActiveQueries().get(0).queryId();

            assertThat(registry.cancel(queryId)).isTrue();
            request.join(10_000);

            String json = body.toString(StandardCharsets.UTF_8);
            assertThat(json).contains("\"cancelled\": true");
            assertThat(json)
                .as("the caller withdrew the request; no deadline was missed")
                .contains("\"timed_out\": false")
                .contains("\"segments_timed_out\": 0");
            assertThat(registry.getActiveQueryCount())
                .as("the query is unregistered once it has answered")
                .isZero();
        }

        @Test
        @DisplayName("a search that outlives the request's budget is reported as timed out")
        void aSearchThatOutlivesItsBudgetSaysSo() throws Exception {
            // timed_out was a literal false on this path: written into every answer without
            // anything measuring the clock, so it asserted the one thing that had not been
            // checked.
            com.eonmux.excalibur.api.retrieval.LexicalQueryService slow =
                mock(com.eonmux.excalibur.api.retrieval.LexicalQueryService.class);
            when(slow.stats()).thenReturn(new com.eonmux.excalibur.ingest.lexical.ChunkIndexStats(
                "test", 1, 1, 1, 0, 0, java.util.Optional.empty(), false));
            when(slow.search(anyString(), anyInt())).thenAnswer(invocation -> {
                Thread.sleep(2_000);
                return java.util.List.of();
            });
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null, null);
            endpoint.setLexicalQueryService(slow);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"memory limit\", \"timeout_ms\": 50}", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("the caller asked for 50ms and did not get an answer in it")
                .contains("\"timed_out\": true");
        }
    }
}
