package com.eonmux.excalibur.store;

import java.lang.foreign.MemorySegment;
import java.util.logging.Level;

/**
 * The routed read, write and delete paths: resolving a key to a segment, and coping with the
 * directory changing while that is happening.
 *
 * <p>These four operations share one problem, which is why they share a file. A key is routed
 * by hash to a directory slot, and between resolving the slot and acting on the segment in it
 * a split may publish - retiring the segment the operation is holding and re-pointing the slot
 * at a child. Every path here answers that the same way: read the volatile
 * {@link RoutingSnapshot} once into a local so the mask and the slot agree, act, and on
 * {@link SegmentRetiredException} go round again against the directory as it now is. A
 * mutation that arrives while the split is still copying gets {@link SegmentSplittingException}
 * instead, and waits for the split to publish or be abandoned before going round. The loop is
 * bounded because each retry follows a split that has already settled, so a later pass
 * resolves; an unbounded loop would turn a routing bug into a hang instead of an error.</p>
 *
 * <h2>Capacity is not an error here</h2>
 * <p>A write that finds its segment full does not fail: it asks the split worker for an
 * immediate split, waits for the children to publish, and routes again - so the child it lands
 * in is the one the directory names, found by the same path as any other write, and a child
 * that is itself full is split in turn. Only when no split worker is running, the split does not
 * publish in time, or the split would widen the directory past its limit does the caller see
 * {@link SegmentAtCapacityException} and get to decide about retrying; an entry too large for
 * any segment is refused outright, since no split can make room for it.</p>
 *
 * <p><strong>Thread safety:</strong> stateless apart from the engine it acts on. No lock is
 * taken on any path; the only shared read is the volatile routing snapshot, and the only
 * shared writes are to the engine's atomic counters and its catalog, both of which are
 * thread-safe in their own right.</p>
 */
final class EngineWrites {

    /**
     * How many times a routed operation may re-resolve before giving up.
     *
     * <p>Each retry follows a split that has already published, so one is normally enough;
     * the bound is there so a routing bug surfaces as an error rather than a hang.</p>
     */
    private static final int MAX_ROUTING_RETRIES = 8;

    /**
     * How long a write blocked on capacity waits for a split to publish, in ms.
     */
    private static final long SPLIT_WAIT_TIMEOUT_MS = 5000;

    /** The engine these operations route through. */
    private final StorageEngine engine;

    /**
     * Binds these paths to their engine.
     *
     * @param engine The owning engine
     */
    EngineWrites(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Routes a write to the segment that owns the key and reports where it landed.
     *
     * <h2>Why the loop is bounded</h2>
     * <p>The directory can change under a write. Each retry follows a publication that has
     * already happened - a segment that reports itself retired has been replaced in the
     * directory this loop re-reads, so a second pass resolves the child. One retry is normally
     * enough; the bound is there so that a routing bug surfaces as an error rather than as a
     * hang.</p>
     *
     * <h2>Why every capacity signal is treated the same</h2>
     * <p>Load factor, an exhausted data region and an unsatisfiable probe chain all arrive as
     * {@link SegmentCapacityExceededException}, and all three mean the same thing: split and
     * retry. Classifying them by message would silently miss the data-region case, which is
     * the one vector payloads hit first.</p>
     *
     * <h2>Why the terms are hashed here</h2>
     * <p>The value's chunk-text terms are counted into the sketch of whichever segment accepts
     * the write, under that segment's write lock. Hashing them here, once and before any lock,
     * keeps the tokenising out of the lock and out of every retry.</p>
     *
     * @param key        The key
     * @param value      The value
     * @param termHashes The value's {@linkplain SegmentTermSketch#termHashes(byte[]) term hashes}
     * @return The catalog index of the segment the key was written to
     * @see StorageEngine#putReportingSegment(byte[], byte[])
     */
    long putReportingSegment(byte[] key, byte[] value, long[] termHashes) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }

