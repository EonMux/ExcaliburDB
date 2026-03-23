package excalibur.api.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.endpoint.HealthEndpoint;
import excalibur.api.endpoint.StatsEndpoint;
import excalibur.api.endpoint.QueryEndpoint;
import excalibur.api.endpoint.IngestEndpoint;
import excalibur.api.endpoint.ReindexEndpoint;
import excalibur.api.endpoint.IngestStatusEndpoint;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP API server for ExcaliburDB.
 *
 * <p>ExcaliburDBServer provides the HTTP API surface for the database using
 * the built-in {@link HttpServer} with virtual thread dispatch for efficient
 * concurrent request handling.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /health} - Health check for load balancers and Kubernetes</li>
 *   <li>{@code GET /v1/stats} - System statistics and observability</li>
 *   <li>{@code POST /v1/query} - Query execution</li>
 *   <li>{@code POST /v1/ingest} - Register ingestion source</li>
 *   <li>{@code POST /v1/reindex} - Force reindex of a source</li>
 *   <li>{@code GET /v1/ingest/status} - Ingestion status per source</li>
 * </ul>
 *
 * <h2>Virtual Thread Dispatch</h2>
 * <p>Requests are dispatched to virtual threads using
 * {@link Executors#newVirtualThreadPerTaskExecutor()}, enabling efficient
 * handling of many concurrent connections without thread pool limits.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Server binds to specified port on all interfaces (0.0.0.0)</li>
 *   <li>Graceful shutdown waits for in-flight requests</li>
 *   <li>Health endpoint always returns 200 if process is alive</li>
 *   <li>Stats reflect current system state (no stale data)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is thread-safe.</strong></p>
 * <ul>
 *   <li>Start/stop operations are atomic</li>
 *   <li>All endpoints are thread-safe</li>
 *   <li>Virtual thread executor handles concurrent requests safely</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class ExcaliburDBServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcaliburDBServer.class);

    /**
     * Default port for the API server.
     */
    public static final int DEFAULT_PORT = 8080;

    /**
     * Default backlog for incoming connections.
     */
    public static final int DEFAULT_BACKLOG = 0;

    private final int port;
    private final int backlog;
    private final IndexOrchestrator indexOrchestrator;
    private final StorageEngine storageEngine;
    private final QueryPipeline queryPipeline;

    // Registries for ingestion endpoints
    private final IngestEndpoint.SourceRegistry ingestSourceRegistry;
    private final ReindexEndpoint.ReindexStatusRegistry reindexStatusRegistry;
    private final IngestStatusEndpoint.IngestionStatusRegistry ingestionStatusRegistry;

    private HttpServer server;
    private final AtomicBoolean running;

    /**
     * Creates a new ExcaliburDBServer with default port.
     *
     * @param indexOrchestrator The index orchestrator for visibility and worker metrics
     * @param storageEngine The storage engine for volume information
     * @param queryPipeline The query pipeline for executing queries
     * @throws NullPointerException if any parameter is null
     */
    public ExcaliburDBServer(IndexOrchestrator indexOrchestrator,
                             StorageEngine storageEngine,
                             QueryPipeline queryPipeline) {
        this(DEFAULT_PORT, indexOrchestrator, storageEngine, queryPipeline);
    }

    /**
     * Creates a new ExcaliburDBServer with specified port.
     *
     * @param port The port to bind to (1-65535)
     * @param indexOrchestrator The index orchestrator
     * @param storageEngine The storage engine
     * @param queryPipeline The query pipeline
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if port is out of range
     */
    public ExcaliburDBServer(int port,
                             IndexOrchestrator indexOrchestrator,
                             StorageEngine storageEngine,
                             QueryPipeline queryPipeline) {
        this(port, DEFAULT_BACKLOG, indexOrchestrator, storageEngine, queryPipeline);
    }

    /**
     * Creates a new ExcaliburDBServer with full configuration.
     *
     * @param port The port to bind to
     * @param backlog The connection backlog (0 for system default)
     * @param indexOrchestrator The index orchestrator
     * @param storageEngine The storage engine
     * @param queryPipeline The query pipeline
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if port is out of range
     */
    public ExcaliburDBServer(int port,
                             int backlog,
                             IndexOrchestrator indexOrchestrator,
                             StorageEngine storageEngine,
                             QueryPipeline queryPipeline) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be in range 0-65535: " + port);
        }
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        Objects.requireNonNull(queryPipeline, "QueryPipeline cannot be null");

        this.port = port;
        this.backlog = backlog;
        this.indexOrchestrator = indexOrchestrator;
        this.storageEngine = storageEngine;
        this.queryPipeline = queryPipeline;
        this.running = new AtomicBoolean(false);

        // Initialize registries for ingestion endpoints
        this.ingestSourceRegistry = new IngestEndpoint.InMemorySourceRegistry();
        this.reindexStatusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        this.ingestionStatusRegistry = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();

        LOGGER.debug("ExcaliburDBServer configured for port {}", port);
    }

    /**
     * Starts the API server.
     *
     * <p>The server binds to the configured port and begins accepting requests.
     * Requests are dispatched to virtual threads for efficient concurrent handling.</p>
     *
     * @throws IOException if the server cannot bind to the port
     * @throws IllegalStateException if the server is already running
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("Server is already running");
        }

        LOGGER.info("Starting ExcaliburDB API server on port {}", port);

        server = HttpServer.create(new InetSocketAddress(port), backlog);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        registerEndpoints();

        server.start();
        running.set(true);

        LOGGER.info("ExcaliburDB API server started successfully on port {}", port);
        LOGGER.info("Endpoints available:");
        LOGGER.info("  GET  /health           - Health check");
        LOGGER.info("  GET  /v1/stats         - System statistics");
        LOGGER.info("  POST /v1/query         - Query execution");
        LOGGER.info("  POST /v1/ingest        - Register ingestion source");
        LOGGER.info("  POST /v1/reindex       - Force reindex");
        LOGGER.info("  GET  /v1/ingest/status - Ingestion status");
    }

    /**
     * Stops the API server gracefully.
     *
     * <p>The server stops accepting new connections immediately but waits
     * for in-flight requests to complete before fully shutting down.</p>
     *
     * @param timeoutSeconds Maximum time to wait for in-flight requests (0 for immediate)
     */
    public synchronized void stop(int timeoutSeconds) {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        LOGGER.info("Stopping ExcaliburDB API server...");

        if (server != null) {
            server.stop(timeoutSeconds);
            server = null;
        }

        LOGGER.info("ExcaliburDB API server stopped");
    }

    /**
     * Stops the API server immediately.
     */
    public void stop() {
        stop(0);
    }

    /**
     * Checks if the server is currently running.
     *
     * @return true if the server is running and accepting requests
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the port the server is bound to.
     *
     * @return The port number, or -1 if not started
     */
    public int getPort() {
        if (server == null) {
            return -1;
        }
        return server.getAddress().getPort();
    }

    /**
     * Registers all API endpoints.
     */
    private void registerEndpoints() {
        // Core endpoints
        HealthEndpoint healthEndpoint = new HealthEndpoint(indexOrchestrator);
        StatsEndpoint statsEndpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
        QueryEndpoint queryEndpoint = new QueryEndpoint(queryPipeline);

        // Ingestion endpoints
        IngestEndpoint ingestEndpoint = new IngestEndpoint(ingestSourceRegistry);
        ReindexEndpoint reindexEndpoint = new ReindexEndpoint(
            new ReindexEndpoint.InMemorySourceRegistry(), // Source registry adapter
            reindexStatusRegistry
        );
        IngestStatusEndpoint ingestStatusEndpoint = new IngestStatusEndpoint(ingestionStatusRegistry);

        // Register endpoints
        server.createContext("/health", wrapHandler(healthEndpoint));
        server.createContext("/v1/stats", wrapHandler(statsEndpoint));
        server.createContext("/v1/query", wrapHandler(queryEndpoint));
        server.createContext("/v1/ingest", wrapHandler(ingestEndpoint));
        server.createContext("/v1/reindex", wrapHandler(reindexEndpoint));
        server.createContext("/v1/ingest/status", wrapHandler(ingestStatusEndpoint));

        server.createContext("/", this::handleNotFound);

        LOGGER.debug("Registered endpoints: /health, /v1/stats, /v1/query, /v1/ingest, /v1/reindex, /v1/ingest/status");
    }

    /**
     * Wraps a handler with logging and error handling.
     *
     * @param handler The original handler
     * @return A wrapped handler with logging
     */
    private HttpHandler wrapHandler(HttpHandler handler) {
        return exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            long start = System.currentTimeMillis();

            try {
                LOGGER.debug("Request: {} {} from {}", method, path, 
                    exchange.getRemoteAddress().getAddress().getHostAddress());

                handler.handle(exchange);

                long duration = System.currentTimeMillis() - start;
                LOGGER.debug("Response: {} {} - {}ms", method, path, duration);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                LOGGER.error("Error handling {} {}: {} ({}ms)", method, path, e.getMessage(), duration, e);

                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    sendError(exchange, 500, "Internal Server Error");
                }
            } finally {
                exchange.close();
            }
        };
    }

    /**
     * Handles requests to unknown paths with 404.
     */
    private void handleNotFound(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        LOGGER.debug("404 Not Found: {}", path);
        sendError(exchange, 404, "Not Found");
    }

    /**
     * Sends an error response.
     */
    private void sendError(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String message) 
            throws IOException {
        String json = String.format("{\"error\": \"%s\"}", escapeJson(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    @Override
    public String toString() {
        return String.format("ExcaliburDBServer{port=%d, running=%s}", 
            server != null ? server.getAddress().getPort() : port, running.get());
    }
}
