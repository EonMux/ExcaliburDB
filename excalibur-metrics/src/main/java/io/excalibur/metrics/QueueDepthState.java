package io.excalibur.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The HNSW integration backlog and the limit its severity is judged against.
 *
 * <h2>Why the limit lives here</h2>
 * <p>A depth on its own says nothing: fifty thousand queued entries is idle on one deployment
 * and an outage on another. The verdicts an operator actually alerts on - under control,
 * critical, full - are ratios, so the limit that backpressure really applies at has to be
 * reachable from wherever the depth is read. Holding the two together is what keeps a JMX
 * verdict from being computed against a default the running shard is not using.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>Both values are atomics. Depth is a plain set from whichever worker last measured the
 * backlog - it is a level, not a tally, so there is no increment to lose and the newest
 * measurement is the correct one. The verdicts take the limit as an argument rather than
 * reading it, so that a caller comparing several of them compares them all against one limit
 * even if configuration changes mid-read.</p>
 *
 * @author ExcaliburDB
 */
final class QueueDepthState {

    /**
     * Default HNSW integration queue depth limit. Matches the default of
     * {@code excalibur.shard.hnsw-integration-queue-depth-limit}.
     */
    private static final long DEFAULT_HNSW_QUEUE_LIMIT = 100_000;

    private final AtomicLong depth = new AtomicLong(0);
    private final AtomicLong limit = new AtomicLong(DEFAULT_HNSW_QUEUE_LIMIT);

    /**
     * Creates an empty backlog judged against the shard module's default limit.
     */
    QueueDepthState() {
    }

    /**
     * Gets the current queue depth.
     *
     * @return Entries not yet absorbed into the graph
     */
    long depth() {
        return depth.get();
    }

    /**
     * Records the current queue depth.
     *
     * @param value Measured depth
     */
    void setDepth(long value) {
        depth.set(value);
    }

    /**
     * Gets the queue depth limit the verdicts are evaluated against.
     *
     * @return Configured queue depth limit
     */
    long limit() {
        return limit.get();
    }

    /**
     * Sets the queue depth limit.
     *
     * @param value Queue depth limit; callers screen out non-positive limits
     */
    void setLimit(long value) {
        limit.set(value);
    }

    /**
     * Gets the queue depth as a percentage of a limit, clamped to [0, 100].
     *
     * @param against Maximum queue depth limit
     * @return Percentage of the limit, or 0.0 when the limit is not positive
     */
    double percentOf(long against) {
        if (against <= 0) {
            return 0.0;
        }
        double percent = (double) depth() / against * 100.0;
        return Math.max(0.0, Math.min(100.0, percent));
    }

    /**
     * Checks whether the queue is under control, at less than half of a limit.
     *
     * @param against Maximum queue depth limit
     * @return true when the depth is below half the limit
     */
    boolean isUnderControl(long against) {
        return depth() < against * 0.5;
    }

    /**
     * Checks whether the queue is critical, at more than four fifths of a limit.
     *
     * @param against Maximum queue depth limit
     * @return true when the depth is above 80% of the limit
     */
    boolean isCritical(long against) {
        return depth() > against * 0.8;
    }

    /**
     * Checks whether the queue is full, past a limit.
     *
     * @param against Maximum queue depth limit
     * @return true when the depth is above the limit
     */
    boolean isFull(long against) {
        return depth() > against;
    }
}
