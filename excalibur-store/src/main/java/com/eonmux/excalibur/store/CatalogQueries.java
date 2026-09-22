package com.eonmux.excalibur.store;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The derived views over {@link CatalogRegistry}: everything the catalog can be asked that
 * is not simply "give me the record for this id".
 *
 * <p>Three kinds of question live here, and they share one property that is the reason to
 * keep them together: none of them is stored. Each is computed from the records as they are
 * at the moment of asking, so none of them can go stale, and none of them needs invalidating
 * when a segment changes state.</p>
 *
 * <ul>
 *   <li><strong>Selections</strong> - the segments belonging to a source, in a state, or
 *       visible to a query. These drive rolling reindex and query planning.</li>
 *   <li><strong>Aggregates</strong> - the per-state counts and the entry total, rolled up
 *       into a {@link SegmentCatalog.CatalogMetadata} snapshot. Recomputed on every call
 *       rather than maintained incrementally: a counter that is incremented on one
 *       transition path and forgotten on another drifts silently, and this is the number a
 *       node's readiness is judged by.</li>
 *   <li><strong>Write-log offsets</strong> - how far each segment's index has absorbed its
 *       log, and therefore how much of the tail a query still has to scan itself.</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> thread-safe, but each answer is a snapshot taken
 * without a global lock. An aggregate computed while segments are transitioning reflects
 * some interleaving of those transitions rather than any single instant - which is the
 * right trade for a statistic that is read far more often than the states it counts change.</p>
 */
final class CatalogQueries {

    /** The records every answer is computed from. */
    private final CatalogRegistry registry;

    /**
     * Creates the query surface over a registry.
     *
     * @param registry The record store
     */
    CatalogQueries(CatalogRegistry registry) {
        this.registry = registry;
    }

    // ==================== Selections ====================

    /**
     * Gets all segments associated with a specific source.
     *
     * @param sourceId The source ID to filter by
     * @return List of segment metadata for segments belonging to the source
     */
    List<SegmentCatalog.SegmentMetadata> bySource(String sourceId) {
        if (sourceId == null) {
            return List.of();
        }

        return registry.values().stream()
            .filter(m -> sourceId.equals(m.getSourceId()))
            .collect(Collectors.toList());
    }

    /**
     * Gets all ACTIVE segments associated with a specific source.
     *
     * @param sourceId The source ID to filter by
     * @return List of segment IDs for ACTIVE segments belonging to the source
     */
    List<Long> activeBySource(String sourceId) {
        if (sourceId == null) {
            return List.of();
        }

        return registry.values().stream()
            .filter(m -> sourceId.equals(m.getSourceId()))
            .filter(m -> m.getLifecycleState() == LifecycleState.ACTIVE)
            .map(SegmentCatalog.SegmentMetadata::getSegmentId)
            .collect(Collectors.toList());
    }

    /**
     * Gets all segments in a specific lifecycle state.
     *
     * @param state The lifecycle state to filter by
     * @return List of segment IDs in the specified state
     */
    List<Long> byState(LifecycleState state) {
        return registry.values().stream()
            .filter(m -> m.getLifecycleState() == state)
            .map(SegmentCatalog.SegmentMetadata::getSegmentId)
            .collect(Collectors.toList());
    }

    /**
     * Gets all segments that are queryable (INDEXING, WARMING, ACTIVE, COMPACTING).
     *
     * @return List of queryable segment IDs
     */
    List<Long> queryable() {
        return registry.values().stream()
            .filter(m -> m.getLifecycleState().isQueryable())
            .map(SegmentCatalog.SegmentMetadata::getSegmentId)
            .collect(Collectors.toList());
    }

    // ==================== Aggregates ====================

