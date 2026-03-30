package excalibur.shard;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.shard.allocator.VolumeManager;
import excalibur.shard.visibility.EpochIsolationValidator;
import excalibur.shard.worker.CuckooMaintenanceWorker;
import excalibur.shard.worker.CuckooMetricsWorker;
import excalibur.shard.worker.HnswCompactionWorker;
import excalibur.shard.worker.HnswIntegrationWorker;
import excalibur.shard.worker.SegmentReindexScheduler;
import excalibur.shard.worker.WarmingPhaseWorker;
import io.excalibur.vector.quantization.PQConfig;
import io.excalibur.config.Config;
import io.excalibur.store.CompactionWorker;
import io.excalibur.store.LifecycleState;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
public class IndexOrchestrator implements AutoCloseable {

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
     *   <li>Compact delay is informational; actual compaction uses segment lifecycle</li>
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

        return new IndexOrchestrator(
            catalog,
            storageEngine,
            volumeManager,
            shard.getHnswQueueLimit(),        // HNSW queue depth limit from config
            DEFAULT_CUCKOO_MAINTENANCE_INTERVAL_MS,  // Keep default: 5000ms
            DEFAULT_REINDEX_CHECK_INTERVAL_MS,       // Keep default: 60000ms
            DEFAULT_EPOCH_TICK_INTERVAL_MS           // Keep default: 100ms
        );
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
     * @param segmentId The segment ID
     */
    public void unregisterSegment(long segmentId) {
        SegmentIndexInfo info = segmentIndexInfo.remove(segmentId);
        if (info != null) {
            // Unregister from visibility tracker
            visibilityTracker.unregisterSegment(segmentId);

            if (info.graph != null) {
                hnswWorker.unregisterSegment(info.segment);
                // Unregister from warming worker
                warmingWorker.unregisterSegment(segmentId);
                // Unregister from HNSW compaction worker
                hnswCompactionWorker.unregisterGraph(info.graph);
            }
            // Unregister from Cuckoo workers
            cuckooWorker.unregisterFilter(segmentId);
            cuckooMetricsWorker.unregisterFilter(segmentId);
            compactionWorker.unregisterSegment(info.segment);

            // Unregister from hierarchical Cuckoo filter
            if (hierarchicalFilter != null) {
                String segmentIdStr = String.valueOf(segmentId);
                hierarchicalFilter.unregisterSegment(segmentIdStr);
                LOGGER.debug("Unregistered segment {} from hierarchical Cuckoo filter", segmentId);
            }

            LOGGER.debug("Unregistered segment {} from index orchestrator", segmentId);
        }
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
     * <p>This method handles the atomic swap of the old segment for the new
     * compacted segment. It:</p>
     * <ol>
     *   <li>Renames the new segment file from .compact to .dat</li>
     *   <li>Updates the StorageEngine's segment registry</li>
     *   <li>Updates the segmentIndexInfo map</li>
     *   <li>Updates the catalog to complete compaction</li>
     * </ol>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is called from the CompactionWorker's background thread.
     * All updates to shared state are thread-safe.</p>
     *
     * @param oldSegment The old segment (closed, to be replaced)
     * @param newSegment The new compacted segment (active, to be used)
     */
    private void onCompactionComplete(Segment oldSegment, Segment newSegment) {
        LOGGER.info("Compaction complete: {} -> {}", oldSegment.path(), newSegment.path());

        // Find the segment ID for the old segment
        Long segmentId = null;
        for (Map.Entry<Long, SegmentIndexInfo> entry : segmentIndexInfo.entrySet()) {
            if (entry.getValue().segment == oldSegment) {
                segmentId = entry.getKey();
                break;
            }
        }

        if (segmentId == null) {
            LOGGER.warn("Could not find segment ID for compacted segment: {}", oldSegment.path());
            return;
        }

        // Rename the new segment file from .compact to .dat
        Path compactPath = newSegment.path();
        if (compactPath.toString().endsWith(".compact")) {
            Path finalPath = Path.of(compactPath.toString().substring(0,
                compactPath.toString().length() - 8) + ".dat");
            try {
                java.nio.file.Files.move(compactPath, finalPath,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.debug("Renamed compacted segment: {} -> {}", compactPath, finalPath);
            } catch (java.io.IOException e) {
                LOGGER.error("Failed to rename compacted segment: {} -> {}", compactPath, finalPath, e);
                // Continue with the swap anyway - the segment is still valid
            }
        }

        // Update StorageEngine's segment registry
        boolean replaced = storageEngine.replaceSegment(segmentId, oldSegment, newSegment);
        if (!replaced) {
            LOGGER.warn("Failed to replace segment in StorageEngine registry for segment {}", segmentId);
        }

        // Update segmentIndexInfo with the new segment
        SegmentIndexInfo oldInfo = segmentIndexInfo.get(segmentId);
        if (oldInfo != null) {
            SegmentIndexInfo newInfo = new SegmentIndexInfo(
                segmentId, newSegment, oldInfo.graph, oldInfo.filter, oldInfo.tracker);
            segmentIndexInfo.put(segmentId, newInfo);
            LOGGER.debug("Updated segmentIndexInfo for segment {}", segmentId);
        }

        // Update catalog to complete compaction transition
        catalog.completeCompaction(segmentId);

        LOGGER.info("Compaction swap complete for segment {}: {} entries, registry updated",
            segmentId, newSegment.entryCount());
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
     * <p>If a {@link ReindexOperationHandler} is configured, this method
     * delegates to it. Otherwise, logs a warning and returns true (stub mode).</p>
     *
     * @param sourceId The source ID to re-index
     * @return true if reindex succeeded, false otherwise
     */
    private boolean triggerReindexOperation(long sourceId) {
        // If handler is not configured, log warning and complete
        if (reindexOperationHandler == null) {
            LOGGER.warn("Reindex triggered for source {} but ReindexOperationHandler not configured. " +
                "Reindex will not execute. Configure via setReindexOperationHandler().", sourceId);
            return true; // Return true to avoid blocking future scheduled reindexes
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
     * <p>Without this configuration, reindex operations operate in stub mode
     * (logging warnings but not blocking future reindexes).</p>
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
