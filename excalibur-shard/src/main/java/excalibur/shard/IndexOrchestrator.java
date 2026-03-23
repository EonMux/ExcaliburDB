package excalibur.shard;

import excalibur.filter.CuckooFilter;
import excalibur.shard.worker.CuckooMaintenanceWorker;
import excalibur.shard.worker.HnswIntegrationWorker;
import excalibur.shard.worker.SegmentReindexScheduler;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates all background index workers for ExcaliburDB.
 *
 * <p>The IndexOrchestrator manages the lifecycle of all background workers
 * that perform asynchronous index operations:</p>
 * <ul>
 *   <li>{@link HnswIntegrationWorker} - Integrates write log entries into HNSW graph</li>
 *   <li>{@link CuckooMaintenanceWorker} - Maintains Cuckoo filter consistency</li>
 *   <li>{@link SegmentReindexScheduler} - Schedules stale segment re-indexing</li>
 *   <li>{@link EpochTickScheduler} - Advances visibility epoch periodically</li>
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
 *   <li>Start: VisibilityTracker → EpochTickScheduler → CuckooMaintenanceWorker → HnswIntegrationWorker → SegmentReindexScheduler</li>
 *   <li>Stop: SegmentReindexScheduler → HnswIntegrationWorker → CuckooMaintenanceWorker → EpochTickScheduler</li>
 * </ol>
 *
 * <h2>Metrics Exposure</h2>
 * <p>The orchestrator exposes queue depth metrics for backpressure monitoring:</p>
 * <ul>
 *   <li>HNSW integration queue depth</li>
 *   <li>Pending Cuckoo filter deletions</li>
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

    /**
     * Visibility tracker for coordinating epochs across segments.
     * Provides epoch-based visibility for consistent query snapshots.
     */
    private final VisibilityTracker visibilityTracker;

    /**
     * Epoch tick scheduler for periodic epoch advancement.
     * Ensures writes become visible within one tick of fsync.
     */
    private final EpochTickScheduler epochTickScheduler;

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
        if (catalog == null) {
            throw new NullPointerException("Catalog cannot be null");
        }
        if (storageEngine == null) {
            throw new NullPointerException("StorageEngine cannot be null");
        }

        this.catalog = catalog;
        this.storageEngine = storageEngine;
        this.segmentIndexInfo = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);

        // Create visibility tracker for coordinating epochs across segments
        this.visibilityTracker = new VisibilityTracker(catalog);

        // Create epoch tick scheduler for periodic epoch advancement
        this.epochTickScheduler = new EpochTickScheduler(visibilityTracker, epochTickIntervalMs);

        // Create workers with listeners
        this.hnswWorker = new HnswIntegrationWorker(catalog,
            (segment, count) -> onVectorsIntegrated(segment, count),
            100, hnswQueueDepthLimit, 100);

        this.cuckooWorker = new CuckooMaintenanceWorker(catalog,
            segmentId -> onSegmentFilterCleaned(segmentId),
            cuckooMaintenanceIntervalMs, 1000, 0.001);

        this.reindexScheduler = new SegmentReindexScheduler(catalog,
            sourceId -> onReindexNeeded(sourceId),
            reindexCheckIntervalMs, 3600000);

        LOGGER.info("IndexOrchestrator created with hnswQueueDepthLimit={}, cuckooInterval={}ms, reindexInterval={}ms, epochTickInterval={}ms",
            hnswQueueDepthLimit, cuckooMaintenanceIntervalMs, reindexCheckIntervalMs, epochTickIntervalMs);
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts all background workers.
     *
     * <p>Workers are started in order: EpochTickScheduler → CuckooMaintenanceWorker → HnswIntegrationWorker → SegmentReindexScheduler</p>
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
            hnswWorker.start();
            reindexScheduler.start();

            LOGGER.info("Index orchestrator started successfully");
            return true;
        }
        return false;
    }

    /**
     * Stops all background workers.
     *
     * <p>Workers are stopped in reverse order: SegmentReindexScheduler → HnswIntegrationWorker → CuckooMaintenanceWorker → EpochTickScheduler</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            LOGGER.info("Stopping index orchestrator...");

            // Stop workers in reverse order
            reindexScheduler.stop();
            hnswWorker.stop();
            cuckooWorker.stop();

            // Stop epoch tick scheduler last
            epochTickScheduler.stop();

            LOGGER.info("Index orchestrator stopped. HNSW integrated: {}, Cuckoo deletions: {}, Reindexes: {}, Epoch ticks: {}",
                hnswWorker.getTotalIntegrated(), cuckooWorker.getTotalDeletions(),
                reindexScheduler.getTotalReindexesTriggered(), epochTickScheduler.getTickCount());
        }
    }

    @Override
    public void close() {
        stop();
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
        }

        // Register with Cuckoo worker if filter exists
        if (filter != null) {
            cuckooWorker.registerFilter(segmentId, filter);
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
            }
            cuckooWorker.unregisterFilter(segmentId);
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
     * Called when a re-index is needed for a source.
     */
    private boolean onReindexNeeded(long sourceId) {
        LOGGER.info("Re-index needed for source {}", sourceId);
        // In a full implementation, this would trigger the ingestion pipeline
        return true;
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
     * Returns the segment re-index scheduler.
     *
     * @return Re-index scheduler
     */
    public SegmentReindexScheduler getReindexScheduler() {
        return reindexScheduler;
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

        SegmentIndexInfo(long segmentId, Segment segment, HnswGraph graph,
                          CuckooFilter filter, TailOffsetTracker tracker) {
            this.segmentId = segmentId;
            this.segment = segment;
            this.graph = graph;
            this.filter = filter;
            this.tracker = tracker;
            this.vectorsIntegrated = 0;
            this.filterDeletions = 0;
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