        if (!engine.started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = com.eonmux.excalibur.store.hash.XxHash64.hash(key);

        // Bounded, because each retry follows a publication that has already happened: a
        // segment that reports itself retired has been replaced in the directory this loop
        // re-reads, so a second pass resolves the child. Looping without a bound would turn a
        // routing bug into a hang.
        for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
            RoutingSnapshot snapshot = engine.routing;
            int segmentIndex = snapshot.slotFor(keyHash);
            if (segmentIndex < 0) {
                throw new IllegalStateException("No segments available - call start() first");
            }

            Segment segment = snapshot.segmentFor(keyHash);
            if (segment == null) {
                // Create segment at this index only if it doesn't exist
                segment = engine.registry.createSegment(segmentIndex);
            }

            try {
                segment.put(key, value, termHashes);

                // Update catalog entry count only - don't change lifecycle state
                // Segments remain in INDEXING state during writes
                long catalogIndex = engine.routingTable.catalogIndexOf(segment, segmentIndex);
                engine.catalog.updateEntryCount(catalogIndex, 1);

                // Increment visibility epoch for consistent reads
                engine.visibilityTracker.advanceEpoch();

                engine.statistics.incrementWriteOperations();
                return catalogIndex;
            } catch (SegmentCapacityExceededException e) {
                // Every capacity signal - load factor, exhausted data region, or an
                // unsatisfiable probe chain - is handled the same way: split and retry.
                // Classifying these by exception message would silently miss the data
                // region case, which is the one vector payloads hit first.
                splitForRoom(key, value, segment, segmentIndex);
            } catch (SegmentSplittingException e) {
                // Another split of this segment is copying it into children. The write belongs
                // in whichever child owns the key once it publishes, or back here if it is
                // abandoned; the directory says which once the split has settled.
                awaitSplitSettled(segment, segmentIndex);
            } catch (SegmentRetiredException e) {
                // The split published while this write was in flight. The key belongs to one of
                // the children now; writing it here would land in a file nothing can reach.
                StorageEngine.LOGGER.log(Level.FINE,
                    "Re-routing a write after the target segment was retired by a split");
            }
        }

