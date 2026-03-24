package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
import io.excalibur.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * HTTP endpoint for the /v1/query API.
 *
 * <p>QueryEndpoint handles POST requests to /v1/query for vector similarity
 * search with timeout and cancellation support.</p>
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * {
 *   "query": "search query text",
 *   "vector": [0.1, 0.2, ...],
 *   "top_k": 10,
 *   "threshold": 0.5,
 *   "timeout_ms": 5000,
 *   "include_partial_segments": true,
 *   "enable_llm_scoring": true
 * }
 * }</pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "results": [
 *     {"document_id": 123, "score": 0.95, "payload": "..."}
 *   ],
 *   "coverage": {
 *     "total_segments": 1024,
 *     "active_segments": 980,
 *     "indexing_segments": 40,
 *     "warming_segments": 4,
 *     "estimated_corpus_coverage": 0.96,
 *     "tail_scan_entries": 12500,
 *     "degraded": false
 *   },
 *   "timed_out": false,
 *   "query_time_ms": 42
 * }
 * }</pre>
 *
 * <h2>Timeout Handling</h2>
 * <p>Queries that exceed their timeout return partial results with
 * {@code timed_out: true}. The cancellation token is used to propagate
 * cancellation to all query stages.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each request creates its own CancellationToken
 * and executes independently.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public class QueryEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryEndpoint.class);

    /**
     * Default timeout for queries in milliseconds.
     */
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    /**
     * Maximum request body size in bytes.
     */
    private static final int MAX_REQUEST_SIZE = 1024 * 1024; // 1MB

    private final QueryPipeline queryPipeline;
    private final ExecutorService executorService;
    private final Config config;

    /**
     * Creates a new QueryEndpoint.
     *
     * @param queryPipeline The query pipeline for executing queries
     */
    public QueryEndpoint(QueryPipeline queryPipeline) {
        this(queryPipeline, null, null);
    }

    /**
     * Creates a new QueryEndpoint with configuration.
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config) {
        this(queryPipeline, config, null);
    }

    /**
     * Creates a new QueryEndpoint with a custom executor service.
     *
     * @param queryPipeline The query pipeline
     * @param executorService The executor service for async operations
     */
    public QueryEndpoint(QueryPipeline queryPipeline, ExecutorService executorService) {
        this(queryPipeline, null, executorService);
    }

    /**
     * Creates a new QueryEndpoint with all options.
     *
     * @param queryPipeline The query pipeline for executing queries
     * @param config The configuration for default values (may be null)
     * @param executorService The executor service for async operations (may be null)
     */
    public QueryEndpoint(QueryPipeline queryPipeline, Config config, ExecutorService executorService) {
        this.queryPipeline = Objects.requireNonNull(queryPipeline, "QueryPipeline cannot be null");
        this.config = config;
        this.executorService = executorService != null ? executorService : Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.nanoTime();

        try {
            // Parse request body
            String requestBody = readRequestBody(exchange);
            QueryRequest request = parseRequest(requestBody, correlationId);

            LOGGER.debug("[{}] Processing query: topK={}, timeout={}ms",
                correlationId, request.topK(), request.timeoutMs());

            // Create cancellation token
            CancellationToken token = new CancellationToken();

            // Execute query with timeout
            CompletableFuture<QueryResponse> future = queryPipeline.execute(request, token);

            // Wait for completion
            QueryResponse response;
            try {
                response = future.get(request.timeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Cancel the token to propagate cancellation
                token.cancel("Query timeout");
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.warn("[{}] Query timed out after {}ms", correlationId, durationMs);
                response = buildTimeoutResponse(durationMs);
            } catch (CancellationException e) {
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.info("[{}] Query cancelled after {}ms", correlationId, durationMs);
                response = buildCancelledResponse(durationMs);
            } catch (ExecutionException e) {
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.error("[{}] Query failed: {}", correlationId, e.getCause().getMessage());
                response = buildErrorResponse(durationMs, e.getCause().getMessage());
            }

            // Send response
            sendResponse(exchange, 200, response);

        } catch (IllegalArgumentException e) {
            LOGGER.warn("[{}] Invalid request: {}", correlationId, e.getMessage());
            sendError(exchange, 400, "Bad Request: " + e.getMessage());

        } catch (Exception e) {
            LOGGER.error("[{}] Internal error: {}", correlationId, e.getMessage(), e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Reads the request body from the HTTP exchange.
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readNBytes(MAX_REQUEST_SIZE + 1);
            if (bytes.length > MAX_REQUEST_SIZE) {
                throw new IllegalArgumentException("Request body too large (max " + MAX_REQUEST_SIZE + " bytes)");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses the JSON request body into a QueryRequest.
     *
     * <p>This is a minimal JSON parser - production code would use
     * a proper JSON library.</p>
     */
    private QueryRequest parseRequest(String json, String correlationId) {
        // Simple JSON parsing (in production, use a proper JSON parser)
        String query = extractString(json, "query");
        float[] vector = extractFloatArray(json, "vector");
        int topK = extractInt(json, "top_k", 10);
        float threshold = extractFloat(json, "threshold", 0.0f);
        long timeoutMs = extractLong(json, "timeout_ms", DEFAULT_TIMEOUT_MS);

        // Use config default for include_partial_segments if config is provided
        boolean defaultIncludePartial = config != null
            ? config.query().isIncludePartialSegments()
            : true;
        boolean includePartial = extractBoolean(json, "include_partial_segments", defaultIncludePartial);

        boolean enableLlmScoring = extractBoolean(json, "enable_llm_scoring", true);
        float llmThreshold = extractFloat(json, "llm_threshold", 0.3f);

        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Field 'vector' is required and must be non-empty");
        }

        return QueryRequest.builder()
            .queryText(query)
            .queryVector(vector)
            .topK(topK)
            .threshold(threshold)
            .timeoutMs(timeoutMs)
            .includePartialSegments(includePartial)
            .enableLlmScoring(enableLlmScoring)
            .llmThreshold(llmThreshold)
            .correlationId(correlationId)
            .build();
    }

    /**
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, QueryResponse response) throws IOException {
        String json = toJson(response);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", escapeJson(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Converts QueryResponse to JSON.
     */
    private String toJson(QueryResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Results
        sb.append("\"results\": [");
        for (int i = 0; i < response.results().size(); i++) {
            if (i > 0) sb.append(",");
            QueryResultItem item = response.results().get(i);
            sb.append(String.format(
                "{\"document_id\": %d, \"score\": %.6f}",
                item.documentId(), item.score()
            ));
        }
        sb.append("], ");

        // Coverage
        IndexCoverage coverage = response.coverage();
        sb.append("\"coverage\": {");
        sb.append(String.format(
            "\"total_segments\": %d, ", coverage.totalSegments()));
        sb.append(String.format(
            "\"active_segments\": %d, ", coverage.activeSegments()));
        sb.append(String.format(
            "\"indexing_segments\": %d, ", coverage.indexingSegments()));
        sb.append(String.format(
            "\"warming_segments\": %d, ", coverage.warmingSegments()));
        sb.append(String.format(
            "\"estimated_corpus_coverage\": %.4f, ", coverage.estimatedCorpusCoverage()));
        sb.append(String.format(
            "\"tail_scan_entries\": %d, ", coverage.tailScanEntries()));
        sb.append(String.format(
            "\"degraded\": %s", coverage.degraded()));
        sb.append("}, ");

        // Query metadata
        sb.append(String.format("\"timed_out\": %s, ", response.timedOut()));
        sb.append(String.format("\"query_time_ms\": %d", response.queryTimeMs()));

        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a timeout response.
     */
    private QueryResponse buildTimeoutResponse(long durationMs) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            true  // timedOut
        );
    }

    /**
     * Builds a cancelled response.
     */
    private QueryResponse buildCancelledResponse(long durationMs) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            false
        );
    }

    /**
     * Builds an error response.
     */
    private QueryResponse buildErrorResponse(long durationMs, String errorMessage) {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(true)
            .estimatedCorpusCoverage(0.0)
            .computeCoverage()
            .build();

        return new QueryResponse(
            Collections.emptyList(),
            coverage,
            durationMs,
            false,
            false
        );
    }

    // ==================== Minimal JSON Parsing Utilities ====================

    private String extractString(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;

        start = json.indexOf("\"", start + pattern.length() + 1);
        if (start < 0) return null;

        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;

        return unescapeJson(json.substring(start + 1, end));
    }

    private float[] extractFloatArray(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;

        start = json.indexOf("[", start);
        if (start < 0) return null;

        int end = json.indexOf("]", start);
        if (end < 0) return null;

        String arrayContent = json.substring(start + 1, end).trim();
        if (arrayContent.isEmpty()) return new float[0];

        String[] parts = arrayContent.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    private int extractInt(String json, String field, int defaultValue) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return defaultValue;

        start += pattern.length();
        while (start < json.length() && (json.charAt(start) == ':' || Character.isWhitespace(json.charAt(start)))) {
            start++;
        }

        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        if (end == start) return defaultValue;
        return Integer.parseInt(json.substring(start, end));
    }

    private long extractLong(String json, String field, long defaultValue) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return defaultValue;

        start += pattern.length();
        while (start < json.length() && (json.charAt(start) == ':' || Character.isWhitespace(json.charAt(start)))) {
            start++;
        }

        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        if (end == start) return defaultValue;
        return Long.parseLong(json.substring(start, end));
    }

    private float extractFloat(String json, String field, float defaultValue) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return defaultValue;

        start += pattern.length();
        while (start < json.length() && (json.charAt(start) == ':' || Character.isWhitespace(json.charAt(start)))) {
            start++;
        }

        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) ||
            json.charAt(end) == '-' || json.charAt(end) == '.' || json.charAt(end) == 'e' || json.charAt(end) == 'E')) {
            end++;
        }

        if (end == start) return defaultValue;
        return Float.parseFloat(json.substring(start, end));
    }

    private boolean extractBoolean(String json, String field, boolean defaultValue) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return defaultValue;

        start += pattern.length();
        while (start < json.length() && (json.charAt(start) == ':' || Character.isWhitespace(json.charAt(start)))) {
            start++;
        }

        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return defaultValue;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    /**
     * Shuts down the endpoint and releases resources.
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
