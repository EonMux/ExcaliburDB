package excalibur.api;

import excalibur.api.server.ExcaliburDBServer;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.config.Config;
import io.excalibur.config.ConfigLoader;
import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.metrics.Metrics;
import io.excalibur.metrics.MetricsRegistry;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * Default server port.
     */
    public static final int DEFAULT_PORT = 8080;

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
    private final VolumeManager volumeManager;
    private final QueryPipeline queryPipeline;
    private final ExcaliburDBServer server;
    private final int port;
    private final HnswConfigFactory hnswConfigFactory;

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

        // Initialize document visibility tracker for atomic document visibility
        // This ensures queries never see partially-ingested documents
        this.documentVisibilityTracker = new DocumentVisibilityTracker(new DocumentRegistry());
        this.documentVisibilityChecker = new DocumentVisibilityTrackerAdapter(documentVisibilityTracker);
        LOGGER.info("Document visibility tracker initialized for document atomicity");

        // Determine server port from config or use default
        this.port = determinePort();

        // Check Panama FFM availability and create Arena only if available
        boolean panamaAvailable = MemorySegmentFactory.isPanamaAvailable();
        this.arena = panamaAvailable ? Arena.ofShared() : null;

        LOGGER.info("Using {} memory implementation",
            panamaAvailable ? "Panama FFM" : "ByteBuffer fallback");

        // Initialize data directory
        Path dataDir = initializeDataDirectory();

        // Initialize storage engine (uses fallback constructor if Arena is null)
        this.storageEngine = createStorageEngine(dataDir);

        // Initialize volume manager
        this.volumeManager = createVolumeManager();

        // Initialize index orchestrator
        this.indexOrchestrator = createIndexOrchestrator();

        // Initialize hierarchical Cuckoo filter and wire it to the maintenance worker
        this.hierarchicalFilter = createHierarchicalCuckooFilter();
        indexOrchestrator.setHierarchicalCuckooFilter(hierarchicalFilter);

        // Wire compaction callback from lifecycle manager to compaction worker
        wireCompactionCallback();

        // Configure HNSW initializer for creating HNSW graphs on new segments
        configureHnswInitializer();

        // Register existing segments with IndexOrchestrator for HNSW integration
        registerExistingSegmentsWithOrchestrator();

        // Start the segment lifecycle manager for background compaction
        storageEngine.startLifecycleManager();

        // Initialize query pipeline
        this.queryPipeline = createQueryPipeline();

        // Create HTTP server
        this.server = createServer();

        LOGGER.info("ExcaliburApplication initialized successfully");
    }

    /**
     * Loads configuration from the specified path.
     *
     * @param configPath Path to configuration file
     * @return Loaded configuration
     * @throws IOException if file cannot be read
     */
    private Config loadConfiguration(String configPath) throws IOException {
        LOGGER.info("Loading configuration from: {}", configPath);

        try {
            Config cfg = ConfigLoader.load(configPath, System.getenv());
            LOGGER.info("Configuration loaded: visibility.epochTickMs={}, health.readinessThreshold={}",
                cfg.visibility().getEpochTickMs(),
                cfg.health().getReadinessThreshold());
            return cfg;
        } catch (IOException e) {
            LOGGER.warn("Could not load configuration file: {}. Using defaults.", e.getMessage());
            // Create default configuration
            return new Config(configPath, System.getenv(), new java.util.HashMap<>());
        }
    }

    /**
     * Determines the server port from configuration or defaults.
     *
     * @return Server port
     */
    private int determinePort() {
        // Check for port in environment variable first (common in cloud deployments)
        String portEnv = System.getenv("EXCALIBUR_PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                return Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid EXCALIBUR_PORT value: {}. Using default.", portEnv);
            }
        }

        // Use default port
        return DEFAULT_PORT;
    }

    /**
     * Initializes the data directory.
     *
     * @return Path to the data directory
     * @throws IOException if directory cannot be created
     */
    private Path initializeDataDirectory() throws IOException {
        // Check for data directory in config or environment
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
     * Creates the storage engine.
     *
     * @param dataDir Data directory path
     * @return Initialized storage engine
     */
    private StorageEngine createStorageEngine(Path dataDir) {
        LOGGER.info("Initializing storage engine...");

        // Use fallback constructor when Arena is null (ByteBuffer-based implementation)
        // Use Arena constructor when Panama FFM is available
        StorageEngine engine = (arena != null)
            ? new StorageEngine(dataDir, arena)
            : new StorageEngine(dataDir);
        engine.start();

        LOGGER.info("Storage engine initialized with {} segments", engine.getSegmentCount());
        return engine;
    }

    /**
     * Creates the volume manager.
     *
     * @return Initialized volume manager
     */
    private VolumeManager createVolumeManager() {
        LOGGER.info("Initializing volume manager...");

        SegmentCatalog catalog = storageEngine.getCatalog();
        VolumeManager vm = new VolumeManager(catalog);

        // Register default volume (data directory)
        Path dataDir = storageEngine.dataDir();
        vm.registerVolume(dataDir);

        LOGGER.info("Volume manager initialized with {} volume(s)", vm.getVolumeCount());
        return vm;
    }

    /**
     * Creates the index orchestrator.
     *
     * @return Initialized index orchestrator
     */
    private IndexOrchestrator createIndexOrchestrator() {
        LOGGER.info("Initializing index orchestrator...");

        SegmentCatalog catalog = storageEngine.getCatalog();

        IndexOrchestrator orchestrator = new IndexOrchestrator(
            catalog,
            storageEngine,
            volumeManager,
            (int) config.ingest().getHnswQueueDepthLimit(),
            IndexOrchestrator.DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS,
            IndexOrchestrator.DEFAULT_REINDEX_CHECK_INTERVAL_MS,
            config.visibility().getEpochTickMs()
        );

        LOGGER.info("Index orchestrator initialized");
        return orchestrator;
    }

    /**
     * Creates the hierarchical Cuckoo filter for corpus → cluster → segment membership.
     *
     * <p>The hierarchical filter enables efficient segment pruning during queries
     * and proper deletion propagation through all filter levels when documents
     * are tombstoned.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Corpus capacity: 10 million entries (configurable via excalibur.filter.corpus-capacity)</li>
     *   <li>Fingerprint bits: 13 (provides ~0.1% false positive rate)</li>
     *   <li>Cluster capacity: 100,000 entries per cluster</li>
     *   <li>Segment capacity: 10,000 entries per segment</li>
     * </ul>
     *
     * @return Initialized hierarchical Cuckoo filter
     */
    private HierarchicalCuckooFilter createHierarchicalCuckooFilter() {
        LOGGER.info("Initializing hierarchical Cuckoo filter...");

        // Use configurable corpus capacity or default to 10 million entries
        // This should be sized based on expected corpus size for optimal false positive rate
        long corpusCapacity = getLongProperty("excalibur.filter.corpus-capacity", 10_000_000L);

        // Default parameters tuned for ~0.1% false positive rate
        int fingerprintBits = 13;
        int clusterCapacity = 100_000;
        int segmentCapacity = 10_000;

        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(
            corpusCapacity,
            fingerprintBits,
            clusterCapacity,
            segmentCapacity
        );

        LOGGER.info("Hierarchical Cuckoo filter initialized: corpusCapacity={}, fingerprintBits={}, clusterCapacity={}, segmentCapacity={}",
            corpusCapacity, fingerprintBits, clusterCapacity, segmentCapacity);

        return filter;
    }

    /**
     * Gets a long property from system properties or returns the default.
     *
     * @param key The property key
     * @param defaultValue The default value if property is not set
     * @return The property value or default
     */
    private long getLongProperty(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isEmpty()) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid value for property {}: '{}', using default: {}", key, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * Wires the compaction callback from the lifecycle manager to the compaction worker.
     *
     * <p>This sets up the connection between the SegmentLifecycleManager (which detects
     * when segments need compaction) and the CompactionWorker (which performs the
     * actual compaction).</p>
     */
    private void wireCompactionCallback() {
        LOGGER.info("Wiring compaction callback...");

        // Get the lifecycle manager from the storage engine
        var lifecycleManager = storageEngine.getLifecycleManager();

        // Set up the compaction callback
        // When the lifecycle manager detects a segment needs compaction,
        // it will call the compaction worker to perform the actual compaction
        lifecycleManager.setCompactionCallback(segmentId -> {
            // Get the segment from the storage engine
            var segment = storageEngine.getSegment(segmentId);
            if (segment == null) {
                LOGGER.warn("Compaction callback received for unknown segment: {}", segmentId);
                return false;
            }

            // Request compaction from the compaction worker
            indexOrchestrator.getCompactionWorker().requestCompaction(segment);
            LOGGER.debug("Compaction requested for segment {} via lifecycle callback", segmentId);
            return true;
        });

        LOGGER.info("Compaction callback wired successfully");
    }

    /**
     * Configures the HNSW initializer for the storage engine.
     *
     * <p>This sets up HNSW graph creation for new segments using the
     * configuration from Config.Shard (efConstruction, efSearch).</p>
     */
    private void configureHnswInitializer() {
        LOGGER.info("Configuring HNSW initializer...");

        // Get vector dimensions from config (default to 1536 for OpenAI embeddings)
        int dimension = config.query().getVectorDimensions();
        if (dimension <= 0) {
            dimension = io.excalibur.store.HnswInitializer.DEFAULT_VECTOR_DIMENSION;
        }

        // Create HNSW initializer with configuration from Config.Shard
        io.excalibur.store.HnswInitializer initializer =
            new io.excalibur.store.HnswInitializer(
                hnswConfigFactory,
                CosineSimilarity.getInstance(),
                dimension,
                io.excalibur.store.HnswInitializer.DEFAULT_MAX_NODES,
                arena  // May be null for ByteBuffer fallback
            );

        // Configure storage engine to use the initializer
        storageEngine.setHnswInitializer(initializer);

        LOGGER.info("HNSW initializer configured: dimension={}, maxNodes={}",
            initializer.getDimension(), initializer.getMaxNodes());
    }

    /**
     * Registers existing segments with the IndexOrchestrator for HNSW integration.
     *
     * <p>This method iterates through all existing segments in the storage engine
     * and registers those with HNSW graphs with the IndexOrchestrator. This enables
     * the HnswIntegrationWorker to integrate vectors from the write log.</p>
     *
     * <p>Segments without HNSW graphs (created before HNSW was enabled) are skipped.</p>
     */
    private void registerExistingSegmentsWithOrchestrator() {
        LOGGER.info("Registering existing segments with IndexOrchestrator...");

        int registeredCount = 0;
        long segmentCount = storageEngine.getSegmentCount();

        for (long segmentId = 0; segmentId < segmentCount; segmentId++) {
            io.excalibur.store.Segment segment = storageEngine.getSegment(segmentId);
            if (segment == null) {
                continue;
            }

            HnswGraph graph = segment.getHnswGraph();
            io.excalibur.vector.hnsw.TailOffsetTracker tracker = segment.getTailOffsetTracker();

            if (graph != null && tracker != null) {
                indexOrchestrator.registerSegment(
                    segmentId,
                    segment,
                    graph,
                    null, // CuckooFilter - handled separately
                    tracker
                );
                registeredCount++;
                LOGGER.debug("Registered segment {} with IndexOrchestrator", segmentId);
            }
        }

        LOGGER.info("Registered {} segments with IndexOrchestrator for HNSW integration",
            registeredCount);
    }

    /**
     * Creates the query pipeline.
     *
     * @return Initialized query pipeline
     */
    private QueryPipeline createQueryPipeline() {
        LOGGER.info("Initializing query pipeline...");

        // Create graph provider that returns graphs from storage
        // In a full implementation, this would look up graphs from a registry
        QueryPipeline.HnswGraphProvider graphProvider = segmentId -> {
            // For now, return null (no pre-built graphs)
            // Graphs are built incrementally during ingestion
            return null;
        };

        // Use cosine similarity as default distance function
        DistanceFunction distanceFunction = CosineSimilarity.getInstance();

        QueryPipeline pipeline = new QueryPipeline(
            storageEngine,
            indexOrchestrator.getVisibilityTracker(),
            documentVisibilityChecker,  // Document atomicity filter for query pipeline
            graphProvider,
            distanceFunction
        );

        LOGGER.info("Query pipeline initialized with document atomicity enabled");
        return pipeline;
    }

    /**
     * Creates the HTTP server.
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

        LOGGER.info("HTTP server created");
        return srv;
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

        try {
            // Stop HTTP server first (stops accepting new requests)
            LOGGER.info("Stopping HTTP server...");
            server.stop(SHUTDOWN_TIMEOUT_SECONDS);

            // Close index orchestrator (stops background workers)
            LOGGER.info("Stopping index orchestrator...");
            indexOrchestrator.close();

            // Close storage engine
            LOGGER.info("Closing storage engine...");
            storageEngine.close();

            // Close arena only if using Panama FFM (releases all memory-mapped segments)
            if (arena != null) {
                LOGGER.info("Releasing memory-mapped segments...");
                arena.close();
            }

            LOGGER.info("ExcaliburDB shutdown complete");

        } catch (Exception e) {
            LOGGER.error("Error during shutdown: {}", e.getMessage(), e);
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
