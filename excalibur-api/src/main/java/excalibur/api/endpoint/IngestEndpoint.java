package excalibur.api.endpoint;

import java.util.Locale;

import excalibur.api.protocol.RequestBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.source.FilesystemSource;
import excalibur.ingest.source.HttpSource;
import excalibur.ingest.source.Source;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.api.security.IngestTargetRejectedException;
import excalibur.api.security.IngestTargetValidator;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import io.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;


/**
 * HTTP endpoint for the /v1/ingest API.
 *
 * <p>IngestEndpoint handles POST requests to /v1/ingest for registering
 * ingestion sources (filesystem or HTTP). Sources are registered and
 * ingestion runs in the background.</p>
 *
 * <p>The same path answers {@code DELETE} through
 * {@link SourceDeregisterEndpoint}, which stops crawling a source and keeps
 * what it indexed. One resource, two verbs.</p>
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
 * <h2>Target Allow-Lists</h2>
 * <p>Both source types point the server at something a caller names, so both are
 * allow-listed by {@link excalibur.api.security.IngestTargetValidator}:</p>
 * <ul>
 *   <li>{@code root_path} must resolve, after canonicalisation, inside one of the
 *       directories listed in {@code excalibur.ingest.allowed-roots}</li>
 *   <li>{@code base_url} must use http or https, name a host listed in
 *       {@code excalibur.ingest.allowed-hosts}, and resolve only to public addresses</li>
 * </ul>
 * <p>An empty allow-list disables that source type; it never means "unrestricted".
 * A refused request is answered with 403 and a message that does not describe the
 * server's filesystem or network.</p>
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

    private final SourceRegistry sourceRegistry;

    /**
     * Validates client-supplied ingest targets against the configured allow-lists.
     * Never null: an endpoint built without an explicit policy gets the default one,
     * which disables both filesystem and URL ingest.
     */
    private final IngestTargetValidator targetValidator;

    /**
     * Creates a new IngestEndpoint with a source registry.
     *
     * <p>Ingest targets are validated against the default security policy, which has
     * no allow-listed roots or hosts and therefore refuses every target. Production
     * callers must pass a configured {@link IngestTargetValidator}.</p>
     *
     * @param sourceRegistry The registry for managing sources
     */
    public IngestEndpoint(SourceRegistry sourceRegistry) {
        this(sourceRegistry, (IngestStatusEndpoint.IngestionStatusRegistry) null, null,
            new IngestTargetValidator(ApiSecurityConfig.defaults()));
    }

    /**
     * Creates a new IngestEndpoint with a source registry and status registry.
     *
     * <p>When both registries are provided, sources registered will automatically
     * have their status tracked in the status registry.</p>
     *
     * @param sourceRegistry The registry for managing sources
     * @param statusRegistry The registry for tracking ingestion status (may be null)
     */
    public IngestEndpoint(SourceRegistry sourceRegistry, IngestStatusEndpoint.IngestionStatusRegistry statusRegistry) {
        this(sourceRegistry, statusRegistry, null,
            new IngestTargetValidator(ApiSecurityConfig.defaults()));
    }

    /**
     * Creates a new IngestEndpoint with a source registry and ingestion orchestrator.
     *
     * <p>When an orchestrator is provided, the InMemorySourceRegistry will trigger
     * background ingestion when a source is registered. For custom SourceRegistry
     * implementations, use the single-argument constructor.</p>
     *
     * @param sourceRegistry The registry for managing sources
     * @param orchestrator The ingestion orchestrator for background processing (may be null)
     */
    public IngestEndpoint(SourceRegistry sourceRegistry, IngestionOrchestrator orchestrator) {
        this(sourceRegistry, null, orchestrator,
            new IngestTargetValidator(ApiSecurityConfig.defaults()));
    }

    /**
     * Creates a new IngestEndpoint with a source registry, status registry, and ingestion orchestrator.
     *
     * <p>This is the recommended constructor for production use. It configures both
     * status tracking and background ingestion.</p>
     *
     * <p>When both status registry and orchestrator are provided:</p>
     * <ul>
     *   <li>Status entries are created immediately when sources are registered</li>
     *   <li>Background ingestion updates status progress automatically</li>
     *   <li>The /v1/ingest/status endpoint returns meaningful data</li>
     * </ul>
     *
     * @param sourceRegistry The registry for managing sources
     * @param statusRegistry The registry for tracking ingestion status (may be null)
     * @param orchestrator The ingestion orchestrator for background processing (may be null)
     */
    public IngestEndpoint(SourceRegistry sourceRegistry,
                          IngestStatusEndpoint.IngestionStatusRegistry statusRegistry,
                          IngestionOrchestrator orchestrator) {
        this(sourceRegistry, statusRegistry, orchestrator,
            new IngestTargetValidator(ApiSecurityConfig.defaults()));
    }

    /**
     * Creates a new IngestEndpoint with an explicit ingest target policy.
     *
     * <p>This is the constructor production wiring uses. The validator decides which
     * filesystem roots and which hosts a client may point the crawler at; without it
     * {@code root_path} is an arbitrary local file read and {@code base_url} is a
     * server-side request forgery primitive.</p>
     *
     * @param sourceRegistry  The registry for managing sources
     * @param statusRegistry  The registry for tracking ingestion status (may be null)
     * @param orchestrator    The ingestion orchestrator for background processing (may be null)
     * @param targetValidator The allow-list policy for ingest targets
     * @throws NullPointerException if sourceRegistry or targetValidator is null
     */
    public IngestEndpoint(SourceRegistry sourceRegistry,
                          IngestStatusEndpoint.IngestionStatusRegistry statusRegistry,
                          IngestionOrchestrator orchestrator,
                          IngestTargetValidator targetValidator) {
        Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
        this.targetValidator = Objects.requireNonNull(targetValidator, "IngestTargetValidator cannot be null");

        // Configure InMemorySourceRegistry with both status and orchestrator
        if (sourceRegistry instanceof InMemorySourceRegistry) {
            InMemorySourceRegistry inMemRegistry = (InMemorySourceRegistry) sourceRegistry;
            if (statusRegistry != null) {
                inMemRegistry.setStatusRegistry(statusRegistry);
            }
            if (orchestrator != null) {
                inMemRegistry.setIngestionOrchestrator(orchestrator);
            }
        }

        this.sourceRegistry = sourceRegistry;
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

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withCorrelationId(correlationId)
                .build()) {

            try {
                // Parse request body
                String requestBody = readRequestBody(exchange);
                IngestRequest request = parseRequest(requestBody);

                // Set source ID in MDC for per-source logging
                StructuredLoggingHelper.setSourceId(request.sourceId);

                LOGGER.info("Registering ingestion source: type={}", request.sourceType);

                // Build the source. The target is validated against the configured
                // allow-lists first, so an unknown root or host never reaches a crawler.
                Source source;
                try {
                    source = createSource(request);
                } catch (IngestTargetRejectedException e) {
                    sendError(exchange, 403, e.getMessage());
                    return;
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // Source construction failures can echo server-side detail; log it
                    // and answer with a fixed message.
                    LOGGER.warn("Rejected ingest source configuration: {}", e.getMessage());
                    sendError(exchange, 400, "Invalid source configuration");
                    return;
                }

                // Registration is a single atomic claim on the source ID: checking
                // first and registering afterwards lets two concurrent requests both
                // start ingestion and orphan one unclosed Source.
                try {
                    sourceRegistry.register(source);
                } catch (SourceRegistrationException e) {
                    closeQuietly(source);
                    sendError(exchange, e.reason().statusCode(), e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    closeQuietly(source);
                    throw e;
                }

                // Send success response
                IngestResponse response = new IngestResponse(
                    request.sourceId,
                    request.sourceType,
                    "registered"
                );
                sendResponse(exchange, 200, response);

                LOGGER.info("Source registered successfully");

            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid ingest request: {}", e.getMessage());
                sendError(exchange, 400, "Bad Request: " + e.getMessage());

            } catch (Exception e) {
                LOGGER.error("Error processing ingest request: {}", e.getMessage(), e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
        // MDC context is automatically cleared when ctx.close() is called
    }

    /**
     * Reads the request body from the HTTP exchange.
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        return RequestBody.read(exchange);
    }

    /**
     * Parses the JSON request body into an IngestRequest.
     */
    private IngestRequest parseRequest(String json) {
        String sourceType = JsonText.string(json, "source_type");
        if (sourceType == null || sourceType.isEmpty()) {
            throw new IllegalArgumentException("Field 'source_type' is required");
        }

        // Extract config object
        String configJson = JsonText.object(json, "config");
        if (configJson == null) {
            throw new IllegalArgumentException("Field 'config' is required");
        }

        String sourceId = JsonText.string(configJson, "source_id");
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("Field 'config.source_id' is required");
        }

        // Type-specific config parsing
        Map<String, String> config = new HashMap<>();

        switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "filesystem":
                String rootPath = JsonText.string(configJson, "root_path");
                if (rootPath == null || rootPath.isEmpty()) {
                    throw new IllegalArgumentException("Field 'config.root_path' is required for filesystem source");
                }
                config.put("root_path", rootPath);
                break;

            case "http":
                String baseUrl = JsonText.string(configJson, "base_url");
                if (baseUrl == null || baseUrl.isEmpty()) {
                    throw new IllegalArgumentException("Field 'config.base_url' is required for HTTP source");
                }
                config.put("base_url", baseUrl);
                break;

            default:
                throw new IllegalArgumentException("Unknown source type: " + sourceType);
        }

        // Optional fields
        String[] includedExtensions = JsonText.stringArray(configJson, "included_extensions");
        if (includedExtensions != null) {
            config.put("included_extensions", String.join(",", includedExtensions));
        }

        String[] excludedDirectories = JsonText.stringArray(configJson, "excluded_directories");
        if (excludedDirectories != null) {
            config.put("excluded_directories", String.join(",", excludedDirectories));
        }

        return new IngestRequest(sourceType, sourceId, config);
    }

    /**
     * Creates a Source instance based on the request.
     */
    private Source createSource(IngestRequest request) {
        switch (request.sourceType.toLowerCase(Locale.ROOT)) {
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
     *
     * <p>The requested root is canonicalised and matched against the configured
     * allow-list before a crawler is built, and the crawler is given the resolved
     * real path so it cannot be re-pointed by a symlink swapped in afterwards.</p>
     */
    private FilesystemSource createFilesystemSource(IngestRequest request) {
        Path rootPath = targetValidator.resolveFilesystemRoot(request.config.get("root_path"));

        FilesystemSource.Builder builder = FilesystemSource.builder()
            .sourceId(request.sourceId)
            .rootPath(rootPath);

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
     *
     * <p>The URL is checked against the host allow-list and every address the host
     * resolves to must be public, which is what keeps the crawler off cloud metadata
     * endpoints and internal services. The crawl is then pinned to that single host so
     * a redirect cannot walk it somewhere else: {@link HttpSource} follows redirects
     * itself, with {@code Redirect.NEVER} on its client, and re-applies its domain
     * policy to every hop.</p>
     */
    private HttpSource createHttpSource(IngestRequest request) {
        URI baseUrl = targetValidator.resolveUrl(request.config.get("base_url"));

        HttpSource.Builder builder = HttpSource.builder()
            .sourceId(request.sourceId)
            .baseUrl(baseUrl)
            .allowedDomains(Set.of(IngestTargetValidator.hostOf(baseUrl)));

        return builder.build();
    }

    /**
     * Closes a source that never made it into the registry.
     *
     * <p>An HTTP source owns a client and a selector thread; dropping the reference
     * without closing leaks both.</p>
     */
    private static void closeQuietly(Source source) {
        if (source == null) {
            return;
        }
        try {
            source.close();
        } catch (Exception e) {
            LOGGER.debug("Error closing unregistered source: {}", e.getMessage());
        }
    }

    /**
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, IngestResponse response) throws IOException {
        String json = toJson(response);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    /**
     * Converts IngestResponse to JSON.
     */
    private String toJson(IngestResponse response) {
        return String.format(
            "{\"source_id\": \"%s\", \"source_type\": \"%s\", \"status\": \"%s\"}",
            JsonText.escape(response.sourceId),
            JsonText.escape(response.sourceType),
            JsonText.escape(response.status)
        );
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
}
