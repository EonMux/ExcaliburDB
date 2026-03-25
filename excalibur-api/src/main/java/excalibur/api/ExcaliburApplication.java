package excalibur.api;

import excalibur.api.server.ExcaliburDBServer;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.config.Config;
import io.excalibur.config.ConfigLoader;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
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

        // Determine server port from config or use default
        this.port = determinePort();

        // Create arena for memory allocation (must be global scope for long-lived segments)
        this.arena = Arena.ofShared();

        // Initialize data directory
        Path dataDir = initializeDataDirectory();

        // Initialize storage engine
        this.storageEngine = createStorageEngine(dataDir);

        // Initialize volume manager
        this.volumeManager = createVolumeManager();

        // Initialize index orchestrator
        this.indexOrchestrator = createIndexOrchestrator();

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

        StorageEngine engine = new StorageEngine(dataDir, arena);
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
            graphProvider,
            distanceFunction
        );

        LOGGER.info("Query pipeline initialized");
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

            // Close arena (releases all memory-mapped segments)
            LOGGER.info("Releasing memory-mapped segments...");
            arena.close();

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
}
