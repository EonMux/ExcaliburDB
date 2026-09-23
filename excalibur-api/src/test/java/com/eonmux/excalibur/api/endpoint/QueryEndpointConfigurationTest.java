package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.query.*;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.LlmConfig;
import com.eonmux.excalibur.config.QueryConfig;
import com.eonmux.excalibur.config.VisibilityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What QueryEndpoint takes from configuration, and the bounds it enforces on a request.
 *
 * <p>Covers the settings an operator can change and the server must actually read: the
 * configured score floor applies to a request that names no threshold, and a request
 * that names one overrides it. Also covers the caller-supplied values that size work
 * inside and outside the process — {@code oversampling_factor}, which bounds how much
 * of each segment's graph a search walks, and the query text itself, which becomes the
 * body of an outbound embedding or expansion call. Each is refused past its bound with
 * the bound named in the response, accepted exactly at it, and — for query length —
 * applied on the lexical path as well as the vector one.</p>
 */
@DisplayName("QueryEndpoint configuration and bounds")
class QueryEndpointConfigurationTest extends QueryEndpointTestBase {

    @Nested
    @DisplayName("Configuration wiring")
    class ConfigurationWiring {

        @Test
        @DisplayName("uses hardcoded default for topK when not specified in request")
        void usesDefaultTopK() throws Exception {
            Config mockConfig = createMockConfig();
            QueryEndpoint endpointWithConfig = new QueryEndpoint(
                queryPipeline, mockConfig, null, null, embeddingClient);

            // Request without top_k specified - uses hardcoded default 10
            String json = "{\"query\": \"test\"}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            when(queryPipeline.execute(requestCaptor.capture(), any(CancellationToken.class)))
                .thenReturn(future);

            endpointWithConfig.handle(exchange);

            // Verify the hardcoded default was used (not config value)
            QueryRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.topK()).isEqualTo(10); // Hardcoded default
        }

        @Test
        @DisplayName("a request that omits threshold gets the configured score floor")
        void usesConfiguredThreshold() throws Exception {
            Config mockConfig = createMockConfig();
            when(mockConfig.query().getThreshold()).thenReturn(0.42);
            QueryEndpoint endpointWithConfig = new QueryEndpoint(
                queryPipeline, mockConfig, null, null, embeddingClient);

            QueryRequest captured = capture(endpointWithConfig, "{\"query\": \"test\"}");

            assertThat(captured.threshold())
                .as("excalibur.query.threshold is the floor for requests that name none; a "
                    + "setting an operator can change and the server never reads is a trap")
                .isEqualTo(0.42f);
        }

        @Test
        @DisplayName("a threshold in the request overrides the configured one")
        void requestThresholdWins() throws Exception {
            Config mockConfig = createMockConfig();
            when(mockConfig.query().getThreshold()).thenReturn(0.42);
            QueryEndpoint endpointWithConfig = new QueryEndpoint(
                queryPipeline, mockConfig, null, null, embeddingClient);

            QueryRequest captured =
                capture(endpointWithConfig, "{\"query\": \"test\", \"threshold\": 0.1}");

            assertThat(captured.threshold()).isEqualTo(0.1f);
        }

        /** Runs one request through the endpoint and returns what reached the pipeline. */
        private QueryRequest capture(QueryEndpoint endpoint, String json) throws Exception {
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);
            ArgumentCaptor<QueryRequest> requestCaptor =
                ArgumentCaptor.forClass(QueryRequest.class);
            when(queryPipeline.execute(requestCaptor.capture(), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));

            endpoint.handle(exchange);