    /**
     * Computes an immutable snapshot of the per-state counts and the entry total.
     *
     * @return Immutable CatalogMetadata snapshot
     */
    SegmentCatalog.CatalogMetadata snapshot() {
        long total = registry.size();
        long active = 0;
        long indexing = 0;
        long warming = 0;
        long compacting = 0;
        long tombstoned = 0;
        long purged = 0;
        long totalEntryCount = 0;

        for (SegmentCatalog.SegmentMetadata m : registry.values()) {
            LifecycleState state = m.getLifecycleState();
            switch (state) {
                case ACTIVE -> active++;
                case INDEXING -> indexing++;
                case WARMING -> warming++;
                case COMPACTING -> compacting++;
                case TOMBSTONED -> tombstoned++;
                case PURGED -> purged++;
                default -> { }
            }
            totalEntryCount += m.getEntryCount();
        }

        return new SegmentCatalog.CatalogMetadata(total, active, indexing, warming, compacting,
                                                  tombstoned, purged, totalEntryCount);
    }

    /**
     * Gets the total number of entries across all segments.
     *
     * @return Total entry count
     */
    long totalEntryCount() {
        return registry.values().stream()
            .mapToLong(SegmentCatalog.SegmentMetadata::getEntryCount)
            .sum();
    }

    // ==================== Write-log offsets ====================

    /**
     * Gets the last integrated offset for a segment.
     *
     * @param segmentId Unique segment identifier
     * @return Last integrated offset, or 0 if segment not found
     */
    long lastIntegratedOffset(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = registry.get(segmentId);
        return metadata != null ? metadata.getLastIntegratedOffset() : 0L;
    }

    /**
     * Advances the last integrated offset for a segment.
     *
     * @param segmentId Unique segment identifier
     * @param offset The new last integrated offset (must be &ge; 0)
     * @return true if the offset was updated, false if segment not found or not greater
     * @throws IllegalArgumentException if offset is negative
     */
    boolean setLastIntegratedOffset(long segmentId, long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative: " + offset);
        }
        SegmentCatalog.SegmentMetadata metadata = registry.get(segmentId);
        if (metadata == null) {
            return false;
        }
        return metadata.compareAndSetLastIntegratedOffset(offset);
    }

    /**
     * Gets the write log head offset for a segment.
     *
     * @param segmentId Unique segment identifier
     * @return Write log head offset, or 0 if segment not found
     */
    long writeLogHead(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = registry.get(segmentId);
        return metadata != null ? metadata.getWriteLogHead() : 0L;
    }

    /**
     * Advances the write log head offset for a segment.
     *
     * @param segmentId Unique segment identifier
     * @param offset The new write log head offset (must be &ge; 0)
     * @return true if the offset was updated, false if segment not found or not greater
     * @throws IllegalArgumentException if offset is negative
     */
    boolean setWriteLogHead(long segmentId, long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative: " + offset);
        }
        SegmentCatalog.SegmentMetadata metadata = registry.get(segmentId);
        if (metadata == null) {
            return false;
        }
        return metadata.compareAndSetWriteLogHead(offset);
    }

    /**
     * Gets the number of write-log bytes a segment's index has not yet absorbed.
     *
     * <p>Clamped at zero. The head and the checkpoint are advanced independently and read
     * here without a lock, so an interleaving can show a checkpoint past the head it was
     * taken against; a negative tail size would then be reported as an enormous one by any
     * caller treating it as unsigned.</p>
     *
     * @param segmentId Unique segment identifier
     * @return Tail scan size in bytes, or 0 if segment not found
     */
    long tailScanSize(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = registry.get(segmentId);
        if (metadata == null) {
            return 0L;
        }
        long head = metadata.getWriteLogHead();
        long lastIntegrated = metadata.getLastIntegratedOffset();
        // Clamp to non-negative to handle edge cases
        return Math.max(0L, head - lastIntegrated);
    }

    /**
     * Checks if a segment has un-integrated entries in its write log tail.
     *
     * @param segmentId Unique segment identifier
     * @return true if there are un-integrated entries, false otherwise
     */
    boolean hasTailEntries(long segmentId) {
        return tailScanSize(segmentId) > 0;
    }
}
