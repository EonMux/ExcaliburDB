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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * The resolved segments paired with the IDs they were resolved from.
     * Immutable after construction.
     */
    private volatile Map<Long, Segment> cachedSegmentsById;

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

        cachedSegments = List.copyOf(resolveVisibleSegmentsById().values());
        return cachedSegments;
    }

    /**
     * Resolves the visible segments, each paired with the ID it was resolved from.
     *
     * <p>The ID is the directory index the storage engine routes by, and it is the ID every
     * other component keys a segment on: the catalog, the visibility tracker, the term filter
     * hierarchy and the graph provider. It is not {@link Segment#segmentId()}, which counts
     * every segment instance a process has ever constructed and coincides with the directory
     * index only until the first compaction or split creates one out of order.</p>
     *
     * <p>Callers that need both the segment and an ID to look it up by must take the pair from
     * here rather than asking the segment for an ID of its own, because the two answers differ
     * without either side being able to tell.</p>
     *
     * @return Immutable map of directory index to segment, in visibility order
     */
    public Map<Long, Segment> resolveVisibleSegmentsById() {
        if (cachedSegmentsById != null) {
            return cachedSegmentsById;
        }

        synchronized (this) {
            if (cachedSegmentsById != null) {
                return cachedSegmentsById;
            }

            Map<Long, Segment> resolved = new LinkedHashMap<>(visibleSegmentIds.size());
            for (Long segmentId : visibleSegmentIds) {
                Segment segment = storageEngine.getSegment(segmentId);
                if (segment != null && !segment.isClosed()) {
                    resolved.put(segmentId, segment);
                }
            }

            cachedSegmentsById = Collections.unmodifiableMap(resolved);

            LOGGER.trace("Resolved {} segments for epoch {}", resolved.size(), epoch);
            return cachedSegmentsById;
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

            cachedStats = measureSnapshot();

            return cachedStats;
        }
    }

    /**
     * Measures the snapshot the caller is holding the monitor for.
     *
     * <p>Coverage is the queryable share of the whole catalog, not of the segments this
     * snapshot can see, so a query answered from half a corpus reports half rather than one.
     * A catalog with nothing in it has no share to report and answers zero.</p>
     *
     * @return The statistics to cache for this snapshot
     */
    private SnapshotStats measureSnapshot() {
        // Calculate segment breakdown by lifecycle state
        LifecycleBreakdown breakdown = visibleSegmentsByLifecycle();

        int totalQueryable = breakdown.queryable();
        int totalCatalog = catalog.size();
        double coverage = totalCatalog > 0 ? (double) totalQueryable / totalCatalog : 0.0;

        return new SnapshotStats(
            epoch,
            visibleSegmentIds.size(),
            totalQueryable,
            breakdown.indexing(),
            breakdown.warming(),
            breakdown.active(),
            breakdown.compacting(),
            breakdown.tombstoned(),
            coverage
        );
    }

    /**
     * Counts this snapshot's visible segments by the lifecycle state the catalog holds for them.
     *
     * <p>A visible id the catalog no longer knows is counted nowhere rather than counted as
     * some default state: the snapshot's id list is a point-in-time copy, and a segment purged
     * from the catalog since is not in any state to attribute it to.</p>
     *
     * @return The per-state counts across the visible segments
     */
    private LifecycleBreakdown visibleSegmentsByLifecycle() {
        LifecycleBreakdown breakdown = LifecycleBreakdown.NONE;

        for (Long segmentId : visibleSegmentIds) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata != null) {
                breakdown = breakdown.counting(metadata.getLifecycleState());
            }
        }

        return breakdown;
    }

    /**
     * How many of a snapshot's visible segments sit in each lifecycle state.
     *
     * @param indexing   INDEXING state count
     * @param warming    WARMING state count
     * @param active     ACTIVE state count
     * @param compacting COMPACTING state count
     * @param tombstoned TOMBSTONED state count, visible because the query's epoch predates
     *                   the tombstone
     */
    private record LifecycleBreakdown(
        int indexing,
        int warming,
        int active,
        int compacting,
        int tombstoned
    ) {
        /** The breakdown of no segments at all, which counting starts from. */
        static final LifecycleBreakdown NONE = new LifecycleBreakdown(0, 0, 0, 0, 0);

        /**
         * Returns this breakdown with one more segment counted against {@code state}.
         *
         * @param state The lifecycle state of the segment being counted
         * @return A new breakdown, or this one unchanged for a state no query sees
         */
        LifecycleBreakdown counting(LifecycleState state) {
            return switch (state) {
                case INDEXING -> new LifecycleBreakdown(indexing + 1, warming, active, compacting, tombstoned);
                case WARMING -> new LifecycleBreakdown(indexing, warming + 1, active, compacting, tombstoned);
                case ACTIVE -> new LifecycleBreakdown(indexing, warming, active + 1, compacting, tombstoned);
                case COMPACTING -> new LifecycleBreakdown(indexing, warming, active, compacting + 1, tombstoned);
                case TOMBSTONED -> new LifecycleBreakdown(indexing, warming, active, compacting, tombstoned + 1);
                default -> this; // BOOTSTRAPPING, PURGED not visible
            };
        }

        /**
         * The segments a query may actually be answered from.
         *
         * <p>Tombstoned segments are left out: they are in the snapshot because the query's
         * epoch predates their removal, not because they are corpus the query should be
         * measured against.</p>
         *
         * @return The count of segments in a queryable state
         */
        int queryable() {
            return indexing + warming + active + compacting;
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
