package com.eonmux.excalibur.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Where a {@link Segment} comes into existence, and where it is allowed to stop existing.
 *
 * <p>There is exactly one creation path, and this is it. Eager pre-allocation at start-up and
 * lazy creation on the first write that routes to an empty slot both come through
 * {@link #createSegment(int)}, so a segment can never reach service without its write log, its
 * catalog row, its HNSW graph or its registration with the split worker. Every one of those
 * omissions is silent at the time and expensive later - a segment with no log accepts writes
 * it cannot recover, a segment with no catalog row is invisible to every query - which is why
 * the path is single rather than convenient.</p>
 *
 * <h2>Reclamation is refused more often than it is performed</h2>
 * <p>The other half of this class is the answer to "may this tombstoned segment be purged",
 * and the answer is usually no. A segment index is a slot in a hash directory; slots are never
 * vacated, only re-pointed. Purging a segment the directory still routes to removes its
 * catalog row and leaves the file behind, and the next start finds that file, sees no row
 * naming it, and adds it back as a live segment - undoing a tombstone that a rolling reindex
 * placed deliberately. {@link #reclaim(SegmentLifecycleManager, long)} carries the full
 * argument.</p>
 *
 * <p><strong>Thread safety:</strong> creation holds {@code StorageEngine.segmentLock} - the
 * engine's single monitor, shared with every other directory mutation - and double-checks the
 * map inside it, so two threads racing on the same empty slot produce one segment. Reclamation
 * holds it too, across both the routing check and the purge. The
 * lifecycle manager is published through the engine's volatile field under the engine's own
 * monitor.</p>
 */
final class SegmentRegistry {

    /** The engine whose segments these are. */
    private final StorageEngine engine;

    /**
     * Segment indices whose reclamation has already been refused and logged.
     *
     * <p>A refusal is a standing condition, not an event: the lifecycle sweep offers the same
     * segment on every tick for as long as it stays tombstoned. Recording it keeps the warning
     * to once per segment instead of once per tick.</p>
     */
    private final Set<Long> refusedReclamations = ConcurrentHashMap.newKeySet();

    /**
     * Binds this registry to its engine.
     *
     * @param engine The owning engine
     */
    SegmentRegistry(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Creates, registers and fully initializes the segment at the given directory index.
     *
     * <p>This is the single creation path. Both eager pre-allocation and lazy creation
     * on first write go through it, so a segment can never be created without its write
     * log, its catalog entry or its HNSW graph.</p>
     *
     * @param segmentIndex The directory index for the new segment
     * @return The created or already-present segment
     * @throws RuntimeException if the segment or its write log cannot be created
     */
    Segment createSegment(int segmentIndex) {
        synchronized (engine.segmentLock) {
            // Double-check after acquiring lock
            Segment existing = engine.segments.get((long) segmentIndex);
            if (existing != null) {
                return existing;
            }

            Path segmentPath = engine.segmentPath(segmentIndex);
            try {
                Segment segment = new Segment(segmentPath, 1024 * 1024, engine.arena, engine.loadFactorThreshold);
                engine.locations.adopt(segmentIndex, segmentPath);

                // Create and attach WriteLog for crash recovery. Every mutation is
                // recorded in the log so a crash does not lose committed writes.
                segment.attachWriteLog(new WriteLog(StorageEngine.walPathFor(segmentPath)));

                engine.indexes.initializeHnswGraph(segment, segmentIndex);
                engine.indexes.initializeVectorPrefilter(segment, segmentIndex);

                engine.segments.put((long) segmentIndex, segment);
                engine.canonicalIndex.put(segment, (long) segmentIndex);
                engine.routingTable.publishRouting();

                // Add to catalog with initial INDEXING state
                engine.catalog.add((long) segmentIndex, segmentPath);

                // Register with split worker for automatic capacity management
                if (engine.splitWorker != null && engine.splitWorker.isRunning()) {
                    engine.splitWorker.registerSegment(segment);
                }

                engine.statistics.incrementSegmentOperations();
                return segment;
            } catch (IOException e) {
                throw new RuntimeException("Failed to create segment at index " + segmentIndex, e);
            }
        }
    }

    /**
     * Returns the engine's lifecycle manager, creating and wiring it on first use.
     *
     * <p>Double-checked over the volatile {@code StorageEngine.lifecycleManager} field, under
     * the engine's own monitor - the same monitor the original code used, not a new one, so a
     * caller already holding it still sees the manager it created.</p>
     *
     * <p>The three callbacks are the whole reason this is not just a constructor call. The
     * manager can read the catalog but not the engine, and each of these is knowledge only the
     * engine has: which epoch reclamation may be judged against, whether a segment's index has
     * caught up with its write log, and which indices the routing directory still points at.</p>
     *
     * @return The lifecycle manager, never null
     * @see StorageEngine#getLifecycleManager()
     */
    SegmentLifecycleManager lifecycleManager() {
        if (engine.lifecycleManager == null) {
            synchronized (engine) {
                if (engine.lifecycleManager == null) {
                    SegmentLifecycleManager manager = new SegmentLifecycleManager(engine.catalog);
                    // Reclamation must be gated on the visibility epoch, not wall clock.
                    manager.setCurrentEpochSupplier(() -> engine.visibilityTracker.currentEpoch());
                    // Nothing else moves a segment out of INDEXING: every later transition is
                    // driven by a worker that selects on a later state.
                    manager.setIndexingCompletionProbe(engine::isIndexingComplete);
                    // Only the engine knows which indices the directory still routes to, and
                    // reclaiming one of those is undone by the next start.
                    manager.setReclamationCallback(segmentId -> reclaim(manager, segmentId));
                    engine.lifecycleManager = manager;
                }
            }
        }
        return engine.lifecycleManager;
    }

    /**
     * Reclaims a tombstoned segment, or refuses one the directory still routes to.
     *
     * <p>A segment index is a slot in a hash directory: every key whose hash lands on it is
     * written there, and a slot cannot be vacated - there is no operation that takes a segment
     * out of the directory, only ones that re-point a slot at a different segment. Purging such
     * a segment removes its catalog row and leaves the file and the writes behind. From that
     * moment {@link StorageEngine#getQueryableSegments()} skips the slot, because it has no metadata to
     * judge, so everything routed there stops being answerable; and the next {@link StorageEngine#start()}
     * rediscovers the file, finds no row naming it and adds one back as a live segment, so the
     * tombstone a rolling reindex placed is undone by a bounce and the superseded copy of every
     * document it retired answers queries again.</p>
     *
     * <p>Refusing leaves the segment TOMBSTONED. That is durable, it is not queryable, and it
     * means the same thing after a restart as before one - which is the whole point.</p>
     *
     * @param manager      The lifecycle manager that offered the segment
     * @param segmentIndex The segment offered for reclamation
     * @return true if the catalog row was removed
     */
    private boolean reclaim(SegmentLifecycleManager manager, long segmentIndex) {
        // The check and the purge are one step under the directory's lock. Split publication
        // registers its children's rows under the same lock, so the row this removes is the one
        // the check was about, never one a split registered at the index in between.
        synchronized (engine.segmentLock) {
            if (engine.segments.get(segmentIndex) != null) {
                if (refusedReclamations.add(segmentIndex)) {
                    StorageEngine.LOGGER.log(Level.WARNING,
                        "Segment {0} is tombstoned but the routing directory still writes to it, "
                        + "so it is kept tombstoned rather than purged: a purged row is recreated "
                        + "as a live segment by the next start",
                        segmentIndex);
                }
                return false;
            }

            try {
                manager.purge(segmentIndex);
            } catch (RuntimeException e) {
                StorageEngine.LOGGER.log(Level.WARNING,
                    "Failed to purge segment " + segmentIndex + "; it stays tombstoned", e);
                return false;
            }
        }

        refusedReclamations.remove(segmentIndex);
        return true;
    }
}
