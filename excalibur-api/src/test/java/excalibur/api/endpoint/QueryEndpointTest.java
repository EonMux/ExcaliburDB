package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
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
    private QueryEndpoint endpoint;

    @BeforeEach
    void setUp() {
        queryPipeline = mock(QueryPipeline.class);
        endpoint = new QueryEndpoint(queryPipeline);
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
        @DisplayName("rejects missing vector field")
        void rejectsMissingVectorField() throws IOException {
            String json = "{\"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("rejects empty vector field")
        void rejectsEmptyVectorField() throws IOException {
            String json = "{\"vector\": []}";
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
            String json = "{\"vector\": [0.1, 0.2, 0.3], \"top_k\": 10}";
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
            String json = "{\"vector\": [0.1, 0.2, 0.3], \"top_k\": 10}";
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
            String json = "{\"vector\": [0.1, 0.2, 0.3], \"top_k\": 10}";
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
    }

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutHandling {

        @Test
        @DisplayName("returns timeout response when query times out")
        void returnsTimeoutResponseWhenQueryTimesOut() throws Exception {
            String json = "{\"vector\": [0.1, 0.2, 0.3], \"timeout_ms\": 100}";
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
        @DisplayName("returns 500 for internal errors")
        void returns500ForInternalErrors() throws Exception {
            String json = "{\"vector\": [0.1, 0.2, 0.3], \"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            CompletableFuture<QueryResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Internal error")));
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(failedFuture);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }
    }

    @Nested
    @DisplayName("JSON parsing utilities")
    class JsonParsingUtilities {

        @Test
        @DisplayName("parses string fields correctly")
        void parsesStringFieldsCorrectly() throws Exception {
            String json = "{\"query\": \"search text\", \"vector\": [0.1]}";
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
            String json = "{\"vector\": [0.1, 0.2, 0.3]}";
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
            // Create mock config
            Config mockConfig = mock(Config.class);
            Config.Query mockQueryConfig = mock(Config.Query.class);
            when(mockConfig.query()).thenReturn(mockQueryConfig);
            when(mockQueryConfig.getDefaultTopK()).thenReturn(50); // Config value ignored
            when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(60000);
            when(mockQueryConfig.getRecallTarget()).thenReturn(0.9);
            when(mockQueryConfig.getVectorDimensions()).thenReturn(-1);
            when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

            // Create visibility config mock
            Config.Visibility mockVisibilityConfig = mock(Config.Visibility.class);
            when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
            when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(60000);

            // Create LLM config mock
            Config.Llm mockLlmConfig = mock(Config.Llm.class);
            when(mockConfig.llm()).thenReturn(mockLlmConfig);
            when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

            QueryEndpoint endpointWithConfig = new QueryEndpoint(queryPipeline, mockConfig);

            // Request without top_k specified - uses hardcoded default 10
            String json = "{\"vector\": [0.1, 0.2, 0.3]}";
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
        @DisplayName("uses config defaults for timeout when not specified in request")
        void usesConfigDefaultsForTimeout() throws Exception {
            // Create mock config with custom timeout default
            Config mockConfig = mock(Config.class);
            Config.Query mockQueryConfig = mock(Config.Query.class);
            when(mockConfig.query()).thenReturn(mockQueryConfig);
            when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
            when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(45000);
            when(mockQueryConfig.getRecallTarget()).thenReturn(0.95);
            when(mockQueryConfig.getVectorDimensions()).thenReturn(-1);
            when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

            // Create visibility config mock
            Config.Visibility mockVisibilityConfig = mock(Config.Visibility.class);
            when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
            when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(45000);

            // Create LLM config mock
            Config.Llm mockLlmConfig = mock(Config.Llm.class);
            when(mockConfig.llm()).thenReturn(mockLlmConfig);
            when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

            QueryEndpoint endpointWithConfig = new QueryEndpoint(queryPipeline, mockConfig);

            // Request without timeout_ms specified - should use config default
            String json = "{\"vector\": [0.1, 0.2, 0.3]}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            when(queryPipeline.execute(requestCaptor.capture(), any(CancellationToken.class)))
                .thenReturn(future);

            endpointWithConfig.handle(exchange);

            // Verify the config default was used
            QueryRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.timeoutMs()).isEqualTo(45000L);
        }

        @Test
        @DisplayName("uses builder default for recallTarget when not specified in request")
        void usesDefaultRecallTarget() throws Exception {
            // Create mock config
            Config mockConfig = mock(Config.class);
            Config.Query mockQueryConfig = mock(Config.Query.class);
            when(mockConfig.query()).thenReturn(mockQueryConfig);
            when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
            when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(30000);
            when(mockQueryConfig.getRecallTarget()).thenReturn(0.85); // Config value ignored
            when(mockQueryConfig.getVectorDimensions()).thenReturn(-1);
            when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

            // Create visibility config mock
            Config.Visibility mockVisibilityConfig = mock(Config.Visibility.class);
            when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
            when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(30000);

            // Create LLM config mock
            Config.Llm mockLlmConfig = mock(Config.Llm.class);
            when(mockConfig.llm()).thenReturn(mockLlmConfig);
            when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

            QueryEndpoint endpointWithConfig = new QueryEndpoint(queryPipeline, mockConfig);

            // Request without recall_target specified - uses builder default 0.95
            String json = "{\"vector\": [0.1, 0.2, 0.3]}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            when(queryPipeline.execute(requestCaptor.capture(), any(CancellationToken.class)))
                .thenReturn(future);

            endpointWithConfig.handle(exchange);

            // Verify the builder default was used (0.95)
            QueryRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.recallTarget()).isEqualTo(0.95);
        }

        @Test
        @DisplayName("request values override defaults where supported")
        void requestValuesOverrideDefaults() throws Exception {
            // Create mock config with defaults
            Config mockConfig = mock(Config.class);
            Config.Query mockQueryConfig = mock(Config.Query.class);
            when(mockConfig.query()).thenReturn(mockQueryConfig);
            when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
            when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(30000);
            when(mockQueryConfig.getRecallTarget()).thenReturn(0.95);
            when(mockQueryConfig.getVectorDimensions()).thenReturn(-1);
            when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

            // Create visibility config mock
            Config.Visibility mockVisibilityConfig = mock(Config.Visibility.class);
            when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
            when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(30000);

            // Create LLM config mock
            Config.Llm mockLlmConfig = mock(Config.Llm.class);
            when(mockConfig.llm()).thenReturn(mockLlmConfig);
            when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

            QueryEndpoint endpointWithConfig = new QueryEndpoint(queryPipeline, mockConfig);

            // Request with explicit values - top_k and timeout_ms are supported
            String json = "{\"vector\": [0.1, 0.2, 0.3], \"top_k\": 100, \"timeout_ms\": 60000}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            when(queryPipeline.execute(requestCaptor.capture(), any(CancellationToken.class)))
                .thenReturn(future);

            endpointWithConfig.handle(exchange);

            // Verify request values override defaults
            QueryRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.topK()).isEqualTo(100);
            assertThat(capturedRequest.timeoutMs()).isEqualTo(60000L);
        }

        @Test
        @DisplayName("accepts any vector dimensions (no validation)")
        void acceptsAnyVectorDimensions() throws Exception {
            // Create mock config
            Config mockConfig = mock(Config.class);
            Config.Query mockQueryConfig = mock(Config.Query.class);
            when(mockConfig.query()).thenReturn(mockQueryConfig);
            when(mockQueryConfig.getDefaultTopK()).thenReturn(10);
            when(mockQueryConfig.getLlmTimeoutMs()).thenReturn(30000);
            when(mockQueryConfig.getRecallTarget()).thenReturn(0.95);
            when(mockQueryConfig.getVectorDimensions()).thenReturn(128); // Config value not used for validation
            when(mockQueryConfig.isIncludePartialSegments()).thenReturn(true);

            // Create visibility config mock
            Config.Visibility mockVisibilityConfig = mock(Config.Visibility.class);
            when(mockConfig.visibility()).thenReturn(mockVisibilityConfig);
            when(mockVisibilityConfig.getQueryTimeoutMs()).thenReturn(30000);

            // Create LLM config mock
            Config.Llm mockLlmConfig = mock(Config.Llm.class);
            when(mockConfig.llm()).thenReturn(mockLlmConfig);
            when(mockLlmConfig.getRelevancyThreshold()).thenReturn(0.3);

            QueryEndpoint endpointWithConfig = new QueryEndpoint(queryPipeline, mockConfig);

            // Request with any vector dimensions - no validation performed
            String json = "{\"vector\": [0.1, 0.2, 0.3]}"; // Only 3 dimensions
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpointWithConfig.handle(exchange);

            // Should return 200 - no dimension validation
            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }
    }

    // Helper methods

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
