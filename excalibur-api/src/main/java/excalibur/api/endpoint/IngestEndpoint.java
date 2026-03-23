package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.ingest.source.FilesystemSource;
import excalibur.ingest.source.HttpSource;
import excalibur.ingest.source.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP endpoint for the /v1/ingest API.
 *
 * <p>IngestEndpoint handles POST requests to /v1/ingest for registering
 * ingestion sources (filesystem or HTTP). Sources are registered and
 * ingestion runs in the background.</p>
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * {
 *   "source_type": "filesystem",
 *   "config": {
 *     "source_id": "my-docs",
 *     "root_path": "/path/to/documents",
 *     "included_extensions": [".txt", ".md", ".json"],
 *     "excluded_directories": [".git", "node_modules"]
 *   }
 * }
 * }</pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "source_id": "my-docs",
 *   "source_type": "filesystem",
 *   "status": "registered"
 * }
 * }</pre>
 *
 * <h2>Supported Source Types</h2>
 * <ul>
 *   <li>{@code filesystem} - Local filesystem directory crawler</li>
 *   <li>{@code http} - HTTP-based document source</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Source registration uses concurrent collections.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class IngestEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestEndpoint.class);

    /**
     * Maximum request body size in bytes.
     */
    private static final int MAX_REQUEST_SIZE = 1024 * 1024; // 1MB

    private final SourceRegistry sourceRegistry;

    /**
     * Creates a new IngestEndpoint with a source registry.
     *
     * @param sourceRegistry The registry for managing sources
     */
    public IngestEndpoint(SourceRegistry sourceRegistry) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
    }

    /**
     * Creates a new IngestEndpoint with a default in-memory registry.
     */
    public IngestEndpoint() {
        this(new InMemorySourceRegistry());
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
            IngestRequest request = parseRequest(requestBody);

            LOGGER.info("[{}] Registering ingestion source: type={}, id={}",
                correlationId, request.sourceType, request.sourceId);

            // Validate source ID is not already registered
            if (sourceRegistry.isRegistered(request.sourceId)) {
                sendError(exchange, 409, "Source already registered: " + request.sourceId);
                return;
            }

            // Create source based on type
            Source source = createSource(request);

            // Register the source (ingestion runs in background)
            sourceRegistry.register(source);

            // Send success response
            IngestResponse response = new IngestResponse(
                request.sourceId,
                request.sourceType,
                "registered"
            );
            sendResponse(exchange, 200, response);

            LOGGER.info("[{}] Source registered successfully: {}", correlationId, request.sourceId);

        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid ingest request: {}", e.getMessage());
            sendError(exchange, 400, "Bad Request: " + e.getMessage());

        } catch (Exception e) {
            LOGGER.error("Error processing ingest request: {}", e.getMessage(), e);
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
     * Parses the JSON request body into an IngestRequest.
     */
    private IngestRequest parseRequest(String json) {
        String sourceType = extractString(json, "source_type");
        if (sourceType == null || sourceType.isEmpty()) {
            throw new IllegalArgumentException("Field 'source_type' is required");
        }

        // Extract config object
        String configJson = extractObject(json, "config");
        if (configJson == null) {
            throw new IllegalArgumentException("Field 'config' is required");
        }

        String sourceId = extractString(configJson, "source_id");
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("Field 'config.source_id' is required");
        }

        // Type-specific config parsing
        Map<String, String> config = new HashMap<>();

        switch (sourceType.toLowerCase()) {
            case "filesystem":
                String rootPath = extractString(configJson, "root_path");
                if (rootPath == null || rootPath.isEmpty()) {
                    throw new IllegalArgumentException("Field 'config.root_path' is required for filesystem source");
                }
                config.put("root_path", rootPath);
                break;

            case "http":
                String baseUrl = extractString(configJson, "base_url");
                if (baseUrl == null || baseUrl.isEmpty()) {
                    throw new IllegalArgumentException("Field 'config.base_url' is required for HTTP source");
                }
                config.put("base_url", baseUrl);
                break;

            default:
                throw new IllegalArgumentException("Unknown source type: " + sourceType);
        }

        // Optional fields
        String[] includedExtensions = extractStringArray(configJson, "included_extensions");
        if (includedExtensions != null) {
            config.put("included_extensions", String.join(",", includedExtensions));
        }

        String[] excludedDirectories = extractStringArray(configJson, "excluded_directories");
        if (excludedDirectories != null) {
            config.put("excluded_directories", String.join(",", excludedDirectories));
        }

        return new IngestRequest(sourceType, sourceId, config);
    }

    /**
     * Creates a Source instance based on the request.
     */
    private Source createSource(IngestRequest request) {
        switch (request.sourceType.toLowerCase()) {
            case "filesystem":
                return createFilesystemSource(request);
            case "http":
                return createHttpSource(request);
            default:
                throw new IllegalArgumentException("Unknown source type: " + request.sourceType);
        }
    }

    /**
     * Creates a FilesystemSource from the request config.
     */
    private FilesystemSource createFilesystemSource(IngestRequest request) {
        String rootPath = request.config.get("root_path");

        FilesystemSource.Builder builder = FilesystemSource.builder()
            .sourceId(request.sourceId)
            .rootPath(Path.of(rootPath));

        String extensions = request.config.get("included_extensions");
        if (extensions != null && !extensions.isEmpty()) {
            builder.includedExtensions(extensions.split(","));
        }

        String excluded = request.config.get("excluded_directories");
        if (excluded != null && !excluded.isEmpty()) {
            builder.excludedDirectories(excluded.split(","));
        }

        return builder.build();
    }

    /**
     * Creates an HttpSource from the request config.
     */
    private HttpSource createHttpSource(IngestRequest request) {
        String baseUrl = request.config.get("base_url");

        HttpSource.Builder builder = HttpSource.builder()
            .sourceId(request.sourceId)
            .baseUrl(baseUrl);

        return builder.build();
    }

    /**
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, IngestResponse response) throws IOException {
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
     * Converts IngestResponse to JSON.
     */
    private String toJson(IngestResponse response) {
        return String.format(
            "{\"source_id\": \"%s\", \"source_type\": \"%s\", \"status\": \"%s\"}",
            escapeJson(response.sourceId),
            escapeJson(response.sourceType),
            escapeJson(response.status)
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

    private String extractObject(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;

        start = json.indexOf("{", start);
        if (start < 0) return null;

        int depth = 1;
        int end = start + 1;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            end++;
        }

        return json.substring(start, end);
    }

    private String[] extractStringArray(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;

        start = json.indexOf("[", start);
        if (start < 0) return null;

        int end = json.indexOf("]", start);
        if (end < 0) return null;

        String arrayContent = json.substring(start + 1, end).trim();
        if (arrayContent.isEmpty()) return new String[0];

        List<String> items = new ArrayList<>();
        int i = 0;
        while (i < arrayContent.length()) {
            // Find opening quote
            int openQuote = arrayContent.indexOf("\"", i);
            if (openQuote < 0) break;

            // Find closing quote
            int closeQuote = arrayContent.indexOf("\"", openQuote + 1);
            if (closeQuote < 0) break;

            items.add(unescapeJson(arrayContent.substring(openQuote + 1, closeQuote)));
            i = closeQuote + 1;
        }

        return items.toArray(new String[0]);
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

    // ==================== Inner Classes ====================

    /**
     * Request to register an ingestion source.
     */
    private record IngestRequest(String sourceType, String sourceId, Map<String, String> config) {}

    /**
     * Response after registering a source.
     */
    private record IngestResponse(String sourceId, String sourceType, String status) {}

    /**
     * Registry interface for managing sources.
     */
    public interface SourceRegistry {
        /**
         * Registers a new source.
         *
         * @param source The source to register
         */
        void register(Source source);

        /**
         * Checks if a source ID is already registered.
         *
         * @param sourceId The source ID to check
         * @return true if registered
         */
        boolean isRegistered(String sourceId);

        /**
         * Gets a source by ID.
         *
         * @param sourceId The source ID
         * @return The source, or null if not found
         */
        Source get(String sourceId);

        /**
         * Removes a source.
         *
         * @param sourceId The source ID to remove
         * @return true if removed
         */
        boolean remove(String sourceId);

        /**
         * Gets all registered source IDs.
         *
         * @return Set of source IDs
         */
        Set<String> getAllSourceIds();
    }

    /**
     * In-memory implementation of SourceRegistry.
     */
    public static class InMemorySourceRegistry implements SourceRegistry {
        private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();

        @Override
        public void register(Source source) {
            Objects.requireNonNull(source, "Source cannot be null");
            sources.put(source.getSourceId(), source);
        }

        @Override
        public boolean isRegistered(String sourceId) {
            return sources.containsKey(sourceId);
        }

        @Override
        public Source get(String sourceId) {
            return sources.get(sourceId);
        }

        @Override
        public boolean remove(String sourceId) {
            return sources.remove(sourceId) != null;
        }

        @Override
        public Set<String> getAllSourceIds() {
            return Collections.unmodifiableSet(new HashSet<>(sources.keySet()));
        }
    }
}
