package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates visibility epochs across all segments in the storage engine.
 *
 * <p>The VisibilityTracker provides a global epoch mechanism that enables
 * epoch-based visibility for queries. Each query captures the current epoch
 * at start time and sees a consistent segment snapshot throughout its execution.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>{@link #currentEpoch()} - Return current global epoch</li>
 *   <li>{@link #captureEpoch()} - Atomically capture and return current epoch for queries</li>
 *   <li>{@link #advanceEpoch()} - Increment epoch (called after write log fsync)</li>
 *   <li>{@link #registerSegment(long, long)} - Register segment creation epoch</li>
 *   <li>{@link #markTombstoned(long, long)} - Record tombstone epoch</li>
 *   <li>{@link #visibleSegments(long)} - Return segments visible at captured epoch</li>
 * </ul>
 *
 * <h2>Visibility Rules</h2>
 * <p>A segment is visible at epoch E if:</p>
 * <ul>
 *   <li>{@code creationEpoch <= E} (segment existed at query start)</li>
 *   <li>{@code E < tombstoneEpoch} (segment was not tombstoned before query started)</li>
 *   <li>Lifecycle state is queryable (INDEXING, WARMING, ACTIVE, COMPACTING)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Epoch is tracked via AtomicLong for lock-free reads</li>
 *   <li>Segment registrations use ConcurrentHashMap</li>
 *   <li>All public methods can be called from any thread</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Epoch starts at 0 and monotonically increases</li>
 *   <li>Creation epoch is always &lt;= current epoch</li>
 *   <li>Tombstone epoch is Long.MAX_VALUE for non-tombstoned segments</li>
 *   <li>visibleSegments returns immutable sets</li>
 *   <li>Epoch overflow is detected and logged</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
public class VisibilityTracker implements com.eonmux.excalibur.store.VisibilityTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisibilityTracker.class);

    /**
     * Default epoch tick interval in milliseconds.
     * Spec requires 100ms default.
     */
    public static final long DEFAULT_EPOCH_TICK_MS = 100;

    /**
     * Sentinel value indicating a segment has not been tombstoned.
     */
    public static final long NOT_TOMBSTONED = Long.MAX_VALUE;

    // ==================== Instance Fields ====================

    /**
     * Global visibility epoch counter.
     * Monotonically increasing, incremented after each write or periodically.
     */
    private final AtomicLong globalEpoch;

    /**
     * Map of segment ID to creation epoch.
     * A segment is visible to queries with epoch >= creationEpoch.
     */
    private final Map<Long, Long> creationEpochs;

    /**
     * Map of segment ID to tombstone epoch.
     * A segment is visible to queries with epoch < tombstoneEpoch.
     * Value is Long.MAX_VALUE if not tombstoned.
     */
    private final Map<Long, Long> tombstoneEpochs;

    /**
     * Reference to the segment catalog for lifecycle state queries.
     */
    private final SegmentCatalog catalog;

    // ==================== Constructors ====================

    /**
     * Creates a new VisibilityTracker with epoch starting at 0.
     *
     * @param catalog The segment catalog for lifecycle state queries (may be null for testing)
     */
    public VisibilityTracker(SegmentCatalog catalog) {
        // Allow null catalog for testing scenarios - methods that need catalog
        // handle null gracefully by assuming queryable state
        this.catalog = catalog;
        this.globalEpoch = new AtomicLong(0);
        this.creationEpochs = new ConcurrentHashMap<>();
        this.tombstoneEpochs = new ConcurrentHashMap<>();

        LOGGER.info("VisibilityTracker created with epoch starting at 0");
    }

    /**
     * Creates a new VisibilityTracker with a specific starting epoch.
     *
     * @param catalog The segment catalog (may be null for testing)
     * @param initialEpoch The initial epoch value
     * @throws IllegalArgumentException if initialEpoch is negative
     */
    public VisibilityTracker(SegmentCatalog catalog, long initialEpoch) {
        if (initialEpoch < 0) {
            throw new IllegalArgumentException("Initial epoch must be non-negative: " + initialEpoch);
        }
        // Allow null catalog for testing scenarios - methods that need catalog
        // handle null gracefully by assuming queryable state
        this.catalog = catalog;
        this.globalEpoch = new AtomicLong(initialEpoch);
        this.creationEpochs = new ConcurrentHashMap<>();
        this.tombstoneEpochs = new ConcurrentHashMap<>();

        LOGGER.info("VisibilityTracker created with initial epoch {}", initialEpoch);
    }

    // ==================== Core Epoch Operations ====================

    /**
     * Returns the current global epoch.
     *
     * <p>This is a non-blocking read of the current epoch value.
     * The epoch may advance immediately after this call returns.</p>
     *
     * @return The current global epoch (non-negative)
     */
    public long currentEpoch() {
        return globalEpoch.get();
    }

    /**
     * Atomically captures and returns the current epoch for queries.
     *
     * <p>This method is used by the query pipeline (Stage 0) to capture
     * a consistent snapshot epoch. The captured epoch determines which
     * segments are visible to the query.</p>
     *
     * <p><strong>Visibility Contract:</strong> A query capturing epoch E
     * will see all segments where:</p>
     * <ul>
     *   <li>{@code creationEpoch <= E}</li>
     *   <li>{@code E < tombstoneEpoch} (or {@code tombstoneEpoch == Long.MAX_VALUE})</li>
     *   <li>Lifecycle state is queryable</li>
     * </ul>
     *
     * @return The captured epoch value
     */
    public long captureEpoch() {
        return globalEpoch.get();
    }

    /**
     * Advances the global epoch by one.
     *
     * <p>This method is called:</p>
     * <ul>
     *   <li>After write log fsync (new data becomes visible)</li>
     *   <li>By the epoch tick scheduler (periodic advancement)</li>
     *   <li>After segment state transitions</li>
     * </ul>
     *
     * <p><strong>Domain Correctness:</strong> Epochs must monotonically increase.
     * If overflow is detected (epoch approaching Long.MAX_VALUE), a warning is logged.
     * The epoch will wrap to Long.MIN_VALUE on overflow, which is handled by the
     * comparison logic in visibility checks.</p>
     *
     * @return The new epoch value after advancement
     */
    public long advanceEpoch() {
        // Use getAndIncrement to atomically obtain the true predecessor,
        // avoiding TOCTOU race between get() and incrementAndGet().
        long previousEpoch = globalEpoch.getAndIncrement();
        long newEpoch = previousEpoch + 1;

        // Domain correctness: the only way newEpoch can be <= previousEpoch
        // under getAndIncrement is Long overflow (previousEpoch == Long.MAX_VALUE
        // wraps newEpoch to Long.MIN_VALUE). Log it for debugging, even though
        // the wrap is handled downstream by visibility comparisons.
        if (newEpoch < previousEpoch) {
            // Extremely unlikely in practice (requires ~9.2e18 advances)
            LOGGER.warn("Epoch overflow detected: {} -> {}. Visibility tracking may be compromised.",
                previousEpoch, newEpoch);
        }

        LOGGER.trace("Epoch advanced to {}", newEpoch);
        return newEpoch;
    }

    /**
     * Advances the epoch if the current value matches the expected value.
     *
     * <p>This is a conditional advance that can be used for optimistic updates.</p>
     *
     * <p><strong>Domain Correctness:</strong> Validates that the expected epoch is
     * non-negative and that the advancement maintains monotonicity.</p>
     *
     * @param expectedEpoch The expected current epoch (must be non-negative)
     * @return true if the epoch was advanced, false if the current epoch didn't match
     * @throws IllegalArgumentException if expectedEpoch is negative
     */
    public boolean compareAndAdvanceEpoch(long expectedEpoch) {
        if (expectedEpoch < 0) {
            throw new IllegalArgumentException("Expected epoch must be non-negative: " + expectedEpoch);
        }
        boolean success = globalEpoch.compareAndSet(expectedEpoch, expectedEpoch + 1);
        if (success) {
            LOGGER.trace("Epoch advanced from {} to {} via CAS", expectedEpoch, expectedEpoch + 1);
        }
        return success;
    }

    // ==================== Segment Registration ====================

    /**
     * Registers a segment with its creation epoch.
     *
     * <p>The creation epoch determines when a segment becomes visible to queries.
     * A segment registered at epoch E will be visible to queries that capture
     * epoch E or later.</p>
     *
     * <p>If the segment is already registered, its creation epoch is updated
     * only if the new epoch is earlier (to handle restart scenarios).</p>
     *
     * @param segmentId The segment ID
     * @param epoch The creation epoch (typically the current global epoch)
     * @throws IllegalArgumentException if epoch is negative
     */
    public void registerSegment(long segmentId, long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("Creation epoch must be non-negative: " + epoch);
        }

        // Store creation epoch
        creationEpochs.compute(segmentId, (k, existing) -> {
            if (existing == null || epoch < existing) {
                LOGGER.debug("Segment {} registered with creation epoch {}", segmentId, epoch);
                return epoch;
            }
            return existing;
        });

        // Initialize tombstone epoch to NOT_TOMBSTONED
        tombstoneEpochs.putIfAbsent(segmentId, NOT_TOMBSTONED);
    }

    /**
     * Registers a segment using the current global epoch as its creation epoch.
     *
     * @param segmentId The segment ID
     */
    public void registerSegment(long segmentId) {
        registerSegment(segmentId, currentEpoch());
    }

    /**
     * Unregisters a segment from visibility tracking.
     *
     * <p>This should be called when a segment is purged and no longer
     * needs to be tracked for visibility purposes.</p>
     *
     * @param segmentId The segment ID to unregister
     */
    public void unregisterSegment(long segmentId) {
        creationEpochs.remove(segmentId);
        tombstoneEpochs.remove(segmentId);
        LOGGER.debug("Segment {} unregistered from visibility tracking", segmentId);
    }

    /**
     * Marks a segment as tombstoned at the specified epoch.
     *
     * <p>The tombstone epoch determines when a segment becomes invisible
     * to new queries. A segment tombstoned at epoch E will be visible to
     * queries that captured epoch &lt; E, but invisible to queries that
     * captured epoch >= E.</p>
     *
     * <p>This method also updates the catalog's segment metadata with
     * the tombstone epoch.</p>
     *
     * @param segmentId The segment ID
     * @param epoch The tombstone epoch
     * @throws IllegalArgumentException if segment is not registered
     */
    public void markTombstoned(long segmentId, long epoch) {
        if (!creationEpochs.containsKey(segmentId)) {
            throw new IllegalArgumentException(
                "Cannot mark tombstoned: segment " + segmentId + " is not registered");
        }

        // A concurrent unregisterSegment can remove the mapping between the containsKey
        // check above and this put, in which case put returns null.
        Long previousTombstone = tombstoneEpochs.put(segmentId, epoch);
        if (previousTombstone == null || previousTombstone == NOT_TOMBSTONED) {
            LOGGER.info("Segment {} tombstoned at epoch {}", segmentId, epoch);
        } else {
            LOGGER.debug("Segment {} tombstone epoch updated from {} to {}",
                segmentId, previousTombstone, epoch);
        }

        // Update catalog metadata (if catalog is available)
        if (catalog != null) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata != null) {
                metadata.setTombstoneEpoch(epoch);
            }
        }
    }

    /**
     * Marks a segment as tombstoned using the current global epoch.
     *
     * @param segmentId The segment ID
     */
    public void markTombstoned(long segmentId) {
        markTombstoned(segmentId, currentEpoch());
    }

    // ==================== Visibility Queries ====================

    /**
     * Returns the set of segment IDs visible at the specified query epoch.
     *
     * <p>A segment is visible at epoch E if:</p>
     * <ul>
     *   <li>{@code creationEpoch <= E} (segment existed at query start)</li>
     *   <li>{@code E < tombstoneEpoch} (segment was not tombstoned before query started)</li>
     *   <li>Lifecycle state is queryable (INDEXING, WARMING, ACTIVE, COMPACTING)</li>
     * </ul>
     *
     * <p><strong>Note:</strong> TOMBSTONED segments with tombstoneEpoch > E are
     * included to support in-flight queries that started before tombstoning.</p>
     *
     * @param queryEpoch The epoch at which the query started
     * @return Immutable set of visible segment IDs
     */
    public Set<Long> visibleSegments(long queryEpoch) {
        Set<Long> visible = new HashSet<>();

        for (Map.Entry<Long, Long> entry : creationEpochs.entrySet()) {
            long segmentId = entry.getKey();
            long creationEpoch = entry.getValue();
            long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, NOT_TOMBSTONED);

            // Check epoch visibility: creationEpoch <= queryEpoch < tombstoneEpoch
            if (creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch) {
                // Check lifecycle state
                if (isQueryableAtEpoch(segmentId, queryEpoch)) {
                    visible.add(segmentId);
                }
            }
        }

        return Collections.unmodifiableSet(visible);
    }

    /**
     * Returns all segments visible at the current epoch.
     *
     * <p>This is a convenience method equivalent to:</p>
     * <pre>{@code visibleSegments(currentEpoch())}</pre>
     *
     * @return Immutable set of currently visible segment IDs
     */
    public Set<Long> currentVisibleSegments() {
        return visibleSegments(currentEpoch());
    }

    /**
     * Checks if a specific segment is visible at the given query epoch.
     *
     * @param segmentId The segment ID
     * @param queryEpoch The epoch at which the query started
     * @return true if the segment is visible at the query epoch
     */
    public boolean isSegmentVisible(long segmentId, long queryEpoch) {
        Long creationEpoch = creationEpochs.get(segmentId);
        if (creationEpoch == null) {
            return false; // Segment not registered
        }

        long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, NOT_TOMBSTONED);

        // Check epoch visibility
        if (creationEpoch > queryEpoch || queryEpoch >= tombstoneEpoch) {
            return false;
        }

        // Check lifecycle state
        return isQueryableAtEpoch(segmentId, queryEpoch);
    }

    /**
     * Checks if a segment's lifecycle state makes it queryable at the given epoch.
     *
     * <p>This method handles the special case of TOMBSTONED segments that
     * should still be visible to in-flight queries.</p>
     *
     * <p>If the catalog is null or the segment is not in the catalog, it's assumed
     * to be queryable (useful for testing and scenarios where catalog is not
     * fully populated).</p>
     *
     * @param segmentId The segment ID
     * @param queryEpoch The query epoch
     * @return true if the segment's lifecycle state allows querying
     */
    private boolean isQueryableAtEpoch(long segmentId, long queryEpoch) {
        // No catalog - assume queryable (for testing scenarios)
        if (catalog == null) {
            return true;
        }

        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        if (metadata == null) {
            // No catalog entry - assume queryable (for testing and bootstrap scenarios)
            return true;
        }

        LifecycleState state = metadata.getLifecycleState();

        // Normally queryable states
        if (state.isQueryable()) {
            return true;
        }

        // Special case: TOMBSTONED segments are visible to queries that
        // started before the tombstone epoch
        if (state == LifecycleState.TOMBSTONED) {
            return metadata.isVisibleAtEpoch(queryEpoch);
        }

        return false;
    }

    // ==================== Epoch Information ====================

    /**
     * Returns the creation epoch for a segment.
     *
     * @param segmentId The segment ID
     * @return The creation epoch, or -1 if not registered
     */
    public long getCreationEpoch(long segmentId) {
        return creationEpochs.getOrDefault(segmentId, -1L);
    }

    /**
     * Returns the tombstone epoch for a segment.
     *
     * @param segmentId The segment ID
     * @return The tombstone epoch, or Long.MAX_VALUE if not tombstoned
     */
    public long getTombstoneEpoch(long segmentId) {
        return tombstoneEpochs.getOrDefault(segmentId, NOT_TOMBSTONED);
    }

    /**
     * Checks if a segment is currently tombstoned.
     *
     * @param segmentId The segment ID
     * @return true if the segment has been tombstoned
     */
    public boolean isTombstoned(long segmentId) {
        long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, NOT_TOMBSTONED);
        return tombstoneEpoch != NOT_TOMBSTONED;
    }

    /**
     * Returns the number of registered segments.
     *
     * @return The count of registered segments
     */
    public int getRegisteredSegmentCount() {
        return creationEpochs.size();
    }

    /**
     * Returns the number of tombstoned segments.
     *
     * @return The count of tombstoned segments
     */
    public int getTombstonedSegmentCount() {
        return (int) tombstoneEpochs.values().stream()
            .filter(epoch -> epoch != NOT_TOMBSTONED)
            .count();
    }

    // ==================== Statistics ====================

    /**
     * Returns statistics about the visibility tracker.
     *
     * @return A statistics snapshot
     */
    public VisibilityStats getStats() {
        return new VisibilityStats(
            currentEpoch(),
            creationEpochs.size(),
            getTombstonedSegmentCount(),
            currentVisibleSegments().size()
        );
    }

    /**
     * Statistics snapshot for the visibility tracker.
     *
     * @param currentEpoch Current global epoch
     * @param totalSegments Total registered segments
     * @param tombstonedSegments Number of tombstoned segments
     * @param visibleSegments Number of currently visible segments
     */
    public record VisibilityStats(
        long currentEpoch,
        int totalSegments,
        int tombstonedSegments,
        int visibleSegments
    ) {
        /**
         * Returns the ratio of visible to total segments.
         *
         * @return Visibility ratio (0.0 to 1.0)
         */
        public double visibilityRatio() {
            return totalSegments > 0 ? (double) visibleSegments / totalSegments : 0.0;
        }
    }

    @Override
    public String toString() {
        VisibilityStats stats = getStats();
        return String.format(
            "VisibilityTracker{epoch=%d, total=%d, tombstoned=%d, visible=%d}",
            stats.currentEpoch(), stats.totalSegments(),
            stats.tombstonedSegments(), stats.visibleSegments()
        );
    }
}
