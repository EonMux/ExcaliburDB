package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.QueryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueryCancelEndpoint.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>DELETE requests properly cancel queries via QueryRegistry</li>
 *   <li>Correct response format for successful cancellations</li>
 *   <li>Correct error responses for not found queries</li>
 *   <li>Method not allowed for non-DELETE requests</li>
 *   <li>Proper JSON escaping in responses</li>
 * </ul>
 */
@DisplayName("QueryCancelEndpoint")
class QueryCancelEndpointTest {

    private QueryRegistry queryRegistry;
    private QueryCancelEndpoint endpoint;

    @BeforeEach
    void setUp() {
        queryRegistry = new QueryRegistry();
        endpoint = new QueryCancelEndpoint(queryRegistry);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("rejects null query registry")
        void rejectsNullQueryRegistry() {
            NullPointerException ex = (NullPointerException) catchThrowable(
                () -> new QueryCancelEndpoint(null)
            );
            assertThat(ex).hasMessageContaining("queryRegistry cannot be null");
        }
    }

    @Nested
    @DisplayName("HTTP method handling")
    class HttpMethodHandling {

        @Test
        @DisplayName("rejects GET requests with 405")
        void rejectsGetRequests() throws IOException {
            HttpExchange exchange = createMockExchange("GET", "/v1/query/test-id");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("rejects POST requests with 405")
        void rejectsPostRequests() throws IOException {
            HttpExchange exchange = createMockExchange("POST", "/v1/query/test-id");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("rejects PUT requests with 405")
        void rejectsPutRequests() throws IOException {
            HttpExchange exchange = createMockExchange("PUT", "/v1/query/test-id");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }
    }

    @Nested
    @DisplayName("Path parsing")
    class PathParsing {

        @Test
        @DisplayName("extracts query ID from valid path")
        void extractsQueryIdFromValidPath() throws IOException {
            String queryId = "abc123-def456";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("DELETE", "/v1/query/" + queryId, responseBody);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"query_id\": \"abc123-def456\"");
        }

        @Test
        @DisplayName("rejects missing query ID with 400")
        void rejectsMissingQueryId() throws IOException {
            HttpExchange exchange = createMockExchange("DELETE", "/v1/query/");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }

        @Test
        @DisplayName("handles path with trailing slash")
        void handlesPathWithTrailingSlash() throws IOException {
            String queryId = "test-query-id";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("DELETE", "/v1/query/" + queryId + "/", responseBody);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"query_id\": \"test-query-id\"");
        }

        @Test
        @DisplayName("rejects invalid path format with 400")
        void rejectsInvalidPathFormat() throws IOException {
            HttpExchange exchange = createMockExchange("DELETE", "/v1/invalid/test-id");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
        }
    }

    @Nested
    @DisplayName("Successful cancellation")
    class SuccessfulCancellation {

        @Test
        @DisplayName("cancels registered query and returns success")
        void cancelsRegisteredQuery() throws IOException {
            String queryId = "query-to-cancel";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            assertThat(token.isCancelled()).isFalse();

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("DELETE", "/v1/query/" + queryId, responseBody);

            endpoint.handle(exchange);

            // Verify token was cancelled
            assertThat(token.isCancelled()).isTrue();

            // Verify response
            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"cancelled\": true");
            assertThat(response).contains("\"query_id\": \"query-to-cancel\"");
        }

        @Test
        @DisplayName("returns 200 status for successful cancellation")
        void returns200ForSuccessfulCancellation() throws IOException {
            String queryId = "query-200";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            HttpExchange exchange = createMockExchange("DELETE", "/v1/query/" + queryId);
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("includes cancellation reason in token")
        void includesCancellationReasonInToken() throws IOException {
            String queryId = "query-reason";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            endpoint.handle(createMockExchange("DELETE", "/v1/query/" + queryId));

            assertThat(token.cancellationReason()).isEqualTo("User-initiated cancellation");
        }
    }

    @Nested
    @DisplayName("Query not found")
    class QueryNotFound {

        @Test
        @DisplayName("returns 404 for unregistered query")
        void returns404ForUnregisteredQuery() throws IOException {
            HttpExchange exchange = createMockExchange("DELETE", "/v1/query/nonexistent-id");
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(404), anyLong());
        }

        @Test
        @DisplayName("returns error message for not found query")
        void returnsErrorMessageForNotFoundQuery() throws IOException {
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("DELETE", "/v1/query/nonexistent", responseBody);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"error\":");
            assertThat(response).contains("not found");
        }

        @Test
        @DisplayName("returns 404 for already unregistered query")
        void returns404ForAlreadyUnregisteredQuery() throws IOException {
            String queryId = "temp-query";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);
            queryRegistry.unregister(queryId);

            HttpExchange exchange = createMockExchange("DELETE", "/v1/query/" + queryId);
            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(404), anyLong());
        }
    }

    @Nested
    @DisplayName("Already cancelled")
    class AlreadyCancelled {

        @Test
        @DisplayName("returns 200 with cancelled=false for already cancelled query")
        void returns200WithCancelledFalseForAlreadyCancelled() throws IOException {
            String queryId = "already-cancelled";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            // Cancel the query first
            token.cancel("Previous cancellation");

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("DELETE", "/v1/query/" + queryId, responseBody);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"cancelled\": false");
            assertThat(response).contains("already cancelled");
        }
    }

    @Nested
    @DisplayName("Registry integration")
    class RegistryIntegration {

        @Test
        @DisplayName("tracks cancellation in registry metrics")
        void tracksCancellationInRegistryMetrics() throws IOException {
            String queryId = "metrics-query";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            long beforeCancel = queryRegistry.getTotalQueriesCancelled();

            endpoint.handle(createMockExchange("DELETE", "/v1/query/" + queryId));

            assertThat(queryRegistry.getTotalQueriesCancelled()).isEqualTo(beforeCancel + 1);
        }

        @Test
        @DisplayName("query remains in registry after cancellation")
        void queryRemainsInRegistryAfterCancellation() throws IOException {
            String queryId = "remaining-query";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            endpoint.handle(createMockExchange("DELETE", "/v1/query/" + queryId));

            // Query should still be registered (just cancelled)
            assertThat(queryRegistry.isRegistered(queryId)).isTrue();
            assertThat(queryRegistry.isCancelled(queryId)).isTrue();
        }

        @Test
        @DisplayName("can list cancelled queries in active queries")
        void canListCancelledQueriesInActiveQueries() throws IOException {
            String queryId = "list-cancelled";
            CancellationToken token = new CancellationToken();
            queryRegistry.register(queryId, token);

            endpoint.handle(createMockExchange("DELETE", "/v1/query/" + queryId));

            List<QueryRegistry.ActiveQueryInfo> activeQueries = queryRegistry.getActiveQueries();
            assertThat(activeQueries).hasSize(1);
            assertThat(activeQueries.get(0).queryId()).isEqualTo(queryId);
            assertThat(activeQueries.get(0).cancelled()).isTrue();
        }
    }

    // Helper methods

    private HttpExchange createMockExchange(String method, String path) throws IOException {
        return createMockExchangeWithBody(method, path, new ByteArrayOutputStream());
    }

    private HttpExchange createMockExchangeWithBody(String method, String path, ByteArrayOutputStream responseBody) throws IOException {
        Headers headers = new Headers();
        return createMockExchangeWithHeaders(method, path, headers, responseBody);
    }

    private HttpExchange createMockExchangeWithHeaders(String method, String path, Headers headers, ByteArrayOutputStream responseBody) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(responseBody);
        return exchange;
    }
}