            return requestCaptor.getValue();
        }
    }

    @Nested
    @DisplayName("Query length")
    class QueryLength {

        /**
         * {@code oversampling_factor} multiplies {@code top_k} to size the HNSW beam, and the
         * beam is the only thing bounding how much of each segment's graph a search walks. Left
         * unbounded it is the cheapest denial of service this endpoint offers: a sub-hundred-byte
         * body, inside the read budget, that makes every candidate segment scan end to end.
         */
        @Test
        @DisplayName("refuses an oversampling factor beyond the bound, and names the bound")
        void refusesAnOversizedOversamplingFactor() throws IOException {
            QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, com.eonmux.excalibur.api.security.ApiSecurityConfig.builder()
                    .maxOversamplingFactor(8).build());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"log4j\", \"oversampling_factor\": 9}", body);

            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("a caller has to be able to lower it without guessing by how much")
                .contains("oversampling_factor")
                .contains("8");
        }

        @Test
        @DisplayName("accepts an oversampling factor exactly at the bound")
        void acceptsAnOversamplingFactorAtTheBound() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));
            QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, com.eonmux.excalibur.api.security.ApiSecurityConfig.builder()
                    .maxOversamplingFactor(8).build());
            HttpExchange exchange = createMockExchange(
                "POST", "{\"query\": \"log4j\", \"oversampling_factor\": 8}");

            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        /**
         * The other bounds on this endpoint exist because the value sizes something inside the
         * process. This one exists because the value leaves it: the query text is the body of an
         * embedding call and, on the lexical path, of an expansion call, so an unbounded query is
         * an outbound request whose size the caller chooses and the operator pays for.
         */
        @Test
        @DisplayName("refuses a query longer than the bound, and names the bound")
        void refusesAnOversizedQuery() throws IOException {
            QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, com.eonmux.excalibur.api.security.ApiSecurityConfig.builder()
                    .maxQueryChars(16).build());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"" + "x".repeat(17) + "\"}", body);

            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8))
                .as("a caller has to be able to shorten the query without guessing by how much")
                .contains("query")
                .contains("16");
        }

        @Test
        @DisplayName("accepts a query exactly at the bound")
        void acceptsAQueryAtTheBound() throws IOException {
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));
            QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, com.eonmux.excalibur.api.security.ApiSecurityConfig.builder()
                    .maxQueryChars(16).build());
            HttpExchange exchange = createMockExchange(
                "POST", "{\"query\": \"" + "x".repeat(16) + "\"}");

            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("the lexical path applies the same bound")
        void theLexicalPathIsBoundedToo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            // The two retrieval paths read and parse the body in different places, and a bound
            // that only one of them applied would leave the expansion call - the one a chat model
            // is billed for - unbounded on the path that makes it.
            com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex index =
                com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0", "the kernel OOM killer sent SIGKILL");
            index.refresh();
            QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null, null,
                com.eonmux.excalibur.api.security.ApiSecurityConfig.builder().maxQueryChars(16).build());
            bounded.setLexicalQueryService(new com.eonmux.excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            HttpExchange exchange = createMockExchange(
                "POST", "{\"query\": \"" + "x".repeat(17) + "\"}");

            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    // Helper method to create mock Config with standard setup
    /**
     * The endpoint's executor runs BM25 searches and relevancy scoring over the chunk index and
     * the storage engine, which the caller closes once shutdown returns. Interrupting that work
     * shortens nothing - a mapped read never looks at the flag - and on the chunk index's
     * NIOFSDirectory fallback it closes a channel every later query shares. So shutdown waits,
     * and says when it ran out of time.
     */
    @Nested
    @DisplayName("Shutting down")
    class ShuttingDown {

        @Test
        @DisplayName("an endpoint with nothing running shuts down clean")
        void idleShutdownIsClean() {
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, createMockConfig(),
                Executors.newVirtualThreadPerTaskExecutor(), null, embeddingClient);

            assertThat(endpoint.shutdown(java.time.Duration.ofSeconds(10))).isTrue();
        }

        @Test
        @DisplayName("work still running is waited for the whole wait, then reported rather than "
            + "interrupted, and nothing new starts")
        void runningWorkIsReportedNotInterrupted() throws Exception {
            Config config = createMockConfig();
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            QueryEndpoint endpoint =
                new QueryEndpoint(queryPipeline, config, executor, null, embeddingClient);
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean();
            executor.execute(() -> {
                reading.countDown();
                // Stands in for a mapped read, which never looks at the interrupt flag.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (release.getCount() > 0 && System.nanoTime() < deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(5));
                    interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
                }
            });
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

            java.time.Duration wait = java.time.Duration.ofMillis(300);
            long began = System.nanoTime();
            try {
                assertThat(endpoint.shutdown(wait))
                    .as("the storage and the chunk index must stay open under the read")
                    .isFalse();
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - began))
                    .as("the read was given all of the wait shutdown was handed")
                    .isGreaterThanOrEqualTo(wait);
                assertThat(interrupted).as("the running work was never interrupted").isFalse();
                assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            } finally {
                release.countDown();
            }
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isFalse();
            assertThat(endpoint.shutdown(java.time.Duration.ofSeconds(10)))
                .as("once the read has ended, a later shutdown finds nothing running")
                .isTrue();
        }

        @Test
        @DisplayName("work that ends within the wait shuts down clean, and a missing wait is "
            + "refused")
        void workEndingInTimeIsClean() throws Exception {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, createMockConfig(),
                executor, null, embeddingClient);
            CountDownLatch reading = new CountDownLatch(1);
            executor.execute(() -> {
                reading.countDown();
                java.util.concurrent.locks.LockSupport.parkNanos(
                    TimeUnit.MILLISECONDS.toNanos(200));
            });
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> endpoint.shutdown(null))
                .isInstanceOf(NullPointerException.class);
            assertThat(endpoint.shutdown(java.time.Duration.ofSeconds(10))).isTrue();
        }
    }

    private Config createMockConfig() {
        Config mockConfig = mock(Config.class);
        QueryConfig mockQueryConfig = mock(QueryConfig.class);
        when(mockConfig.query()).thenReturn(mockQueryConfig);
        when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
        when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(30000);
        when(mockQueryConfig.getThreshold()).thenReturn(0.0);
        when(mockQueryConfig.getVectorDimensions()).thenReturn(-1);
        when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

        VisibilityConfig mockVisibilityConfig = mock(VisibilityConfig.class);
        when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
        when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(30000);

        LlmConfig mockLlmConfig = mock(LlmConfig.class);
        when(mockConfig.llm()).thenReturn(mockLlmConfig);
        when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

        return mockConfig;
    }
}
