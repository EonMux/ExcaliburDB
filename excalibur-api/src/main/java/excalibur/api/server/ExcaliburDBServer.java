package excalibur.api.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.endpoint.HealthEndpoint;
import excalibur.api.endpoint.StatsEndpoint;
import excalibur.api.endpoint.QueryEndpoint;
import excalibur.api.endpoint.QueryCancelEndpoint;
import excalibur.api.endpoint.IngestEndpoint;
import excalibur.api.endpoint.ReindexEndpoint;
import excalibur.api.endpoint.IngestStatusEndpoint;
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
    private final VolumeManager volumeManager;
    private final Config config;
    private QueryPipeline queryPipeline;

    // Registries for ingestion endpoints
    private final IngestEndpoint.SourceRegistry ingestSourceRegistry;
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

        // Initialize registries for ingestion endpoints
        this.ingestSourceRegistry = new IngestEndpoint.InMemorySourceRegistry();
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
            indexOrchestrator.setReindexOperationHandler(sourceId -> {
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
                    } else {
                        // Fall back to standard reindex
                        DocumentReindexer.ReindexResult result = documentReindexer.reindexAll(source);
                        // Success if no errors OR if some documents were indexed (partial success)
                        return !result.hasErrors() || result.getDocumentsIndexed() > 0;
                    }
                } catch (Exception e) {
                    LOGGER.error("ReindexOperationHandler failed for source {}: {}", sourceId, e.getMessage());
                    return false;
                }
            });
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
     * resolves sources from the internal {@link IngestEndpoint.InMemorySourceRegistry},
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
     *   <li>SourceResolver delegates to the shared IngestEndpoint.SourceRegistry</li>
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
        this.indexOrchestrator.setReindexOperationHandler(sourceId -> {
            try {
                Source source = this.sourceResolver.resolve(sourceId);
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
                } else {
                    // Fall back to standard reindex
                    DocumentReindexer.ReindexResult result = documentReindexer.reindexAll(source);
                    // Success if no errors OR if some documents were indexed (partial success)
                    return !result.hasErrors() || result.getDocumentsIndexed() > 0;
                }
            } catch (Exception e) {
                LOGGER.error("ReindexOperationHandler failed for source {}: {}", sourceId, e.getMessage());
                return false;
            }
        });

        LOGGER.info("Document reindexer configured with automatic source resolution and scheduled reindex support");
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

        // Create document visibility tracker for atomic document-level visibility
        // This ensures queries never see partially-ingested documents
        this.documentVisibilityTracker = new DocumentVisibilityTracker(new DocumentRegistry());

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
            null,  // lshIngestionService - not configured here
            null,  // documentChunkBuffer - not configured here
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
            null,  // lshIngestionService - not configured here
            null,  // documentChunkBuffer - not configured here
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
     * Configures the ingestion pipeline with LSH support for approximate nearest neighbor pre-filtering.
     *
     * <p>This overload creates the full ingestion pipeline with LSH index population during document
     * ingestion. Vectors inserted into segments are also indexed in the segment's LSH index for
     * efficient Stage 2 pre-filtering during queries.</p>
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

        // Create the ingestion orchestrator with LSH support
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
            lshIngestionService,  // Pass LSH ingestion service for vector indexing
            null,  // documentChunkBuffer - not configured here
            visibilityTracker,
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

        LOGGER.info("Ingestion pipeline configured with LSH support: TextChunker, EmbeddingClient, LshIngestionService, TermFrequencyTracker, documentTimeoutMs={}ms, documentAtomicity={}",
            documentTimeoutMs, visibilityTracker != null ? "enabled" : "disabled");
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

        // Start the index orchestrator (including epoch tick scheduler) before accepting requests
        // This ensures the visibility epoch advances automatically without manual intervention
        indexOrchestrator.start();
        LOGGER.info("Index orchestrator started - epoch tick scheduler running at {}ms interval",
            indexOrchestrator.getEpochTickScheduler().getTickIntervalMs());

        server.start();
        running.set(true);

        LOGGER.info("ExcaliburDB API server started successfully on port {}", port);
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

        if (server != null) {
            server.stop(timeoutSeconds);
            server = null;
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
        if (server == null) {
            return -1;
        }
        return server.getAddress().getPort();
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
     * Registers all API endpoints.
     */
    private void registerEndpoints() {
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

        // QueryEndpoint - pass config for include_partial_segments default and queryRegistry for cancellation
        QueryEndpoint queryEndpoint = new QueryEndpoint(queryPipeline, config, null, queryRegistry);

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
            ingestionOrchestrator
        );
        LOGGER.info("IngestEndpoint configured with status registry (orchestrator: {})",
            ingestionOrchestrator != null ? "enabled" : "disabled (stub mode)");

        // Wire the reindex scheduler to the ingest source registry for periodic reindexing
        // When sources are registered via /v1/ingest, they will be automatically
        // registered with the scheduler for periodic reindexing at the configured interval
        if (ingestSourceRegistry instanceof IngestEndpoint.InMemorySourceRegistry) {
            IngestEndpoint.InMemorySourceRegistry inMemRegistry =
                (IngestEndpoint.InMemorySourceRegistry) ingestSourceRegistry;
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
        ReindexEndpoint reindexEndpoint;
        if (documentReindexer != null) {
            // Auto-create sourceResolver if not explicitly set
            SourceResolver resolver = (sourceResolver != null)
                ? sourceResolver
                : createSourceResolver();
            reindexEndpoint = new ReindexEndpoint(
                new SourceRegistryAdapter(ingestSourceRegistry),
                reindexStatusRegistry,
                documentReindexer,
                resolver,
                volumeManager,              // For selecting volumes for new segments
                storageEngine.getCatalog()  // For segment lifecycle management
            );
            boolean hasRollingReplacement = volumeManager != null && storageEngine.getCatalog() != null;
            LOGGER.info("ReindexEndpoint configured with DocumentReindexer for actual reindex operations, rolling replacement: {}",
                hasRollingReplacement ? "enabled" : "disabled");
        } else {
            reindexEndpoint = new ReindexEndpoint(
                new SourceRegistryAdapter(ingestSourceRegistry),
                reindexStatusRegistry
            );
            LOGGER.info("ReindexEndpoint configured in stub mode (no DocumentReindexer)");
        }

        IngestStatusEndpoint ingestStatusEndpoint = new IngestStatusEndpoint(ingestionStatusRegistry);

        // Register endpoints
        server.createContext("/health", wrapHandler(healthEndpoint));
        server.createContext("/v1/stats", wrapHandler(statsEndpoint));
        // Create a composite handler for /v1/query that routes based on HTTP method
        server.createContext("/v1/query", createQueryMethodRouter(queryEndpoint, queryCancelEndpoint));
        server.createContext("/v1/ingest", wrapHandler(ingestEndpoint));
        server.createContext("/v1/reindex", wrapHandler(reindexEndpoint));
        server.createContext("/v1/ingest/status", wrapHandler(ingestStatusEndpoint));

        server.createContext("/", this::handleNotFound);

        LOGGER.debug("Registered endpoints: /health, /v1/stats, /v1/query (POST=query, DELETE=cancel), /v1/ingest, /v1/reindex, /v1/ingest/status");
    }

    /**
     * Adapts IngestEndpoint.SourceRegistry to ReindexEndpoint.SourceRegistry interface.
     *
     * <p>This adapter bridges the two registry interfaces, ensuring that sources
     * registered via /v1/ingest are visible to /v1/reindex for consistency.</p>
     */
    private static class SourceRegistryAdapter implements ReindexEndpoint.SourceRegistry {
        private final IngestEndpoint.SourceRegistry delegate;

        SourceRegistryAdapter(IngestEndpoint.SourceRegistry delegate) {
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
