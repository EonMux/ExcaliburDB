package excalibur.shard.worker;

import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background worker for HNSW graph compaction.
 *
 * <p>This worker periodically checks registered HNSW graphs for tombstone
 * accumulation and triggers compaction when the tombstone ratio exceeds
 * the configured threshold (default 20%).</p>
 *
 * <h2>Compaction Process</h2>
 * <p>When compaction is triggered:</p>
 * <ol>
 *   <li>The worker calls {@link HnswGraph#compact(Path)} to create a compacted copy</li>
 *   <li>Active nodes are re-inserted into the new graph</li>
 *   <li>Document ID mappings are preserved</li>
 *   <li>PQ codes are re-encoded for the new node IDs</li>
 *   <li>The old graph is atomically replaced with the compacted version</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread</li>
 *   <li>Graph registration/unregistration uses CopyOnWriteArrayList</li>
 *   <li>State transitions use atomic operations</li>
 *   <li>Compaction acquires write lock on the graph (blocking reads/writes)</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Compaction interval: 1 minute default (configurable)</li>
 *   <li>Tombstone threshold: 20% default (configurable)</li>
 *   <li>Minimum interval: 10 seconds</li>
 *   <li>Threshold range: 5% to 50%</li>
 * </ul>
 *
 * <h2>Performance Impact</h2>
 * <p>Compaction blocks reads and writes on the graph during the atomic swap.
 * For large graphs, consider:</p>
 * <ul>
 *   <li>Using a higher threshold to reduce compaction frequency</li>
 *   <li>Scheduling compaction during low-traffic periods</li>
 *   <li>Using {@link HnswGraph#compactTo(Path)} for non-blocking copies</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see HnswGraph#compact(Path)
 * @see HnswGraph#compactTo(Path)
 */
public class HnswCompactionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswCompactionWorker.class);

    // ==================== Configuration Constants ====================

    /**
     * Default interval between compaction checks in milliseconds.
     * Domain constraint: 1 minute provides reasonable balance between
     * space reclamation and overhead.
     */
    public static final long DEFAULT_INTERVAL_MS = 60000; // 1 minute

    /**
     * Minimum allowed interval between compaction checks.
     */
    public static final long MIN_INTERVAL_MS = 10000; // 10 seconds

    /**
     * Default tombstone ratio threshold for triggering compaction.
     * When tombstones exceed this percentage of total nodes, compaction is triggered.
     * Domain constraint: 20% balances space reclamation with compaction overhead.
     */
    public static final double DEFAULT_TOMBSTONE_THRESHOLD = 0.20;

    /**
     * Minimum allowed tombstone threshold.
     */
    public static final double MIN_TOMBSTONE_THRESHOLD = 0.05;

    /**
     * Maximum allowed tombstone threshold.
     */
    public static final double MAX_TOMBSTONE_THRESHOLD = 0.50;

    /**
     * How long {@link #stop()} waits for an in-progress compaction to finish its file
     * swap before giving up and warning.
     */
    private static final long STOP_JOIN_TIMEOUT_MS = 30_000;

    // ==================== Instance Fields ====================

    /**
     * List of graphs registered for compaction monitoring.
     * Uses CopyOnWriteArrayList for thread-safe iteration during registration/unregistration.
     */
    private final CopyOnWriteArrayList<HnswGraph> registeredGraphs;

    /**
     * Flag indicating if the worker is currently running.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Flag indicating if the worker has been stopped.
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Counter for total compaction operations performed.
     */
    private final AtomicLong compactionCount = new AtomicLong(0);

    /**
     * Counter for total nodes removed during all compactions.
     */
    private final AtomicLong totalNodesRemoved = new AtomicLong(0);

    /**
     * Interval between compaction checks in milliseconds.
     */
    private volatile long intervalMs = DEFAULT_INTERVAL_MS;

    /**
     * Tombstone ratio threshold for triggering compaction.
     */
    private volatile double tombstoneThreshold = DEFAULT_TOMBSTONE_THRESHOLD;

    /**
     * The background worker thread.
     */
    private volatile Thread workerThread;

    /**
     * Optional listener notified after each compaction attempt.
     * Compaction renumbers node IDs, so components holding node-scoped state need to hear
     * about it.
     */
    private volatile CompactionListener listener;

    // ==================== Constructors ====================

    /**
     * Creates a new HnswCompactionWorker with default configuration.
     */
    public HnswCompactionWorker() {
        this(DEFAULT_INTERVAL_MS, DEFAULT_TOMBSTONE_THRESHOLD);
    }

    /**
     * Creates a new HnswCompactionWorker with custom configuration.
     *
     * @param intervalMs Interval between compaction checks in milliseconds
     * @param tombstoneThreshold Tombstone ratio threshold (0.05 to 0.50)
     * @throws IllegalArgumentException if parameters are out of range
     */
    public HnswCompactionWorker(long intervalMs, double tombstoneThreshold) {
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Interval must be at least " + MIN_INTERVAL_MS + "ms, got: " + intervalMs);
        }
        if (tombstoneThreshold < MIN_TOMBSTONE_THRESHOLD || tombstoneThreshold > MAX_TOMBSTONE_THRESHOLD) {
            throw new IllegalArgumentException(
                "Tombstone threshold must be between " + MIN_TOMBSTONE_THRESHOLD +
                " and " + MAX_TOMBSTONE_THRESHOLD + ", got: " + tombstoneThreshold);
        }

        this.registeredGraphs = new CopyOnWriteArrayList<>();
        this.intervalMs = intervalMs;
        this.tombstoneThreshold = tombstoneThreshold;

        LOGGER.info("HnswCompactionWorker created: intervalMs={}, tombstoneThreshold={}",
            intervalMs, tombstoneThreshold);
    }

    // ==================== Graph Registration ====================

    /**
     * Registers an HNSW graph for compaction monitoring.
     *
     * <p>Once registered, the worker will periodically check this graph's
     * tombstone ratio and trigger compaction when the threshold is exceeded.</p>
     *
     * @param graph The HNSW graph to register (null values are ignored)
     */
    public void registerGraph(HnswGraph graph) {
        if (graph != null && !registeredGraphs.contains(graph)) {
            registeredGraphs.add(graph);
            LOGGER.debug("Registered HNSW graph for compaction monitoring: {} nodes",
                graph.nodeCount());
        }
    }

    /**
     * Unregisters an HNSW graph from compaction monitoring.
     *
     * @param graph The HNSW graph to unregister
     */
    public void unregisterGraph(HnswGraph graph) {
        if (registeredGraphs.remove(graph)) {
            LOGGER.debug("Unregistered HNSW graph from compaction monitoring");
        }
    }

    /**
     * Returns the number of graphs currently registered for compaction monitoring.
     *
     * @return Registered graph count
     */
    public int getRegisteredGraphCount() {
        return registeredGraphs.size();
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the compaction worker.
     *
     * <p>The worker runs in a virtual thread and periodically checks registered
     * graphs for tombstone accumulation.</p>
     *
     * @return true if the worker was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);
            workerThread = Thread.ofVirtual()
                .name("excalibur-hnsw-compaction-worker")
                .start(this::runLoop);
            LOGGER.info("HnswCompactionWorker started");
            return true;
        }
        return false;
    }

    /**
     * Stops the compaction worker.
     *
     * <p>A compaction already in progress is not interruptible: it closes the graph's
     * mapping and renames files into place, and abandoning it midway would leave the
     * graph closed or the files half swapped. This method therefore waits for the worker
     * thread to finish before returning, so callers that tear down the arena and the
     * storage engine afterwards cannot race a live file swap.</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);
            Thread worker = workerThread;
            if (worker != null) {
                worker.interrupt();
                try {
                    worker.join(STOP_JOIN_TIMEOUT_MS);
                    if (worker.isAlive()) {
                        LOGGER.warn("HnswCompactionWorker did not finish within {}ms; a graph "
                            + "compaction may still be swapping files", STOP_JOIN_TIMEOUT_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LOGGER.info("HnswCompactionWorker stopped. Total compactions: {}, total nodes removed: {}",
                compactionCount.get(), totalNodesRemoved.get());
        }
    }

    /**
     * Checks if the worker is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Configuration ====================

    /**
     * Sets the interval between compaction checks.
     *
     * @param intervalMs Interval in milliseconds (minimum 10000ms)
     * @throws IllegalArgumentException if interval is below minimum
     */
    public void setIntervalMs(long intervalMs) {
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Interval must be at least " + MIN_INTERVAL_MS + "ms, got: " + intervalMs);
        }
        this.intervalMs = intervalMs;
        LOGGER.debug("Compaction interval updated to {}ms", intervalMs);
    }

    /**
     * Returns the current compaction check interval.
     *
     * @return Interval in milliseconds
     */
    public long getIntervalMs() {
        return intervalMs;
    }

    /**
     * Sets the tombstone ratio threshold for triggering compaction.
     *
     * @param threshold Threshold value (0.05 to 0.50)
     * @throws IllegalArgumentException if threshold is out of range
     */
    public void setTombstoneThreshold(double threshold) {
        if (threshold < MIN_TOMBSTONE_THRESHOLD || threshold > MAX_TOMBSTONE_THRESHOLD) {
            throw new IllegalArgumentException(
                "Tombstone threshold must be between " + MIN_TOMBSTONE_THRESHOLD +
                " and " + MAX_TOMBSTONE_THRESHOLD + ", got: " + threshold);
        }
        this.tombstoneThreshold = threshold;
        LOGGER.debug("Tombstone threshold updated to {}", threshold);
    }

    /**
     * Returns the current tombstone ratio threshold.
     *
     * @return Threshold value
     */
    public double getTombstoneThreshold() {
        return tombstoneThreshold;
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of compaction operations performed.
     *
     * @return Total compaction count
     */
    public long getCompactionCount() {
        return compactionCount.get();
    }

    /**
     * Returns the total number of nodes removed during all compactions.
     *
     * @return Total nodes removed
     */
    public long getTotalNodesRemoved() {
        return totalNodesRemoved.get();
    }

    // ==================== Worker Loop ====================

    /**
     * Main worker loop that periodically checks graphs for compaction.
     */
    private void runLoop() {
        LOGGER.debug("HnswCompactionWorker loop started");

        while (running.get() && !stopped.get()) {
            try {
                // Check each registered graph
                for (HnswGraph graph : registeredGraphs) {
                    if (!running.get() || stopped.get()) {
                        break;
                    }

                    try {
                        checkAndCompact(graph);
                    } catch (Exception e) {
                        LOGGER.error("Error checking graph for compaction: {}", e.getMessage(), e);
                    }
                }

                // Sleep until next check
                Thread.sleep(intervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.debug("HnswCompactionWorker interrupted");
                break;
            } catch (Exception e) {
                LOGGER.error("HnswCompactionWorker error: {}", e.getMessage(), e);
                // Continue running despite errors
            }
        }

        LOGGER.debug("HnswCompactionWorker loop ended");
    }

    /**
     * Checks a graph's tombstone ratio and triggers compaction if needed.
     *
     * @param graph The graph to check
     */
    private void checkAndCompact(HnswGraph graph) {
        long total = graph.nodeCount();
        long tombstones = graph.tombstoneCount();

        // Skip empty graphs
        if (total == 0) {
            return;
        }

        double ratio = (double) tombstones / total;

        if (ratio < tombstoneThreshold) {
            LOGGER.trace("Graph tombstone ratio ({}) below threshold ({}). Skipping compaction.",
                String.format("%.2f%%", ratio * 100),
                String.format("%.2f%%", tombstoneThreshold * 100));
            return;
        }

        // A compaction cannot be abandoned once started, so never begin one after a stop.
        if (!running.get() || stopped.get()) {
            return;
        }

        LOGGER.info("Graph tombstone ratio ({}) exceeds threshold ({}). Starting compaction. " +
            "Total nodes: {}, Tombstones: {}",
            String.format("%.2f%%", ratio * 100),
            String.format("%.2f%%", tombstoneThreshold * 100),
            total, tombstones);

        try {
            // HnswGraph.compact builds the replacement next to the live file and owns the
            // swap, so there is nothing here to stage or clean up. The graph's own
            // directory is passed because the parameter is retained for compatibility.
            long removed = graph.compact(compactionScratchDir(graph));

            compactionCount.incrementAndGet();
            totalNodesRemoved.addAndGet(removed);

            LOGGER.info("HNSW compaction completed: removed {} tombstoned nodes. " +
                "Total compactions: {}, Total removed: {}",
                removed, compactionCount.get(), totalNodesRemoved.get());

            notifyCompacted(graph, removed);

        } catch (IOException | RuntimeException e) {
            handleCompactionFailure(graph, e);
        }
    }

    /**
     * Returns the directory the graph lives in, used as the compaction scratch location.
     *
     * @param graph The graph being compacted
     * @return The graph's directory, or the graph path itself when it has no parent
     */
    private Path compactionScratchDir(HnswGraph graph) {
        Path graphPath = graph.path();
        Path parent = graphPath != null ? graphPath.getParent() : null;
        return parent != null ? parent : Path.of(".");
    }

    /**
     * Reports a failed compaction and retires a graph that the failure left unusable.
     *
     * <p>A compaction that fails after the live mapping has been released leaves the graph
     * closed. Keeping such a graph registered means retrying the failure every interval
     * while every query against it throws, so it is unregistered and reported loudly
     * instead of being silently retried.</p>
     *
     * @param graph   The graph that failed to compact
     * @param failure The failure
     */
    private void handleCompactionFailure(HnswGraph graph, Exception failure) {
        if (graph.isClosed()) {
            LOGGER.error("HNSW compaction failed and left graph {} closed; unregistering it. "
                + "The segment is unsearchable until it is reopened: {}",
                graph.path(), failure.getMessage(), failure);
            unregisterGraph(graph);
            notifyCompactionFailed(graph, failure);
            return;
        }

        LOGGER.error("HNSW compaction failed for graph {}: {}", graph.path(),
            failure.getMessage(), failure);
        notifyCompactionFailed(graph, failure);
    }

    /**
     * Notifies the listener that a graph was compacted, if one is configured.
     *
     * @param graph   The compacted graph
     * @param removed Number of tombstoned nodes removed
     */
    private void notifyCompacted(HnswGraph graph, long removed) {
        CompactionListener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onGraphCompacted(graph, removed);
        } catch (Exception e) {
            LOGGER.error("HNSW compaction listener failed for graph {}: {}",
                graph.path(), e.getMessage(), e);
        }
    }

    /**
     * Notifies the listener that a compaction failed, if one is configured.
     *
     * @param graph   The graph that failed to compact
     * @param failure The failure
     */
    private void notifyCompactionFailed(HnswGraph graph, Exception failure) {
        CompactionListener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onGraphCompactionFailed(graph, failure);
        } catch (Exception e) {
            LOGGER.error("HNSW compaction listener failed for graph {}: {}",
                graph.path(), e.getMessage(), e);
        }
    }

    // ==================== Listener ====================

    /**
     * Sets the listener notified after each compaction attempt.
     *
     * @param listener The listener, or null to clear
     */
    public void setCompactionListener(CompactionListener listener) {
        this.listener = listener;
    }

    /**
     * Returns the configured compaction listener.
     *
     * @return The listener, or null if none is configured
     */
    public CompactionListener getCompactionListener() {
        return listener;
    }

    /**
     * Callback interface for graph compaction events.
     *
     * <p>Compaction re-inserts the surviving nodes into a fresh graph, so every node ID
     * changes. Anything keyed by node ID must be reset when this fires.</p>
     */
    public interface CompactionListener {

        /**
         * Called after a graph was compacted and its node IDs renumbered.
         *
         * @param graph        The compacted graph
         * @param nodesRemoved Number of tombstoned nodes removed
         */
        void onGraphCompacted(HnswGraph graph, long nodesRemoved);

        /**
         * Called when a compaction attempt failed.
         *
         * <p>The default implementation does nothing.</p>
         *
         * @param graph   The graph that failed to compact
         * @param failure The failure
         */
        default void onGraphCompactionFailed(HnswGraph graph, Exception failure) {
            // No action required by default.
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("HnswCompactionWorker{running=%s, registeredGraphs=%d, " +
            "intervalMs=%d, tombstoneThreshold=%.2f, compactionCount=%d, totalNodesRemoved=%d}",
            running.get(), registeredGraphs.size(), intervalMs, tombstoneThreshold,
            compactionCount.get(), totalNodesRemoved.get());
    }
}
