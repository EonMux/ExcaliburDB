package com.eonmux.excalibur.store;

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
 * <h2>Two id spaces that never meet</h2>
 * <p>The first two register <em>canonical</em> ids: routing-directory indices the storage
 * engine chooses, including the indices a split publishes its children at. The third
 * <em>allocates</em> ids for segments that belong to a source rather than to a directory slot.
 * If the two could produce the same number, a split publishing its right child at an index a
 * rolling reindex had already been given would land on that reindex's record - another
 * source's segment, often already TOMBSTONED - and a live segment would inherit its state and
 * drop out of every query. So they are disjoint by construction: the directory's width is an
 * {@code int} power of two, which bounds every canonical index by {@link Integer#MAX_VALUE},
 * and {@code create()} only ever hands out ids from {@link #FIRST_CREATED_ID} upward.</p>
 *
 * <p>A persisted catalog can still hold created records at ids inside the routing range,
 * allocated when {@code create()} drew from the same counter as the routing indices. Those
 * records keep their ids - renumbering them would orphan
 * whatever still refers to them - and registering a canonical segment over one is refused
 * with an exception instead of adopting it, so the collision surfaces where it happens rather
 * than as a segment that silently stops answering queries. See
 * {@link #checkRoutingRegistration(long)}.</p>
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

    /**
     * The lowest id {@link #create(String, Path)} hands out.
     *
     * <p>One past the largest index an {@code int}-wide routing directory can hold, so no
     * created id can ever equal a canonical one. The catalog file stores ids as eight-byte
     * fields, so ids in this range persist and recover like any other.</p>
     */
    static final long FIRST_CREATED_ID = (long) Integer.MAX_VALUE + 1;

    /**
     * The last id {@link #create(String, Path)} handed out, or {@code FIRST_CREATED_ID - 1}
     * when it has handed out none.
     *
     * <p>Canonical ids need no generator: the engine chooses them.</p>
     */
    private final AtomicLong createdIdGenerator = new AtomicLong(FIRST_CREATED_ID - 1);

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
     * @param segmentId Unique segment identifier: a routing-directory index
     * @param volumePath Path to the segment file
     * @return The created or existing metadata
     * @throws IllegalArgumentException if the id lies outside the routing range
     * @throws IllegalStateException if the id is held by a segment {@link #create} made
     */
    SegmentCatalog.SegmentMetadata add(long segmentId, Path volumePath) {
        checkRoutingRegistration(segmentId);
        SegmentCatalog.SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k ->
            new SegmentCatalog.SegmentMetadata(segmentId));

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
     * @throws IllegalArgumentException if the id lies outside the routing range
     * @throws IllegalStateException if the id is held by a segment {@link #create} made
     */
    SegmentCatalog.SegmentMetadata registerFile(long segmentId, Path volumePath) {
        checkRoutingRegistration(segmentId);
        SegmentCatalog.SegmentMetadata metadata = segments.computeIfAbsent(segmentId, k ->
            new SegmentCatalog.SegmentMetadata(segmentId));

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
     * <p>The id is drawn from {@link #FIRST_CREATED_ID} upward, so it is never one the
     * routing directory can publish a segment at.</p>
     *
     * @param sourceId The source ID this segment belongs to
     * @param volumePath Path to the segment file
     * @return The created segment metadata with BOOTSTRAPPING state
     * @throws NullPointerException if sourceId or volumePath is null
     * @throws IllegalStateException if the drawn id is somehow already registered
     */
    SegmentCatalog.SegmentMetadata create(String sourceId, Path volumePath) {
        if (sourceId == null) {
            throw new NullPointerException("sourceId cannot be null");
        }
        if (volumePath == null) {
            throw new NullPointerException("volumePath cannot be null");
        }

        // add() and registerFile() refuse ids in the created range, and restore() advances
        // the generator past every one it reads back, so the drawn id is free. An id that is
        // nonetheless taken means that invariant is broken, and a blind put() would overwrite
        // whatever holds it, so the claim is conditional.
        long segmentId = createdIdGenerator.incrementAndGet();
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(segmentId);
        if (segments.putIfAbsent(segmentId, metadata) != null) {
            throw new IllegalStateException(
                "Created segment id " + segmentId + " is already registered; the catalog's "
                    + "created-id generator is behind the ids it holds");
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
     * Refuses a canonical registration that would land on something other than a routing
     * segment's record.
     *
     * <p>A canonical id is a routing-directory index, so it must lie in
     * {@code [0, FIRST_CREATED_ID)}. Within that range, the only record that can already hold
     * the id and not be a routing segment's is one {@link #create(String, Path)} allocated
     * there when it shared a counter with the routing indices, and which a persisted catalog
     * still holds; such a record carries its source's id. Registering over it would re-point another source's segment -
     * frequently already TOMBSTONED - at a live file, and leave that file invisible to queries
     * under the other record's state, so this throws instead. Split publication calls it before
     * it touches any file, which is what lets a refused split return its parent to service
     * unchanged.</p>
     *
     * @param segmentId The routing index about to be registered
     * @throws IllegalArgumentException if the id lies outside the routing range
     * @throws IllegalStateException if a created segment already holds the id
     */
    void checkRoutingRegistration(long segmentId) {
        if (segmentId < 0 || segmentId >= FIRST_CREATED_ID) {
            throw new IllegalArgumentException(
                "Segment id " + segmentId + " is not a routing index: routing indices lie in [0, "
                    + FIRST_CREATED_ID + ")");
        }
        SegmentCatalog.SegmentMetadata existing = segments.get(segmentId);
        if (existing != null && isCreated(existing)) {
            throw new IllegalStateException(
                "Routing index " + segmentId + " is held by a segment created for source '"
                    + existing.getSourceId() + "' (state " + existing.getLifecycleState()
                    + ", file " + existing.getVolumePath() + "); refusing to register a routing "
                    + "segment over it. Purge that segment to free the index.");
        }
    }

    /**
     * Whether the record at a routing index belongs to a source rather than to the routing
     * segment at that index.
     *
     * @param segmentIndex The routing index
     * @return true if a record made by {@link #create(String, Path)} holds the index
     */
    boolean isHeldByCreatedRecord(long segmentIndex) {
        SegmentCatalog.SegmentMetadata existing = segments.get(segmentIndex);
        return existing != null && isCreated(existing);
    }

    /**
     * Replaces a created record that holds a routing index with a routing segment's own row.
     *
     * <p>The catalog can hold, at a routing index, a record {@link #create(String, Path)}
     * allocated for a source while it shared a counter with the routing indices, and which a
     * split then re-pointed at the segment it published there. That record carries the
     * source's id and the source's lifecycle state - TOMBSTONED when the rolling reindex that
     * made it fell back - so the routing segment answers no query while it keeps accepting
     * writes. The replacement is the row the segment would have been given had it been
     * discovered with none: its own file, INDEXING, and ACTIVE with its entry count when it
     * holds entries. Its write-log checkpoint, creation time and last compaction are carried
     * over: from the moment the split re-pointed the record, those were advanced by the
     * routing segment itself, and before it the created segment had no storage to advance
     * them.</p>
     *
     * <p>The source id and the lifecycle state are exactly what is discarded, and the source id
     * is also what selects the record, never its state alone: a routing segment can
     * legitimately be TOMBSTONED, and its row carries no source id.</p>
     *
     * @param segmentIndex The routing index the routing segment occupies
     * @param volumePath   The routing segment's file
     * @param entryCount   The routing segment's live entry count
     * @return The routing segment's new row
     * @throws IllegalArgumentException if the id is not a routing index
     * @throws IllegalStateException if the index is not held by a created record
     */
    SegmentCatalog.SegmentMetadata replaceWithRoutingRecord(long segmentIndex, Path volumePath,
                                                            int entryCount) {
        if (segmentIndex < 0 || segmentIndex >= FIRST_CREATED_ID) {
            throw new IllegalArgumentException(
                "Segment id " + segmentIndex + " is not a routing index");
        }
        SegmentCatalog.SegmentMetadata stale = segments.get(segmentIndex);
        if (stale == null || !isCreated(stale)) {
            throw new IllegalStateException(
                "Routing index " + segmentIndex + " is not held by a created segment");
        }

        SegmentCatalog.SegmentMetadata routing = new SegmentCatalog.SegmentMetadata(segmentIndex);
        routing.setVolumePath(volumePath);
        routing.setCreationTime(stale.getCreationTime() != 0
            ? stale.getCreationTime() : System.currentTimeMillis());
        routing.setLastCompactionTime(stale.getLastCompactionTime());
        routing.setLastIntegratedOffset(stale.getLastIntegratedOffset());
        routing.transitionToIndexing();
        if (entryCount > 0) {
            routing.transitionToWarming();
            routing.transitionToActive();
            routing.setEntryCount(entryCount);
        }

        if (!segments.replace(segmentIndex, stale, routing)) {
            throw new IllegalStateException(
                "The record at routing index " + segmentIndex + " changed while it was replaced");
        }
        Path stalePath = stale.getVolumePath();
        if (stalePath != null && !stalePath.equals(volumePath)) {
            unindexByVolume(stalePath, segmentIndex);
        }
        indexByVolume(volumePath, segmentIndex);
        persistence.persist(routing);
        return routing;
    }

    /**
     * Whether a record was made by {@link #create(String, Path)} rather than registered at a
     * routing index.
     *
     * <p>Created records are the only ones that carry a source id; ids in the created range
     * identify them even when that id did not survive persistence.</p>
     *
     * @param metadata The record
     * @return true if it belongs to a source rather than to a directory slot
     */
    private static boolean isCreated(SegmentCatalog.SegmentMetadata metadata) {
        return metadata.getSegmentId() >= FIRST_CREATED_ID || metadata.getSourceId() != null;
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
        createdIdGenerator.set(FIRST_CREATED_ID - 1);
    }

    /**
     * Populates the registry from records read back off disk.
     *
     * <p>PURGED records are skipped: they describe segments whose disk has already been
     * freed, so recovering them would put a segment back in the catalog that nothing holds.
     * That is also why {@link SegmentCatalog.CatalogMetadata#getLiveSegments()} can treat
     * PURGED as a state the catalog never holds.</p>
     *
     * <p>The created-id generator is advanced past every created-range id read back, so
     * {@link #create(String, Path)} never reissues one. A created record found inside the
     * routing range is kept under its id and reported: if a routing segment is found at that
     * index when the engine starts, {@link #replaceWithRoutingRecord} gives it a row of its
     * own; otherwise a split publishing at that index is refused until the record is
     * purged.</p>
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

            long segmentId = segMetadata.getSegmentId();
            if (segmentId >= FIRST_CREATED_ID) {
                createdIdGenerator.accumulateAndGet(segmentId, Math::max);
            } else if (isCreated(segMetadata)) {
                SegmentCatalog.logger.warn("Segment {} was created for source {} at an id inside "
                        + "the routing range; unless start-up finds a routing segment there to "
                        + "take the index back, a split publishing routing index {} is refused "
                        + "until it is purged", segmentId, segMetadata.getSourceId(), segmentId);
            }
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
