package excalibur.api.server;

import com.sun.net.httpserver.HttpServer;
import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.endpoint.BuildInfo;
import excalibur.api.endpoint.InMemorySourceRegistry;
import excalibur.api.endpoint.SourceRegistry;
import excalibur.api.retrieval.LexicalQueryService;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.lsh.LshIngestionService;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.SourceCatalog;
import excalibur.ingest.source.SourceResolver;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.llm.embedding.EmbeddingClient;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.config.Config;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.retrieval.pipeline.GraphRagPipeline;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 *   <li>{@code GET /health} - Liveness check for load balancers and Kubernetes</li>
 *   <li>{@code GET /health/ready} - Readiness, answering 200 or 503 so a probe can act</li>
 *   <li>{@code GET /metrics} - Prometheus text exposition for a scraper</li>
 *   <li>{@code GET /v1/stats} - System statistics and observability</li>
 *   <li>{@code GET /v1/version} - Which build this process is running</li>
 *   <li>{@code POST}/{@code DELETE /v1/drain} - Take this node out of rotation, and back</li>
 *   <li>{@code GET /v1/query/status} - Queries in flight</li>
 *   <li>{@code POST /v1/query} - Query execution</li>
 *   <li>{@code POST /v1/ingest} - Register ingestion source</li>
 *   <li>{@code POST /v1/reindex} - Force reindex of a source</li>
 *   <li>{@code GET /v1/ingest/status} - Ingestion status per source</li>
 * </ul>
 *
 * <h2>What this class owns</h2>
 * <p>The listener and its lifecycle, and nothing else. What the node is made of is
 * {@link ServerWiring}'s, which every {@code with...} method below delegates to; the route
 * table is {@link EndpointRegistrar}'s; the bind is {@link HttpListener}'s; the order of a
 * shutdown is {@link ShutdownSequence}'s. The public surface stays here because it is the API
 * an embedder writes against, and the guard those methods share - that it is too late once the
 * node is accepting - is a fact about the listener this class owns.</p>
 *
 * <h2>Virtual Thread Dispatch</h2>
 * <p>Requests are dispatched to virtual threads using
 * {@link Executors#newVirtualThreadPerTaskExecutor()}, enabling efficient
 * handling of many concurrent connections without thread pool limits.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Server binds to 127.0.0.1 unless an explicit bind address and an
 *       authentication token are configured</li>
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

    /** What this node is made of; every configuration method below writes into it. */
    private final ServerWiring wiring;

    /** The endpoints that own executors, as {@link #start()} registered them. */
    private volatile EndpointRegistrar.Endpoints endpoints;

    private volatile HttpServer server;
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
        this(port, DEFAULT_BACKLOG, indexOrchestrator, storageEngine, null, null, queryPipeline);
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
        this(port, backlog, indexOrchestrator, storageEngine, null, null, queryPipeline);
    }

    /**
     * Creates a new ExcaliburDBServer with full configuration including VolumeManager.
     *
     * <p>When VolumeManager is provided, the /v1/stats endpoint will report
     * accurate disk space usage from the underlying filesystem. Without it,
     * volume stats fall back to directory metadata size which may be inaccurate.</p>
     *
     * @param port The port to bind to
     * @param backlog The connection backlog (0 for system default)
     * @param indexOrchestrator The index orchestrator
     * @param storageEngine The storage engine
     * @param volumeManager Optional volume manager for accurate disk space tracking
     * @param queryPipeline The query pipeline
     * @throws NullPointerException if required parameters are null
     * @throws IllegalArgumentException if port is out of range
     */
    public ExcaliburDBServer(int port,
                             int backlog,
                             IndexOrchestrator indexOrchestrator,
                             StorageEngine storageEngine,
                             VolumeManager volumeManager,
                             QueryPipeline queryPipeline) {
        this(port, backlog, indexOrchestrator, storageEngine, volumeManager, null, queryPipeline);
    }

    /**
     * Creates a new ExcaliburDBServer with full configuration including Config.
     *
     * <p>When Config is provided, configuration values from the properties file
     * or environment variables will be used for defaults in endpoints.</p>
     *
     * <p>When VolumeManager is provided, the /v1/stats endpoint will report
     * accurate disk space usage from the underlying filesystem. Without it,
     * volume stats fall back to directory metadata size which may be inaccurate.</p>
     *
     * @param port The port to bind to
     * @param backlog The connection backlog (0 for system default)
     * @param indexOrchestrator The index orchestrator
     * @param storageEngine The storage engine
     * @param volumeManager Optional volume manager for accurate disk space tracking
     * @param config Optional configuration for endpoint defaults
     * @param queryPipeline The query pipeline
     * @throws NullPointerException if required parameters are null
     * @throws IllegalArgumentException if port is out of range
     */
    public ExcaliburDBServer(int port,
                             int backlog,
                             IndexOrchestrator indexOrchestrator,
                             StorageEngine storageEngine,
                             VolumeManager volumeManager,
                             Config config,
                             QueryPipeline queryPipeline) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be in range 0-65535: " + port);
        }

        this.port = port;
        this.backlog = backlog;
        this.running = new AtomicBoolean(false);
        this.wiring = new ServerWiring(
            indexOrchestrator, storageEngine, volumeManager, config, queryPipeline);

        LOGGER.debug("ExcaliburDBServer configured for port {} with VolumeManager: {}, Config: {}",
            port, volumeManager != null ? "enabled" : "disabled",
            config != null ? "enabled" : "disabled");
    }

    /**
     * Refuses a configuration change that the running server would silently ignore.
     *
     * <p>Every route reads its collaborators once, while {@link #start()} registers it, so a
     * change made afterwards would take effect on nothing. Failing loudly is the only way the
     * caller learns that; accepting it and serving the old wiring is not.</p>
     *
     * @param what What the caller was trying to configure, for the message
     * @throws IllegalStateException if the server is already running
     */
    private void requireNotStarted(String what) {
        if (running.get()) {
            throw new IllegalStateException("Cannot configure " + what + " after server has started");
        }
    }

    /**
     * Overrides the security policy resolved from configuration.
     *
     * <p>Intended for wiring that builds its policy elsewhere and for tests that need
     * a specific allow-list or token. Must be called before {@link #start()}.</p>
     *
     * @param securityConfig The policy to enforce
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if securityConfig is null
     */
    public void withSecurity(ApiSecurityConfig securityConfig) {
        requireNotStarted("security");
        wiring.withSecurity(securityConfig);
    }

    /** Reports this node's chunk lexical index on the stats endpoint. */
    public void withChunkIndex(ChunkIndex chunkIndex) {
        wiring.withChunkIndex(chunkIndex);
    }

    /** Enables {@code POST /v1/erasure} on this server. */
    public void withDocumentErasure(DocumentErasureService service) {
        requireNotStarted("erasure");
        wiring.withDocumentErasure(service);
    }

    /**
     * Enables {@code GET /v1/documents/list} on this server.
     *
     * @param registry What this node has ingested
     * @throws IllegalStateException if the server is already running
     */
    public void withDocumentRegistry(excalibur.ingest.registry.DocumentRegistry registry) {
        requireNotStarted("the document registry");
        wiring.withDocumentRegistry(registry);
    }

    /**
     * Makes source registrations survive a restart.
     *
     * <p>Must be called before {@link #start()}: the catalog is read once while the endpoints are
     * built, and its records are put back through the same allow-lists a fresh {@code POST
     * /v1/ingest} would meet.</p>
     *
     * @param catalog Where registrations are recorded
     * @throws IllegalStateException if the server is already running
     */
    public void withSourceCatalog(SourceCatalog catalog) {
        requireNotStarted("the source catalog");
        wiring.withSourceCatalog(catalog);
    }

    /** Attaches lexical retrieval, before the server starts. */
    public void withLexicalQueryService(LexicalQueryService service) {
        wiring.withLexicalQueryService(service);
    }

    /** @param expander The expander the lexical half widens queries with, or null for none */
    public void withQueryExpander(io.excalibur.retrieval.expand.QueryExpander expander) {
        wiring.withQueryExpander(expander);
    }

    /**
     * Returns the ingestion orchestrator, once one has been wired.
     *
     * <p>For a caller that has to attach an optional collaborator to it after the pipeline is
     * built: the graph sink and the membership filter both belong to stacks assembled later
     * than this server.</p>
     *
     * @return The orchestrator, or null when ingestion runs in stub mode
     */
    public IngestionOrchestrator getIngestionOrchestrator() {
        return wiring.ingestionOrchestrator();
    }

    /**
     * Returns the security policy this server enforces.
     *
     * @return The security policy (never null)
     */
    public ApiSecurityConfig getSecurity() {
        return wiring.security();
    }

    /**
     * Configures the LLM scorer for relevancy scoring in Stage 5 of the query pipeline.
     *
     * <p>This method wraps the existing {@link QueryPipeline} with an LLM scorer,
     * enabling Stage 5 LLM-based relevancy scoring for query results. The scorer
     * uses an LLM endpoint to evaluate semantic relevance of candidates.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>LLM scorer may be null to disable LLM scoring</li>
     *   <li>Method must be called before {@link #start()} to take effect</li>
     *   <li>Creates a new QueryPipeline instance, preserving all other configuration</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param llmScorer The LLM scorer to use for Stage 5 relevancy scoring, or null to disable
     * @throws IllegalStateException if the server is already running
     * @see excalibur.query.scorer.LlmScorerFactory
     */
    public void withLlmScorer(QueryPipeline.LlmScorer llmScorer) {
        requireNotStarted("LLM scorer");
        wiring.withLlmScorer(llmScorer);
    }

    /**
     * Whether a document's chunks become visible to queries together.
     *
     * @return true when the atomicity filter should run
     * @see ServerWiring#isDocumentAtomicityEnabled()
     */
    boolean isDocumentAtomicityEnabled() {
        return wiring.isDocumentAtomicityEnabled();
    }

    /**
     * Configures the document reindexer for actual reindex operations.
     *
     * <p>When configured, the {@code POST /v1/reindex} endpoint will execute
     * actual reindex operations in background virtual threads. Without this
     * configuration, the endpoint operates in stub mode (accepts requests but
     * performs no actual reindexing).</p>
     *
     * <h3>Zero-Downtime Rolling Replacement</h3>
     * <p>If VolumeManager and SegmentCatalog are available, scheduled reindexes
     * will use rolling replacement:</p>
     * <ul>
     *   <li>New segments are created in BOOTSTRAPPING state</li>
     *   <li>Old segments continue serving queries during reindexing</li>
     *   <li>New segments progress through INDEXING → WARMING → ACTIVE</li>
     *   <li>Old segments are tombstoned after new segments become ACTIVE</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>DocumentReindexer and SourceResolver must both be non-null or both be null</li>
     *   <li>Method must be called before {@link #start()} to take effect</li>
     *   <li>SourceResolver typically delegates to the shared SourceRegistry</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param documentReindexer The document reindexer for executing reindex operations
     * @param sourceResolver The source resolver for obtaining Source instances by ID
     * @throws IllegalStateException if the server is already running
     * @throws IllegalArgumentException if only one parameter is null
     */
    public void withReindexer(DocumentReindexer documentReindexer, SourceResolver sourceResolver) {
        requireNotStarted("reindexer");
        wiring.withReindexer(documentReindexer, sourceResolver);
    }

    /**
     * Creates a SourceResolver that delegates to the internal source registry.
     *
     * <p>This method provides a convenient way to create a SourceResolver that
     * resolves sources from the internal {@link InMemorySourceRegistry},
     * enabling reindex operations for sources registered via /v1/ingest.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Only resolves sources that have been registered via /v1/ingest</li>
     *   <li>Returns null for unregistered source IDs</li>
     *   <li>Thread-safe delegation to the internal concurrent registry</li>
     * </ul>
     *
     * @return A SourceResolver that resolves sources from the ingest registry
     */
    public SourceResolver createSourceResolver() {
        return wiring.createSourceResolver();
    }

    /**
     * Configures the document reindexer with automatic source resolution.
     *
     * <p>Creates a SourceResolver that delegates to the internal source registry,
     * enabling reindex operations for sources registered via /v1/ingest.</p>
     *
     * <p>This is a convenience overload of {@link #withReindexer(DocumentReindexer, SourceResolver)}
     * that automatically creates a SourceResolver using {@link #createSourceResolver()}.</p>
     *
     * <h3>Zero-Downtime Rolling Replacement</h3>
     * <p>If VolumeManager and SegmentCatalog are available, scheduled reindexes
     * will use rolling replacement:</p>
     * <ul>
     *   <li>New segments are created in BOOTSTRAPPING state</li>
     *   <li>Old segments continue serving queries during reindexing</li>
     *   <li>New segments progress through INDEXING → WARMING → ACTIVE</li>
     *   <li>Old segments are tombstoned after new segments become ACTIVE</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Automatically creates SourceResolver that resolves from internal registry</li>
     *   <li>Method must be called before {@link #start()} to take effect</li>
     *   <li>SourceResolver delegates to the shared SourceRegistry</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param documentReindexer The document reindexer for executing reindex operations
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if documentReindexer is null
     */
    public void withReindexer(DocumentReindexer documentReindexer) {
        requireNotStarted("reindexer");
        wiring.withReindexer(documentReindexer);
    }

    /**
     * Configures the ingestion pipeline for background document processing.
     *
     * <p>When configured, the {@code POST /v1/ingest} endpoint will trigger actual
     * background ingestion: crawling, chunking, embedding, and indexing documents
     * from registered sources. Without this configuration, the endpoint only stores
     * source metadata without processing documents.</p>
     *
     * <h3>Document Atomicity</h3>
     * <p>This method creates a DocumentVisibilityTracker that ensures documents become
     * visible atomically - all chunks at once. The tracker is also wired to the query
     * pipeline via the visibility checker, enabling the DocumentAtomicityFilter to
     * exclude partial documents from query results.</p>
     *
     * <h3>Configuration</h3>
     * <p>This method reads the following configuration from the provided Config:</p>
     * <ul>
     *   <li>{@code excalibur.ingest.document-atomicity-timeout-ms} - Timeout for HNSW backpressure (default 2000ms)</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>TextChunker, LLMHttpClient must both be non-null</li>
     *   <li>Method must be called before {@link #start()} to take effect</li>
     *   <li>Ingestion runs in virtual threads for efficient concurrency</li>
     *   <li>Document visibility is epoch-based for consistent query snapshots</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param textChunker The text chunker for splitting documents into chunks
     * @param llmClient The LLM client for generating embeddings
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if any parameter is null
     */
    public void withIngestionPipeline(TextChunker textChunker, LLMHttpClient llmClient) {
        requireNotStarted("ingestion pipeline");
        wiring.withIngestionPipeline(textChunker, llmClient);
    }

    /**
     * Configures the ingestion pipeline with an externally provided visibility tracker.
     *
     * <p>This overload allows sharing a DocumentVisibilityTracker between the application
     * and the server, ensuring consistent document atomicity across the entire system.</p>
     *
     * <h3>Use Case</h3>
     * <p>Use this method when ExcaliburApplication creates the visibility tracker and
     * passes it to the server for ingestion pipeline configuration.</p>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param textChunker The text chunker for splitting documents into chunks
     * @param embeddingClient The embedding client for generating embeddings
     * @param visibilityTracker The document visibility tracker for atomic visibility (may be null)
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if textChunker or embeddingClient is null
     */
    public void withIngestionPipeline(TextChunker textChunker, EmbeddingClient embeddingClient,
                                      DocumentVisibilityTracker visibilityTracker) {
        requireNotStarted("ingestion pipeline");
        wiring.withIngestionPipeline(textChunker, embeddingClient, visibilityTracker);
    }

    /**
     * Configures the ingestion pipeline and the vector pre-filter used by stage 2 of queries.
     *
     * <p>The pre-filter is not populated here. It is keyed by HNSW node id, so it is written by the
     * integration worker as it assigns those ids; ingestion holds the vector before any id exists
     * for it. This method wires the pre-filter to the index orchestrator that owns that worker.</p>
     *
     * <h3>LSH Integration</h3>
     * <p>When LSH is configured:</p>
     * <ul>
     *   <li>Every vector written to a segment is also inserted into its LSH index</li>
     *   <li>Query Stage 2 (LSH pre-filter) can efficiently prune candidates</li>
     *   <li>Tombstones are removed from LSH indices during deletion propagation</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param textChunker The text chunker for splitting documents into chunks
     * @param embeddingClient The embedding client, or null for lexical-only ingestion
     * @param lshIngestionService The LSH ingestion service for vector indexing (may be null to disable LSH)
     * @param visibilityTracker The document visibility tracker for atomic visibility (may be null)
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if textChunker is null
     */
    public void withIngestionPipeline(TextChunker textChunker, EmbeddingClient embeddingClient,
                                      LshIngestionService lshIngestionService,
                                      DocumentVisibilityTracker visibilityTracker) {
        requireNotStarted("ingestion pipeline");
        wiring.withIngestionPipeline(
            textChunker, embeddingClient, lshIngestionService, visibilityTracker);
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

        // Refuse a configuration that would expose an unauthenticated or cleartext listener
        // before anything is bound.
        wiring.security().requireSafeToServe();

        IndexOrchestrator indexOrchestrator = wiring.indexOrchestrator();
        HttpServer created = HttpListener.open(wiring.security(), port, backlog);
        boolean orchestratorStarted = false;
        try {
            created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            this.endpoints = EndpointRegistrar.register(created, wiring.endpointInputs());

            // Start the index orchestrator (including epoch tick scheduler) before accepting requests
            // This ensures the visibility epoch advances automatically without manual intervention
            indexOrchestrator.start();
            orchestratorStarted = true;
            LOGGER.info("Index orchestrator started - epoch tick scheduler running at {}ms interval",
                indexOrchestrator.getEpochTickScheduler().getTickIntervalMs());

            created.start();
        } catch (RuntimeException e) {
            // The listening socket is already bound at this point; leaving it - and a
            // started orchestrator - behind on a failed start leaks both, because stop()
            // does nothing until `running` is set.
            created.stop(0);
            if (orchestratorStarted) {
                indexOrchestrator.close();
            }
            throw e;
        }

        server = created;
        running.set(true);
        logStarted();
    }

    /** What an operator needs once the node is accepting: where it is, and what it serves. */
    private void logStarted() {
        // getPort(), not the configured port: a node started on 0 binds an ephemeral port, and
        // telling the operator it came up "on port 0" withholds the one fact they need to reach it.
        // The build, first. "Which version is on this host" is the first question of an
        // incident and of a rollback, and a banner that lists every route without ever
        // saying what it is a banner for leaves it to be answered off the filesystem.
        LOGGER.info("ExcaliburDB {} on {} started successfully on port {} ({}, "
                + "authentication {})",
            BuildInfo.version(), BuildInfo.jvm(),
            getPort(), wiring.security().tls().isEnabled() ? "TLS" : "cleartext",
            wiring.security().isAuthenticationRequired() ? "required" : "disabled");
        LOGGER.info("Endpoints available:");
        for (String line : wiring.bannerLines()) {
            LOGGER.info("{}", line);
        }
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

        // Order matters. Stop accepting first, then drain everything that can still be
        // touching storage, and only then let the caller close the storage engine and
        // the shared arena. A background task still writing into a mapping whose arena
        // has been closed crashes the JVM rather than throwing.
        HttpServer current = server;
        if (current != null) {
            current.stop(timeoutSeconds);
            server = null;
        }

        ShutdownSequence.drain(endpoints, wiring);

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
        HttpServer current = server;
        if (current == null) {
            return -1;
        }
        return current.getAddress().getPort();
    }

    /**
     * Returns the index orchestrator for visibility and worker metrics.
     *
     * <p>This provides access to the orchestrator for testing and advanced
     * configuration purposes, including:</p>
     * <ul>
     *   <li>Visibility epoch tracking</li>
     *   <li>Background worker metrics</li>
     *   <li>Reindex scheduler configuration</li>
     * </ul>
     *
     * @return The index orchestrator (never null)
     */
    public IndexOrchestrator getIndexOrchestrator() {
        return wiring.indexOrchestrator();
    }

    /**
     * Attaches a graph-RAG pipeline the caller opened itself, enabling
     * {@code POST /v1/graph/query} and nothing else.
     *
     * <p>For embedding this server in a process that already holds a pipeline. The standalone
     * node uses {@link #withGraphSupport} instead, which also registers the build and erasure
     * routes; a pipeline attached here is a graph somebody else's code owns, so this server
     * offers no way to rebuild or erase from it and does not close it.</p>
     *
     * <p>Ignored when {@link #withGraphSupport} has also been called: a node that can build its
     * own graph serves that one.</p>
     *
     * <p>Must be called before {@link #start()}; endpoints are registered there, so a pipeline
     * supplied afterwards would be silently ignored. Rejecting it late is better than accepting it
     * and serving 404.</p>
     *
     * @param pipeline The pipeline to serve, or null for none
     * @return this server, for chaining
     * @throws IllegalStateException if the server is already running
     */
    public ExcaliburDBServer withGraphPipeline(GraphRagPipeline pipeline) {
        if (isRunning()) {
            throw new IllegalStateException(
                "graph pipeline must be attached before start(); endpoints are registered there");
        }
        wiring.withGraphPipeline(pipeline);
        return this;
    }

    /**
     * Attaches a graph this node builds and serves itself, enabling {@code POST /v1/graph/query}
     * and {@code POST /v1/graph/build}.
     *
     * <p>Both routes are registered whether or not a graph exists yet. A node that has ingested
     * but not built has the second and needs it; registering the first only when a graph happened
     * to be present at startup is what made a freshly built graph invisible until a restart.
     *
     * <p>Must be called before {@link #start()}; endpoints are registered there.
     *
     * @param tenants Every namespace's graph: what it serves, what it has staged, what it owes
     * @return this server, for chaining
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if tenants is null
     */
    public ExcaliburDBServer withGraphSupport(GraphTenants tenants) {
        if (isRunning()) {
            throw new IllegalStateException(
                "graph support must be attached before start(); endpoints are registered there");
        }
        wiring.withGraphSupport(tenants);
        return this;
    }

    @Override
    public String toString() {
        HttpServer current = server;
        return String.format("ExcaliburDBServer{port=%d, running=%s}",
            current != null ? current.getAddress().getPort() : port, running.get());
    }

    /**
     * Returns the registry ingestion sources are registered in.
     *
     * @return The source registry
     */
    public SourceRegistry getIngestSourceRegistry() {
        return wiring.ingestSourceRegistry();
    }
}
