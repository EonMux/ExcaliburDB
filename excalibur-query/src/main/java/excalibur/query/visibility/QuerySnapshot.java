package excalibur.query.visibility;

import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A consistent snapshot of visible segments for query execution.
 *
 * <p>QuerySnapshot captures the visibility epoch at query start time and
 * provides a consistent view of which segments are visible to the query.
 * This enables epoch-based visibility tracking where queries see a
 * consistent snapshot even as segments are being modified, created,
 * or tombstoned.</p>
 *
 * <h2>Query Pipeline Integration (Stage 0)</h2>
 * <p>QuerySnapshot is used in Stage 0 of the query pipeline:</p>
 * <ol>
 *   <li>Capture visibility epoch via {@link QuerySnapshot#capture(VisibilityTracker, SegmentCatalog, StorageEngine)}</li>
 *   <li>Resolve queryable segments via {@link #resolveVisibleSegments()}</li>
 *   <li>Use segments throughout query execution for consistent visibility</li>
 * </ol>
 *
 * <h2>Visibility Rules</h2>
 * <p>A segment is visible at epoch E if:</p>
 * <ul>
 *   <li>{@code creationEpoch <= E} (segment existed at query start)</li>
 *   <li>{@code E < tombstoneEpoch} (segment was not tombstoned before query started)</li>
 *   <li>Lifecycle state is queryable (INDEXING, WARMING, ACTIVE, COMPACTING)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Epoch is non-negative</li>
 *   <li>Segment list is immutable after creation</li>
 *   <li>Coverage ratio is in range [0.0, 1.0]</li>
 *   <li>Segment IDs are unique within the snapshot</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>Instances of this class are immutable and fully thread-safe.</strong></p>
 * <p>The segment list is a defensive copy made at snapshot time.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class QuerySnapshot {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuerySnapshot.class);

    // ==================== Instance Fields ====================

    /**
     * The visibility epoch at which this snapshot was captured.
     * Queries with this snapshot see segments visible at this epoch.
     */
    private final long epoch;

    /**
     * The list of segment IDs visible at the captured epoch.
     * Immutable after construction.
     */
    private final List<Long> visibleSegmentIds;

    /**
     * The segment catalog for resolving segment references.
     */
    private final SegmentCatalog catalog;

    /**
     * The storage engine for segment access.
     */
    private final StorageEngine storageEngine;

    /**
     * Cached list of resolved segments (lazy initialization).
     */
    private volatile List<Segment> cachedSegments;

    /**
     * Snapshot statistics (lazy initialization).
     */
    private volatile SnapshotStats cachedStats;

    // ==================== Constructors ====================

    /**
     * Creates a new QuerySnapshot with the specified epoch and visible segments.
     *
     * @param epoch             The visibility epoch
     * @param visibleSegmentIds The set of visible segment IDs
     * @param catalog           The segment catalog
     * @param storageEngine     The storage engine
     * @throws NullPointerException     if visibleSegmentIds, catalog, or storageEngine is null
     * @throws IllegalArgumentException if epoch is negative
     */
    private QuerySnapshot(long epoch, Set<Long> visibleSegmentIds,
                           SegmentCatalog catalog, StorageEngine storageEngine) {
        if (epoch < 0) {
            throw new IllegalArgumentException("Epoch must be non-negative: " + epoch);
        }
        Objects.requireNonNull(visibleSegmentIds, "Visible segment IDs cannot be null");
        Objects.requireNonNull(catalog, "Catalog cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        this.epoch = epoch;
        this.visibleSegmentIds = Collections.unmodifiableList(new ArrayList<>(visibleSegmentIds));
        this.catalog = catalog;
        this.storageEngine = storageEngine;

        LOGGER.trace("QuerySnapshot created at epoch {} with {} visible segments",
            epoch, visibleSegmentIds.size());
    }

    // ==================== Factory Methods ====================

    /**
     * Captures a consistent snapshot of visible segments for query execution.
     *
     * <p>This is the primary factory method for creating QuerySnapshot instances.
     * It captures the current epoch from the VisibilityTracker and resolves
     * the set of segments visible at that epoch.</p>
     *
     * <p><strong>Usage in Query Pipeline (Stage 0):</strong></p>
     * <pre>{@code
     * QuerySnapshot snapshot = QuerySnapshot.capture(visibilityTracker, catalog, storageEngine);
     * List<Segment> segments = snapshot.resolveVisibleSegments();
     * for (Segment segment : segments) {
     *     // Query segment with consistent visibility
     * }
     * }</pre>
     *
     * @param visibilityTracker The visibility tracker for epoch coordination
     * @param catalog           The segment catalog
     * @param storageEngine     The storage engine
     * @return A new QuerySnapshot capturing the current visibility state
     * @throws NullPointerException if any parameter is null
     */
    public static QuerySnapshot capture(
            VisibilityTracker visibilityTracker,
            SegmentCatalog catalog,
            StorageEngine storageEngine) {

        Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        Objects.requireNonNull(catalog, "Catalog cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        // Capture epoch atomically
        long capturedEpoch = visibilityTracker.captureEpoch();

        // Get visible segments at captured epoch
        Set<Long> visibleSegmentIds = visibilityTracker.visibleSegments(capturedEpoch);

        LOGGER.debug("Captured query snapshot at epoch {} with {} visible segments",
            capturedEpoch, visibleSegmentIds.size());

        return new QuerySnapshot(capturedEpoch, visibleSegmentIds, catalog, storageEngine);
    }

    /**
     * Creates a QuerySnapshot at a specific epoch.
     *
     * <p>This method is useful for testing or replaying queries at
     * historical epochs.</p>
     *
     * @param epoch             The epoch to capture at
     * @param visibilityTracker The visibility tracker
     * @param catalog           The segment catalog
     * @param storageEngine     The storage engine
     * @return A new QuerySnapshot at the specified epoch
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if epoch is negative
     */
    public static QuerySnapshot atEpoch(
            long epoch,
            VisibilityTracker visibilityTracker,
            SegmentCatalog catalog,
            StorageEngine storageEngine) {

        Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        Objects.requireNonNull(catalog, "Catalog cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        if (epoch < 0) {
            throw new IllegalArgumentException("Epoch must be non-negative: " + epoch);
        }

        Set<Long> visibleSegmentIds = visibilityTracker.visibleSegments(epoch);

        return new QuerySnapshot(epoch, visibleSegmentIds, catalog, storageEngine);
    }

    // ==================== Segment Resolution ====================

    /**
     * Resolves and returns the list of visible segments.
     *
     * <p>The returned list contains Segment instances for all segment IDs
     * visible at the captured epoch. The list is cached for subsequent calls.</p>
     *
     * <p><strong>Note:</strong> The returned segments may have been modified
     * since the snapshot was captured. The visibility guarantee ensures that
     * the segment existed at capture time, not that its contents are frozen.</p>
     *
     * @return Immutable list of visible segments
     */
    public List<Segment> resolveVisibleSegments() {
        if (cachedSegments != null) {
            return cachedSegments;
        }

        synchronized (this) {
            if (cachedSegments != null) {
                return cachedSegments;
            }

            List<Segment> segments = new ArrayList<>(visibleSegmentIds.size());
            for (Long segmentId : visibleSegmentIds) {
                Segment segment = storageEngine.getSegment(segmentId);
                if (segment != null && !segment.isClosed()) {
                    segments.add(segment);
                }
            }

            cachedSegments = Collections.unmodifiableList(segments);

            LOGGER.trace("Resolved {} segments for epoch {}", segments.size(), epoch);
            return cachedSegments;
        }
    }

    /**
     * Returns the list of visible segment IDs.
     *
     * <p>This method is more lightweight than {@link #resolveVisibleSegments()}
     * as it doesn't require resolving segment references.</p>
     *
     * @return Immutable list of visible segment IDs
     */
    public List<Long> visibleSegmentIds() {
        return visibleSegmentIds;
    }

    /**
     * Returns the number of visible segments in this snapshot.
     *
     * @return Visible segment count
     */
    public int visibleSegmentCount() {
        return visibleSegmentIds.size();
    }

    // ==================== Epoch Access ====================

    /**
     * Returns the visibility epoch at which this snapshot was captured.
     *
     * @return The captured epoch
     */
    public long epoch() {
        return epoch;
    }

    /**
     * Checks if a specific segment is visible in this snapshot.
     *
     * @param segmentId The segment ID to check
     * @return true if the segment is visible in this snapshot
     */
    public boolean isSegmentVisible(long segmentId) {
        return visibleSegmentIds.contains(segmentId);
    }

    // ==================== Statistics ====================

    /**
     * Returns statistics about this snapshot.
     *
     * @return Snapshot statistics
     */
    public SnapshotStats getStats() {
        if (cachedStats != null) {
            return cachedStats;
        }

        synchronized (this) {
            if (cachedStats != null) {
                return cachedStats;
            }

            // Calculate segment breakdown by lifecycle state
            int indexing = 0, warming = 0, active = 0, compacting = 0, tombstoned = 0;

            for (Long segmentId : visibleSegmentIds) {
                SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
                if (metadata != null) {
                    LifecycleState state = metadata.getLifecycleState();
                    switch (state) {
                        case INDEXING -> indexing++;
                        case WARMING -> warming++;
                        case ACTIVE -> active++;
                        case COMPACTING -> compacting++;
                        case TOMBSTONED -> tombstoned++;
                        default -> {} // BOOTSTRAPPING, PURGED not visible
                    }
                }
            }

            int totalQueryable = indexing + warming + active + compacting;
            int totalCatalog = catalog.size();
            double coverage = totalCatalog > 0 ? (double) totalQueryable / totalCatalog : 0.0;

            cachedStats = new SnapshotStats(
                epoch,
                visibleSegmentIds.size(),
                totalQueryable,
                indexing,
                warming,
                active,
                compacting,
                tombstoned,
                coverage
            );

            return cachedStats;
        }
    }

    /**
     * Statistics about a query snapshot.
     *
     * @param epoch       The captured epoch
     * @param totalVisible Total visible segments
     * @param queryable    Total queryable segments
     * @param indexing     INDEXING state count
     * @param warming      WARMING state count
     * @param active       ACTIVE state count
     * @param compacting   COMPACTING state count
     * @param tombstoned   TOMBSTONED state count (visible due to epoch)
     * @param coverage     Estimated corpus coverage (0.0 to 1.0)
     */
    public record SnapshotStats(
        long epoch,
        int totalVisible,
        int queryable,
        int indexing,
        int warming,
        int active,
        int compacting,
        int tombstoned,
        double coverage
    ) {
        /**
         * Returns true if coverage is below the degraded threshold.
         *
         * @param threshold The degraded threshold (default 0.5)
         * @return true if coverage is degraded
         */
        public boolean isDegraded(double threshold) {
            return coverage < threshold;
        }

        /**
         * Returns true if coverage is below the default degraded threshold (0.5).
         *
         * @return true if coverage is degraded
         */
        public boolean isDegraded() {
            return isDegraded(0.5);
        }
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QuerySnapshot that = (QuerySnapshot) o;
        return epoch == that.epoch &&
               visibleSegmentIds.equals(that.visibleSegmentIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, visibleSegmentIds);
    }

    @Override
    public String toString() {
        return String.format("QuerySnapshot{epoch=%d, segments=%d}",
            epoch, visibleSegmentIds.size());
    }
}