        throw new IllegalStateException(
            "Could not route a write after " + MAX_ROUTING_RETRIES
                + " attempts: the directory kept changing underneath it");
    }

    /**
     * Makes room for a write its segment refused for capacity: splits the segment and waits
     * for the children to publish, so the caller can route the write again.
     *
     * <p>Routing again, rather than writing into the child the parent's split bit names, is
     * what lets a write survive whatever happens next: the child it belongs in may be full in
     * turn, or already splitting, and the routing loop treats both as it treats the parent.</p>
     *
     * <h3>What splitting cannot fix</h3>
     * <p>Two cases are refused before any split is asked for, because a split would not make
     * room and each one it made would still cost the directory. An entry larger than an empty
     * segment of this geometry can hold never fits any child a split produces, and splitting
     * for it again on every retry used to deepen the directory until the heap ran out; it is
     * refused as an argument error. A segment whose split would widen the directory past its
     * limit gets {@link RoutingDirectoryFullException} straight away rather than after a wait
     * for a split that the worker will refuse.</p>
     *
     * @param key          The key being written
     * @param value        The value being written
     * @param segment      The segment that is at capacity
     * @param segmentIndex The slot the key routed to
     * @throws IllegalArgumentException if no segment of this geometry could hold the entry
     * @throws RoutingDirectoryFullException if the split would widen the directory past its
     *         limit
     * @throws SegmentAtCapacityException if there is no split worker, or the split does not
     *         publish in time
     */
    private void splitForRoom(byte[] key, byte[] value, Segment segment, int segmentIndex) {
        if (!segment.fitsWhenEmpty(key, value)) {
            throw new IllegalArgumentException("An entry of " + key.length + " key bytes and "
                + value.length + " value bytes can never fit in a segment: it is larger than the "
                + "whole data region of an empty one, and splitting would only produce more "
                + "segments of the same size");
        }
        if (engine.splitWorker == null || !engine.splitWorker.isRunning()) {
            // No split worker available - cannot handle capacity automatically
            throw new SegmentAtCapacityException(
                "Segment " + segmentIndex + " at capacity. No automatic splitting available. " +
                "Ensure StorageEngine is started to enable automatic capacity management.");
        }

        // The table's answer, taken now so a refusal reaches this writer at once. The worker
        // asks again when the split runs, so this is advisory for the bit itself.
        int expectedSplitBit = engine.routingTable.splitBitIfRouted(segment);
        if (expectedSplitBit < 0) {
            // Another thread's split replaced the segment since this write found it full; the
            // directory already names the child the key belongs in.
            return;
        }
        engine.splitWorker.requestSplit(segment, expectedSplitBit);

        awaitSplitComplete(segment, segmentIndex);
    }

    /**
     * Waits for a split of the given segment to publish its children.
     *
     * @param segment      The segment being split
     * @param segmentIndex The segment's directory index, for error messages
     * @throws SegmentAtCapacityException if the split does not complete in time
     */
    private void awaitSplitComplete(Segment segment, int segmentIndex) {
        long deadline = System.currentTimeMillis() + SPLIT_WAIT_TIMEOUT_MS;
        while (!segment.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
                throw new SegmentAtCapacityException(
                    "Segment " + segmentIndex + " at capacity. Split in progress but not complete. " +
                    "Please retry the operation.");
            }
            pauseForSplit();
        }
    }

    /**
     * Waits for a split already running on the given segment to publish or be abandoned.
     *
     * <p>Either outcome lets the operation go on: a published split retires the segment and the
     * directory now names the child that owns the key, and an abandoned one returns the segment
     * to service.</p>
     *
     * @param segment      The segment being split
     * @param segmentIndex The slot the key routed to, for error messages
     * @throws SegmentAtCapacityException if the split does not settle in time
     */
    private void awaitSplitSettled(Segment segment, int segmentIndex) {
        long deadline = System.currentTimeMillis() + SPLIT_WAIT_TIMEOUT_MS;
        while (segment.isSplitting()) {
            if (System.currentTimeMillis() > deadline) {
                throw new SegmentAtCapacityException(
                    "Segment " + segmentIndex + " is being split and the split did not finish "
                        + "within " + SPLIT_WAIT_TIMEOUT_MS + "ms. Please retry the operation.");
            }
            pauseForSplit();
        }
    }

    /**
     * Sleeps briefly between checks on a split's progress.
     *
     * @throws SegmentAtCapacityException if interrupted, with the interrupt restored
     */
    private static void pauseForSplit() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SegmentAtCapacityException(
                "Interrupted while waiting for segment split", ie);
        }
    }

    /**
     * Resolves a key to the segment that owns it and reads it there.
     *
     * <p>One read of the volatile routing snapshot, so the mask and the slot agree. No retry
     * loop: a read that resolves the pre-split parent still finds the key, because the parent
     * holds every key both children were built from until it is retired.</p>
     *
     * @param key The key
     * @return The value slice, or null when absent
     * @see StorageEngine#get(byte[])
     */
    MemorySegment get(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!engine.started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = com.eonmux.excalibur.store.hash.XxHash64.hash(key);

        // One read of the directory, so the mask and the slot agree.
        Segment segment = engine.routing.segmentFor(keyHash);
        if (segment == null) {
            return null;
        }

        engine.statistics.incrementReadOperations();
        return segment.get(key);
    }

    /**
     * Reads a key and stamps the result with the segment's own modification epoch.
     *
     * <p>The per-segment epoch, not the global visibility epoch: {@link ZeroCopySlice#isValid()}
     * compares against the counter the segment increments, so stamping the global one would
     * make every slice look stale as soon as any other segment was written.</p>
     *
     * @param key The key
     * @return A slice carrying the epoch, or a not-found slice
     * @see StorageEngine#getWithEpoch(byte[])
     */
    ZeroCopySlice getWithEpoch(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!engine.started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = com.eonmux.excalibur.store.hash.XxHash64.hash(key);

        Segment segment = engine.routing.segmentFor(keyHash);
        if (segment == null) {
            return ZeroCopySlice.notFound(0, 0, null);
        }

        // Delegate to segment's getWithEpoch which captures the per-segment epoch
        // This ensures ZeroCopySlice.isValid() compares against the correct epoch
        ZeroCopySlice slice = segment.getWithEpoch(key);
        engine.statistics.incrementReadOperations();
        return slice;
    }

    /**
     * Tombstones a key in the segment that owns it.
     *
     * <p>Retried on the same bounded loop as a write, and for a sharper reason: tombstoning a
     * retired parent would report success for a key that stays live in whichever child now
     * owns it.</p>
     *
     * @param key The key
     * @return true if the key was found and tombstoned
     * @see StorageEngine#delete(byte[])
     */
    boolean delete(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!engine.started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = com.eonmux.excalibur.store.hash.XxHash64.hash(key);

        for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
            RoutingSnapshot snapshot = engine.routing;
            int segmentIndex = snapshot.slotFor(keyHash);
            if (segmentIndex < 0) {
                return false;
            }

            Segment segment = snapshot.segmentFor(keyHash);
            if (segment == null) {
                return false;
            }

            try {
                if (segment.delete(key)) {
                    // Only update entry count, don't change lifecycle state
                    // Entry tombstones are handled separately from segment lifecycle
                    long catalogIndex = engine.routingTable.catalogIndexOf(segment, segmentIndex);
                    engine.catalog.updateEntryCount(catalogIndex, -1);
                    engine.catalog.recordTombstone(catalogIndex);
                    engine.statistics.incrementDeleteOperations();
                    engine.visibilityTracker.advanceEpoch();
                    return true;
                }
                return false;
            } catch (SegmentSplittingException e) {
                // A tombstone applied now would be missing from both children; once the split
                // settles, the directory names the segment that owns the key.
                awaitSplitSettled(segment, segmentIndex);
            } catch (SegmentRetiredException e) {
                // Tombstoning the retired parent reported success for a key that stays live in
                // whichever child now owns it.
                StorageEngine.LOGGER.log(Level.FINE,
                    "Re-routing a delete after the target segment was retired by a split");
            }
        }

        throw new IllegalStateException(
            "Could not route a delete after " + MAX_ROUTING_RETRIES
                + " attempts: the directory kept changing underneath it");
    }
}
