package io.excalibur.store;

import io.excalibur.store.catalog.CatalogFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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

    private static final Logger logger = LoggerFactory.getLogger(SegmentCatalog.class);

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
    private final Map<Path, java.util.List<Long>> volumeToSegments = new ConcurrentHashMap<>();

    /**
     * Global segment ID generator.
     */
    private final AtomicLong segmentIdGenerator = new AtomicLong(0);

    /**
     * Catalog metadata for the entire keyspace.
     */
    private final CatalogMetadata metadata = new CatalogMetadata();

    /**
     * Persistent catalog file for durable storage.
     * May be null if persistence is not enabled (in-memory only mode).
     */
    private final CatalogFile catalogFile;

    // ==================== Constructors ====================

    /**
     * Creates a new empty catalog (in-memory only, no persistence).
     *
     * <p><strong>Warning:</strong> Using this constructor means all catalog
     * metadata will be lost on restart. For persistent catalogs, use
     * {@link #SegmentCatalog(Path)} instead.</p>
     */
    public SegmentCatalog() {
        this.catalogFile = null;
    }

    /**
     * Creates a new catalog with persistent storage.
     *
     * <p>The catalog file is created if it doesn't exist, or opened if it does.
     * Call {@link #recover()} after construction to load persisted metadata.</p>
     *
     * @param catalogPath Path to the catalog file
     * @throws UncheckedIOException if the catalog file cannot be created or opened
     */
    public SegmentCatalog(Path catalogPath) {
        try {
            this.catalogFile = new CatalogFile(catalogPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open catalog file: " + catalogPath, e);
        }
    }

    /**
     * Creates a new catalog with persistent storage.
     *
     * <p>The catalog file is created if it doesn't exist, or opened if it does.
     * Call {@link #recover()} after construction to load persisted metadata.</p>
     *
     * @param catalogPath Path to the catalog file
     * @param arena Arena for memory allocation (currently unused, reserved for future)
     * @throws UncheckedIOException if the catalog file cannot be created or opened
     */
    public SegmentCatalog(Path catalogPath, Arena arena) {
        try {
            this.catalogFile = new CatalogFile(catalogPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open catalog file: " + catalogPath, e);
        }
    }

    // ==================== Public API - Segment Operations ====================

    /**
     * Updates the metadata for a segment.
     *
     * <p>This is the primary method for updating segment metadata. It atomically
     * updates all fields for a given segment ID.</p>
     *
     * <p><strong>Important:</strong> This method now synchronizes both the entry state
     * AND the lifecycle state. The lifecycle state is derived from the entry state
     * to ensure consistency.</p>
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

        // Synchronize lifecycle state with entry state for consistency
        syncLifecycleState(metadata, entryState);

        // Track catalog statistics
        if (entryState == ENTRY_STATE_ACTIVE) {
            metadata.setLastActiveTime(System.currentTimeMillis());
            metadata.incrementActiveSegments();
        } else if (entryState == ENTRY_STATE_DELETED) {
            metadata.incrementDeletedSegments();
        }

        // Persist to disk
        persistSegment(metadata);
    }

    /**
     * Synchronizes the lifecycle state with the entry state.
     *
     * <p>This ensures the lifecycle state enum stays in sync with the entry state integer,
     * maintaining backward compatibility while properly utilizing the lifecycle state machine.</p>
     *
     * @param metadata The segment metadata to update
     * @param entryState The entry state to sync from
     */
    private void syncLifecycleState(SegmentMetadata metadata, int entryState) {
        LifecycleState targetLifecycleState = entryStateToLifecycleState(entryState);
        if (targetLifecycleState != null && metadata.getLifecycleState() != targetLifecycleState) {
            try {
                // Use internal setter to bypass transition validation for sync operations
                metadata.setLifecycleStateInternal(targetLifecycleState);
            } catch (Exception e) {
                // Log but don't fail the update if transition is invalid
                // This allows the entry state to be updated even if lifecycle
                // state can't transition
            }
        }
    }

    /**
     * Converts an entry state constant to the corresponding lifecycle state.
     *
     * @param entryState The entry state
     * @return The corresponding lifecycle state, or null if no mapping exists
     */
    private LifecycleState entryStateToLifecycleState(int entryState) {
        switch (entryState) {
            case ENTRY_STATE_NONE: return LifecycleState.BOOTSTRAPPING;
            case ENTRY_STATE_INDEXING: return LifecycleState.INDEXING;
            case ENTRY_STATE_WARMING: return LifecycleState.WARMING;
            case ENTRY_STATE_ACTIVE: return LifecycleState.ACTIVE;
            case ENTRY_STATE_COMPACTING: return LifecycleState.COMPACTING;
            case ENTRY_STATE_DELETED: return LifecycleState.TOMBSTONED;
            default: return null;
        }
    }

    /**
     * Updates entry count for a segment without changing state.
     *
     * @param segmentId Unique segment identifier
     * @param deltaChange Change in entry count (can be negative for deletions)
     */
    public void updateEntryCount(long segmentId, int deltaChange) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata != null) {
            metadata.addEntryCount(deltaChange);
        }
    }

    /**
     * Records that an entry was tombstoned in a segment.
     *
     * <p>The tombstone count is what drives compaction scheduling, so it must be
     * recorded on the delete path rather than inferred from a timer.</p>
     *
     * @param segmentId Unique segment identifier
     */
    public void recordTombstone(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata != null) {
            metadata.addTombstoneCount(1);
        }
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

        // A caller-supplied id must never be handed out again by create().
        reserveSegmentId(segmentId);

        metadata.setVolumePath(volumePath);
        metadata.setCreationTime(System.currentTimeMillis());

        // Only a freshly created entry is moved into INDEXING. Re-registering a segment
        // whose recovered state is already ACTIVE (or later) must not be rejected -
        // ACTIVE -> INDEXING is not a legal transition, and throwing here would abort
        // startup or leave a split half-applied.
        if (metadata.getLifecycleState() == LifecycleState.BOOTSTRAPPING) {
            metadata.transitionToIndexing();
        }

        indexByVolume(volumePath, segmentId);

        // Persist to disk
        persistSegment(metadata);

        return metadata;
    }

    /**
     * Registers or re-points a segment file without forcing a lifecycle transition.
     *
     * <p>Used when a segment's backing file is replaced in place - split publication
     * repoints the parent's index at the left child, compaction repoints an index at
     * the compacted file. Both can happen while the segment is ACTIVE, and
     * {@code ACTIVE -> INDEXING} is not a legal transition, so {@link #add(long, Path)}
     * cannot be used for them.</p>
     *
     * @param segmentId  The segment index whose file changed
     * @param volumePath The new segment file path
     * @return The metadata for the segment
     */
    public SegmentMetadata registerFile(long segmentId, Path volumePath) {
        SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k ->
            new SegmentMetadata(segmentId));

        reserveSegmentId(segmentId);

        Path previousPath = metadata.getVolumePath();
        if (previousPath != null && !previousPath.equals(volumePath)) {
            java.util.List<Long> ids = volumeToSegments.get(previousPath);
            if (ids != null) {
                synchronized (ids) {
                    ids.remove(Long.valueOf(segmentId));
                }
            }
        }

        metadata.setVolumePath(volumePath);
        if (metadata.getCreationTime() == 0) {
            metadata.setCreationTime(System.currentTimeMillis());
        }

        indexByVolume(volumePath, segmentId);
        persistSegment(metadata);

        return metadata;
    }

    /**
     * Records a segment id in the volume index exactly once.
     *
     * @param volumePath The segment file path
     * @param segmentId  The segment id
     */
    private void indexByVolume(Path volumePath, long segmentId) {
        java.util.List<Long> ids = volumeToSegments.computeIfAbsent(
            volumePath, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        synchronized (ids) {
            if (!ids.contains(segmentId)) {
                ids.add(segmentId);
            }
        }
    }

    /**
     * Ensures the id generator can never hand out an id that is already in use.
     *
     * @param segmentId The id that has just been taken
     */
    private void reserveSegmentId(long segmentId) {
        segmentIdGenerator.accumulateAndGet(segmentId, Math::max);
    }

    /**
     * Creates a new segment in BOOTSTRAPPING state for a specific source.
     *
     * <p>This method is used by the reindexer to create new segments for rolling
     * replacement. The segment starts in BOOTSTRAPPING state and is invisible
     * to queries until it transitions to INDEXING state.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>Segment ID is generated atomically and is unique</li>
     *   <li>Segment starts in BOOTSTRAPPING state (invisible to queries)</li>
     *   <li>Creation time is set to current time</li>
     *   <li>Source ID is associated for rolling replacement tracking</li>
     * </ul>
     *
     * @param sourceId The source ID this segment belongs to
     * @param volumePath Path to the segment file
     * @return The created segment metadata with BOOTSTRAPPING state
     * @throws NullPointerException if sourceId or volumePath is null
     */
    public SegmentMetadata create(String sourceId, Path volumePath) {
        if (sourceId == null) {
            throw new NullPointerException("sourceId cannot be null");
        }
        if (volumePath == null) {
            throw new NullPointerException("volumePath cannot be null");
        }

        // Generate a unique segment ID. The generator is also advanced by add() and
        // recover(), but a concurrent add() can still take the next id, so keep
        // drawing until an unused key is claimed rather than overwriting a live
        // routing segment's metadata with a blind put().
        long segmentId;
        SegmentMetadata metadata;
        while (true) {
            segmentId = segmentIdGenerator.incrementAndGet();
            metadata = new SegmentMetadata(segmentId);
            if (segments.putIfAbsent(segmentId, metadata) == null) {
                break;
            }
        }

        metadata.setVolumePath(volumePath);
        metadata.setSourceId(sourceId);
        metadata.setCreationTime(System.currentTimeMillis());
        // Segment is in BOOTSTRAPPING state by default - invisible to queries
        // Caller should call beginIndexing() to make it queryable

        indexByVolume(volumePath, segmentId);

        // Persist to disk
        persistSegment(metadata);

        logger.info("Created segment {} in BOOTSTRAPPING state for source {} at {}",
            segmentId, sourceId, volumePath);

        return metadata;
    }

    /**
     * Gets all segments associated with a specific source.
     *
     * <p>This method is used by the reindexer to find old segments that should
     * be tombstoned after a new segment for the same source becomes ACTIVE.</p>
     *
     * @param sourceId The source ID to filter by
     * @return List of segment metadata for segments belonging to the source
     */
    public java.util.List<SegmentMetadata> getSegmentsBySource(String sourceId) {
        if (sourceId == null) {
            return java.util.Collections.emptyList();
        }

        return segments.values().stream()
            .filter(m -> sourceId.equals(m.getSourceId()))
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Gets all ACTIVE segments associated with a specific source.
     *
     * <p>This method is used by the reindexer to find old active segments that
     * should be tombstoned after a new segment for the same source becomes ACTIVE.</p>
     *
     * @param sourceId The source ID to filter by
     * @return List of segment IDs for ACTIVE segments belonging to the source
     */
    public java.util.List<Long> getActiveSegmentsBySource(String sourceId) {
        if (sourceId == null) {
            return java.util.Collections.emptyList();
        }

        return segments.values().stream()
            .filter(m -> sourceId.equals(m.getSourceId()))
            .filter(m -> m.getLifecycleState() == LifecycleState.ACTIVE)
            .map(SegmentMetadata::getSegmentId)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Removes a segment from the catalog.
     *
     * <p>This is typically called when a segment is purged or tombstoned.
     * The segment remains in memory until explicitly removed.</p>
     *
     * <p><strong>CRITICAL:</strong> This method transitions the segment to PURGED state.
     * The segment MUST be in TOMBSTONED state before calling this method,
     * otherwise an IllegalStateException will be thrown.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if segment is not in TOMBSTONED state
     */
    public void remove(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            return; // Already removed
        }

        // Validate state before removal - segment must be TOMBSTONED
        if (metadata.getLifecycleState() != LifecycleState.TOMBSTONED) {
            throw new IllegalStateException(
                "Cannot remove segment " + segmentId + " in state " + metadata.getLifecycleState() +
                ". Segment must be in TOMBSTONED state before removal.");
        }

        // Transition to PURGED first (validates TOMBSTONED -> PURGED transition)
        metadata.transitionToPurged();

        // Persist the PURGED state before removing from memory
        persistSegment(metadata);

        // Now remove from collections
        segments.remove(segmentId);

        if (metadata.getVolumePath() != null) {
            java.util.List<Long> segmentIds = volumeToSegments.get(metadata.getVolumePath());
            if (segmentIds != null) {
                synchronized (segmentIds) {
                    segmentIds.remove(Long.valueOf(segmentId));
                }
                if (segmentIds.isEmpty()) {
                    volumeToSegments.remove(metadata.getVolumePath());
                }
            }
        }
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

    // ==================== Lifecycle Transition Methods ====================

    /**
     * Completes the indexing phase for a segment and transitions it to WARMING.
     *
     * <p>This method should be called when all entries for a batch have been
     * written to the segment and the Cuckoo filter and LSH have been populated.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in INDEXING state
     * @throws IllegalArgumentException if segment not found
     */
    public void completeIndexing(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        metadata.transitionToWarming();
        persistSegment(metadata);
    }

    /**
     * Completes the warming phase for a segment and transitions it to ACTIVE.
     *
     * <p>This method should be called when HNSW edge optimization and PQ
     * codebook training are complete.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in WARMING state
     * @throws IllegalArgumentException if segment not found
     */
    public void completeWarmup(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        metadata.transitionToActive();
        persistSegment(metadata);
    }

    /**
     * Begins compaction for a segment and transitions it to COMPACTING.
     *
     * <p>This method should be called when a compaction worker starts
     * cleaning up tombstones in the segment.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in ACTIVE state
     * @throws IllegalArgumentException if segment not found
     */
    public void beginCompaction(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        metadata.transitionToCompacting();
        persistSegment(metadata);
    }

    /**
     * Completes compaction for a segment and transitions it back to ACTIVE.
     *
     * <p>This method should be called when a compaction worker finishes
     * cleaning up tombstones in the segment.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    public void completeCompaction(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        // Transition from COMPACTING back to ACTIVE
        metadata.setLifecycleState(LifecycleState.ACTIVE);
        metadata.setEntryState(ENTRY_STATE_ACTIVE);
        metadata.setLastCompactionTime(System.currentTimeMillis());
        // Compaction is what removes tombstoned records, so the count starts over.
        metadata.clearTombstoneCount();
        persistSegment(metadata);
    }

    /**
     * Updates the path for a segment after compaction.
     *
     * <p>This method should be called when a compaction worker creates
     * a new segment file to replace the old one. The path is updated
     * to point to the new segment file.</p>
     *
     * @param segmentId Unique segment identifier
     * @param newPath The new path to the segment file
     * @throws IllegalArgumentException if segment not found or path is null
     */
    public void updatePath(long segmentId, Path newPath) {
        if (newPath == null) {
            throw new IllegalArgumentException("New path cannot be null");
        }
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        metadata.setVolumePath(newPath);
        persistSegment(metadata);
    }

    /**
     * Marks a segment as TOMBSTONED, making it invisible to new queries.
     *
     * <p>This method should be called when a segment is scheduled for deletion.
     * The segment remains on disk until epoch-based reclamation completes.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in ACTIVE or COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    public void tombstone(long segmentId) {
        tombstone(segmentId, -1);
    }

    /**
     * Marks a segment as TOMBSTONED with visibility epoch tracking.
     *
     * <p>This method should be called when a segment is scheduled for deletion.
     * The segment remains on disk until epoch-based reclamation completes.
     * The visibility epoch is recorded to enable epoch-based visibility tracking.</p>
     *
     * @param segmentId Unique segment identifier
     * @param visibilityEpoch The current visibility epoch from the StorageEngine
     * @throws IllegalStateException if the segment is not in ACTIVE or COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    public void tombstone(long segmentId, long visibilityEpoch) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        if (visibilityEpoch >= 0) {
            metadata.transitionToTombstoned(visibilityEpoch);
        } else {
            metadata.transitionToTombstoned();
        }
        persistSegment(metadata);
    }

    /**
     * Marks a segment as ready for indexing by transitioning from BOOTSTRAPPING to INDEXING.
     *
     * <p>This is typically called automatically by {@link #add(long, Path)}.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in BOOTSTRAPPING state
     * @throws IllegalArgumentException if segment not found
     */
    public void beginIndexing(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        metadata.transitionToIndexing();
        persistSegment(metadata);
    }

    /**
     * Gets all segments in a specific lifecycle state.
     *
     * @param state The lifecycle state to filter by
     * @return List of segment IDs in the specified state
     */
    public java.util.List<Long> getSegmentsByState(LifecycleState state) {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState() == state)
            .map(SegmentMetadata::getSegmentId)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Gets all segments that are queryable (INDEXING, WARMING, ACTIVE, COMPACTING).
     *
     * @return List of queryable segment IDs
     */
    public java.util.List<Long> getQueryableSegments() {
        return segments.values().stream()
            .filter(m -> m.getLifecycleState().isQueryable())
            .map(SegmentMetadata::getSegmentId)
            .collect(java.util.stream.Collectors.toList());
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
        segmentIdGenerator.set(0);
    }

    // ==================== Persistence Methods ====================

    /**
     * Recovers persisted catalog metadata from disk.
     *
     * <p>This method loads all segment metadata from the catalog file and
     * populates the in-memory catalog. It should be called after construction
     * when using persistent storage.</p>
     *
     * <p>If persistence is not enabled (in-memory only mode), this method
     * does nothing.</p>
     *
     * <p>Records that fail CRC validation are skipped, providing graceful
     * recovery from partial writes or corruption.</p>
     *
     * @throws UncheckedIOException if the catalog file cannot be read
     */
    public void recover() {
        if (catalogFile == null) {
            return; // No persistence enabled
        }

        try {
            List<SegmentMetadata> persisted = catalogFile.readAll();

            for (SegmentMetadata segMetadata : persisted) {
                // Skip PURGED segments - they're fully deleted
                if (segMetadata.getLifecycleState() == LifecycleState.PURGED) {
                    continue;
                }

                // Add to in-memory structures
                segments.put(segMetadata.getSegmentId(), segMetadata);

                if (segMetadata.getVolumePath() != null) {
                    volumeToSegments.computeIfAbsent(segMetadata.getVolumePath(), k -> new java.util.ArrayList<>())
                        .add(segMetadata.getSegmentId());
                }

                // Update ID generator to avoid conflicts
                segmentIdGenerator.accumulateAndGet(segMetadata.getSegmentId(), Math::max);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to recover catalog from: " + catalogFile.getPath(), e);
        }
    }

    /**
     * Flushes all pending changes to the catalog file.
     *
     * <p>This forces all metadata changes to be written to disk, ensuring
     * durability. Call this before shutting down to ensure no data loss.</p>
     *
     * <p>If persistence is not enabled (in-memory only mode), this method
     * does nothing.</p>
     *
     * @throws UncheckedIOException if the flush fails
     */
    public void flush() {
        if (catalogFile == null) {
            return; // No persistence enabled
        }

        try {
            catalogFile.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to flush catalog file: " + catalogFile.getPath(), e);
        }
    }

    /**
     * Closes the catalog file and releases resources.
     *
     * <p>This flushes any pending changes and closes the underlying file.
     * After calling this method, the catalog is still usable in-memory but
     * persistence operations will fail.</p>
     *
     * <p>If persistence is not enabled (in-memory only mode), this method
     * does nothing.</p>
     */
    public void close() {
        if (catalogFile != null) {
            try {
                catalogFile.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
    }

    /**
     * Checks if persistence is enabled for this catalog.
     *
     * @return true if the catalog has a persistent backing store
     */
    public boolean isPersistent() {
        return catalogFile != null;
    }

    /**
     * Persists a single segment's metadata to disk.
     *
     * <p>This is an internal method called after in-memory updates to ensure
     * the change is durable. If persistence is not enabled, this method does
     * nothing.</p>
     *
     * @param metadata The metadata to persist
     */
    private void persistSegment(SegmentMetadata metadata) {
        if (catalogFile == null || metadata == null) {
            return;
        }

        try {
            catalogFile.writeSegment(metadata);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist segment metadata: " + metadata.getSegmentId(), e);
        }
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
     * <p>Recalculates all counts from current segment states to ensure accuracy.
     * Returns an immutable snapshot that will not be affected by subsequent updates.</p>
     *
     * @return Immutable CatalogMetadata snapshot
     */
    public CatalogMetadata getCatalogMetadata() {
        return computeCatalogMetadataSnapshot();
    }

    /**
     * Computes and returns an immutable snapshot of catalog metadata.
     *
     * <p>This method iterates through all segments to compute accurate counts,
     * ensuring no stale data. The returned snapshot is immutable and safe to
     * cache by callers.</p>
     *
     * <p>Domain correctness guarantees:</p>
     * <ul>
     *   <li>All counts are computed from current segment states</li>
     *   <li>No cached/stale values are returned</li>
     *   <li>The snapshot is immutable after creation</li>
     * </ul>
     *
     * @return Immutable CatalogMetadata snapshot
     */
    private CatalogMetadata computeCatalogMetadataSnapshot() {
        long total = segments.size();
        long active = 0;
        long indexing = 0;
        long warming = 0;
        long compacting = 0;
        long tombstoned = 0;
        long purged = 0;
        long totalEntryCount = 0;

        for (SegmentMetadata m : segments.values()) {
            LifecycleState state = m.getLifecycleState();
            switch (state) {
                case ACTIVE -> active++;
                case INDEXING -> indexing++;
                case WARMING -> warming++;
                case COMPACTING -> compacting++;
                case TOMBSTONED -> tombstoned++;
                case PURGED -> purged++;
                default -> {}
            }
            totalEntryCount += m.getEntryCount();
        }

        return new CatalogMetadata(total, active, indexing, warming, compacting,
                                   tombstoned, purged, totalEntryCount);
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
     * <p>Coverage is computed as the ratio of queryable segments to total non-purged segments.
     * The value is clamped to [0.0, 1.0] to ensure domain correctness.</p>
     *
     * @return Coverage ratio (0.0 to 1.0)
     */
    public double getEstimatedCoverage() {
        CatalogMetadata snapshot = getCatalogMetadata();
        long totalNonPurged = snapshot.getTotalSegments() - snapshot.getPurgedSegments();

        if (totalNonPurged <= 0) {
            return 0.0;
        }

        long queryableSegments = snapshot.getActiveSegments() +
                                 snapshot.getIndexingSegments() +
                                 snapshot.getWarmSegments() +
                                 snapshot.getCompactingSegments();

        double rawCoverage = (double) queryableSegments / totalNonPurged;
        return Math.max(0.0, Math.min(1.0, rawCoverage));
    }

    // ==================== Offset Tracking Methods ====================

    /**
     * Gets the last integrated offset for a segment.
     *
     * <p>The last integrated offset is the write log offset up to which all entries
     * have been linked into the HNSW graph. Entries beyond this offset exist in
     * the write log but are not yet in the graph.</p>
     *
     * <p><strong>Domain Constraints:</strong></p>
     * <ul>
     *   <li>Value is always non-negative (≥ 0)</li>
     *   <li>Value is monotonically increasing</li>
     *   <li>Value ≤ writeLogHead</li>
     * </ul>
     *
     * @param segmentId Unique segment identifier
     * @return Last integrated offset, or 0 if segment not found
     */
    public long getLastIntegratedOffset(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        return metadata != null ? metadata.getLastIntegratedOffset() : 0L;
    }

    /**
     * Sets the last integrated offset for a segment.
     *
     * <p>This method should be called by the HNSW integration worker after
     * successfully linking entries into the graph.</p>
     *
     * <p><strong>Thread Safety:</strong> This method delegates to
     * {@link SegmentMetadata#compareAndSetLastIntegratedOffset(long)} which
     * ensures atomic monotonic updates.</p>
     *
     * @param segmentId Unique segment identifier
     * @param offset The new last integrated offset (must be ≥ 0)
     * @return true if the offset was updated, false if segment not found or offset not greater
     */
    public boolean setLastIntegratedOffset(long segmentId, long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative: " + offset);
        }
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            return false;
        }
        return metadata.compareAndSetLastIntegratedOffset(offset);
    }

    /**
     * Gets the write log head offset for a segment.
     *
     * <p>The write log head is the offset of the next byte to be written.
     * All entries with offset < writeLogHead have been persisted to the write log.</p>
     *
     * <p><strong>Domain Constraints:</strong></p>
     * <ul>
     *   <li>Value is always non-negative (≥ 0)</li>
     *   <li>Value is monotonically increasing</li>
     *   <li>Value ≥ lastIntegratedOffset</li>
     * </ul>
     *
     * @param segmentId Unique segment identifier
     * @return Write log head offset, or 0 if segment not found
     */
    public long getWriteLogHead(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        return metadata != null ? metadata.getWriteLogHead() : 0L;
    }

    /**
     * Sets the write log head offset for a segment.
     *
     * <p>This method should be called by the storage engine after successfully
     * appending entries to the write log.</p>
     *
     * <p><strong>Thread Safety:</strong> This method uses atomic CAS operations
     * to ensure monotonic updates.</p>
     *
     * @param segmentId Unique segment identifier
     * @param offset The new write log head offset (must be ≥ 0)
     * @return true if the offset was updated, false if segment not found or offset not greater
     */
    public boolean setWriteLogHead(long segmentId, long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative: " + offset);
        }
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            return false;
        }
        return metadata.compareAndSetWriteLogHead(offset);
    }

    /**
     * Gets the tail scan size for a segment.
     *
     * <p>The tail scan size is the number of bytes that need to be scanned
     * in the write log for entries not yet integrated into the HNSW graph.</p>
     *
     * <p><strong>Domain Constraints:</strong></p>
     * <ul>
     *   <li>Value is always non-negative (≥ 0)</li>
     *   <li>Value = writeLogHead - lastIntegratedOffset</li>
     * </ul>
     *
     * @param segmentId Unique segment identifier
     * @return Tail scan size in bytes, or 0 if segment not found
     */
    public long getTailScanSize(long segmentId) {
        SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            return 0L;
        }
        long writeLogHead = metadata.getWriteLogHead();
        long lastIntegrated = metadata.getLastIntegratedOffset();
        // Clamp to non-negative to handle edge cases
        return Math.max(0L, writeLogHead - lastIntegrated);
    }

    /**
     * Checks if a segment has un-integrated entries in its write log tail.
     *
     * <p>This is used by the query pipeline to determine if a tail scan
     * is needed for a segment.</p>
     *
     * @param segmentId Unique segment identifier
     * @return true if there are un-integrated entries, false otherwise
     */
    public boolean hasTailEntries(long segmentId) {
        return getTailScanSize(segmentId) > 0;
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
        private volatile Path volumePath;

        /**
         * Entry count. Atomic because every put and delete adjusts it by a delta from
         * whichever ingest thread performed the write.
         */
        private final java.util.concurrent.atomic.AtomicInteger entryCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

        private volatile int entryState = ENTRY_STATE_NONE;

        /**
         * Number of entries tombstoned in this segment since the last compaction.
         *
         * <p>Atomic for the same reason as {@code entryCount}: it is adjusted from
         * whichever thread performed the delete. Reset to zero when compaction
         * completes, since compaction is what removes tombstoned records.</p>
         */
        private final java.util.concurrent.atomic.AtomicInteger tombstoneCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

        /**
         * Lifecycle state. Volatile because query threads read it lock-free while
         * background workers transition it; all transitions go through the
         * {@code stateLock} monitor so validate-and-assign is atomic.
         */
        private volatile LifecycleState lifecycleState = LifecycleState.BOOTSTRAPPING;

        /** Monitor guarding lifecycle transitions. */
        private final Object stateLock = new Object();

        /**
         * Source ID that this segment belongs to.
         * Used for rolling replacement during reindexing to track which segments
         * should be tombstoned when a new segment for the same source becomes ACTIVE.
         * Null for segments not associated with a specific source.
         */
        private volatile String sourceId;

        /**
         * Atomic last integrated offset for thread-safe updates.
         * Uses AtomicLong to ensure monotonic increase and visibility across threads.
         *
         * <p>This is the write log offset up to which all entries have been
         * linked into the HNSW graph. Entries beyond this offset exist in
         * the write log but are not yet in the graph.</p>
         */
        private final AtomicLong lastIntegratedOffset = new AtomicLong(0);

        /**
         * Atomic write log head offset for thread-safe updates.
         * Uses AtomicLong to ensure monotonic increase and visibility across threads.
         *
         * <p>This is the offset of the next byte to be written to the write log.
         * All entries with offset < writeLogHead have been persisted to the write log.</p>
         */
        private final AtomicLong writeLogHead = new AtomicLong(0);

        private long lastCompactionTime = 0;
        private long creationTime = 0;
        private long lastActiveTime = 0;

        /**
         * Epoch at which this segment was created.
         * Set when the segment is first registered.
         * Used for visibility tracking: queries with epoch < creationEpoch
         * cannot see this segment.
         * A value of -1 indicates the creation epoch has not been set.
         */
        private volatile long creationEpoch = -1;

        /**
         * Epoch at which this segment was tombstoned.
         * Set when the segment transitions to TOMBSTONED state.
         * Used for epoch-based reclamation: queries started before this epoch
         * can still see the segment, while queries started after cannot.
         * A value of -1 indicates the segment has not been tombstoned.
         */
        private volatile long tombstoneEpoch = -1;

        /**
         * Reference to the Count-Min Sketch for term frequency estimation.
         * This is an in-memory reference that provides quick access for the query planner.
         * The actual sketch data is managed by the Segment object and may be persisted
         * via memory-mapped files.
         *
         * <p>This reference is set when the sketch is attached to the segment during
         * ingestion. It enables the query pipeline to access the sketch for selectivity
         * estimation without dereferencing through the Segment object.</p>
         *
         * <p>Volatile to ensure visibility across threads after attachment.</p>
         */
        private volatile excalibur.filter.sketch.CountMinSketch termFrequencySketch;

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
         * Gets the source ID that this segment belongs to.
         *
         * <p>Used for rolling replacement during reindexing to track which segments
         * should be tombstoned when a new segment for the same source becomes ACTIVE.</p>
         *
         * @return Source ID, or null if not associated with a specific source
         */
        public String getSourceId() {
            return sourceId;
        }

        /**
         * Sets the source ID that this segment belongs to.
         *
         * <p>Should be set when creating a segment for reindexing operations
         * to enable tracking and tombstoning of old segments for the same source.</p>
         *
         * @param sourceId Source ID (may be null)
         */
        public void setSourceId(String sourceId) {
            this.sourceId = sourceId;
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
            return entryCount.get();
        }

        /**
         * Sets the entry count.
         *
         * @param entryCount Entry count
         */
        public void setEntryCount(int entryCount) {
            this.entryCount.set(entryCount);
        }

        /**
         * Atomically adjusts the entry count by a delta.
         *
         * @param delta The change to apply (may be negative)
         * @return The updated entry count
         */
        public int addEntryCount(int delta) {
            return entryCount.addAndGet(delta);
        }

        /**
         * Gets the number of tombstoned entries since the last compaction.
         *
         * @return Tombstone count
         */
        public int getTombstoneCount() {
            return tombstoneCount.get();
        }

        /**
         * Records tombstoned entries.
         *
         * @param delta Number of entries tombstoned
         * @return The updated tombstone count
         */
        public int addTombstoneCount(int delta) {
            return tombstoneCount.addAndGet(delta);
        }

        /**
         * Clears the tombstone count, e.g. after a compaction removed the records.
         */
        public void clearTombstoneCount() {
            tombstoneCount.set(0);
        }

        /**
         * Returns the fraction of this segment's records that are tombstones.
         *
         * <p>Denominator is live entries plus tombstones, matching
         * {@code Segment.tombstoneCount() / Segment.entryCount()}.</p>
         *
         * @return Tombstone ratio in [0.0, 1.0]; 0.0 when the segment holds no records
         */
        public double tombstoneRatio() {
            int tombstones = tombstoneCount.get();
            long total = (long) entryCount.get() + tombstones;
            return total <= 0 ? 0.0 : (double) tombstones / total;
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
            synchronized (stateLock) {
                LifecycleState.validateTransition(this.lifecycleState, state);
                this.lifecycleState = state;
            }
        }

        /**
         * Sets the lifecycle state without transition validation.
         *
         * <p>This is an internal method used by the catalog to sync lifecycle state
         * with entry state. It bypasses validation to allow state synchronization
         * during recovery or migration scenarios.</p>
         *
         * <p><strong>Warning:</strong> This method should only be used for internal
         * synchronization. External callers should use {@link #setLifecycleState(LifecycleState)}
         * to ensure valid transitions.</p>
         *
         * @param state The new lifecycle state
         */
        void setLifecycleStateInternal(LifecycleState state) {
            synchronized (stateLock) {
                this.lifecycleState = state;
            }
        }

        /**
         * Restores a persisted lifecycle state without transition validation.
         *
         * <p>For catalog recovery only: the state read back from disk is wherever the
         * segment actually was, and replaying it through the transition rules would
         * reject perfectly valid states such as a persisted ACTIVE or TOMBSTONED.</p>
         *
         * @param state The state read from the catalog file (ignored if null)
         */
        public void restoreLifecycleState(LifecycleState state) {
            if (state == null) {
                return;
            }
            synchronized (stateLock) {
                this.lifecycleState = state;
            }
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
         * <p>This method does NOT record the tombstone epoch. Use
         * {@link #transitionToTombstoned(long)} to record the visibility epoch.</p>
         *
         * @throws IllegalStateException if not in ACTIVE or COMPACTING state
         */
        public void transitionToTombstoned() {
            setLifecycleState(LifecycleState.TOMBSTONED);
        }

        /**
         * Transition to the TOMBSTONED state with visibility epoch tracking.
         *
         * <p>This method records the visibility epoch at which the segment was
         * tombstoned. Queries started before this epoch will still see the segment,
         * while queries started at or after this epoch will not.</p>
         *
         * @param visibilityEpoch The current visibility epoch from the StorageEngine
         * @throws IllegalStateException if not in ACTIVE or COMPACTING state
         */
        public void transitionToTombstoned(long visibilityEpoch) {
            setLifecycleState(LifecycleState.TOMBSTONED);
            this.tombstoneEpoch = visibilityEpoch;
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
         * <p><strong>Domain Constraint:</strong> The offset must be non-negative.
         * This method validates the input and throws an exception if negative.</p>
         *
         * <p><strong>Note:</strong> This method directly sets the offset without
         * checking monotonicity. Use {@link #compareAndSetLastIntegratedOffset(long)}
         * for thread-safe updates that guarantee monotonic increase.</p>
         *
         * @param lastIntegratedOffset Last integrated offset (must be >= 0)
         * @throws IllegalArgumentException if lastIntegratedOffset is negative
         */
        public void setLastIntegratedOffset(long lastIntegratedOffset) {
            if (lastIntegratedOffset < 0) {
                throw new IllegalArgumentException(
                    "lastIntegratedOffset must be non-negative, got: " + lastIntegratedOffset);
            }
            this.lastIntegratedOffset.set(lastIntegratedOffset);
        }

        /**
         * Atomically sets the last integrated offset with monotonicity guarantee.
         *
         * <p>This method only updates the offset if the new value is greater than
         * the current value, ensuring the offset never decreases.</p>
         *
         * <p><strong>Domain Constraints:</strong></p>
         * <ul>
         *   <li>Offset must be non-negative (returns false if negative)</li>
         *   <li>Offset must be greater than current value (monotonicity)</li>
         * </ul>
         *
         * <p><strong>Thread Safety:</strong> This operation is atomic and thread-safe.
         * Multiple threads can call this method concurrently without corrupting the offset.</p>
         *
         * @param newOffset The new offset value (must be >= 0)
         * @return true if the offset was updated (new value was greater and non-negative)
         */
        public boolean compareAndSetLastIntegratedOffset(long newOffset) {
            if (newOffset < 0) {
                return false;
            }
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
         * <p><strong>Domain Constraint:</strong> Silently ignores negative values
         * to maintain the non-negative invariant.</p>
         *
         * @param newOffset The new offset value (negative values are ignored)
         */
        public void updateOffsetToMax(long newOffset) {
            if (newOffset < 0) {
                return; // Ignore negative values
            }
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

        // ==================== Write Log Head Methods ====================

        /**
         * Gets the write log head offset.
         *
         * <p><strong>Atomicity:</strong> This method returns a consistent snapshot
         * of the offset value. The returned value is always ≥ lastIntegratedOffset.</p>
         *
         * @return Write log head offset
         */
        public long getWriteLogHead() {
            return writeLogHead.get();
        }

        /**
         * Sets the write log head offset.
         *
         * <p><strong>Note:</strong> This method directly sets the offset without
         * checking monotonicity. Use {@link #compareAndSetWriteLogHead(long)}
         * for thread-safe updates that guarantee monotonic increase.</p>
         *
         * @param offset Write log head offset
         */
        public void setWriteLogHead(long offset) {
            this.writeLogHead.set(offset);
        }

        /**
         * Atomically sets the write log head offset with monotonicity guarantee.
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
        public boolean compareAndSetWriteLogHead(long newOffset) {
            while (true) {
                long current = writeLogHead.get();
                if (newOffset <= current) {
                    return false;
                }
                if (writeLogHead.compareAndSet(current, newOffset)) {
                    return true;
                }
                // CAS failed, retry
            }
        }

        /**
         * Atomically updates the write log head to the maximum of current and new value.
         *
         * <p>This method is useful for batch updates where you want to advance
         * the offset to the maximum of multiple values.</p>
         *
         * @param newOffset The new offset value
         */
        public void updateWriteLogHeadToMax(long newOffset) {
            while (true) {
                long current = writeLogHead.get();
                if (newOffset <= current) {
                    return; // Current is already >= new
                }
                if (writeLogHead.compareAndSet(current, newOffset)) {
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

        /**
         * Gets the epoch at which this segment was created.
         *
         * <p>The creation epoch is the visibility epoch value at the time
         * the segment was first registered. Queries with epoch < creationEpoch
         * cannot see this segment.</p>
         *
         * @return The creation epoch, or -1 if not set
         */
        public long getCreationEpoch() {
            return creationEpoch;
        }

        /**
         * Sets the creation epoch.
         *
         * <p>This should be called when a segment is first registered.
         * The value should be the current visibility epoch from the VisibilityTracker.</p>
         *
         * @param epoch The visibility epoch at which the segment was created
         */
        public void setCreationEpoch(long epoch) {
            this.creationEpoch = epoch;
        }

        /**
         * Gets the epoch at which this segment was tombstoned.
         *
         * <p>The tombstone epoch is the visibility epoch value at the time
         * the segment transitioned to TOMBSTONED state. Queries started
         * before this epoch will still see the segment, while queries
         * started at or after this epoch will not.</p>
         *
         * @return The tombstone epoch, or -1 if the segment has not been tombstoned
         */
        public long getTombstoneEpoch() {
            return tombstoneEpoch;
        }

        /**
         * Sets the tombstone epoch.
         *
         * <p>This should be called when a segment transitions to TOMBSTONED state.
         * The value should be the current visibility epoch from the StorageEngine.</p>
         *
         * @param epoch The visibility epoch at which the segment was tombstoned
         */
        public void setTombstoneEpoch(long epoch) {
            this.tombstoneEpoch = epoch;
        }

        /**
         * Gets the Count-Min Sketch for term frequency estimation.
         *
         * <p>The sketch provides approximate term frequency data for the segment,
         * which is used by the query planner for selectivity estimation.</p>
         *
         * @return The Count-Min Sketch, or null if not attached
         */
        public excalibur.filter.sketch.CountMinSketch getTermFrequencySketch() {
            return termFrequencySketch;
        }

        /**
         * Sets the Count-Min Sketch for term frequency estimation.
         *
         * <p>This method is typically called during ingestion when the sketch
         * is created and attached to the segment.</p>
         *
         * @param sketch The Count-Min Sketch to attach (may be null)
         */
        public void setTermFrequencySketch(excalibur.filter.sketch.CountMinSketch sketch) {
            this.termFrequencySketch = sketch;
        }

        /**
         * Checks if this segment has a term frequency sketch attached.
         *
         * @return true if a sketch is available, false otherwise
         */
        public boolean hasTermFrequencySketch() {
            return termFrequencySketch != null;
        }

        /**
         * Checks if this segment is visible at the given query epoch.
         *
         * <p>A segment is visible at epoch E if:</p>
         * <ul>
         *   <li>creationEpoch <= E (segment existed at query start)</li>
         *   <li>E < tombstoneEpoch (segment was not tombstoned before query started)</li>
         * </ul>
         *
         * <p><strong>Note:</strong> This method only checks epoch visibility, not lifecycle state.
         * Callers should also check {@link #getLifecycleState()}.isQueryable() if needed.</p>
         *
         * @param queryEpoch The epoch at which a query started
         * @return true if the segment is visible to the query
         */
        public boolean isVisibleAtEpoch(long queryEpoch) {
            // If creation epoch is set and query epoch is before creation, not visible
            if (creationEpoch >= 0 && queryEpoch < creationEpoch) {
                return false;
            }
            // If not tombstoned, visible
            if (tombstoneEpoch < 0) {
                return true;
            }
            // If tombstoned after query started, visible to that query
            return tombstoneEpoch > queryEpoch;
        }

        /**
         * Checks if this segment was tombstoned after the given query epoch.
         *
         * <p>This is used for epoch-based visibility: a query that started at
         * queryEpoch should still see segments that were tombstoned after
         * the query started.</p>
         *
         * @param queryEpoch The epoch at which a query started
         * @return true if the segment is visible to the query (tombstoned after query started, or not tombstoned)
         * @deprecated Use {@link #isVisibleAtEpoch(long)} instead for full epoch visibility check
         */
        @Deprecated
        public boolean wasTombstonedAfter(long queryEpoch) {
            // If not tombstoned, always visible
            if (tombstoneEpoch < 0) {
                return true;
            }
            // If tombstoned after query started, visible to that query
            return tombstoneEpoch > queryEpoch;
        }

        @Override
        public String toString() {
            // The counts are atomics, so they have to be read rather than formatted as numbers;
            // %d on the holder throws, and a toString that throws takes out the diagnostic that
            // was reaching for it - in the error paths where it is the only thing to go on. The
            // state reported is the lifecycle one, which is what callers reason about; entryState
            // is an internal flag about a single entry and read as a lifecycle state it is wrong
            // rather than merely unhelpful.
            return String.format(
                "SegmentMetadata{id=%d, state=%s, entries=%d, tombstones=%d, source=%s}",
                segmentId, lifecycleState, entryCount.get(), tombstoneCount.get(), sourceId);
        }
    }

    /**
     * Mutable metadata for the entire catalog.
     *
     * <p>This holds current catalog state. Thread-safe access is managed
     * by the enclosing SegmentCatalog class.</p>
     *
     * <p>Domain correctness: All counts are non-negative.</p>
     */
    public static final class CatalogMetadata {
        private long totalSegments;
        private long activeSegments;
        private long indexingSegments;
        private long warmSegments;
        private long compactingSegments;
        private long tombstonedSegments;
        private long purgedSegments;
        private long totalEntryCount;

        /**
         * Creates a new catalog metadata instance with zero values.
         */
        public CatalogMetadata() {
        }

        /**
         * Creates a catalog metadata snapshot with specified values.
         *
         * @param totalSegments Total number of segments
         * @param activeSegments Number of ACTIVE segments
         * @param indexingSegments Number of INDEXING segments
         * @param warmSegments Number of WARMING segments
         * @param compactingSegments Number of COMPACTING segments
         * @param tombstonedSegments Number of TOMBSTONED segments
         * @param purgedSegments Number of PURGED segments
         * @param totalEntryCount Total entries across all segments
         */
        public CatalogMetadata(long totalSegments, long activeSegments, long indexingSegments,
                               long warmSegments, long compactingSegments, long tombstonedSegments,
                               long purgedSegments, long totalEntryCount) {
            this.totalSegments = totalSegments;
            this.activeSegments = activeSegments;
            this.indexingSegments = indexingSegments;
            this.warmSegments = warmSegments;
            this.compactingSegments = compactingSegments;
            this.tombstonedSegments = tombstonedSegments;
            this.purgedSegments = purgedSegments;
            this.totalEntryCount = totalEntryCount;
        }

        public long getTotalSegments() { return totalSegments; }
        public void setTotalSegments(long totalSegments) { this.totalSegments = totalSegments; }
        public long getActiveSegments() { return activeSegments; }
        public void setActiveSegments(long activeSegments) { this.activeSegments = activeSegments; }
        public long getIndexingSegments() { return indexingSegments; }
        public void setIndexingSegments(long indexingSegments) { this.indexingSegments = indexingSegments; }
        public long getWarmSegments() { return warmSegments; }
        public void setWarmSegments(long warmSegments) { this.warmSegments = warmSegments; }
        public long getCompactingSegments() { return compactingSegments; }
        public void setCompactingSegments(long compactingSegments) { this.compactingSegments = compactingSegments; }
        public long getTombstonedSegments() { return tombstonedSegments; }
        public void setTombstonedSegments(long tombstonedSegments) { this.tombstonedSegments = tombstonedSegments; }
        public long getPurgedSegments() { return purgedSegments; }
        public void setPurgedSegments(long purgedSegments) { this.purgedSegments = purgedSegments; }
        public long getTotalEntryCount() { return totalEntryCount; }
        public void setTotalEntryCount(long totalEntryCount) { this.totalEntryCount = totalEntryCount; }

        @Override
        public String toString() {
            return String.format(
                "CatalogMetadata{total=%d, active=%d, indexing=%d, warming=%d, " +
                "compacting=%d, tombstoned=%d, purged=%d, entries=%d}",
                totalSegments, activeSegments, indexingSegments, warmSegments,
                compactingSegments, tombstonedSegments, purgedSegments, totalEntryCount);
        }
    }
}
