package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP endpoint for the /v1/ingest/status API.
 *
 * <p>IngestStatusEndpoint handles GET requests to /v1/ingest/status for
 * retrieving the status of ingestion operations per source.</p>
 *
 * <h2>Request Format</h2>
 * <p>Optional query parameter: {@code source_id} to get status for a specific source.
 * If not provided, returns status for all sources.</p>
 *
 * <pre>
 * GET /v1/ingest/status
 * GET /v1/ingest/status?source_id=my-docs
 * </pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "sources": [
 *     {
 *       "source_id": "my-docs",
 *       "state": "crawling",
 *       "progress": 0.73,
 *       "entries_ingested": 450000,
 *       "entries_pending": 170000,
 *       "last_error": null
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h2>States</h2>
 * <ul>
 *   <li>{@code idle} - No ingestion in progress</li>
 *   <li>{@code crawling} - Crawling documents from source</li>
 *   <li>{@code embedding} - Generating embeddings</li>
 *   <li>{@code indexing} - Building index structures</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Progress values are in range [0.0, 1.0]</li>
 *   <li>Entry counts are non-negative</li>
 *   <li>State transitions follow valid lifecycle</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Status reads use concurrent collections.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class IngestStatusEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestStatusEndpoint.class);

    private final IngestionStatusRegistry statusRegistry;

    /**
     * Creates a new IngestStatusEndpoint.
     *
     * @param statusRegistry The registry for ingestion status
     */
    public IngestStatusEndpoint(IngestionStatusRegistry statusRegistry) {
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "IngestionStatusRegistry cannot be null");
    }

    /**
     * Creates a new IngestStatusEndpoint with an in-memory registry.
     */
    public IngestStatusEndpoint() {
        this(new InMemoryIngestionStatusRegistry());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        try {
            // Parse query parameters
            String query = exchange.getRequestURI().getQuery();
            String sourceId = getQueryParam(query, "source_id");

            LOGGER.debug("[{}] Ingest status request for source: {}", correlationId,
                sourceId != null ? sourceId : "all");

            if (sourceId != null) {
                // Get status for specific source
                IngestionStatus status = statusRegistry.getStatus(sourceId);
                if (status == null) {
                    sendError(exchange, 404, "Source not found: " + sourceId);
                    return;
                }
                sendSingleResponse(exchange, status);
            } else {
                // Get status for all sources
                Map<String, IngestionStatus> allStatuses = statusRegistry.getAllStatuses();
                sendAllResponse(exchange, allStatuses);
            }

        } catch (Exception e) {
            LOGGER.error("Error processing ingest status request: {}", e.getMessage(), e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Extracts a query parameter value from the query string.
     */
    private String getQueryParam(String query, String param) {
        if (query == null || query.isEmpty()) return null;

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf("=");
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                if (key.equals(param)) {
                    return decodeUrl(value);
                }
            } else if (pair.equals(param)) {
                return "";
            }
        }
        return null;
    }

    /**
     * Decodes a URL-encoded string.
     */
    private String decodeUrl(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Sends a response for a single source.
     */
    private void sendSingleResponse(HttpExchange exchange, IngestionStatus status) throws IOException {
        String json = toJson(status);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sends a response for all sources.
     */
    private void sendAllResponse(HttpExchange exchange, Map<String, IngestionStatus> statuses) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sources\": [");

        boolean first = true;
        for (IngestionStatus status : statuses.values()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(toJson(status));
        }

        sb.append("]}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

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
     * Converts IngestionStatus to JSON.
     */
    private String toJson(IngestionStatus status) {
        return String.format(
            "{" +
            "\"source_id\": \"%s\", " +
            "\"state\": \"%s\", " +
            "\"progress\": %.4f, " +
            "\"entries_ingested\": %d, " +
            "\"entries_pending\": %d, " +
            "\"last_error\": %s" +
            "}",
            escapeJson(status.sourceId()),
            escapeJson(status.state()),
            status.progress(),
            status.entriesIngested(),
            status.entriesPending(),
            status.lastError() != null ? "\"" + escapeJson(status.lastError()) + "\"" : "null"
        );
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    // ==================== Inner Classes ====================

    /**
     * Ingestion status for a source.
     */
    public static class IngestionStatus {
        private final String sourceId;
        private volatile String state;
        private volatile double progress;
        private volatile long entriesIngested;
        private volatile long entriesPending;
        private volatile String lastError;
        private volatile long lastUpdatedMs;

        public IngestionStatus(String sourceId) {
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId cannot be null");
            this.state = "idle";
            this.progress = 0.0;
            this.entriesIngested = 0;
            this.entriesPending = 0;
            this.lastError = null;
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        public String sourceId() { return sourceId; }
        public String state() { return state; }
        public double progress() { return progress; }
        public long entriesIngested() { return entriesIngested; }
        public long entriesPending() { return entriesPending; }
        public String lastError() { return lastError; }
        public long lastUpdatedMs() { return lastUpdatedMs; }

        /**
         * Updates the status.
         *
         * @param state Current state
         * @param progress Progress in range [0.0, 1.0]
         * @param entriesIngested Number of entries successfully ingested
         * @param entriesPending Number of entries waiting to be processed
         */
        public void update(String state, double progress, long entriesIngested, long entriesPending) {
            this.state = state;
            this.progress = Math.max(0.0, Math.min(1.0, progress));
            this.entriesIngested = Math.max(0, entriesIngested);
            this.entriesPending = Math.max(0, entriesPending);
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        /**
         * Sets an error state.
         */
        public void setError(String error) {
            this.lastError = error;
            this.state = "idle";
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        /**
         * Resets to idle state.
         */
        public void reset() {
            this.state = "idle";
            this.progress = 0.0;
            this.entriesIngested = 0;
            this.entriesPending = 0;
            this.lastError = null;
            this.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    /**
     * Registry interface for ingestion status.
     */
    public interface IngestionStatusRegistry {
        /**
         * Gets the status for a source.
         *
         * @param sourceId The source ID
         * @return The status, or null if not found
         */
        IngestionStatus getStatus(String sourceId);

        /**
         * Gets all statuses.
         *
         * @return Map of source ID to status
         */
        Map<String, IngestionStatus> getAllStatuses();

        /**
         * Creates or updates status for a source.
         *
         * @param sourceId The source ID
         * @return The status object to update
         */
        IngestionStatus getOrCreate(String sourceId);

        /**
         * Removes status for a source.
         *
         * @param sourceId The source ID
         */
        void remove(String sourceId);
    }

    /**
     * In-memory implementation of IngestionStatusRegistry.
     */
    public static class InMemoryIngestionStatusRegistry implements IngestionStatusRegistry {
        private final ConcurrentHashMap<String, IngestionStatus> statuses = new ConcurrentHashMap<>();

        @Override
        public IngestionStatus getStatus(String sourceId) {
            return statuses.get(sourceId);
        }

        @Override
        public Map<String, IngestionStatus> getAllStatuses() {
            return Collections.unmodifiableMap(new HashMap<>(statuses));
        }

        @Override
        public IngestionStatus getOrCreate(String sourceId) {
            return statuses.computeIfAbsent(sourceId, IngestionStatus::new);
        }

        @Override
        public void remove(String sourceId) {
            statuses.remove(sourceId);
        }
    }
}
