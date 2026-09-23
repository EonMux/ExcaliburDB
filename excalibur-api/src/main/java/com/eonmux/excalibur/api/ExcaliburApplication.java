package com.eonmux.excalibur.api;

import com.eonmux.excalibur.api.bootstrap.CoreComponents;
import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.api.bootstrap.IngestionWiring;
import com.eonmux.excalibur.api.bootstrap.NodeShutdown;
import com.eonmux.excalibur.api.bootstrap.QueryPipelineFactory;
import com.eonmux.excalibur.api.bootstrap.ReindexerFactory;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.api.server.ListenPort;
import com.eonmux.excalibur.api.server.ShutdownBudget;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ConfigLoader;
import com.eonmux.excalibur.config.ConfigValidation;
import com.eonmux.excalibur.config.ShardConfig;
import com.eonmux.excalibur.core.memory.ForeignMemoryBootstrap;
import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.metrics.MetricsRegistry;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

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

    /** Exit code when the command line could not be understood, as in {@code excalibur-fsck}. */
    private static final int EXIT_USAGE = 2;

    /** Exit code when the server could not start, or failed while running. */
    private static final int EXIT_FAILED = 1;

    /**
     * Default data directory for storage.
     */
    public static final String DEFAULT_DATA_DIR = "data";

    private final Config config;

    /**
     * How long the whole shutdown may take, from {@link ShutdownBudget#KEY_TIMEOUT}.
     *
     * <p>Read at construction rather than when the shutdown hook fires, so a value that cannot be
     * used stops startup instead of surfacing as a failed stop.</p>
     */
    private final Duration shutdownTimeout;

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
    private volatile com.eonmux.excalibur.ingest.lexical.ChunkIndex chunkIndex;

    /**
     * Enforces the chunk index's erasure deadline.
     *
     * <p>Held so shutdown can stop it before the index it compacts is closed underneath it.</p>
     */
    private volatile com.eonmux.excalibur.api.bootstrap.LexicalErasureSweep lexicalErasureSweep;

    /** Every namespace's graph: what it has staged, what it serves, and what it owes. */
    private final com.eonmux.excalibur.api.bootstrap.GraphTenants graphTenants;
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
     * Hierarchical Cuckoo filter for corpus → segment membership testing.
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
        // First, while the process is still single-threaded. Two threads reaching
        // java.lang.foreign for the first time at the same instant deadlock the JVM on its
        // class-initialization monitors, and no timeout or interrupt can break that - the
        // server would hang rather than fail. Once the classes are initialized the race has
        // nothing left to lose.
        ForeignMemoryBootstrap.ensureInitialized();

        if (isHelpRequest(args)) {
            System.out.print(usage());
            return;
        }

        ConfigSource configSource;
        try {
            configSource = configSource(args);
        } catch (IllegalArgumentException e) {
            // Before the logging bridge and before anything is opened: a line the server
            // cannot read is a line it must not act on part of.
            System.err.println("excalibur: " + e.getMessage());
            System.err.println();
            System.err.print(usage());
            System.exit(EXIT_USAGE);
            return;
        }

        routeStorageEngineLogsToTheConfiguredAppender();
        try {
            ExcaliburApplication app = new ExcaliburApplication(configSource);
            app.run();
        } catch (Exception e) {
            LOGGER.error("Failed to start ExcaliburDB: {}", e.getMessage(), e);
            System.exit(EXIT_FAILED);
        }
    }

    /**
     * Reports whether the line is asking how to start the server rather than starting it.
     *
     * <p>Recognised anywhere on the line. {@code excalibur --config node.properties --help}
     * is a half-typed command, and the one outcome the operator did not ask for is a
     * database running on that file.</p>
     *
     * @param args Command-line arguments
     * @return true if help was asked for
     */
    static boolean isHelpRequest(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h") || arg.equals("help")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the usage text.
     *
     * @return How to start the server
     */
    static String usage() {
        return """
            Usage: excalibur [--config <file>]

            Starts an ExcaliburDB node. Settings not named in the file take their documented
            defaults, and any of them can be overridden by an EXCALIBUR_<SECTION>_<NAME>
            environment variable.

            Options:
              --config, -c <file>  Read settings from this file. Also accepted as
                                   --config=<file>. Without it the server reads
                                   %s from the working directory if it is
                                   there, and starts on the packaged defaults if it is not.
              --help, -h           Print this and do nothing else.

            Exit codes:
              0  the server stopped cleanly
              1  the server could not start, or failed while running
              2  the command line was wrong
            """.formatted(DEFAULT_CONFIG_PATH);
    }

    /**
     * Sends {@code java.util.logging} records through SLF4J, like everything else.
     *
     * <p>The storage engine logs through the JDK's logger rather than SLF4J. Left alone those
     * records go to the JDK's own root handler: they are written in the JDK's two-line format
     * rather than the JSON every other line has, so a log pipeline that parses this process's
     * output either drops them or files them as junk - and they are the lines that report a
     * tombstone the directory still routes to, a segment that would not purge, a checksum that
     * did not match. Records below {@code INFO} never leave the JDK logger at all, so raising
     * the log level does not reach them either.</p>
     *
     * <p>Removing the root handler first is what stops the same record being written twice.
     * Levels stay in step from the other direction: {@code LevelChangePropagator} in
     * {@code logback.xml} pushes the configured level onto the JDK loggers, so a record the
     * appender would discard is discarded before it is built.</p>
     */
    static void routeStorageEngineLogsToTheConfiguredAppender() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    /**
     * Where the configuration is read from, and whether the operator chose it.
     *
     * <p>The distinction decides what an unreadable file means. A path the operator typed is a
     * statement about where this node's settings live, and falling back from it starts a
     * different node than the one asked for. The default name is not a statement: running with
     * no properties file at all is supported, so its absence has to stay non-fatal.</p>
     *
     * @param path  The file to read
     * @param named Whether it came from {@code --config} rather than from the default
     */
    record ConfigSource(String path, boolean named) {
    }

    /**
     * Reads the {@code --config} argument, if there is one.
     *
     * @param args Command-line arguments
     * @return The path the operator named, or the default nobody chose
     */
    static ConfigSource configSource(String[] args) {
        String named = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--config=")) {
                named = requireValue(arg, arg.substring("--config=".length()), named);
                continue;
            }
            if (!"--config".equals(arg) && !"-c".equals(arg)) {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(arg + " needs the path of a settings file");
            }
            named = requireValue(arg, args[++i], named);
        }
        return named != null
            ? new ConfigSource(named, true)
            : new ConfigSource(DEFAULT_CONFIG_PATH, false);
    }

    /**
     * Accepts one settings file and refuses a second, or an empty one.
     *
     * <p>Two {@code --config} arguments are a line whose author disagrees with themselves,
     * and whichever one this method preferred would be the wrong half of the time.</p>
     *
     * @param flag    The argument that carried the value, for the message
     * @param value   The path given
     * @param already What an earlier argument already named, or null
     * @return The path
     * @throws IllegalArgumentException if the value is empty or a second one was given
     */
    private static String requireValue(String flag, String value, String already) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(flag + " needs the path of a settings file");
        }
        if (already != null) {
            throw new IllegalArgumentException(
                "a node reads one settings file; got both " + already + " and " + value);
        }
        return value;
    }

    /**
     * Creates a new ExcaliburApplication with the specified configuration.
     *
     * @param configSource Where the configuration is read from
     * @throws IOException if configuration cannot be loaded or initialization fails
     */
    public ExcaliburApplication(ConfigSource configSource) throws IOException {
        LOGGER.info("Starting ExcaliburDB...");

        // Load configuration
        this.config = loadConfiguration(configSource);

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

        // Initialize HNSW config factory from ShardConfig settings
        // This ensures property file values (efConstruction, efSearch) are used for HNSW graphs
        this.hnswConfigFactory = HnswConfigFactory.fromShardConfig(config.shard());
        LOGGER.info("HNSW config factory initialized: efConstruction={}, efSearch={}",
            hnswConfigFactory.getEfConstruction(), hnswConfigFactory.getEfSearch());

        // Determine server port from config or use default
        this.port = determinePort();
        this.shutdownTimeout = ShutdownBudget.configuredTimeout(config.getEffectiveProperties());
        LOGGER.info("Shutdown deadline: {}s ({} to stop within it)", shutdownTimeout.toSeconds(),
            ShutdownBudget.KEY_TIMEOUT);

        // Resolve and validate the API security policy before any component starts.
        // A non-loopback bind without a token fails here rather than after the socket
        // is already accepting requests.
        // Read straight off Config, which already merged the file with the EXCALIBUR_* environment.
        // Re-reading the file here would be a second parser that can disagree with that one about
        // precedence -- and the setting it most affects is the auth token.
        this.apiSecurity = ApiSecurityConfig.fromConfig(config);
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
            com.eonmux.excalibur.api.bootstrap.DocumentRegistryFactory.open(dataDir),
            indexOrchestrator.getVisibilityTracker());
        this.documentVisibilityChecker =
            new DocumentVisibilityTrackerAdapter(documentVisibilityTracker);
        LOGGER.info("Document visibility tracker initialized on the node epoch clock");

        this.queryPipeline = QueryPipelineFactory.create(config, storageEngine, indexOrchestrator,
            documentVisibilityChecker, hierarchicalFilter);

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
     * @param source Where the configuration is read from
     * @return Loaded configuration
     * @throws IOException if a file the operator named cannot be read
     * @throws IllegalStateException if configuration validation fails
     */
    static Config loadConfiguration(ConfigSource source) throws IOException {
        String configPath = source.path();
        LOGGER.info("Loading configuration from: {}", configPath);

        Config cfg;
        try {
            cfg = ConfigLoader.load(configPath, System.getenv());
        } catch (IOException e) {
            // A path the operator typed is not a suggestion. Starting on the packaged defaults
            // instead gives a node with no allowed ingest roots, the shipped index tuning and a
            // loopback bind - one that comes up, reports itself healthy, and is configured by
            // nobody. That is harder to diagnose than not starting, which is the same argument
            // ListenPort makes for refusing a port it cannot parse.
            if (source.named()) {
                throw new IOException("Configuration file named on the command line could not be"
                    + " read: " + configPath + ". Refusing to start on the packaged defaults,"
                    + " which would run a node this deployment did not configure.", e);
            }
            LOGGER.warn("No configuration file at the default path {}; using defaults. {}",
                configPath, e.getMessage());
            cfg = new Config(configPath, System.getenv(), new java.util.HashMap<>());
            // ConfigLoader does this on the path it owns; this path bypasses it, and the
            // environment can carry the same typo a file can.
            com.eonmux.excalibur.config.ConfigLoader.reportUnrecognisedKeys(
                configPath, cfg.getEffectiveProperties());
        }

        // The excalibur.api.* half, which the config module deliberately does not judge because
        // nothing there reads those keys. They are this node's rate limits and request bounds,
        // and a typo in one is a policy the operator believes is in force and is not.
        com.eonmux.excalibur.api.bootstrap.ApiConfigKeys.reportUnrecognised(cfg.getEffectiveProperties());

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
        srv.withSourceCatalog(com.eonmux.excalibur.ingest.registry.SourceCatalog.open(dataDirectory));

        // And how each of them was last crawled, so /v1/ingest/status after a restart reports
        // that crawl rather than zeros. Never fatal: a history that cannot be read is logged and
        // each source reports from its next run.
        srv.withSourceStatus(
            com.eonmux.excalibur.ingest.registry.SourceStatusFile.open(dataDirectory));

        // This enables /v1/ingest to trigger full document processing: chunking, embedding, storage
        attachIngestionPipeline(srv);

        // Erasure, wired after the pipeline because it removes what the pipeline wrote and needs
        // the chunk index the pipeline opened.
        registerDocumentErasure(srv);

        // The same registry the erasure service resolves documents through, read-only. It is
        // the only thing that knows which documents this node holds, and every id an erasure
        // request needs had to come from somewhere.
        srv.withDocumentRegistry(documentVisibilityTracker.getRegistry());

        // Both graph routes, whether or not a graph exists yet: a node that has ingested and not
        // built needs the build route, and one that builds later must start answering without a
        // restart.
        srv.withGraphSupport(graphTenants);
        LOGGER.info("Graph-RAG endpoints registered: /v1/graph/query, /v1/graph/build, "
            + "/v1/graph/build/status, /v1/graph/erasure, /v1/graph/namespaces "
            + "({} namespace(s) open)",
            graphTenants.openCount());

        LOGGER.info("HTTP server created");
        return srv;
    }

    /**
     * Attaches the ingestion half of the node and keeps hold of what it opened.
     *
     * <p>The chunk index and its erasure sweep are recorded on the application rather than left
     * with the wiring because they outlive the attachment by the whole life of the process, and
     * shutdown is the only thing that closes them. They are read again a few lines later, by the
     * erasure service, which is why this runs before that one.</p>
     *
     * @param srv The server being built
     */
    private void attachIngestionPipeline(ExcaliburDBServer srv) {
        IngestionWiring.Attachment ingestion = new IngestionWiring(config, dataDirectory,
            storageEngine, indexOrchestrator, hierarchicalFilter, documentVisibilityTracker,
            graphTenants).attachTo(srv);
        this.chunkIndex = ingestion.chunkIndex();
        this.lexicalErasureSweep = ingestion.lexicalErasureSweep();
    }

    /**
     * Registers {@code POST /v1/erasure}, whether or not this node has a lexical index.
     *
     * <p>A node with no chunk index still has content in storage that someone can ask to have
     * removed, so the route exists either way and the index is simply one more place the service
     * reaches into when there is one. Removing a document is three removals rather than one: the
     * text in storage, the terms in the lexical index, and the vectors - and without the last a
     * vector query still ranks the erased document, with no text, because the payload comes from
     * storage and storage no longer has it, but with its name and a distance, which is the fact
     * the request asked to remove.</p>
     *
     * @param srv The server being built
     */
    private void registerDocumentErasure(ExcaliburDBServer srv) {
        srv.withDocumentErasure(new com.eonmux.excalibur.ingest.erasure.DocumentErasureService(
            documentVisibilityTracker.getRegistry(),
            com.eonmux.excalibur.api.bootstrap.StorageChunkWriter.tombstoneAdapter(storageEngine),
            this.chunkIndex,
            com.eonmux.excalibur.ingest.lexical.StoredChunkText.of(storageEngine),
            documentVisibilityTracker,
            com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser.of(storageEngine)));
    }

    /**
     * Opens every namespace's graph, bounded by what this process may map.
     *
     * <p>Rooted at {@code excalibur.graph.path}, or the data directory when that is unset, with
     * one subdirectory per namespace and the shared namespace being the one a node that never
     * sees the header uses. The mapping budget is
     * detected once and governs how many graphs stay open at a time: a served namespace costs
     * dozens of mappings and a pair of descriptors, and a node with a graph per tenant will
     * otherwise exhaust the process's supply of either at whatever unrelated allocation happens
     * to come next.</p>
     *
     * @param dataDir The data directory
     * @return The tenant registry
     */
    private com.eonmux.excalibur.api.bootstrap.GraphTenants openGraphTenants(Path dataDir) {
        Path root = GraphRagFactory.tenantRootFor(config, dataDir);
        try {
            return new com.eonmux.excalibur.api.bootstrap.GraphTenants(
                root, config,
                com.eonmux.excalibur.tenancy.MappingBudget.detect(),
                config.graph().getMaxOpenNamespaces());
        } catch (IOException e) {
            throw new IllegalStateException("Graph tenants under " + root
                + " could not be opened: " + e.getMessage(), e);
        }
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
        // From the bound socket, not the configured port and a guessed host: a TLS listener
        // refuses the http URL, a node bound to one interface is not on localhost, and port 0
        // binds an ephemeral port the configuration never names.
        String baseUrl = server.baseUrl();
        LOGGER.info("Listening on: {}", baseUrl);
        LOGGER.info("Health endpoint: {}/health", baseUrl);
        LOGGER.info("Stats endpoint: {}/v1/stats", baseUrl);
        LOGGER.info("Query endpoint: {}/v1/query", baseUrl);
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

        // Ordering is a correctness property, not tidiness, and it is written down in
        // NodeShutdown next to the sequence that runs it: this method's job is only to make
        // sure it runs once, because run() is watching shutdownRequested to stop its own loop.
        new NodeShutdown(server, indexOrchestrator, lexicalErasureSweep, chunkIndex,
            documentVisibilityTracker, graphTenants, storageEngine, arena,
            shutdownTimeout).run();
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
     * <p>This factory creates {@link com.eonmux.excalibur.vector.hnsw.HnswConfig} instances
     * configured with values from the ShardConfig settings:</p>
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
     * Gets the hierarchical Cuckoo filter for corpus → segment membership.
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
