package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.shard.allocator.VolumePlacements;
import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.shard.worker.CuckooMaintenanceWorker;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.shard.worker.HnswCompactionWorker;
import com.eonmux.excalibur.shard.worker.HnswIntegrationWorker;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.shard.worker.WarmingPhaseWorker;
import com.eonmux.excalibur.vector.quantization.PQConfig;
import com.eonmux.excalibur.config.ShardConfig;
import com.eonmux.excalibur.store.CompactionOutcome;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.SplitWorker;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Coordinates all background index workers for ExcaliburDB.
 *
 * <p>The IndexOrchestrator manages the lifecycle of all background workers
 * that perform asynchronous index operations:</p>
 * <ul>
 *   <li>{@link HnswIntegrationWorker} - Integrates write log entries into HNSW graph</li>
 *   <li>{@link CuckooMaintenanceWorker} - Maintains Cuckoo filter consistency</li>
 *   <li>{@link CuckooMetricsWorker} - Monitors and records Cuckoo filter FP rates</li>
 *   <li>{@link SegmentReindexScheduler} - Schedules stale segment re-indexing</li>
 *   <li>{@link EpochTickScheduler} - Advances visibility epoch periodically</li>
 *   <li>{@link HnswCompactionWorker} - Compacts HNSW graphs by removing tombstoned nodes</li>
 * </ul>
 *
 * <h2>Segment Registry</h2>
 * <p>Registrations are not a boot-time snapshot. The storage engine creates segments
 * lazily and splits them under load, so the orchestrator reconciles its registrations
 * with the engine's directory on a periodic sweep ({@link #syncSegmentsFromStorage()}),
 * and repairs them immediately when it is told about a
 * {@linkplain #onSplitComplete(Segment, Segment, Segment) split} or a compaction.</p>
 *
 * <h2>Visibility Tracking</h2>
 * <p>The orchestrator coordinates visibility across segments using {@link VisibilityTracker}.
 * Each query captures the current epoch at start time and sees a consistent segment
 * snapshot throughout its execution.</p>
 *
 * <h2>Worker Coordination</h2>
 * <p>The order the workers come up and go down in is owned by {@code ShardWorkers}, which is
 * where it is justified. {@link VisibilityTracker} is not in it: it is a counter with no
 * lifecycle, and it is {@link EpochTickScheduler} that advances it.</p>
 * <ol>
 *   <li>Start: EpochTickScheduler → CuckooMaintenanceWorker → CuckooMetricsWorker →
 *       HnswIntegrationWorker → WarmingPhaseWorker → HnswCompactionWorker →
 *       SegmentReindexScheduler → CompactionWorker, then the reconciliation sweep</li>
 *   <li>Stop: the reconciliation sweep, then exactly the reverse - CompactionWorker →
 *       SegmentReindexScheduler → HnswCompactionWorker → WarmingPhaseWorker →
 *       HnswIntegrationWorker → CuckooMetricsWorker → CuckooMaintenanceWorker →
 *       EpochTickScheduler</li>
 * </ol>
 *
 * <h2>Metrics Exposure</h2>
 * <p>The orchestrator exposes queue depth metrics for backpressure monitoring:</p>
 * <ul>
 *   <li>HNSW integration queue depth</li>
 *   <li>Pending Cuckoo filter deletions</li>
 *   <li>Cuckoo filter false positive rates</li>
 *   <li>Scheduled re-index count</li>
 *   <li>Current visibility epoch</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>All worker lifecycle operations are synchronized</li>
 *   <li>Metrics are exposed via atomic operations</li>
 *   <li>Segment registration is thread-safe</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>HNSW queue depth limit: 100,000 entries (configurable)</li>
 *   <li>Cuckoo maintenance interval: 5 seconds (configurable)</li>
 *   <li>Re-index check interval: 1 minute (configurable)</li>
 *   <li>Epoch tick interval: 100ms (configurable)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see HnswIntegrationWorker
 * @see CuckooMaintenanceWorker
 * @see CuckooMetricsWorker
 * @see SegmentReindexScheduler
 * @see VisibilityTracker
 * @see EpochTickScheduler
 */
public class IndexOrchestrator implements AutoCloseable, SplitWorker.SplitListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexOrchestrator.class);

    // ==================== Configuration Constants ====================

    /**
     * Default HNSW queue depth limit.
     */
    public static final int DEFAULT_HNSW_QUEUE_DEPTH_LIMIT = 100_000;

    /**
     * Default Cuckoo maintenance interval in milliseconds.
     */
    public static final long DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS = 5000;

    /**
     * Default re-index check interval in milliseconds.
     */
    public static final long DEFAULT_REINDEX_CHECK_INTERVAL_MS = 60_000;

    /**
     * Default epoch tick interval in milliseconds.
     * Spec requires 100ms default.
     */
    public static final long DEFAULT_EPOCH_TICK_INTERVAL_MS = 100;

    /**
     * Epoch the visibility counter starts at when the caller names none.
     *
     * <p>Right only for a node with no durable records to be older than: an empty data directory,
     * or an engine built for a test. A node recovering a data directory has to resume above the
     * epochs those records carry - see the constructor that takes one.</p>
     */
    public static final long DEFAULT_INITIAL_EPOCH = 0L;

    /**
     * Default interval between segment directory reconciliation sweeps, in milliseconds.
     *
     * <p>Segments appear and disappear behind the orchestrator's back: the storage engine
     * creates one lazily on the first write that routes to it, and a split replaces one
     * segment with two. The sweep is what converges the orchestrator's registrations with
     * the engine's directory; see {@link #syncSegmentsFromStorage()}.</p>
     */
    public static final long DEFAULT_SEGMENT_SYNC_INTERVAL_MS = 1000;
    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final StorageEngine storageEngine;

    /**
     * Volume manager for disk space tracking and segment-to-volume assignment.
     * May be null if not configured.
     */
    private final VolumeManager volumeManager;

    /**
     * Visibility tracker for coordinating epochs across segments.
     * Provides epoch-based visibility for consistent query snapshots.
     */
    private final VisibilityTracker visibility;

    /**
     * Epoch isolation validator for preventing torn reads at the node level.
     * Tracks when individual HNSW nodes become visible and filters query results
     * to only include nodes visible at the query's captured epoch.
     */
    private final EpochIsolationValidator epochIsolation;

    /** The background workers, and the order they may be started and stopped in. */
    private final ShardWorkers workers;

    /** Which segments are indexed here, and everything told when that changes. */
    private final SegmentRegistry registry;

    /** The reconciliation sweep that converges the registry on the engine's directory. */
    private final SegmentSyncScheduler segmentSync;

    /** The bookkeeping the store cannot do for itself when a segment is compacted. */
    private final CompactionSwap compactionSwap;

    /** The bookkeeping left over once the engine has published a split. */
    private final SplitBookkeeping splitBookkeeping;

    /** What is forgotten, or retired, after an HNSW graph is rebuilt. */
    private final GraphCompactionBookkeeping graphCompactionBookkeeping;

    /** Where a scheduled re-index is executed, and how its outcome is reported back. */
    private final ReindexDispatcher reindexDispatcher;

    /** Compaction an operator asked for, run on the calling thread. */
    private final OnDemandCompaction onDemandCompaction;

    private final AtomicBoolean running;
    private final AtomicBoolean stopped;

    // ==================== Constructors ====================

    /**
     * Creates a new IndexOrchestrator with default configuration.
     *
     * @param catalog       The segment catalog
     * @param storageEngine The storage engine
     * @throws NullPointerException if catalog or storageEngine is null
     */
    public IndexOrchestrator(SegmentCatalog catalog, StorageEngine storageEngine) {
        this(catalog, storageEngine,
             DEFAULT_HNSW_QUEUE_DEPTH_LIMIT,
             DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS,
             DEFAULT_REINDEX_CHECK_INTERVAL_MS,
             DEFAULT_EPOCH_TICK_INTERVAL_MS);
    }

    /**
     * Creates a new IndexOrchestrator with custom configuration.
     *
     * @param catalog                       The segment catalog
     * @param storageEngine                 The storage engine
     * @param hnswQueueDepthLimit           HNSW integration queue depth limit
     * @param cuckooMaintenanceIntervalMs   Cuckoo maintenance interval (ms)
     * @param reindexCheckIntervalMs        Re-index check interval (ms)
     * @throws NullPointerException         if catalog or storageEngine is null
     * @throws IllegalArgumentException     if configuration values are invalid
     */
    public IndexOrchestrator(SegmentCatalog catalog, StorageEngine storageEngine,
                              int hnswQueueDepthLimit, long cuckooMaintenanceIntervalMs,
                              long reindexCheckIntervalMs) {
        this(catalog, storageEngine, hnswQueueDepthLimit, cuckooMaintenanceIntervalMs,
             reindexCheckIntervalMs, DEFAULT_EPOCH_TICK_INTERVAL_MS);
    }

    /**
     * Creates a new IndexOrchestrator with full custom configuration.
     *
     * @param catalog                       The segment catalog
     * @param storageEngine                 The storage engine
     * @param hnswQueueDepthLimit           HNSW integration queue depth limit
     * @param cuckooMaintenanceIntervalMs   Cuckoo maintenance interval (ms)
     * @param reindexCheckIntervalMs        Re-index check interval (ms)
     * @param epochTickIntervalMs           Epoch tick interval (ms)
     * @throws NullPointerException         if catalog or storageEngine is null
     * @throws IllegalArgumentException     if configuration values are invalid
     */
    public IndexOrchestrator(SegmentCatalog catalog, StorageEngine storageEngine,
                              int hnswQueueDepthLimit, long cuckooMaintenanceIntervalMs,
                              long reindexCheckIntervalMs, long epochTickIntervalMs) {
        this(catalog, storageEngine, null, hnswQueueDepthLimit, cuckooMaintenanceIntervalMs,
             reindexCheckIntervalMs, epochTickIntervalMs);
    }

    /**
     * Creates a new IndexOrchestrator with full custom configuration and VolumeManager.
     *
     * @param catalog                       The segment catalog
     * @param storageEngine                 The storage engine
     * @param volumeManager                 The volume manager for disk space tracking (optional)
     * @param hnswQueueDepthLimit           HNSW integration queue depth limit
     * @param cuckooMaintenanceIntervalMs   Cuckoo maintenance interval (ms)
     * @param reindexCheckIntervalMs        Re-index check interval (ms)
     * @param epochTickIntervalMs           Epoch tick interval (ms)
     * @throws NullPointerException         if catalog or storageEngine is null
     * @throws IllegalArgumentException     if configuration values are invalid
     */
    public IndexOrchestrator(SegmentCatalog catalog, StorageEngine storageEngine,
                              VolumeManager volumeManager,
                              int hnswQueueDepthLimit, long cuckooMaintenanceIntervalMs,
                              long reindexCheckIntervalMs, long epochTickIntervalMs) {
        this(catalog, storageEngine, volumeManager, hnswQueueDepthLimit,
             cuckooMaintenanceIntervalMs, reindexCheckIntervalMs, epochTickIntervalMs,
             DEFAULT_INITIAL_EPOCH);
    }

    /**
     * Creates a new IndexOrchestrator whose visibility counter resumes at a given epoch.
     *
     * <p>Epochs are stamped into durable records and compared against this counter. A node
     * holding records from an earlier run has to resume above every epoch those records carry,
     * or it reads its own corpus as written in the future and its own tombstones as not yet
     * applied. The caller that knows which stores exist computes that floor; here it is taken
     * as given.</p>
     *
     * @param catalog                       The segment catalog
     * @param storageEngine                 The storage engine
     * @param volumeManager                 The volume manager for disk space tracking (optional)
     * @param hnswQueueDepthLimit           HNSW integration queue depth limit
     * @param cuckooMaintenanceIntervalMs   Cuckoo maintenance interval (ms)
     * @param reindexCheckIntervalMs        Re-index check interval (ms)
     * @param epochTickIntervalMs           Epoch tick interval (ms)
     * @param initialEpoch                  Epoch the visibility counter starts at
     * @throws NullPointerException         if catalog or storageEngine is null
     * @throws IllegalArgumentException     if configuration values are invalid
     */
    public IndexOrchestrator(SegmentCatalog catalog, StorageEngine storageEngine,
                              VolumeManager volumeManager,
                              int hnswQueueDepthLimit, long cuckooMaintenanceIntervalMs,
                              long reindexCheckIntervalMs, long epochTickIntervalMs,
                              long initialEpoch) {
        this(catalog, storageEngine, volumeManager, hnswQueueDepthLimit,
             cuckooMaintenanceIntervalMs, reindexCheckIntervalMs, epochTickIntervalMs,
             initialEpoch, CompactionSettings.DEFAULTS);
    }

    /**
     * Creates a new IndexOrchestrator with an explicit compaction policy.
     *
     * <p>Both compaction workers are built here and never rebuilt, so the policy has to arrive
     * with the rest of the construction arguments. {@link CompactionSettings#DEFAULTS} is what
     * every other constructor passes.</p>
     *
     * @param catalog                       The segment catalog
     * @param storageEngine                 The storage engine
     * @param volumeManager                 The volume manager for segment placement (optional)
     * @param hnswQueueDepthLimit           HNSW integration queue depth limit
     * @param cuckooMaintenanceIntervalMs   Cuckoo maintenance interval (ms)
     * @param reindexCheckIntervalMs        Re-index check interval (ms)
     * @param epochTickIntervalMs           Epoch tick interval (ms)
     * @param initialEpoch                  Epoch the visibility counter starts at
     * @param compaction                    When compaction runs and what it rewrites
     * @throws NullPointerException         if catalog, storageEngine or compaction is null
     * @throws IllegalArgumentException     if configuration values are invalid
     */
    public IndexOrchestrator(SegmentCatalog catalog, StorageEngine storageEngine,
                              VolumeManager volumeManager,
                              int hnswQueueDepthLimit, long cuckooMaintenanceIntervalMs,
                              long reindexCheckIntervalMs, long epochTickIntervalMs,
                              long initialEpoch, CompactionSettings compaction) {
        Objects.requireNonNull(compaction, "compaction settings cannot be null");
        if (catalog == null) {
            throw new NullPointerException("Catalog cannot be null");
        }
        if (storageEngine == null) {
            throw new NullPointerException("StorageEngine cannot be null");
        }

        this.catalog = catalog;
        this.storageEngine = storageEngine;
        this.volumeManager = volumeManager;
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);

        // Create visibility tracker for coordinating epochs across segments, resumed above
        // whatever epochs the durable stores already carry.
        this.visibility = new VisibilityTracker(catalog, initialEpoch);

        // Create epoch isolation validator for node-level epoch tracking
        this.epochIsolation = new EpochIsolationValidator();

        // Every worker is built with its listener and never rebuilt, so the callbacks have to
        // exist before the collaborators that answer them do. Each one reads its field at call
        // time, and no worker is running until start(), so the cycle is closed by the end of
        // this constructor and observed by nobody in between.
        ShardWorkerListeners listeners = new ShardWorkerListeners(
            (segment, count) -> onVectorsIntegrated(segment, count),
            segmentId -> { onSegmentFilterCleaned(segmentId); },
            (segmentId, pqSuccess, vectorCount) -> onWarmingComplete(segmentId, pqSuccess, vectorCount),
            sourceId -> onReindexNeeded(sourceId),
            (oldSegment, newSegment) -> onCompactionComplete(oldSegment, newSegment),
            new HnswCompactionWorker.CompactionListener() {
                @Override
                public void onGraphCompacted(HnswGraph graph, long nodesRemoved) {
                    graphCompactionBookkeeping.onGraphCompacted(graph, nodesRemoved);
                }

                @Override
                public void onGraphCompactionFailed(HnswGraph graph, Exception failure) {
                    graphCompactionBookkeeping.onGraphCompactionFailed(graph, failure);
                }
            });

        this.workers = new ShardWorkers(catalog, storageEngine, visibility, epochIsolation,
            new WorkerTuning(hnswQueueDepthLimit, cuckooMaintenanceIntervalMs,
                reindexCheckIntervalMs, epochTickIntervalMs),
            compaction, listeners);

        this.registry = new SegmentRegistry(storageEngine, workers, visibility, epochIsolation);
        this.compactionSwap = new CompactionSwap(catalog, storageEngine, workers, registry);
        this.splitBookkeeping = new SplitBookkeeping(registry);
        this.graphCompactionBookkeeping =
            new GraphCompactionBookkeeping(registry, workers, epochIsolation);
        this.reindexDispatcher = new ReindexDispatcher(workers.reindex());
        this.onDemandCompaction = new OnDemandCompaction(storageEngine, workers);
        this.segmentSync = new SegmentSyncScheduler(storageEngine, this,
            () -> registry.syncFromStorage(), DEFAULT_SEGMENT_SYNC_INTERVAL_MS);

        LOGGER.info("IndexOrchestrator created with hnswQueueDepthLimit={}, cuckooInterval={}ms, reindexInterval={}ms, epochTickInterval={}ms, volumeManager={}",
            hnswQueueDepthLimit, cuckooMaintenanceIntervalMs, reindexCheckIntervalMs, epochTickIntervalMs, volumeManager != null);
    }

    // ==================== Factory Methods ====================

    /**
     * Creates an IndexOrchestrator configured from ShardConfig settings.
     *
     * <p>This factory method wires the shard configuration values to the appropriate
     * orchestrator parameters:</p>
     * <ul>
     *   <li>{@code hnswQueueLimit} → HNSW integration queue depth limit</li>
     *   <li>{@code compactDelayMs} → how long the compaction loop waits before its first check</li>
     *   <li>{@code compaction-*} and {@code hnsw-compaction-*} → the policy both compaction
     *       workers are built with, via {@link CompactionSettings#fromConfig(ShardConfig)}</li>
     *   <li>{@code segment-sync-interval-ms} → how often the registry is reconciled with the
     *       segment directory, via {@link #withSegmentSyncIntervalMs(long)}</li>
     *   <li>{@code volumes} → the directories segments are spread across, via
     *       {@link #withVolumePlacement(List)}</li>
     *   <li>{@code cuckooMaintenanceIntervalMs} → Uses default (5000ms)</li>
     *   <li>{@code reindexCheckIntervalMs} → Uses default (60000ms)</li>
     *   <li>{@code epochTickIntervalMs} → Uses default (100ms)</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>HNSW queue limit must be positive (enforced by ShardConfig validation)</li>
     *   <li>Compact delay is applied to the compaction worker's first check, so an operator
     *       can keep compaction off the back of a bulk load</li>
     *   <li>Volume placement has to be installed before {@link StorageEngine#start()}, because
     *       that is where the engine discovers what is on disk and creates what is missing</li>
     * </ul>
     *
     * @param catalog The segment catalog
     * @param storageEngine The storage engine
     * @param volumeManager The volume manager (optional, may be null)
     * @param shard The shard configuration from ShardConfig
     * @return A new IndexOrchestrator configured with shard settings
     * @throws NullPointerException if catalog, storageEngine, or shard is null
     */
    public static IndexOrchestrator fromConfig(SegmentCatalog catalog,
                                                StorageEngine storageEngine,
                                                VolumeManager volumeManager,
                                                ShardConfig shard) {
        return fromConfig(catalog, storageEngine, volumeManager, shard, DEFAULT_INITIAL_EPOCH);
    }

    /**
     * Creates an IndexOrchestrator from shard configuration, resuming at a given epoch.
     *
     * @param catalog       The segment catalog
     * @param storageEngine The storage engine
     * @param volumeManager The volume manager (optional, may be null)
     * @param shard         The shard configuration from ShardConfig
     * @param initialEpoch  Epoch the visibility counter starts at, above every epoch the node's
     *                      durable records carry
     * @return A new IndexOrchestrator configured with shard settings
     * @throws NullPointerException if catalog, storageEngine, or shard is null
     */
    public static IndexOrchestrator fromConfig(SegmentCatalog catalog,
                                                StorageEngine storageEngine,
                                                VolumeManager volumeManager,
                                                ShardConfig shard,
                                                long initialEpoch) {
        Objects.requireNonNull(catalog, "catalog cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(shard, "shard config cannot be null");

        ShardIndexSettings settings = ShardIndexSettings.fromConfig(shard);
        IndexOrchestrator orchestrator = fromSettings(catalog, storageEngine, volumeManager,
            settings, DEFAULT_EPOCH_TICK_INTERVAL_MS, initialEpoch);
        orchestrator.withVolumePlacement(settings.volumes());
        return orchestrator;
    }

    /**
     * Creates an IndexOrchestrator from shard configuration over an engine that has started.
     *
     * <p>The construction path a node takes. Volume placement has to be installed before
     * {@link StorageEngine#start()} - see {@link #withVolumePlacement(List)} - so a node installs
     * it itself and starts the engine before any orchestrator exists; this applies every other
     * shard setting. Without it the node built the orchestrator from a hand-picked subset and
     * {@code segment-sync-interval-ms} and the three {@code pq-*} keys were parsed, range-checked
     * and delivered nowhere.</p>
     *
     * @param catalog             The segment catalog
     * @param storageEngine       The storage engine, with its volume placement already installed
     * @param volumeManager       The volume manager (optional, may be null)
     * @param shard               The shard configuration from ShardConfig
     * @param epochTickIntervalMs How often the visibility epoch advances
     * @param initialEpoch        Epoch the visibility counter starts at, above every epoch the
     *                            node's durable records carry
     * @return A new IndexOrchestrator configured with shard settings
     * @throws NullPointerException     if catalog, storageEngine, or shard is null
     * @throws IllegalArgumentException if a configured value is outside the range its worker
     *                                  accepts
     */
    public static IndexOrchestrator forStartedEngine(SegmentCatalog catalog,
                                                     StorageEngine storageEngine,
                                                     VolumeManager volumeManager,
                                                     ShardConfig shard,
                                                     long epochTickIntervalMs,
                                                     long initialEpoch) {
        Objects.requireNonNull(catalog, "catalog cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(shard, "shard config cannot be null");
        return fromSettings(catalog, storageEngine, volumeManager,
            ShardIndexSettings.fromConfig(shard), epochTickIntervalMs, initialEpoch);
    }

    /**
     * Builds an orchestrator and delivers every setting except volume placement.
     */
    private static IndexOrchestrator fromSettings(SegmentCatalog catalog,
                                                  StorageEngine storageEngine,
                                                  VolumeManager volumeManager,
                                                  ShardIndexSettings settings,
                                                  long epochTickIntervalMs,
                                                  long initialEpoch) {
        LOGGER.info("Creating IndexOrchestrator from ShardConfig: hnswQueueLimit={}, "
                + "compactDelayMs={}, segmentSyncIntervalMs={}, compaction={}, pqTraining={}",
            settings.hnswQueueLimit(), settings.compactionStartDelayMs(),
            settings.segmentSyncIntervalMs(), settings.compaction(), settings.pqTraining());

        IndexOrchestrator orchestrator = new IndexOrchestrator(
            catalog,
            storageEngine,
            volumeManager,
            settings.hnswQueueLimit(),
            DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS,
            DEFAULT_REINDEX_CHECK_INTERVAL_MS,
            epochTickIntervalMs,
            initialEpoch,
            settings.compaction()
        );

        // The key was parsed, range-checked, shipped and logged at startup while reaching no
        // compaction code: an operator who set it to keep compaction away from a bulk load saw
        // it echoed back and got the same schedule as before.
        orchestrator.withCompactionStartDelayMs(settings.compactionStartDelayMs());
        orchestrator.withSegmentSyncIntervalMs(settings.segmentSyncIntervalMs());

        // The warming worker reads this shape when a segment trains, not when the worker is
        // built, and nothing has started it yet - so handing it over here is as early as it
        // can be delivered. Without it every deployment trained the eight subspaces and 256
        // centroids the no-argument PQConfig picks, whatever its embeddings look like.
        orchestrator.setPqConfig(settings.pqTraining());
        return orchestrator;
    }

    /**
     * Spreads the storage engine's segments over the configured volumes.
     *
     * <p>The data directory is registered first and then each configured volume, so a
     * single-volume deployment - the default, and an empty list - changes nothing at all: the
     * engine keeps the placement it was born with and every file lands exactly where it always
     * did. Naming volumes registers them on the {@link VolumeManager} and hands the engine a
     * placement backed by its consistent hash ring, so a volume added later moves as few
     * existing segments as possible.</p>
     *
     * <p>Must run before {@link StorageEngine#start()}. Start is where the engine scans for
     * what is on disk and creates whatever the base keyspace is missing, and both depend on
     * knowing the volume set; the engine refuses a placement installed after it.</p>
     *
     * <p>A configured volume that is not a directory is refused here, before the volume
     * manager gets a chance to create it. An unmounted volume silently replaced by an empty
     * directory on the root filesystem is how a node loses a quarter of its corpus and reports
     * nothing.</p>
     *
     * @param volumePaths The configured volume directories, possibly empty
     * @return this orchestrator, for chaining
     * @throws NullPointerException     if volumePaths is null
     * @throws IllegalArgumentException if a configured volume is not an existing directory
     * @throws IllegalStateException    if volumes are configured but this orchestrator has no
     *                                  volume manager, or the engine has already started
     */
    public IndexOrchestrator withVolumePlacement(List<String> volumePaths) {
        VolumePlacements.install(storageEngine, volumeManager, volumePaths);
        return this;
    }

    /**
     * Sets how long the compaction worker waits before its first check.
     *
     * <p>Takes effect on the next {@link #start()}. Does nothing when this orchestrator has no
     * compaction worker, which happens when the storage engine exposes no Arena.</p>
     *
     * @param delayMs The delay in milliseconds
     * @return this orchestrator, for chaining
     */
    public IndexOrchestrator withCompactionStartDelayMs(long delayMs) {
        if (workers.segmentCompaction() != null) {
            workers.segmentCompaction().withStartDelayMs(delayMs);
        }
        return this;
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts all background workers.
     *
     * <p>EpochTickScheduler → CuckooMaintenanceWorker → CuckooMetricsWorker →
     * HnswIntegrationWorker → WarmingPhaseWorker → HnswCompactionWorker →
     * SegmentReindexScheduler → CompactionWorker, and then the segment reconciliation sweep.
     * The order is enforced by {@code ShardWorkers}, which documents why it is that order; the
     * sweep is started last because it is the only thing here that registers new index
     * structures, and it must not do so into workers that have not come up.</p>
     *
     * @return true if all workers were started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            LOGGER.info("Starting index orchestrator...");

            workers.start();

            // Converge the registrations with the storage engine's directory. Segments
            // created after boot - lazily on first write, or as the children of a split -
            // are otherwise known to no worker at all. Started last, so it never registers
            // index structures into workers that have not come up yet.
            segmentSync.start();

            LOGGER.info("Index orchestrator started successfully");
            return true;
        }
        return false;
    }

    /**
     * Stops all background workers.
     *
     * <p>The reconciliation sweep first, then exactly the reverse of {@link #start()}:
     * CompactionWorker → SegmentReindexScheduler → HnswCompactionWorker → WarmingPhaseWorker →
     * HnswIntegrationWorker → CuckooMetricsWorker → CuckooMaintenanceWorker →
     * EpochTickScheduler. The epoch ticker is last because its stop fences an
     * already-dispatched tick, a promise that only holds once nothing can publish new work.</p>
     *
     * <p>The workers are asked to stop, not interrupted, and each is waited for within its own
     * bound; {@link #shutdown()} is the variant that says whether they all ended.</p>
     */
    public void stop() {
        stopWorkers();
    }

    /**
     * Stops every background worker and reports whether they all ended.
     *
     * <p>No worker is interrupted. Each rewrites, maps, grows or forces files through channels
     * that queries and writers share, and an interrupt reaching one inside such an operation
     * closes the channel for all of them; so each is asked through a signal it reads between
     * units of work and waited for within its own bound. A worker still inside a segment
     * rewrite or a graph swap when its bound runs out is left to finish it.</p>
     *
     * <p>The answer is what a caller about to close the storage engine needs: false means work
     * is still running against segments mapped out of the engine's arena, and closing it would
     * unmap memory under that work, which ends the process. Idempotent: a later call waits again
     * for what an earlier one left running, and answers from the workers' state, not from
     * whether this is the first call.</p>
     *
     * <p>Unlike {@link #stop()} this is final: it also drains the term filter rebuilds, which
     * copy out of the same segment mappings, and a drained orchestrator is not started again.
     * {@link #close()} still has to be called for the rest of what the orchestrator holds, and
     * calls this first.</p>
     *
     * @return true if every background worker, the reconciliation sweep and every term filter
     *         rebuild have ended
     */
    public boolean shutdown() {
        boolean workersEnded = stopWorkers();
        boolean rebuildsEnded = registry.close();
        return workersEnded && rebuildsEnded;
    }

    /**
     * Stops the reconciliation sweep and then the workers, once, and reports whether they ended.
     *
     * @return true if every one of them has ended
     */
    private boolean stopWorkers() {
        boolean first = stopped.compareAndSet(false, true);
        if (first) {
            running.set(false);

            LOGGER.info("Stopping index orchestrator...");

            // The reconciliation sweep goes first, in the exact mirror of start(): it is the
            // one thing that registers new index structures, and it must not be able to do so
            // into a worker set that is going down.
            segmentSync.stop();
        }
        boolean syncEnded = segmentSync.shutdown();
        boolean workersEnded = workers.stop();
        if (first) {

            LOGGER.info("Index orchestrator stopped. HNSW integrated: {}, Cuckoo deletions: {}, Reindexes: {}, Compactions: {}, HNSW compactions: {}, PQ trained: {}, Epoch ticks: {}, Cuckoo FP samples: {}",
                workers.hnsw().getTotalIntegrated(), workers.cuckoo().getTotalDeletions(),
                workers.reindex().getTotalReindexesTriggered(), workers.segmentCompactionCount(),
                workers.graphCompaction().getCompactionCount(),
                workers.warming().getTotalPqTrained(), workers.epochTicks().getTickCount(),
                workers.cuckooMetrics().getTotalSamplesRecorded());
        }
        boolean ended = syncEnded && workersEnded;
        if (!ended) {
            LOGGER.warn("Index orchestrator stopped with background work still running; the "
                + "storage engine it runs against must not be closed under it");
        }
        return ended;
    }

    /**
     * Stops the workers, as {@link #shutdown()}, and releases what the orchestrator holds.
     *
     * <p>Callers that go on to close the storage engine should call {@link #shutdown()} first
     * and act on its answer; this cannot return one.</p>
     */
    @Override
    public void close() {
        shutdown();
        // Close the epoch tick scheduler to shut down its executor
        workers.close();
    }

    /**
     * Checks if the orchestrator is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Segment Registration ====================

    /**
     * Sets how often the registry is reconciled with the storage engine's directory.
     *
     * <p>Takes effect on the next {@link #start()}, like
     * {@link #withCompactionStartDelayMs(long)}. Slower is the right direction on a node with
     * very many segments, where a sweep is an expensive pass; faster shortens the worst case
     * before a segment the engine created on its own is indexed at all.</p>
     *
     * @param intervalMs The period between sweeps, in milliseconds
     * @return this orchestrator, for chaining
     * @throws IllegalArgumentException if intervalMs is below the sweep's own minimum
     */
    public IndexOrchestrator withSegmentSyncIntervalMs(long intervalMs) {
        segmentSync.setIntervalMs(intervalMs);
        return this;
    }

    /**
     * Registers a segment for index orchestration.
     *
     * <p>This registers the segment with all appropriate workers:</p>
     * <ul>
     *   <li>HNSW integration (if it has a graph)</li>
     *   <li>Cuckoo maintenance (if it has a filter)</li>
     *   <li>Cuckoo metrics (for FP rate monitoring, if it has a filter)</li>
     *   <li>Compaction worker (for tombstone cleanup)</li>
     *   <li>Warming phase worker (for PQ training during WARMING state)</li>
     *   <li>Hierarchical Cuckoo filter (for corpus → segment membership)</li>
     * </ul>
     *
     * @param segmentId The segment ID
     * @param segment   The segment
     * @param graph     The HNSW graph (can be null)
     * @param filter    The Cuckoo filter (can be null)
     * @param tracker   The tail offset tracker (can be null)
     */
    public void registerSegment(long segmentId, Segment segment, HnswGraph graph,
                                 CuckooFilter filter, TailOffsetTracker tracker) {
        registry.register(segmentId, segment, graph, filter, tracker);
    }

    /**
     * Unregisters a segment from index orchestration.
     *
     * <p>Registrations keyed by segment ID are always dropped. The HNSW integration
     * worker, the store compaction worker and the HNSW compaction worker key their
     * registrations by object identity, and the routing directory can reach one
     * {@link Segment} - and one graph - from several IDs, so those are retired only once
     * the last ID referencing them is gone.</p>
     *
     * @param segmentId The segment ID
     */
    public void unregisterSegment(long segmentId) {
        registry.unregister(segmentId);
    }

    /**
     * Reconciles the orchestrator's registrations with the storage engine's directory.
     *
     * <p>The engine owns the segment directory and changes it without telling anyone: it
     * creates a segment lazily on the first write that routes to it, and a split replaces
     * one segment with two children. A segment the orchestrator does not know about is
     * integrated into no HNSW graph, compacted by nobody and invisible to epoch-based
     * visibility, so the registry has to converge on the directory rather than being
     * populated once at boot.</p>
     *
     * <p>Three cases are handled: an index the orchestrator has never seen is registered;
     * an index whose {@link Segment} instance was replaced is re-registered, because the
     * workers key their registrations by object identity; and an index that has left the
     * directory is retired.</p>
     *
     * @return Number of registrations added, replaced or retired
     */
    public int syncSegmentsFromStorage() {
        return registry.syncFromStorage();
    }

    /**
     * Returns the number of registered segments.
     *
     * @return Registered segment count
     */
    public int getRegisteredSegmentCount() {
        return registry.size();
    }

    /**
     * Marks a segment as tombstoned for visibility tracking.
     *
     * <p>This method should be called when a segment transitions to TOMBSTONED state.
     * It records the tombstone epoch in the visibility tracker, ensuring that:</p>
     * <ul>
     *   <li>New queries (capturing epoch >= tombstone epoch) will not see this segment</li>
     *   <li>In-flight queries (capturing epoch &lt; tombstone epoch) will still see the segment</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This method only updates the visibility tracker.
     * The caller is responsible for updating the segment catalog state.</p>
     *
     * @param segmentId The segment ID to tombstone
     * @throws IllegalArgumentException if the segment is not registered with the visibility tracker
     */
    public void tombstoneSegment(long segmentId) {
        long tombstoneEpoch = visibility.currentEpoch();
        visibility.markTombstoned(segmentId, tombstoneEpoch);
        LOGGER.info("Segment {} tombstoned at epoch {}", segmentId, tombstoneEpoch);
    }

    /**
     * Marks a segment as tombstoned with a specific visibility epoch.
     *
     * <p>This overload allows specifying an explicit tombstone epoch,
     * useful for coordinated state transitions.</p>
     *
     * @param segmentId The segment ID to tombstone
     * @param tombstoneEpoch The epoch at which the segment becomes invisible to new queries
     * @throws IllegalArgumentException if the segment is not registered with the visibility tracker
     */
    public void tombstoneSegment(long segmentId, long tombstoneEpoch) {
        visibility.markTombstoned(segmentId, tombstoneEpoch);
        LOGGER.info("Segment {} tombstoned at epoch {}", segmentId, tombstoneEpoch);
    }

    // ==================== Metrics Exposure ====================

    /**
     * Returns the current HNSW integration queue depth.
     *
     * <p>This metric is used for backpressure monitoring. When the queue depth
     * exceeds the limit, ingestion should be throttled.</p>
     *
     * @return Current queue depth
     */
    public long getHnswQueueDepth() {
        return workers.hnsw().getCurrentQueueDepth();
    }

    /**
     * Returns the HNSW queue depth limit.
     *
     * @return Queue depth limit
     */
    public int getHnswQueueDepthLimit() {
        return workers.hnsw().getQueueDepthLimit();
    }

    /**
     * Checks if backpressure is currently being applied.
     *
     * @return true if backpressured
     */
    public boolean isBackpressured() {
        return workers.hnsw().isBackpressured();
    }

    /**
     * Default backpressure timeout in milliseconds.
     */
    public static final long DEFAULT_BACKPRESSURE_TIMEOUT_MS = 30_000;

    /**
     * Blocks until backpressure is released or timeout expires.
     *
     * <p>This method should be called by the ingestion pipeline before adding
     * entries to the write log. When the HNSW integration queue exceeds the
     * configured depth limit, the worker signals backpressure, and callers
     * should wait for the queue to drain before continuing.</p>
     *
     * <p>The method uses virtual thread-friendly parking (LockSupport.parkNanos)
     * rather than busy-waiting.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Poll interval: 10ms (virtual thread friendly)</li>
     *   <li>Backpressure releases when queue depth falls below 50% of limit</li>
     *   <li>Interrupts are handled gracefully with immediate return</li>
     * </ul>
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if backpressure was released (or was not active), false on timeout
     * @throws IllegalArgumentException if timeoutMs is negative
     */
    public boolean awaitBackpressureRelease(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Timeout must be non-negative, got: " + timeoutMs);
        }

        if (!workers.hnsw().isBackpressured()) {
            return true;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long pollIntervalNs = TimeUnit.MILLISECONDS.toNanos(10); // 10ms poll interval

        while (workers.hnsw().isBackpressured() && System.nanoTime() < deadline) {
            // Check for thread interruption
            if (Thread.currentThread().isInterrupted()) {
                LOGGER.debug("Backpressure wait interrupted");
                return false;
            }

            // Park for a short duration (virtual thread friendly)
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0) {
                break;
            }
            LockSupport.parkNanos(Math.min(pollIntervalNs, remainingNs));
        }

        boolean released = !workers.hnsw().isBackpressured();
        if (!released) {
            LOGGER.warn("Backpressure timeout after {}ms, queue depth: {}",
                timeoutMs, workers.hnsw().getCurrentQueueDepth());
        }
        return released;
    }

    /**
     * Blocks until backpressure is released using the default timeout.
     *
     * <p>Uses a 30-second default timeout. For custom timeouts, use
     * {@link #awaitBackpressureRelease(long)}.</p>
     *
     * @return true if backpressure was released, false on timeout
     */
    public boolean awaitBackpressureRelease() {
        return awaitBackpressureRelease(DEFAULT_BACKPRESSURE_TIMEOUT_MS);
    }

    /**
     * Returns how many Cuckoo filter entries are waiting to be removed.
     *
     * <p>Every entry still held by a tombstoned segment the maintenance worker has not yet
     * cleaned; see {@link CuckooMaintenanceWorker#getPendingDeletionCount()}.</p>
     *
     * @return Pending deletion count, in filter entries
     */
    public int getPendingCuckooDeletions() {
        return workers.cuckoo().getPendingDeletionCount();
    }

    /**
     * Returns the estimated false-positive rates of the registered segments' Cuckoo filters.
     *
     * <p>Read now, from the filters themselves, rather than from the last scheduled sample;
     * see {@link CuckooMetricsWorker#sample()}.</p>
     *
     * @return The average and worst rate over the segments whose filters hold anything
     */
    public CuckooMetricsWorker.FpRates getCuckooFpRates() {
        return workers.cuckooMetrics().sample();
    }

    /**
     * Returns the number of sources scheduled for re-index.
     *
     * @return Scheduled source count
     */
    public int getScheduledReindexCount() {
        return workers.reindex().getRegisteredSourceCount();
    }

    /**
     * Returns the current visibility epoch.
     *
     * @return Visibility epoch
     */
    public long getVisibilityEpoch() {
        return visibility.currentEpoch();
    }

    /**
     * Advances the visibility epoch.
     *
     * <p>This is called after each batch of writes is committed.
     * Note: The epoch is also advanced automatically by the EpochTickScheduler.</p>
     *
     * @return The new epoch value
     */
    public long advanceEpoch() {
        return visibility.advanceEpoch();
    }

    /**
     * Sets the vector pre-filter that HNSW integration mirrors into.
     *
     * <p>The pre-filter is keyed by HNSW node id, and this orchestrator owns the worker that
     * assigns those ids. Wiring it here is what lets the query pipeline measure the nodes a
     * pre-filter names as the graph's own nodes rather than as ids from a space nothing else
     * uses.</p>
     *
     * @param vectorPrefilter The pre-filter to mirror into, or null to maintain none
     */
    public void setVectorPrefilter(com.eonmux.excalibur.store.VectorPrefilter vectorPrefilter) {
        workers.hnsw().setVectorPrefilter(vectorPrefilter);
    }

    /**
     * Returns the segment catalog this orchestrator indexes into.
     *
     * @return The catalog, never null
     */
    public SegmentCatalog getCatalog() {
        return catalog;
    }

    /**
     * Returns the visibility tracker for epoch-based visibility.
     *
     * <p>Use this to capture epochs for queries and determine
     * segment visibility.</p>
     *
     * @return The visibility tracker
     */
    public VisibilityTracker getVisibilityTracker() {
        return visibility;
    }

    /**
     * Returns the epoch isolation validator for node-level epoch filtering.
     *
     * <p>This validator is used by the query pipeline to filter HNSW search results
     * to only include nodes that were visible at the query's captured epoch,
     * preventing "torn reads" where a query might see partially visible data.</p>
     *
     * @return The epoch isolation validator
     */
    public EpochIsolationValidator getEpochIsolationValidator() {
        return epochIsolation;
    }

    /**
     * Returns the epoch tick scheduler.
     *
     * @return The epoch tick scheduler
     */
    public EpochTickScheduler getEpochTickScheduler() {
        return workers.epochTicks();
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of vectors integrated into HNSW graphs.
     *
     * @return Total vectors integrated
     */
    public long getTotalVectorsIntegrated() {
        return workers.hnsw().getTotalIntegrated();
    }

    /**
     * Returns the total number of Cuckoo filter deletions.
     *
     * @return Total deletions
     */
    public long getTotalCuckooDeletions() {
        return workers.cuckoo().getTotalDeletions();
    }

    /**
     * Returns the total number of re-indexes triggered.
     *
     * @return Total re-indexes triggered
     */
    public long getTotalReindexesTriggered() {
        return workers.reindex().getTotalReindexesTriggered();
    }

    /**
     * Returns the total number of completed re-indexes.
     *
     * @return Total re-indexes completed
     */
    public long getTotalReindexesCompleted() {
        return workers.reindex().getTotalReindexesCompleted();
    }

    // ==================== Event Handlers ====================

    /**
     * Called when vectors are integrated into the HNSW graph, and deliberately does nothing.
     *
     * <p>Nothing has to happen here. The integrated vectors become visible when the epoch next
     * advances, which {@link EpochTickScheduler} does on its own; a caller that needs them
     * visible sooner asks for a tick rather than being told about one. The running total the
     * stats endpoint reports is kept by {@link HnswIntegrationWorker#getTotalIntegrated()},
     * not here.</p>
     *
     * <p>The callback is still registered because the worker requires a listener and calls it
     * on every batch. Passing none is not an option: the constructor rejects null.</p>
     *
     * @param segment The segment whose graph received them
     * @param count   How many vectors were integrated
     */
    private void onVectorsIntegrated(Segment segment, int count) {
        // Intentionally empty; see the javadoc.
    }

    /**
     * Called when a source's data has gone stale and has to be re-indexed.
     *
     * @param sourceId The source ID to re-index
     * @return true if the re-index ran and succeeded
     */
    private boolean onReindexNeeded(String sourceId) {
        return reindexDispatcher.onReindexNeeded(sourceId);
    }

    /**
     * Called when the store has rewritten a segment and installed the replacement.
     *
     * @param oldSegment The segment that was compacted, still open
     * @param newSegment The compacted replacement, at the canonical path
     * @return Which of the two segments the store may retire
     */
    private CompactionOutcome onCompactionComplete(Segment oldSegment, Segment newSegment) {
        return compactionSwap.onCompactionComplete(oldSegment, newSegment);
    }

    /**
     * Called when a segment's filter is cleaned after tombstoning.
     *
     * <p>Returns nothing, because {@link CuckooMaintenanceWorker.MaintenanceListener} does not
     * ask for anything back. It used to report {@code true}, which the language quietly
     * discarded at the lambda that adapted it - a value that looks like an acknowledgement and
     * reaches nobody.</p>
     *
     * @param segmentId The segment whose filter was cleaned
     */
    private void onSegmentFilterCleaned(long segmentId) {
        LOGGER.info("Filter cleaned for tombstoned segment {}", segmentId);
    }

    /**
     * Called when a segment completes the warming phase.
     *
     * <p>This method is invoked after PQ codebook training completes
     * (or fails with fallback to full-precision vectors).</p>
     *
     * @param segmentId   The segment ID
     * @param pqSuccess   True if PQ training succeeded
     * @param vectorCount Number of vectors encoded (0 if PQ failed)
     */
    private void onWarmingComplete(long segmentId, boolean pqSuccess, int vectorCount) {
        if (pqSuccess) {
            LOGGER.info("Segment {} warming complete: PQ trained, {} vectors encoded", segmentId, vectorCount);
        } else {
            LOGGER.info("Segment {} warming complete: using full-precision vectors", segmentId);
        }
    }

    /**
     * Called when a segment split has been published by the storage engine.
     *
     * <p>The engine has already renamed both children to their canonical names, given
     * each a write log and an HNSW graph, and re-pointed the routing directory. What is
     * left is this shard's own bookkeeping: the parent's registrations describe a segment
     * that no longer serves any key, and the children are registered with nobody.</p>
     *
     * <p>This method must not throw: the split is already published by the time it runs,
     * and the worker treats a listener failure as a reason to discard the children.</p>
     *
     * @param parent     The segment that was split
     * @param leftChild  The child holding the entries whose split bit is 0
     * @param rightChild The child holding the entries whose split bit is 1
     */
    @Override
    public void onSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
        splitBookkeeping.onSplitComplete(parent, leftChild, rightChild);
    }

    /**
     * Marks both children's term filter levels stale before either can take a write.
     *
     * <p>Each child holds a share of its parent's entries that no write ever filed under it. The
     * right child's first write would otherwise leave its level non-empty and missing everything
     * it inherited, and stage 1 of the query pipeline would rule it out for those terms from
     * then until something noticed - a window that opens the moment routing reaches the child,
     * before {@link #onSplitComplete} is called. The rebuilds that clear these marks are queued
     * when the children are registered.</p>
     *
     * @param parent     The segment being split
     * @param leftIndex  The left child's directory index
     * @param rightIndex The right child's directory index
     */
    @Override
    public void onSplitPublishing(Segment parent, long leftIndex, long rightIndex) {
        registry.markTermFilterStale(leftIndex);
        registry.markTermFilterStale(rightIndex);
    }

    /**
     * Returns the parent's level to service after a split that was not published.
     *
     * <p>The parent still holds every key and is still registered under the left index, so its
     * level is refilled from it, which clears the advance mark. Nothing serves the right index;
     * its mark stays until a segment is registered there, which rebuilds it anyway.</p>
     *
     * @param parent     The segment that stays in service
     * @param leftIndex  The index it is registered under
     * @param rightIndex The index the right child would have taken
     */
    @Override
    public void onSplitAbandoned(Segment parent, long leftIndex, long rightIndex) {
        registry.rebuildTermFilter(leftIndex);
    }

    /**
     * Waits until every queued refill of a segment's term filter level has finished.
     *
     * <p>A segment whose level is being refilled is searched in full, so nothing waits on this
     * for correctness. It is for callers that want the pruning back before they measure - a
     * benchmark after bootstrap, or a test.</p>
     *
     * @param timeout How long to wait
     * @param unit    The unit of the timeout
     * @return true if every queued refill finished in time
     * @throws InterruptedException if the wait is interrupted
     */
    public boolean awaitTermFilterRebuilds(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
        return registry.awaitTermFilterRebuilds(timeout, unit);
    }

    /**
     * Sets the handler for reindex operations.
     *
     * <p>When configured, scheduled reindex operations will delegate to this
     * handler. The handler is responsible for resolving the source and
     * executing the actual reindexing.</p>
     *
     * <p>Without this configuration, a scheduled reindex is recorded as failed - it
     * cannot run - which leaves the source's staleness clock untouched and lets the
     * scheduler back off instead of claiming a reindex that never happened.</p>
     *
     * @param handler The reindex operation handler, or null to disable
     */
    public void setReindexOperationHandler(ReindexOperationHandler handler) {
        reindexDispatcher.setHandler(handler);
        LOGGER.info("ReindexOperationHandler {} for IndexOrchestrator",
            handler != null ? "configured" : "cleared");
    }

    /**
     * Returns the configured reindex operation handler.
     *
     * @return The handler, or null if not configured
     */
    public ReindexOperationHandler getReindexOperationHandler() {
        return reindexDispatcher.handler();
    }

    // ==================== Worker Access ====================

    /**
     * Returns the HNSW integration worker.
     *
     * @return HNSW worker
     */
    public HnswIntegrationWorker getHnswWorker() {
        return workers.hnsw();
    }

    /**
     * Returns the Cuckoo maintenance worker.
     *
     * @return Cuckoo worker
     */
    public CuckooMaintenanceWorker getCuckooWorker() {
        return workers.cuckoo();
    }

    /**
     * Returns the compaction worker for background tombstone cleanup.
     *
     * @return The compaction worker, or null when the storage engine exposes no Arena to allocate
     *         replacement segments out of
     */
    public CompactionWorker getCompactionWorker() {
        return workers.segmentCompaction();
    }

    // ==================== Compaction On Demand ====================

    /**
     * Compacts everything the policy says is worth compacting, now, on the calling thread.
     *
     * @return What the run examined, reclaimed and failed on
     */
    public CompactionReport compactNow() {
        return compactNow(false);
    }

    /**
     * Compacts segments and HNSW graphs on the calling thread and reports what happened.
     *
     * <p>Unforced, this applies exactly the thresholds the background workers apply, so it
     * brings forward work that would have happened anyway. Forced, it rewrites every live
     * segment and rebuilds every registered graph whether or not there is dead weight to
     * reclaim, which is what an operator wants after a bulk erasure and is otherwise wasted
     * I/O.</p>
     *
     * <h3>Running alongside the background workers</h3>
     * <p>Safe, and it takes no new lock to be so. A segment compaction holds that segment's own
     * write lock for its whole duration and re-checks under it that the segment is still open
     * and not splitting; so if the background loop is already rewriting a segment, this call
     * blocks on that lock and then finds the segment closed and skips it - and the reverse
     * holds too. A graph rebuild is serialised the same way by the graph's own write lock. No
     * segment and no graph is ever compacted twice concurrently, and neither caller has to know
     * the other exists.</p>
     *
     * <p>One segment failing never ends the run: the failure is logged with its cause and
     * counted in the report. The report is the honest answer - partial success is reported as
     * partial success rather than as either extreme.</p>
     *
     * <p>This form cannot be stopped short except by interrupting its thread, which is read at
     * the same points as the signal of {@link #compactNow(boolean, BooleanSupplier)} - but an
     * interrupt that lands inside a rewrite's channel operation closes that channel for every
     * thread using it, so anything that may need to stop a run should use that form instead.</p>
     *
     * @param force true to compact every live segment and graph regardless of the thresholds
     * @return What the run examined, reclaimed and failed on
     */
    public CompactionReport compactNow(boolean force) {
        return compactNow(force, () -> false);
    }

    /**
     * As {@link #compactNow(boolean)}, and stoppable.
     *
     * <h3>Stopping</h3>
     * <p>{@code stopRequested} is read before each segment, before the first graph and before
     * each further graph. Once it answers true the run examines nothing more, returns a report
     * of what it had done by then, and leaves unreached segments and graphs to the background
     * workers - they are not counted as considered. A rewrite or rebuild already under way is
     * finished rather than abandoned: it publishes files and swaps mappings in an order that
     * only makes sense run to completion.</p>
     *
     * <p>This is how a shutdown reclaims a long run: it sets the signal, waits for this call to
     * return, and only then closes the arena the segments are mapped out of - a run still going
     * would be copying entries out of memory being unmapped underneath it. It must not interrupt
     * the thread instead: every rewrite creates, maps, grows and forces files through channels
     * that queries and writers go on to share, and an interrupt reaching the run inside one of
     * those operations closes the channel for all of them. An interrupt that arrives anyway is
     * read as a stop at the same points and its flag is left set.</p>
     *
     * @param force         true to compact every live segment and graph regardless of the
     *                      thresholds
     * @param stopRequested Answers true once the run should stop at its next safe point
     * @return What the run examined, reclaimed and failed on
     * @throws NullPointerException if stopRequested is null
     */
    public CompactionReport compactNow(boolean force, BooleanSupplier stopRequested) {
        Objects.requireNonNull(stopRequested, "stopRequested cannot be null");
        return onDemandCompaction.run(force, stopRequested);
    }

    /**
     * Sets the hierarchical Cuckoo filter for deletion propagation.
     *
     * <p>When set, the Cuckoo maintenance worker will propagate deletions through
     * the hierarchy: segment → corpus. This ensures no stale false
     * positives from tombstoned entries.</p>
     *
     * <p>The filter is also stored locally to enable segment registration
     * in the hierarchy via {@link #registerSegment}.</p>
     *
     * <p><strong>CRITICAL:</strong> This method also registers all existing segments
     * with the new filter. This ensures that segments registered before the filter
     * was configured are properly included in the hierarchical filter cascade for
     * Stage 1 query pruning.</p>
     *
     * @param filter The hierarchical Cuckoo filter (may be null)
     */
    public void setHierarchicalCuckooFilter(HierarchicalCuckooFilter filter) {
        registry.setHierarchicalFilter(filter);
        LOGGER.info("Hierarchical Cuckoo filter {} for IndexOrchestrator",
            filter != null ? "configured" : "cleared");
    }

    /**
     * Returns the hierarchical Cuckoo filter for corpus → segment membership.
     *
     * <p>The hierarchical filter is used for:</p>
     * <ul>
     *   <li>Efficient query pruning via Stage 1 Cuckoo filter cascade</li>
     *   <li>Proper deletion propagation when segments are tombstoned</li>
     * </ul>
     *
     * @return The hierarchical Cuckoo filter, or null if not configured
     */
    public HierarchicalCuckooFilter getHierarchicalCuckooFilter() {
        return registry.hierarchicalFilter();
    }

    /**
     * Returns the segment re-index scheduler.
     *
     * @return Re-index scheduler
     */
    public SegmentReindexScheduler getReindexScheduler() {
        return workers.reindex();
    }

    /**
     * Returns the warming phase worker for PQ training.
     *
     * @return Warming phase worker
     */
    public WarmingPhaseWorker getWarmingWorker() {
        return workers.warming();
    }

    /**
     * Returns the HNSW compaction worker for removing tombstoned nodes.
     *
     * @return HNSW compaction worker
     */
    public HnswCompactionWorker getHnswCompactionWorker() {
        return workers.graphCompaction();
    }

    /**
     * Returns the total number of segments with PQ successfully trained.
     *
     * @return Total PQ trained count
     */
    public long getTotalPqTrained() {
        return workers.warming().getTotalPqTrained();
    }

    /**
     * Returns the total number of segments where PQ training failed.
     *
     * @return Total PQ failed count
     */
    public long getTotalPqFailed() {
        return workers.warming().getTotalPqFailed();
    }

    /**
     * Sets the PQ configuration for warming phase training.
     *
     * @param pqConfig The PQ configuration
     * @throws NullPointerException if pqConfig is null
     */
    public void setPqConfig(PQConfig pqConfig) {
        this.workers.warming().setPqConfig(pqConfig);
        LOGGER.info("PQ configuration updated: {}", pqConfig);
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "IndexOrchestrator{running=%s, segments=%d, hnswQueueDepth=%d, " +
            "pendingCuckooDeletions=%d, epoch=%d}",
            running.get(), registry.size(), getHnswQueueDepth(),
            getPendingCuckooDeletions(), visibility.currentEpoch());
    }
}

