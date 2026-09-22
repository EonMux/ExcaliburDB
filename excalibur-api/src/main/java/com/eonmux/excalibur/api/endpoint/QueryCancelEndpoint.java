package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP endpoint for cancelling in-progress queries.
 *
 * <p>QueryCancelEndpoint handles DELETE requests to /v1/query/{query_id}
 * for user-initiated query cancellation.</p>
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * DELETE /v1/query/abc12345-1234-5678-9012-123456789abc HTTP/1.1
 * }</pre>
 *
 * <h2>Response Format (Success)</h2>
 * <pre>{@code
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {
 *   "cancelled": true,
 *   "query_id": "abc12345-1234-5678-9012-123456789abc"
 * }
 * }</pre>
 *
 * <h2>Response Format (Not Found)</h2>
 * <pre>{@code
 * HTTP/1.1 404 Not Found
 * Content-Type: application/json
 *
 * {
 *   "error": "Query not found or already completed"
 * }
 * }</pre>
 *
 * <h2>Response Format (Already Cancelled)</h2>
 * <pre>{@code
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {
 *   "cancelled": false,
 *   "query_id": "abc12345-1234-5678-9012-123456789abc",
 *   "message": "Query was already cancelled"
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All operations delegate to the thread-safe
 * {@link QueryRegistry}.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public class QueryCancelEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryCancelEndpoint.class);

    private final QueryRegistry queryRegistry;

    /**
     * Creates a new QueryCancelEndpoint.
     *
     * @param queryRegistry The query registry for cancellation operations
     * @throws NullPointerException if queryRegistry is null
     */
    public QueryCancelEndpoint(QueryRegistry queryRegistry) {
        this.queryRegistry = java.util.Objects.requireNonNull(queryRegistry, "queryRegistry cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        // Only handle DELETE requests for cancellation
        // POST requests should be handled by QueryEndpoint
        if (!"DELETE".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed - Use DELETE");
            return;
        }

        // Extract query ID from path: /v1/query/{query_id}
        String path = exchange.getRequestURI().getPath();
        String queryId = extractQueryId(path);

        if (queryId == null || queryId.isEmpty()) {
            sendError(exchange, 400, "Bad Request - Query ID is required in path (e.g., /v1/query/{query_id})");
            return;
        }

        LOGGER.debug("Attempting to cancel query: {}", queryId);

        try {
            // Check if query exists
            if (!queryRegistry.isRegistered(queryId)) {
                LOGGER.debug("Query not found: {}", queryId);
                sendError(exchange, 404, "Query not found or already completed");
                return;
            }

            // Check if already cancelled
            if (queryRegistry.isCancelled(queryId)) {
                LOGGER.debug("Query already cancelled: {}", queryId);
                sendJson(exchange, 200, String.format(
                    "{\"cancelled\": false, \"query_id\": \"%s\", \"message\": \"Query was already cancelled\"}",
                    JsonText.escape(queryId)
                ));
                return;
            }

            // Attempt cancellation
            boolean cancelled = queryRegistry.cancel(queryId, "User-initiated cancellation");

            if (cancelled) {
                LOGGER.info("Query cancelled successfully: {}", queryId);
                sendJson(exchange, 200, String.format(
                    "{\"cancelled\": true, \"query_id\": \"%s\"}",
                    JsonText.escape(queryId)
                ));
            } else {
                // Race condition - query completed between check and cancel
                LOGGER.debug("Query completed before cancellation: {}", queryId);
                sendJson(exchange, 200, String.format(
                    "{\"cancelled\": false, \"query_id\": \"%s\", \"message\": \"Query completed before cancellation\"}",
                    JsonText.escape(queryId)
                ));
            }

        } catch (Exception e) {
            LOGGER.error("Error cancelling query {}: {}", queryId, e.getMessage(), e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Extracts the query ID from the request path.
     *
     * <p>Handles paths like:</p>
     * <ul>
     *   <li>/v1/query/{query_id}</li>
     *   <li>/v1/query/{query_id}/</li>
     * </ul>
     *
     * @param path The request path
     * @return The query ID, Returns "cancel" for the /v1/query/cancel path, null for unrecognized paths.
     */
    private String extractQueryId(String path) {
        if (path == null) {
            return null;
        }

        // Remove trailing slash if present
        String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;

        // Split path and extract query ID
        String[] segments = normalizedPath.split("/");

        // Expected format: /v1/query/{query_id}
        // segments[0] = "", segments[1] = "v1", segments[2] = "query", segments[3] = query_id
        if (segments.length == 4 && "v1".equals(segments[1]) && "query".equals(segments[2])) {
            return segments[3];
        }

        return null;
    }

    /**
     * Sends a JSON response.
     */
    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        sendJson(exchange, statusCode, json);
    }

}
