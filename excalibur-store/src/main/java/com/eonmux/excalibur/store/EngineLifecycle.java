package com.eonmux.excalibur.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Bringing a {@link StorageEngine} up and taking it down, and the volume set both of those
 * depend on.
 *
 * <p>Start-up and shutdown are one subject rather than two because they are the only times the
 * engine opens or releases memory mappings, and because each is a fixed sequence whose order is
 * the correctness argument. Everywhere else in the engine a method can be read on its own;
 * here, a step moved is a bug. Keeping both sequences in one file is what lets the reasons be
 * written next to the order they justify instead of scattered over the methods that happen to
 * implement them.</p>
 *
 * <h2>What start-up is defending against</h2>
 * <p>A segment file that exists and is not adopted is worse than one that is missing: the
 * routing rebuild creates an empty segment over its index, so the data stays on disk and
 * unreachable while the node reports itself healthy. Everything at the front of
 * {@link #start()} - the volume check, the refusal to continue past a write-ahead log that
 * will not replay - exists to turn that silent outcome into a failure to start.</p>
 *
 * <h2>Arena lifetime</h2>
 * <p>The arena outlives every mapping taken from it, so {@link #close()} releases it last and
 * releases it unconditionally: no earlier failure may skip that step, because an arena left
 * open holds every segment mapping for the life of the JVM. An arena supplied by a caller is
 * never closed here - the caller may still have its own mappings in it.</p>
 *
 * <p><strong>Thread safety:</strong> neither sequence is safe to run concurrently with itself;
 * both are guarded by {@code StorageEngine.started}, which start sets last and close clears
 * last. Both mutate the segment map directly rather than under the segment lock, as they did
 * before this class existed: start runs before any worker is running, and close runs after
 * every worker has been stopped.</p>
 */
final class EngineLifecycle {

    /** The engine this brings up and takes down. */
    private final StorageEngine engine;

    /**
     * Binds this sequence to its engine.
     *
     * @param engine The owning engine
     */
    EngineLifecycle(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Brings the engine up: recover the catalog, adopt what is on disk, rebuild the
     * directory, start the split worker.
     *
     * <h2>Why discovery is the whole of it</h2>
     * <p>A restart has to find the segment files that are already there and adopt them, index
     * by index, before anything creates a segment. Nothing later in the sequence would notice
     * if it did not: the routing rebuild creates a fresh empty segment for any base index it
     * cannot find, so a segment that was passed over comes back as an empty file over the top
     * of a live one, and the node serves a corpus with a hole in it and logs nothing. That is
     * why the volume check below runs first, and why a write-ahead log that cannot be replayed
     * fails the start instead of being skipped.</p>
     *
     * <h2>Every segment gets a log</h2>
     * <p>A missing or empty write-ahead log used to mean "attach nothing", and
     * {@code Segment.appendPutToLog} returns quietly when there is no log - so the segment
     * served reads and writes normally while nothing it accepted was being logged, durable
     * only if the OS happened to write the mapping back. The window is real: compaction
     * unlinks a log and then creates its replacement, and a crash in between leaves an absent
     * or zero-length file. So a log is created unconditionally, and the fact that the old one
     * was unusable is logged as data loss rather than passed over.</p>
     *
     * @see StorageEngine#start()
     */
    void start() {
        if (engine.started) {
            return; // Already started
        }

        // Validate data directory is accessible
        if (!java.nio.file.Files.exists(engine.dataDir)) {
            throw new IllegalStateException(
                "Data directory does not exist: " + engine.dataDir);
        }

        // Recover persisted catalog metadata from previous runs
        engine.catalog.recover();

        // DISCOVER EXISTING SEGMENTS - This is the critical fix for data persistence
        // Without this, existing segment files would be ignored and data would be lost
        SegmentDiscovery discovery = SegmentDiscovery.across(engine.placement.directories());
        java.util.List<SegmentDiscovery.DiscoveredSegment> discoveredSegments = discovery.scanDirectory();

        // Before anything is opened, and well before the routing rebuild starts creating empty
        // segments over indices it did not find.
        refuseIfAnyVolumeIsMissing(discoveredSegments);

        // Every segment this start opens, so that a start that fails part-way releases them
        // rather than leaving their descriptors, write logs and graphs open behind an engine
        // that never started - and that a caller has no reason to close.
        List<Segment> opened = new ArrayList<>();
        try {
            int maxSegmentIndex = openDiscovered(discoveredSegments, opened);

            engine.routingTable.rebuildRoutingDirectory(maxSegmentIndex);

            // After the rebuild, because only the directory says which segment each index
            // routes to, and before anything can query or split.
            returnRoutingIndicesHeldBySources();
        } catch (RuntimeException | Error e) {
            releaseAfterFailedStart(opened, e);
            throw e;
        }

        // Start the background split worker for automatic capacity management
        if (engine.splitWorker != null) {
            engine.splitWorker.start();

            // Register each distinct segment once for load factor monitoring
            for (Segment segment : engine.routingTable.distinctSegments().values()) {
                engine.splitWorker.registerSegment(segment);
            }
        }

        engine.started = true;
        engine.statistics.incrementStartOperations();

        // Log startup summary
        java.util.logging.Logger.getLogger(StorageEngine.class.getName()).info(
            String.format("StorageEngine started: discovered=%d, total=%d segments, ready for operations",
                discoveredSegments.size(), engine.segments.size()));
    }

    /**
     * Opens every discovered segment, replays its log and registers it with the engine.
     *
     * @param discoveredSegments What the scan found
     * @param opened             Receives each segment as soon as it is open, so the caller can
     *                           release it if a later step fails
     * @return The highest segment index opened, or -1 if none was
     */
    private int openDiscovered(List<SegmentDiscovery.DiscoveredSegment> discoveredSegments,
                               List<Segment> opened) {
        // Track the highest segment index found for proper routing
        int maxSegmentIndex = -1;

        // Open and register each discovered segment
        for (SegmentDiscovery.DiscoveredSegment discovered : discoveredSegments) {
            try {
                // Open existing segment WITHOUT truncating - preserves data.
                // With the configured threshold, not the compile-time default: a segment
                // filled under a larger configured factor and reopened at the default rejects
                // replay of entries it had already acknowledged, and the startup fails every
                // time from then on.
                Segment segment =
                    Segment.openExisting(discovered.path(), engine.arena, engine.loadFactorThreshold);
                opened.add(segment);

                long segmentIndex = discovered.segmentIndex();
                // Where this segment is, not where the volume set would now put it: every
                // name derived from the index from here on has to follow the file.
                engine.locations.adopt(segmentIndex, discovered.path());
                engine.segments.put(segmentIndex, segment);
                engine.canonicalIndex.put(segment, segmentIndex);

                // Recovered segments are queryable, so they need a graph too.
                engine.indexes.initializeHnswGraph(segment, segmentIndex);
                engine.indexes.initializeVectorPrefilter(segment, segmentIndex);

                // Track max index for routing table sizing
                if (segmentIndex > maxSegmentIndex) {
                    maxSegmentIndex = (int) segmentIndex;
                }

                // Every segment gets a write log. A missing or empty WAL used to mean "attach
                // nothing", and Segment.appendPutToLog quietly returns when there is no log -
                // so the segment served reads and writes normally while nothing it accepted was
                // logged, durable only if the OS happened to write the mapping back. The window
                // is real: compaction unlinks a WAL and then creates its replacement, and a
                // crash in between leaves an absent or zero-length file.
                Path walPath = discovered.walPath() != null
                    ? discovered.walPath()
                    : StorageEngine.walPathFor(discovered.path());
                WriteLog writeLog = new WriteLog(walPath);
                segment.attachWriteLog(writeLog);

                if (discovered.hasWriteLog()) {
                    try {
                        // Replay write log to recover any uncommitted entries
                        segment.recoverFromLog();
                    } catch (IOException | RuntimeException e) {
                        // A replay that cannot be completed means the segment's committed
                        // state is unknown. Refuse to start rather than serving a segment
                        // that silently lost the tail of its log.
                        throw new IllegalStateException(
                            "Write-ahead log replay failed for segment " + segmentIndex
                            + " (" + walPath + "). Refusing to start with "
                            + "partially recovered state.", e);
                    }
                } else {
                    StorageEngine.LOGGER.log(Level.WARNING,
                        "Segment {0} had no usable write-ahead log at {1}; a fresh one was "
                        + "created. Anything it accepted since the log was lost is not "
                        + "recoverable.",
                        new Object[] { segmentIndex, walPath });
                }

                // After the replay, so the watermark it is judged by - the log's sequence number
                // and the entry counts - is the state the segment will serve from.
                if (!segment.terms.loadOrRebuild()) {
                    StorageEngine.LOGGER.log(Level.FINE,
                        "Rebuilt the term sketch of segment {0} from its live entries",
                        segmentIndex);
                }

                // Update last integrated offset from catalog if available
                SegmentCatalog.SegmentMetadata walMetadata = engine.catalog.get(segmentIndex);
                if (walMetadata != null) {
                    writeLog.setLastIntegratedOffset(walMetadata.getLastIntegratedOffset());
                }

                // Register in catalog and set appropriate lifecycle state
                SegmentCatalog.SegmentMetadata metadata = engine.catalog.get(segmentIndex);
                if (metadata == null) {
                    // New to catalog - add with appropriate state
                    metadata = engine.catalog.add(segmentIndex, discovered.path());
                }

                // Set lifecycle state based on whether segment has entries
                // Segments with entries should be ACTIVE (queryable)
                // Empty segments should be BOOTSTRAPPING (invisible to queries)
                if (segment.entryCount() > 0) {
                    // Transition to ACTIVE state for queryable segments
                    if (metadata.getLifecycleState() == LifecycleState.BOOTSTRAPPING) {
                        try {
                            metadata.transitionToIndexing();
                            metadata.transitionToWarming();
                            metadata.transitionToActive();
                            metadata.setEntryCount(segment.entryCount());
                        } catch (IllegalStateException e) {
                            // State transition failed - log and continue
                            java.util.logging.Logger.getLogger(StorageEngine.class.getName())
                                .warning("Failed to transition segment " + segmentIndex + " to ACTIVE: " + e.getMessage());
                        }
                    }
                }

                engine.statistics.incrementSegmentOperations();

            } catch (IOException e) {
                throw new RuntimeException("Failed to open discovered segment: " + discovered.path(), e);
            }
        }
        return maxSegmentIndex;
    }

    /**
     * Closes every segment a failed start opened or created, and forgets them, keeping the
     * failure's cause.
     *
     * <p>That is the segments opened from disk - including one that failed before it was
     * registered - and any the routing rebuild created for indices it did not find. A failure
     * here is added to the start's own as suppressed rather than thrown: the reason the start
     * failed is what the operator needs, and every remaining segment still has to be closed.</p>
     *
     * @param opened The segments this start opened from disk
     * @param cause  Why the start failed
     */
    private void releaseAfterFailedStart(List<Segment> opened, Throwable cause) {
        Set<Segment> toClose = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        toClose.addAll(opened);
        toClose.addAll(engine.segments.values());
        engine.segments.clear();
        engine.canonicalIndex.clear();
        engine.routingTable.publishRouting();
        engine.locations.forgetAll();
        for (Segment segment : toClose) {
            try {
                segment.close();
            } catch (RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
    }

    /**
     * Gives every routed segment whose catalog row belongs to a source a row of its own.
     *
     * <p>A catalog can hold, at a routing index, a record created for a rolling reindex while
     * such records shared a counter with the routing indices, re-pointed at a split child the
     * directory later published at that index. The routing segment then carries the source's
     * id and the source's lifecycle state - TOMBSTONED when the reindex fell back - so the keys
     * it holds answer no query while writes keep landing in it, and reclamation keeps the row
     * because the directory still routes there. Only the routing segment's own row makes it
     * queryable again, so the stale record is replaced with the row a discovered segment would
     * have been given; see {@code CatalogRegistry.replaceWithRoutingRecord}.</p>
     *
     * <p>The record is identified by the source id it carries, never by its state: a routing
     * segment can legitimately be TOMBSTONED or COMPACTING, and those rows carry no source id,
     * so they are left alone. The replacement is persisted, so a second start finds a clean
     * row and does nothing.</p>
     */
    private void returnRoutingIndicesHeldBySources() {
        for (Map.Entry<Long, Segment> routed : engine.routingTable.distinctSegments().entrySet()) {
            long segmentIndex = routed.getKey();
            if (!engine.catalog.isHeldByCreatedRecord(segmentIndex)) {
                continue;
            }
            SegmentCatalog.SegmentMetadata stale = engine.catalog.get(segmentIndex);
            Segment segment = routed.getValue();
            SegmentCatalog.SegmentMetadata repaired = engine.catalog.replaceWithRoutingRecord(
                segmentIndex, segment.path(), segment.entryCount());
            StorageEngine.LOGGER.log(Level.WARNING,
                "Routing segment {0} ({1}) was registered under a record created for source "
                    + "''{2}'' in state {3}; replaced it with the segment''s own row in state {4} "
                    + "so its entries are queryable again",
                new Object[] { segmentIndex, segment.path(), stale.getSourceId(),
                    stale.getLifecycleState(), repaired.getLifecycleState() });
        }
    }

    /**
     * Refuses to start when the catalog names segments that no scanned directory produced.
     *
     * <p>A volume that failed to mount, or that was dropped from the configuration, takes its
     * segments with it. Nothing further on in startup would notice: discovery would simply
     * return fewer segments, the routing rebuild would create empty files over the indices it
     * did not find, and the node would come up serving a corpus with a hole in it and no error
     * anywhere. So the catalog - which records where every segment was last written - is the
     * check, and a gap is fatal rather than a warning.</p>
     *
     * <p>The check runs only for a store that has actually used a volume other than the data
     * directory, whether it still has it registered or not. A single-directory store has no
     * volume that can go missing, and a segment file absent from it is a deleted file, which
     * this engine has always tolerated.</p>
     *
     * @param discovered Everything the scan found
     * @throws IllegalStateException if the catalog knows a live segment nothing produced
     */
    private void refuseIfAnyVolumeIsMissing(List<SegmentDiscovery.DiscoveredSegment> discovered) {
        Map<Long, SegmentCatalog.SegmentMetadata> known = engine.catalog.getAll();
        if (known.isEmpty()) {
            return;
        }

        List<Path> scanned = engine.placement.directories();
        if (scanned.size() <= 1 && !usesDirectoryOutside(known, scanned)) {
            return;
        }

        Set<Long> found = new HashSet<>();
        for (SegmentDiscovery.DiscoveredSegment segment : discovered) {
            found.add(segment.segmentIndex());
        }

        SortedMap<Path, List<Long>> gaps = new TreeMap<>();
        for (Map.Entry<Long, SegmentCatalog.SegmentMetadata> entry : known.entrySet()) {
            SegmentCatalog.SegmentMetadata metadata = entry.getValue();
            Path recorded = metadata.getVolumePath();
            if (recorded == null || found.contains(entry.getKey())) {
                continue;
            }
            if (isRetired(metadata.getLifecycleState())) {
                continue;
            }
            Path directory = recorded.getParent() != null ? recorded.getParent() : recorded;
            gaps.computeIfAbsent(directory, key -> new ArrayList<>()).add(entry.getKey());
        }

        if (gaps.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
            "Refusing to start: the catalog records segments that no scanned directory holds. "
                + "Missing by directory: " + gaps + ". Directories scanned: " + scanned
                + ". Restore or remount the volume, or remove those segments from the catalog "
                + "deliberately; starting without them would silently create empty segments "
                + "over their indices.");
    }

    /**
     * Whether the catalog remembers a segment written outside the directories now scanned.
     *
     * @param known   The recovered catalog entries
     * @param scanned The directories this start scans
     * @return true if any recorded segment sits outside the scanned set
     */
    private static boolean usesDirectoryOutside(Map<Long, SegmentCatalog.SegmentMetadata> known,
                                                List<Path> scanned) {
        for (SegmentCatalog.SegmentMetadata metadata : known.values()) {
            Path recorded = metadata.getVolumePath();
            if (recorded == null) {
                continue;
            }
            Path directory = recorded.getParent() != null ? recorded.getParent() : recorded;
            if (!scanned.contains(directory)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a segment in this state is allowed to have no file.
     *
     * @param state The recorded lifecycle state, possibly null
     * @return true if reclamation has removed, or is entitled to remove, the file
     */
    private static boolean isRetired(LifecycleState state) {
        return state == LifecycleState.TOMBSTONED || state == LifecycleState.PURGED;
    }

    /**
     * Installs the volume set, before the engine has looked at a disk.
     *
     * <p>Rejected after {@link StorageEngine#start()} because start is where the engine
     * discovers what is already on disk and creates whatever the base keyspace is missing, and
     * both of those answers depend on the placement. Installing one afterwards would mean a
     * restart had already scanned the wrong set of directories and created empty files over
     * the segments it did not find.</p>
     *
     * <p>Anything the single-directory default was already asked about was answered for a
     * directory set the engine is no longer using, so the location cache is dropped.</p>
     *
     * @param placement Where each segment belongs, and every directory a restart must scan
     * @return The engine, for chaining
     * @see StorageEngine#withSegmentPlacement(SegmentPlacement)
     */
    StorageEngine installPlacement(SegmentPlacement placement) {
        Objects.requireNonNull(placement, "placement cannot be null");
        if (engine.started) {
            throw new IllegalStateException(
                "Segment placement must be installed before start(): start() has already "
                    + "discovered segments and created the base keyspace under "
                    + engine.placement.directories());
        }

        List<Path> directories = placement.directories();
        if (directories == null || directories.isEmpty()) {
            throw new IllegalArgumentException("Placement must name at least one directory");
        }
        if (!directories.contains(engine.dataDir)) {
            throw new IllegalArgumentException(
                "Placement must include the data directory " + engine.dataDir
                    + ", which holds the catalog and the routing directory; got " + directories);
        }
        for (Path directory : directories) {
            if (!java.nio.file.Files.isDirectory(directory)) {
                throw new IllegalArgumentException(
                    "Volume directory does not exist: " + directory
                        + ". A volume that is not mounted holds segments this node cannot read.");
            }
        }

        engine.placement = placement;
        // Anything the single-directory default was already asked about was answered for a
        // directory set this engine is no longer using.
        engine.locations.forgetAll();
        StorageEngine.LOGGER.log(Level.INFO, "Segment placement installed over {0}", directories);
        return engine;
    }

    /**
     * Takes the engine down in the one order that releases everything.
     *
     * <p>Workers first, then the directory to disk, then each distinct segment flushed and
     * closed, then the catalog, and only then the arena. The order is not stylistic. The arena
     * owns every mapping in the engine, so closing it before the segments that read through
     * those mappings is a crash; and an arena that is never closed keeps the mappings alive
     * for the life of the JVM, so no earlier failure may be allowed to skip it. That is why a
     * failing catalog flush is logged and stepped over rather than thrown: the catalog's own
     * close reports whether its metadata reached the device, and the mappings still have to be
     * let go either way.</p>
     *
     * <p>Only an arena this engine created is closed. One handed in by a caller stays the
     * caller's to close, because the caller may still have segments of its own mapped in it.</p>
     *
     * @see StorageEngine#close()
     */
    void close() {
        // Stop the lifecycle manager if running
        engine.stopLifecycleManager();

        // Stop the split worker if running
        if (engine.splitWorker != null) {
            engine.splitWorker.stop();
        }

        // The directory, before the segments go. Splits already persist it; this covers a
        // directory that was reconstructed at start and never split afterwards.
        engine.routingTable.persistRoutingDirectory();

        // Flush and close each distinct segment once. Segment.close() also closes the
        // write log it owns, so no descriptor survives this.
        for (Segment segment : engine.routingTable.distinctSegments().values()) {
            if (!segment.isClosed()) {
                segment.flush();
                segment.close();
            }
        }
        engine.segments.clear();
        engine.canonicalIndex.clear();
        engine.routingTable.publishRouting();

        // Flush and close catalog to persist metadata. A failed flush must not abort the rest of
        // this method: the mappings released below are still held by this process, and an arena
        // left open keeps them held for the life of the JVM. So record it and carry on - the
        // catalog's own close reports whether its metadata reached the device.
        try {
            engine.catalog.flush();
        } catch (RuntimeException e) {
            StorageEngine.LOGGER.log(Level.SEVERE, "Failed to flush the catalog while closing", e);
        }
        engine.catalog.close();
        engine.catalog.clear();

        // Release the mappings. Only safe once every segment and write log is closed,
        // and only for the Arena this engine created - a caller-supplied Arena stays
        // the caller's to close.
        if (engine.ownsArena && engine.arena != null) {
            try {
                engine.arena.close();
            } catch (RuntimeException e) {
                StorageEngine.LOGGER.log(Level.WARNING, "Failed to close storage arena", e);
            }
        }

        // Reset state
        engine.started = false;
        // Epoch reset not needed - visibilityTracker handles this;
    }
}
