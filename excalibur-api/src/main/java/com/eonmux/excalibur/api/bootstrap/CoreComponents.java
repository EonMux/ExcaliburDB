package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
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
 * @param indexOrchestrator  Orchestrator with the hierarchical filter attached
 * @param hierarchicalFilter Corpus/segment membership filter
 * @author ExcaliburDB
 */
public record CoreComponents(Arena arena,
                             StorageEngine storageEngine,
                             VolumeManager volumeManager,
                             IndexOrchestrator indexOrchestrator,
                             HierarchicalCuckooFilter hierarchicalFilter) {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoreComponents.class);

    /** Property sizing the membership filter against the expected corpus. */
    public static final String KEY_CORPUS_CAPACITY = "excalibur.filter.corpus-capacity";

    /** Entries the membership filter is sized for when nothing says otherwise. */
    public static final long DEFAULT_CORPUS_CAPACITY = 10_000_000L;

    /** Property sizing each segment's own level of the membership filter. */
    public static final String KEY_SEGMENT_CAPACITY = "excalibur.filter.segment-capacity";

    /** Distinct terms one segment's filter is sized for when nothing says otherwise. */
    public static final int DEFAULT_SEGMENT_CAPACITY = 10_000;

    /** Property setting how many bits of each term the membership filter keeps. */
    public static final String KEY_FINGERPRINT_BITS = "excalibur.filter.fingerprint-bits";

    /** Bits kept per term when nothing says otherwise, for roughly a 0.1% false positive rate. */
    public static final int DEFAULT_FINGERPRINT_BITS = 13;

    /** Fewest bits a fingerprint may be kept at; the filter's slot format holds no fewer. */
    public static final int MIN_FINGERPRINT_BITS = 8;

    /** Most bits a fingerprint may be kept at. */
    public static final int MAX_FINGERPRINT_BITS = 16;

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

        // Resolved before anything is allocated. Every other misconfiguration in this server
        // fails before the resource it would govern exists -- the port before the socket, the
        // keystore before the listener -- and a value rejected halfway through leaves an open
        // Arena and a started storage engine behind with nothing left holding them, at a point
        // where the shutdown hook is not registered yet and storage closes non-durably.
        long corpusCapacity = corpusCapacity(config);
        int segmentCapacity = segmentCapacity(config);
        int fingerprintBits = fingerprintBits(config);
        if (segmentCapacity > corpusCapacity) {
            // The corpus level holds every segment's terms; a segment level larger than it would
            // also break the hashing relationship that makes a delete safe between them.
            throw new IllegalArgumentException(KEY_SEGMENT_CAPACITY + " (" + segmentCapacity
                + ") must not exceed " + KEY_CORPUS_CAPACITY + " (" + corpusCapacity + ")");
        }

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
        HierarchicalCuckooFilter hierarchicalFilter =
            createHierarchicalCuckooFilter(corpusCapacity, segmentCapacity, fingerprintBits);
        indexOrchestrator.setHierarchicalCuckooFilter(hierarchicalFilter);

        wireCompactionCallback(storageEngine, indexOrchestrator);
        registerExistingSegmentsWithOrchestrator(storageEngine, indexOrchestrator);
        storageEngine.startLifecycleManager();

        return new CoreComponents(arena, storageEngine, volumeManager, indexOrchestrator, hierarchicalFilter);
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
     * Creates the hierarchical Cuckoo filter for corpus → segment membership.
     *
     * <p>The hierarchical filter enables efficient segment pruning during queries
     * and proper deletion propagation through all filter levels when documents
     * are tombstoned.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Corpus capacity: {@value #DEFAULT_CORPUS_CAPACITY} entries, or
     *       {@code excalibur.filter.corpus-capacity}</li>
     *   <li>Fingerprint bits: {@value #DEFAULT_FINGERPRINT_BITS}, or
     *       {@code excalibur.filter.fingerprint-bits}</li>
     *   <li>Segment capacity: {@value #DEFAULT_SEGMENT_CAPACITY} distinct terms, or
     *       {@code excalibur.filter.segment-capacity}</li>
     * </ul>
     *
     * @param corpusCapacity  Entries the corpus level is sized for, already validated
     * @param segmentCapacity Distinct terms each segment's level is sized for, already validated
     * @param fingerprintBits Bits kept per term, already validated
     * @return Initialized hierarchical Cuckoo filter
     */
    private static HierarchicalCuckooFilter createHierarchicalCuckooFilter(long corpusCapacity,
                                                                           int segmentCapacity,
                                                                           int fingerprintBits) {
        LOGGER.info("Initializing hierarchical Cuckoo filter...");

        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(
            corpusCapacity,
            fingerprintBits,
            segmentCapacity
        );

        LOGGER.info("Hierarchical Cuckoo filter initialized: corpusCapacity={}, fingerprintBits={}, segmentCapacity={}",
            corpusCapacity, fingerprintBits, segmentCapacity);

        return filter;
    }

    /**
     * Resolves the expected corpus size the membership filter is sized against.
     *
     * <p>Read off the effective properties, so the file and the {@code EXCALIBUR_*} environment
     * override reach it the way they reach every other key. It was previously read from
     * {@link System#getProperty}, which no other setting in this server uses: the key looked
     * ordinary, appeared in the same namespace as the rest, and responded to neither of the two
     * channels the documentation describes. A deployment that raised it in
     * {@code excalibur.properties} got the default silently.</p>
     *
     * @param config The application configuration
     * @return The configured capacity, or {@link #DEFAULT_CORPUS_CAPACITY}
     * @throws IllegalArgumentException if a value is configured and is not a positive number
     */
    private static long corpusCapacity(Config config) {
        return positiveCapacity(config, KEY_CORPUS_CAPACITY, DEFAULT_CORPUS_CAPACITY);
    }

    /**
     * Resolves the distinct terms one segment's level of the filter is sized against.
     *
     * <p>Separate from the corpus capacity because the two answer different questions: the
     * corpus level holds every term on the node, a segment level holds one segment's
     * vocabulary. A segment that outgrows its level keeps answering correctly and stops being
     * pruned, which shows up as {@code cuckoo.saturatedSegments} on {@code /v1/stats} and as
     * latency. This is the knob that responds to it.</p>
     *
     * @param config The application configuration
     * @return The configured capacity, or {@link #DEFAULT_SEGMENT_CAPACITY}
     * @throws IllegalArgumentException if a value is configured and is not a positive number
     *                                  that fits an int
     */
    private static int segmentCapacity(Config config) {
        long parsed = positiveCapacity(config, KEY_SEGMENT_CAPACITY, DEFAULT_SEGMENT_CAPACITY);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(KEY_SEGMENT_CAPACITY + " must be at most "
                + Integer.MAX_VALUE + "; got: " + parsed);
        }
        return (int) parsed;
    }

    /**
     * Resolves how many bits of each term the membership filter keeps.
     *
     * <p>This is the accuracy-for-memory trade the filter makes. Each extra bit halves the
     * chance that two unrelated terms look alike, and so halves the segments a query scans for
     * nothing; each costs one more bit per slot, and the filter has about twice as many slots
     * as the corpus capacity. Below the floor the filter stops ruling anything out; above the
     * ceiling a fingerprint no longer fits the slot the format gives it.</p>
     *
     * @param config The application configuration
     * @return The configured bit width, or {@link #DEFAULT_FINGERPRINT_BITS}
     * @throws IllegalArgumentException if a value is configured and is outside the range
     */
    private static int fingerprintBits(Config config) {
        String value = config.getEffectiveProperties().get(KEY_FINGERPRINT_BITS);
        if (value == null || value.isBlank()) {
            return DEFAULT_FINGERPRINT_BITS;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                KEY_FINGERPRINT_BITS + " must be a whole number, got: " + value, e);
        }
        if (parsed < MIN_FINGERPRINT_BITS || parsed > MAX_FINGERPRINT_BITS) {
            throw new IllegalArgumentException(KEY_FINGERPRINT_BITS + " must be between "
                + MIN_FINGERPRINT_BITS + " and " + MAX_FINGERPRINT_BITS + ", got: " + parsed);
        }
        return parsed;
    }

    /**
     * Reads one capacity key, refusing a value that cannot be one.
     *
     * @param config       The application configuration
     * @param key          The property key
     * @param fallback     What an absent or blank value means
     * @return The configured capacity, or the fallback
     * @throws IllegalArgumentException if a value is configured and is not a positive number
     */
    private static long positiveCapacity(Config config, String key, long fallback) {
        String value = config.getEffectiveProperties().get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key
                + " must be a positive number of entries; got: " + value, e);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive; got: " + parsed);
        }
        return parsed;
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
