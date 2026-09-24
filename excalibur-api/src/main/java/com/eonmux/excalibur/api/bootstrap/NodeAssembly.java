package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.ErasureReceiptFile;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * A node over one data directory, assembled and not yet started.
 *
 * <p>The one place a node is put together, used by both ways of running one: the server, which
 * binds a listener over it, and the embedded facade, which calls it in-process. Assembling it
 * twice is how the two come to disagree - about whether the document registry persists, whether
 * erasure reaches the vectors, which pipeline the atomicity filter was installed on - and the
 * embedder is the one who finds out, after a restart.</p>
 *
 * <h2>Order</h2>
 * <p>Each step needs what the one before it produced:</p>
 * <ol>
 *   <li>the storage stack ({@link CoreComponents}): arena, engine with its HNSW initializer,
 *       volume manager, index orchestrator;</li>
 *   <li>document visibility on the orchestrator's epoch clock, over the persisted registry;</li>
 *   <li>the query pipeline over the engine, the clock and that visibility;</li>
 *   <li>the graph tenants;</li>
 *   <li>the server, unstarted, with its security policy, the source catalog and status history,
 *       the ingestion pipeline, erasure, the registry read-back and graph support;</li>
 *   <li>the reindexer, which needs the chunk index ingestion opened.</li>
 * </ol>
 *
 * <p>If a step fails, what the earlier ones opened is released before the failure is thrown:
 * a process that retries in-place must not find the data directory still held by the attempt
 * that failed.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Built on one thread. The components it holds are the node's, and are as thread-safe as
 * each of them is.</p>
 */
