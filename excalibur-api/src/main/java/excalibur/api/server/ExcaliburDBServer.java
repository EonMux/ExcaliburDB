package excalibur.api.server;

import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import excalibur.api.endpoint.HealthEndpoint;
import excalibur.api.endpoint.StatsEndpoint;
import excalibur.api.endpoint.QueryEndpoint;
import excalibur.api.endpoint.QueryCancelEndpoint;
import excalibur.api.endpoint.IngestEndpoint;
import excalibur.api.endpoint.ReindexEndpoint;
import excalibur.api.endpoint.IngestStatusEndpoint;
import excalibur.api.endpoint.InMemorySourceRegistry;
import excalibur.api.endpoint.SourceRegistry;
import excalibur.api.reindex.ReindexCoordinator;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.api.security.IngestTargetValidator;
import excalibur.api.security.RequestGuard;
import excalibur.query.executor.QueryRegistry;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.lsh.LshIngestionService;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.shard.ReindexOperationHandler;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceResolver;
import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import excalibur.ingest.processor.TermFrequencyTracker;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import excalibur.shard.worker.SegmentReindexScheduler;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import io.excalibur.config.Config;
import io.excalibur.config.ConfigLoader;
import io.excalibur.config.ConfigValidation;
import io.excalibur.llm.client.LLMHttpClient;
import excalibur.llm.embedding.EmbeddingClient;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
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
    private final IndexOrchestrator indexOrchestrator;
    private final StorageEngine storageEngine;
    private final VolumeManager volumeManager;
    private final Config config;
    private QueryPipeline queryPipeline;

    /**
     * The graph-RAG path, or null where no graph store is configured.
     *
     * <p>Optional rather than required, so a deployment that only does vector search is unaffected
     * by its absence: the route is simply not registered, and an unregistered route already
     * answers 404 rather than failing at startup.
     */
    private io.excalibur.retrieval.pipeline.GraphRagPipeline graphPipeline;

    /** Every namespace's graph, when this node builds its own. */
    private excalibur.api.bootstrap.GraphTenants graphTenants;

    // Registries for ingestion endpoints
    /**
     * Where ingestion sources are registered.
     *
     * <p>Not final: its bound comes from the security policy, and {@link #withSecurity} can
     * replace that policy before the server starts. Rebuilding it there is what makes the
     * configured bound apply however the policy was supplied - it is always empty at that
     * point, because the method refuses once the server is running.</p>
     */
    private volatile SourceRegistry ingestSourceRegistry;
    private final ReindexEndpoint.ReindexStatusRegistry reindexStatusRegistry;
    private final IngestStatusEndpoint.IngestionStatusRegistry ingestionStatusRegistry;

    // Query registry for cancellation support
    private final QueryRegistry queryRegistry;

    // Optional reindexing components (may be null for stub mode)
    private DocumentReindexer documentReindexer;
    private SourceResolver sourceResolver;

    // Optional ingestion pipeline components (may be null for stub mode)
    private TextChunker textChunker;
    private LLMHttpClient llmClient;
    private EmbeddingClient embeddingClient;
    private IngestionOrchestrator ingestionOrchestrator;
    private DocumentVisibilityTracker documentVisibilityTracker;  // Shared with query pipeline for atomicity

    // HNSW configuration factory for creating graphs with proper config
    private final HnswConfigFactory hnswConfigFactory;

    /**
     * Security policy for the HTTP surface: bind address, bearer token, rate limits
     * and the ingest allow-lists. Never null.
     */
    private ApiSecurityConfig security;

    /**
     * The single mutual exclusion for reindexing, shared by {@code POST /v1/reindex}
     * and the scheduled reindex handler.
     */
    private final ReindexCoordinator reindexCoordinator = new ReindexCoordinator();

    // Endpoints that own executors and must be drained on stop
    private volatile QueryEndpoint queryEndpoint;
    private volatile ReindexEndpoint reindexEndpoint;

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
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        Objects.requireNonNull(queryPipeline, "QueryPipeline cannot be null");

        this.port = port;
        this.backlog = backlog;
        this.indexOrchestrator = indexOrchestrator;
        this.storageEngine = storageEngine;
        this.volumeManager = volumeManager;
        this.config = config;
        this.queryPipeline = queryPipeline;
        this.running = new AtomicBoolean(false);
        this.security = resolveSecurity(config);

        // Initialize registries for ingestion endpoints. Bounded by the configured limit rather
        // than by the compiled-in default: excalibur.ingest.max-sources was parsed, validated and
        // documented while reaching nothing, so setting it in either direction had no effect and
        // an authenticated caller could always register the default 256 - each a retained source,
        // and for an HTTP source a pinned client and its selector thread.
        this.ingestSourceRegistry = new InMemorySourceRegistry(security.maxRegisteredSources());
        this.reindexStatusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        this.ingestionStatusRegistry = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();

        // Initialize query registry for cancellation support
        this.queryRegistry = new QueryRegistry();

        // Initialize HNSW config factory from Config.Shard if available
        this.hnswConfigFactory = config != null
            ? HnswConfigFactory.fromShardConfig(config.shard())
            : HnswConfigFactory.withDefaults();

        LOGGER.debug("ExcaliburDBServer configured for port {} with VolumeManager: {}, Config: {}, HnswConfigFactory: efConstruction={}, efSearch={}",
            port, volumeManager != null ? "enabled" : "disabled", config != null ? "enabled" : "disabled",
            hnswConfigFactory.getEfConstruction(), hnswConfigFactory.getEfSearch());
    }

    /**
     * Resolves the security policy from the application configuration.
     *
     * <p>{@link Config} exposes typed sections only, so the API keys are read from the
     * same properties file and the same {@code EXCALIBUR_*} environment the rest of the
     * configuration comes from. With no configuration at all the safe default applies:
     * loopback binding, no authentication, both ingest allow-lists empty.</p>
     *
     * @param config The application configuration (may be null)
     * @return The resolved policy, never null
     */
    private static ApiSecurityConfig resolveSecurity(Config config) {
        if (config == null) {
            return ApiSecurityConfig.defaults();
        }
        return ApiSecurityConfig.fromProperties(config.getEffectiveProperties());
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
        if (running.get()) {
            throw new IllegalStateException("Cannot configure security after server has started");
        }
        this.security = Objects.requireNonNull(securityConfig, "securityConfig cannot be null");
        // The registry's bound comes from the policy, and the one built in the constructor was
        // sized by whatever policy applied then. The server is not running, so nothing has been
        // registered yet.
        this.ingestSourceRegistry = new InMemorySourceRegistry(security.maxRegisteredSources());
        LOGGER.info("API security policy configured: {}", securityConfig);
    }

    /**
     * Returns the ingestion orchestrator, once one has been wired.
     *
     * <p>For a caller that has to attach an optional collaborator to it after the pipeline is
     * built - the graph sink, which belongs to a stack assembled later than this server.</p>
     *
     * @return The orchestrator, or null when ingestion runs in stub mode
     */
    /**
     * Lexical retrieval over ingested chunks, attached by the application when it keeps an index.
     *
     * <p>Held here rather than passed to the constructor because the index is created from the
     * data directory, which the application owns, and because it is optional.</p>
     */
    private volatile excalibur.api.retrieval.LexicalQueryService lexicalQueryService;

    /**
     * The chunk lexical index, so {@code GET /v1/stats} can report what it owes.
     *
     * <p>Held beside the query service rather than read out of it: what the stats endpoint needs
     * is the index itself, and routing that through the thing that happens to search it would
     * make the report depend on retrieval being wired.</p>
     */
    private volatile excalibur.ingest.lexical.ChunkIndex chunkIndex;

    /** Reports this node's chunk lexical index on the stats endpoint. */
    public void withChunkIndex(excalibur.ingest.lexical.ChunkIndex chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    /**
     * The service behind {@code POST /v1/erasure}.
     *
     * <p>Without one the route is not registered at all, rather than registered and answering that
     * it cannot erase: a caller under an obligation should get a 404 they can escalate, not a 200
     * that removed nothing.</p>
     */
    private volatile excalibur.ingest.erasure.DocumentErasureService documentErasure;

    /** Enables {@code POST /v1/erasure} on this server. */
    public void withDocumentErasure(excalibur.ingest.erasure.DocumentErasureService service) {
        if (running.get()) {
            throw new IllegalStateException("Cannot configure erasure after server has started");
        }
        this.documentErasure = service;
    }

    /** Attaches lexical retrieval, before the server starts. */
    public void withLexicalQueryService(excalibur.api.retrieval.LexicalQueryService service) {
        this.lexicalQueryService = service;
    }

    public excalibur.ingest.pipeline.IngestionOrchestrator getIngestionOrchestrator() {
        return ingestionOrchestrator;
    }

    /**
     * Returns the security policy this server enforces.
     *
     * @return The security policy (never null)
     */
    public ApiSecurityConfig getSecurity() {
        return security;
    }

    /**
     * Returns the reindex coordinator shared by the reindex endpoint and the
     * scheduled reindex handler.
     *
     * @return The reindex coordinator (never null)
     */
    public ReindexCoordinator getReindexCoordinator() {
        return reindexCoordinator;
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates an ExcaliburDBServer with full configuration including Config.Shard wiring.
     *
     * <p>This factory method creates an IndexOrchestrator using the Config.Shard settings,
     * ensuring that shard configuration values are properly consumed by the system:</p>
     * <ul>
     *   <li>{@code hnswQueueLimit} → IndexOrchestrator's HNSW integration queue depth limit</li>
     *   <li>{@code efConstruction} → HnswConfig's construction candidate list size</li>
     *   <li>{@code efSearch} → HnswConfig's search candidate list size</li>
     *   <li>{@code compactDelayMs} → Informational for compaction timing</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Creates IndexOrchestrator via factory method that consumes Config.Shard</li>
     *   <li>All config values are validated by Config.Shard before being used</li>
     *   <li>VolumeManager is required for proper disk space tracking</li>
     * </ul>
     *
     * @param config The configuration object containing Shard settings
     * @param catalog The segment catalog for index orchestration
     * @param storageEngine The storage engine for data access
     * @param volumeManager The volume manager for disk space tracking
     * @param queryPipeline The query pipeline for executing queries
     * @return A new ExcaliburDBServer with Config.Shard properly wired
     * @throws NullPointerException if any required parameter is null
     */
    public static ExcaliburDBServer create(Config config,
                                           SegmentCatalog catalog,
                                           StorageEngine storageEngine,
                                           VolumeManager volumeManager,
                                           QueryPipeline queryPipeline) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(catalog, "catalog cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(queryPipeline, "queryPipeline cannot be null");

        // Create IndexOrchestrator from Config.Shard settings
        IndexOrchestrator orchestrator = IndexOrchestrator.fromConfig(
            catalog,
            storageEngine,
            volumeManager,
            config.shard()
        );

        LOGGER.info("Created ExcaliburDBServer with Config.Shard: efConstruction={}, efSearch={}, hnswQueueLimit={}, loadFactor={}",
            config.shard().getEfConstruction(),
            config.shard().getEfSearch(),
            config.shard().getHnswQueueLimit(),
            config.shard().getLoadFactor());

        return new ExcaliburDBServer(
            DEFAULT_PORT,
            DEFAULT_BACKLOG,
            orchestrator,
            storageEngine,
            volumeManager,
            config,
            queryPipeline
        );
    }

    /**
     * Creates an ExcaliburDBServer with custom port and Config.Shard wiring.
     *
     * <p>This is a convenience overload that allows specifying a custom port while
     * still wiring Config.Shard settings properly.</p>
     *
     * @param port The port to bind to (1-65535)
     * @param config The configuration object containing Shard settings
     * @param catalog The segment catalog for index orchestration
     * @param storageEngine The storage engine for data access
     * @param volumeManager The volume manager for disk space tracking
     * @param queryPipeline The query pipeline for executing queries
     * @return A new ExcaliburDBServer with Config.Shard properly wired
     * @throws NullPointerException if any required parameter is null
     * @throws IllegalArgumentException if port is out of range
     */
    public static ExcaliburDBServer create(int port,
                                           Config config,
                                           SegmentCatalog catalog,
                                           StorageEngine storageEngine,
                                           VolumeManager volumeManager,
                                           QueryPipeline queryPipeline) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(catalog, "catalog cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(queryPipeline, "queryPipeline cannot be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be in range 0-65535: " + port);
        }

        // Create IndexOrchestrator from Config.Shard settings
        IndexOrchestrator orchestrator = IndexOrchestrator.fromConfig(
            catalog,
            storageEngine,
            volumeManager,
            config.shard()
        );

        LOGGER.info("Created ExcaliburDBServer on port {} with Config.Shard: efConstruction={}, efSearch={}, hnswQueueLimit={}",
            port,
            config.shard().getEfConstruction(),
            config.shard().getEfSearch(),
            config.shard().getHnswQueueLimit());

        return new ExcaliburDBServer(
            port,
            DEFAULT_BACKLOG,
            orchestrator,
            storageEngine,
            volumeManager,
            config,
            queryPipeline
        );
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
    public void withLlmScorer(excalibur.query.pipeline.QueryPipeline.LlmScorer llmScorer) {
        if (running.get()) {
            throw new IllegalStateException("Cannot configure LLM scorer after server has started");
        }
        this.queryPipeline = queryPipeline.withLlmScorer(llmScorer);
        LOGGER.debug("LLM scorer configured: {}", llmScorer != null ? "enabled" : "disabled");
    }

    /**
     * Configures document atomicity based on the provided configuration.
     *
     * <p>Document atomicity ensures that a document's chunks are buffered and published
     * to the visibility epoch as a batch, so queries never see partially-ingested documents.
     * When disabled, chunks become visible immediately upon ingestion.</p>
     *
     * <h3>Configuration</h3>
     * <p>This method uses the {@code excalibur.ingest.document-atomicity} config key:
     * <ul>
     *   <li>{@code true} (default) - Document atomicity enabled, visibility checker is used</li>
     *   <li>{@code false} - Document atomicity disabled, visibility checker is null</li>
     * </ul>
     * </p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>If config is null, document atomicity defaults to enabled (true)</li>
     *   <li>Method must be called before {@link #start()} to take effect</li>
     *   <li>Creates a new QueryPipeline instance with appropriate visibility checker</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe and should only be called during initialization,
     * before starting the server.</p>
     *
     * @param documentVisibilityChecker The visibility checker to use when atomicity is enabled,
     *                                   or null to use config setting
     * @throws IllegalStateException if the server is already running
     */
    public void withDocumentAtomicity(DocumentVisibilityChecker documentVisibilityChecker) {
        if (running.get()) {
            throw new IllegalStateException("Cannot configure document atomicity after server has started");
        }

        // Determine if document atomicity should be enabled
        boolean atomicityEnabled = config != null
            ? config.ingest().isDocumentAtomicityEnabled()
            : true; // Default to enabled if no config

        if (atomicityEnabled && documentVisibilityChecker != null) {
            this.queryPipeline = queryPipeline.withVisibilityChecker(documentVisibilityChecker);
            LOGGER.info("Document atomicity enabled with DocumentVisibilityChecker");
        } else if (!atomicityEnabled) {
            // Disable document atomicity by setting visibility checker to null
            this.queryPipeline = queryPipeline.withVisibilityChecker(null);
            LOGGER.info("Document atomicity disabled via configuration");
        } else {
            LOGGER.debug("Document atomicity enabled but no DocumentVisibilityChecker provided");
        }
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
        if (running.get()) {
            throw new IllegalStateException("Cannot configure reindexer after server has started");
        }
        if ((documentReindexer == null) != (sourceResolver == null)) {
            throw new IllegalArgumentException(
                "DocumentReindexer and SourceResolver must both be null or both be non-null");
        }
        this.documentReindexer = documentReindexer;
        this.sourceResolver = sourceResolver;

        // Wire the reindexer to the scheduler for automatic scheduled reindexing
        if (documentReindexer != null && sourceResolver != null) {
            indexOrchestrator.setReindexOperationHandler(
                sourceId -> runScheduledReindex(sourceId, documentReindexer, sourceResolver));
        } else {
            // Clear the handler if reindexer is being disabled
            indexOrchestrator.setReindexOperationHandler(null);
        }

        LOGGER.info("Document reindexer configured: {}", documentReindexer != null ? "enabled" : "disabled (stub mode)");
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
        return sourceId -> {
            Source source = ingestSourceRegistry.get(sourceId);
            return source;
        };
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
        if (running.get()) {
            throw new IllegalStateException("Cannot configure reindexer after server has started");
        }
        Objects.requireNonNull(documentReindexer, "documentReindexer cannot be null");
        this.documentReindexer = documentReindexer;
        this.sourceResolver = createSourceResolver();

        // Wire the handler to enable scheduled reindex operations
        SourceResolver resolver = this.sourceResolver;
        this.indexOrchestrator.setReindexOperationHandler(
            sourceId -> runScheduledReindex(sourceId, documentReindexer, resolver));

        LOGGER.info("Document reindexer configured with automatic source resolution and scheduled reindex support");
    }

    /**
     * Executes one scheduled reindex under the shared reindex claim.
     *
     * <p>The scheduler and {@code POST /v1/reindex} are two independent triggers for
     * the same operation. Both take the same per-source claim from
     * {@link ReindexCoordinator}, because two rolling replacements of one source run
     * concurrently will each tombstone the segment the other has just published.</p>
     *
     * @param sourceId          The source to reindex
     * @param documentReindexer The reindexer to run
     * @param sourceResolver    Resolver for the source instance
     * @return true when the reindex made progress
     */
    private boolean runScheduledReindex(String sourceId,
                                        DocumentReindexer documentReindexer,
                                        SourceResolver sourceResolver) {
        ReindexCoordinator.Lease lease = reindexCoordinator.tryAcquire(sourceId);
        if (lease == null) {
            LOGGER.info("Scheduled reindex skipped: source {} is already being reindexed", sourceId);
            return false;
        }
        try {
            Source source = sourceResolver.resolve(sourceId);
            if (source == null) {
                LOGGER.warn("ReindexOperationHandler: Source not found: {}", sourceId);
                return false;
            }

            // Use rolling replacement if available
            boolean useRollingReplacement = volumeManager != null
                && storageEngine.getCatalog() != null
                && documentReindexer.hasRollingReplacement();

            if (useRollingReplacement) {
                // Select a volume for the new segment
                java.nio.file.Path volumePath = volumeManager.getVolumeWithMostSpace();
                if (volumePath == null) {
                    LOGGER.warn("ReindexOperationHandler: No volume available for source: {}", sourceId);
                    return false;
                }
                DocumentReindexer.RollingReindexResult result =
                    documentReindexer.reindexWithRollingReplacement(source, volumePath);
                // Success if no errors OR if some documents were indexed (partial success)
                return !result.hasErrors() || result.getDocumentsIndexed() > 0;
            }

            // Fall back to standard reindex
            DocumentReindexer.ReindexResult result = documentReindexer.reindexAll(source);
            // Success if no errors OR if some documents were indexed (partial success)
            return !result.hasErrors() || result.getDocumentsIndexed() > 0;
        } catch (Exception e) {
            LOGGER.error("ReindexOperationHandler failed for source {}: {}", sourceId, e.getMessage());
            return false;
        } finally {
            lease.release();
        }
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
        if (running.get()) {
            throw new IllegalStateException("Cannot configure ingestion pipeline after server has started");
        }
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        Objects.requireNonNull(llmClient, "LLMHttpClient cannot be null");

        this.textChunker = textChunker;
        this.llmClient = llmClient;

        // Create dedicated EmbeddingClient from LLMHttpClient's config
        this.embeddingClient = new EmbeddingClient(llmClient.getConfig());

        // Read document timeout from config, defaulting to 2000ms if not configured
        int documentTimeoutMs = config != null
            ? config.ingest().getDocumentTimeoutMs()
            : IngestionOrchestrator.DEFAULT_DOCUMENT_TIMEOUT_MS;

        // Document visibility on the node's own epoch clock -- the same counter the query
        // pipeline captures in stage 0. Sharing it is what makes the atomicity filter a
        // snapshot bound rather than a completeness check; a tracker with a private counter
        // would produce epochs the query has no way to compare against.
        this.documentVisibilityTracker = new DocumentVisibilityTracker(
            new DocumentRegistry(), indexOrchestrator.getVisibilityTracker());

        // Create term frequency tracker for selectivity estimation
        // This enables query optimization by tracking term frequencies in segment sketches
        TermFrequencyTracker termFrequencyTracker = new TermFrequencyTracker();

        // Create the ingestion orchestrator with status registry adapter and visibility tracker
        this.ingestionOrchestrator = new IngestionOrchestrator(
            textChunker,
            embeddingClient,
            storageEngine,
            indexOrchestrator,
            new IngestionOrchestrator.StatusRegistry() {
                @Override
                public IngestionOrchestrator.IngestionStatus getOrCreate(String sourceId) {
                    final IngestStatusEndpoint.IngestionStatus delegate =
                        ingestionStatusRegistry.getOrCreate(sourceId);
                    return new IngestionOrchestrator.IngestionStatus() {
                        @Override
                        public void update(String state, double progress,
                                           long entriesIngested, long entriesPending) {
                            delegate.update(state, progress, entriesIngested, entriesPending);
                        }

                        @Override
                        public void setError(String error) {
                            delegate.setError(error);
                        }
                    };
                }
            },
            // No document chunk buffer. Document visibility does not depend on it -- publishDocument
            // falls through to registerFromMetadata -- but it means the WAL mirror, and so the
            // tail-scan hand-off that makes a chunk queryable before HNSW integration catches up,
            // is not active. Wiring it needs a write log this buffer can own (it appends and
            // assumes exclusive use), and the node has no such log today: every WriteLog here
            // belongs to a segment. That is a lifecycle decision, not a call-site one.
            null,
            documentVisibilityTracker,  // Wire visibility tracker for document atomicity
            termFrequencyTracker,  // Term frequency tracker for selectivity estimation
            documentTimeoutMs
        );

        // Wire the visibility tracker to the query pipeline for atomicity filtering
        // This enables DocumentAtomicityFilter to exclude partial documents from query results
        if (this.queryPipeline.getVisibilityChecker() == null) {
            this.queryPipeline = queryPipeline.withVisibilityChecker(
                new DocumentVisibilityTrackerAdapter(documentVisibilityTracker)
            );
            LOGGER.info("Query pipeline wired with document visibility checker for atomicity filtering");
        }

        LOGGER.info("Ingestion pipeline configured with TextChunker, EmbeddingClient, TermFrequencyTracker, documentTimeoutMs={}ms, documentAtomicity=enabled",
            documentTimeoutMs);
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
        if (running.get()) {
            throw new IllegalStateException("Cannot configure ingestion pipeline after server has started");
        }
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        Objects.requireNonNull(embeddingClient, "EmbeddingClient cannot be null");

        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.documentVisibilityTracker = visibilityTracker;

        // Read document timeout from config, defaulting to 2000ms if not configured
        int documentTimeoutMs = config != null
            ? config.ingest().getDocumentTimeoutMs()
            : IngestionOrchestrator.DEFAULT_DOCUMENT_TIMEOUT_MS;

        // Create term frequency tracker for selectivity estimation
        // This enables query optimization by tracking term frequencies in segment sketches
        TermFrequencyTracker termFrequencyTracker = new TermFrequencyTracker();

        // Create the ingestion orchestrator with status registry adapter and visibility tracker
        this.ingestionOrchestrator = new IngestionOrchestrator(
            textChunker,
            embeddingClient,
            storageEngine,
            indexOrchestrator,
            new IngestionOrchestrator.StatusRegistry() {
                @Override
                public IngestionOrchestrator.IngestionStatus getOrCreate(String sourceId) {
                    final IngestStatusEndpoint.IngestionStatus delegate =
                        ingestionStatusRegistry.getOrCreate(sourceId);
                    return new IngestionOrchestrator.IngestionStatus() {
                        @Override
                        public void update(String state, double progress,
                                           long entriesIngested, long entriesPending) {
                            delegate.update(state, progress, entriesIngested, entriesPending);
                        }

                        @Override
                        public void setError(String error) {
                            delegate.setError(error);
                        }
                    };
                }
            },
            // No document chunk buffer. Document visibility does not depend on it -- publishDocument
            // falls through to registerFromMetadata -- but it means the WAL mirror, and so the
            // tail-scan hand-off that makes a chunk queryable before HNSW integration catches up,
            // is not active. Wiring it needs a write log this buffer can own (it appends and
            // assumes exclusive use), and the node has no such log today: every WriteLog here
            // belongs to a segment. That is a lifecycle decision, not a call-site one.
            null,
            visibilityTracker,  // Use provided visibility tracker (may be null)
            termFrequencyTracker,  // Term frequency tracker for selectivity estimation
            documentTimeoutMs
        );

        // Wire the visibility tracker to the query pipeline for atomicity filtering
        if (visibilityTracker != null && this.queryPipeline.getVisibilityChecker() == null) {
            this.queryPipeline = queryPipeline.withVisibilityChecker(
                new DocumentVisibilityTrackerAdapter(visibilityTracker)
            );
            LOGGER.info("Query pipeline wired with shared document visibility checker for atomicity filtering");
        }

        LOGGER.info("Ingestion pipeline configured with TextChunker, EmbeddingClient, TermFrequencyTracker, documentTimeoutMs={}ms, documentAtomicity={}",
            documentTimeoutMs, visibilityTracker != null ? "enabled" : "disabled");
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
     * @param embeddingClient The embedding client for generating embeddings
     * @param lshIngestionService The LSH ingestion service for vector indexing (may be null to disable LSH)
     * @param visibilityTracker The document visibility tracker for atomic visibility (may be null)
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if textChunker or embeddingClient is null
     */
    public void withIngestionPipeline(TextChunker textChunker, EmbeddingClient embeddingClient,
                                      LshIngestionService lshIngestionService,
                                      DocumentVisibilityTracker visibilityTracker) {
        if (running.get()) {
            throw new IllegalStateException("Cannot configure ingestion pipeline after server has started");
        }
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        // embeddingClient may be null: that is lexical-only ingestion, where documents are
        // chunked, stored and term-indexed but never embedded. Vector search is then the one
        // retrieval signal this node cannot offer, rather than a reason to index nothing.

        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.documentVisibilityTracker = visibilityTracker;

        // Read document timeout from config, defaulting to 2000ms if not configured
        int documentTimeoutMs = config != null
            ? config.ingest().getDocumentTimeoutMs()
            : IngestionOrchestrator.DEFAULT_DOCUMENT_TIMEOUT_MS;

        // Create term frequency tracker for selectivity estimation
        // This enables query optimization by tracking term frequencies in segment sketches
        TermFrequencyTracker termFrequencyTracker = new TermFrequencyTracker();

        // Create the ingestion orchestrator
        this.ingestionOrchestrator = new IngestionOrchestrator(
            textChunker,
            embeddingClient,
            storageEngine,
            indexOrchestrator,
            new IngestionOrchestrator.StatusRegistry() {
                @Override
                public IngestionOrchestrator.IngestionStatus getOrCreate(String sourceId) {
                    final IngestStatusEndpoint.IngestionStatus delegate =
                        ingestionStatusRegistry.getOrCreate(sourceId);
                    return new IngestionOrchestrator.IngestionStatus() {
                        @Override
                        public void update(String state, double progress,
                                           long entriesIngested, long entriesPending) {
                            delegate.update(state, progress, entriesIngested, entriesPending);
                        }

                        @Override
                        public void setError(String error) {
                            delegate.setError(error);
                        }
                    };
                }
            },
            // No document chunk buffer. Document visibility does not depend on it -- publishDocument
            // falls through to registerFromMetadata -- but it means the WAL mirror, and so the
            // tail-scan hand-off that makes a chunk queryable before HNSW integration catches up,
            // is not active. Wiring it needs a write log this buffer can own (it appends and
            // assumes exclusive use), and the node has no such log today: every WriteLog here
            // belongs to a segment. That is a lifecycle decision, not a call-site one.
            null,
            visibilityTracker,
            termFrequencyTracker,  // Term frequency tracker for selectivity estimation
            documentTimeoutMs
        );

        // The vector pre-filter is keyed by HNSW node id, so it is written by the integration
        // worker as it assigns those ids rather than by ingestion, which holds the vector before
        // any id exists for it. Wiring it here keeps that decision in one place for every caller.
        indexOrchestrator.setVectorPrefilter(lshIngestionService);

        // The engine owns when a pre-filter exists, because that has to follow the graph it is
        // keyed against: attached to created, recovered and split segments alike, and discarded
        // from a split child before it can adopt the one describing its parent. This runs after
        // start(), so the engine back-fills the segments that already exist.
        storageEngine.setVectorPrefilter(lshIngestionService);

        // Wire the visibility tracker to the query pipeline for atomicity filtering
        if (visibilityTracker != null && this.queryPipeline.getVisibilityChecker() == null) {
            this.queryPipeline = queryPipeline.withVisibilityChecker(
                new DocumentVisibilityTrackerAdapter(visibilityTracker)
            );
            LOGGER.info("Query pipeline wired with shared document visibility checker for atomicity filtering");
        }

        LOGGER.info("Ingestion pipeline configured: TextChunker, EmbeddingClient, "
            + "TermFrequencyTracker, documentTimeoutMs={}ms, documentAtomicity={}, vectorPreFilter={}",
            documentTimeoutMs, visibilityTracker != null ? "enabled" : "disabled",
            lshIngestionService != null ? "enabled" : "disabled");
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
        security.requireSafeToServe();

        // Built before the bind, not after: reading the keystore, locating the key entry and
        // reducing the protocol and suite lists to what this JVM supports can all fail, and a
        // failure after HttpsServer.create leaves a listening socket behind that stop() will
        // not clean up, because stop() does nothing until `running` is set.
        HttpsConfigurator tls = null;
        if (security.tls().isEnabled()) {
            try {
                tls = security.tls().configurator();
            } catch (GeneralSecurityException e) {
                throw new IOException("TLS is configured but could not be initialised: "
                    + e.getMessage(), e);
            }
        }

        InetSocketAddress bindTo = new InetSocketAddress(security.bindInetAddress(), port);
        LOGGER.info("Starting ExcaliburDB API server on {}://{}:{}",
            tls != null ? "https" : "http", security.bindAddress(), port);

        HttpServer created;
        if (tls != null) {
            HttpsServer https = HttpsServer.create(bindTo, backlog);
            https.setHttpsConfigurator(tls);
            created = https;
        } else {
            created = HttpServer.create(bindTo, backlog);
        }
        boolean orchestratorStarted = false;
        try {
            created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            registerEndpoints(created);

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

        LOGGER.info("ExcaliburDB API server started successfully on port {} ({})",
            port, security.tls().isEnabled() ? "TLS" : "cleartext");
        LOGGER.info("Endpoints available:");
        LOGGER.info("  GET    /health           - Health check");
        LOGGER.info("  GET    /v1/stats         - System statistics");
        LOGGER.info("  POST   /v1/query         - Query execution");
        LOGGER.info("  DELETE /v1/query/{id}    - Cancel in-progress query");
        LOGGER.info("  POST   /v1/ingest        - Register ingestion source");
        LOGGER.info("  POST   /v1/reindex       - Force reindex");
        LOGGER.info("  GET    /v1/ingest/status - Ingestion status");
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

        ReindexEndpoint reindex = reindexEndpoint;
        if (reindex != null) {
            reindex.close();
        }

        QueryEndpoint query = queryEndpoint;
        if (query != null) {
            query.shutdown();
        }

        IngestionOrchestrator ingestion = ingestionOrchestrator;
        if (ingestion != null && !ingestion.shutdown()) {
            LOGGER.warn("Ingestion threads did not finish before shutdown timeout");
        }

        // The registry owns the sources it published; nothing else may close them.
        try {
            ingestSourceRegistry.close();
        } catch (Exception e) {
            LOGGER.warn("Error closing ingestion sources: {}", e.getMessage());
        }

        // Stop the index orchestrator after the HTTP server stops
        // This ensures no new queries are being accepted before stopping workers
        indexOrchestrator.close();
        LOGGER.info("Index orchestrator stopped");

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
     * Returns the HNSW configuration factory for creating graphs with proper config.
     *
     * <p>This factory creates {@link io.excalibur.vector.hnsw.HnswConfig} instances
     * that are configured with the values from the Config.Shard settings:</p>
     * <ul>
     *   <li>{@code efConstruction} - Size of candidate list during graph construction</li>
     *   <li>{@code efSearch} - Size of candidate list during search</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>If Config was provided at construction, uses Config.Shard values</li>
     *   <li>If no Config was provided, uses default values (efConstruction=128, efSearch=64)</li>
     *   <li>All values are validated by Config.Shard before being used</li>
     * </ul>
     *
     * @return The HNSW configuration factory (never null)
     */
    public HnswConfigFactory getHnswConfigFactory() {
        return hnswConfigFactory;
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
        return indexOrchestrator;
    }

    /**
     * Returns the document visibility tracker for atomic document-level visibility.
     *
     * <p>The visibility tracker ensures that:</p>
     * <ul>
     *   <li>Documents become visible atomically (all chunks at once)</li>
     *   <li>Queries never see partially-ingested documents</li>
     *   <li>Visibility is epoch-based for consistent query snapshots</li>
     * </ul>
     *
     * @return The document visibility tracker, or null if not configured
     */
    public DocumentVisibilityTracker getDocumentVisibilityTracker() {
        return documentVisibilityTracker;
    }

    /**
     * Registers all API endpoints on the given server.
     *
     * @param target The HTTP server to register contexts on
     */
    /**
     * Attaches the graph-RAG pipeline, enabling {@code POST /v1/graph/query}.
     *
     * <p>Must be called before {@link #start()}; endpoints are registered there, so a pipeline
     * supplied afterwards would be silently ignored. Rejecting it late is better than accepting it
     * and serving 404.
     */
    public ExcaliburDBServer withGraphPipeline(
            io.excalibur.retrieval.pipeline.GraphRagPipeline pipeline) {
        if (isRunning()) {
            throw new IllegalStateException(
                "graph pipeline must be attached before start(); endpoints are registered there");
        }
        this.graphPipeline = pipeline;
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
     * @param holder       Holds the stack currently being served
     * @param buildService Builds a new stack from staged facts and installs it
     * @return this server, for chaining
     */
    public ExcaliburDBServer withGraphSupport(excalibur.api.bootstrap.GraphTenants tenants) {
        if (isRunning()) {
            throw new IllegalStateException(
                "graph support must be attached before start(); endpoints are registered there");
        }
        this.graphTenants = Objects.requireNonNull(tenants, "tenants cannot be null");
        return this;
    }

    private void registerEndpoints(HttpServer target) {
        RequestGuard guard = new RequestGuard(security);

        // Determine health config values from config or use defaults
        double readinessThreshold = config != null
            ? config.health().getReadinessThreshold()
            : HealthEndpoint.DEFAULT_READINESS_THRESHOLD;
        double healthyThreshold = config != null
            ? config.health().getHealthyThreshold()
            : HealthEndpoint.DEFAULT_HEALTHY_THRESHOLD;
        int maxLatencyMs = config != null
            ? config.health().getMaxLatencyMs()
            : HealthEndpoint.DEFAULT_MAX_LATENCY_MS;

        // Core endpoints - use full health config for HealthEndpoint
        HealthEndpoint healthEndpoint = new HealthEndpoint(
            indexOrchestrator, readinessThreshold, healthyThreshold, maxLatencyMs);
        StatsEndpoint statsEndpoint = new StatsEndpoint(indexOrchestrator, storageEngine, volumeManager);
        statsEndpoint.withLexicalIndex(this.chunkIndex);

        // QueryEndpoint - pass config for defaults, queryRegistry for cancellation,
        // and embeddingClient so clients can submit raw query text (recommended path)
        // instead of computing embeddings client-side.
        QueryEndpoint queryHandler = new QueryEndpoint(
            queryPipeline, config, null, queryRegistry, embeddingClient, security);
        // Lexical retrieval, when this node keeps a chunk index. With no embedding client it is
        // what /v1/query answers from instead of refusing; with one it is a second signal.
        queryHandler.setLexicalQueryService(this.lexicalQueryService);
        this.queryEndpoint = queryHandler;

        // QueryCancelEndpoint for user-initiated query cancellation
        // Note: This endpoint is registered at the same path as QueryEndpoint
        // and handles DELETE requests while QueryEndpoint handles POST
        QueryCancelEndpoint queryCancelEndpoint = new QueryCancelEndpoint(queryRegistry);

        // Ingestion endpoints - pass both status registry and orchestrator
        // This ensures status entries are created when sources are registered,
        // and background ingestion updates progress when orchestrator is configured
        IngestEndpoint ingestEndpoint = new IngestEndpoint(
            ingestSourceRegistry,
            ingestionStatusRegistry,
            ingestionOrchestrator,
            new IngestTargetValidator(security)
        );
        LOGGER.info("IngestEndpoint configured with status registry (orchestrator: {})",
            ingestionOrchestrator != null ? "enabled" : "disabled (stub mode)");

        // Wire the reindex scheduler to the ingest source registry for periodic reindexing
        // When sources are registered via /v1/ingest, they will be automatically
        // registered with the scheduler for periodic reindexing at the configured interval
        if (ingestSourceRegistry instanceof InMemorySourceRegistry) {
            InMemorySourceRegistry inMemRegistry =
                (InMemorySourceRegistry) ingestSourceRegistry;
            long reindexIntervalMs = config != null
                ? config.ingest().getReindexIntervalMs()
                : SegmentReindexScheduler.DEFAULT_REINDEX_INTERVAL_MS;
            inMemRegistry.setReindexScheduler(
                indexOrchestrator.getReindexScheduler(),
                reindexIntervalMs
            );
            LOGGER.info("IngestSourceRegistry wired to ReindexScheduler with interval={}ms", reindexIntervalMs);
        }

        // Create ReindexEndpoint with shared source registry for consistency
        // If documentReindexer is configured, actual reindexing will occur
        // Auto-create sourceResolver if not explicitly set
        // Pass VolumeManager and SegmentCatalog for zero-downtime rolling replacement
        ReindexEndpoint reindexHandler;
        if (documentReindexer != null) {
            // Auto-create sourceResolver if not explicitly set
            SourceResolver resolver = (sourceResolver != null)
                ? sourceResolver
                : createSourceResolver();
            reindexHandler = new ReindexEndpoint(
                new SourceRegistryAdapter(ingestSourceRegistry),
                reindexStatusRegistry,
                documentReindexer,
                resolver,
                volumeManager,               // For selecting volumes for new segments
                storageEngine.getCatalog(),  // For segment lifecycle management
                reindexCoordinator           // Shared with the scheduled reindex handler
            );
            boolean hasRollingReplacement = volumeManager != null && storageEngine.getCatalog() != null;
            LOGGER.info("ReindexEndpoint configured with DocumentReindexer for actual reindex operations, rolling replacement: {}",
                hasRollingReplacement ? "enabled" : "disabled");
        } else {
            reindexHandler = new ReindexEndpoint(
                new SourceRegistryAdapter(ingestSourceRegistry),
                reindexStatusRegistry
            );
            LOGGER.info("ReindexEndpoint configured without a DocumentReindexer; "
                + "POST /v1/reindex will answer 503 until one is wired");
        }
        this.reindexEndpoint = reindexHandler;

        IngestStatusEndpoint ingestStatusEndpoint = new IngestStatusEndpoint(ingestionStatusRegistry);

        // Register endpoints. Every path goes through the guard: /health is the one
        // unauthenticated route, because a liveness probe cannot carry a token, and it
        // is still rate limited.
        target.createContext("/health",
            wrapHandler(guard.protect(RequestGuard.Access.PUBLIC, healthEndpoint)));
        target.createContext("/v1/stats",
            wrapHandler(guard.protect(RequestGuard.Access.READ, statsEndpoint)));
        // Create a composite handler for /v1/query that routes based on HTTP method
        target.createContext("/v1/query", createQueryMethodRouter(
            guard.protect(RequestGuard.Access.READ, queryHandler),
            guard.protect(RequestGuard.Access.MUTATE, queryCancelEndpoint)));
        // POST registers a source, DELETE stops crawling one. Both mutate, and both are charged
        // against the mutate budget; the same shape /v1/query uses for query and cancel.
        target.createContext("/v1/ingest", createMethodRouter("POST",
            guard.protect(RequestGuard.Access.MUTATE, ingestEndpoint),
            "DELETE",
            guard.protect(RequestGuard.Access.MUTATE,
                new excalibur.api.endpoint.SourceDeregisterEndpoint(ingestSourceRegistry)),
            "Method Not Allowed - Use POST to register a source or DELETE to stop crawling one"));
        target.createContext("/v1/reindex",
            wrapHandler(guard.protect(RequestGuard.Access.MUTATE, reindexHandler)));
        target.createContext("/v1/ingest/status",
            wrapHandler(guard.protect(RequestGuard.Access.READ, ingestStatusEndpoint)));
        // A mutation, and the most consequential one: it removes content rather than adding it.
        if (documentErasure != null) {
            target.createContext("/v1/erasure", wrapHandler(guard.protect(
                RequestGuard.Access.MUTATE,
                new excalibur.api.endpoint.DocumentErasureEndpoint(
                    documentErasure, ingestSourceRegistry))));
        }

        // Registered only when a graph store is configured. A read, not a mutation: it walks the
        // graph and returns context, and charging it against the smaller mutate budget would
        // throttle ordinary questions.
        if (graphTenants != null) {
            target.createContext("/v1/graph/query", wrapHandler(guard.protect(
                RequestGuard.Access.READ,
                new excalibur.api.endpoint.GraphQueryEndpoint(graphTenants))));
            // Both mutations: they rewrite the served graph.
            target.createContext("/v1/graph/build", wrapHandler(guard.protect(
                RequestGuard.Access.MUTATE,
                new excalibur.api.endpoint.GraphBuildEndpoint(graphTenants))));
            target.createContext("/v1/graph/erasure", wrapHandler(guard.protect(
                RequestGuard.Access.MUTATE,
                new excalibur.api.endpoint.GraphErasureEndpoint(graphTenants))));
        } else if (graphPipeline != null) {
            target.createContext("/v1/graph/query", wrapHandler(guard.protect(
                RequestGuard.Access.READ,
                new excalibur.api.endpoint.GraphQueryEndpoint(graphPipeline))));
        }

        target.createContext("/",
            wrapHandler(guard.protect(RequestGuard.Access.PUBLIC, this::handleNotFound)));

        LOGGER.info("Registered endpoints with authentication {}: /health (public), /v1/stats, "
                + "/v1/query (POST=query, DELETE=cancel), "
                + "/v1/ingest (POST=register, DELETE=stop crawling), /v1/reindex, "
                + "/v1/ingest/status{}{}",
            security.isAuthenticationRequired() ? "required" : "disabled",
            documentErasure != null ? ", /v1/erasure" : "",
            graphTenants != null
                ? ", /v1/graph/query, /v1/graph/build, /v1/graph/erasure"
                : (graphPipeline != null ? ", /v1/graph/query" : ""));
    }

    /**
     * Adapts SourceRegistry to ReindexEndpoint.SourceRegistry interface.
     *
     * <p>This adapter bridges the two registry interfaces, ensuring that sources
     * registered via /v1/ingest are visible to /v1/reindex for consistency.</p>
     */
    private static class SourceRegistryAdapter implements ReindexEndpoint.SourceRegistry {
        private final SourceRegistry delegate;

        SourceRegistryAdapter(SourceRegistry delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public boolean isRegistered(String sourceId) {
            return delegate.isRegistered(sourceId);
        }

        @Override
        public excalibur.ingest.source.Source get(String sourceId) {
            return delegate.get(sourceId);
        }
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
     * Creates a method-based router for /v1/query endpoint.
     *
     * <p>Routes requests based on HTTP method:</p>
     * <ul>
     *   <li>POST - Query execution (delegates to QueryEndpoint)</li>
     *   <li>DELETE - Query cancellation (delegates to QueryCancelEndpoint)</li>
     * </ul>
     *
     * @param queryEndpoint The handler for POST requests
     * @param cancelEndpoint The handler for DELETE requests
     * @return A composite handler that routes based on HTTP method
     */
    /**
     * Routes one context by request method.
     *
     * <p>Two operations on one noun rather than two nouns: registering a source and stopping one
     * are the same resource, and inventing a second path for the second verb is what HTTP methods
     * exist to avoid.</p>
     */
    private HttpHandler createMethodRouter(String firstMethod, HttpHandler first,
                                           String secondMethod, HttpHandler second,
                                           String otherwise) {
        return exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            long start = System.currentTimeMillis();
            try {
                LOGGER.debug("Request: {} {}", method, path);
                if (firstMethod.equalsIgnoreCase(method)) {
                    first.handle(exchange);
                } else if (secondMethod.equalsIgnoreCase(method)) {
                    second.handle(exchange);
                } else {
                    sendError(exchange, 405, otherwise);
                }
                LOGGER.debug("Response: {} {} - {}ms", method, path,
                    System.currentTimeMillis() - start);
            } catch (Exception e) {
                LOGGER.error("Error handling {} {}: {}", method, path, e.getMessage(), e);
                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    sendError(exchange, 500, "Internal Server Error");
                }
            } finally {
                exchange.close();
            }
        };
    }

    private HttpHandler createQueryMethodRouter(HttpHandler queryEndpoint, HttpHandler cancelEndpoint) {
        return exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            long start = System.currentTimeMillis();

            try {
                LOGGER.debug("Request: {} {} from {}", method, path,
                    exchange.getRemoteAddress().getAddress().getHostAddress());

                // Route based on method
                if ("POST".equalsIgnoreCase(method)) {
                    queryEndpoint.handle(exchange);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    cancelEndpoint.handle(exchange);
                } else {
                    // Return method not allowed for other methods
                    sendError(exchange, 405, "Method Not Allowed - Use POST for queries or DELETE for cancellation");
                }

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
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
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
        return ingestSourceRegistry;
    }
}
