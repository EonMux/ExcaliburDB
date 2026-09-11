package excalibur.api.server;

import excalibur.api.bootstrap.DurableEpochFloor;
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
import excalibur.api.bootstrap.SourceRestoration;
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
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Where registered ingest sources are written down, so they outlive the process.
     *
     * <p>Optional. Without one the registry keeps its sources in memory alone, and a restart
     * leaves the node holding an indexed corpus that nothing re-crawls - which is the state
     * every {@code /v1/ingest} caller was told it had avoided.</p>
     */
    private volatile excalibur.ingest.registry.SourceCatalog sourceCatalog;

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
    public void withSourceCatalog(excalibur.ingest.registry.SourceCatalog catalog) {
        if (running.get()) {
            throw new IllegalStateException("Cannot configure the source catalog after server has started");
        }
        this.sourceCatalog = catalog;
    }

    /** Attaches lexical retrieval, before the server starts. */
    public void withLexicalQueryService(excalibur.api.retrieval.LexicalQueryService service) {
        this.lexicalQueryService = service;
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

        // Create IndexOrchestrator from Config.Shard settings, on one visibility clock with
        // the engine and resumed above the epochs this node's durable records already carry.
        IndexOrchestrator orchestrator = IndexOrchestrator.fromConfig(
            catalog,
            storageEngine,
            volumeManager,
            config.shard(),
            DurableEpochFloor.resumeEpoch(storageEngine.dataDir(), catalog)
        );
        storageEngine.setVisibilityTracker(orchestrator.getVisibilityTracker());

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

        // Create IndexOrchestrator from Config.Shard settings, on one visibility clock with
        // the engine and resumed above the epochs this node's durable records already carry.
        IndexOrchestrator orchestrator = IndexOrchestrator.fromConfig(
            catalog,
            storageEngine,
            volumeManager,
            config.shard(),
            DurableEpochFloor.resumeEpoch(storageEngine.dataDir(), catalog)
        );
        storageEngine.setVisibilityTracker(orchestrator.getVisibilityTracker());

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
     * Whether a document's chunks become visible to queries together.
     *
     * <p>On, the atomicity filter is installed on the query pipeline and a query never sees
     * half of a document being ingested. Off, chunks become visible as they land, which is
     * cheaper and is what {@code excalibur.ingest.document-atomicity=false} asks for.</p>
     *
     * <p>A server built without a {@link Config} has it on: atomicity is the safe reading, and
     * the absence of configuration is not a request to drop a correctness guarantee.</p>
     *
     * @return true when the filter should run
     */
    boolean isDocumentAtomicityEnabled() {
        return config == null || config.ingest().isDocumentAtomicityEnabled();
    }

    /**
     * Installs the atomicity filter on the query pipeline, unless it is turned off or already
     * installed.
     *
     * <p>Every path that configures an ingestion pipeline calls this, so the knob is honoured
     * in one place rather than in three that can drift.</p>
     *
     * @param tracker The tracker the filter reads, or null when this node has none
     */
    private void wireDocumentAtomicity(DocumentVisibilityTracker tracker) {
        if (tracker == null || this.queryPipeline.getVisibilityChecker() != null) {
            return;
        }
        if (!isDocumentAtomicityEnabled()) {
            LOGGER.info("Document atomicity disabled by excalibur.ingest.document-atomicity; "
                + "a query may see a document that is still being ingested");
            return;
        }
        this.queryPipeline = queryPipeline.withVisibilityChecker(
            new DocumentVisibilityTrackerAdapter(tracker));
        LOGGER.info("Query pipeline wired with document visibility checker for atomicity filtering");
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
            documentVisibilityTracker,  // Wire visibility tracker for document atomicity
            termFrequencyTracker,  // Term frequency tracker for selectivity estimation
            documentTimeoutMs
        );

        wireDocumentAtomicity(documentVisibilityTracker);

        LOGGER.info("Ingestion pipeline configured with TextChunker, EmbeddingClient, TermFrequencyTracker, documentTimeoutMs={}ms, documentAtomicity={}",
            documentTimeoutMs, isDocumentAtomicityEnabled() ? "enabled" : "disabled");
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
            visibilityTracker,  // Use provided visibility tracker (may be null)
            termFrequencyTracker,  // Term frequency tracker for selectivity estimation
            documentTimeoutMs
        );

        wireDocumentAtomicity(visibilityTracker);

        LOGGER.info("Ingestion pipeline configured with TextChunker, EmbeddingClient, TermFrequencyTracker, documentTimeoutMs={}ms, documentAtomicity={}",
            documentTimeoutMs,
            visibilityTracker != null && isDocumentAtomicityEnabled() ? "enabled" : "disabled");
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

        wireDocumentAtomicity(visibilityTracker);

        LOGGER.info("Ingestion pipeline configured: TextChunker, TermFrequencyTracker, "
            + "embedding={}, documentTimeoutMs={}ms, documentAtomicity={}, vectorPreFilter={}",
            embeddingClient != null ? "enabled" : "disabled",
            documentTimeoutMs,
            visibilityTracker != null && isDocumentAtomicityEnabled() ? "enabled" : "disabled",
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

        // getPort(), not the configured port: a node started on 0 binds an ephemeral port, and
        // telling the operator it came up "on port 0" withholds the one fact they need to reach it.
        LOGGER.info("ExcaliburDB API server started successfully on port {} ({})",
            getPort(), security.tls().isEnabled() ? "TLS" : "cleartext");
        LOGGER.info("Endpoints available:");
        for (String line : endpointBanner(documentErasure != null, graphTenants != null,
                graphTenants == null && graphPipeline != null)) {
            LOGGER.info("{}", line);
        }
    }

    /**
     * The routes this node serves, as an operator reads them at startup.
     *
     * <p>Derived from the same three conditions {@link #registerEndpoints(HttpServer)} branches
     * on, and package-private so a test can hold the two against each other. The banner is the
     * only inventory of the HTTP surface an operator is given, so one that quietly omits a
     * registered route is worse than no banner at all.</p>
     *
     * @param erasureWired    Whether {@code /v1/erasure} is registered
     * @param graphTenanted   Whether the full tenant-backed graph surface is registered
     * @param graphQueryOnly  Whether only {@code /v1/graph/query} is registered, from a pipeline
     *                        wired without tenants
     * @return One line per route, aligned on the description
     */
    static List<String> endpointBanner(boolean erasureWired, boolean graphTenanted,
                                       boolean graphQueryOnly) {
        List<String> lines = new ArrayList<>(13);
        lines.add(route("GET", "/health", "Liveness and readiness (unauthenticated)"));
        lines.add(route("GET", "/v1/stats", "System statistics"));
        lines.add(route("POST", "/v1/query", "Query execution"));
        lines.add(route("DELETE", "/v1/query/{query_id}", "Cancel an in-progress query"));
        lines.add(route("POST", "/v1/ingest", "Register an ingestion source"));
        lines.add(route("DELETE", "/v1/ingest", "Stop crawling a source"));
        lines.add(route("GET", "/v1/ingest/status", "Ingestion status per source"));
        lines.add(route("POST", "/v1/reindex", "Force a reindex"));
        lines.add(route("GET", "/v1/reindex/status", "Reindex status per source"));
        if (erasureWired) {
            lines.add(route("POST", "/v1/erasure", "Remove named documents from the corpus"));
        }
        if (graphTenanted || graphQueryOnly) {
            lines.add(route("POST", "/v1/graph/query", "Answer a question from the graph"));
        }
        if (graphTenanted) {
            lines.add(route("POST", "/v1/graph/build", "Publish the staged facts as the graph"));
            lines.add(route("POST", "/v1/graph/erasure", "Record an obligation to erase"));
        }
        return List.copyOf(lines);
    }

    /** Widths chosen for the longest method and the longest path above. */
    private static String route(String method, String path, String description) {
        return String.format("  %-6s %-20s - %s", method, path, description);
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
     * @param tenants Every namespace's graph: what it serves, what it has staged, what it owes
     * @return this server, for chaining
     * @throws IllegalStateException if the server is already running
     * @throws NullPointerException if tenants is null
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
        // Lexical retrieval, when this node keeps a chunk index. On a node with no embedding
        // client it is what /v1/query answers from instead of refusing the query; on a node
        // that has one it is the second signal, fused with vector search by rank. Both name
        // the same thing - the chunk id ingestion assigned - which is what makes them fusible
        // rather than two id spaces to reconcile.
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
            // So that deregistering a source forgets what its reindex runs did, the way it
            // already forgets what its ingestion did. /v1/reindex/status serves this registry,
            // and without the wiring it keeps answering for sources that are gone.
            inMemRegistry.setReindexStatusRegistry(reindexStatusRegistry);
            LOGGER.info("IngestSourceRegistry wired to ReindexScheduler with interval={}ms", reindexIntervalMs);

            // The catalog is attached before its records are read back, so a source restored here
            // stays recorded rather than being re-written on the way in.
            if (sourceCatalog != null) {
                inMemRegistry.setSourceCatalog(sourceCatalog);
                SourceRestoration.restore(
                    sourceCatalog,
                    new excalibur.api.endpoint.SourceFactory(new IngestTargetValidator(security)),
                    inMemRegistry
                );
            }
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
        // Every context below is pinned to its own path. HttpServer matches on the longest
        // prefix, so without this a path that merely begins with a route's name is served by
        // that route: /healthz reads back as healthy and /v1/statsfoo returns the statistics.
        HttpHandler notFound = guard.protect(RequestGuard.Access.PUBLIC, this::handleNotFound);
        target.createContext("/health",
            wrapHandler(exactPath("/health",
                guard.protect(RequestGuard.Access.PUBLIC, healthEndpoint), notFound)));
        target.createContext("/v1/stats",
            wrapHandler(exactPath("/v1/stats",
                guard.protect(RequestGuard.Access.READ, statsEndpoint), notFound)));
        // POST runs a query, DELETE cancels one. Any other method is answered by a handler of its
        // own, so the 405 is charged against the read budget rather than served below the guard.
        // POST is the query itself and takes no sub-path; DELETE carries the query id in one,
        // so the context serves its own path and its children and each method is pinned within.
        target.createContext("/v1/query", wrapHandler(pathOrChild("/v1/query", createMethodRouter(
            "POST", exactPath("/v1/query",
                guard.protect(RequestGuard.Access.READ, queryHandler), notFound),
            "DELETE", guard.protect(RequestGuard.Access.MUTATE, queryCancelEndpoint),
            exactPath("/v1/query", guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use POST for queries or DELETE for cancellation")),
                notFound)), notFound)));
        // POST registers a source, DELETE stops crawling one. Both mutate, and both are charged
        // against the mutate budget; the same shape /v1/query uses for query and cancel.
        target.createContext("/v1/ingest", wrapHandler(exactPath("/v1/ingest", createMethodRouter(
            "POST", guard.protect(RequestGuard.Access.MUTATE, ingestEndpoint),
            "DELETE", guard.protect(RequestGuard.Access.MUTATE,
                new excalibur.api.endpoint.SourceDeregisterEndpoint(ingestSourceRegistry)),
            guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use POST to register a source "
                    + "or DELETE to stop crawling one"))), notFound)));
        target.createContext("/v1/reindex",
            wrapHandler(exactPath("/v1/reindex",
                guard.protect(RequestGuard.Access.MUTATE, reindexHandler), notFound)));
        target.createContext("/v1/ingest/status",
            wrapHandler(exactPath("/v1/ingest/status",
                guard.protect(RequestGuard.Access.READ, ingestStatusEndpoint), notFound)));
        // The readback for /v1/reindex, which answers 202 and works in the background. Without
        // it the run's verdict reached the node's log and nothing else. A longer path than
        // /v1/reindex, so the server's longest-prefix matching routes it here rather than to
        // the reindex handler, which answers 405 to a GET.
        target.createContext("/v1/reindex/status",
            wrapHandler(exactPath("/v1/reindex/status", guard.protect(RequestGuard.Access.READ,
                new excalibur.api.endpoint.ReindexStatusEndpoint(reindexStatusRegistry)),
                notFound)));
        // A mutation, and the most consequential one: it removes content rather than adding it.
        if (documentErasure != null) {
            target.createContext("/v1/erasure", wrapHandler(exactPath("/v1/erasure",
                guard.protect(RequestGuard.Access.MUTATE,
                    new excalibur.api.endpoint.DocumentErasureEndpoint(
                        documentErasure, ingestSourceRegistry)), notFound)));
        }

        // Registered only when a graph store is configured. A read, not a mutation: it walks the
        // graph and returns context, and charging it against the smaller mutate budget would
        // throttle ordinary questions.
        if (graphTenants != null) {
            target.createContext("/v1/graph/query", wrapHandler(exactPath("/v1/graph/query",
                guard.protect(RequestGuard.Access.READ,
                    new excalibur.api.endpoint.GraphQueryEndpoint(graphTenants)), notFound)));
            // Both mutations: they rewrite the served graph.
            target.createContext("/v1/graph/build", wrapHandler(exactPath("/v1/graph/build",
                guard.protect(RequestGuard.Access.MUTATE,
                    new excalibur.api.endpoint.GraphBuildEndpoint(graphTenants)), notFound)));
            target.createContext("/v1/graph/erasure", wrapHandler(exactPath("/v1/graph/erasure",
                guard.protect(RequestGuard.Access.MUTATE,
                    new excalibur.api.endpoint.GraphErasureEndpoint(graphTenants)), notFound)));
        } else if (graphPipeline != null) {
            target.createContext("/v1/graph/query", wrapHandler(exactPath("/v1/graph/query",
                guard.protect(RequestGuard.Access.READ,
                    new excalibur.api.endpoint.GraphQueryEndpoint(graphPipeline)), notFound)));
        }

        target.createContext("/", wrapHandler(notFound));

        LOGGER.info("Registered endpoints with authentication {}: /health (public), /v1/stats, "
                + "/v1/query (POST=query, DELETE=cancel), "
                + "/v1/ingest (POST=register, DELETE=stop crawling), /v1/reindex, "
                + "/v1/ingest/status, /v1/reindex/status{}{}",
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
     * Serves a context only for the exact path it was registered under.
     *
     * <p>{@code HttpServer} dispatches on the longest matching <em>prefix</em>, so a context at
     * {@code /v1/stats} also receives {@code /v1/statsfoo}, and {@code /health} receives
     * {@code /healthz}. A client with a typo in its URL would be answered as though it had
     * none - a liveness probe pointed at the wrong path reading back "healthy" is the worst
     * case, and the documented contract is that anything this server does not serve answers
     * 404. One trailing slash is the same path, because that is what a browser and most HTTP
     * clients produce for a bare route.</p>
     *
     * @param context  The path this context is registered under
     * @param delegate What serves the path itself
     * @param notFound What answers every other path reaching this context
     * @return A handler that answers 404 for every other path under the context
     */
    private static HttpHandler exactPath(String context, HttpHandler delegate,
                                         HttpHandler notFound) {
        return exchange -> {
            if (isExactly(context, exchange.getRequestURI().getPath())) {
                delegate.handle(exchange);
            } else {
                notFound.handle(exchange);
            }
        };
    }

    /**
     * Serves a context for its own path and for the paths directly beneath it.
     *
     * <p>For {@code /v1/query}, whose cancel route carries the query id in the path. Anything
     * deeper than one segment, or under a longer prefix that merely starts the same way, is
     * still 404.</p>
     *
     * @param context  The path this context is registered under
     * @param delegate What serves the path and its children
     * @param notFound What answers every other path reaching this context
     * @return A handler that answers 404 for paths outside the context
     */
    private static HttpHandler pathOrChild(String context, HttpHandler delegate,
                                           HttpHandler notFound) {
        return exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (isExactly(context, path) || path.startsWith(context + "/")) {
                delegate.handle(exchange);
            } else {
                notFound.handle(exchange);
            }
        };
    }

    /** Whether a request path is a context's own path, with or without one trailing slash. */
    private static boolean isExactly(String context, String path) {
        return context.equals(path) || (context + "/").equals(path);
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
     * Routes one context by request method.
     *
     * <p>Two operations on one noun rather than two nouns: registering a source and stopping one
     * are the same resource, and inventing a second path for the second verb is what HTTP methods
     * exist to avoid.</p>
     *
     * <p>Every branch is a whole handler, the fall-through included, so each one carries its own
     * {@link RequestGuard} decision. A router that wrote the 405 itself would answer it below the
     * guard: an anonymous caller could then spend a path's worth of work per request, at line rate,
     * on a method the route does not serve. Logging, error handling and closing the exchange are
     * {@link #wrapHandler}'s, as they are for every other context.</p>
     *
     * @param firstMethod  The method the first handler serves
     * @param first        The handler for {@code firstMethod}
     * @param secondMethod The method the second handler serves
     * @param second       The handler for {@code secondMethod}
     * @param otherwise    The handler for every other method
     * @return A handler that dispatches on the request method
     */
    private HttpHandler createMethodRouter(String firstMethod, HttpHandler first,
                                           String secondMethod, HttpHandler second,
                                           HttpHandler otherwise) {
        return exchange -> {
            String method = exchange.getRequestMethod();
            if (firstMethod.equalsIgnoreCase(method)) {
                first.handle(exchange);
            } else if (secondMethod.equalsIgnoreCase(method)) {
                second.handle(exchange);
            } else {
                otherwise.handle(exchange);
            }
        };
    }

    /**
     * A handler that answers 405 and nothing else.
     *
     * @param message The reason, naming the methods the route does serve
     * @return A handler for the methods a route does not serve
     */
    private HttpHandler methodNotAllowed(String message) {
        return exchange -> sendError(exchange, 405, message);
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
