package excalibur.api;

import excalibur.api.bootstrap.CoreComponents;
import excalibur.api.bootstrap.GraphRagFactory;
import excalibur.api.bootstrap.ReindexerFactory;
import excalibur.api.bootstrap.ShutdownSequence;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.api.server.ExcaliburDBServer;
import excalibur.api.server.ListenPort;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.ingest.lsh.LshIngestionService;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import excalibur.llm.client.LLMClientConfig;
import excalibur.llm.embedding.EmbeddingClient;
import io.excalibur.llm.client.LLMHttpClient;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.config.Config;
import io.excalibur.config.ConfigLoader;
import io.excalibur.config.ConfigValidation;
import io.excalibur.metrics.Metrics;
import io.excalibur.metrics.MetricsRegistry;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for ExcaliburDB server.
 *
 * <p>ExcaliburApplication bootstraps all components required for the ExcaliburDB
 * server and starts the HTTP API. It handles:</p>
 * <ul>
 *   <li>Configuration loading from properties file and environment variables</li>
 *   <li>Storage engine initialization with memory-mapped segments</li>
 *   <li>Index orchestrator setup for background workers</li>
 *   <li>Query pipeline configuration for search operations</li>
 *   <li>HTTP server startup on the configured port</li>
 *   <li>Graceful shutdown handling via shutdown hook</li>
 * </ul>
 *
 * <h2>Startup Command</h2>
 * <pre>{@code
 * java -jar excalibur.jar --config excalibur.properties
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <p>Configuration is loaded from the properties file specified via --config,
 * with environment variable overrides. See {@link Config} for available options.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. It should only be instantiated once
 * as the main application entry point.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 * @see ExcaliburDBServer
 * @see Config
 */
public final class ExcaliburApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcaliburApplication.class);

    /**
     * Default configuration file path.
     */
    public static final String DEFAULT_CONFIG_PATH = "excalibur.properties";

    /**
     * Default data directory for storage.
     */
    public static final String DEFAULT_DATA_DIR = "data";

    /**
     * Shutdown timeout in seconds.
     */
    public static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final Config config;
    private final Arena arena;
    private final StorageEngine storageEngine;
    private final IndexOrchestrator indexOrchestrator;

    /**
     * The chunk index, once the ingestion pipeline has opened one.
     *
     * <p>Held so shutdown can close it. Closing publishes whatever is still buffered, which is the
     * difference between a clean stop and one that leaves the tail of a run in storage and out of
     * every query.</p>
     */
    private volatile excalibur.ingest.lexical.ChunkIndex chunkIndex;
    /**
     * The graph-RAG stack, or null where no graph store is on disk.
     *
     * <p>Optional: a deployment doing only vector search has no graph, and the
     * {@code /v1/graph/query} route is simply not registered. Held here rather than
     * only inside the server because its mappings and Lucene readers have to be
     * released on shutdown.
     */
    private final GraphRagFactory.GraphRag graphRag;

    /** Every namespace's graph: what it has staged, what it serves, and what it owes. */
    private final excalibur.api.bootstrap.GraphTenants graphTenants;
    private final VolumeManager volumeManager;
    private final QueryPipeline queryPipeline;
    private final ExcaliburDBServer server;
    private final int port;
    private final HnswConfigFactory hnswConfigFactory;

    /**
     * Security policy for the HTTP surface, resolved once from the properties file and
     * the environment and validated before anything binds.
     */
    private final ApiSecurityConfig apiSecurity;

    /**
     * Hierarchical Cuckoo filter for corpus → cluster → segment membership testing.
     * Shared between ingestion (inserts) and maintenance worker (deletions).
     */
    private final HierarchicalCuckooFilter hierarchicalFilter;

    /**
     * Document visibility tracker for atomic document-level visibility.
     * Tracks when documents become fully visible to queries, ensuring no partial
     * documents appear in query results.
     */
    private final DocumentVisibilityTracker documentVisibilityTracker;

    /**
     * Adapter that exposes DocumentVisibilityTracker as DocumentVisibilityChecker.
     * Passed to the query pipeline for document atomicity filtering.
     */
    private final DocumentVisibilityChecker documentVisibilityChecker;

    /**
     * Metrics registry for JMX MBean management.
     * All JMX MBeans are registered through this registry for observability.
     */
    private final MetricsRegistry metricsRegistry;

    private volatile boolean shutdownRequested = false;

    /** Where segments, indexes and the lexical index live. */
    private Path dataDirectory;

    /**
     * Main entry point.
     *
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        try {
            // Parse command-line arguments
            String configPath = parseConfigPath(args);

            // Create and run the application
            ExcaliburApplication app = new ExcaliburApplication(configPath);
            app.run();

        } catch (Exception e) {
            LOGGER.error("Failed to start ExcaliburDB: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Parses the --config argument from command-line arguments.
     *
     * @param args Command-line arguments
     * @return The configuration file path, or default if not specified
     */
    private static String parseConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i]) || "-c".equals(args[i])) {
                return args[i + 1];
            }
        }
        return DEFAULT_CONFIG_PATH;
    }

    /**
     * Creates a new ExcaliburApplication with the specified configuration.
     *
     * @param configPath Path to the configuration file
     * @throws IOException if configuration cannot be loaded or initialization fails
     */
    public ExcaliburApplication(String configPath) throws IOException {
        LOGGER.info("Starting ExcaliburDB...");

        // Load configuration
        this.config = loadConfiguration(configPath);

        // Initialize metrics registry and register JMX MBeans
        // This enables observability for query latency, queue depth, coverage, etc.
        this.metricsRegistry = new MetricsRegistry();
        try {
            Metrics.registerAll(metricsRegistry);
            LOGGER.info("JMX MBeans registered successfully");
        } catch (Exception e) {
            LOGGER.warn("Failed to register JMX MBeans: {}. Metrics will not be available via JMX.", e.getMessage());
        }
        // Initialize health score and coverage to sensible defaults
        Metrics.setHealthScore(0.5);
        Metrics.setEstimatedCoverage(0.0);
        Metrics.recordEpoch(0);

        // Initialize HNSW config factory from Config.Shard settings
        // This ensures property file values (efConstruction, efSearch) are used for HNSW graphs
        this.hnswConfigFactory = HnswConfigFactory.fromShardConfig(config.shard());
        LOGGER.info("HNSW config factory initialized: efConstruction={}, efSearch={}",
            hnswConfigFactory.getEfConstruction(), hnswConfigFactory.getEfSearch());

        // Determine server port from config or use default
        this.port = determinePort();

        // Resolve and validate the API security policy before any component starts.
        // A non-loopback bind without a token fails here rather than after the socket
        // is already accepting requests.
        // Read straight off Config, which already merged the file with the EXCALIBUR_* environment.
        // Re-reading the file here would be a second parser that can disagree with that one about
        // precedence -- and the setting it most affects is the auth token.
        this.apiSecurity = ApiSecurityConfig.fromProperties(config.getEffectiveProperties());
        this.apiSecurity.requireSafeToServe();
        LOGGER.info("API security policy: {}", apiSecurity);

        // Initialize data directory
        Path dataDir = initializeDataDirectory();
        this.dataDirectory = dataDir;

        // Build the storage stack: arena, storage engine with its HNSW initializer
        // installed before start(), volume manager, index orchestrator and the
        // hierarchical Cuckoo filter.
        CoreComponents core = CoreComponents.create(config, dataDir, hnswConfigFactory);
        this.arena = core.arena();
        this.storageEngine = core.storageEngine();
        this.volumeManager = core.volumeManager();
        this.indexOrchestrator = core.indexOrchestrator();
        this.hierarchicalFilter = core.hierarchicalFilter();

        // Document visibility, on the node's own epoch clock. Built here rather than earlier
        // because it needs the index orchestrator's visibility tracker: publishing a document
        // on the same counter the query pipeline captures from is what lets the atomicity
        // filter bound by the query's snapshot instead of only checking completeness.
        // Persisted, because the registry is what says which documents this node holds and under
        // which chunk ids. Lost on restart, every document reads as new to change detection - so a
        // re-crawl reingests the whole corpus and tombstones none of the old chunks - and
        // POST /v1/erasure can no longer name the chunks of anything ingested before the restart.
        this.documentVisibilityTracker = new DocumentVisibilityTracker(
            excalibur.api.bootstrap.DocumentRegistryFactory.open(dataDir),
            indexOrchestrator.getVisibilityTracker());
        this.documentVisibilityChecker =
            new DocumentVisibilityTrackerAdapter(documentVisibilityTracker);
        LOGGER.info("Document visibility tracker initialized on the node epoch clock");

        // Initialize query pipeline
        this.queryPipeline = createQueryPipeline();

        // Open the graph-RAG stack, if one was built into the data directory. A graph that is
        // present but unreadable fails the start: the alternative is a node that silently
        // answers 404 on a documented endpoint.
        this.graphRag = openGraphRag(dataDir);

        // The build side of the graph, per namespace. A node that never sees the namespace
        // header runs with exactly one, called shared, which is the single-tenant arrangement.
        this.graphTenants = openGraphTenants(dataDir);

        // Create HTTP server
        this.server = createServer();

        // Configure the document reindexer. This runs after the server field is
        // assigned: it wires the reindexer into the server, and doing it earlier
        // dereferences a field that is still null.
        configureReindexer();

        LOGGER.info("ExcaliburApplication initialized successfully");
    }

    /**
     * Loads configuration from the specified path.
     *
     * <p>This method loads the configuration and validates all values against
     * their allowed ranges. If validation fails, an IllegalStateException is
     * thrown and the application will not start.</p>
     *
     * @param configPath Path to configuration file
     * @return Loaded configuration
     * @throws IOException if file cannot be read
     * @throws IllegalStateException if configuration validation fails
     */
    private Config loadConfiguration(String configPath) throws IOException {
        LOGGER.info("Loading configuration from: {}", configPath);

        Config cfg;
        try {
            cfg = ConfigLoader.load(configPath, System.getenv());
        } catch (IOException e) {
            LOGGER.warn("Could not load configuration file: {}. Using defaults.", e.getMessage());
            // Create default configuration
            cfg = new Config(configPath, System.getenv(), new java.util.HashMap<>());
        }

        // Validate configuration - validation failures are fatal
        // Do NOT catch IllegalStateException - let it propagate to main() which calls System.exit(1)
        ConfigValidation.validate(cfg);
        LOGGER.info("Configuration validated: visibility.epochTickMs={}, health.readinessThreshold={}",
            cfg.visibility().getEpochTickMs(),
            cfg.health().getReadinessThreshold());
        return cfg;
    }

    /**
     * Determines the server port from configuration or defaults.
     *
     * <p>Read off the effective properties for the same reason the security policy is: they are
     * the file and the environment already merged by one parser, so the port cannot end up
     * disagreeing with the rest of the configuration about which value won.</p>
     *
     * @return Server port
     * @throws IllegalArgumentException if a configured port is not usable
     */
    private int determinePort() {
        return ListenPort.resolve(config.getEffectiveProperties(), System.getenv());
    }

    /**
     * Initializes the data directory.
     *
     * @return Path to the data directory
     * @throws IOException if directory cannot be created
     */
    private Path initializeDataDirectory() throws IOException {
        // The environment is the only source. Deployments already spell this as a variable, and a
        // properties key beside it would give the same node two answers with no rule for which
        // wins - which is how a restart quietly starts serving from an empty directory.
        String dataDirPath = System.getenv("EXCALIBUR_DATA_DIR");
        if (dataDirPath == null || dataDirPath.isEmpty()) {
            dataDirPath = DEFAULT_DATA_DIR;
        }

        Path dataDir = Paths.get(dataDirPath).toAbsolutePath();

        if (!Files.exists(dataDir)) {
            LOGGER.info("Creating data directory: {}", dataDir);
            Files.createDirectories(dataDir);
        }

        LOGGER.info("Data directory: {}", dataDir);
        return dataDir;
    }


    /**
     * Creates the query pipeline.
     *
     * @return Initialized query pipeline
     */
    private QueryPipeline createQueryPipeline() {
        LOGGER.info("Initializing query pipeline...");

        // Create graph provider that returns graphs from segments in storage
        // Each segment has an associated HNSW graph that is built incrementally during ingestion
        QueryPipeline.HnswGraphProvider graphProvider = segmentId -> {
            Segment segment = storageEngine.getSegment(segmentId);
            if (segment != null) {
                return segment.getHnswGraph();
            }
            return null;
        };

        // Use cosine similarity as default distance function
        DistanceFunction distanceFunction = CosineSimilarity.getInstance();

        // Create LLM scorer with query-specific timeout from config
        // Uses excalibur.query.llm-timeout-ms for relevancy scoring calls
        QueryPipeline.LlmScorer llmScorer = null;
        if (excalibur.api.bootstrap.RetrievalCapabilities.from(config.llm()).chatModel()) {
            try {
                int queryLlmTimeoutMs = config.query().getLlmTimeoutMs();
                llmScorer = excalibur.query.scorer.LlmScorerFactory.fromConfigWithQueryTimeout(
                    config.llm(), queryLlmTimeoutMs);
                LOGGER.info("LLM scorer created with query-specific timeout: {}ms", queryLlmTimeoutMs);
            } catch (Exception e) {
                LOGGER.warn("Failed to create LLM scorer: {}. LLM scoring will be disabled.", e.getMessage());
            }
        } else {
            LOGGER.info("LLM API key not configured - LLM scoring disabled");
        }

        QueryPipeline pipeline = new QueryPipeline(
            storageEngine,
            indexOrchestrator.getVisibilityTracker(),
            documentVisibilityChecker,  // Document atomicity filter for query pipeline
            graphProvider,
            distanceFunction,
            llmScorer,  // LLM scorer with query-specific timeout
            QueryPipeline.DEFAULT_COVERAGE_THRESHOLD,
            hierarchicalFilter,  // CRITICAL: Pass the hierarchical filter for Stage 1 pruning
            // Node-level epoch filtering. Omitting it leaves the pipeline unable to exclude graph
            // nodes written after the query's snapshot epoch, so a long-running query can return
            // vectors that did not exist when it started. The orchestrator maintains the
            // segment-scoped records this reads, and they were inert while nothing consumed them.
            indexOrchestrator.getEpochIsolationValidator()
        );

        LOGGER.info("Query pipeline initialized with document atomicity, hierarchical Cuckoo "
            + "filter and node-level epoch isolation enabled");
        return pipeline;
    }

    /**
     * Configures the document reindexer for actual reindex operations.
     *
     * <p>Wiring order matters: this must run after {@code server} is assigned, because
     * it hands the reindexer to the server. Running it from the middle of the
     * constructor dereferences a field that is still null, and swallowing the
     * resulting NullPointerException leaves reindexing permanently disabled with only
     * a warning to show for it.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Chunks are written through the storage engine's hash routing, so a
     *       reindexed chunk stays reachable by key</li>
     *   <li>Superseded chunks are tombstoned through the same routed delete</li>
     *   <li>The lexical index is kept in step: reindexed chunks are indexed and retired
     *       ones erased, so BM25 cannot answer with a document the corpus no longer has</li>
     *   <li>Rolling replacement is enabled when a volume manager and a segment catalog
     *       are available</li>
     * </ul>
     *
     * @throws IllegalStateException if the reindexer cannot be constructed
     */
    private void configureReindexer() {
        LOGGER.info("Configuring document reindexer...");

        // Reindexing re-embeds every chunk it rewrites, so it needs the same embedding endpoint
        // ingestion used. A node that does not embed its corpus has nothing for this to do that
        // would not contradict the way its index was built.
        if (!excalibur.api.bootstrap.RetrievalCapabilities.from(config.llm()).embedCorpus()) {
            LOGGER.warn("This node does not embed its corpus, so POST /v1/reindex will answer "
                + "503: reindexing re-embeds what it rewrites. Set excalibur.llm.api-key (or "
                + "EXCALIBUR_LLM_API_KEY), and leave excalibur.llm.embeddings-enabled at its "
                + "default, to enable it.");
            return;
        }

        DocumentReindexer reindexer = ReindexerFactory.create(
            config,
            storageEngine,
            volumeManager,
            indexOrchestrator,
            hierarchicalFilter,
            documentVisibilityTracker.getRegistry(),
            chunkIndex
        );

        server.withReindexer(reindexer);
        LOGGER.info("Document reindexer configured successfully");
    }

    /**
     * Creates the HTTP server.
     *
     * <p>This method also wires the ingestion pipeline, enabling the /v1/ingest endpoint to
     * trigger full document processing.</p>
     *
     * @return Initialized HTTP server
     */
    private ExcaliburDBServer createServer() {
        LOGGER.info("Creating HTTP server on port {}...", port);

        ExcaliburDBServer srv = new ExcaliburDBServer(
            port,
            ExcaliburDBServer.DEFAULT_BACKLOG,
            indexOrchestrator,
            storageEngine,
            volumeManager,
            config,
            queryPipeline
        );

        // One resolution of the security policy for the whole process
        srv.withSecurity(apiSecurity);

        // Registrations are written down before the endpoints read them back, so a node that is
        // restarted keeps crawling what it was asked to crawl. Fatal if the file exists and cannot
        // be read: starting with an empty registry would silently cancel every source on it.
        srv.withSourceCatalog(excalibur.ingest.registry.SourceCatalog.open(dataDirectory));

        // This enables /v1/ingest to trigger full document processing: chunking, embedding, storage
        configureIngestionPipeline(srv);

        // Erasure, wired after the pipeline because it removes what the pipeline wrote and needs
        // the chunk index the pipeline opened. Registered whether or not there is one: a node with
        // no lexical index still has content in storage that someone can ask to have removed.
        srv.withDocumentErasure(new excalibur.ingest.erasure.DocumentErasureService(
            documentVisibilityTracker.getRegistry(),
            excalibur.api.bootstrap.StorageChunkWriter.tombstoneAdapter(storageEngine),
            this.chunkIndex,
            excalibur.ingest.lexical.StoredChunkText.of(storageEngine),
            documentVisibilityTracker,
            // Without this a vector query still ranks the erased document: no text, because the
            // payload comes from storage and storage no longer has it, but its name and a
            // distance - which is the fact the request asked to remove.
            excalibur.ingest.erasure.SegmentVectorEraser.of(storageEngine)));

        // Both graph routes, whether or not a graph exists yet: a node that has ingested and not
        // built needs the build route, and one that builds later must start answering without a
        // restart.
        srv.withGraphSupport(graphTenants);
        LOGGER.info("Graph-RAG endpoints registered: /v1/graph/query, /v1/graph/build, "
            + "/v1/graph/erasure ({} namespace(s) open)", graphTenants.openCount());

        LOGGER.info("HTTP server created");
        return srv;
    }

    /**
     * Opens every namespace's graph, bounded by what this process may map.
     *
     * <p>Rooted at the configured graph path's parent, one subdirectory per namespace, with the
     * shared namespace being the one a node that never sees the header uses. The mapping budget is
     * detected once and governs how many graphs stay open at a time: a served store is thirteen
     * mappings, and a node with a graph per tenant will otherwise exhaust the process's supply at
     * whatever unrelated allocation happens to come next.</p>
     *
     * @param dataDir The data directory
     * @return The tenant registry
     */
    private excalibur.api.bootstrap.GraphTenants openGraphTenants(Path dataDir) {
        Path root = GraphRagFactory.directoryFor(config, dataDir).getParent();
        if (root == null) {
            root = dataDir;
        }
        try {
            return new excalibur.api.bootstrap.GraphTenants(
                root, config,
                io.excalibur.tenancy.MappingBudget.detect(),
                config.graph().getMaxOpenNamespaces());
        } catch (IOException e) {
            throw new IllegalStateException("Graph tenants under " + root
                + " could not be opened: " + e.getMessage(), e);
        }
    }

    /**
     * Opens the graph-RAG stack, or returns null when the node has no graph.
     *
     * <p>An unreadable graph is a startup failure rather than a silently absent endpoint. The
     * two look identical from a client - both answer 404 - and only one of them is what the
     * operator configured.
     *
     * @param dataDir The data directory the graph lives under by default
     * @return The opened stack, or null
     */
    private GraphRagFactory.GraphRag openGraphRag(Path dataDir) {
        try {
            return GraphRagFactory.open(config, dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Graph store at "
                + GraphRagFactory.directoryFor(config, dataDir)
                + " could not be opened: " + e.getMessage(), e);
        }
    }

    /**
     * Configures the ingestion pipeline for background document processing.
     *
     * <p>This method creates and wires all ingestion components:</p>
     * <ul>
     *   <li>{@link TextChunker} - Splits documents into overlapping chunks</li>
     *   <li>{@link EmbeddingClient} - Generates vector embeddings via LLM API</li>
     *   <li>{@link LshIngestionService} - The vector pre-filter, written as HNSW integration assigns node ids</li>
     *   <li>{@link IngestionOrchestrator} - Orchestrates the complete ingestion pipeline</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Vector dimensions from config must match LLM embedding dimensions</li>
     *   <li>LSH parameters are tuned for ~0.1% false positive rate</li>
     *   <li>Document visibility tracker is shared with query pipeline for atomicity</li>
     * </ul>
     *
     * @param server The server to configure
     */
    private void configureIngestionPipeline(ExcaliburDBServer server) {
        try {
            // Create TextChunker for splitting documents into semantic chunks
            // Default: 512 tokens with 64 token overlap (sentence-boundary-aware)
            TextChunker textChunker = new TextChunker();
            LOGGER.info("TextChunker created for document chunking");

            // Not embedding is lexical-only ingestion, not "no ingestion". Documents are chunked,
            // written and term-indexed either way; only the vector signal is missing, which is one
            // signal of several rather than the precondition for having an index at all --
            // requiring it meant a corpus-wide embedding pass before a single document could be
            // searched.
            excalibur.api.bootstrap.RetrievalCapabilities capabilities =
                excalibur.api.bootstrap.RetrievalCapabilities.from(config.llm());

            LLMClientConfig llmClientConfig = LLMClientConfig.fromConfig(config.llm());
            EmbeddingClient embeddingClient = null;
            if (capabilities.embedCorpus()) {
                embeddingClient = new EmbeddingClient(llmClientConfig);
                LOGGER.info("EmbeddingClient created: baseUrl={}, model={}, dimensions={}",
                    config.llm().getBaseUrl(), config.llm().getEmbeddingModel(),
                    llmClientConfig.getEmbeddingDimension());
            } else if (capabilities.chatModel()) {
                LOGGER.info("Corpus embedding declined by excalibur.llm.embeddings-enabled - "
                    + "ingesting lexically. Documents are chunked, stored and term-indexed, and "
                    + "POST /v1/query answers from BM25 with the chat model expanding the query.");
            } else {
                LOGGER.warn("LLM API key not configured - ingesting lexically. Documents are "
                    + "chunked, stored and term-indexed, and POST /v1/query answers from BM25 "
                    + "over that index. No vectors are produced, so the vector signal is "
                    + "missing and every answer reports its coverage as degraded. Set "
                    + "excalibur.llm.api-key, or EXCALIBUR_LLM_API_KEY, to add it.");
            }

            // The vector pre-filter. It is written by HNSW integration rather than by ingestion,
            // because it is keyed by the node ids that integration assigns.
            // Uses vector dimensions from query config (should match embedding dimensions)
            int vectorDimensions = config.query().getVectorDimensions();
            if (vectorDimensions <= 0) {
                vectorDimensions = llmClientConfig.getEmbeddingDimension();
            }
            // A BM25 index over the chunks this node stores. Built regardless of whether
            // embeddings are configured: it is the retrieval path that does not require the
            // corpus to be permuted into vectors first.
            excalibur.ingest.lexical.ChunkIndex chunkIndex = null;
            try {
                chunkIndex = excalibur.api.bootstrap.ChunkIndexFactory.open(config, dataDirectory,
                    () -> indexOrchestrator.getVisibilityTracker().currentEpoch());
                this.chunkIndex = chunkIndex;
                LOGGER.info("Chunk lexical index opened on the {} engine", chunkIndex.engine());
            } catch (IOException e) {
                LOGGER.warn("Chunk lexical index unavailable, BM25 retrieval disabled: {}",
                    e.getMessage());
            }

            // LSH places vectors; with none to place it is dead weight on every write.
            LshIngestionService lshIngestionService =
                capabilities.embedCorpus() ? new LshIngestionService(vectorDimensions) : null;
            LOGGER.info("LshIngestionService: {}",
                lshIngestionService == null ? "disabled (lexical-only ingestion)"
                                            : "dimensions=" + vectorDimensions);

            // Wire the ingestion pipeline
            server.withIngestionPipeline(textChunker, embeddingClient, lshIngestionService, documentVisibilityTracker);
            LOGGER.info("Ingestion pipeline configured with document atomicity");

            if (chunkIndex != null) {
                excalibur.ingest.pipeline.IngestionOrchestrator orchestrator =
                    server.getIngestionOrchestrator();
                if (orchestrator != null) {
                    orchestrator.setLexicalIndex(chunkIndex);
                }
                // Query expansion is what carries the semantics when the corpus is not embedded,
                // so it belongs to nodes that have a chat model and no vectors. Without one this
                // is plain BM25, which is a weaker search and still a working one.
                io.excalibur.retrieval.expand.QueryExpander expander =
                    io.excalibur.retrieval.expand.QueryExpander.identity();
                if (capabilities.expandQueries()) {
                    expander = new io.excalibur.retrieval.expand.LlmQueryExpander(
                        new io.excalibur.llm.client.LLMHttpClient(llmClientConfig));
                    LOGGER.info("Query expansion enabled with chat model {}",
                        config.llm().getChatModel());
                } else if (!capabilities.embedCorpus()) {
                    LOGGER.info("No chat model configured; retrieval is plain BM25 without "
                        + "query expansion");
                }
                server.withLexicalQueryService(new excalibur.api.retrieval.LexicalQueryService(
                    chunkIndex, expander, java.util.List.of()));
                server.withChunkIndex(chunkIndex);
            }

            // Relation extraction is a chat call, so it turns on with the model rather than with
            // the embedding pass.
            if (capabilities.chatModel()) {
                configureGraphIngest(server, llmClientConfig);
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to configure ingestion pipeline: {}. Sources can be registered "
                + "and crawled, but nothing will be indexed.", e.getMessage(), e);
            // Continue without the pipeline: registration is bookkeeping and does not need one,
            // and /v1/ingest/status reports every source as unconfigured rather than as ingested.
        }
    }

    /**
     * Points ingestion at the graph, so published documents become facts.
     *
     * <p>The lexical half is always wired: an entity's text is what the BM25 signal ranks it by,
     * and it costs one staged record per mention. Extraction is wired only when it is asked for -
     * it is a chat-model call per document, which is the most expensive thing ingestion can do,
     * and a node doing vector search should not start paying for a graph nobody asked for.</p>
     *
     * @param server          The server holding the ingestion orchestrator
     * @param llmClientConfig The LLM configuration, for the extractor's client
     */
    private void configureGraphIngest(ExcaliburDBServer server, LLMClientConfig llmClientConfig) {
        excalibur.ingest.pipeline.IngestionOrchestrator orchestrator =
            server.getIngestionOrchestrator();
        if (orchestrator == null) {
            LOGGER.warn("No ingestion orchestrator; documents will not reach the graph");
            return;
        }

        boolean extractionEnabled = config.graph().isExtractionEnabled();
        excalibur.ingest.graph.RelationExtractor extractor =
            excalibur.ingest.graph.RelationExtractor.none();
        if (extractionEnabled) {
            extractor = new excalibur.ingest.graph.LlmRelationExtractor(
                new io.excalibur.llm.client.LLMHttpClient(llmClientConfig),
                config.graph().getExtractorVersion());
        }

        // Ingestion is not namespace-aware: a source belongs to whichever namespace registered
        // it, and today that is the shared one. Staging is therefore taken from the shared tenant,
        // held for the life of the process rather than borrowed per document - it is two small
        // append-only files, not the thirteen mappings the budget exists to bound.
        orchestrator.setGraphSink(new excalibur.ingest.graph.StagingGraphIngestSink(
            graphTenants.shared().staging(), extractor));
        LOGGER.info("Graph ingest wired: extraction {} (extractor version {})",
            extractionEnabled ? "enabled" : "disabled", config.graph().getExtractorVersion());
    }

    /**
     * Runs the application.
     *
     * <p>This method starts all components and registers a shutdown hook
     * for graceful termination.</p>
     *
     * @throws IOException if the server cannot be started
     */
    public void run() throws IOException {
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

        // Start the HTTP server
        server.start();

        LOGGER.info("========================================");
        LOGGER.info("ExcaliburDB server started successfully");
        LOGGER.info("Listening on port: {}", port);
        LOGGER.info("Health endpoint: http://localhost:{}/health", port);
        LOGGER.info("Stats endpoint: http://localhost:{}/v1/stats", port);
        LOGGER.info("Query endpoint: http://localhost:{}/v1/query", port);
        LOGGER.info("========================================");

        // Keep the main thread alive
        try {
            while (!shutdownRequested && server.isRunning()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            LOGGER.info("Main thread interrupted, initiating shutdown...");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Performs graceful shutdown.
     */
    private void shutdown() {
        if (shutdownRequested) {
            return;
        }
        shutdownRequested = true;

        LOGGER.info("========================================");
        LOGGER.info("Shutting down ExcaliburDB...");
        LOGGER.info("========================================");

        // Ordering is a correctness property, not tidiness. Query and reindex threads
        // read and write memory-mapped segments through the shared Arena; closing that
        // arena while a mapping is still live crashes the JVM instead of throwing. So:
        // stop accepting, drain everything in flight, close storage, close the arena.
        ShutdownSequence sequence = new ShutdownSequence()
            .add("stop HTTP server and drain in-flight requests",
                () -> server.stop(SHUTDOWN_TIMEOUT_SECONDS))
            .add("stop index orchestrator", indexOrchestrator::close)
            // After ingestion has stopped and before storage closes: closing it publishes what
            // was still buffered, and it is storage that those chunks were written to first.
            // A lambda, not a bound method reference: the arguments are evaluated before
            // addIfPresent gets to check for null, so `chunkIndex::close` would fail here on
            // exactly the shutdown where no index was ever opened.
            .addIfPresent("close chunk lexical index", chunkIndex, () -> chunkIndex.close())
            // After ingestion has stopped, so what is written is the final state rather than a
            // snapshot something was still adding to. Every operation that changes the registry
            // writes it too; this is what catches a node stopped between them.
            .add("persist document registry",
                () -> documentVisibilityTracker.getRegistry().persist())
            .addIfPresent("close graph tenants", graphTenants, graphTenants::close)
            .add("close storage engine", storageEngine::close)
            .addIfPresent("release memory-mapped segments", arena, arena::close);

        int failedSteps = sequence.stepNames().size() - sequence.run().size();
        reportShutdownOutcome(failedSteps, storageEngine.getCatalog().closeFailure());
    }

    /**
     * Logs how the shutdown actually went.
     *
     * <p>An operator reads this line to decide whether the node can be restarted without further
     * thought, so it has to distinguish a shutdown that persisted everything from one that did
     * not. A stale catalog in particular is not cosmetic: the next start re-processes write-log
     * ranges it has already integrated.</p>
     *
     * @param failedSteps    Number of shutdown steps that threw
     * @param catalogFailure The failure that stopped the catalog closing durably, or null
     */
    private static void reportShutdownOutcome(int failedSteps, Throwable catalogFailure) {
        if (failedSteps == 0 && catalogFailure == null) {
            LOGGER.info("ExcaliburDB shutdown complete");
            return;
        }

        if (catalogFailure != null) {
            LOGGER.error("ExcaliburDB shutdown finished with {} failed step(s) and a catalog that "
                + "did not close durably; the next start may re-process write-log ranges it had "
                + "already integrated", failedSteps, catalogFailure);
        } else {
            LOGGER.error("ExcaliburDB shutdown finished with {} failed step(s); see the errors "
                + "logged above", failedSteps);
        }
    }

    /**
     * Gets the loaded configuration.
     *
     * @return The configuration
     */
    public Config getConfig() {
        return config;
    }

    /**
     * Gets the storage engine.
     *
     * @return The storage engine
     */
    public StorageEngine getStorageEngine() {
        return storageEngine;
    }

    /**
     * Gets the index orchestrator.
     *
     * @return The index orchestrator
     */
    public IndexOrchestrator getIndexOrchestrator() {
        return indexOrchestrator;
    }

    /**
     * Gets the HTTP server.
     *
     * @return The HTTP server
     */
    public ExcaliburDBServer getServer() {
        return server;
    }

    /**
     * Gets the server port.
     *
     * @return The port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the HNSW configuration factory.
     *
     * <p>This factory creates {@link io.excalibur.vector.hnsw.HnswConfig} instances
     * configured with values from the Config.Shard settings:</p>
     * <ul>
     *   <li>{@code excalibur.shard.default-ef-construction} - efConstruction parameter</li>
     *   <li>{@code excalibur.shard.default-ef-search} - efSearch parameter</li>
     * </ul>
     *
     * @return The HNSW configuration factory
     */
    public HnswConfigFactory getHnswConfigFactory() {
        return hnswConfigFactory;
    }

    /**
     * Gets the hierarchical Cuckoo filter for corpus → cluster → segment membership.
     *
     * <p>This filter is shared between:</p>
     * <ul>
     *   <li>Ingestion pipeline - inserts entries during document ingestion</li>
     *   <li>CuckooMaintenanceWorker - propagates deletions through the hierarchy</li>
     * </ul>
     *
     * <p>Use this to pass the same filter instance to ingestion components,
     * ensuring that entries inserted during ingestion can be properly cleaned
     * up when segments are tombstoned.</p>
     *
     * @return The hierarchical Cuckoo filter
     */
    public HierarchicalCuckooFilter getHierarchicalFilter() {
        return hierarchicalFilter;
    }

    /**
     * Gets the document visibility tracker for atomic document-level visibility.
     *
     * <p>The visibility tracker ensures that:</p>
     * <ul>
     *   <li>Documents become visible atomically (all chunks at once)</li>
     *   <li>Queries never see partially-ingested documents</li>
     *   <li>Visibility is epoch-based for consistent query snapshots</li>
     * </ul>
     *
     * <p>Use this to pass the same tracker instance to ingestion components,
     * ensuring that document atomicity is maintained across the system.</p>
     *
     * @return The document visibility tracker
     */
    public DocumentVisibilityTracker getDocumentVisibilityTracker() {
        return documentVisibilityTracker;
    }

    /**
     * Gets the document visibility checker for query pipeline atomicity filtering.
     *
     * <p>This is an adapter around the document visibility tracker that implements
     * the DocumentVisibilityChecker interface for use in the query pipeline.</p>
     *
     * @return The document visibility checker
     */
    public DocumentVisibilityChecker getDocumentVisibilityChecker() {
        return documentVisibilityChecker;
    }

    /**
     * Gets the metrics registry for JMX MBean management.
     *
     * <p>The metrics registry provides access to all registered JMX MBeans
     * for observability and monitoring:</p>
     * <ul>
     *   <li>Query latency (p50, p90, p95, p99)</li>
     *   <li>System health score</li>
     *   <li>Ingestion queue depth</li>
     *   <li>Visibility epoch</li>
     *   <li>Cuckoo filter false positive rate</li>
     *   <li>HNSW queue depth</li>
     * </ul>
     *
     * @return The metrics registry
     */
    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }
}
