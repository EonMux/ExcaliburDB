package excalibur.ingest.pipeline;

/**
 * Interface for tracking visibility epochs during reindexing.
 *
 * <p>This interface provides the minimal API needed by DocumentReindexer for
 * epoch-based visibility tracking during rolling replacement. The full
 * implementation is provided by the excalibur-shard module's VisibilityTracker.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
@FunctionalInterface
public interface VisibilityTracker {

    /**
     * Returns the current visibility epoch.
     *
     * <p>The visibility epoch is a monotonically increasing counter that
     * determines which segments are visible to queries. Each query captures
     * the epoch at start time and sees a consistent snapshot.</p>
     *
     * @return The current visibility epoch (non-negative)
     */
    long currentEpoch();

    /**
     * Advances the visibility epoch by one.
     *
     * <p>This should be called after new data has been written and synced
     * to make it visible to new queries.</p>
     *
     * @return The new epoch value after advancement
     */
    default long advanceEpoch() {
        return currentEpoch() + 1;
    }
}
