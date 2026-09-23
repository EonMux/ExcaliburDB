package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.shard.allocator.VolumePlacements;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.QueryConfig;
import com.eonmux.excalibur.config.ShardConfig;
import com.eonmux.excalibur.core.memory.MemorySegmentFactory;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentLifecycleManager;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.DistanceFunctions;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The storage-side components every other part of the server is built on.
 *
 * <p>Grouping them here keeps one ordering constraint in one place: the HNSW
 * initializer must be installed <em>before</em> {@link StorageEngine#start()}, because
 * start() discovers and pre-allocates the whole base keyspace. A segment created
 * without a graph carries no vector index, and a query pipeline whose graph provider
 * returns null for every segment returns nothing at all.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>The record is immutable; the components it holds carry their own guarantees.</p>
 *
 * @param arena              Shared arena backing memory-mapped segments, or null on the
 *                           ByteBuffer fallback
 * @param storageEngine      The started storage engine
 * @param volumeManager      Volume manager registered with the data directory
 * @param indexOrchestrator  Orchestrator tracking every registered segment
 * @author ExcaliburDB
 */
public record CoreComponents(Arena arena,
                             StorageEngine storageEngine,
                             VolumeManager volumeManager,
                             IndexOrchestrator indexOrchestrator) {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoreComponents.class);

    /**
     * Builds and starts the storage stack.
     *
     * @param config            Application configuration
     * @param dataDir           Data directory for segments and the catalog
     * @param hnswConfigFactory Factory supplying efConstruction/efSearch for new graphs
     * @return The wired components
     */
    public static CoreComponents create(Config config, Path dataDir, HnswConfigFactory hnswConfigFactory) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(dataDir, "dataDir cannot be null");
        Objects.requireNonNull(hnswConfigFactory, "hnswConfigFactory cannot be null");

        boolean panamaAvailable = MemorySegmentFactory.isPanamaAvailable();
        Arena arena = panamaAvailable ? Arena.ofShared() : null;
        LOGGER.info("Using {} memory implementation",
            panamaAvailable ? "Panama FFM" : "ByteBuffer fallback");

        StorageEngine storageEngine = createStorageEngine(config, dataDir, arena, hnswConfigFactory);
        VolumeManager volumeManager = createVolumeManager(storageEngine);

        // Before start(). Start is where the engine scans for what is on disk and creates
        // whatever the base keyspace is missing, and both depend on knowing the volume set;
        // a placement installed afterwards would have it scan one set of directories and
        // write to another.
        VolumePlacements.install(storageEngine, volumeManager, config.shard().getVolumes());
        startStorageEngine(storageEngine);

        IndexOrchestrator indexOrchestrator =
            createIndexOrchestrator(config, dataDir, storageEngine, volumeManager);
        shareTheVisibilityClock(storageEngine, indexOrchestrator);

        wireCompactionCallback(storageEngine, indexOrchestrator);
        registerExistingSegmentsWithOrchestrator(storageEngine, indexOrchestrator);
        storageEngine.startLifecycleManager();

        return new CoreComponents(arena, storageEngine, volumeManager, indexOrchestrator);
    }

    /**
     * Creates the storage engine.
     *
     * <p>The engine is configured but not started: {@link #create} installs the segment
     * placement first, and start() is where the engine scans the directories that placement
     * names.</p>
     *
     * @param dataDir Data directory path
     * @return Configured storage engine, not yet started
     */
    private static StorageEngine createStorageEngine(Config config, Path dataDir, Arena arena,
                                                    HnswConfigFactory hnswConfigFactory) {
        LOGGER.info("Initializing storage engine...");

        // Use fallback constructor when Arena is null (ByteBuffer-based implementation)
        // Use Arena constructor when Panama FFM is available
        StorageEngine engine = (arena != null)
            ? new StorageEngine(dataDir, arena)
            : new StorageEngine(dataDir);

        // excalibur.shard.segment-load-factor was documented but reached nothing: the store has no
        // config dependency, so the value has to be handed to it here. Set before start(), which
        // pre-allocates the base keyspace.
        engine.withLoadFactorThreshold(config.shard().getLoadFactor());

        // Also before start(): a limit is a statement about the directory the engine opens, and
        // the engine refuses one set after it is serving.
        engine.withMaxRoutingWidth(config.shard().getMaxRoutingWidth());

        // Install the HNSW initializer BEFORE start(). start() discovers existing
        // segments and pre-allocates the whole base keyspace; a segment created without
        // a graph carries no vector index, and a query pipeline whose graph provider
        // returns null for every segment returns nothing at all.
        engine.setHnswInitializer(createHnswInitializer(config, hnswConfigFactory, arena));

        return engine;
    }

    /**
     * Starts the storage engine, once everything start() depends on has been installed.
     *
     * @param engine The configured, not yet started engine
     */
    private static void startStorageEngine(StorageEngine engine) {
        engine.start();
        LOGGER.info("Storage engine initialized with {} segments", engine.getSegmentCount());
    }

    /**
     * Creates the volume manager.
     *
     * @return Initialized volume manager
     */
    private static VolumeManager createVolumeManager(StorageEngine storageEngine) {
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
    private static IndexOrchestrator createIndexOrchestrator(Config config, Path dataDir,
                                                             StorageEngine storageEngine,
                                                             VolumeManager volumeManager) {
        LOGGER.info("Initializing index orchestrator...");

        SegmentCatalog catalog = storageEngine.getCatalog();

        // The catalog has been recovered by now, so every epoch this node's durable records
        // carry is readable. Starting the counter below them hides the corpus and revives
        // tombstones, both without an error anywhere.
        long resumeEpoch = DurableEpochFloor.resumeEpoch(dataDir, catalog);
        if (resumeEpoch > 0) {
            LOGGER.info("Visibility epoch resumes at {}, above every epoch the durable stores "
                + "already carry", resumeEpoch);
        }

        // Every shard setting through the shard module's own translation, so none of them can
        // be parsed, validated and then left out of a hand-written argument list. Volume
        // placement is the exception, installed before the engine started.
        IndexOrchestrator orchestrator = IndexOrchestrator.forStartedEngine(
            catalog,
            storageEngine,
            volumeManager,
            config.shard(),
            config.visibility().getEpochTickMs(),
            resumeEpoch);

        LOGGER.info("Index orchestrator initialized");
        return orchestrator;
    }

    /**
     * Wires the compaction callback from the lifecycle manager to the compaction worker.
     *
     * <p>The lifecycle manager decides which segments hold enough dead space to be worth
     * rewriting; the compaction worker does the rewriting. Nothing is wired when the
     * orchestrator has no compaction worker, so the lifecycle manager keeps whatever
     * disposition it already had for those segments rather than being told they were
     * handled.</p>
     */
    private static void wireCompactionCallback(StorageEngine storageEngine,
                                               IndexOrchestrator indexOrchestrator) {
        var compactionWorker = indexOrchestrator.getCompactionWorker();
        if (compactionWorker == null) {
            LOGGER.warn("No compaction worker available; segments needing compaction will not be "
                + "rewritten in the background");
            return;
        }

        LOGGER.info("Wiring compaction callback...");

        storageEngine.getLifecycleManager()
            .setCompactionCallback(compactionCallback(storageEngine, compactionWorker));

        LOGGER.info("Compaction callback wired successfully");
    }

    /**
     * Builds the callback the lifecycle manager asks for a rewrite through.
     *
     * <p>The manager records a segment as handled on what this returns and stops asking, so it
     * returns scheduled only when the worker actually took the rewrite. A worker that has been
     * stopped refuses the request; reporting that as scheduled would strand the segment's
     * tombstoned space with nothing left that will ask for it again.</p>
     *
     * @param storageEngine    The engine the segment id is resolved against
     * @param compactionWorker The worker the rewrite is dispatched to
     * @return The callback, which reports whether the rewrite was scheduled
     */
    static SegmentLifecycleManager.CompactionCallback compactionCallback(
            StorageEngine storageEngine, CompactionWorker compactionWorker) {
        return segmentId -> {
            var segment = storageEngine.getSegment(segmentId);
            if (segment == null) {
                LOGGER.warn("Compaction callback received for unknown segment: {}", segmentId);
                return false;
            }

            boolean scheduled = compactionWorker.requestCompaction(segment);
            if (scheduled) {
                LOGGER.debug("Compaction requested for segment {} via lifecycle callback", segmentId);
            } else {
                LOGGER.warn("Compaction worker refused the rewrite of segment {}; the segment "
                    + "keeps its tombstoned space", segmentId);
            }
            return scheduled;
        };
    }

    /**
     * Creates the HNSW initializer the storage engine attaches to every segment.
     *
     * <p>Uses the configuration from ShardConfig (efConstruction, efSearch) and the
     * vector dimension from QueryConfig.</p>
     *
     * @return The initializer, to be installed before the engine starts
     */
    private static com.eonmux.excalibur.store.HnswInitializer createHnswInitializer(Config config,
            HnswConfigFactory hnswConfigFactory, Arena arena) {
        // Get vector dimensions from config (default to 1536 for OpenAI embeddings)
        int dimension = config.query().getVectorDimensions();
        if (dimension <= 0) {
            dimension = com.eonmux.excalibur.store.HnswInitializer.DEFAULT_VECTOR_DIMENSION;
        }

        // Create HNSW initializer with configuration from ShardConfig
        com.eonmux.excalibur.store.HnswInitializer initializer =
            new com.eonmux.excalibur.store.HnswInitializer(
                hnswConfigFactory,
                DistanceFunctions.byName(config.query().getDistanceMetric()),
                dimension,
                com.eonmux.excalibur.store.HnswInitializer.DEFAULT_MAX_NODES,
                arena  // May be null for ByteBuffer fallback
            );

        LOGGER.info("HNSW initializer created: dimension={}, maxNodes={}, metric={}",
            initializer.getDimension(), initializer.getMaxNodes(),
            config.query().getDistanceMetric());
        return initializer;
    }

    /**
     * Puts the storage engine and the index orchestrator on one visibility clock.
     *
     * <p>The engine starts on a local counter of its own, because it is built before anything
     * that could coordinate one. Left there, a node runs two clocks: the engine advances its own
     * on every write-log flush and stamps it into the catalog's tombstone epochs, while queries,
     * ingestion and the lexical store read the orchestrator's, which advances on a timer. The
     * two numbers are then compared to each other as though they meant the same thing, and
     * whether a tombstoned segment is visible depends on which counter happened to be ahead.</p>
     */
    private static void shareTheVisibilityClock(StorageEngine storageEngine,
                                                IndexOrchestrator indexOrchestrator) {
        storageEngine.setVisibilityTracker(indexOrchestrator.getVisibilityTracker());
    }

    /**
     * Registers every segment opened at startup with the IndexOrchestrator.
     *
     * <p>A segment left unregistered here would be integrated into no graph, compacted by
     * nobody and invisible to epoch-based visibility until something else happened to
     * register it. So every segment is registered, as the
     * orchestrator's own reconciliation with the directory does; one without an HNSW graph or
     * tail tracker is registered with none, and the HNSW workers are simply not told of it.</p>
     */
    private static void registerExistingSegmentsWithOrchestrator(StorageEngine storageEngine,
                                                                 IndexOrchestrator indexOrchestrator) {
        LOGGER.info("Registering existing segments with IndexOrchestrator...");

        int registeredCount = 0;
        int withGraph = 0;

        // getSegments() returns the DISTINCT segments keyed by their canonical index.
        // Walking 0..getSegmentCount() instead walks the routing directory width, which
        // is neither the segment id range nor free of buddy-pointer duplicates.
        for (Map.Entry<Long, Segment> entry : storageEngine.getSegments().entrySet()) {
            long segmentId = entry.getKey();
            Segment segment = entry.getValue();
            if (segment == null) {
                continue;
            }

            HnswGraph graph = segment.getHnswGraph();
            TailOffsetTracker tracker = segment.getTailOffsetTracker();
            indexOrchestrator.registerSegment(
                segmentId,
                segment,
                graph,
                tracker
            );
            registeredCount++;
            if (graph != null && tracker != null) {
                withGraph++;
            }
            LOGGER.debug("Registered segment {} with IndexOrchestrator", segmentId);
        }

        LOGGER.info("Registered {} segments with IndexOrchestrator, {} of them for HNSW "
            + "integration", registeredCount, withGraph);
    }
}
