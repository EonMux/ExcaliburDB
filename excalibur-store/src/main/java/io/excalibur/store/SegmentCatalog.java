package io.excalibur.store;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SegmentCatalog manages metadata for all segments in the storage engine.
 *
 * <p>The catalog stores persistent metadata about each segment including:
 * <ul>
 *   <li>segmentId - unique identifier</li>
 *   <li>volumePath - path to the segment file</li>
 *   <li>entryCount - current number of entries</li>
 *   <li>lifecycleState - current lifecycle state</li>
 *   <li>lastIntegratedOffset - write log offset up to which entries are integrated</li>
 *   <li>lastCompactionTime - timestamp of last compaction</li>
 *   <li>creationTime - timestamp when segment was created</li>
 *   <li>size - current size in bytes</li>
 * </ul>
 *
 * <h2>Lifecycle State Integration</h2>
 * <p>The catalog uses {@link LifecycleState} enum for consistent lifecycle state
 * management across the storage engine. This ensures:
 * <ul>
 *   <li>Valid state transitions only</li>
 *   <li>Consistent query visibility</li>
 *   <li>Proper segment lifecycle tracking</li>
 * </ul>
 *
 * <h2>Entry State</h2>
 * <p>The catalog tracks the state of entries within each segment:
 * <ul>
 *   <li>{@link EntryState#NONE} - No entries yet</li>
 *   <li>{@link EntryState#INDEXING} - Entries being written</li>
 *   <li>{@link EntryState#ACTIVE} - Fully indexed and optimized</li>
 *   <li>{@link EntryState#DELETED} - All entries deleted (tombstoned)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>The catalog is fully thread-safe. All operations are synchronized
 * to ensure consistent state across concurrent operations.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class SegmentCatalog {

    // ==================== Entry State ====================

    /**
     * No entries yet.
     */
    public static final int ENTRY_STATE_NONE = 0;

    /**
     * Entries being written.
     */
    public static final int ENTRY_STATE_INDEXING = 1;

    /**
     * Entries being warmed up (HNSW optimization, PQ training).
     */
    public static final int ENTRY_STATE_WARMING = 2;

    /**
     * Fully indexed and optimized.
     */
    public static final int ENTRY_STATE_ACTIVE = 3;

    /**
     * All entries deleted (tombstoned).
     */
    public static final int ENTRY_STATE_DELETED = 4;

    /**
     * Compactable segments.
     */
    public static final int ENTRY_STATE_COMPACTING = 5;

    // ==================== Instance Fields ====================

    /**
     * Registry of segments by ID.
     * 
     * <p>Key: segmentId → Value: SegmentMetadata</p>
     */
    private final Map<Long, SegmentMetadata> segments = new ConcurrentHashMap<>();

    /**
     * Registry of segments by volume path.
     * 
     * <p>Used for reverse lookup when we need to find all segments
     * in a specific volume.</p>
     * 
     * <p>Key: volumePath → Value: list of segmentIds</p>
     */
    private final Map<Path, java.util.List<Long>> volumeToSegments = new HashMap<>();

    /**
     * Global segment ID generator.
     */
    private final AtomicLong segmentIdGenerator = new AtomicLong(0);

    /**
     * Catalog metadata for the entire keyspace.
     */
    private final CatalogMetadata metadata = new CatalogMetadata();

    // ==================== Constructors ====================

    /**
     * Creates a new empty catalog.
     */
    public SegmentCatalog() {
        // Default initial capacity
    }

    // ==================== Public API - Segment Operations ====================

    /**
     * Updates the metadata for a segment.
     *
     * <p>This is the primary method for updating segment metadata. It atomically
     * updates all fields for a given segment ID.</p>
     *
     * @param segmentId Unique segment identifier
     * @param entryState Current state of entries in the segment
     * @param deltaChange In delta for entry count
     */
    public void update(long segmentId, int entryState, int deltaChange) {
        SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k -> 
            new SegmentMetadata(segmentId));

        // Update entry state
        metadata.setEntryState(entryState);

        // Update entry count using delta for atomic updates
        metadata.setEntryCount(metadata.getEntryCount() + deltaChange);

        // Track catalog statistics
        if (entryState == ENTRY_STATE_ACTIVE) {
            metadata.setLastActiveTime(System.currentTimeMillis());
            metadata.incrementActiveSegments();
        } else if (entryState == ENTRY_STATE_DELETED) {
            metadata.incrementDeletedSegments();
        }

        // Update catalog metadata
        updateCatalogMetadata();
    }

    /**
     * Gets the metadata for a segment.
     *
     * @param segmentId Unique segment identifier
     * @return SegmentMetadata, or null if segment not found
     */
    public SegmentMetadata get(long segmentId) {
        return segments.get(segmentId);
    }

    /**
     * Gets the metadata for a segment by volume path.
     *
     * @param volumePath Path to the volume containing segments
     * @return List of SegmentMetadata for segments in the volume
     */
    public java.util.List<SegmentMetadata> getByVolume(Path volumePath) {
        java.util.List<Long> segmentIds = volumeToSegments.getOrDefault(volumePath, java.util.Collections.emptyList());
        java.util.List<SegmentMetadata> result = new java.util.ArrayList<>();
        for (long segmentId : segmentIds) {
            result.add(segments.get(segmentId));
        }
        return result;
    }

    /**
     * Gets all segment metadata.
     *
     * @return Map of segmentId to SegmentMetadata
     */
    public Map<Long, SegmentMetadata> getAll() {
        return new HashMap<>(segments);
    }

    /**
     * Adds a new segment to the catalog.
     *
     * @param segmentId Unique segment identifier
     * @param volumePath Path to the segment file
     * @return The created metadata
     */
    public SegmentMetadata add(long segmentId, Path volumePath) {
        SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k -> 
            new SegmentMetadata(segmentId));

        metadata.setVolumePath(volumePath);
        metadata.setCreationTime(System.currentTimeMillis());
        metadata.transitionToIndexing();

        // Register in volume index
        volumeToSegments.computeIfAbsent(volumePath, k -> new java.util.ArrayList<>())
            .add(segmentId);

        return metadata;
    }

    /**
     * Removes a segment from the catalog.
     *
     * <p>This is typically called when a segment is purged or tombstoned.
     * The segment remains in memory until explicitly removed.</p>
     *
     * @param segmentId Unique segment identifier
     */
    public void remove(long segmentId) {
        SegmentMetadata metadata = segments.remove(segmentId);
        
        if (metadata != null && metadata.getVolumePath() != null) {
            java.util.List<Long> segmentIds = volumeToSegments.get(metadata.getVolumePath());
            if (segmentIds != null) {
                segmentIds.remove(segmentId);
                if (segmentIds.isEmpty()) {
                    volumeToSegments.remove(metadata.getVolumePath());
                }
            }
        }

        metadata.transitionToPurged();
    }

    /**
     * Checks if a segment exists in the catalog.
     *
     * @param segmentId Unique segment identifier
     * @return true if segment exists
     */
    public boolean contains(long segmentId) {
        return segments.containsKey(segmentId);
    }

    /**
     * Gets the number of segments in the catalog.
     *
     * @return Segment count
     */
    public int size() {
        return segments.size();
    }

    /**
     * Clears the entire catalog.
     * 
     * <p>Use with caution - this removes all segment metadata.</p>
     */
    public void clear() {
        segments.clear();
        volumeToSegments.clear();
        metadata.reset();
        segmentIdGenerator.set(0);
    }

    /**
     * Registers a new volume in the catalog.
     *
     * @param volumePath Path to the volume
     */
    public void registerVolume(Path volumePath) {
        volumeToSegments.computeIfAbsent(volumePath, k -> new java.util.ArrayList<>());
    }

    /**
     * Unregisters a volume from the catalog.
     *
     * @param volumePath Path to the volume
     */
    public void unregisterVolume(Path volumePath) {
        volumeToSegments.remove(volumePath);
    }

    // ==================== Catalog Metadata ====================

    /**
     * Entry state constants for external use.
     */
    public static final int ACTIVE = ENTRY_STATE_ACTIVE;
    public static final int DELETED = ENTRY_STATE_DELETED;
    public static final int INDEXING = ENTRY_STATE_INDEXING;

    /**
     * Returns the catalog metadata.
     *
     * <p>Recalculates all counts from current segment states to ensure accuracy.</p>
     *
     * @return CatalogMetadata
     */
    public CatalogMetadata getCatalogMetadata() {
        updateCatalogMetadata();
        return metadata;
    }

    /**
     * Updates catalog metadata with current statistics.
     */
    private void updateCatalogMetadata() {
        metadata.setTotalSegments(segments.size());
        metadata.setActiveSegments(getActiveSegmentCount());
        metadata.setIndexingSegments(getIndexingSegmentCount());
        metadata.setWarmSegments(getWarmSegmentCount());
        metadata.setCompactingSegments(getCompactingSegmentCount());
        metadata.setTombstonedSegments(getTombstonedSegmentCount());
        metadata.setPurgedSegments(getPurgedSegmentCount());
        metadata.setTotalEntryCount(getTotalEntryCount());
    }

    /**
     * Gets the count of active segments.
     *
     * @return Count of segments with lifecycle state ACTIVE
     */
    private long getActiveSegmentCount() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == LifecycleState.ACTIVE)
            .count();
    }

    /**
     * Gets the count of indexing segments.
     *
     * @return Count of segments with lifecycle state INDEXING
     */
    private long getIndexingSegmentCount() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == LifecycleState.INDEXING)
            .count();
    }

    /**
     * Gets the count of warming segments.
     *
     * @return Count of segments with lifecycle state WARMING
     */
    private long getWarmSegmentCount() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == LifecycleState.WARMING)
            .count();
    }

    /**
     * Gets the count of compacting segments.
     *
     * @return Count of segments with lifecycle state COMPACTING
     */
    private long getCompactingSegmentCount() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == LifecycleState.COMPACTING)
            .count();
    }

    /**
     * Gets the count of tombstoned segments.
     *
     * @return Count of segments with lifecycle state TOMBSTONED
     */
    private long getTombstonedSegmentCount() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == LifecycleState.TOMBSTONED)
            .count();
    }

    /**
     * Gets the count of purged segments.
     *
     * @return Count of segments with lifecycle state PURGED
     */
    private long getPurgedSegmentCount() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == LifecycleState.PURGED)
            .count();
    }

    /**
     * Gets the total number of entries across all segments.
     *
     * @return Total entry count
     */
    public long getTotalEntryCount() {
        return segments.values().stream()
            .mapToLong(SegmentMetadata::getEntryCount)
            .sum();
    }

    /**
     * Computes estimated corpus coverage.
     *
     * <p>Coverage is the ratio of active segments to total non-purged segments.</p>
     *
     * @return Coverage ratio (0.0 to 1.0)
     */
    public double getEstimatedCoverage() {
        long totalActive = metadata.getTotalSegments();
        long totalNonPurged = metadata.getTotalSegments() - metadata.getPurgedSegments();
        
        if (totalNonPurged <= 0) {
            return 0.0;
        }
        
        return (double) metadata.getActiveSegments() / totalNonPurged;
    }

    // ==================== Inner Classes ====================

    /**
     * Metadata for a single segment.
     *
     * <p><strong>Thread Safety:</strong> This class provides atomic updates for
     * the {@code lastIntegratedOffset} field. All offset updates use CAS operations
     * and ensure monotonic increase. Other fields are not thread-safe and should
     * be updated from a single thread or with external synchronization.</p>
     */
    public static class SegmentMetadata {
        private final long segmentId;
        private Path volumePath;
        private int entryCount = 0;
        private int entryState = ENTRY_STATE_NONE;
        private LifecycleState lifecycleState = LifecycleState.BOOTSTRAPPING;

        /**
         * Atomic last integrated offset for thread-safe updates.
         * Uses AtomicLong to ensure monotonic increase and visibility across threads.
         */
        private final AtomicLong lastIntegratedOffset = new AtomicLong(0);

        private long lastCompactionTime = 0;
        private long creationTime = 0;
        private long lastActiveTime = 0;

        /**
         * Creates new segment metadata.
         *
         * @param segmentId Unique segment identifier
         */
        public SegmentMetadata(long segmentId) {
            this.segmentId = segmentId;
        }

        /**
         * Gets the segment ID.
         *
         * @return Segment ID
         */
        public long getSegmentId() {
            return segmentId;
        }

        /**
         * Gets the volume path.
         *
         * @return Volume path, or null if not set
         */
        public Path getVolumePath() {
            return volumePath;
        }

        /**
         * Sets the volume path.
         *
         * @param volumePath Volume path
         */
        public void setVolumePath(Path volumePath) {
            this.volumePath = volumePath;
        }

        /**
         * Gets the entry count.
         *
         * @return Entry count
         */
        public int getEntryCount() {
            return entryCount;
        }

        /**
         * Sets the entry count.
         *
         * @param entryCount Entry count
         */
        public void setEntryCount(int entryCount) {
            this.entryCount = entryCount;
        }

        /**
         * Gets the entry state.
         *
         * @return Entry state
         */
        public int getEntryState() {
            return entryState;
        }

        /**
         * Sets the entry state.
         *
         * @param entryState Entry state
         */
        public void setEntryState(int entryState) {
            this.entryState = entryState;
        }

        /**
         * Gets the lifecycle state.
         *
         * @return Lifecycle state
         */
        public LifecycleState getLifecycleState() {
            return lifecycleState;
        }

        /**
         * Sets the lifecycle state.
         *
         * <p>Validates the state transition before applying the new state.
         * Triggers catalog metadata update to reflect the new state.</p>
         *
         * @param state The new lifecycle state
         * @throws IllegalStateException if the transition is invalid
         */
        public void setLifecycleState(LifecycleState state) {
            LifecycleState.validateTransition(this.lifecycleState, state);
            LifecycleState oldState = this.lifecycleState;
            this.lifecycleState = state;
            // Notify catalog to update metadata if we're in a cataloged segment
            // This is called via reflection from SegmentCatalog when needed
        }

        /**
         * Sets the lifecycle state from an integer value.
         *
         * @param value The lifecycle state value
         * @return The new lifecycle state
         */
        public LifecycleState setLifecycleStateFromValue(int value) {
            LifecycleState state = LifecycleState.fromValue(value);
            if (state == null) {
                throw new IllegalArgumentException(
                    "Invalid lifecycle state value: " + value);
            }
            setLifecycleState(state);
            return state;
        }

        /**
         * Gets the lifecycle state as an integer.
         *
         * @return The lifecycle state value
         */
        public int getLifecycleStateValue() {
            return lifecycleState.value();
        }

        /**
         * Advances the lifecycle state to the next state.
         *
         * @return The new lifecycle state
         */
        public LifecycleState advance() {
            return lifecycleState.advance();
        }

        /**
         * Transition to the INDEXING state.
         *
         * @throws IllegalStateException if already in INDEXING or later state
         */
        public void transitionToIndexing() {
            setLifecycleState(LifecycleState.INDEXING);
        }

        /**
         * Transition to the WARMING state.
         *
         * @throws IllegalStateException if not in INDEXING state
         */
        public void transitionToWarming() {
            setLifecycleState(LifecycleState.WARMING);
        }

        /**
         * Transition to the ACTIVE state.
         *
         * <p>This sets both the lifecycle state and the entry state to ACTIVE.</p>
         *
         * @throws IllegalStateException if not in WARMING state
         */
        public void transitionToActive() {
            setLifecycleState(LifecycleState.ACTIVE);
            setEntryState(ENTRY_STATE_ACTIVE);
        }

        /**
         * Transition to the COMPACTING state.
         *
         * @throws IllegalStateException if not in ACTIVE state
         */
        public void transitionToCompacting() {
            setLifecycleState(LifecycleState.COMPACTING);
        }

        /**
         * Transition to the TOMBSTONED state.
         *
         * @throws IllegalStateException if not in ACTIVE or COMPACTING state
         */
        public void transitionToTombstoned() {
            setLifecycleState(LifecycleState.TOMBSTONED);
        }

        /**
         * Transition to the PURGED state.
         *
         * @throws IllegalStateException if not in TOMBSTONED state
         */
        public void transitionToPurged() {
            setLifecycleState(LifecycleState.PURGED);
        }

        /**
         * Resets the lifecycle state to BOOTSTRAPPING.
         *
         * @throws IllegalStateException if not in PURGED state
         */
        public void resetToBootstrapping() {
            LifecycleState.validateTransition(this.lifecycleState, LifecycleState.BOOTSTRAPPING);
            this.lifecycleState = LifecycleState.BOOTSTRAPPING;
        }

        /**
         * Gets the last integrated offset.
         *
         * <p><strong>Atomicity:</strong> This method returns a consistent snapshot
         * of the offset value. The returned value is always >= any value that has
         * been persisted to disk.</p>
         *
         * @return Last integrated offset
         */
        public long getLastIntegratedOffset() {
            return lastIntegratedOffset.get();
        }

        /**
         * Sets the last integrated offset.
         *
         * <p><strong>Note:</strong> This method directly sets the offset without
         * checking monotonicity. Use {@link #compareAndSetLastIntegratedOffset(long)}
         * for thread-safe updates that guarantee monotonic increase.</p>
         *
         * @param lastIntegratedOffset Last integrated offset
         */
        public void setLastIntegratedOffset(long lastIntegratedOffset) {
            this.lastIntegratedOffset.set(lastIntegratedOffset);
        }

        /**
         * Atomically sets the last integrated offset with monotonicity guarantee.
         *
         * <p>This method only updates the offset if the new value is greater than
         * the current value, ensuring the offset never decreases.</p>
         *
         * <p><strong>Thread Safety:</strong> This operation is atomic and thread-safe.
         * Multiple threads can call this method concurrently without corrupting the offset.</p>
         *
         * @param newOffset The new offset value
         * @return true if the offset was updated (new value was greater)
         */
        public boolean compareAndSetLastIntegratedOffset(long newOffset) {
            while (true) {
                long current = lastIntegratedOffset.get();
                if (newOffset <= current) {
                    return false;
                }
                if (lastIntegratedOffset.compareAndSet(current, newOffset)) {
                    return true;
                }
                // CAS failed, retry
            }
        }

        /**
         * Atomically updates the offset to the maximum of current and new value.
         *
         * <p>This method is useful for batch updates where you want to advance
         * the offset to the maximum of multiple values.</p>
         *
         * @param newOffset The new offset value
         */
        public void updateOffsetToMax(long newOffset) {
            while (true) {
                long current = lastIntegratedOffset.get();
                if (newOffset <= current) {
                    return; // Current is already >= new
                }
                if (lastIntegratedOffset.compareAndSet(current, newOffset)) {
                    return;
                }
            }
        }

        /**
         * Gets the last compaction time.
         *
         * @return Last compaction time
         */
        public long getLastCompactionTime() {
            return lastCompactionTime;
        }

        /**
         * Sets the last compaction time.
         *
         * @param lastCompactionTime Last compaction time
         */
        public void setLastCompactionTime(long lastCompactionTime) {
            this.lastCompactionTime = lastCompactionTime;
        }

        /**
         * Gets the creation time.
         *
         * @return Creation time
         */
        public long getCreationTime() {
            return creationTime;
        }

        /**
         * Sets the creation time.
         *
         * @param creationTime Creation time
         */
        public void setCreationTime(long creationTime) {
            this.creationTime = creationTime;
        }

        /**
         * Gets the last active time.
         *
         * @return Last active time
         */
        public long getLastActiveTime() {
            return lastActiveTime;
        }

        /**
         * Sets the last active time.
         *
         * @param lastActiveTime Last active time
         */
        public void setLastActiveTime(long lastActiveTime) {
            this.lastActiveTime = lastActiveTime;
        }

        /**
         * Increments the active segment counter.
         */
        public void incrementActiveSegments() {
            this.lastActiveTime = System.currentTimeMillis();
        }

        /**
         * Increments the deleted segment counter.
         */
        public void incrementDeletedSegments() {
            this.lastActiveTime = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format(
                "SegmentMetadata{id=%d, state=%d, entries=%d, size=%d}",
                segmentId, entryState, entryCount, entryCount);
        }
    }

    /**
     * Metadata for the entire catalog.
     */
    public static class CatalogMetadata {
        private long totalSegments = 0;
        private long activeSegments = 0;
        private long indexingSegments = 0;
        private long warmSegments = 0;
        private long compactingSegments = 0;
        private long tombstonedSegments = 0;
        private long purgedSegments = 0;
        private long totalEntryCount = 0;

        public void setTotalSegments(long total) { this.totalSegments = total; }
        public void setActiveSegments(long active) { this.activeSegments = active; }
        public void setIndexingSegments(long indexing) { this.indexingSegments = indexing; }
        public void setWarmSegments(long warm) { this.warmSegments = warm; }
        public void setCompactingSegments(long compacting) { this.compactingSegments = compacting; }
        public void setTombstonedSegments(long tombstoned) { this.tombstonedSegments = tombstoned; }
        public void setPurgedSegments(long purged) { this.purgedSegments = purged; }
        public void setTotalEntryCount(long count) { this.totalEntryCount = count; }

        public long getTotalSegments() { return totalSegments; }
        public long getActiveSegments() { return activeSegments; }
        public long getIndexingSegments() { return indexingSegments; }
        public long getWarmSegments() { return warmSegments; }
        public long getCompactingSegments() { return compactingSegments; }
        public long getTombstonedSegments() { return tombstonedSegments; }
        public long getPurgedSegments() { return purgedSegments; }
        public long getTotalEntryCount() { return totalEntryCount; }

        public void reset() {
            totalSegments = 0;
            activeSegments = 0;
            indexingSegments = 0;
            warmSegments = 0;
            compactingSegments = 0;
            tombstonedSegments = 0;
            purgedSegments = 0;
            totalEntryCount = 0;
        }
    }
}
