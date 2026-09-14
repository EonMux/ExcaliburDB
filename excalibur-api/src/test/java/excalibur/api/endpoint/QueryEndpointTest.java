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
import java.util.concurrent.atomic.AtomicLong;

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

        @Test
        @DisplayName("a timeout response says what the index holds rather than implying it is empty")
        void aTimeoutResponseStillSaysWhatTheIndexHolds() throws Exception {
            // A response reporting no segments at all, because the query never got to look, is
            // indistinguishable from one served by an empty index.
            when(queryPipeline.segmentCensus()).thenReturn(
                IndexCoverage.builder().totalSegments(7).activeSegments(7));
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"timeout_ms\": 100}", responseBody);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(new CompletableFuture<>());

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"timed_out\": true");
            assertThat(response).contains("\"total_segments\": 7");
            assertThat(response)
                .as("nothing was read, whatever the index holds")
                .contains("\"estimated_corpus_coverage\": 0.0000");
        }

        @Test
        @DisplayName("the pipeline is charged the time that is left, not the time the client asked for")
        void thePipelineIsChargedWhatIsLeft() throws Exception {
            // Embedding is paid for out of the request's budget. Handing the pipeline the
            // client's full timeout afterwards gives it a deadline this handler has already
            // decided not to wait for.
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(300);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"timeout_ms\": 1000}", new ByteArrayOutputStream());
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));

            endpoint.handle(exchange);

            ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(request.capture(), any(CancellationToken.class));
            assertThat(request.getValue().timeoutMs())
                .as("the embedding call has already spent part of the budget")
                .isLessThan(1000)
                .isGreaterThan(0);
        }

        @Test
        @DisplayName("the deadline the pipeline was given is waited out, so its partial answer arrives")
        void theDeadlineGivenIsTheDeadlineWaited() throws Exception {
            // The pipeline answers a timeout with the results it already has. Abandoning it
            // before its own deadline replaces that answer with an empty one.
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(300);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"timeout_ms\": 1000}", new ByteArrayOutputStream());
            AtomicLong dispatchedAt = new AtomicLong();
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenAnswer(invocation -> {
                    dispatchedAt.set(System.nanoTime());
                    return new CompletableFuture<QueryResponse>();
                });

            endpoint.handle(exchange);
            long waitedMs = (System.nanoTime() - dispatchedAt.get()) / 1_000_000;

            ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(request.capture(), any(CancellationToken.class));
            assertThat(waitedMs)
                .as("giving the pipeline a deadline and then not waiting for it discards its answer")
                .isGreaterThan(request.getValue().timeoutMs());
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
        @DisplayName("answers a withdrawn query 200 with cancelled, not an empty match")
        void reportsACancelledQuery() throws Exception {
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"top_k\": 10}", responseBody);

            CompletableFuture<QueryResponse> withdrawn = new CompletableFuture<>();
            withdrawn.cancel(true);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(withdrawn);

            endpoint.handle(exchange);

            // 200 with nothing found and 200 with the request withdrawn are different answers.
            // Without `cancelled` the caller reads an empty result list as a corpus that holds
            // nothing matching, and stops looking.
            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"cancelled\": true");
            assertThat(response).contains("\"timed_out\": false");
            assertThat(response)
                .as("a query that was withdrawn covered none of the corpus and says so")
                .contains("\"degraded\": true");
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
    @DisplayName("Result text")
    class ResultText {

        /** A corpus of one chunk, keyed the way a result names it. */
        private final java.util.function.Function<String, String> corpus =
            java.util.Map.of("docs:guide.md:chunk:0", "the passage itself")::get;

        private QueryEndpoint endpointOverCorpus(int maxChars) {
            QueryEndpoint over = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder()
                    .maxResultTextChars(maxChars).build());
            over.setChunkText(corpus);
            return over;
        }

        private QueryResponse oneHit() {
            return new QueryResponse(
                java.util.List.of(new QueryResultItem(
                    "docs:guide.md:chunk:0", 0.9f, null, 3L, 7L)),
                IndexCoverage.builder().totalSegments(1).activeSegments(1)
                    .estimatedCorpusCoverage(1.0).build(),
                12L, false, false);
        }

        private String answer(QueryEndpoint target, String requestJson) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", requestJson, body);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(oneHit()));
            target.handle(exchange);
            return body.toString(StandardCharsets.UTF_8);
        }

        /**
         * The identifier-only answer is what every existing client parses. Adding text to it
         * unasked would double the size of every response those clients already handle, for a
         * field none of them read.
         */
        @Test
        @DisplayName("omits text unless the request asks for it")
        void omitsTextByDefault() throws IOException {
            String json = answer(endpointOverCorpus(4096), "{\"query\": \"log4j\"}");

            assertThat(json).doesNotContain("\"text\"");
            assertThat(json).contains("docs:guide.md:chunk:0");
        }

        /**
         * Without this a search is only half an answer: the caller is handed identifiers it can
         * resolve only against the store the corpus was ingested from, which on a crawled corpus
         * it does not have.
         */
        @Test
        @DisplayName("returns the stored passage when the request asks for it")
        void returnsTextWhenAsked() throws IOException {
            String json = answer(endpointOverCorpus(4096),
                "{\"query\": \"log4j\", \"include_text\": true}");

            assertThat(json).contains("\"text\": \"the passage itself\"");
            assertThat(json)
                .as("a caller has to be able to tell a whole chunk from a clipped one")
                .contains("\"text_truncated\": false");
        }

        @Test
        @DisplayName("clips a passage past the bound and says that it did")
        void clipsPastTheBound() throws IOException {
            String json = answer(endpointOverCorpus(3),
                "{\"query\": \"log4j\", \"include_text\": true}");

            assertThat(json).contains("\"text\": \"the\"");
            assertThat(json).contains("\"text_truncated\": true");
        }

        /**
         * A result the index still ranks whose text storage no longer holds. Null is the honest
         * answer, and the identifier and score are still worth returning.
         */
        @Test
        @DisplayName("reports null text for a chunk storage no longer holds")
        void nullTextForAMissingChunk() throws IOException {
            QueryEndpoint over = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder().build());
            over.setChunkText(id -> null);

            String json = answer(over, "{\"query\": \"log4j\", \"include_text\": true}");

            assertThat(json).contains("\"text\": null");
        }

        /**
         * Silently ranking without the text the caller asked for would have it quote passages it
         * never received. A node with no chunk storage cannot honour the field, and says so.
         */
        @Test
        @DisplayName("refuses include_text on a node that keeps no chunk storage, and says why")
        void refusesTextWhenNoStorage() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"log4j\", \"include_text\": true}", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("include_text");
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

    // Helper method to create mock Config with standard setup
    private Config createMockConfig() {
        Config mockConfig = mock(Config.class);
        Config.Query mockQueryConfig = mock(Config.Query.class);
        when(mockConfig.query()).thenReturn(mockQueryConfig);
        when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
        when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(30000);
        when(mockQueryConfig.getThreshold()).thenReturn(0.0);
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
        @DisplayName("refuses include_text before searching, not after")
        void refusesTextBeforeSearching(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            // The point of resolving the reader first. Refusing after the search has run spends
            // a full BM25 pass on an answer that is thrown away, and on a node with no chunk
            // storage that is every request that asks for text.
            ExecutorService searches = mock(ExecutorService.class);
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0", "the kernel OOM killer sent SIGKILL");
            index.refresh();
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, searches, null, null);
            endpoint.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
            excalibur.query.executor.QueryRegistry registry =
                new excalibur.query.executor.QueryRegistry();
            ExecutorService refusesEverything = mock(ExecutorService.class);
            when(refusesEverything.submit(any(java.util.concurrent.Callable.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0", "the kernel OOM killer sent SIGKILL");
            index.refresh();
            QueryEndpoint endpoint = new QueryEndpoint(
                queryPipeline, null, refusesEverything, registry, null);
            endpoint.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            QueryEndpoint endpoint = new QueryEndpoint(queryPipeline, null, null, null, null);
            endpoint.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
        @DisplayName("a running lexical query can be found and cancelled")
        void aRunningLexicalQueryCanBeCancelled() throws Exception {
            // The lexical path returned before the query was ever registered, so
            // DELETE /v1/query/{id} answered 404 "not found or already completed" for a query
            // that was neither.
            java.util.concurrent.CountDownLatch searching = new java.util.concurrent.CountDownLatch(1);
            excalibur.api.retrieval.LexicalQueryService blocking =
                mock(excalibur.api.retrieval.LexicalQueryService.class);
            when(blocking.stats()).thenReturn(new excalibur.ingest.lexical.ChunkIndexStats(
                "test", 1, 1, 1, 0, 0, java.util.Optional.empty(), false));
            when(blocking.search(anyString(), anyInt())).thenAnswer(invocation -> {
                searching.countDown();
                Thread.sleep(30_000);
                return java.util.List.of();
            });
            excalibur.query.executor.QueryRegistry registry =
                new excalibur.query.executor.QueryRegistry();
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
            excalibur.api.retrieval.LexicalQueryService slow =
                mock(excalibur.api.retrieval.LexicalQueryService.class);
            when(slow.stats()).thenReturn(new excalibur.ingest.lexical.ChunkIndexStats(
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
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder()
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
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder()
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
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder()
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
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder()
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
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:oom.txt:chunk:0", "the kernel OOM killer sent SIGKILL");
            index.refresh();
            QueryEndpoint bounded = new QueryEndpoint(queryPipeline, null, null, null, null,
                excalibur.api.security.ApiSecurityConfig.builder().maxQueryChars(16).build());
            bounded.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
                index, null, java.util.List.of()));
            HttpExchange exchange = createMockExchange(
                "POST", "{\"query\": \"" + "x".repeat(17) + "\"}");

            bounded.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

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

        private excalibur.ingest.lexical.ChunkLexicalIndex indexWithATermOnlyBm25Finds(
                java.nio.file.Path dir) throws Exception {
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            index.add("kb:cve.txt:chunk:0",
                "CVE-2021-44228 affects log4j 2.14.1 through the JNDI lookup substitution");
            index.refresh();
            return index;
        }

        private QueryEndpoint hybridEndpoint(java.nio.file.Path dir, Config config)
                throws Exception {
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, config, null, null, embeddingClient);
            hybrid.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                excalibur.ingest.lexical.ChunkLexicalIndex.open(dir, 8.0);
            for (int i = 0; i < 6; i++) {
                index.add("kb:cve.txt:chunk:" + i, "CVE-2021-44228 log4j JNDI lookup " + i);
            }
            index.refresh();
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, null, null, null, embeddingClient);
            hybrid.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
            hybrid.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
            excalibur.ingest.lexical.ChunkLexicalIndex index =
                indexWithATermOnlyBm25Finds(dir);
            QueryEndpoint hybrid = new QueryEndpoint(
                queryPipeline, null, null, null, embeddingClient);
            hybrid.setLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
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
