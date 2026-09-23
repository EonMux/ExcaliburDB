package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import com.eonmux.excalibur.vector.quantization.PQConfig;
import com.eonmux.excalibur.vector.quantization.ProductQuantizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** Dimensions already warned about, so the substitution is logged once each. */
    private final Set<Integer> warnedDimensions = ConcurrentHashMap.newKeySet();

    private volatile Thread workerThread;

    /**
     * How the running loop is told to stop, replaced on every {@link #start()}.
     *
     * <p>Never an interrupt. Warming a segment trains its quantizer, writes the codebook and
     * re-encodes the graph it serves from, then moves the segment through the catalog; an
     * interrupt landing inside any of that closes the channel it was writing, which the graph
     * and the catalog share with every other thread using them. The signal is read between
     * segments and wakes the pause between checks.</p>
     */
    private volatile StopSignal stopSignal = new StopSignal();

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

            StopSignal signal = new StopSignal();
            stopSignal = signal;
            workerThread = Thread.ofVirtual()
                .name("excalibur-warming-phase-worker")
                .start(() -> runWarmingLoop(signal));

            LOGGER.info("WarmingPhaseWorker started");
            return true;
        }
        return false;
    }

    /**
     * Stops the background warming worker and says whether it actually ended.
     *
     * <p>Asked through the stop signal and waited for, never interrupted, for the reason given
     * on {@code stopSignal}. A worker still warming a segment when the wait runs out is
     * reported: the caller closes the arena that segment's graph is mapped from next.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains, so a longer budget gives a segment being warmed needs to finish training its quantizer rather than a fixed wait cut short.
     * A later call waits again for a thread an earlier one left running.</p>
     *
     * @param wait How long to wait for the worker thread to end
     * @return true if the worker thread has ended, or was never started
     * @throws NullPointerException if wait is null
     */
    public boolean stop(Duration wait) {
        Objects.requireNonNull(wait, "wait cannot be null");
        Thread worker = workerThread;
        if (stopped.compareAndSet(false, true)) {
            running.set(false);
            stopSignal.request();
            LOGGER.info("WarmingPhaseWorker asked to stop. PQ trained: {}, PQ failed: {}, Transitions: {}",
                totalPqTrained.get(), totalPqFailed.get(), totalTransitions.get());
        }
        // Outside the guard, so a later call waits again for a thread an earlier one left
        // running instead of answering from a snapshot of it.
        if (worker != null) {
            try {
                worker.join(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                LOGGER.warn("WarmingPhaseWorker did not finish warming within {}ms of being "
                    + "asked to stop", wait.toMillis());
            }
        }
        return worker == null || !worker.isAlive();
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
    private void runWarmingLoop(StopSignal signal) {
        while (running.get() && !stopped.get() && !signal.isRequested()) {
            try {
                // Process all segments in WARMING state
                for (WarmableSegment warmable : registeredSegments) {
                    if (!running.get() || stopped.get() || signal.isRequested()) {
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

                // Until the next check, or until stop() asks the loop to end.
                if (signal.pause(checkIntervalMs)) {
                    break;
                }

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
            PQConfig config = pqConfigForDimension(dimension);
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
     * The quantiser shape to train a segment of the given dimension to.
     *
     * <p>A vector is split into equal parts, so the configured subspace count has to divide
     * the dimension. Configuration cannot check that - the dimension comes from the embedding
     * model, not from a property file - so it is checked here, and a split that does not fit
     * is replaced by one that does. Only the split: the configured codebook size and sample
     * ceiling are perfectly usable at any dimension, and replacing the whole shape would leave
     * a node training the defaults it was explicitly configured away from.</p>
     *
     * <p>Package-private so a test can hold the substitution against what was configured.</p>
     *
     * @param dimension The vector dimension
     * @return The shape to train, or null if no split fits this dimension
     */
    PQConfig pqConfigForDimension(int dimension) {
        PQConfig configured = pqConfig;
        if (configured.isCompatible(dimension)) {
            return configured;
        }

        int substitute;
        try {
            substitute = PQConfig.forDimension(dimension).numSubspaces();
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Cannot create PQ config for dimension {}: {}", dimension, e.getMessage());
            return null;
        }

        PQConfig adjusted = PQConfig.builder()
            .numSubspaces(substitute)
            .numCentroids(configured.numCentroids())
            .maxTrainingSamples(configured.maxTrainingSamples())
            .build();
        // Once per dimension: this runs for every segment that warms, and a line per segment
        // would bury the one that matters. Silence was worse - the startup log reported the
        // configured split as though it were in force, and every segment trained another one.
        if (warnedDimensions.add(dimension)) {
            LOGGER.warn("excalibur.shard.pq-subspaces is {}, which does not divide the {}-"
                    + "dimensional vectors this node embeds; training {} subspaces instead. "
                    + "Set it to a divisor of {} to choose the split yourself.",
                configured.numSubspaces(), dimension, substitute, dimension);
        }
        return adjusted;
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
