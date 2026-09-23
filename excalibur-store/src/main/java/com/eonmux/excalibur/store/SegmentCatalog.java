package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.catalog.CatalogFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Map;

/**
 * The metadata for every segment in the storage engine.
 *
 * <p>One {@link SegmentMetadata} record per segment - where its file is, how many entries it
 * holds, what state it is in, how far its index has absorbed its write log - held in memory
 * and mirrored to disk. It is what a restart reads to learn which segments exist and what
 * each was doing, and what a query reads to decide which to search.</p>
 *
 * <h2>Structure</h2>
 * <p>This class is the published surface; the work is done by four collaborators, each of
 * which carries the reasoning for its own part:
 * <ul>
 *   <li>{@link CatalogRegistry} - the in-memory records, the two indexes over them, and
 *       every operation that changes one</li>
 *   <li>{@link CatalogPersistence} - the on-disk copy, and why a close-time failure is
 *       reported rather than thrown</li>
 *   <li>{@link CatalogQueries} - the derived views: selections, aggregates and write-log
 *       offsets, none of them stored</li>
 *   <li>{@link EntryStateMapping} - the correspondence between a segment's lifecycle
 *       state and the older entry-state integer its on-disk record stores</li>
 * </ul>
 *
 * <h2>Lifecycle and entry state</h2>
 * <p>Each segment's position is held twice: as a {@link LifecycleState}, which the engine
 * reasons about and the transition rules govern, and as the older {@code ENTRY_STATE_*}
 * integer below, which the on-disk record stores. {@link EntryStateMapping} holds both
 * directions of the mapping, and the two are kept in step.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Fully thread-safe; every operation that changes a record is synchronized.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class SegmentCatalog {

    /**
     * The logger every part of the catalog writes to.
     *
     * <p>Declared here, and shared with the collaborators, so that the whole catalog logs
     * under one name however its work is divided up. An operator filtering on it must not
     * have to know that registration and persistence live in different classes.</p>
     */
    static final Logger logger = LoggerFactory.getLogger(SegmentCatalog.class);

    /**
     * The lowest segment id {@link #create(String, Path)} hands out.
     *
     * <p>Every id below it is a routing-directory index, which only the storage engine
     * chooses; every id {@code create()} returns is at or above it. See
     * {@link CatalogRegistry} for why the two ranges must not overlap.</p>
     */
    public static final long FIRST_CREATED_SEGMENT_ID = CatalogRegistry.FIRST_CREATED_ID;

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
     * Tombstones being cleaned; a frozen snapshot serves queries meanwhile.
     */
    public static final int ENTRY_STATE_COMPACTING = 5;

    // ==================== Instance Fields ====================

    /** The durable copy of every record. */
    private final CatalogPersistence persistence;

    /** The in-memory records and the indexes over them. */
    private final CatalogRegistry registry;

    /** The derived views over those records. */
    private final CatalogQueries queries;

    // ==================== Constructors ====================

    /**
     * Creates a new empty catalog (in-memory only, no persistence).
     *
     * <p><strong>Warning:</strong> Using this constructor means all catalog
     * metadata will be lost on restart. For persistent catalogs, use
     * {@link #SegmentCatalog(Path)} instead.</p>
     */
    public SegmentCatalog() {
        this((CatalogFile) null);
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
        this(CatalogPersistence.open(catalogPath));
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
        this(CatalogPersistence.open(catalogPath));
    }

    /**
     * Creates a new catalog over an already-open catalog file.
     *
     * <p>Call {@link #recover()} after construction to load persisted metadata. The catalog
     * takes over closing the file.</p>
     *
     * @param catalogFile The file to persist metadata to, or null for in-memory only
     */
    public SegmentCatalog(CatalogFile catalogFile) {
        this.persistence = new CatalogPersistence(catalogFile);
        this.registry = new CatalogRegistry(persistence);
        this.queries = new CatalogQueries(registry);
    }

    // ==================== Public API - Segment Operations ====================

    /**
     * Updates the metadata for a segment.
     *
     * <p>Sets the entry state and adjusts the entry count together, deriving the lifecycle
     * state from the entry state so the two stay consistent. See {@link CatalogRegistry}
     * for why that derivation bypasses transition validation.</p>
     *
     * @param segmentId Unique segment identifier
     * @param entryState Current state of entries in the segment
     * @param deltaChange In delta for entry count
     */
    public void update(long segmentId, int entryState, int deltaChange) {
        registry.update(segmentId, entryState, deltaChange);
    }

    /**
     * Updates entry count for a segment without changing state.
     *
     * @param segmentId Unique segment identifier
     * @param deltaChange Change in entry count (can be negative for deletions)
     */
    public void updateEntryCount(long segmentId, int deltaChange) {
        registry.updateEntryCount(segmentId, deltaChange);
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
        registry.recordTombstone(segmentId);
    }

    /**
     * Gets the metadata for a segment.
     *
     * @param segmentId Unique segment identifier
     * @return SegmentMetadata, or null if segment not found
     */
    public SegmentMetadata get(long segmentId) {
        return registry.get(segmentId);
    }

    /**
     * Gets the metadata for a segment by volume path.
     *
     * @param volumePath Path to the volume containing segments
     * @return List of SegmentMetadata for segments in the volume
     */
    public java.util.List<SegmentMetadata> getByVolume(Path volumePath) {
        return registry.getByVolume(volumePath);
    }

    /**
     * Gets all segment metadata.
     *
     * @return Map of segmentId to SegmentMetadata
     */
    public Map<Long, SegmentMetadata> getAll() {
        return registry.getAll();
    }

    /**
     * Adds a new segment to the catalog.
     *
     * <p>A newly created record is moved into INDEXING; one that is being re-registered in
     * a later state is left where it is. See {@link CatalogRegistry} for why.</p>
     *
     * @param segmentId The routing-directory index, below {@link #FIRST_CREATED_SEGMENT_ID}
     * @param volumePath Path to the segment file
     * @return The created metadata
     * @throws IllegalArgumentException if the id is not a routing index
     * @throws IllegalStateException if a segment made by {@link #create} holds the id
     */
    public SegmentMetadata add(long segmentId, Path volumePath) {
        return registry.add(segmentId, volumePath);
    }

    /**
     * Registers or re-points a segment file without forcing a lifecycle transition.
     *
     * <p>Used when a segment's backing file is replaced in place - split publication
     * repoints the parent's index at the left child, compaction at the compacted file. Both
     * can happen while the segment is ACTIVE, and {@code ACTIVE -> INDEXING} is not a legal
     * transition, so {@link #add(long, Path)} cannot serve.</p>
     *
     * @param segmentId  The segment index whose file changed
     * @param volumePath The new segment file path
     * @return The metadata for the segment
     * @throws IllegalArgumentException if the id is not a routing index
     * @throws IllegalStateException if a segment made by {@link #create} holds the id
     */
    public SegmentMetadata registerFile(long segmentId, Path volumePath) {
        return registry.registerFile(segmentId, volumePath);
    }

    /**
     * Creates a new segment in BOOTSTRAPPING state for a specific source.
     *
     * <p>For the reindexer's rolling replacement. The id is allocated atomically, at or above
     * {@link #FIRST_CREATED_SEGMENT_ID}, so it never names a routing-directory index, and the
     * segment is invisible to queries until {@link #beginIndexing(long)} moves it on.</p>
     *
     * @param sourceId The source ID this segment belongs to
     * @param volumePath Path to the segment file
     * @return The created segment metadata with BOOTSTRAPPING state
     * @throws NullPointerException if sourceId or volumePath is null
     */
    public SegmentMetadata create(String sourceId, Path volumePath) {
        return registry.create(sourceId, volumePath);
    }

    /**
     * Checks that a routing index can be registered by {@link #add} or {@link #registerFile}
     * without landing on a segment made by {@link #create}.
     *
     * <p>For callers that must know before they commit to anything - split publication,
     * which cannot undo its file moves once made.</p>
     *
     * @param segmentIndex The routing index about to be registered
     * @throws IllegalArgumentException if the id is not a routing index
     * @throws IllegalStateException if a created segment holds the id
     */
    void checkRoutingRegistration(long segmentIndex) {
        registry.checkRoutingRegistration(segmentIndex);
    }

    /**
     * Whether a record made by {@link #create} holds a routing index.
     *
     * @param segmentIndex The routing index
     * @return true if the record there belongs to a source rather than to a routing segment
     */
    boolean isHeldByCreatedRecord(long segmentIndex) {
        return registry.isHeldByCreatedRecord(segmentIndex);
    }

    /**
     * Gives a routing segment its own row in place of a created record holding its index.
     *
     * <p>For start-up, which is where such a row is found. See
     * {@link CatalogRegistry#replaceWithRoutingRecord} for what is kept and what is not.</p>
     *
     * @param segmentIndex The routing index
     * @param volumePath   The routing segment's file
     * @param entryCount   The routing segment's live entry count
     * @return The new row
     * @throws IllegalArgumentException if the id is not a routing index
     * @throws IllegalStateException if the index is not held by a created record
     */
    SegmentMetadata replaceWithRoutingRecord(long segmentIndex, Path volumePath, int entryCount) {
        return registry.replaceWithRoutingRecord(segmentIndex, volumePath, entryCount);
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
        return queries.bySource(sourceId);
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
        return queries.activeBySource(sourceId);
    }

    /**
     * Removes a segment from the catalog.
     *
     * <p>Transitions the segment to PURGED and persists that before dropping the record,
     * so a crash mid-removal leaves a purged row rather than a live one. The segment must
     * already be TOMBSTONED.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if segment is not in TOMBSTONED state
     */
    public void remove(long segmentId) {
        registry.remove(segmentId);
    }

    /**
     * Checks if a segment exists in the catalog.
     *
     * @param segmentId Unique segment identifier
     * @return true if segment exists
     */
    public boolean contains(long segmentId) {
        return registry.contains(segmentId);
    }

    // ==================== Lifecycle Transition Methods ====================
    //
    // Each validates the transition, applies it and persists the record. The rules
    // themselves are in LifecycleState; the ordering is in CatalogRegistry.

    /**
     * Completes the indexing phase for a segment and transitions it to WARMING.
     *
     * <p>Called once every entry for a batch has been written to the segment and the
     * Cuckoo filter and LSH have been populated.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in INDEXING state
     * @throws IllegalArgumentException if segment not found
     */
    public void completeIndexing(long segmentId) {
        registry.completeIndexing(segmentId);
    }

    /**
     * Completes the warming phase for a segment and transitions it to ACTIVE.
     *
     * <p>Called once HNSW edge optimization and PQ codebook training are complete.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in WARMING state
     * @throws IllegalArgumentException if segment not found
     */
    public void completeWarmup(long segmentId) {
        registry.completeWarmup(segmentId);
    }

    /**
     * Begins compaction for a segment and transitions it to COMPACTING.
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in ACTIVE state
     * @throws IllegalArgumentException if segment not found
     */
    public void beginCompaction(long segmentId) {
        registry.beginCompaction(segmentId);
    }

    /**
     * Completes compaction for a segment and transitions it back to ACTIVE.
     *
     * <p>The tombstone count starts over, since compaction is what removes the tombstoned
     * records it counted.</p>
     *
     * @param segmentId Unique segment identifier
     * @throws IllegalStateException if the segment is not in COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    public void completeCompaction(long segmentId) {
        registry.completeCompaction(segmentId);
    }

    /**
     * Updates the path for a segment after compaction.
     *
     * <p>Called when a compaction worker has written a new segment file to replace the old
     * one, so that the catalog names the file that now holds the data.</p>
     *
     * @param segmentId Unique segment identifier
     * @param newPath The new path to the segment file
     * @throws IllegalArgumentException if segment not found or path is null
     */
    public void updatePath(long segmentId, Path newPath) {
        registry.updatePath(segmentId, newPath);
    }

    /**
     * Marks a segment as TOMBSTONED, making it invisible to new queries.
     *
     * <p>Records no visibility epoch, so the segment is invisible to every query rather
     * than to those that start after this point. Use {@link #tombstone(long, long)}
     * wherever an epoch is available.</p>
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
     * <p>The segment remains on disk until epoch-based reclamation completes. Recording the
     * epoch is what lets a query that started before this point keep reading the segment;
     * see {@link SegmentVisibilityWindow}.</p>
     *
     * @param segmentId Unique segment identifier
     * @param visibilityEpoch The current visibility epoch from the StorageEngine
     * @throws IllegalStateException if the segment is not in ACTIVE or COMPACTING state
     * @throws IllegalArgumentException if segment not found
     */
    public void tombstone(long segmentId, long visibilityEpoch) {
        registry.tombstone(segmentId, visibilityEpoch);
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
        registry.beginIndexing(segmentId);
    }

    /**
     * Gets all segments in a specific lifecycle state.
     *
     * @param state The lifecycle state to filter by
     * @return List of segment IDs in the specified state
     */
    public java.util.List<Long> getSegmentsByState(LifecycleState state) {
        return queries.byState(state);
    }

    /**
     * Gets all segments that are queryable (INDEXING, WARMING, ACTIVE, COMPACTING).
     *
     * @return List of queryable segment IDs
     */
    public java.util.List<Long> getQueryableSegments() {
        return queries.queryable();
    }

    /**
     * Gets the number of segments in the catalog.
     *
     * @return Segment count
     */
    public int size() {
        return registry.size();
    }

    /**
     * Clears the entire catalog.
     *
     * <p>Use with caution - this removes all segment metadata.</p>
     */
    public void clear() {
        registry.clear();
    }

    // ==================== Persistence Methods ====================

    /**
     * Recovers persisted catalog metadata from disk.
     *
     * <p>Loads every persisted record into memory, and should be called once after
     * construction. A record that fails CRC validation is skipped, so a partial write costs
     * one segment rather than the whole catalog, and a PURGED record is skipped because the
     * segment it names is already gone. Does nothing when persistence is off.</p>
     *
     * @throws UncheckedIOException if the catalog file cannot be read
     */
    public void recover() {
        if (!persistence.isPersistent()) {
            return; // No persistence enabled
        }
        registry.restore(persistence.readAll());
    }

    /**
     * Flushes all pending changes to the catalog file.
     *
     * <p>Forces every metadata change to the device; call it before shutting down. Does
     * nothing when persistence is off.</p>
     *
     * @throws UncheckedIOException if the flush fails
     */
    public void flush() {
        persistence.flush();
    }

    /**
     * Closes the catalog file and releases resources.
     *
     * <p>The catalog stays usable in memory afterwards, but persistence operations will
     * fail. The close always completes: a failure is recorded on {@link #closeFailure()}
     * and logged rather than thrown, because the steps that follow a catalog close in a
     * shutdown - releasing mappings above all - must still run. See
     * {@link CatalogPersistence} for why.</p>
     */
    public void close() {
        persistence.close();
    }

    /**
     * Returns the failure that prevented this catalog from closing durably.
     *
     * <p>A non-null value means the on-disk metadata may not reflect the last writes, so the
     * shutdown must not be reported as clean.</p>
     *
     * @return The first close-time failure, or null when the close was clean
     */
    public Throwable closeFailure() {
        return persistence.closeFailure();
    }

    /**
     * Checks if persistence is enabled for this catalog.
     *
     * @return true if the catalog has a persistent backing store
     */
    public boolean isPersistent() {
        return persistence.isPersistent();
    }

    /**
     * Registers a new volume in the catalog.
     *
     * @param volumePath Path to the volume
     */
    public void registerVolume(Path volumePath) {
        registry.registerVolume(volumePath);
    }

    /**
     * Unregisters a volume from the catalog.
     *
     * @param volumePath Path to the volume
     */
    public void unregisterVolume(Path volumePath) {
        registry.unregisterVolume(volumePath);
    }

    // ==================== Catalog Metadata ====================

    /** Alias of {@link #ENTRY_STATE_ACTIVE} for external use. */
    public static final int ACTIVE = ENTRY_STATE_ACTIVE;

    /** Alias of {@link #ENTRY_STATE_DELETED} for external use. */
    public static final int DELETED = ENTRY_STATE_DELETED;

    /** Alias of {@link #ENTRY_STATE_INDEXING} for external use. */
    public static final int INDEXING = ENTRY_STATE_INDEXING;

    /**
     * Returns the catalog metadata.
     *
     * <p>Recomputed from the current segment states on every call, so it cannot be
     * stale, and immutable once returned.</p>
     *
     * @return Immutable CatalogMetadata snapshot
     */
    public CatalogMetadata getCatalogMetadata() {
        return queries.snapshot();
    }

    /**
     * Gets the total number of entries across all segments.
     *
     * @return Total entry count
     */
    public long getTotalEntryCount() {
        return queries.totalEntryCount();
    }

    /**
     * Computes estimated corpus coverage.
     *
     * <p>The share of the live corpus a query could read - see
     * {@link CatalogMetadata#getEstimatedCoverage()}, which is where the rule lives.</p>
     *
     * @return Coverage ratio (0.0 to 1.0)
     */
    public double getEstimatedCoverage() {
        return getCatalogMetadata().getEstimatedCoverage();
    }

    // ==================== Offset Tracking Methods ====================
    //
    // Both offsets are non-negative, monotonically increasing, and ordered
    // lastIntegratedOffset <= writeLogHead. The difference between them is the tail a query
    // still has to scan for itself. See CatalogQueries and MonotonicOffset.

    /**
     * Gets the last integrated offset for a segment.
     *
     * <p>The write log offset up to which all entries have been linked into the HNSW graph.
     * Entries beyond it exist in the write log but are not yet in the graph.</p>
     *
     * @param segmentId Unique segment identifier
     * @return Last integrated offset, or 0 if segment not found
     */
    public long getLastIntegratedOffset(long segmentId) {
        return queries.lastIntegratedOffset(segmentId);
    }

    /**
     * Advances the last integrated offset for a segment.
     *
     * <p>Called by the HNSW integration worker after linking entries into the graph; the
     * update is atomic and monotonic, as
     * {@link SegmentMetadata#compareAndSetLastIntegratedOffset(long)} explains.</p>
     *
     * @param segmentId Unique segment identifier
     * @param offset The new last integrated offset (must be &ge; 0)
     * @return true if the offset was updated, false if segment not found or offset not greater
     * @throws IllegalArgumentException if offset is negative
     */
    public boolean setLastIntegratedOffset(long segmentId, long offset) {
        return queries.setLastIntegratedOffset(segmentId, offset);
    }

    /**
     * Gets the write log head offset for a segment.
     *
     * <p>The offset of the next byte to write; everything below it is in the log.</p>
     *
     * @param segmentId Unique segment identifier
     * @return Write log head offset, or 0 if segment not found
     */
    public long getWriteLogHead(long segmentId) {
        return queries.writeLogHead(segmentId);
    }

    /**
     * Advances the write log head offset for a segment.
     *
     * <p>Called by the storage engine after appending entries to the write log. The update
     * is atomic and monotonic.</p>
     *
     * @param segmentId Unique segment identifier
     * @param offset The new write log head offset (must be &ge; 0)
     * @return true if the offset was updated, false if segment not found or offset not greater
     * @throws IllegalArgumentException if offset is negative
     */
    public boolean setWriteLogHead(long segmentId, long offset) {
        return queries.setWriteLogHead(segmentId, offset);
    }

    /**
     * Gets the tail scan size for a segment: the write-log bytes its index has not absorbed.
     *
     * @param segmentId Unique segment identifier
     * @return Tail scan size in bytes, never negative; 0 if segment not found
     */
    public long getTailScanSize(long segmentId) {
        return queries.tailScanSize(segmentId);
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
     * <p>The record the catalog keeps per segment, and the one
     * {@link com.eonmux.excalibur.store.catalog.CatalogFile} reads and writes. Parts of it carry
     * their own reasoning elsewhere: the two counts are a {@link SegmentOccupancy}, each
     * write-log offset a {@link MonotonicOffset}, and the epoch span over which the segment
     * answers queries a {@link SegmentVisibilityWindow}.</p>
     *
     * <p><strong>Thread Safety:</strong> the counts, the offsets and the lifecycle
     * transitions are each individually atomic. The plain timestamps are not, and should be
     * written from a single thread or with external synchronization.
     */
    public static class SegmentMetadata {
        private final long segmentId;
        private volatile Path volumePath;

        /** Live entries and tombstones, the two counts compaction is scheduled on. */
        private final SegmentOccupancy occupancy = new SegmentOccupancy();

        private volatile int entryState = ENTRY_STATE_NONE;

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
         * The write log offset up to which all entries have been linked into the HNSW
         * graph. Entries beyond this offset exist in the write log but are not yet in the
         * graph.
         */
        private final MonotonicOffset lastIntegratedOffset = new MonotonicOffset();

        /**
         * The offset of the next byte to be written to the write log. All entries with
         * offset below it have been persisted to the write log.
         */
        private final MonotonicOffset writeLogHead = new MonotonicOffset();

        private long lastCompactionTime = 0;
        private long creationTime = 0;

        /**
         * When the segment last entered the ACTIVE or DELETED entry state, and the reference
         * the wall-clock reclamation grace period runs from for a tombstone carrying no
         * visibility epoch. See {@code SegmentLifecycleManager#isPastGracePeriod}.
         */
        private long lastActiveTime = 0;

        /** The span of visibility epochs over which this segment answers queries. */
        private final SegmentVisibilityWindow visibility = new SegmentVisibilityWindow();

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
            return occupancy.entries();
        }

        /**
         * Sets the entry count.
         *
         * @param entryCount Entry count
         */
        public void setEntryCount(int entryCount) {
            occupancy.entries(entryCount);
        }

        /**
         * Atomically adjusts the entry count by a delta.
         *
         * @param delta The change to apply (may be negative)
         * @return The updated entry count
         */
        public int addEntryCount(int delta) {
            return occupancy.addEntries(delta);
        }

        /**
         * Gets the number of tombstoned entries since the last compaction.
         *
         * @return Tombstone count
         */
        public int getTombstoneCount() {
            return occupancy.tombstones();
        }

        /**
         * Records tombstoned entries.
         *
         * @param delta Number of entries tombstoned
         * @return The updated tombstone count
         */
        public int addTombstoneCount(int delta) {
            return occupancy.addTombstones(delta);
        }

        /**
         * Clears the tombstone count, e.g. after a compaction removed the records.
         */
        public void clearTombstoneCount() {
            occupancy.clearTombstones();
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
            return occupancy.tombstoneRatio();
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
         * <p>Validates the state transition before applying the new state.</p>
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
         * <p>Used by the catalog to sync the lifecycle state with the entry state, which
         * may name a position the state machine cannot legally reach from where it is.
         * Everything else uses {@link #setLifecycleState(LifecycleState)}.</p>
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
         * @throws IllegalArgumentException if the value names no lifecycle state
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
         * <p><strong>The state is written before the epoch, and that order is the invariant.</strong>
         * The two writes are not one critical section - the state under {@code stateLock}, the epoch as
         * a volatile store on the visibility window - so a reader can land between them and see
         * {@code TOMBSTONED} with the tombstone epoch still at its {@code -1} sentinel. That reader
         * concludes the segment is visible (see {@link SegmentVisibilityWindow#visibleAt(long)}), which
         * is what it would have concluded an instant earlier anyway: the window can only delay a
         * tombstone taking effect, never retire a segment out from under a query entitled to see it.
         * The opposite order would not fail safe, because {@link #setLifecycleState(LifecycleState)}
         * validates and throws: recording the epoch first would leave a tombstone epoch on a segment
         * that never became tombstoned, one that reads as invisible to every query at or after that
         * epoch while it is still ACTIVE.</p>
         *
         * @param visibilityEpoch The current visibility epoch from the StorageEngine
         * @throws IllegalStateException if not in ACTIVE or COMPACTING state
         */
        public void transitionToTombstoned(long visibilityEpoch) {
            setLifecycleState(LifecycleState.TOMBSTONED);
            visibility.tombstoneEpoch(visibilityEpoch);
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
            setLifecycleState(LifecycleState.BOOTSTRAPPING);
        }

        /**
         * Gets the last integrated offset.
         *
         * <p><strong>Atomicity:</strong> This method returns a consistent snapshot
         * of the offset value. The returned value is always at or beyond any value that
         * has been persisted to disk.</p>
         *
         * @return Last integrated offset
         */
        public long getLastIntegratedOffset() {
            return lastIntegratedOffset.get();
        }

        /**
         * Sets the last integrated offset, whether or not it advances.
         *
         * <p><strong>Note:</strong> This method directly sets the offset without
         * checking monotonicity. Use {@link #compareAndSetLastIntegratedOffset(long)}
         * for thread-safe updates that guarantee monotonic increase.</p>
         *
         * @param lastIntegratedOffset Last integrated offset (must be &ge; 0)
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
         * Atomically advances the last integrated offset, never lowering it.
         *
         * <p>A negative offset is refused rather than ignored, because the caller is
         * reporting progress and there is no such thing as negative progress.</p>
         *
         * @param newOffset The new offset value (must be &ge; 0)
         * @return true if the offset was updated (new value was greater and non-negative)
         */
        public boolean compareAndSetLastIntegratedOffset(long newOffset) {
            if (newOffset < 0) {
                return false;
            }
            return lastIntegratedOffset.advanceTo(newOffset);
        }

        /**
         * Atomically updates the offset to the maximum of current and new value.
         *
         * <p>Differs from {@link #compareAndSetLastIntegratedOffset(long)} only in saying
         * nothing about what it did: for a batch that advances the offset to the largest of
         * several values, whether any individual one won is not the caller's question.
         * Negative values are silently ignored to maintain the non-negative invariant.</p>
         *
         * @param newOffset The new offset value (negative values are ignored)
         */
        public void updateOffsetToMax(long newOffset) {
            if (newOffset < 0) {
                return; // Ignore negative values
            }
            lastIntegratedOffset.advanceTo(newOffset);
        }

        // ==================== Write Log Head Methods ====================

        /**
         * Gets the write log head offset.
         *
         * <p><strong>Atomicity:</strong> This method returns a consistent snapshot
         * of the offset value. The returned value is always at or beyond
         * lastIntegratedOffset.</p>
         *
         * @return Write log head offset
         */
        public long getWriteLogHead() {
            return writeLogHead.get();
        }

        /**
         * Sets the write log head offset, whether or not it advances.
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
         * Atomically advances the write log head offset, never lowering it.
         *
         * @param newOffset The new offset value
         * @return true if the offset was updated (new value was greater)
         */
        public boolean compareAndSetWriteLogHead(long newOffset) {
            return writeLogHead.advanceTo(newOffset);
        }

        /**
         * Atomically updates the write log head to the maximum of current and new value.
         *
         * <p>For batch updates that advance the head to the largest of several values and
         * do not care which one won.</p>
         *
         * @param newOffset The new offset value
         */
        public void updateWriteLogHeadToMax(long newOffset) {
            writeLogHead.advanceTo(newOffset);
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
         * Gets the epoch at which this segment was created.
         *
         * <p>Queries with an epoch below it cannot see this segment. See
         * {@link SegmentVisibilityWindow}.</p>
         *
         * @return The creation epoch, or -1 if not set
         */
        public long getCreationEpoch() {
            return visibility.creationEpoch();
        }

        /**
         * Sets the creation epoch, which should be the current visibility epoch at the
         * moment the segment is first registered.
         *
         * @param epoch The visibility epoch at which the segment was created
         */
        public void setCreationEpoch(long epoch) {
            visibility.creationEpoch(epoch);
        }

        /**
         * Gets the epoch at which this segment was tombstoned.
         *
         * <p>Queries started before it still see the segment; queries started at or after
         * it do not. See {@link SegmentVisibilityWindow}.</p>
         *
         * @return The tombstone epoch, or -1 if the segment has not been tombstoned
         */
        public long getTombstoneEpoch() {
            return visibility.tombstoneEpoch();
        }

        /**
         * Sets the tombstone epoch, which should be the current visibility epoch at the
         * moment the segment transitions to TOMBSTONED.
         *
         * @param epoch The visibility epoch at which the segment was tombstoned
         */
        public void setTombstoneEpoch(long epoch) {
            visibility.tombstoneEpoch(epoch);
        }

        /**
         * Checks if this segment is visible at the given query epoch.
         *
         * <p>Visible when the segment already existed when the query started and had not
         * been tombstoned by then; {@link SegmentVisibilityWindow} carries the rule and the
         * meaning of the -1 sentinels.</p>
         *
         * <p><strong>Note:</strong> This method only checks epoch visibility, not lifecycle
         * state. Callers should also check {@link #getLifecycleState()}.isQueryable() if
         * needed.</p>
         *
         * @param queryEpoch The epoch at which a query started
         * @return true if the segment is visible to the query
         */
        public boolean isVisibleAtEpoch(long queryEpoch) {
            return visibility.visibleAt(queryEpoch);
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
                segmentId, lifecycleState, occupancy.entries(), occupancy.tombstones(),
                sourceId);
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

        /**
         * Segments in a state a query may read: INDEXING, WARMING, ACTIVE or COMPACTING.
         *
         * @return The count
         */
        public long getQueryableSegments() {
            return activeSegments + indexingSegments + warmSegments + compactingSegments;
        }

        /**
         * The live corpus: everything the catalog holds that is not retired.
         *
         * <p>Retired means TOMBSTONED or PURGED; {@link CatalogCoverage} says why both are
         * subtracted and what goes wrong if only PURGED is.</p>
         *
         * @return The count, which may be zero
         */
        public long getLiveSegments() {
            return CatalogCoverage.liveSegments(totalSegments, tombstonedSegments,
                purgedSegments);
        }

        /**
         * The share of the live corpus a query can read, in [0, 1].
         *
         * <p>This node's readiness signal: 1.0 when every segment it holds is either
         * queryable or deliberately retired, lower when one is stranded in a state no query
         * reads, and 0.0 when there is no live corpus at all. It is not a warm-up gauge.
         * {@link CatalogCoverage} carries the reasoning and the failure it was written to
         * avoid.</p>
         *
         * @return The ratio
         */
        public double getEstimatedCoverage() {
            return CatalogCoverage.estimatedCoverage(getQueryableSegments(), getLiveSegments());
        }

        @Override
        public String toString() {
            return String.format(
                "CatalogMetadata{total=%d, active=%d, indexing=%d, warming=%d, "
                + "compacting=%d, tombstoned=%d, purged=%d, entries=%d}",
                totalSegments, activeSegments, indexingSegments, warmSegments,
                compactingSegments, tombstonedSegments, purgedSegments, totalEntryCount);
        }
    }
}
