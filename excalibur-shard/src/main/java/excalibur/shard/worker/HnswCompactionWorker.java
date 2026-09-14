package excalibur.shard.worker;

import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
     * Graphs registered for compaction monitoring.
     *
     * <p>A set rather than a list: registration only ever asks whether a graph is already
     * being monitored, and nothing reads the registry by position or depends on its order.
     * Membership is decided by the one atomic add, so two threads registering the same graph
     * cannot both conclude it is absent, and the cost of a registration does not grow with the
     * number of graphs a deployment holds. Iteration is weakly consistent, which is what a
     * sweep needs: it may unregister a graph it is part-way through examining.</p>
     */
    private final Set<HnswGraph> registeredGraphs;

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

        this.registeredGraphs = ConcurrentHashMap.newKeySet();
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
        if (graph != null && registeredGraphs.add(graph)) {
            LOGGER.debug("Registered HNSW graph for compaction monitoring: {}", graph.path());
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
                        compactGraph(graph, false, true);
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
     * Compacts every registered graph the threshold selects, on the calling thread.
     *
     * <p>The same work the scheduled loop performs, for a caller that wants it now rather than
     * at the next interval. Safe to call while the loop is running: {@code HnswGraph.compact}
     * takes the graph's own write lock, so one graph is never rebuilt twice at once, and a
     * graph the loop is already rebuilding simply makes this call wait for it.</p>
     *
     * <p>A graph that fails is counted and reported, never rethrown - one unusable graph must
     * not cost the caller the rest of the run. Nor does a stale registration: a graph that has
     * already been closed is retired and counted as skipped, so the graphs behind it are still
     * considered.</p>
     *
     * <p>Forcing overrides the tombstone-ratio policy, not the question of whether there is
     * anything to reclaim. A graph with no tombstones is skipped either way, so the rebuilt
     * count always means graphs where space actually came back.</p>
     *
     * @param force true to rebuild every registered graph regardless of its tombstone ratio
     * @return What the run examined, rebuilt and failed on
     */
    public GraphCompactionSummary compactNow(boolean force) {
        int considered = 0;
        int compacted = 0;
        int failed = 0;

        for (HnswGraph graph : registeredGraphs) {
            considered++;
            switch (compactGraph(graph, force, false)) {
                case COMPACTED -> compacted++;
                case FAILED -> failed++;
                case SKIPPED -> { }
            }
        }

        LOGGER.info("On-demand HNSW compaction: {} graph(s) considered, {} compacted, {} failed",
            considered, compacted, failed);
        return new GraphCompactionSummary(considered, compacted, failed);
    }

    /**
     * What one graph's compaction attempt came to.
     */
    private enum GraphOutcome {
        /** The graph was left as it was. */
        SKIPPED,
        /** The graph was rebuilt. */
        COMPACTED,
        /** The rebuild raised an error, which has been logged and reported. */
        FAILED
    }

    /**
     * Compacts one graph if it is worth it, or unconditionally when forced.
     *
     * @param graph     The graph to consider
     * @param force     true to rebuild regardless of the tombstone ratio
     * @param scheduled true when this is the background loop, which must not start a rebuild
     *                  after a stop has been requested
     * @return What happened to the graph
     */
    private GraphOutcome compactGraph(HnswGraph graph, boolean force, boolean scheduled) {
        long total;
        long tombstones;
        try {
            if (graph.isClosed()) {
                retireClosedGraph(graph);
                return GraphOutcome.SKIPPED;
            }
            total = graph.nodeCount();
            tombstones = graph.tombstoneCount();
        } catch (IllegalStateException refused) {
            if (!graph.isClosed()) {
                // Not the close race: something else refused to let the graph be measured,
                // and a graph nobody can even measure is a failure, not one with no work.
                handleCompactionFailure(graph, refused);
                return GraphOutcome.FAILED;
            }
            // The graph was closed between the check above and the read. Same situation as
            // above, and reaching it through an exception does not make it a failure.
            retireClosedGraph(graph);
            return GraphOutcome.SKIPPED;
        }

        // Nothing to reclaim: an empty graph, or one whose nodes are all live. Forcing
        // overrides the ratio policy, not arithmetic. Rebuilding here would copy every node
        // to produce the graph that is already there, and reporting it as a compaction would
        // tell an operator that space was reclaimed when none was.
        if (total == 0 || tombstones == 0) {
            LOGGER.debug("HNSW graph {} has no tombstoned nodes; nothing to reclaim.",
                graph.path());
            return GraphOutcome.SKIPPED;
        }

        double ratio = (double) tombstones / total;

        if (!force && ratio < tombstoneThreshold) {
            LOGGER.trace("Graph tombstone ratio ({}) below threshold ({}). Skipping compaction.",
                String.format("%.2f%%", ratio * 100),
                String.format("%.2f%%", tombstoneThreshold * 100));
            return GraphOutcome.SKIPPED;
        }

        // A compaction cannot be abandoned once started, so the loop never begins one after a
        // stop. A caller asking explicitly has said it wants this one to happen.
        if (scheduled && (!running.get() || stopped.get())) {
            return GraphOutcome.SKIPPED;
        }

        LOGGER.info("Graph tombstone ratio ({}) against threshold ({}). Starting compaction. " +
            "Total nodes: {}, Tombstones: {}",
            String.format("%.2f%%", ratio * 100),
            String.format("%.2f%%", tombstoneThreshold * 100),
            total, tombstones);

        try {
            // HnswGraph.compact builds the replacement next to the live file and owns the
            // swap, so there is nothing here to stage or clean up. The graph's own
            // directory is passed because the parameter is retained for compatibility.
            long removed = graph.compact(compactionScratchDir(graph));
            if (removed == 0) {
                // The tombstones were reclaimed by another caller between the check and the
                // rebuild, so HnswGraph.compact left the graph exactly as it was. No node was
                // renumbered and no space came back; counting it would inflate the run.
                LOGGER.debug("HNSW graph {} was already free of tombstones when the rebuild "
                    + "reached it.", graph.path());
                return GraphOutcome.SKIPPED;
            }

            compactionCount.incrementAndGet();
            totalNodesRemoved.addAndGet(removed);

            LOGGER.info("HNSW compaction completed: removed {} tombstoned nodes. " +
                "Total compactions: {}, Total removed: {}",
                removed, compactionCount.get(), totalNodesRemoved.get());

            notifyCompacted(graph, removed);
            return GraphOutcome.COMPACTED;

        } catch (IOException | RuntimeException e) {
            handleCompactionFailure(graph, e);
            return GraphOutcome.FAILED;
        }
    }

    /**
     * What an on-demand graph compaction run came to.
     *
     * <p>A considered graph that is neither compacted nor failed was skipped: it had no
     * tombstones to reclaim, its ratio was under the threshold on an unforced run, it was
     * already closed, or a shutdown reached the loop first.</p>
     *
     * @param graphsConsidered registered graphs the run examined
     * @param graphsCompacted  graphs actually rebuilt, each having reclaimed at least one
     *                         tombstoned node
     * @param graphsFailed     graphs whose rebuild raised an error
     */
    public record GraphCompactionSummary(int graphsConsidered, int graphsCompacted,
                                         int graphsFailed) {
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
     * Drops a registration whose graph has already been closed.
     *
     * <p>A closed graph cannot be examined, let alone rebuilt, and every later sweep would
     * meet the same wall. Left registered, it also aborted whichever sweep reached it, so the
     * graphs behind it in the iteration were never considered at all. Retiring it keeps the
     * run going, and the warning is what makes the lifecycle leak that left it registered
     * visible rather than silent.</p>
     *
     * @param graph The closed graph to retire
     */
    private void retireClosedGraph(HnswGraph graph) {
        LOGGER.warn("HNSW graph {} is closed but was still registered for compaction; "
            + "unregistering it. Nothing was reclaimed for it.", graph.path());
        unregisterGraph(graph);
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
