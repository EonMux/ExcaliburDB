package io.excalibur.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-memory half of {@link SegmentCatalog}: which segments exist, where their files
 * are, and what state each one is in.
 *
 * <p>Two indexes over the same records, kept in step. {@code segments} is the primary one,
 * keyed by segment id. {@code volumeToSegments} is the reverse: which segment ids a given
 * file path holds, which is what lets a volume be asked what it contains without walking
 * every record. A registration that updated one and not the other would leave a segment
 * that exists but cannot be found by volume, or a volume claiming a segment that was
 * purged - so every operation that touches one touches the other, here, rather than at
 * each call site.</p>
 *
 * <h2>Registration, and why there are three ways to do it</h2>
 * <ul>
 *   <li>{@link #add(long, Path)} registers a segment under a caller-chosen id and moves a
 *       brand-new record into INDEXING. Re-registering one whose recovered state is already
 *       ACTIVE must not be rejected, so the transition is conditional.</li>
 *   <li>{@link #registerFile(long, Path)} re-points an existing segment at a new file
 *       without touching its state, for split publication and compaction - both of which
 *       happen while the segment is ACTIVE, and {@code ACTIVE -> INDEXING} is not legal.</li>
 *   <li>{@link #create(String, Path)} allocates a fresh id for a rolling reindex and leaves
 *       the segment BOOTSTRAPPING, which is invisible to queries.</li>
 * </ul>
 *
 * <p>Every id that enters the registry is fed back into the generator, so an id supplied by
 * a caller can never be handed out again by {@code create()}.</p>
 *
 * <p>Each mutation is persisted through {@link CatalogPersistence} before it is reported as
 * done, in the same order the in-memory change was made.</p>
 *
 * <p><strong>Thread safety:</strong> fully thread-safe. The two maps are concurrent and the
 * id generator is atomic. The per-volume id lists are copy-on-write, so a reader iterates a
 * snapshot rather than a list a writer may be resizing underneath it, and every change to
 * one is made inside a {@code compute} on {@code volumeToSegments}. That last part is what
 * makes "drop the list once it is empty" safe: the emptiness test and the unlinking happen
 * under the map's own per-key lock, so a registration racing a removal cannot have its id
 * added to a list that is on its way out of the map.</p>
 */
final class CatalogRegistry {

    /**
     * Registry of segments by ID.
     *
     * <p>Key: segmentId &rarr; Value: SegmentMetadata</p>
     */
    private final Map<Long, SegmentCatalog.SegmentMetadata> segments = new ConcurrentHashMap<>();

    /**
     * Registry of segments by volume path.
     *
     * <p>Used for reverse lookup when we need to find all segments in a specific volume.</p>
     *
     * <p>Key: volumePath &rarr; Value: list of segmentIds</p>
     */
    private final ConcurrentHashMap<Path, List<Long>> volumeToSegments = new ConcurrentHashMap<>();

    /** Global segment ID generator. */
    private final AtomicLong segmentIdGenerator = new AtomicLong(0);

    /** Where each change is made durable. */
    private final CatalogPersistence persistence;

    /**
     * Creates a registry that persists every change through the given store.
     *
     * @param persistence The durable store, which may itself be in-memory only
     */
    CatalogRegistry(CatalogPersistence persistence) {
        this.persistence = persistence;
    }

    // ==================== Lookup ====================

    /**
     * Gets the metadata for a segment.
     *
     * @param segmentId Unique segment identifier
     * @return SegmentMetadata, or null if segment not found
     */
    SegmentCatalog.SegmentMetadata get(long segmentId) {
        return segments.get(segmentId);
    }

    /**
     * Gets the metadata for every segment recorded against a volume path.
     *
     * @param volumePath Path to the volume containing segments
     * @return List of SegmentMetadata for segments in the volume
     */
    List<SegmentCatalog.SegmentMetadata> getByVolume(Path volumePath) {
        List<Long> segmentIds = volumeToSegments.getOrDefault(volumePath, Collections.emptyList());
        List<SegmentCatalog.SegmentMetadata> result = new ArrayList<>();
        for (long segmentId : segmentIds) {
            // The two maps are updated one after the other, so a segment can have left
            // `segments` while its id is still in the volume index. It is gone either way;
            // a null in the returned list would only move the fault to the caller.
            SegmentCatalog.SegmentMetadata metadata = segments.get(segmentId);
            if (metadata != null) {
                result.add(metadata);
            }
        }
        return result;
    }

    /**
     * Gets all segment metadata, as a snapshot.
     *
     * @return Map of segmentId to SegmentMetadata
     */
    Map<Long, SegmentCatalog.SegmentMetadata> getAll() {
        return new HashMap<>(segments);
    }

    /**
     * Returns the live record collection, for read-only traversal.
     *
     * @return The values view of the primary index
     */
    Collection<SegmentCatalog.SegmentMetadata> values() {
        return segments.values();
    }

    /**
     * Checks if a segment exists in the registry.
     *
     * @param segmentId Unique segment identifier
     * @return true if segment exists
     */
    boolean contains(long segmentId) {
        return segments.containsKey(segmentId);
    }

    /**
     * Returns the number of segments registered.
     *
     * @return Segment count
     */
    int size() {
        return segments.size();
    }

    // ==================== Registration ====================

    /**
     * Adds a segment under a caller-supplied id, moving a new record into INDEXING.
     *
     * @param segmentId Unique segment identifier
     * @param volumePath Path to the segment file
     * @return The created or existing metadata
     */
    SegmentCatalog.SegmentMetadata add(long segmentId, Path volumePath) {
        SegmentCatalog.SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k ->
            new SegmentCatalog.SegmentMetadata(segmentId));

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
        persistence.persist(metadata);

        return metadata;
    }

    /**
     * Registers or re-points a segment file without forcing a lifecycle transition.
     *
     * @param segmentId  The segment index whose file changed
     * @param volumePath The new segment file path
     * @return The metadata for the segment
     */
    SegmentCatalog.SegmentMetadata registerFile(long segmentId, Path volumePath) {
        SegmentCatalog.SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k ->
            new SegmentCatalog.SegmentMetadata(segmentId));

        reserveSegmentId(segmentId);

        Path previousPath = metadata.getVolumePath();
        if (previousPath != null && !previousPath.equals(volumePath)) {
            unindexByVolume(previousPath, segmentId);
        }

        metadata.setVolumePath(volumePath);
        if (metadata.getCreationTime() == 0) {
            metadata.setCreationTime(System.currentTimeMillis());
        }

        indexByVolume(volumePath, segmentId);
        persistence.persist(metadata);

        return metadata;
    }

    /**
     * Creates a new segment in BOOTSTRAPPING state for a specific source.
     *
     * @param sourceId The source ID this segment belongs to
     * @param volumePath Path to the segment file
     * @return The created segment metadata with BOOTSTRAPPING state
     * @throws NullPointerException if sourceId or volumePath is null
     */
    SegmentCatalog.SegmentMetadata create(String sourceId, Path volumePath) {
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
        SegmentCatalog.SegmentMetadata metadata;
        while (true) {
            segmentId = segmentIdGenerator.incrementAndGet();
            metadata = new SegmentCatalog.SegmentMetadata(segmentId);
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
        persistence.persist(metadata);

        SegmentCatalog.logger.info("Created segment {} in BOOTSTRAPPING state for source {} at {}",
            segmentId, sourceId, volumePath);

        return metadata;
    }

    /**
     * Records a segment id in the volume index exactly once.
     *
     * @param volumePath The segment file path
     * @param segmentId  The segment id
     */
    private void indexByVolume(Path volumePath, long segmentId) {
        volumeToSegments.compute(volumePath, (key, ids) -> {
            List<Long> list = ids == null ? new CopyOnWriteArrayList<>() : ids;
            if (!list.contains(segmentId)) {
                list.add(segmentId);
            }
            return list;
        });
    }

    /**
     * Drops a segment id from a volume's index, and the volume with it once it holds none.
     *
     * <p>Both steps happen inside one {@code compute}, so the list cannot be unlinked from
     * the map while a concurrent {@link #indexByVolume(Path, long)} is adding to it - which
     * is how a freshly registered segment becomes unreachable through its volume.</p>
     *
     * @param volumePath The volume the segment is leaving
     * @param segmentId  The segment id
     */
    private void unindexByVolume(Path volumePath, long segmentId) {
        volumeToSegments.computeIfPresent(volumePath, (key, ids) -> {
            ids.remove(Long.valueOf(segmentId));
            return ids.isEmpty() ? null : ids;
        });
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
     * Registers a new volume.
     *
     * @param volumePath Path to the volume
     */
    void registerVolume(Path volumePath) {
        volumeToSegments.computeIfAbsent(volumePath, k -> new CopyOnWriteArrayList<>());
    }

    /**
     * Unregisters a volume.
     *
     * @param volumePath Path to the volume
     */
    void unregisterVolume(Path volumePath) {
        volumeToSegments.remove(volumePath);
    }

    /**
     * Removes a segment, transitioning it to PURGED first.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if segment is not in TOMBSTONED state
     */
    void remove(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            return; // Already removed
        }

        // Validate state before removal - segment must be TOMBSTONED
        if (metadata.getLifecycleState() != LifecycleState.TOMBSTONED) {
            throw new IllegalStateException(
                "Cannot remove segment " + segmentId + " in state " + metadata.getLifecycleState()
                + ". Segment must be in TOMBSTONED state before removal.");
        }

        // Transition to PURGED first (validates TOMBSTONED -> PURGED transition)
        metadata.transitionToPurged();

        // Persist the PURGED state before removing from memory
        persistence.persist(metadata);

        // Now remove from collections
        segments.remove(segmentId);

        if (metadata.getVolumePath() != null) {
            unindexByVolume(metadata.getVolumePath(), segmentId);
        }
    }

    /**
     * Drops every record and resets the id generator.
     */
    void clear() {
        segments.clear();
        volumeToSegments.clear();
        segmentIdGenerator.set(0);
    }

    /**
     * Populates the registry from records read back off disk.
     *
     * <p>PURGED records are skipped: they describe segments whose disk has already been
     * freed, so recovering them would put a segment back in the catalog that nothing holds.
     * That is also why {@link SegmentCatalog.CatalogMetadata#getLiveSegments()} can treat
     * PURGED as a state the catalog never holds.</p>
     *
     * @param persisted The records read from the catalog file
     */
    void restore(List<SegmentCatalog.SegmentMetadata> persisted) {
        for (SegmentCatalog.SegmentMetadata segMetadata : persisted) {
            // Skip PURGED segments - they're fully deleted
            if (segMetadata.getLifecycleState() == LifecycleState.PURGED) {
                continue;
            }

            // Add to in-memory structures
            segments.put(segMetadata.getSegmentId(), segMetadata);

            if (segMetadata.getVolumePath() != null) {
                indexByVolume(segMetadata.getVolumePath(), segMetadata.getSegmentId());
            }

            // Update ID generator to avoid conflicts
            segmentIdGenerator.accumulateAndGet(segMetadata.getSegmentId(), Math::max);
        }
    }

    // ==================== Mutation ====================

    /**
     * Updates entry state and entry count together, keeping the lifecycle state in step.
     *
     * @param segmentId Unique segment identifier
     * @param entryState Current state of entries in the segment
     * @param deltaChange In delta for entry count
     */
    void update(long segmentId, int entryState, int deltaChange) {
        SegmentCatalog.SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k ->
            new SegmentCatalog.SegmentMetadata(segmentId));

        // Update entry state
        metadata.setEntryState(entryState);

        // Atomically, because two ingest threads updating the same segment through a
        // get-then-set each overwrite the other's increment.
        metadata.addEntryCount(deltaChange);

        // Synchronize lifecycle state with entry state for consistency
        syncLifecycleState(metadata, entryState);

        // Record when the segment was last touched. This is not a statistic: it is the
        // reference the wall-clock reclamation grace period runs from for a tombstone that
        // carries no visibility epoch, so the delete path has to stamp it as much as the
        // active one. See SegmentLifecycleManager#isPastGracePeriod.
        if (entryState == SegmentCatalog.ENTRY_STATE_ACTIVE
            || entryState == SegmentCatalog.ENTRY_STATE_DELETED) {
            metadata.setLastActiveTime(System.currentTimeMillis());
        }

        // Persist to disk
        persistence.persist(metadata);
    }

    /**
     * Synchronizes the lifecycle state with the entry state.
     *
     * <p>Bypasses transition validation, because this is a synchronisation rather than a
     * transition: the entry state is the older of the two representations and may name a
     * position the state machine cannot legally reach from where it is. There is therefore
     * nothing here that can be rejected -
     * {@link SegmentCatalog.SegmentMetadata#setLifecycleStateInternal} consults no transition
     * rule and does no more than store the field under the metadata's state lock - so the
     * entry-state update this is part of cannot be failed by it.</p>
     *
     * <p>An entry state that names no lifecycle state leaves the lifecycle state alone, which
     * is how an unknown value read back from an older record stops short of being an error.</p>
     *
     * @param metadata The segment metadata to update
     * @param entryState The entry state to sync from
     */
    private void syncLifecycleState(SegmentCatalog.SegmentMetadata metadata, int entryState) {
        LifecycleState targetLifecycleState = EntryStateMapping.toLifecycleState(entryState);
        if (targetLifecycleState != null && metadata.getLifecycleState() != targetLifecycleState) {
            metadata.setLifecycleStateInternal(targetLifecycleState);
        }
    }

    /**
     * Adjusts a segment's entry count, doing nothing for a segment that is not registered.
     *
     * @param segmentId Unique segment identifier
     * @param deltaChange Change in entry count (can be negative for deletions)
     */
    void updateEntryCount(long segmentId, int deltaChange) {
        SegmentCatalog.SegmentMetadata metadata = segments.get(segmentId);
        if (metadata != null) {
            metadata.addEntryCount(deltaChange);
        }
    }

    /**
     * Records that an entry was tombstoned in a segment.
     *
     * @param segmentId Unique segment identifier
     */
    void recordTombstone(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = segments.get(segmentId);
        if (metadata != null) {
            metadata.addTombstoneCount(1);
        }
    }

    // ==================== Lifecycle transitions ====================

    /**
     * Returns the record for a segment that must exist.
     *
     * @param segmentId Unique segment identifier
     * @return The metadata, never null
     * @throws IllegalArgumentException if segment not found
     */
    private SegmentCatalog.SegmentMetadata require(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = segments.get(segmentId);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentId);
        }
        return metadata;
    }

    /**
     * Transitions a segment to WARMING and persists it.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in INDEXING state
     * @throws IllegalArgumentException if segment not found
     */
    void completeIndexing(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        metadata.transitionToWarming();
        persistence.persist(metadata);
    }

    /**
     * Transitions a segment to ACTIVE and persists it.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in WARMING state
     * @throws IllegalArgumentException if segment not found
     */
    void completeWarmup(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        metadata.transitionToActive();
        persistence.persist(metadata);
    }

    /**
     * Transitions a segment to COMPACTING and persists it.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in ACTIVE state
     * @throws IllegalArgumentException if segment not found
     */
    void beginCompaction(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        metadata.transitionToCompacting();
        persistence.persist(metadata);
    }

    /**
     * Returns a segment from COMPACTING to ACTIVE, clearing its tombstone count.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    void completeCompaction(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        // Transition from COMPACTING back to ACTIVE
        metadata.setLifecycleState(LifecycleState.ACTIVE);
        metadata.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);
        metadata.setLastCompactionTime(System.currentTimeMillis());
        // Compaction is what removes tombstoned records, so the count starts over.
        metadata.clearTombstoneCount();
        persistence.persist(metadata);
    }

    /**
     * Re-points a segment at a new file after compaction.
     *
     * @param segmentId Unique segment identifier
     * @param newPath The new path to the segment file
     * @throws IllegalArgumentException if segment not found or path is null
     */
    void updatePath(long segmentId, Path newPath) {
        if (newPath == null) {
            throw new IllegalArgumentException("New path cannot be null");
        }
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        metadata.setVolumePath(newPath);
        persistence.persist(metadata);
    }

    /**
     * Marks a segment TOMBSTONED, recording the visibility epoch when one is given.
     *
     * @param segmentId Unique segment identifier
     * @param visibilityEpoch The current visibility epoch, or negative to record none
     * @throws IllegalStateException if the segment is not in ACTIVE or COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    void tombstone(long segmentId, long visibilityEpoch) {
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        if (visibilityEpoch >= 0) {
            metadata.transitionToTombstoned(visibilityEpoch);
        } else {
            metadata.transitionToTombstoned();
        }
        persistence.persist(metadata);
    }

    /**
     * Transitions a segment from BOOTSTRAPPING to INDEXING and persists it.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in BOOTSTRAPPING state
     * @throws IllegalArgumentException if segment not found
     */
    void beginIndexing(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = require(segmentId);
        metadata.transitionToIndexing();
        persistence.persist(metadata);
    }
}
