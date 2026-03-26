package excalibur.shard.worker;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import io.excalibur.vector.quantization.PQConfig;
import io.excalibur.vector.quantization.ProductQuantizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual thread worker that handles the WARMING phase for segments.
 *
 * <p>This worker implements the PQ training workflow described in Phase 5:</p>
 * <ol>
 *   <li>Segment transitions to WARMING state after INDEXING completes</li>
 *   <li>Worker trains ProductQuantizer codebooks on segment vectors</li>
 *   <li>Worker encodes all vectors with the trained quantizer</li>
 *   <li>Worker transitions segment to ACTIVE state</li>
 * </ol>
 *
 * <h2>PQ Training</h2>
 * <p>Product Quantization compresses high-dimensional vectors into compact codes.
 * During the WARMING phase, this worker:</p>
 * <ul>
 *   <li>Trains PQ codebooks using K-means on a sample of vectors</li>
 *   <li>Encodes all segment vectors with the trained codebooks</li>
 *   <li>Enables asymmetric distance computation for faster queries</li>
 * </ul>
 *
 * <h2>Graceful Fallback</h2>
 * <p>If PQ training fails (insufficient vectors, dimension mismatch, etc.), the
 * segment still transitions to ACTIVE state. Queries will use full-precision
 * vectors via the fallback mechanism in {@link HnswGraph#searchWithPQ}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Warming operations do not block segment reads or writes</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Check interval: 100ms minimum (configurable)</li>
 *   <li>Minimum vectors for PQ training: 100 (configurable)</li>
 *   <li>Default PQ subspaces (M): 8</li>
 *   <li>Default PQ centroids (K): 256</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see ProductQuantizer
 * @see PQConfig
 * @see HnswGraph#trainProductQuantizer
 */
public class WarmingPhaseWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(WarmingPhaseWorker.class);

    // ==================== Configuration Constants ====================

    /**
     * Default interval between warming checks in milliseconds.
     */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 100;

    /**
     * Minimum allowed check interval in milliseconds.
     */
    public static final long MIN_CHECK_INTERVAL_MS = 10;

    /**
     * Default minimum vectors required for PQ training.
     * With fewer vectors, training is skipped and full-precision is used.
     */
    public static final int DEFAULT_MIN_VECTORS_FOR_TRAINING = 100;

    /**
     * Minimum vectors for PQ training.
     */
    public static final int ABSOLUTE_MIN_VECTORS = 10;

    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final WarmingListener listener;
    private final CopyOnWriteArrayList<WarmableSegment> registeredSegments;
    private final Map<Long, Boolean> warmingInProgress;

    private final AtomicBoolean running;
    private final AtomicBoolean stopped;

    private final AtomicLong totalPqTrained;
    private final AtomicLong totalPqFailed;
    private final AtomicLong totalTransitions;
    private final AtomicLong lastWarmingTime;

    private volatile long checkIntervalMs;
    private volatile int minVectorsForTraining;
    private volatile PQConfig pqConfig;

    private volatile Thread workerThread;

    // ==================== Constructors ====================

    /**
     * Creates a new WarmingPhaseWorker with default configuration.
     *
     * @param catalog  The segment catalog
     * @param listener Callback for warming events
     * @throws NullPointerException if catalog or listener is null
     */
    public WarmingPhaseWorker(SegmentCatalog catalog, WarmingListener listener) {
        this(catalog, listener, DEFAULT_CHECK_INTERVAL_MS, DEFAULT_MIN_VECTORS_FOR_TRAINING, null);
    }

    /**
     * Creates a new WarmingPhaseWorker with custom configuration.
     *
     * @param catalog               The segment catalog
     * @param listener              Callback for warming events
     * @param checkIntervalMs       Interval between warming checks
     * @param minVectorsForTraining Minimum vectors required for PQ training
     * @param pqConfig              PQ configuration (null for default)
     * @throws NullPointerException if catalog or listener is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public WarmingPhaseWorker(SegmentCatalog catalog, WarmingListener listener,
                               long checkIntervalMs, int minVectorsForTraining, PQConfig pqConfig) {
        Objects.requireNonNull(catalog, "Catalog cannot be null");
        Objects.requireNonNull(listener, "Listener cannot be null");

        validateCheckInterval(checkIntervalMs);
        validateMinVectors(minVectorsForTraining);

        this.catalog = catalog;
        this.listener = listener;
        this.registeredSegments = new CopyOnWriteArrayList<>();
        this.warmingInProgress = new ConcurrentHashMap<>();

        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);

        this.totalPqTrained = new AtomicLong(0);
        this.totalPqFailed = new AtomicLong(0);
        this.totalTransitions = new AtomicLong(0);
        this.lastWarmingTime = new AtomicLong(0);

        this.checkIntervalMs = checkIntervalMs;
        this.minVectorsForTraining = minVectorsForTraining;
        this.pqConfig = pqConfig != null ? pqConfig : new PQConfig();

        LOGGER.info("WarmingPhaseWorker created with checkInterval={}ms, minVectors={}, pqConfig={}",
            checkIntervalMs, minVectorsForTraining, this.pqConfig);
    }

    // ==================== Configuration Validation ====================

    private void validateCheckInterval(long intervalMs) {
        if (intervalMs < MIN_CHECK_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Check interval must be at least " + MIN_CHECK_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    private void validateMinVectors(int minVectors) {
        if (minVectors < ABSOLUTE_MIN_VECTORS) {
            throw new IllegalArgumentException(
                "Minimum vectors for training must be at least " + ABSOLUTE_MIN_VECTORS + ", got: " + minVectors);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background warming worker.
     *
     * @return true if the worker was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            workerThread = Thread.ofVirtual()
                .name("excalibur-warming-phase-worker")
                .start(this::runWarmingLoop);

            LOGGER.info("WarmingPhaseWorker started");
            return true;
        }
        return false;
    }

    /**
     * Stops the background warming worker.
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            LOGGER.info("WarmingPhaseWorker stopped. PQ trained: {}, PQ failed: {}, Transitions: {}",
                totalPqTrained.get(), totalPqFailed.get(), totalTransitions.get());
        }
    }

    /**
     * Checks if the worker is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Segment Registration ====================

    /**
     * Registers a segment for warming phase handling.
     *
     * <p>The segment must have an attached HNSW graph and tail offset tracker.</p>
     *
     * @param segmentId The segment ID
     * @param segment   The segment
     * @param graph     The HNSW graph for the segment
     * @param tracker   The tail offset tracker
     * @throws NullPointerException if any parameter is null
     */
    public void registerSegment(long segmentId, Segment segment, HnswGraph graph, TailOffsetTracker tracker) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(graph, "Graph cannot be null");
        Objects.requireNonNull(tracker, "Tracker cannot be null");

        WarmableSegment warmable = new WarmableSegment(segmentId, segment, graph, tracker);
        if (!registeredSegments.contains(warmable)) {
            registeredSegments.add(warmable);
            LOGGER.debug("Registered segment {} for warming phase handling", segmentId);
        }
    }

    /**
     * Unregisters a segment from warming phase handling.
     *
     * @param segmentId The segment ID to unregister
     */
    public void unregisterSegment(long segmentId) {
        registeredSegments.removeIf(s -> s.segmentId == segmentId);
        warmingInProgress.remove(segmentId);
        LOGGER.debug("Unregistered segment {} from warming phase handling", segmentId);
    }

    /**
     * Returns the number of registered segments.
     *
     * @return Registered segment count
     */
    public int getRegisteredSegmentCount() {
        return registeredSegments.size();
    }

    // ==================== Main Warming Loop ====================

    /**
     * Main warming loop that runs in the background virtual thread.
     */
    private void runWarmingLoop() {
        while (running.get() && !stopped.get()) {
            try {
                // Process all segments in WARMING state
                for (WarmableSegment warmable : registeredSegments) {
                    if (!running.get() || stopped.get()) {
                        break;
                    }

                    // Check if segment is in WARMING state
                    LifecycleState state = getSegmentState(warmable.segmentId);
                    if (state != LifecycleState.WARMING) {
                        continue;
                    }

                    // Skip if warming is already in progress for this segment
                    if (warmingInProgress.putIfAbsent(warmable.segmentId, true) != null) {
                        continue;
                    }

                    try {
                        processWarmingSegment(warmable);
                    } finally {
                        warmingInProgress.remove(warmable.segmentId);
                    }
                }

                // Sleep until next check
                Thread.sleep(checkIntervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("Error in warming phase loop", e);
                // Continue running despite errors
            }
        }

        LOGGER.info("Warming phase loop terminated");
    }

    // ==================== Warming Operations ====================

    /**
     * Processes a segment in WARMING state.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Trains PQ codebooks on the segment's vectors</li>
     *   <li>Encodes all vectors with the trained quantizer</li>
     *   <li>Transitions the segment to ACTIVE state</li>
     * </ol>
     *
     * @param warmable The segment to process
     */
    private void processWarmingSegment(WarmableSegment warmable) {
        long segmentId = warmable.segmentId;
        HnswGraph graph = warmable.graph;

        LOGGER.info("Processing WARMING segment {}: training PQ codebooks", segmentId);

        try {
            // Check if graph has enough vectors for PQ training
            long vectorCountLong = graph.nodeCount();
            if (vectorCountLong > Integer.MAX_VALUE) {
                LOGGER.warn("Segment {} has too many vectors ({}) for PQ training. " +
                    "Using subset.", segmentId, vectorCountLong);
            }
            int vectorCount = (int) Math.min(vectorCountLong, Integer.MAX_VALUE);

            if (vectorCount < minVectorsForTraining) {
                LOGGER.info("Segment {} has {} vectors, below minimum {} for PQ training. " +
                    "Skipping PQ, transitioning to ACTIVE.", segmentId, vectorCount, minVectorsForTraining);
                transitionToActive(segmentId);
                totalPqFailed.incrementAndGet();
                return;
            }

            // Get vector dimension from graph
            int dimension = graph.config().dimension();

            // Create PQ config compatible with the dimension
            PQConfig config = getPQConfigForDimension(dimension);
            if (config == null) {
                LOGGER.warn("Segment {} dimension {} not compatible with PQ. " +
                    "Skipping PQ, transitioning to ACTIVE.", segmentId, dimension);
                transitionToActive(segmentId);
                totalPqFailed.incrementAndGet();
                return;
            }

            // Train PQ codebooks
            LOGGER.debug("Training PQ codebooks for segment {}: dimension={}, M={}, K={}",
                segmentId, dimension, config.numSubspaces(), config.numCentroids());

            long trainingStart = System.currentTimeMillis();

            ProductQuantizer quantizer = graph.trainProductQuantizer(
                config.maxTrainingSamples(),
                config.numSubspaces(),
                config.numCentroids()
            );

            long trainingTime = System.currentTimeMillis() - trainingStart;
            LOGGER.info("PQ codebooks trained for segment {} in {}ms", segmentId, trainingTime);

            // Set the trained quantizer on the graph
            graph.setProductQuantizer(quantizer);

            // Encode all vectors with the trained quantizer
            long encodingStart = System.currentTimeMillis();

            graph.encodeAllVectors();

            long encodingTime = System.currentTimeMillis() - encodingStart;
            LOGGER.info("PQ encoding completed for segment {} in {}ms ({} vectors)",
                segmentId, encodingTime, vectorCount);

            // Transition segment to ACTIVE
            transitionToActive(segmentId);

            totalPqTrained.incrementAndGet();
            totalTransitions.incrementAndGet();
            lastWarmingTime.set(System.currentTimeMillis());

            // Notify listener
            listener.onWarmingComplete(segmentId, true, vectorCount);

            LOGGER.info("Segment {} transitioned to ACTIVE with PQ enabled", segmentId);

        } catch (Exception e) {
            LOGGER.error("PQ training failed for segment {}: {}. Transitioning to ACTIVE without PQ.",
                segmentId, e.getMessage());

            // Still transition to ACTIVE, queries will use full-precision fallback
            transitionToActive(segmentId);

            totalPqFailed.incrementAndGet();
            totalTransitions.incrementAndGet();

            // Notify listener of failure
            listener.onWarmingComplete(segmentId, false, 0);
        }
    }

    /**
     * Gets a PQ configuration compatible with the given dimension.
     *
     * @param dimension The vector dimension
     * @return A compatible PQConfig, or null if dimension is not compatible
     */
    private PQConfig getPQConfigForDimension(int dimension) {
        // First check if the configured PQ config is compatible
        if (pqConfig.isCompatible(dimension)) {
            return pqConfig;
        }

        // Try to create a compatible config using the factory method
        try {
            return PQConfig.forDimension(dimension);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Cannot create PQ config for dimension {}: {}", dimension, e.getMessage());
            return null;
        }
    }

    /**
     * Transitions a segment to ACTIVE state.
     *
     * @param segmentId The segment ID
     */
    private void transitionToActive(long segmentId) {
        try {
            catalog.completeWarmup(segmentId);
            LOGGER.debug("Segment {} transitioned to ACTIVE", segmentId);
        } catch (Exception e) {
            LOGGER.error("Failed to transition segment {} to ACTIVE: {}",
                segmentId, e.getMessage());
        }
    }

    /**
     * Gets the lifecycle state for a segment from the catalog.
     *
     * @param segmentId The segment ID
     * @return The lifecycle state, or null if not found
     */
    private LifecycleState getSegmentState(long segmentId) {
        try {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            return metadata != null ? metadata.getLifecycleState() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of segments with PQ successfully trained.
     *
     * @return Total PQ trained count
     */
    public long getTotalPqTrained() {
        return totalPqTrained.get();
    }

    /**
     * Returns the total number of segments where PQ training failed.
     *
     * @return Total PQ failed count
     */
    public long getTotalPqFailed() {
        return totalPqFailed.get();
    }

    /**
     * Returns the total number of segment transitions to ACTIVE.
     *
     * @return Total transitions count
     */
    public long getTotalTransitions() {
        return totalTransitions.get();
    }

    /**
     * Returns the timestamp of the last warming completion.
     *
     * @return Last warming time in epoch milliseconds, or 0 if never
     */
    public long getLastWarmingTime() {
        return lastWarmingTime.get();
    }

    // ==================== Configuration Getters/Setters ====================

    /**
     * Returns the check interval in milliseconds.
     *
     * @return Check interval
     */
    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    /**
     * Sets the check interval in milliseconds.
     *
     * @param intervalMs The new interval
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setCheckIntervalMs(long intervalMs) {
        validateCheckInterval(intervalMs);
        this.checkIntervalMs = intervalMs;
    }

    /**
     * Returns the minimum vectors required for PQ training.
     *
     * @return Minimum vectors
     */
    public int getMinVectorsForTraining() {
        return minVectorsForTraining;
    }

    /**
     * Sets the minimum vectors required for PQ training.
     *
     * @param minVectors The new minimum
     * @throws IllegalArgumentException if value is invalid
     */
    public void setMinVectorsForTraining(int minVectors) {
        validateMinVectors(minVectors);
        this.minVectorsForTraining = minVectors;
    }

    /**
     * Returns the PQ configuration.
     *
     * @return PQ configuration
     */
    public PQConfig getPqConfig() {
        return pqConfig;
    }

    /**
     * Sets the PQ configuration.
     *
     * @param pqConfig The new PQ configuration
     * @throws NullPointerException if pqConfig is null
     */
    public void setPqConfig(PQConfig pqConfig) {
        Objects.requireNonNull(pqConfig, "PQConfig cannot be null");
        this.pqConfig = pqConfig;
    }

    // ==================== Listener Interface ====================

    /**
     * Callback interface for warming events.
     */
    @FunctionalInterface
    public interface WarmingListener {

        /**
         * Called when a segment completes the warming phase.
         *
         * @param segmentId    The segment ID
         * @param pqSuccess    True if PQ training succeeded
         * @param vectorCount  Number of vectors encoded (0 if PQ failed)
         */
        void onWarmingComplete(long segmentId, boolean pqSuccess, int vectorCount);
    }

    // ==================== Internal Classes ====================

    /**
     * Holder for a segment with its ID, graph, and tracker.
     */
    private static class WarmableSegment {
        final long segmentId;
        final Segment segment;
        final HnswGraph graph;
        final TailOffsetTracker tracker;

        WarmableSegment(long segmentId, Segment segment, HnswGraph graph, TailOffsetTracker tracker) {
            this.segmentId = segmentId;
            this.segment = segment;
            this.graph = graph;
            this.tracker = tracker;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof WarmableSegment other)) return false;
            return segmentId == other.segmentId;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(segmentId);
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "WarmingPhaseWorker{running=%s, segments=%d, pqTrained=%d, pqFailed=%d, " +
            "transitions=%d, checkIntervalMs=%d, minVectors=%d}",
            running.get(), registeredSegments.size(), totalPqTrained.get(), totalPqFailed.get(),
            totalTransitions.get(), checkIntervalMs, minVectorsForTraining);
    }
}
