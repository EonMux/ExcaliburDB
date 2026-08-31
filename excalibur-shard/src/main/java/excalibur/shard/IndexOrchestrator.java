package excalibur.shard;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.shard.allocator.VolumeManager;
import excalibur.shard.visibility.EpochIsolationValidator;
import excalibur.shard.worker.CuckooMaintenanceWorker;
import excalibur.shard.worker.CuckooMetricsWorker;
import excalibur.shard.worker.HnswCompactionWorker;
import excalibur.shard.worker.HnswIntegrationWorker;
import excalibur.shard.worker.SegmentGraphRebuilder;
import excalibur.shard.worker.SegmentReindexScheduler;
import excalibur.shard.worker.WarmingPhaseWorker;
import io.excalibur.vector.quantization.PQConfig;
import io.excalibur.config.Config;
import io.excalibur.store.CompactionWorker;
import io.excalibur.store.LifecycleState;
import java.lang.foreign.Arena;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.SplitWorker;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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
 * <p>The orchestrator ensures workers are started and stopped in the correct order:</p>
 * <ol>
 *   <li>Start: VisibilityTracker → EpochTickScheduler → CuckooMaintenanceWorker → CuckooMetricsWorker → HnswIntegrationWorker → HnswCompactionWorker → SegmentReindexScheduler</li>
 *   <li>Stop: SegmentReindexScheduler → HnswCompactionWorker → HnswIntegrationWorker → CuckooMetricsWorker → CuckooMaintenanceWorker → EpochTickScheduler</li>
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

    private final HnswIntegrationWorker hnswWorker;
    private final CuckooMaintenanceWorker cuckooWorker;
    private final SegmentReindexScheduler reindexScheduler;
    private final CompactionWorker compactionWorker;

    /**
     * HNSW compaction worker for removing tombstoned nodes from graphs.
     * Triggers compaction when tombstone ratio exceeds threshold.
     */
    private final HnswCompactionWorker hnswCompactionWorker;

    /**
     * Warming phase worker for PQ codebook training.
     * Trains ProductQuantizer during the WARMING phase of segment lifecycle.
     */
    private final WarmingPhaseWorker warmingWorker;

    /**
     * Cuckoo metrics worker for FP rate monitoring.
     * Periodically samples false positive rates from all registered Cuckoo filters.
     */
    private final CuckooMetricsWorker cuckooMetricsWorker;

    /**
     * Volume manager for disk space tracking and segment-to-volume assignment.
     * May be null if not configured.
     */
    private final VolumeManager volumeManager;

    /**
     * Visibility tracker for coordinating epochs across segments.
     * Provides epoch-based visibility for consistent query snapshots.
     */
    private final VisibilityTracker visibilityTracker;

    /**
     * Epoch isolation validator for preventing torn reads at the node level.
     * Tracks when individual HNSW nodes become visible and filters query results
     * to only include nodes visible at the query's captured epoch.
     */
    private final EpochIsolationValidator epochIsolationValidator;

    /**
     * Epoch tick scheduler for periodic epoch advancement.
     * Ensures writes become visible within one tick of fsync.
     */
    private final EpochTickScheduler epochTickScheduler;

    /**
     * Handler for reindex operations.
     * May be null if reindexing is handled externally or in stub mode.
     */
    private volatile ReindexOperationHandler reindexOperationHandler;

    /**
     * Hierarchical Cuckoo filter for corpus → cluster → segment membership testing.
     * Used to register segments in the hierarchy and enable efficient query pruning.
     * May be null if not configured.
     */
    private volatile HierarchicalCuckooFilter hierarchicalFilter;

    private final Map<Long, SegmentIndexInfo> segmentIndexInfo;

    /**
     * Scheduler for the segment directory reconciliation sweep.
     * Recreated by {@link #start()} when a previous {@link #close()} shut it down.
     */
    private volatile ScheduledExecutorService segmentSyncExecutor;

    /**
     * The scheduled reconciliation task, or null while the orchestrator is stopped.
     */
    private volatile ScheduledFuture<?> segmentSyncTask;

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
        if (catalog == null) {
            throw new NullPointerException("Catalog cannot be null");
        }
        if (storageEngine == null) {
            throw new NullPointerException("StorageEngine cannot be null");
        }

        this.catalog = catalog;
        this.storageEngine = storageEngine;
        this.volumeManager = volumeManager;
        this.segmentIndexInfo = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);

        // Create visibility tracker for coordinating epochs across segments
        this.visibilityTracker = new VisibilityTracker(catalog);

        // Create epoch isolation validator for node-level epoch tracking
        this.epochIsolationValidator = new EpochIsolationValidator();

        // Create epoch tick scheduler for periodic epoch advancement
        this.epochTickScheduler = new EpochTickScheduler(visibilityTracker, epochTickIntervalMs);

        // Create workers with listeners
        // Pass epoch isolation validator and visibility tracker to HNSW worker for node-level epoch tracking
        this.hnswWorker = new HnswIntegrationWorker(catalog,
            (segment, count) -> onVectorsIntegrated(segment, count),
            epochIsolationValidator,
            visibilityTracker,
            100, hnswQueueDepthLimit, 100);

        // The gauges report queue health as a fraction of a limit, and that limit lived in a
        // static nobody guaranteed matched the one being enforced: two config keys describe this
        // quantity and the construction paths did not agree on which. Publishing it from the
        // object that enforces it is what makes the gauge unable to disagree with reality -
        // "healthy" cannot be reported at the moment writes are being refused.
        io.excalibur.metrics.Metrics.setHnswQueueLimit(hnswQueueDepthLimit);

        this.cuckooWorker = new CuckooMaintenanceWorker(catalog, storageEngine,
            segmentId -> onSegmentFilterCleaned(segmentId),
            cuckooMaintenanceIntervalMs, 1000, 0.001);

        this.reindexScheduler = new SegmentReindexScheduler(catalog,
            sourceId -> onReindexNeeded(sourceId),
            reindexCheckIntervalMs, 3600000);

        // Create compaction worker for background tombstone cleanup
        // Uses Arena from storage engine for memory allocation
        Arena arena = storageEngine.arena();
        if (arena != null) {
            this.compactionWorker = new CompactionWorker(arena, (oldSegment, newSegment) -> {
                onCompactionComplete(oldSegment, newSegment);
            });
            LOGGER.info("CompactionWorker initialized with Panama FFM Arena");
        } else {
            // Fallback: Create CompactionWorker with a shared arena for non-Panama environments
            this.compactionWorker = new CompactionWorker(Arena.ofShared(), (oldSegment, newSegment) -> {
                onCompactionComplete(oldSegment, newSegment);
            });
            LOGGER.info("CompactionWorker initialized with fallback shared Arena");
        }

        // Create warming phase worker for PQ codebook training
        // This worker handles the WARMING → ACTIVE transition with PQ training
        this.warmingWorker = new WarmingPhaseWorker(catalog,
            (segmentId, pqSuccess, vectorCount) -> onWarmingComplete(segmentId, pqSuccess, vectorCount),
            100, 100, null);
        LOGGER.info("WarmingPhaseWorker initialized for PQ training");

        // Create HNSW compaction worker for removing tombstoned nodes
        // Uses default 1-minute interval and 20% tombstone threshold
        this.hnswCompactionWorker = new HnswCompactionWorker();
        this.hnswCompactionWorker.setCompactionListener(new HnswCompactionWorker.CompactionListener() {
            @Override
            public void onGraphCompacted(HnswGraph graph, long nodesRemoved) {
                onGraphCompactionComplete(graph, nodesRemoved);
            }

            @Override
            public void onGraphCompactionFailed(HnswGraph graph, Exception failure) {
                onGraphCompactionFailure(graph, failure);
            }
        });
        LOGGER.info("HnswCompactionWorker initialized for graph compaction");

        // Create Cuckoo metrics worker for FP rate monitoring
        // Uses same interval as CuckooMaintenanceWorker for consistency
        this.cuckooMetricsWorker = new CuckooMetricsWorker(cuckooMaintenanceIntervalMs);
        LOGGER.info("CuckooMetricsWorker initialized for FP rate monitoring");

        LOGGER.info("IndexOrchestrator created with hnswQueueDepthLimit={}, cuckooInterval={}ms, reindexInterval={}ms, epochTickInterval={}ms, volumeManager={}",
            hnswQueueDepthLimit, cuckooMaintenanceIntervalMs, reindexCheckIntervalMs, epochTickIntervalMs, volumeManager != null);
    }

    // ==================== Factory Methods ====================

    /**
     * Creates an IndexOrchestrator configured from Config.Shard settings.
     *
     * <p>This factory method wires the shard configuration values to the appropriate
     * orchestrator parameters:</p>
     * <ul>
     *   <li>{@code hnswQueueLimit} → HNSW integration queue depth limit</li>
     *   <li>{@code compactDelayMs} → Used as additional context for compaction timing</li>
     *   <li>{@code cuckooMaintenanceIntervalMs} → Uses default (5000ms)</li>
     *   <li>{@code reindexCheckIntervalMs} → Uses default (60000ms)</li>
     *   <li>{@code epochTickIntervalMs} → Uses default (100ms)</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>HNSW queue limit must be positive (enforced by Config.Shard validation)</li>
     *   <li>Compact delay is applied to the compaction worker's first check, so an operator
     *       can keep compaction off the back of a bulk load</li>
     * </ul>
     *
     * @param catalog The segment catalog
     * @param storageEngine The storage engine
     * @param volumeManager The volume manager (optional, may be null)
     * @param shard The shard configuration from Config.Shard
     * @return A new IndexOrchestrator configured with shard settings
     * @throws NullPointerException if catalog, storageEngine, or shard is null
     */
    public static IndexOrchestrator fromConfig(SegmentCatalog catalog,
                                                StorageEngine storageEngine,
                                                VolumeManager volumeManager,
                                                Config.Shard shard) {
        Objects.requireNonNull(catalog, "catalog cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(shard, "shard config cannot be null");

        LOGGER.info("Creating IndexOrchestrator from Config.Shard: hnswQueueLimit={}, compactDelayMs={}",
            shard.getHnswQueueLimit(), shard.getCompactDelayMs());

        IndexOrchestrator orchestrator = new IndexOrchestrator(
            catalog,
            storageEngine,
            volumeManager,
            shard.getHnswQueueLimit(),        // HNSW queue depth limit from config
            DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS,  // Keep default: 5000ms
            DEFAULT_REINDEX_CHECK_INTERVAL_MS,       // Keep default: 60000ms
            DEFAULT_EPOCH_TICK_INTERVAL_MS           // Keep default: 100ms
        );

        // The key was parsed, range-checked, shipped and logged at startup while reaching no
        // compaction code: an operator who set it to keep compaction away from a bulk load saw
        // it echoed back and got the same schedule as before.
        orchestrator.withCompactionStartDelayMs(shard.getCompactDelayMs());
        return orchestrator;
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
        if (compactionWorker != null) {
            compactionWorker.withStartDelayMs(delayMs);
        }
        return this;
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts all background workers.
     *
     * <p>Workers are started in order: EpochTickScheduler → CuckooMaintenanceWorker → HnswIntegrationWorker → WarmingPhaseWorker → SegmentReindexScheduler</p>
     *
     * @return true if all workers were started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            LOGGER.info("Starting index orchestrator...");

            // Start epoch tick scheduler first for visibility tracking
            epochTickScheduler.start();

            // Start workers in order
            cuckooWorker.start();
            cuckooMetricsWorker.start();
            hnswWorker.start();
            warmingWorker.start();
            hnswCompactionWorker.start();
            reindexScheduler.start();
            compactionWorker.start();

            // Converge the registrations with the storage engine's directory. Segments
            // created after boot - lazily on first write, or as the children of a split -
            // are otherwise known to no worker at all.
            startSegmentSync();

            LOGGER.info("Index orchestrator started successfully");
            return true;
        }
        return false;
    }

    /**
     * Stops all background workers.
     *
     * <p>Workers are stopped in reverse order: CompactionWorker → SegmentReindexScheduler → WarmingPhaseWorker → HnswIntegrationWorker → CuckooMaintenanceWorker → EpochTickScheduler</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            LOGGER.info("Stopping index orchestrator...");

            // Stop workers in reverse order
            stopSegmentSync();
            compactionWorker.stop();
            reindexScheduler.stop();
            hnswCompactionWorker.stop();
            warmingWorker.stop();
            hnswWorker.stop();
            cuckooMetricsWorker.stop();
            cuckooWorker.stop();

            // Stop epoch tick scheduler last
            epochTickScheduler.stop();

            LOGGER.info("Index orchestrator stopped. HNSW integrated: {}, Cuckoo deletions: {}, Reindexes: {}, Compactions: {}, HNSW compactions: {}, PQ trained: {}, Epoch ticks: {}, Cuckoo FP samples: {}",
                hnswWorker.getTotalIntegrated(), cuckooWorker.getTotalDeletions(),
                reindexScheduler.getTotalReindexesTriggered(), compactionWorker.getCompactionCount(),
                hnswCompactionWorker.getCompactionCount(),
                warmingWorker.getTotalPqTrained(), epochTickScheduler.getTickCount(),
                cuckooMetricsWorker.getTotalSamplesRecorded());
        }
    }

    @Override
    public void close() {
        stop();
        // Close the epoch tick scheduler to shut down its executor
        epochTickScheduler.close();

        ScheduledExecutorService executor = segmentSyncExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Schedules the segment directory reconciliation sweep.
     *
     * <p>The executor is recreated when a previous {@link #close()} shut it down, so a
     * stop/start cycle does not hand the scheduler a terminated executor.</p>
     */
    private void startSegmentSync() {
        // Register for split events as well as sweeping. The sweep alone converges eventually, but
        // between a split and the next sweep a query can route to a child that has no HNSW graph
        // or filter registered against it and simply find nothing. The observer closes that window;
        // the sweep stays as the backstop for everything a split event does not cover, such as
        // segments created directly on disk.
        storageEngine.addSplitObserver(this);

        ScheduledExecutorService executor = segmentSyncExecutor;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("excalibur-segment-sync").factory());
            segmentSyncExecutor = executor;
        }

        segmentSyncTask = executor.scheduleWithFixedDelay(
            this::runSegmentSync,
            DEFAULT_SEGMENT_SYNC_INTERVAL_MS,
            DEFAULT_SEGMENT_SYNC_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the reconciliation sweep without shutting its executor down.
     */
    private void stopSegmentSync() {
        // Deregister first. A split notification arriving after the orchestrator stopped would
        // register index structures nothing will ever close.
        storageEngine.removeSplitObserver(this);

        ScheduledFuture<?> task = segmentSyncTask;
        if (task != null) {
            task.cancel(false);
            segmentSyncTask = null;
        }
    }

    /**
     * Runs one reconciliation sweep.
     *
     * <p>A scheduled task that throws is never rescheduled, so a transient failure must
     * not be allowed to escape and silently retire the sweep for the process lifetime.</p>
     */
    private void runSegmentSync() {
        try {
            syncSegmentsFromStorage();
        } catch (RuntimeException e) {
            LOGGER.error("Segment directory reconciliation failed: {}", e.getMessage(), e);
        }
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
     * Registers a segment for index orchestration.
     *
     * <p>This registers the segment with all appropriate workers:</p>
     * <ul>
     *   <li>HNSW integration (if it has a graph)</li>
     *   <li>Cuckoo maintenance (if it has a filter)</li>
     *   <li>Cuckoo metrics (for FP rate monitoring, if it has a filter)</li>
     *   <li>Compaction worker (for tombstone cleanup)</li>
     *   <li>Warming phase worker (for PQ training during WARMING state)</li>
     *   <li>Hierarchical Cuckoo filter (for corpus → cluster → segment membership)</li>
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
        if (segment == null) {
            return;
        }

        // Store segment index info
        segmentIndexInfo.put(segmentId, new SegmentIndexInfo(segmentId, segment, graph, filter, tracker));

        // Register with visibility tracker for epoch-based visibility
        visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());

        // Register with HNSW worker if graph exists
        if (graph != null && tracker != null) {
            hnswWorker.registerSegment(segment, graph, tracker);
            // Register with warming worker for PQ training during WARMING phase
            warmingWorker.registerSegment(segmentId, segment, graph, tracker);
            // Register with HNSW compaction worker for tombstone cleanup
            hnswCompactionWorker.registerGraph(graph);
        }

        // Register with Cuckoo worker if filter exists
        if (filter != null) {
            cuckooWorker.registerFilter(segmentId, filter);
            // Register with Cuckoo metrics worker for FP rate monitoring
            cuckooMetricsWorker.registerFilter(segmentId, filter);
        }

        // Register with compaction worker for background tombstone cleanup
        compactionWorker.registerSegment(segment);

        // Register with hierarchical Cuckoo filter for corpus → cluster → segment membership
        // This enables efficient query pruning via Stage 1 Cuckoo filter cascade
        if (hierarchicalFilter != null) {
            String segmentIdStr = String.valueOf(segmentId);
            // Use "default" cluster for segments without explicit cluster assignment
            // This allows the hierarchy to function with a single default cluster
            hierarchicalFilter.registerSegment(segmentIdStr, "default");
            LOGGER.debug("Registered segment {} in hierarchical Cuckoo filter with cluster 'default'", segmentId);
        }

        LOGGER.debug("Registered segment {} with index orchestrator", segmentId);
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
        unregisterSegment(segmentId, true);
    }

    /**
     * Unregisters a segment, optionally keeping its place in the filter hierarchy.
     *
     * <p>An ID that is about to be re-registered with a different {@link Segment}
     * instance - a compacted replacement, or the left child of a split, which inherits
     * its parent's index - must keep its hierarchical Cuckoo filter. That filter holds
     * every term the segment contributed, and Stage 1 of the query pipeline treats a
     * filter miss as ground truth, so dropping and recreating it turns those terms into
     * empty query results until the data is ingested again.</p>
     *
     * @param segmentId           The segment ID
     * @param dropHierarchyEntry  Whether to remove the segment from the filter hierarchy
     */
    private void unregisterSegment(long segmentId, boolean dropHierarchyEntry) {
        SegmentIndexInfo info = segmentIndexInfo.remove(segmentId);
        if (info == null) {
            return;
        }

        // Unregister from visibility tracker
        visibilityTracker.unregisterSegment(segmentId);

        // Node visibility records are scoped by segment ID and must not outlive the
        // segment they describe.
        epochIsolationValidator.unregisterSegment(segmentId);

        // Unregister from workers keyed by segment ID
        warmingWorker.unregisterSegment(segmentId);
        cuckooWorker.unregisterFilter(segmentId);
        cuckooMetricsWorker.unregisterFilter(segmentId);

        if (!isSegmentRegisteredElsewhere(info.segment)) {
            hnswWorker.unregisterSegment(info.segment);
            compactionWorker.unregisterSegment(info.segment);
        }
        if (info.graph != null && !isGraphRegisteredElsewhere(info.graph)) {
            hnswCompactionWorker.unregisterGraph(info.graph);
        }

        // Unregister from hierarchical Cuckoo filter
        if (dropHierarchyEntry && hierarchicalFilter != null) {
            String segmentIdStr = String.valueOf(segmentId);
            hierarchicalFilter.unregisterSegment(segmentIdStr);
            LOGGER.debug("Unregistered segment {} from hierarchical Cuckoo filter", segmentId);
        }

        LOGGER.debug("Unregistered segment {} from index orchestrator", segmentId);
    }

    /**
     * Returns whether another registration still references a segment instance.
     *
     * @param segment The segment instance
     * @return true if some remaining registration holds this exact instance
     */
    private boolean isSegmentRegisteredElsewhere(Segment segment) {
        for (SegmentIndexInfo info : segmentIndexInfo.values()) {
            if (info.segment == segment) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether another registration still references a graph instance.
     *
     * @param graph The graph instance
     * @return true if some remaining registration holds this exact instance
     */
    private boolean isGraphRegisteredElsewhere(HnswGraph graph) {
        for (SegmentIndexInfo info : segmentIndexInfo.values()) {
            if (info.graph == graph) {
                return true;
            }
        }
        return false;
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
        Map<Long, Segment> live = storageEngine.getSegments();
        int changes = 0;

        for (Map.Entry<Long, Segment> entry : live.entrySet()) {
            long segmentId = entry.getKey();
            Segment segment = entry.getValue();

            SegmentIndexInfo info = segmentIndexInfo.get(segmentId);
            if (info != null && info.segment == segment) {
                continue;
            }

            CuckooFilter filter = info != null ? info.filter : null;
            if (info != null) {
                // The ID is re-registered immediately below, so its filter hierarchy
                // entry - which still describes this ID's terms - is kept.
                unregisterSegment(segmentId, false);
            }
            registerSegment(segmentId, segment, segment.getHnswGraph(), filter,
                segment.getTailOffsetTracker());
            changes++;
        }

        for (Long segmentId : List.copyOf(segmentIndexInfo.keySet())) {
            if (!live.containsKey(segmentId)) {
                unregisterSegment(segmentId);
                changes++;
            }
        }

        if (changes > 0) {
            LOGGER.info("Segment registry reconciled with storage directory: {} change(s), "
                + "{} segment(s) registered", changes, segmentIndexInfo.size());
        }
        return changes;
    }

    /**
     * Returns the number of registered segments.
     *
     * @return Registered segment count
     */
    public int getRegisteredSegmentCount() {
        return segmentIndexInfo.size();
    }

    /**
     * Marks a segment as tombstoned for visibility tracking.
     *
     * <p>This method should be called when a segment transitions to TOMBSTONED state.
     * It records the tombstone epoch in the visibility tracker, ensuring that:</p>
     * <ul>
     *   <li>New queries (capturing epoch >= tombstone epoch) will not see this segment</li>
     *   <li>In-flight queries (capturing epoch < tombstone epoch) will still see the segment</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This method only updates the visibility tracker.
     * The caller is responsible for updating the segment catalog state.</p>
     *
     * @param segmentId The segment ID to tombstone
     * @throws IllegalArgumentException if the segment is not registered with the visibility tracker
     */
    public void tombstoneSegment(long segmentId) {
        long tombstoneEpoch = visibilityTracker.currentEpoch();
        visibilityTracker.markTombstoned(segmentId, tombstoneEpoch);
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
        visibilityTracker.markTombstoned(segmentId, tombstoneEpoch);
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
        return hnswWorker.getCurrentQueueDepth();
    }

    /**
     * Returns the HNSW queue depth limit.
     *
     * @return Queue depth limit
     */
    public int getHnswQueueDepthLimit() {
        return hnswWorker.getQueueDepthLimit();
    }

    /**
     * Checks if backpressure is currently being applied.
     *
     * @return true if backpressured
     */
    public boolean isBackpressured() {
        return hnswWorker.isBackpressured();
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

        if (!hnswWorker.isBackpressured()) {
            return true;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long pollIntervalNs = TimeUnit.MILLISECONDS.toNanos(10); // 10ms poll interval

        while (hnswWorker.isBackpressured() && System.nanoTime() < deadline) {
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

        boolean released = !hnswWorker.isBackpressured();
        if (!released) {
            LOGGER.warn("Backpressure timeout after {}ms, queue depth: {}",
                timeoutMs, hnswWorker.getCurrentQueueDepth());
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
     * Returns the number of pending Cuckoo filter deletions.
     *
     * @return Pending deletion count
     */
    public int getPendingCuckooDeletions() {
        return cuckooWorker.getPendingDeletionCount();
    }

    /**
     * Returns the number of sources scheduled for re-index.
     *
     * @return Scheduled source count
     */
    public int getScheduledReindexCount() {
        return reindexScheduler.getRegisteredSourceCount();
    }

    /**
     * Returns the current visibility epoch.
     *
     * @return Visibility epoch
     */
    public long getVisibilityEpoch() {
        return visibilityTracker.currentEpoch();
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
        return visibilityTracker.advanceEpoch();
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
        return visibilityTracker;
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
        return epochIsolationValidator;
    }

    /**
     * Returns the epoch tick scheduler.
     *
     * @return The epoch tick scheduler
     */
    public EpochTickScheduler getEpochTickScheduler() {
        return epochTickScheduler;
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of vectors integrated into HNSW graphs.
     *
     * @return Total vectors integrated
     */
    public long getTotalVectorsIntegrated() {
        return hnswWorker.getTotalIntegrated();
    }

    /**
     * Returns the total number of Cuckoo filter deletions.
     *
     * @return Total deletions
     */
    public long getTotalCuckooDeletions() {
        return cuckooWorker.getTotalDeletions();
    }

    /**
     * Returns the total number of re-indexes triggered.
     *
     * @return Total re-indexes triggered
     */
    public long getTotalReindexesTriggered() {
        return reindexScheduler.getTotalReindexesTriggered();
    }

    /**
     * Returns the total number of completed re-indexes.
     *
     * @return Total re-indexes completed
     */
    public long getTotalReindexesCompleted() {
        return reindexScheduler.getTotalReindexesCompleted();
    }

    // ==================== Event Handlers ====================

    /**
     * Called when vectors are integrated into the HNSW graph.
     */
    private void onVectorsIntegrated(Segment segment, int count) {
        // Update segment index info
        for (SegmentIndexInfo info : segmentIndexInfo.values()) {
            if (info.segment == segment) {
                info.vectorsIntegrated += count;
                break;
            }
        }

        // Note: Epoch is advanced automatically by EpochTickScheduler
        // Immediate visibility can be requested via epochTickScheduler.tickNow() if needed
    }

    /**
     * Called when a segment's filter is cleaned after tombstoning.
     */
    private boolean onSegmentFilterCleaned(long segmentId) {
        LOGGER.info("Filter cleaned for tombstoned segment {}", segmentId);
        return true;
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

        // Update segment index info if present
        SegmentIndexInfo info = segmentIndexInfo.get(segmentId);
        if (info != null) {
            info.pqTrained = pqSuccess;
        }
    }

    /**
     * Called when compaction completes for a segment.
     *
     * <p>The store's compaction worker owns the file swap: by the time this runs the
     * compacted file already sits at the segment's canonical {@code segment_N.dat} name
     * and {@code newSegment} points at it. This method owns everything the store cannot
     * see - the index structures attached to the segment and the worker registrations
     * that are keyed by segment identity.</p>
     *
     * <ol>
     *   <li>Carries the HNSW graph, its tail tracker and the term frequency sketch onto
     *       the replacement</li>
     *   <li>Swaps the StorageEngine's registry entry</li>
     *   <li>Re-points every worker that held the old {@link Segment} instance</li>
     *   <li>Completes the catalog's compaction transition</li>
     * </ol>
     *
     * <h3>Thread Safety</h3>
     * <p>This runs on the compaction worker's thread while the old segment is still open
     * and its write lock is held by that thread. Nothing here may close the old segment,
     * take its write lock, or block: every call below is a lock-free registry update or
     * touches only the replacement segment.</p>
     *
     * @param oldSegment The segment that was compacted (still open, write lock held)
     * @param newSegment The compacted replacement, already installed at the canonical path
     */
    private void onCompactionComplete(Segment oldSegment, Segment newSegment) {
        try {
            applyCompactionSwap(oldSegment, newSegment);
        } catch (RuntimeException e) {
            // Throwing here would make the compaction worker discard and close a segment it
            // has already installed at the canonical path. The reconciliation sweep repairs
            // whatever part of the bookkeeping did not complete.
            LOGGER.error("Compaction bookkeeping failed for {}: {}. The compacted file is "
                + "installed; the segment registry will be reconciled by the next sweep.",
                newSegment.path(), e.getMessage(), e);
        }
    }

    /**
     * Performs the registry and worker half of a completed compaction.
     *
     * @param oldSegment The segment that was compacted
     * @param newSegment The compacted replacement
     */
    private void applyCompactionSwap(Segment oldSegment, Segment newSegment) {
        long segmentId = registeredSegmentId(oldSegment);
        if (segmentId < 0) {
            // Not registered here, but the routing directory still has to be re-pointed:
            // the compaction worker closes the old segment as soon as this returns, and a
            // directory entry pointing at a closed segment fails every read and write.
            segmentId = directoryIndexOf(oldSegment);
        }
        if (segmentId < 0) {
            LOGGER.warn("Could not find segment ID for compacted segment: {}", oldSegment.path());
            return;
        }

        LOGGER.info("Compaction complete for segment {}: {} -> {}",
            segmentId, oldSegment.path(), newSegment.path());

        SegmentIndexInfo oldInfo = segmentIndexInfo.get(segmentId);
        HnswGraph graph = oldInfo != null && oldInfo.graph != null
            ? oldInfo.graph : oldSegment.getHnswGraph();
        TailOffsetTracker tracker = oldInfo != null && oldInfo.tracker != null
            ? oldInfo.tracker : oldSegment.getTailOffsetTracker();
        CuckooFilter filter = oldInfo != null ? oldInfo.filter : null;

        // Quiesce the old registration before touching anything it shares. The integration
        // worker holds this very tracker and the old segment's still-populated write log, and it
        // takes no lock: a cycle that starts after the rebase re-reads the old log from zero and
        // inserts every vector a second time, and a batch already in flight completes and pushes
        // the offset back up to one measured against a log the replacement does not have.
        hnswWorker.unregisterSegment(oldSegment);
        compactionWorker.unregisterSegment(oldSegment);

        boolean installed = false;
        try {
            carryIndexAttachments(oldSegment, newSegment, graph, tracker);
            installed = storageEngine.replaceSegment(segmentId, oldSegment, newSegment);
        } finally {
            if (!installed) {
                // Put back what was quiesced, so a failed swap leaves the old segment served
                // and indexed exactly as it was.
                if (graph != null && tracker != null) {
                    hnswWorker.registerSegment(oldSegment, graph, tracker);
                }
                compactionWorker.registerSegment(oldSegment);
            }
        }

        if (!installed) {
            LOGGER.error("Compacted segment {} could not be installed in the storage registry; "
                + "leaving the existing registration in place", segmentId);
            return;
        }

        repointWorkers(segmentId, oldSegment, newSegment, graph, filter, tracker);

        // Update catalog to complete compaction transition
        catalog.completeCompaction(segmentId);

        LOGGER.info("Compaction swap complete for segment {}: {} entries, registry updated",
            segmentId, newSegment.entryCount());
    }

    /**
     * Moves the index structures a segment carries onto its compacted replacement.
     *
     * <p>Compaction rewrites the segment's data only. The HNSW graph, its tail offset
     * tracker and the term frequency sketch describe exactly the same entries and belong
     * to the replacement - the query path reads them off the segment
     * ({@code segment.getHnswGraph()}), so a replacement without them drops the whole
     * segment out of vector search with no error.</p>
     *
     * <p>The LSH index is deliberately <strong>not</strong> carried: it is owned by the
     * old segment, whose {@code close()} closes it as soon as this listener returns, and
     * the ingestion pipeline re-opens it from its persisted directory - named after the
     * unchanged segment path - on the next write.</p>
     *
     * @param oldSegment The compacted segment
     * @param newSegment The replacement
     * @param graph      The HNSW graph to carry, or null if the segment has none
     * @param tracker    The tail offset tracker to carry, or null
     */
    private void carryIndexAttachments(Segment oldSegment, Segment newSegment,
                                       HnswGraph graph, TailOffsetTracker tracker) {
        if (graph != null) {
            // Take ownership away from the old segment before attaching. The store closes the old
            // segment as soon as this listener returns, and Segment.close() now closes whatever
            // graph is still attached — which would release the mappings out from under the
            // replacement that is about to serve queries from the same graph.
            oldSegment.detachHnswGraph();
            newSegment.attachHnswGraph(graph);
        }
        if (tracker != null) {
            newSegment.attachTailOffsetTracker(tracker);
            // Entries between the checkpoint and the old log's head were copied into the
            // replacement's data file and were never indexed: the integration worker reads only
            // from the write log, and compaction has just discarded the one they were in.
            // Rebasing alone would leave them retrievable by key and absent from vector search
            // for good. Under sustained ingest the worker is always behind, so this is the
            // ordinary case, not an unlucky one.
            if (graph != null && hasUnintegratedBacklog(oldSegment, tracker)) {
                LOGGER.info("Compaction of {} left {} bytes of write log unintegrated; draining "
                    + "them before the log is discarded",
                    oldSegment.path(), oldSegment.getWriteLogHead()
                        - tracker.getLastIntegratedOffset());
                // The old log is unlinked but still mapped by the WriteLog object the old
                // segment holds, so its entries are readable right up until that segment closes.
                hnswWorker.drainPendingIntegration(oldSegment, graph, tracker);
            }
            rebaseCheckpointIfStale(newSegment, tracker);
        }

        excalibur.filter.sketch.CountMinSketch sketch = oldSegment.getTermFrequencySketch();
        if (sketch != null) {
            newSegment.attachTermFrequencySketch(sketch);
        }
    }

    /**
     * Whether a segment's write log holds entries the graph never saw.
     *
     * @param oldSegment The segment being replaced, whose log is still readable
     * @param tracker    Its integration checkpoint
     * @return true when the checkpoint is behind the log's head
     */
    private boolean hasUnintegratedBacklog(Segment oldSegment, TailOffsetTracker tracker) {
        return oldSegment.getWriteLogHead() > tracker.getLastIntegratedOffset();
    }

    /**
     * Reports an integration checkpoint that no longer indexes into the segment's log.
     *
     * <p>The compacted segment starts from an empty write log, while the HNSW integration
     * checkpoint is a byte offset into the log that compaction discarded. Left as it is, every
     * entry appended after the compaction sits below the checkpoint and is never integrated, so
     * the segment silently accepts vectors that no query can find.</p>
     *
     * @param newSegment The compacted replacement
     * @param tracker    The carried tail offset tracker
     */
    private void rebaseCheckpointIfStale(Segment newSegment, TailOffsetTracker tracker) {
        rebaseCheckpointIfStale(newSegment, tracker, "compaction");
    }

    /**
     * Rebases an integration checkpoint that outran the write log it points into.
     *
     * <p>The checkpoint is a byte offset into a specific write log. Two paths hand a segment a
     * different log than the one its checkpoint was measured against:
     * <ul>
     *   <li><b>compaction</b>, which rewrites the live entries into a new file;</li>
     *   <li><b>a split</b>, where the left child takes over the parent's HNSW file - checkpoint
     *       and all - while {@code prepareSplitChild} gives it a brand new, empty log.</li>
     * </ul>
     *
     * <p>In both the checkpoint is larger than the new log's head, and the integration worker
     * only looks for work when {@code lastIntegratedOffset < head}. Left alone it never finds
     * any: everything written to that segment from then on is stored, acknowledged, and never
     * indexed. Nothing fails; the segment simply stops answering vector queries about its own
     * newest data.
     *
     * <p>Rebasing onto the head is right rather than merely safe. In both cases the graph
     * already holds every vector that survived into the new file, so there is nothing below the
     * head left to integrate.
     *
     * @param newSegment The segment now holding the log
     * @param tracker    Its integration checkpoint
     * @param cause      What handed it a different log, for the log message
     */
    private void rebaseCheckpointIfStale(Segment newSegment, TailOffsetTracker tracker,
                                         String cause) {
        if (tracker == null) {
            return;
        }
        long checkpoint = tracker.getLastIntegratedOffset();
        long head = newSegment.getWriteLogHead();
        if (checkpoint <= head) {
            return;
        }
        tracker.resetTo(head);
        LOGGER.info("Rebased HNSW integration checkpoint for {} from {} to the new write log's "
            + "head {} after {}; without this every subsequent append would fall below the "
            + "checkpoint and never be integrated.",
            newSegment.path(), checkpoint, head, cause);
    }

    /**
     * Re-points every worker registration from the old segment instance to its replacement.
     *
     * <p>The HNSW integration worker and the store's compaction worker key registrations
     * by {@link Segment} identity, so a swap that updates only the maps leaves both of
     * them polling a segment whose write log no longer receives appends, and leaves the
     * replacement registered with nobody.</p>
     *
     * @param segmentId  The segment ID
     * @param oldSegment The compacted segment
     * @param newSegment The replacement
     * @param graph      The carried HNSW graph, or null
     * @param filter     The carried Cuckoo filter, or null
     * @param tracker    The carried tail offset tracker, or null
     */
    private void repointWorkers(long segmentId, Segment oldSegment, Segment newSegment,
                                HnswGraph graph, CuckooFilter filter, TailOffsetTracker tracker) {
        hnswWorker.unregisterSegment(oldSegment);
        compactionWorker.unregisterSegment(oldSegment);

        if (graph != null && tracker != null) {
            hnswWorker.registerSegment(newSegment, graph, tracker);
            warmingWorker.unregisterSegment(segmentId);
            warmingWorker.registerSegment(segmentId, newSegment, graph, tracker);
            // The graph itself is unchanged, so its HNSW compaction registration stands.
        }

        compactionWorker.registerSegment(newSegment);

        SegmentIndexInfo newInfo = new SegmentIndexInfo(
            segmentId, newSegment, graph, filter, tracker);
        segmentIndexInfo.put(segmentId, newInfo);
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
        try {
            long parentId = registeredSegmentId(parent);
            if (parentId >= 0) {
                // The left child inherits the parent's directory index, and with it the
                // parent's place in the filter hierarchy: the terms it holds are still
                // the terms of the keys that index serves.
                unregisterSegment(parentId, false);
            }

            installSplitChild(leftChild);
            installSplitChild(rightChild);

            LOGGER.info("Split published for segment {}: children {} and {} registered",
                parentId, leftChild.path(), rightChild.path());
        } catch (RuntimeException e) {
            // The split is already published in the routing directory by the time this
            // runs, so failing it back to the worker would discard children that keys
            // already route to. The reconciliation sweep repairs the registrations.
            LOGGER.error("Split bookkeeping failed for parent {}: {}. The registry will be "
                + "reconciled by the next sweep.", parent.path(), e.getMessage(), e);
        }
    }

    /**
     * Registers one split child under the directory index it now occupies.
     *
     * @param child The child segment
     */
    private void installSplitChild(Segment child) {
        // First, and before the directory check: the left child inherits the parent's HNSW file
        // and with it the parent's integration checkpoint, measured against a write log that
        // prepareSplitChild has just replaced with an empty one. Doing this after the early
        // return below would leave a child that is not yet in the directory holding a stale
        // checkpoint, and the reconciliation sweep that eventually registers it would bake that
        // in -- the same silent failure, reached a few seconds later.
        TailOffsetTracker tracker = child.getTailOffsetTracker();
        rebaseCheckpointIfStale(child, tracker, "a split");

        // A split gives each child a share of the parent's entries and a brand new, empty log.
        // Vectors only ever reach a graph through that log, so without this the child's data is
        // retrievable by key and invisible to vector search until something re-ingests it -
        // which nothing does.
        HnswGraph childGraph = child.getHnswGraph();
        if (childGraph != null && childGraph.nodeCount() == 0 && child.entryCount() > 0) {
            SegmentGraphRebuilder.rebuild(child, childGraph, tracker);
        }

        long childId = directoryIndexOf(child);
        if (childId < 0) {
            LOGGER.warn("Split child {} is not in the storage directory; it will be picked up "
                + "by the next reconciliation sweep", child.path());
            return;
        }

        registerSegment(childId, child, child.getHnswGraph(), null, tracker);
    }

    /**
     * Called after a segment's HNSW graph was compacted.
     *
     * <p>Compaction re-inserts the surviving nodes into a fresh graph, so every node ID
     * changes. Node visibility records are keyed by node ID, so retaining them would map
     * live nodes to unrelated epochs; they are dropped, which is correct because every
     * surviving node predates the compaction and is visible to every query.</p>
     *
     * @param graph        The compacted graph
     * @param nodesRemoved Number of tombstoned nodes removed
     */
    private void onGraphCompactionComplete(HnswGraph graph, long nodesRemoved) {
        long segmentId = segmentIdOfGraph(graph);
        if (segmentId < 0) {
            LOGGER.debug("Compacted graph {} belongs to no registered segment", graph.path());
            return;
        }

        epochIsolationValidator.resetSegmentNodeEpochs(segmentId);
        LOGGER.info("HNSW graph for segment {} compacted: {} nodes removed, node visibility "
            + "records reset", segmentId, nodesRemoved);
    }

    /**
     * Called when an HNSW graph compaction attempt failed.
     *
     * <p>A failure that released the graph's mapping leaves it closed. Continuing to feed
     * such a graph makes every integration cycle throw, so the segment is retired from the
     * workers that write to it. The segment's stored entries stay readable through the
     * storage engine.</p>
     *
     * @param graph   The graph that failed to compact
     * @param failure The failure
     */
    private void onGraphCompactionFailure(HnswGraph graph, Exception failure) {
        long segmentId = segmentIdOfGraph(graph);
        if (segmentId < 0 || !graph.isClosed()) {
            return;
        }

        SegmentIndexInfo info = segmentIndexInfo.get(segmentId);
        if (info != null) {
            hnswWorker.unregisterSegment(info.segment);
            warmingWorker.unregisterSegment(segmentId);
        }
        LOGGER.error("HNSW graph for segment {} was left closed by a failed compaction; the "
            + "segment is no longer indexed and is unsearchable until it is reopened: {}",
            segmentId, failure.getMessage());
    }

    /**
     * Returns the ID a segment instance is registered under.
     *
     * @param segment The segment instance
     * @return The segment ID, or -1 if the instance is not registered
     */
    private long registeredSegmentId(Segment segment) {
        for (Map.Entry<Long, SegmentIndexInfo> entry : segmentIndexInfo.entrySet()) {
            if (entry.getValue().segment == segment) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Returns the ID of the registered segment owning a graph instance.
     *
     * @param graph The graph instance
     * @return The segment ID, or -1 if no registered segment holds it
     */
    private long segmentIdOfGraph(HnswGraph graph) {
        for (Map.Entry<Long, SegmentIndexInfo> entry : segmentIndexInfo.entrySet()) {
            if (entry.getValue().graph == graph) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Returns the directory index the storage engine holds a segment instance at.
     *
     * @param segment The segment instance
     * @return The directory index, or -1 if the engine does not hold this instance
     */
    private long directoryIndexOf(Segment segment) {
        for (Map.Entry<Long, Segment> entry : storageEngine.getSegments().entrySet()) {
            if (entry.getValue() == segment) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Called when a re-index is needed for a source.
     *
     * <p>This method triggers the re-index operation and properly manages
     * the reindexInProgress flag by calling markReindexComplete() on success
     * or markReindexFailed() on failure. This ensures subsequent scheduled
     * reindexes are not permanently blocked.</p>
     *
     * <p>If a {@link ReindexOperationHandler} is configured via
     * {@link #setReindexOperationHandler(ReindexOperationHandler)}, this method
     * will delegate to it. Otherwise, it logs a warning and completes without
     * performing any actual reindexing.</p>
     */
    private boolean onReindexNeeded(long sourceId) {
        LOGGER.info("Re-index needed for source {}", sourceId);
        try {
            // Trigger the ingestion pipeline for reindex
            boolean success = triggerReindexOperation(sourceId);

            if (success) {
                // Mark as complete to allow future scheduled reindexes
                reindexScheduler.markReindexComplete(sourceId);
                LOGGER.info("Re-index completed successfully for source {}", sourceId);
            } else {
                reindexScheduler.markReindexFailed(sourceId, "Reindex returned failure");
            }
            return success;
        } catch (Exception e) {
            LOGGER.error("Re-index failed for source {}: {}", sourceId, e.getMessage());
            reindexScheduler.markReindexFailed(sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Triggers the actual re-index operation for a source.
     *
     * <p>If a {@link ReindexOperationHandler} is configured, this method delegates to it.
     * Without one, no document is touched, so this reports failure: reporting success
     * would stamp {@code lastReindexTime} and the completed counter for a re-index that
     * never ran, which resets the source's staleness clock and hides the missing wiring.
     * The failure path still clears {@code reindexInProgress}, so future scheduled
     * re-indexes are not blocked.</p>
     *
     * @param sourceId The source ID to re-index
     * @return true if reindex succeeded, false otherwise
     */
    private boolean triggerReindexOperation(long sourceId) {
        // If handler is not configured, the reindex cannot run
        if (reindexOperationHandler == null) {
            LOGGER.warn("Reindex triggered for source {} but ReindexOperationHandler not configured. " +
                "Reindex will not execute. Configure via setReindexOperationHandler().", sourceId);
            return false;
        }

        // Delegate to the handler
        LOGGER.debug("Executing reindex operation for source {} via ReindexOperationHandler", sourceId);
        try {
            return reindexOperationHandler.executeReindex(String.valueOf(sourceId));
        } catch (Exception e) {
            LOGGER.error("ReindexOperationHandler threw exception for source {}: {}", sourceId, e.getMessage());
            return false;
        }
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
        this.reindexOperationHandler = handler;
        LOGGER.info("ReindexOperationHandler {} for IndexOrchestrator",
            handler != null ? "configured" : "cleared");
    }

    /**
     * Returns the configured reindex operation handler.
     *
     * @return The handler, or null if not configured
     */
    public ReindexOperationHandler getReindexOperationHandler() {
        return reindexOperationHandler;
    }

    // ==================== Worker Access ====================

    /**
     * Returns the HNSW integration worker.
     *
     * @return HNSW worker
     */
    public HnswIntegrationWorker getHnswWorker() {
        return hnswWorker;
    }

    /**
     * Returns the Cuckoo maintenance worker.
     *
     * @return Cuckoo worker
     */
    public CuckooMaintenanceWorker getCuckooWorker() {
        return cuckooWorker;
    }

    /**
     * Returns the compaction worker for background tombstone cleanup.
     *
     * @return Compaction worker
     */
    public CompactionWorker getCompactionWorker() {
        return compactionWorker;
    }

    /**
     * Sets the hierarchical Cuckoo filter for deletion propagation.
     *
     * <p>When set, the Cuckoo maintenance worker will propagate deletions through
     * the hierarchy: segment → cluster → corpus. This ensures no stale false
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
        this.hierarchicalFilter = filter;
        this.cuckooWorker.setHierarchicalFilter(filter);

        // Register all existing segments with the new hierarchical filter
        // This is CRITICAL for Stage 1 Cuckoo filter cascade to function correctly
        if (filter != null) {
            int registeredCount = 0;
            for (Map.Entry<Long, SegmentIndexInfo> entry : segmentIndexInfo.entrySet()) {
                long segmentId = entry.getKey();
                String segmentIdStr = String.valueOf(segmentId);
                // Use "default" cluster for segments without explicit cluster assignment
                // This allows the hierarchy to function with a single default cluster
                filter.registerSegment(segmentIdStr, "default");
                registeredCount++;
            }
            if (registeredCount > 0) {
                LOGGER.info("Registered {} existing segments with hierarchical Cuckoo filter", registeredCount);
            }
        }

        LOGGER.info("Hierarchical Cuckoo filter {} for IndexOrchestrator",
            filter != null ? "configured" : "cleared");
    }

    /**
     * Returns the hierarchical Cuckoo filter for corpus → cluster → segment membership.
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
        return hierarchicalFilter;
    }

    /**
     * Returns the segment re-index scheduler.
     *
     * @return Re-index scheduler
     */
    public SegmentReindexScheduler getReindexScheduler() {
        return reindexScheduler;
    }

    /**
     * Returns the warming phase worker for PQ training.
     *
     * @return Warming phase worker
     */
    public WarmingPhaseWorker getWarmingWorker() {
        return warmingWorker;
    }

    /**
     * Returns the HNSW compaction worker for removing tombstoned nodes.
     *
     * @return HNSW compaction worker
     */
    public HnswCompactionWorker getHnswCompactionWorker() {
        return hnswCompactionWorker;
    }

    /**
     * Returns the total number of segments with PQ successfully trained.
     *
     * @return Total PQ trained count
     */
    public long getTotalPqTrained() {
        return warmingWorker.getTotalPqTrained();
    }

    /**
     * Returns the total number of segments where PQ training failed.
     *
     * @return Total PQ failed count
     */
    public long getTotalPqFailed() {
        return warmingWorker.getTotalPqFailed();
    }

    /**
     * Sets the PQ configuration for warming phase training.
     *
     * @param pqConfig The PQ configuration
     * @throws NullPointerException if pqConfig is null
     */
    public void setPqConfig(io.excalibur.vector.quantization.PQConfig pqConfig) {
        this.warmingWorker.setPqConfig(pqConfig);
        LOGGER.info("PQ configuration updated: {}", pqConfig);
    }

    // ==================== Internal Classes ====================

    /**
     * Holder for segment index information.
     */
    private static class SegmentIndexInfo {
        final long segmentId;
        final Segment segment;
        final HnswGraph graph;
        final CuckooFilter filter;
        final TailOffsetTracker tracker;

        volatile long vectorsIntegrated;
        volatile long filterDeletions;
        volatile boolean pqTrained;

        SegmentIndexInfo(long segmentId, Segment segment, HnswGraph graph,
                          CuckooFilter filter, TailOffsetTracker tracker) {
            this.segmentId = segmentId;
            this.segment = segment;
            this.graph = graph;
            this.filter = filter;
            this.tracker = tracker;
            this.vectorsIntegrated = 0;
            this.filterDeletions = 0;
            this.pqTrained = false;
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "IndexOrchestrator{running=%s, segments=%d, hnswQueueDepth=%d, " +
            "pendingCuckooDeletions=%d, epoch=%d}",
            running.get(), segmentIndexInfo.size(), getHnswQueueDepth(),
            getPendingCuckooDeletions(), visibilityTracker.currentEpoch());
    }
}
