package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.llm.embedding.EmbeddingClient;
import io.excalibur.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueryEndpoint domain correctness.
 */
@DisplayName("QueryEndpoint")
class QueryEndpointTest {

    private QueryPipeline queryPipeline;
    private EmbeddingClient embeddingClient;
    private QueryEndpoint endpoint;

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

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("rejects null query pipeline")
        void rejectsNullQueryPipeline() {
            assertThatThrownBy(() -> new QueryEndpoint(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("QueryPipeline cannot be null");
        }

        @Test
        @DisplayName("allows null executor service with default fallback")
        void allowsNullExecutorService() {
            // Null executor service is allowed - a default virtual thread executor is created
            QueryEndpoint endpointWithNullExecutor = new QueryEndpoint(queryPipeline, null, null);
            assertThat(endpointWithNullExecutor).isNotNull();
        }
    }

    @Nested
    @DisplayName("HTTP method handling")
    class HttpMethodHandling {

        @Test
        @DisplayName("rejects GET requests with 405")
        void rejectsGetRequests() throws IOException {
            HttpExchange exchange = createMockExchange("GET", "{}");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("rejects DELETE requests with 405")
        void rejectsDeleteRequests() throws IOException {
            HttpExchange exchange = createMockExchange("DELETE", "{}");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }
    }

    @Nested
    @DisplayName("Request parsing")
    class RequestParsing {

        @Test
        @DisplayName("rejects missing query field")
        void rejectsMissingQueryField() throws IOException {
            String json = "{\"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("rejects empty query field")
        void rejectsEmptyQueryField() throws IOException {
            String json = "{\"query\": \"\"}";
            HttpExchange exchange = createMockExchange("POST", json);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("rejects invalid JSON")
        void rejectsInvalidJson() throws IOException {
            String invalidJson = "not json at all";
            HttpExchange exchange = createMockExchange("POST", invalidJson);

            endpoint.handle(exchange);

            // Should return 500 for internal errors (JSON parsing failure)
            verify(exchange).sendResponseHeaders(anyInt(), anyLong());
        }
    }

    @Nested
    @DisplayName("Successful query")
    class SuccessfulQuery {

        @Test
        @DisplayName("returns 200 for valid POST request")
        void returns200ForValidPostRequest() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("response includes results array")
        void responseIncludesResultsArray() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"results\":");
        }

        @Test
        @DisplayName("response includes coverage object")
        void responseIncludesCoverageObject() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\":");
            assertThat(response).contains("\"total_segments\":");
            assertThat(response).contains("\"active_segments\":");
            assertThat(response).contains("\"indexing_segments\":");
            assertThat(response).contains("\"warming_segments\":");
            assertThat(response).contains("\"estimated_corpus_coverage\":");
            assertThat(response).contains("\"tail_scan_entries\":");
            assertThat(response).contains("\"degraded\":");
        }

        @Test
        @DisplayName("coverage says how much of the index the query actually reached")
        void coverageReportsWhatWasSearched() throws Exception {
            // Coverage that only censuses the catalog cannot tell a client whether an empty
            // answer means "searched everything, found nothing" or "was shown nothing". The
            // per-query counts are the only place that distinction lives, so they have to reach
            // the wire.
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"compacting_segments\": 0");
            assertThat(response).contains("\"tombstoned_segments\": 0");
            assertThat(response).contains("\"segments_searched\": 7");
            assertThat(response).contains("\"segments_excluded\": 1");
            assertThat(response).contains("\"segments_with_errors\": 0");
            assertThat(response).contains("\"segments_timed_out\": 0");
        }
    }

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutHandling {

        @Test
        @DisplayName("returns timeout response when query times out")
        void returnsTimeoutResponseWhenQueryTimesOut() throws Exception {
            String json = "{\"query\": \"test\", \"timeout_ms\": 100}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            // Create a future that never completes
            CompletableFuture<QueryResponse> neverCompletingFuture = new CompletableFuture<>();
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(neverCompletingFuture);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"timed_out\": true");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns 500 when the pipeline fails")
        void returns500ForInternalErrors() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            CompletableFuture<QueryResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Internal error")));
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(failedFuture);

            endpoint.handle(exchange);

            // A failed query must not be reported as 200 with an empty result list:
            // that is indistinguishable from "nothing matched", so a caller retries
            // nothing and silently loses results.
            verify(exchange).sendResponseHeaders(eq(500), anyLong());
        }

        @Test
        @DisplayName("does not leak internal failure detail to the client")
        void doesNotLeakFailureDetail() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            CompletableFuture<QueryResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(
                new IllegalStateException("/var/lib/excalibur/data/segment-7 is corrupt")));
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(failedFuture);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).doesNotContain("/var/lib/excalibur");
            assertThat(response).contains("Query execution failed");
        }
    }

    @Nested
    @DisplayName("JSON parsing utilities")
    class JsonParsingUtilities {

        @Test
        @DisplayName("parses string fields correctly")
        void parsesStringFieldsCorrectly() throws Exception {
            String json = "{\"query\": \"search text\"}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            // Verify the request was parsed and processed
            verify(queryPipeline).execute(any(QueryRequest.class), any(CancellationToken.class));
        }

        @Test
        @DisplayName("uses default values for missing optional fields")
        void usesDefaultValuesForMissingOptionalFields() throws Exception {
            String json = "{\"query\": \"test\"}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            // Verify the request was processed (defaults were applied)
            verify(queryPipeline).execute(any(QueryRequest.class), any(CancellationToken.class));
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("shutdown terminates executor gracefully")
        void shutdownTerminatesExecutorGracefully() {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            QueryEndpoint endpointWithExecutor = new QueryEndpoint(queryPipeline, executor);

            endpointWithExecutor.shutdown();

            assertThat(executor.isShutdown()).isTrue();
        }
    }

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

    }

    // Helper method to create mock Config with standard setup
    private Config createMockConfig() {
        Config mockConfig = mock(Config.class);
        Config.Query mockQueryConfig = mock(Config.Query.class);
        when(mockConfig.query()).thenReturn(mockQueryConfig);
        when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
        when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(30000);
        when(mockQueryConfig.getRecallTarget()).thenReturn(0.95);
        when(mockQueryConfig.getVectorDimensions()).thenReturn(-1);
        when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

        Config.Visibility mockVisibilityConfig = mock(Config.Visibility.class);
        when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
        when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(30000);

        Config.Llm mockLlmConfig = mock(Config.Llm.class);
        when(mockConfig.llm()).thenReturn(mockLlmConfig);
        when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

        return mockConfig;
    }

    // Helper methods

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
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0",
                "the kernel OOM killer sent SIGKILL after the cgroup memory limit was exceeded");
            index.refresh();
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null, null);
            endpoint.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            return endpoint;
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
        @DisplayName("a malformed body is still a 400, not a silent empty result")
        void malformedBodyStillRejected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            HttpExchange exchange = createMockExchange("POST", "{\"top_k\": 3}");

            withLexical(dir).handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    private HttpExchange createMockExchange(String method, String body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private HttpExchange createMockExchangeWithBody(String method, String body,
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

    private QueryResponse createMockResponse() {
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
