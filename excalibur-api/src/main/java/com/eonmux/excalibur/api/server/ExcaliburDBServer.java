package com.eonmux.excalibur.api.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.api.endpoint.BuildInfo;
import com.eonmux.excalibur.api.endpoint.InMemorySourceRegistry;
import com.eonmux.excalibur.api.endpoint.SourceNamespaceLookup;
import com.eonmux.excalibur.api.endpoint.SourceRegistry;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lsh.LshIngestionService;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.NamespaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * HTTP API server for ExcaliburDB.
 *
 * <p>ExcaliburDBServer provides the HTTP API surface for the database using
 * the built-in {@link HttpServer} with virtual thread dispatch for efficient
 * concurrent request handling.</p>
 *
 * <h2>Endpoints</h2>
 * <p>Every route, with its access level and rate-limit budget, is registered by
 * {@link EndpointRegistrar}; {@link EndpointBanner} prints the same set at startup.
 * Neither is repeated here, so there is one list to keep current.</p>
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

    /** The executor requests are dispatched on, once {@link #start()} has created it. */
    private volatile ExecutorService requestExecutor;

    /**
     * Whether the last {@link #stop(int)} left work running that still reads storage.
     *
     * <p>Set when a drain outlived its wait, and set before the drain starts and cleared only by
     * a drain that returned: a drain step that threw may have left exactly the run it was meant
     * to stop still going, and assuming otherwise would close storage underneath it. The storage
     * engine and its arena must then stay open: a rewrite still reading a mapping whose arena has
     * closed takes the JVM down rather than throwing, where leaving storage unclosed at exit
     * costs only a replay of the write log on the next start.</p>
     */
    private volatile boolean storageStillInUse;

    /**
     * Whether the last {@link #stop(int)} left work running that still compacts or writes the
     * chunk index. Held the same pessimistic way as {@link #storageStillInUse}.
     */
    private volatile boolean chunkIndexStillInUse;

    /**
     * Whether the last {@link #stop(int)} left work running that still builds into or reads the
     * graph tenants. Held the same pessimistic way as {@link #storageStillInUse}.
     */
    private volatile boolean graphsStillInUse;

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
    public void withDocumentRegistry(com.eonmux.excalibur.ingest.registry.DocumentRegistry registry) {
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

    /**
     * Makes {@code GET /v1/ingest/status} survive a restart.
     *
     * <p>Must be called before {@link #start()}, for the reason {@link #withSourceCatalog} must:
     * the sources the catalog restores take their status from this file as they come back, so a
     * source crawled before the restart reports that crawl rather than zeros stamped with the
     * moment the node came up.</p>
     *
     * @param statusFile Where each source's last finished run is written
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException  if the file is null
     */
    public void withSourceStatus(SourceStatusFile statusFile) {
        requireNotStarted("the source status file");
        wiring.withSourceStatus(statusFile);
    }

    /** Attaches lexical retrieval, before the server starts. */
    public void withLexicalQueryService(LexicalQueryService service) {
        wiring.withLexicalQueryService(service);
    }

    /** @param expander The expander the lexical half widens queries with, or null for none */
    public void withQueryExpander(com.eonmux.excalibur.retrieval.expand.QueryExpander expander) {
        wiring.withQueryExpander(expander);
    }

    /**
     * Returns the ingestion orchestrator, once one has been wired.
     *
     * <p>For a caller that has to attach an optional collaborator to it after the pipeline is
     * built: the graph sink and the membership filter both belong to stacks assembled later
     * than this server.</p>
     *
     * @return The orchestrator, or null when no ingestion pipeline is wired
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
     * Configures the LLM scorer for relevancy scoring in Stage 4 of the query pipeline.
     *
     * <p>This method wraps the existing {@link QueryPipeline} with an LLM scorer,
     * enabling Stage 4 LLM-based relevancy scoring for query results. The scorer
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
     * @param llmScorer The LLM scorer to use for Stage 4 relevancy scoring, or null to disable
     * @throws IllegalStateException if the server is already running
     * @see com.eonmux.excalibur.query.scorer.LlmScorerFactory
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
     * configuration the route still exists and answers 503, rather than accepting
     * a reindex that nothing would run.</p>
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
     * Configures the ingestion pipeline and the vector pre-filter used by stage 1 of queries.
     *
     * <p>With a pipeline attached, {@code POST /v1/ingest} crawls, chunks, embeds and indexes
     * the documents of every source it registers, in the background. Without one it records
     * the registration and indexes nothing, and {@code GET /v1/ingest/status} reports the
     * source as {@code unconfigured}.</p>
     *
     * <h3>Document Atomicity</h3>
     * <p>The visibility tracker passed in is shared with the application rather than made
     * here, so the application and this node agree about which documents are whole. Its
     * registry is the persisted one: a tracker over a registry of its own would forget at
     * every restart which chunks belong to which document. Unless
     * {@code excalibur.ingest.document-atomicity} is {@code false}, the query pipeline is
     * wrapped in the atomicity filter so a query never sees half of a document; how long one
     * document may wait on HNSW backpressure is
     * {@code excalibur.ingest.document-atomicity-timeout-ms}.</p>
     *
     * <p>The pre-filter is not populated here. It is keyed by HNSW node id, so it is written by the
     * integration worker as it assigns those ids; ingestion holds the vector before any id exists
     * for it. This method wires the pre-filter to the index orchestrator that owns that worker.</p>
     *
     * <h3>LSH Integration</h3>
     * <p>When LSH is configured:</p>
     * <ul>
     *   <li>Every vector written to a segment is also inserted into its LSH index</li>
     *   <li>Query Stage 1 names each query's bucket-mates, which Stage 2 measures beside the
     *       graph search</li>
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
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        try {
            created.setExecutor(requests);

            this.endpoints = EndpointRegistrar.register(created, wiring.endpointInputs());

            // Start the index orchestrator (including epoch tick scheduler) before accepting requests
            // This ensures the visibility epoch advances automatically without manual intervention
            // Set before the call rather than after it: a start that throws part-way has already
            // brought up the workers ahead of the one that failed, and they are as much in need
            // of stopping as those of a start that returned.
            orchestratorStarted = true;
            indexOrchestrator.start();
            LOGGER.info("Index orchestrator started - epoch tick scheduler running at {}ms interval",
                indexOrchestrator.getEpochTickScheduler().getTickIntervalMs());

            created.start();
        } catch (RuntimeException e) {
            // The listening socket is already bound at this point; leaving it - and a
            // started orchestrator - behind on a failed start leaks both, because stop()
            // does nothing until `running` is set.
            created.stop(0);
            requests.shutdown();
            if (orchestratorStarted) {
                stopOrchestratorAfterFailedStart(indexOrchestrator, e);
            }
            throw e;
        }

        server = created;
        requestExecutor = requests;
        running.set(true);
        logStarted();
    }

    /**
     * Starts the node without binding a listener, for a caller in the same process.
     *
     * <p>Everything {@link #start()} starts except the socket: the index orchestrator, whose
     * epoch tick is what makes a published document visible, starts here too. The node then
     * counts as running, so {@link #stop(ShutdownBudget)} drains the query pipeline, ingestion
     * and the orchestrator exactly as it does for a served node - there is simply no listener or
     * request pool for it to stop first. The security policy is not consulted: it governs a
     * listener, and there is none.</p>
     *
     * @return What the routes would have been built from
     * @throws IllegalStateException if the node is already running, or was assembled without
     *                               what an in-process caller needs
     */
    public synchronized InProcessServices startInProcess() {
        if (running.get()) {
            throw new IllegalStateException("Server is already running");
        }
        InProcessServices services = wiring.inProcessServices();

        IndexOrchestrator indexOrchestrator = wiring.indexOrchestrator();
        try {
            indexOrchestrator.start();
        } catch (RuntimeException e) {
            stopOrchestratorAfterFailedStart(indexOrchestrator, e);
            throw e;
        }
        running.set(true);
        LOGGER.info("ExcaliburDB {} on {} started in-process, with no listener",
            BuildInfo.version(), BuildInfo.jvm());
        return services;
    }

    /**
     * Takes down an orchestrator a failed {@link #start()} brought up, and records whether it
     * went.
     *
     * <p>A failed start never sets {@code running}, so {@link #stop(int)} returns without
     * draining anything and the flags it would have set keep their defaults - which say storage
     * is free. The shutdown that follows a failed start closes the storage engine and its arena
     * on that word. A worker that outlived its bound here is still reading segments mapped out
     * of that arena, and closing it under the worker ends the process rather than failing it;
     * so the answer {@link IndexOrchestrator#shutdown(Duration)} gives is what the flag is set
     * from, exactly as a drain's would be.</p>
     *
     * <p>Both waits come out of one default shutdown budget's share for drains, the share a
     * {@link #stop(int)} would have given the workers: the start has already failed, and a
     * worker that will not stop must not hold the failure back past what a stop would have
     * waited. {@link IndexOrchestrator#close(Duration)} still runs either way, for the executors
     * it releases: it cannot report an answer, which is why it is not what the answer is read
     * from. A failure of either is attached to the start's own failure rather than replacing it,
     * and leaves storage held, since nothing then says the workers ended.</p>
     *
     * @param indexOrchestrator The orchestrator the start brought up, possibly only in part
     * @param failure           Why the start failed, which is rethrown by the caller
     */
    private void stopOrchestratorAfterFailedStart(IndexOrchestrator indexOrchestrator,
                                                  RuntimeException failure) {
        ShutdownBudget budget = ShutdownBudget.startingNow(
            Duration.ofSeconds(ShutdownBudget.DEFAULT_TIMEOUT_SECONDS));
        boolean ended = false;
        try {
            ended = indexOrchestrator.shutdown(budget.forDrains());
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
        storageStillInUse = !ended;
        if (!ended) {
            LOGGER.error("The index orchestrator did not stop after a failed start; the storage "
                + "engine is left open for the work still running against it");
        }
        try {
            indexOrchestrator.close(budget.forDrains());
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
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
     * <p>The drains behind the listener share a budget of
     * {@link ShutdownBudget#DEFAULT_TIMEOUT_SECONDS}, started once the listener has stopped so
     * the grace given here does not eat into it. A caller with a deadline of its own to keep
     * uses {@link #stop(ShutdownBudget)}.</p>
     *
     * @param timeoutSeconds Maximum time to wait for in-flight requests (0 for immediate)
     */
    public void stop(int timeoutSeconds) {
        stop(timeoutSeconds, () -> ShutdownBudget.startingNow(
            Duration.ofSeconds(ShutdownBudget.DEFAULT_TIMEOUT_SECONDS)));
    }

    /**
     * Stops the API server within a shutdown's budget.
     *
     * <p>The listener is given {@link ShutdownBudget#listenerGraceSeconds()} for in-flight
     * exchanges, and every drain after it - the request threads the listener stopped waiting for
     * among them - is given what the budget leaves the drains, and no more. What has
     * not stopped by then is reported through {@link #isStorageStillInUse()} and its two
     * siblings, as a drain that timed out on its own always was, so the caller leaves it open.
     * The budget's reserve is left for the caller's durable writes.</p>
     *
     * @param budget The shutdown's deadline
     * @throws NullPointerException if budget is null
     */
    public void stop(ShutdownBudget budget) {
        Objects.requireNonNull(budget, "budget cannot be null");
        stop(budget.listenerGraceSeconds(), () -> budget);
    }

    /**
     * Stops the listener, then drains within the budget {@code drainBudget} supplies.
     *
     * @param timeoutSeconds The listener's grace for in-flight exchanges
     * @param drainBudget    The budget the drains run against, asked for once the listener has
     *                       stopped
     */
    private synchronized void stop(int timeoutSeconds, Supplier<ShutdownBudget> drainBudget) {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        LOGGER.info("Stopping ExcaliburDB API server...");

        // In use until a drain that returned says otherwise: anything below that throws leaves
        // unknown what is still running, and unknown has to read as running to the caller that
        // closes storage next.
        storageStillInUse = true;
        chunkIndexStillInUse = true;
        graphsStillInUse = true;

        // Order matters. Ask long requests to end, stop accepting, then drain everything that
        // can still be touching storage, and only then let the caller close the storage engine
        // and the shared arena. A background task still writing into a mapping whose arena has
        // been closed crashes the JVM rather than throwing.
        ShutdownSequence.askRequestsToStop(wiring);
        HttpServer current = server;
        if (current != null) {
            current.stop(timeoutSeconds);
            server = null;
        }

        ShutdownSequence.Drained drained = ShutdownSequence.drain(endpoints, wiring,
            requestExecutor, drainBudget.get());

        storageStillInUse = !drained.storageReleased();
        chunkIndexStillInUse = !drained.chunkIndexReleased();
        graphsStillInUse = !drained.graphsReleased();

        LOGGER.info("ExcaliburDB API server stopped");
    }

    /**
     * Whether the last stop left work running that still reads the storage engine.
     *
     * <p>The caller that owns storage asks this before closing it. True means something - a
     * compaction whose rewrite did not end when asked to - is still reading chunk text or
     * segment mappings, and closing the engine or releasing its arena underneath it can crash
     * the process; the safe course is to leave both open and let the process exit.</p>
     *
     * @return true if storage must not be closed yet
     */
    public boolean isStorageStillInUse() {
        return storageStillInUse;
    }

    /**
     * Whether the last stop left work running that still compacts or writes the chunk index.
     *
     * <p>The caller that owns the index asks this before releasing its files. It publishes the
     * index's buffer either way - that is safe beside a running compaction, and skipping it
     * would lose chunks storage already holds - and keeps the files open only when this is
     * true.</p>
     *
     * @return true if the chunk index's files must not be released yet
     */
    public boolean isChunkIndexStillInUse() {
        return chunkIndexStillInUse;
    }

    /**
     * Whether the last stop left work running that still builds into or reads the graph tenants.
     *
     * <p>The caller that owns the tenants asks this before closing them. True means a graph
     * build - or a request thread - outlived its drain, and closing the tenants would unmap
     * files and close Lucene directories it is still using.</p>
     *
     * @return true if the graph tenants must not be closed yet
     */
    public boolean isGraphsStillInUse() {
        return graphsStillInUse;
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
     * The URL the server is listening on: the scheme it serves and the address it is bound to.
     *
     * <p>Read from the bound socket rather than rebuilt from configuration, so it is the port
     * actually taken - an ephemeral one for port 0 - and the interface actually bound, and says
     * {@code https} exactly when the listener is a TLS one. A wildcard bind reports the wildcard
     * address, because that is what the node listens on; no single host name would be true of
     * it.</p>
     *
     * @return The base URL, without a trailing slash, or null if the server is not started
     */
    public String baseUrl() {
        HttpServer current = server;
        if (current == null) {
            return null;
        }
        return urlOf(current instanceof HttpsServer, current.getAddress());
    }

    /**
     * Where a probe on this host reaches the liveness route.
     *
     * <p>From the bound socket, like {@link #baseUrl()}, so it carries the scheme, the port and
     * the interface the listener really has. The one difference is a wildcard bind: the wildcard
     * is what the node listens on but not an address anything can connect to, so the loopback
     * address of the same family - on every interface the wildcard covers - stands in for it.
     * {@code /health} and not {@code /health/ready}: a process supervisor asks whether the
     * process is alive, and a drained or still-recovering node is alive; restarting it would
     * only lose the recovery it is part-way through.</p>
     *
     * @return The liveness URL, or null if the server is not started
     */
    public String livenessProbeUrl() {
        HttpServer current = server;
        if (current == null) {
            return null;
        }
        return probeUrlOf(current instanceof HttpsServer, current.getAddress());
    }

    /**
     * Spells the liveness URL a local probe uses for a listening address.
     *
     * @param tls     Whether the listener serves TLS
     * @param address The bound address
     * @return The {@code /health} URL, on loopback when the address is a wildcard
     */
    static String probeUrlOf(boolean tls, InetSocketAddress address) {
        InetAddress host = address.getAddress();
        InetSocketAddress reachable = host.isAnyLocalAddress()
            ? new InetSocketAddress(loopbackLike(host), address.getPort())
            : address;
        return urlOf(tls, reachable) + "/health";
    }

    /**
     * Returns the loopback address of the same family as a wildcard.
     *
     * @param wildcard {@code 0.0.0.0} or {@code ::}
     * @return {@code 127.0.0.1} or {@code ::1}
     */
    private static InetAddress loopbackLike(InetAddress wildcard) {
        byte[] loopback = new byte[wildcard.getAddress().length];
        if (wildcard instanceof Inet6Address) {
            loopback[loopback.length - 1] = 1;
        } else {
            loopback[0] = 127;
            loopback[loopback.length - 1] = 1;
        }
        try {
            return InetAddress.getByAddress(loopback);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException("A 4- or 16-byte address was refused", e);
        }
    }

    /**
     * Spells a listening address as a URL.
     *
     * @param tls     Whether the listener serves TLS
     * @param address The bound address
     * @return {@code scheme://host:port}, an IPv6 host in brackets so its colons are not read as
     *         the port separator
     */
    static String urlOf(boolean tls, InetSocketAddress address) {
        InetAddress host = address.getAddress();
        String literal = host.getHostAddress();
        String spelled = host instanceof Inet6Address ? "[" + literal + "]" : literal;
        return (tls ? "https" : "http") + "://" + spelled + ":" + address.getPort();
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

    /**
     * Reports what ingestion stages into the graph on {@code /v1/stats} and {@code /metrics}.
     *
     * <p>The sink is built by the ingestion wiring, which is the only thing that knows whether
     * this node stages facts at all, and handed to the ingestion orchestrator it feeds. Its
     * counts - documents seen, facts staged, and the three ways a document's facts fail to reach
     * the graph - were kept and read by nothing; this is how the stats side is given the same
     * sink rather than one of its own.</p>
     *
     * <p>Must be called before {@link #start()}; the stats collector is assembled there.</p>
     *
     * @param sink The sink ingestion stages through, or null on a node that stages nothing
     * @throws IllegalStateException if the server is already running
     */
    public void withGraphIngestSink(NamespaceRoutingGraphIngestSink sink) {
        requireNotStarted("the graph ingest sink");
        wiring.withGraphIngestSink(sink);
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

    /**
     * The graph namespace the documents written under a source id belong to.
     *
     * <p>Answers for crawled sources from their registration and for supplied documents from
     * the ids {@code POST /v1/documents} has taken, which a node only knows once it has
     * started.</p>
     *
     * @param sourceId The source id
     * @return Its namespace, or empty if this node holds nothing under that id
     */
    public Optional<NamespaceId> namespaceOfSource(String sourceId) {
        EndpointRegistrar.Endpoints registered = endpoints;
        return SourceNamespaceLookup.namespaceOf(wiring.ingestSourceRegistry(),
            registered == null ? null : registered.suppliedSources(), sourceId);
    }
}
