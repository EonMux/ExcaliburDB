package com.eonmux.excalibur.api.endpoint;

import java.util.Locale;

import com.eonmux.excalibur.api.protocol.RequestBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.IngestTargetRejectedException;
import com.eonmux.excalibur.api.security.IngestTargetValidator;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <h2>Which Namespace a Source Belongs To</h2>
 * <p>{@value com.eonmux.excalibur.api.bootstrap.GraphTenants#NAMESPACE_HEADER} - the same header the graph
 * routes read - names the namespace the registered source's documents belong to. Every document
 * the crawl publishes is staged into that namespace's graph, including the documents a re-crawl
 * publishes after a restart, because the namespace is written into the source catalog alongside
 * everything else the registration is rebuilt from.</p>
 * <p>Absent, it is the shared namespace, which is how a node that has never heard of
 * multi-tenancy behaves. Present and not a legal namespace id, the registration is refused with
 * {@code 400} rather than quietly given the shared namespace: a caller who asked for one tenant
 * and was given another has had its documents cross a tenancy boundary, and nothing later in the
 * pipeline could tell.</p>
 *
 * <h2>Target Allow-Lists</h2>
 * <p>Both source types point the server at something a caller names, so both are
 * allow-listed by {@link com.eonmux.excalibur.api.security.IngestTargetValidator}:</p>
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

    private final SourceRegistry sourceRegistry;

    /**
     * Builds the crawler a request describes, validating its target against the configured
     * allow-lists. Never null: an endpoint built without an explicit policy gets the default one,
     * which disables both filesystem and URL ingest.
     */
    private final SourceFactory sourceFactory;

    /**
     * The ids documents have been supplied directly under, or null on a node that takes no
     * supplied documents. A crawl registered over one of them deletes what was supplied.
     */
    private final InlineSourceIds inlineSourceIds;

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
        this(sourceRegistry, statusRegistry, orchestrator, targetValidator, null);
    }

    /**
     * Creates a new IngestEndpoint that also refuses crawls over supplied documents.
     *
     * @param sourceRegistry  The registry for managing sources
     * @param statusRegistry  The registry for tracking ingestion status (may be null)
     * @param orchestrator    The ingestion orchestrator for background processing (may be null)
     * @param targetValidator The allow-list policy for ingest targets
     * @param inlineSourceIds The ids documents have been supplied directly under, whose crawl
     *                        registration this endpoint refuses, or null on a node that takes
     *                        no supplied documents
     * @throws NullPointerException if sourceRegistry or targetValidator is null
     */
    public IngestEndpoint(SourceRegistry sourceRegistry,
                          IngestStatusEndpoint.IngestionStatusRegistry statusRegistry,
                          IngestionOrchestrator orchestrator,
                          IngestTargetValidator targetValidator,
                          InlineSourceIds inlineSourceIds) {
        this(sourceRegistry, statusRegistry, orchestrator, targetValidator, inlineSourceIds,
            IngestConfig.DEFAULT_CRAWL_DELAY_MS);
    }

    /**
     * Creates a new IngestEndpoint that paces the crawls it registers at a configured rate.
     *
     * @param sourceRegistry      The registry for managing sources
     * @param statusRegistry      The registry for tracking ingestion status (may be null)
     * @param orchestrator        The ingestion orchestrator for background processing (may be null)
     * @param targetValidator     The allow-list policy for ingest targets
     * @param inlineSourceIds     The ids documents have been supplied directly under, whose crawl
     *                            registration this endpoint refuses, or null on a node that takes
     *                            no supplied documents
     * @param minCrawlDelayMillis The shortest gap a registered crawl may leave between requests
     *                            to its host, from {@code excalibur.ingest.crawl-delay-ms}
     * @throws NullPointerException if sourceRegistry or targetValidator is null
     */
    public IngestEndpoint(SourceRegistry sourceRegistry,
                          IngestStatusEndpoint.IngestionStatusRegistry statusRegistry,
                          IngestionOrchestrator orchestrator,
                          IngestTargetValidator targetValidator,
                          InlineSourceIds inlineSourceIds,
                          long minCrawlDelayMillis) {
        Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
        this.inlineSourceIds = inlineSourceIds;
        this.sourceFactory = new SourceFactory(
            Objects.requireNonNull(targetValidator, "IngestTargetValidator cannot be null"),
            minCrawlDelayMillis);

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
                // Before the body is read: which tenant a registration belongs to is settled
                // from a header, and a request naming a namespace this node cannot name is
                // refused whatever it carries.
                NamespaceId namespace;
                try {
                    namespace = GraphTenants.namespaceOf(exchange.getRequestHeaders()
                        .getFirst(GraphTenants.NAMESPACE_HEADER));
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Refused an ingest registration naming an illegal namespace: {}",
                        e.getMessage());
                    sendError(exchange, 400, "Header '" + GraphTenants.NAMESPACE_HEADER
                        + "': " + e.getMessage());
                    return;
                }

                // Parse request body
                String requestBody = readRequestBody(exchange);
                SourceDescriptor request = parseRequest(requestBody, namespace);

                // Set source ID in MDC for per-source logging
                StructuredLoggingHelper.setSourceId(request.sourceId());

                LOGGER.info("Registering ingestion source: type={}, namespace={}",
                    request.sourceType(), request.namespace());

                // Build the source. The target is validated against the configured
                // allow-lists first, so an unknown root or host never reaches a crawler.
                Source source;
                try {
                    source = sourceFactory.create(request);
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

                // After the allow-list, not before it: which targets a caller may point the
                // crawler at is the boundary, and a request that fails it is refused without
                // being told anything about what this node holds.
                //
                // A crawl decides for itself what its id holds, and its first pass tombstones
                // every document under that id it did not find - which is every document
                // supplied by hand. The caller cannot see that loss coming, so it is refused
                // here rather than discovered later.
                if (inlineSourceIds != null && inlineSourceIds.isSupplied(request.sourceId())) {
                    closeQuietly(source);
                    sendError(exchange, 409, "Source '" + request.sourceId() + "' holds documents "
                        + "supplied directly; a crawl registered under its id would remove them "
                        + "on its first pass. Use a different source_id.");
                    return;
                }

                // Registration is a single atomic claim on the source ID: checking
                // first and registering afterwards lets two concurrent requests both
                // start ingestion and orphan one unclosed Source.
                try {
                    sourceRegistry.register(source, request);
                } catch (SourceRegistrationException e) {
                    closeQuietly(source);
                    sendError(exchange, e.reason().statusCode(), e.getMessage());
                    return;
                } catch (SourceCatalogException e) {
                    // Accepted-but-unrecorded is the failure the catalog exists to prevent: the
                    // caller would be told its source is registered and find it gone after the
                    // next restart. The detail is for the log; the response names no path.
                    closeQuietly(source);
                    LOGGER.error("Source registration could not be recorded: {}",
                        e.getMessage(), e);
                    sendError(exchange, SourceCatalogException.STATUS_CODE,
                        SourceCatalogException.CLIENT_MESSAGE);
                    return;
                } catch (RuntimeException e) {
                    closeQuietly(source);
                    throw e;
                }

                // Read again after the claim, not only before it. Both routes publish their
                // claim before their last read - this one registers, the supplied route records
                // - so whichever publishes second sees the first, and the pair can never both
                // proceed. Checking only before registering leaves a window the whole length of
                // a catalog write, and a crawl that wins it deletes the supplied documents on
                // its first pass.
                if (inlineSourceIds != null && inlineSourceIds.isSupplied(request.sourceId())) {
                    sourceRegistry.remove(request.sourceId());
                    closeQuietly(source);
                    sendError(exchange, 409, "Source '" + request.sourceId() + "' holds documents "
                        + "supplied directly; a crawl registered under its id would remove them "
                        + "on its first pass. Use a different source_id.");
                    return;
                }

                // Send success response
                IngestResponse response = new IngestResponse(
                    request.sourceId(),
                    request.sourceType(),
                    "registered"
                );
                sendResponse(exchange, 200, response);

                LOGGER.info("Source registered successfully");

            } catch (RequestBody.TooLargeException e) {
                // Well-formed and too big is not the same failure as malformed: 400 tells the
                // caller not to send it again, 413 tells it to split the payload.
                LOGGER.warn("Refused an oversized ingest body: {}", e.getMessage());
                sendError(exchange, 413, e.getMessage());

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
     * Parses the JSON request body into the registration it describes.
     *
     * <p>What comes out is what gets written down: the type, the id, the namespace and the
     * configuration as the caller supplied it. A restart replays exactly this through the same
     * allow-lists, into the same namespace.</p>
     *
     * @param json      The request body
     * @param namespace The namespace the header named, already validated
     * @return The registration
     * @throws IllegalArgumentException if the body does not describe a source this node builds
     */
    private static SourceDescriptor parseRequest(String json, NamespaceId namespace) {
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
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("Field 'config.source_id' is required");
        }
        String idViolation = SourceIds.violation(sourceId, "config.source_id");
        if (idViolation != null) {
            throw new IllegalArgumentException(idViolation);
        }

        // Type-specific config parsing
        Map<String, String> config = new HashMap<>();

        switch (sourceType.toLowerCase(Locale.ROOT)) {
            case SourceFactory.TYPE_FILESYSTEM:
                String rootPath = JsonText.string(configJson, SourceFactory.ROOT_PATH);
                if (rootPath == null || rootPath.isEmpty()) {
                    throw new IllegalArgumentException("Field 'config.root_path' is required for filesystem source");
                }
                config.put(SourceFactory.ROOT_PATH, rootPath);
                rejectFields(configJson, HTTP_ONLY_FIELDS, "an HTTP crawl only: a directory "
                    + "walk fetches nothing over a wire, so it has no request to time, no "
                    + "response body to bound and no agent to identify itself as");
                putFilesystemTunables(configJson, config);
                break;

            case SourceFactory.TYPE_HTTP:
                String baseUrl = JsonText.string(configJson, SourceFactory.BASE_URL);
                if (baseUrl == null || baseUrl.isEmpty()) {
                    throw new IllegalArgumentException("Field 'config.base_url' is required for HTTP source");
                }
                config.put(SourceFactory.BASE_URL, baseUrl);
                rejectFields(configJson, FILESYSTEM_ONLY_FIELDS, "a filesystem walk only: an "
                    + "HTTP crawl matches URLs, which have neither a file extension nor a "
                    + "directory, no symbolic links, and no local file to size");
                putHttpTunables(configJson, config);
                break;

            default:
                throw new IllegalArgumentException("Unknown source type: " + sourceType);
        }

        return new SourceDescriptor(sourceType, sourceId, config, namespace);
    }

    /**
     * Settings only a filesystem walk can apply.
     *
     * <p>A crawl has a URL and a host policy: no file extension, no directory name, no
     * symbolic link and no local file to size. Nor does it need to be told an encoding: every
     * response names its own in its {@code Content-Type}.</p>
     */
    private static final List<String> FILESYSTEM_ONLY_FIELDS = List.of(
        SourceFactory.INCLUDED_EXTENSIONS, SourceFactory.EXCLUDED_DIRECTORIES,
        SourceFactory.FOLLOW_SYMLINKS, SourceFactory.INCLUDE_HIDDEN,
        SourceFactory.MAX_FILE_BYTES, SourceFactory.CHARSET);

    /** Settings only an HTTP crawl can apply. A directory walk fetches nothing over a wire. */
    private static final List<String> HTTP_ONLY_FIELDS = List.of(
        SourceFactory.MAX_DOCUMENTS, SourceFactory.MAX_RESPONSE_BYTES,
        SourceFactory.TIMEOUT_SECONDS, SourceFactory.INCLUDED_PATH_PATTERNS,
        SourceFactory.EXCLUDED_PATH_PATTERNS, SourceFactory.USER_AGENT,
        SourceFactory.CRAWL_DELAY_MS);

    /**
     * Reads the settings that shape a filesystem walk.
     *
     * @param configJson The request's config object
     * @param config     The descriptor being built
     */
    private static void putFilesystemTunables(String configJson, Map<String, String> config) {
        putList(configJson, config, SourceFactory.INCLUDED_EXTENSIONS);
        putList(configJson, config, SourceFactory.EXCLUDED_DIRECTORIES);
        putBounded(configJson, config, SourceFactory.MAX_DEPTH, 1, SourceFactory.MAX_CRAWL_DEPTH);
        putFlag(configJson, config, SourceFactory.FOLLOW_SYMLINKS);
        putFlag(configJson, config, SourceFactory.INCLUDE_HIDDEN);
        putBounded(configJson, config, SourceFactory.MAX_FILE_BYTES, 1,
            SourceFactory.FILE_BYTES_LIMIT);
        putCharset(configJson, config);
    }

    /**
     * Copies the declared encoding across under its canonical name, refusing one this node
     * cannot decode.
     *
     * <p>Refused here, as a 400 naming the field, rather than when the source is built: an
     * unknown name that reached the catalog would fail every later restore of the
     * registration. The canonical name is what is recorded, so the source's config view shows
     * the encoding the node will actually use rather than whichever alias was sent.</p>
     */
    private static void putCharset(String configJson, Map<String, String> config) {
        if (!JsonText.has(configJson, SourceFactory.CHARSET)) {
            return;
        }
        String name = JsonText.string(configJson, SourceFactory.CHARSET);
        config.put(SourceFactory.CHARSET, SourceFactory.charset(name).name());
    }

    /**
     * Reads the settings that shape an HTTP crawl.
     *
     * @param configJson The request's config object
     * @param config     The descriptor being built
     */
    private static void putHttpTunables(String configJson, Map<String, String> config) {
        putBounded(configJson, config, SourceFactory.MAX_DEPTH, 0, SourceFactory.MAX_CRAWL_DEPTH);
        putBounded(configJson, config, SourceFactory.MAX_DOCUMENTS, 1,
            SourceFactory.DOCUMENTS_LIMIT);
        putBounded(configJson, config, SourceFactory.MAX_RESPONSE_BYTES, 1,
            SourceFactory.RESPONSE_BYTES_LIMIT);
        putBounded(configJson, config, SourceFactory.TIMEOUT_SECONDS, 1,
            SourceFactory.TIMEOUT_SECONDS_LIMIT);
        putList(configJson, config, SourceFactory.INCLUDED_PATH_PATTERNS);
        putList(configJson, config, SourceFactory.EXCLUDED_PATH_PATTERNS);
        putUserAgent(configJson, config);
        // Zero is accepted and means the deployment's own floor rather than "no pacing": the
        // factory takes the larger of the two, so this field can only ask a crawl to be gentler.
        putBounded(configJson, config, SourceFactory.CRAWL_DELAY_MS, 0,
            IngestConfig.MAX_CRAWL_DELAY_MS);
    }

    /**
     * Copies a string array across as a comma-joined value, absent staying absent.
     *
     * <p>A comma inside an element is refused rather than carried. The registration is
     * recorded and restored as a comma-joined string, so an element containing one comes back
     * as two: {@code ["/a,b/"]} would exclude {@code /a} and {@code b/}, far more of the crawl
     * than was asked for, and the caller would be told the source registered fine.</p>
     */
    private static void putList(String configJson, Map<String, String> config, String field) {
        String[] values = JsonText.stringArray(configJson, field);
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value.indexOf(',') >= 0) {
                throw new IllegalArgumentException("Field 'config." + field + "' must not "
                    + "contain a comma in any element: the list is stored comma-separated, so "
                    + "one element would be restored as two. Send them as separate elements.");
            }
        }
        config.put(field, String.join(",", values));
    }

    /** Copies a true/false setting across, absent staying absent. */
    private static void putFlag(String configJson, Map<String, String> config, String field) {
        if (JsonText.has(configJson, field)) {
            config.put(field, Boolean.toString(JsonText.booleanValue(configJson, field, false)));
        }
    }

    /**
     * Copies a whole-number setting across, refusing one outside the bound this node allows.
     *
     * <p>The bound is what stops a registration from sizing this node's work: a crawl depth,
     * a document count and a response size each decide how much memory and how many requests
     * one registration turns into.</p>
     */
    private static void putBounded(String configJson, Map<String, String> config, String field,
                                   long min, long max) {
        if (!JsonText.has(configJson, field)) {
            return;
        }
        long value = JsonText.longValue(configJson, field, min);
        if (value < min || value > max) {
            throw new IllegalArgumentException("Field 'config." + field + "' must be between "
                + min + " and " + max + ", got: " + value);
        }
        config.put(field, Long.toString(value));
    }

    /**
     * Copies the crawl's User-Agent across, refusing one that could forge a header.
     *
     * <p>The value is sent as a request header. A carriage return or a newline in it would end
     * that header and start one the caller wrote, so the crawl would carry headers this node
     * never agreed to send.</p>
     */
    private static void putUserAgent(String configJson, Map<String, String> config) {
        String userAgent = JsonText.string(configJson, SourceFactory.USER_AGENT);
        if (userAgent == null) {
            return;
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException(
                "Field 'config." + SourceFactory.USER_AGENT + "' must not be blank");
        }
        if (userAgent.length() > SourceFactory.USER_AGENT_LIMIT) {
            throw new IllegalArgumentException("Field 'config." + SourceFactory.USER_AGENT
                + "' must be at most " + SourceFactory.USER_AGENT_LIMIT + " characters, got: "
                + userAgent.length());
        }
        for (int i = 0; i < userAgent.length(); i++) {
            char c = userAgent.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("Field 'config." + SourceFactory.USER_AGENT
                    + "' must not contain control characters: one of them would end the header "
                    + "and let the rest of the value become headers this node did not send.");
            }
        }
        config.put(SourceFactory.USER_AGENT, userAgent);
    }

    /**
     * Refuses a setting on a source that cannot apply it.
     *
     * <p>Refused rather than dropped. A request carrying one would otherwise be answered
     * {@code 200 registered} and then crawled with the setting discarded, with nothing in the
     * response or in the status to say so.</p>
     *
     * @param configJson The request's config object
     * @param fields     The settings this source type cannot honour
     * @param reason     What those settings belong to and why this source cannot apply them
     * @throws IllegalArgumentException if any of them is present
     */
    private static void rejectFields(String configJson, List<String> fields, String reason) {
        for (String field : fields) {
            if (JsonText.has(configJson, field)) {
                throw new IllegalArgumentException("Field 'config." + field + "' applies to "
                    + reason + ". Remove it, or register this source with the source_type it "
                    + "belongs to.");
            }
        }
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
     * Response after registering a source.
     */
    private record IngestResponse(String sourceId, String sourceType, String status) {}
}
