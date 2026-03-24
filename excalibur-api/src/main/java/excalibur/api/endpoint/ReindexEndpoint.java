package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP endpoint for the /v1/reindex API.
 *
 * <p>ReindexEndpoint handles POST requests to /v1/reindex for forcing
 * a re-crawl and re-index of a registered source. The reindex operation
 * runs in the background with zero-downtime rolling replacement.</p>
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * {
 *   "source_id": "my-docs"
 * }
 * }</pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "source_id": "my-docs",
 *   "status": "reindexing",
 *   "message": "Reindex started in background"
 * }
 * }</pre>
 *
 * <h2>Zero-Downtime Reindexing</h2>
 * <p>When a reindex is triggered:</p>
 * <ol>
 *   <li>New segments are created in BOOTSTRAPPING state</li>
 *   <li>Old segments continue serving queries</li>
 *   <li>New segments progress through INDEXING → WARMING → ACTIVE</li>
 *   <li>Once new segments cover old data, old segments are TOMBSTONED</li>
 *   <li>Rolling replacement ensures data is never unavailable</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Reindex operations are tracked per source.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class ReindexEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexEndpoint.class);

    /**
     * Maximum request body size in bytes.
     */
    private static final int MAX_REQUEST_SIZE = 1024 * 1024; // 1MB

    private final SourceRegistry sourceRegistry;
    private final ReindexStatusRegistry reindexStatusRegistry;

    // Optional components for actual reindexing (may be null for stub behavior)
    private final DocumentReindexer documentReindexer;
    private final SourceResolver sourceResolver;
    private final ExecutorService reindexExecutor;

    /**
     * Creates a new ReindexEndpoint with full reindexing capability.
     *
     * <p>When DocumentReindexer and SourceResolver are provided, reindex
     * operations will execute in background virtual threads, invoking
     * the actual reindexing pipeline.</p>
     *
     * @param sourceRegistry The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     * @param documentReindexer The document reindexer for executing reindex operations
     * @param sourceResolver The source resolver for obtaining Source instances
     * @throws NullPointerException if sourceRegistry or reindexStatusRegistry is null
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry,
                           ReindexStatusRegistry reindexStatusRegistry,
                           DocumentReindexer documentReindexer,
                           SourceResolver sourceResolver) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
        this.reindexStatusRegistry = Objects.requireNonNull(reindexStatusRegistry, "ReindexStatusRegistry cannot be null");
        this.documentReindexer = documentReindexer;
        this.sourceResolver = sourceResolver;
        this.reindexExecutor = Executors.newVirtualThreadPerTaskExecutor();

        LOGGER.info("ReindexEndpoint configured with document reindexer: {}",
            documentReindexer != null ? "enabled" : "disabled (stub mode)");
    }

    /**
     * Creates a new ReindexEndpoint with source registry but without actual reindexing.
     *
     * <p>This constructor provides backward compatibility. Reindex requests will
     * be accepted and tracked in the status registry, but no actual reindexing
     * will occur.</p>
     *
     * @param sourceRegistry The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry, ReindexStatusRegistry reindexStatusRegistry) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
        this.reindexStatusRegistry = Objects.requireNonNull(reindexStatusRegistry, "ReindexStatusRegistry cannot be null");
        this.documentReindexer = null;
        this.sourceResolver = null;
        this.reindexExecutor = null;
    }

    /**
     * Creates a new ReindexEndpoint with default registries (stub mode).
     *
     * <p>This constructor creates an endpoint without actual reindexing capability.
     * Use for testing or when reindexing should be handled externally.</p>
     */
    public ReindexEndpoint() {
        this(new InMemorySourceRegistry(), new InMemoryReindexStatusRegistry());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        try {
            // Parse request body
            String requestBody = readRequestBody(exchange);
            ReindexRequest request = parseRequest(requestBody);

            LOGGER.info("[{}] Reindex requested for source: {}", correlationId, request.sourceId);

            // Validate source exists
            if (!sourceRegistry.isRegistered(request.sourceId)) {
                sendError(exchange, 404, "Source not found: " + request.sourceId);
                return;
            }

            // Check if reindex is already in progress
            ReindexStatus currentStatus = reindexStatusRegistry.getStatus(request.sourceId);
            if (currentStatus != null && currentStatus.isInProgress()) {
                sendError(exchange, 409, "Reindex already in progress for source: " + request.sourceId);
                return;
            }

            // Start reindex (runs in background)
            reindexStatusRegistry.startReindex(request.sourceId);

            // Launch async reindex task if reindexer is configured
            if (documentReindexer != null && sourceResolver != null && reindexExecutor != null) {
                final String sourceId = request.sourceId;
                reindexExecutor.submit(() -> executeReindex(sourceId));
                LOGGER.info("[{}] Async reindex task submitted for source: {}", correlationId, request.sourceId);
            } else {
                LOGGER.info("[{}] Reindex started (stub mode - no actual reindexing)", correlationId);
            }

            // Send success response
            ReindexResponse response = new ReindexResponse(
                request.sourceId,
                "reindexing",
                "Reindex started in background"
            );
            sendResponse(exchange, 202, response);

            LOGGER.info("[{}] Reindex started for source: {}", correlationId, request.sourceId);

        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid reindex request: {}", e.getMessage());
            sendError(exchange, 400, "Bad Request: " + e.getMessage());

        } catch (Exception e) {
            LOGGER.error("Error processing reindex request: {}", e.getMessage(), e);
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
     * Parses the JSON request body into a ReindexRequest.
     */
    private ReindexRequest parseRequest(String json) {
        String sourceId = extractString(json, "source_id");
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("Field 'source_id' is required");
        }
        return new ReindexRequest(sourceId);
    }

    /**
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, ReindexResponse response) throws IOException {
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
     * Converts ReindexResponse to JSON.
     */
    private String toJson(ReindexResponse response) {
        return String.format(
            "{\"source_id\": \"%s\", \"status\": \"%s\", \"message\": \"%s\"}",
            escapeJson(response.sourceId),
            escapeJson(response.status),
            escapeJson(response.message)
        );
    }

    // ==================== JSON Parsing Utilities ====================

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

    // ==================== Async Reindex Execution ====================

    /**
     * Executes the reindex operation in a background virtual thread.
     *
     * <p>This method is called asynchronously after the HTTP response has been
     * sent. It performs the actual reindexing by:</p>
     * <ol>
     *   <li>Resolving the source using SourceResolver</li>
     *   <li>Invoking DocumentReindexer.reindexAll(source)</li>
     *   <li>Updating status registry as reindex progresses</li>
     *   <li>Calling completeReindex() or failReindex() when done</li>
     * </ol>
     *
     * @param sourceId The source ID to reindex
     */
    private void executeReindex(String sourceId) {
        String threadName = Thread.currentThread().getName();
        LOGGER.info("[reindex-{}] Starting reindex execution for source: {}", threadName, sourceId);

        try {
            // Resolve the source
            Source source = sourceResolver.resolve(sourceId);
            if (source == null) {
                String error = "Source not found: " + sourceId;
                LOGGER.error("[reindex-{}] {}", threadName, error);
                reindexStatusRegistry.failReindex(sourceId, error);
                return;
            }

            // Update status to indicate embedding phase
            reindexStatusRegistry.updateProgress(sourceId, "crawling", 0.1, 0, 0);
            LOGGER.debug("[reindex-{}] Source resolved, starting reindexAll for: {}", threadName, sourceId);

            // Execute the reindex
            reindexStatusRegistry.updateProgress(sourceId, "embedding", 0.3, 0, 0);
            DocumentReindexer.ReindexResult result = documentReindexer.reindexAll(source);

            // Update status to indicate indexing phase
            reindexStatusRegistry.updateProgress(sourceId, "indexing", 0.7,
                result.getDocumentsIndexed(), result.getDocumentsIndexed() + result.getChunksFailed());

            if (result.hasErrors()) {
                String error = String.format("Reindex completed with %d errors: %s",
                    result.getErrorCount(),
                    result.getErrors().stream()
                        .map(DocumentReindexer.ReindexResult.OperationError::getMessage)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("unknown"));
                LOGGER.warn("[reindex-{}] Reindex completed with errors for {}: {}",
                    threadName, sourceId, error);
                reindexStatusRegistry.failReindex(sourceId, error);
            } else {
                LOGGER.info("[reindex-{}] Reindex completed successfully for {}: {} docs indexed, {} chunks created",
                    threadName, sourceId, result.getDocumentsIndexed(), result.getChunksCreated());
                reindexStatusRegistry.completeReindex(sourceId);
            }

        } catch (Exception e) {
            String error = "Reindex failed: " + e.getMessage();
            LOGGER.error("[reindex-{}] {} for source: {}", threadName, error, sourceId, e);
            reindexStatusRegistry.failReindex(sourceId, error);
        }
    }

    // ==================== Inner Classes ====================

    /**
     * Request to reindex a source.
     */
    private record ReindexRequest(String sourceId) {}

    /**
     * Response after starting reindex.
     */
    private record ReindexResponse(String sourceId, String status, String message) {}

    /**
     * Reindex status for a source.
     */
    public static class ReindexStatus {
        private final String sourceId;
        private volatile String state;
        private volatile double progress;
        private volatile long startTimeMs;
        private volatile long entriesProcessed;
        private volatile long entriesTotal;
        private volatile String lastError;

        public ReindexStatus(String sourceId) {
            this.sourceId = sourceId;
            this.state = "idle";
            this.progress = 0.0;
            this.startTimeMs = 0;
            this.entriesProcessed = 0;
            this.entriesTotal = 0;
            this.lastError = null;
        }

        public String sourceId() { return sourceId; }
        public String state() { return state; }
        public double progress() { return progress; }
        public long startTimeMs() { return startTimeMs; }
        public long entriesProcessed() { return entriesProcessed; }
        public long entriesTotal() { return entriesTotal; }
        public String lastError() { return lastError; }

        public boolean isInProgress() {
            return "crawling".equals(state) || "embedding".equals(state) || "indexing".equals(state);
        }

        public void start() {
            this.state = "crawling";
            this.progress = 0.0;
            this.startTimeMs = System.currentTimeMillis();
            this.entriesProcessed = 0;
            this.entriesTotal = 0;
            this.lastError = null;
        }

        public void updateState(String newState, double progress, long processed, long total) {
            this.state = newState;
            this.progress = progress;
            this.entriesProcessed = processed;
            this.entriesTotal = total;
        }

        public void complete() {
            this.state = "idle";
            this.progress = 1.0;
        }

        public void fail(String error) {
            this.state = "idle";
            this.lastError = error;
        }
    }

    /**
     * Registry interface for looking up sources.
     */
    public interface SourceRegistry {
        boolean isRegistered(String sourceId);
    }

    /**
     * Registry interface for tracking reindex status.
     */
    public interface ReindexStatusRegistry {
        ReindexStatus getStatus(String sourceId);
        void startReindex(String sourceId);
        void updateProgress(String sourceId, String state, double progress, long processed, long total);
        void completeReindex(String sourceId);
        void failReindex(String sourceId, String error);
        Map<String, ReindexStatus> getAllStatuses();
    }

    /**
     * In-memory implementation of SourceRegistry.
     */
    public static class InMemorySourceRegistry implements SourceRegistry {
        private final Set<String> registeredSources = ConcurrentHashMap.newKeySet();

        public void register(String sourceId) {
            registeredSources.add(sourceId);
        }

        @Override
        public boolean isRegistered(String sourceId) {
            return registeredSources.contains(sourceId);
        }

        public void unregister(String sourceId) {
            registeredSources.remove(sourceId);
        }
    }

    /**
     * In-memory implementation of ReindexStatusRegistry.
     */
    public static class InMemoryReindexStatusRegistry implements ReindexStatusRegistry {
        private final ConcurrentHashMap<String, ReindexStatus> statuses = new ConcurrentHashMap<>();

        @Override
        public ReindexStatus getStatus(String sourceId) {
            return statuses.get(sourceId);
        }

        @Override
        public void startReindex(String sourceId) {
            ReindexStatus status = new ReindexStatus(sourceId);
            status.start();
            statuses.put(sourceId, status);
        }

        @Override
        public void updateProgress(String sourceId, String state, double progress, long processed, long total) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.updateState(state, progress, processed, total);
            }
        }

        @Override
        public void completeReindex(String sourceId) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.complete();
            }
        }

        @Override
        public void failReindex(String sourceId, String error) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.fail(error);
            }
        }

        @Override
        public Map<String, ReindexStatus> getAllStatuses() {
            return Collections.unmodifiableMap(new HashMap<>(statuses));
        }
    }
}