public final class NodeAssembly {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeAssembly.class);

    /** What a server node calls itself in its data directory's lock file. */
    public static final String SERVER_LOCK_HOLDER = "excalibur node";

    /** What an embedded node calls itself in its data directory's lock file. */
    public static final String EMBEDDED_LOCK_HOLDER = "excalibur embedded node";

    /** How long a failed assembly waits for each worker it started to stop. */
    private static final Duration ABANDON_WAIT = Duration.ofSeconds(10);

    private final Config config;
    private final Path dataDirectory;
    private final HnswConfigFactory hnswConfigFactory;
    private final CoreComponents core;
    private final DocumentVisibilityTracker documentVisibilityTracker;
    private final DocumentVisibilityChecker documentVisibilityChecker;
    private final QueryPipeline queryPipeline;
    private final GraphTenants graphTenants;
    private final ExcaliburDBServer server;
    private final IngestionWiring.Attachment ingestion;

    /**
     * This node's exclusive claim on {@link #dataDirectory}, taken before anything in it was
     * read. Held in a field so the channel it was taken through stays reachable for as long as
     * the claim is meant to last.
     */
    private volatile DataDirectoryLock dataDirectoryLock;

    private NodeAssembly(Config config, Path dataDirectory, HnswConfigFactory hnswConfigFactory,
                         CoreComponents core, DocumentVisibilityTracker documentVisibilityTracker,
                         QueryPipeline queryPipeline, GraphTenants graphTenants,
                         ExcaliburDBServer server, IngestionWiring.Attachment ingestion) {
        this.config = config;
        this.dataDirectory = dataDirectory;
        this.hnswConfigFactory = hnswConfigFactory;
        this.core = core;
        this.documentVisibilityTracker = documentVisibilityTracker;
        this.documentVisibilityChecker =
            new DocumentVisibilityTrackerAdapter(documentVisibilityTracker);
        this.queryPipeline = queryPipeline;
        this.graphTenants = graphTenants;
        this.server = server;
        this.ingestion = ingestion;
    }

    /**
     * Assembles a node over an existing data directory.
     *
     * @param config   The validated configuration
     * @param dataDir  The data directory, which must exist
     * @param security The security policy the server's routes are guarded by
     * @param port     The port a listener would bind; 0 for an ephemeral one
     * @param holder   What the data directory's lock file names as holding it, such as
     *                 {@link #SERVER_LOCK_HOLDER}
     * @return The node, with nothing started that a caller has to start, holding the claim on
     *         its data directory
     * @throws com.eonmux.excalibur.core.datadir.DataDirectoryLockedException if another process,
     *                                  or another node in this one, holds the directory
     * @throws IOException              if the directory cannot be claimed, or a restore was
     *                                  interrupted there
     * @throws IllegalStateException    if a component cannot be opened; everything opened
     *                                  before it, the claim included, has been released
     * @throws NullPointerException     if an argument is null
     */
    public static NodeAssembly open(Config config, Path dataDir, ApiSecurityConfig security,
                                    int port, String holder) throws IOException {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(dataDir, "dataDir cannot be null");
        Objects.requireNonNull(security, "security cannot be null");
        Objects.requireNonNull(holder, "holder cannot be null");

        // Before anything in the directory is read: a second node is refused here rather than
        // after both have mapped its segments.
        DataDirectoryLock lock = DataDirectoryClaim.claim(dataDir, holder);
        try {
            NodeAssembly node = assembleUnder(config, dataDir, security, port);
            node.dataDirectoryLock = lock;
            return node;
        } catch (RuntimeException e) {
            DataDirectoryClaim.release(lock, e);
            throw e;
        }
    }

    private static NodeAssembly assembleUnder(Config config, Path dataDir,
                                              ApiSecurityConfig security, int port) {

        // Built from the shard settings so the properties file's efConstruction and efSearch are
        // what the graphs are built and searched with.
        HnswConfigFactory hnswConfigFactory = HnswConfigFactory.fromShardConfig(config.shard());
        LOGGER.info("HNSW config factory initialized: efConstruction={}, efSearch={}",
            hnswConfigFactory.getEfConstruction(), hnswConfigFactory.getEfSearch());

        CoreComponents core = CoreComponents.create(config, dataDir, hnswConfigFactory);
        Opened opened = new Opened(core);
        try {
            return assemble(config, dataDir, security, port, hnswConfigFactory, core, opened);
        } catch (RuntimeException e) {
            opened.release(e);
            throw e;
        }
    }

    private static NodeAssembly assemble(Config config, Path dataDir, ApiSecurityConfig security,
                                         int port, HnswConfigFactory hnswConfigFactory,
                                         CoreComponents core, Opened opened) {
        // Document visibility, on the node's own epoch clock: publishing a document on the same
        // counter the query pipeline captures from is what lets the atomicity filter bound by the
        // query's snapshot instead of only checking completeness. Persisted, because the
        // registry is what says which documents this node holds and under which chunk ids. Lost
        // on restart, every document reads as new to change detection - so a re-crawl reingests
        // the whole corpus and tombstones none of the old chunks - and erasure can no longer name
        // the chunks of anything ingested before the restart.
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            DocumentRegistryFactory.open(dataDir),
            core.indexOrchestrator().getVisibilityTracker());
        LOGGER.info("Document visibility tracker initialized on the node epoch clock");

        QueryPipeline queryPipeline = QueryPipelineFactory.create(config, core.storageEngine(),
            core.indexOrchestrator(), new DocumentVisibilityTrackerAdapter(tracker));
        opened.queryPipeline = queryPipeline;

        // The build side of the graph, per namespace. A node that never sees the namespace
        // header runs with exactly one, called shared, which is the single-tenant arrangement.
        GraphTenants graphTenants = openGraphTenants(config, dataDir);
        opened.graphTenants = graphTenants;

        ExcaliburDBServer server = new ExcaliburDBServer(port, ExcaliburDBServer.DEFAULT_BACKLOG,
            core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
            queryPipeline);
        server.withSecurity(security);
        opened.server = server;

        // Registrations are written down before the endpoints read them back, so a node that is
        // restarted keeps crawling what it was asked to crawl. Fatal if the file exists and
        // cannot be read: starting with an empty registry would silently cancel every source.
        server.withSourceCatalog(SourceCatalog.open(dataDir));
        // And how each of them was last crawled, so the status after a restart reports that
        // crawl rather than zeros. Never fatal: a history that cannot be read is logged and each
        // source reports from its next run.
        server.withSourceStatus(SourceStatusFile.open(dataDir));

        IngestionWiring.Attachment ingestion = new IngestionWiring(config, dataDir,
            core.storageEngine(), core.indexOrchestrator(), tracker, graphTenants)
            .attachTo(server);
        opened.ingestion = ingestion;

        // Erasure, wired after the pipeline because it removes what the pipeline wrote and needs
        // the chunk index the pipeline opened. Three removals rather than one: the text in
        // storage, the terms in the lexical index, and the vectors - without the last a vector
        // query still ranks the erased document by name and distance.
        server.withDocumentErasure(new DocumentErasureService(
            tracker.getRegistry(),
            StorageChunkWriter.tombstoneAdapter(core.storageEngine()),
            ingestion.chunkIndex(),
            StoredChunkText.of(core.storageEngine()),
            tracker,
            SegmentVectorEraser.of(core.storageEngine())));
        // Beside the source catalog, so an erasure's caller can still ask about it after a
        // restart; see GET /v1/erasure/{request_id}.
        server.withErasureReceipts(ErasureReceiptFile.open(dataDir));

        // The same registry the erasure service resolves documents through, read-only.
        server.withDocumentRegistry(tracker.getRegistry());

        // Both graph routes, whether or not a graph exists yet: a node that has ingested and not
        // built needs the build route, and one that builds later must start answering without a
        // restart.
        server.withGraphSupport(graphTenants);
        LOGGER.info("Graph-RAG support attached ({} namespace(s) open)", graphTenants.openCount());

        // After the server exists, because it is handed to the server, and after ingestion,
        // because it keeps the chunk index ingestion opened in step with what it rewrites.
        server.withReindexer(ReindexerFactory.create(config, core.storageEngine(),
            core.volumeManager(), core.indexOrchestrator(), tracker.getRegistry(),
            ingestion.chunkIndex()));
        LOGGER.info("Document reindexer configured");

        return new NodeAssembly(config, dataDir, hnswConfigFactory, core, tracker, queryPipeline,
            graphTenants, server, ingestion);
    }

    /**
     * Opens every namespace's graph, bounded by what this process may map.
     *
     * <p>Rooted at {@code excalibur.graph.path}, or the data directory when that is unset. The
     * mapping budget is detected once and governs how many graphs stay open at a time: a served
     * namespace costs dozens of mappings and a pair of descriptors, and a node with a graph per
     * tenant would otherwise exhaust the process's supply of either at whatever unrelated
     * allocation happens to come next.</p>
     */
    private static GraphTenants openGraphTenants(Config config, Path dataDir) {
        Path root = GraphRagFactory.tenantRootFor(config, dataDir);
        try {
            return new GraphTenants(root, config, MappingBudget.detect(),
                config.graph().getMaxOpenNamespaces());
        } catch (IOException e) {
            throw new IllegalStateException("Graph tenants under " + root
                + " could not be opened: " + e.getMessage(), e);
        }
    }

    /**
     * The shutdown of this node, in the order its components depend on each other.
     *
     * @param shutdownTimeout The deadline for the whole shutdown
     * @return The sequence, which the caller runs once
     * @throws IllegalArgumentException if the deadline leaves nothing past the durability
     *                                  reserve
     */
    public NodeShutdown shutdown(Duration shutdownTimeout) {
        return new NodeShutdown(server, core.indexOrchestrator(), ingestion.lexicalErasureSweep(),
            ingestion.chunkIndex(), documentVisibilityTracker, graphTenants,
            core.storageEngine(), core.arena(), shutdownTimeout);
    }

    /**
     * Records in the data directory's lock file where this node is listening.
     *
     * <p>Read by the container's health check, which has only the environment to go on and
     * would otherwise have to reconstruct the scheme, the port and the address from
     * configuration it cannot see and get one of them wrong.</p>
     *
     * @param listening The base URL the listener bound
     * @param health    The liveness probe's URL
     * @throws IOException if the lock file cannot be rewritten
     * @throws IllegalStateException if the claim has been released
     */
    public void advertise(String listening, String health) throws IOException {
        DataDirectoryLock lock = dataDirectoryLock;
        if (lock == null) {
            throw new IllegalStateException("the data directory claim has been released");
        }
        lock.advertise(listening, health);
    }

    /**
     * Gives up the claim on the data directory, once the node is taken apart.
     *
     * <p>Only after a shutdown that closed the storage engine: one that left work running also
     * left files being written, and releasing the claim then would let a second node in while
     * this one could still be writing. A server never calls this - the operating system
     * releases the claim when the process exits - but an embedded node is closed and reopened
     * inside one process, and the second open needs the claim back. Idempotent.</p>
     *
     * @throws IOException if the lock cannot be released
     */
    public void releaseDataDirectory() throws IOException {
        DataDirectoryLock lock = dataDirectoryLock;
        dataDirectoryLock = null;
        if (lock != null) {
            lock.close();
        }
    }

    /** @return The configuration the node was assembled from */
    public Config config() {
        return config;
    }

    /** @return The data directory the node keeps its files in */
    public Path dataDirectory() {
        return dataDirectory;
    }

    /** @return The HNSW settings every segment's graph is built with */
    public HnswConfigFactory hnswConfigFactory() {
        return hnswConfigFactory;
    }

    /** @return The shared arena the segments are mapped into, or null on the fallback */
    public Arena arena() {
        return core.arena();
    }

    /** @return The storage engine */
    public StorageEngine storageEngine() {
        return core.storageEngine();
    }

    /** @return The volume manager */
    public VolumeManager volumeManager() {
        return core.volumeManager();
    }

    /** @return The index orchestrator, which owns the visibility clock */
    public IndexOrchestrator indexOrchestrator() {
        return core.indexOrchestrator();
    }

    /** @return Which documents are visible, over the persisted registry */
    public DocumentVisibilityTracker documentVisibilityTracker() {
        return documentVisibilityTracker;
    }

    /** @return The tracker as the query pipeline's atomicity filter reads it */
    public DocumentVisibilityChecker documentVisibilityChecker() {
        return documentVisibilityChecker;
    }

    /** @return The query pipeline as it was created, before ingestion wrapped it */
    public QueryPipeline queryPipeline() {
        return queryPipeline;
    }

    /** @return The graph tenants */
    public GraphTenants graphTenants() {
        return graphTenants;
    }

    /** @return The server, unstarted until its owner starts it */
    public ExcaliburDBServer server() {
        return server;
    }

    /** @return The chunk lexical index, or null on a node that could not open one */
    public ChunkIndex chunkIndex() {
        return ingestion.chunkIndex();
    }

    /** @return The lexical erasure sweep, or null alongside a null chunk index */
    public LexicalErasureSweep lexicalErasureSweep() {
        return ingestion.lexicalErasureSweep();
    }

    /**
     * What a partly assembled node has opened, released in reverse if assembly fails.
     *
     * <p>Nothing has been started that a caller starts - the orchestrator's workers and the
     * listener start later - but the storage engine has mapped its segments and started its
     * lifecycle checks, the pipeline and ingestion own executors, and the chunk index holds its
     * files. Each release is attempted whatever the one before it did, and every failure is
     * attached to the one that stopped assembly rather than replacing it.</p>
     */
    private static final class Opened {

        private final CoreComponents core;
        private QueryPipeline queryPipeline;
        private GraphTenants graphTenants;
        private ExcaliburDBServer server;
        private IngestionWiring.Attachment ingestion;

        Opened(CoreComponents core) {
            this.core = core;
        }

        void release(RuntimeException failure) {
            Deque<Runnable> steps = new ArrayDeque<>();
            IngestionOrchestrator orchestrator = server == null ? null
                : server.getIngestionOrchestrator();
            if (orchestrator != null) {
                steps.add(() -> orchestrator.shutdown(ABANDON_WAIT));
            }
            if (queryPipeline != null) {
                steps.add(() -> queryPipeline.shutdown(ABANDON_WAIT));
            }
            if (ingestion != null && ingestion.lexicalErasureSweep() != null) {
                steps.add(() -> ingestion.lexicalErasureSweep().close());
            }
            if (ingestion != null && ingestion.chunkIndex() != null) {
                steps.add(() -> ingestion.chunkIndex().close());
            }
            if (graphTenants != null) {
                steps.add(() -> graphTenants.close(ABANDON_WAIT));
            }
            steps.add(() -> core.indexOrchestrator().close(ABANDON_WAIT));
            steps.add(() -> {
                if (core.storageEngine().stopBackgroundWork(ABANDON_WAIT)) {
                    core.storageEngine().close();
                    if (core.arena() != null) {
                        core.arena().close();
                    }
                } else {
                    // Closing under a worker that is still reading a mapping ends the process
                    // rather than failing it; leaving the engine open is the lesser harm.
                    LOGGER.error("Storage background work did not stop after a failed assembly; "
                        + "the storage engine is left open");
                }
            });
            for (Runnable step : steps) {
                try {
                    step.run();
                } catch (RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
        }
    }
}
