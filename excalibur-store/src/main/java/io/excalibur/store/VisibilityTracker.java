package io.excalibur.store;

import java.util.Set;

/**
 * Coordinates visibility epochs across segments for consistent query snapshots.
 *
 * <p>This interface provides the contract for epoch-based visibility tracking.
 * A query captures the current epoch at start time and sees a consistent segment
 * snapshot throughout its execution.</p>
 *
 * <h2>Visibility Rules</h2>
 * <p>A segment is visible at epoch E if:</p>
 * <ul>
 *   <li>{@code creationEpoch <= E} (segment existed at query start)</li>
 *   <li>{@code E < tombstoneEpoch} (segment was not tombstoned before query started)</li>
 *   <li>Lifecycle state is queryable (INDEXING, WARMING, ACTIVE, COMPACTING)</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>Simple local implementation for basic usage</li>
 *   <li>Full implementation in {@code excalibur-shard} with segment registration</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. All methods can be called from any thread.</p>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
public interface VisibilityTracker {

    /**
     * Returns the current global epoch.
     *
     * <p>This is a non-blocking read of the current epoch value.
     * The epoch may advance immediately after this call returns.</p>
     *
     * @return The current global epoch (non-negative)
     */
    long currentEpoch();

    /**
     * Atomically captures and returns the current epoch for queries.
     *
     * <p>This method is used by the query pipeline (Stage 0) to capture
     * a consistent snapshot epoch.</p>
     *
     * @return The captured epoch value
     */
    long captureEpoch();

    /**
     * Advances the global epoch by one.
     *
     * <p>This method is called after write log fsync or periodically.</p>
     *
     * @return The new epoch value after advancement
     */
    long advanceEpoch();

    /**
     * Registers a segment with its creation epoch.
     *
     * @param segmentId The segment ID
     * @param epoch The creation epoch
     */
    void registerSegment(long segmentId, long epoch);

    /**
     * Marks a segment as tombstoned at the specified epoch.
     *
     * @param segmentId The segment ID
     * @param epoch The tombstone epoch
     */
    void markTombstoned(long segmentId, long epoch);

    /**
     * Returns the set of segment IDs visible at the specified query epoch.
     *
     * @param queryEpoch The epoch at which the query started
     * @return Set of visible segment IDs
     */
    Set<Long> visibleSegments(long queryEpoch);

    /**
     * Checks if a specific segment is visible at the given query epoch.
     *
     * @param segmentId The segment ID
     * @param queryEpoch The epoch at which the query started
     * @return true if the segment is visible at the query epoch
     */
    boolean isSegmentVisible(long segmentId, long queryEpoch);

    /**
     * Returns the creation epoch for a segment.
     *
     * @param segmentId The segment ID
     * @return The creation epoch, or -1 if not registered
     */
    long getCreationEpoch(long segmentId);

    /**
     * Returns the tombstone epoch for a segment.
     *
     * @param segmentId The segment ID
     * @return The tombstone epoch, or Long.MAX_VALUE if not tombstoned
     */
    long getTombstoneEpoch(long segmentId);

    /**
     * Simple default implementation of VisibilityTracker.
     *
     * <p>This implementation provides basic epoch tracking without segment
     * registration. It's suitable for StorageEngine when a full
     * VisibilityTracker from the shard module is not available.</p>
     */
    class Default implements VisibilityTracker {
        private final java.util.concurrent.atomic.AtomicLong epoch =
            new java.util.concurrent.atomic.AtomicLong(0);

        @Override
        public long currentEpoch() {
            return epoch.get();
        }

        @Override
        public long captureEpoch() {
            return epoch.get();
        }

        @Override
        public long advanceEpoch() {
            return epoch.incrementAndGet();
        }

        @Override
        public void registerSegment(long segmentId, long epoch) {
            // No-op in simple implementation
        }

        @Override
        public void markTombstoned(long segmentId, long epoch) {
            // No-op in simple implementation
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            // Returns empty set - caller must use catalog directly
            return Set.of();
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            // In simple implementation, all segments are visible
            return true;
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            return 0; // All segments created at epoch 0
        }

        @Override
        public long getTombstoneEpoch(long segmentId) {
            return Long.MAX_VALUE; // Not tombstoned
        }
    }
}
