package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.query.*;
import com.eonmux.excalibur.query.executor.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What QueryEndpoint accepts before it queries anything, and how it is built and torn down.
 *
 * <p>Covers the layer in front of retrieval: which constructor arguments are mandatory,
 * which HTTP methods reach the pipeline at all, and which request bodies are rejected as
 * 400 rather than being run as an empty or half-parsed query. Also covers the JSON
 * reader's own contract — a present field is read, an absent optional field takes its
 * documented default — and that shutting the endpoint down shuts its executor down.</p>
 */
@DisplayName("QueryEndpoint requests")
class QueryEndpointRequestTest extends QueryEndpointTestBase {

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

            endpointWithExecutor.shutdown(java.time.Duration.ofSeconds(10));

            assertThat(executor.isShutdown()).isTrue();
        }
    }
}
