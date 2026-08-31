package excalibur.api.bootstrap;

import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.config.Config;
import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
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
 * @param indexOrchestrator  Orchestrator with the hierarchical filter attached
 * @param hierarchicalFilter Corpus/cluster/segment membership filter
 * @author ExcaliburDB
 */
public record CoreComponents(Arena arena,
                             StorageEngine storageEngine,
                             VolumeManager volumeManager,
                             IndexOrchestrator indexOrchestrator,
                             HierarchicalCuckooFilter hierarchicalFilter) {

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
        IndexOrchestrator indexOrchestrator = createIndexOrchestrator(config, storageEngine, volumeManager);
        HierarchicalCuckooFilter hierarchicalFilter = createHierarchicalCuckooFilter();
        indexOrchestrator.setHierarchicalCuckooFilter(hierarchicalFilter);

        wireCompactionCallback(storageEngine, indexOrchestrator);
        registerExistingSegmentsWithOrchestrator(storageEngine, indexOrchestrator);
        storageEngine.startLifecycleManager();

        return new CoreComponents(arena, storageEngine, volumeManager, indexOrchestrator, hierarchicalFilter);
    }

    /**
     * Creates the storage engine.
     *
     * @param dataDir Data directory path
     * @return Initialized storage engine
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

        // Install the HNSW initializer BEFORE start(). start() discovers existing
        // segments and pre-allocates the whole base keyspace; a segment created without
        // a graph carries no vector index, and a query pipeline whose graph provider
        // returns null for every segment returns nothing at all.
        engine.setHnswInitializer(createHnswInitializer(config, hnswConfigFactory, arena));

        engine.start();

        LOGGER.info("Storage engine initialized with {} segments", engine.getSegmentCount());
        return engine;
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
    private static IndexOrchestrator createIndexOrchestrator(Config config, StorageEngine storageEngine,
                                                             VolumeManager volumeManager) {
        LOGGER.info("Initializing index orchestrator...");

        SegmentCatalog catalog = storageEngine.getCatalog();

        IndexOrchestrator orchestrator = new IndexOrchestrator(
            catalog,
            storageEngine,
            volumeManager,
            // The shard key, the same one IndexOrchestrator.fromConfig and ExcaliburDBServer
            // build from: two keys describing one queue meant the limit depended on which path
            // constructed the engine.
            config.shard().getHnswQueueLimit(),
            IndexOrchestrator.DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS,
            IndexOrchestrator.DEFAULT_REINDEX_CHECK_INTERVAL_MS,
            config.visibility().getEpochTickMs()
        );

        // The configured delay before compaction's first check, matching what
        // IndexOrchestrator.fromConfig applies on the other construction path.
        orchestrator.withCompactionStartDelayMs(config.shard().getCompactDelayMs());

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
     *   <li>Cluster capacity: the corpus capacity, because every segment shares one cluster</li>
     *   <li>Segment capacity: 10,000 entries per segment</li>
     * </ul>
     *
     * @return Initialized hierarchical Cuckoo filter
     */
    private static HierarchicalCuckooFilter createHierarchicalCuckooFilter() {
        LOGGER.info("Initializing hierarchical Cuckoo filter...");

        // Use configurable corpus capacity or default to 10 million entries
        // This should be sized based on expected corpus size for optimal false positive rate
        long corpusCapacity = getLongProperty("excalibur.filter.corpus-capacity", 10_000_000L);

        // Default parameters tuned for ~0.1% false positive rate
        int fingerprintBits = 13;

        // Every segment is registered into a single "default" cluster, so the cluster level does
        // not partition anything: it holds the whole corpus's terms. Sized at a fraction of the
        // corpus it saturates first, and a level that cannot accept a term is a level that
        // denies it - which prunes every segment for that term and answers with nothing while
        // the documents sit in storage. Until clusters mean something, the cluster level is the
        // corpus level and is sized like it.
        int clusterCapacity = (int) Math.min(corpusCapacity, Integer.MAX_VALUE);
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
    private static long getLongProperty(String key, long defaultValue) {
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
    private static void wireCompactionCallback(StorageEngine storageEngine,
                                               IndexOrchestrator indexOrchestrator) {
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
     * Creates the HNSW initializer the storage engine attaches to every segment.
     *
     * <p>Uses the configuration from Config.Shard (efConstruction, efSearch) and the
     * vector dimension from Config.Query.</p>
     *
     * @return The initializer, to be installed before the engine starts
     */
    private static io.excalibur.store.HnswInitializer createHnswInitializer(Config config,
            HnswConfigFactory hnswConfigFactory, Arena arena) {
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

        LOGGER.info("HNSW initializer created: dimension={}, maxNodes={}",
            initializer.getDimension(), initializer.getMaxNodes());
        return initializer;
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
    private static void registerExistingSegmentsWithOrchestrator(StorageEngine storageEngine,
                                                                 IndexOrchestrator indexOrchestrator) {
        LOGGER.info("Registering existing segments with IndexOrchestrator...");

        int registeredCount = 0;

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
}
