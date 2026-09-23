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
 * {@link SegmentRetiredException} go round again against the directory as it now is. The loop
 * is bounded because each retry follows a publication that has already completed, so a second
 * pass resolves; an unbounded loop would turn a routing bug into a hang instead of an
 * error.</p>
 *
 * <h2>Capacity is not an error here</h2>
 * <p>A write that finds its segment full does not fail: it asks the split worker for an
 * immediate split, waits for the children to publish, and writes into the child chosen with
 * the bit the split actually partitioned on - read back from the parent rather than derived
 * independently, because deriving it is how a split loses half its keys. Only when no split
 * worker is running, or the split does not publish in time, does the caller see
 * {@link SegmentAtCapacityException} and get to decide about retrying.</p>
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
     * @param key   The key
     * @param value The value
     * @return The catalog index of the segment the key was written to
     * @see StorageEngine#putReportingSegment(byte[], byte[])
     */
    long putReportingSegment(byte[] key, byte[] value) {
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
                segment.put(key, value);

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
                return putWithSplitHandling(key, value, keyHash, segment, segmentIndex);
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
     * Handles a put operation when the target segment has reached capacity.
     *
     * <p>This method implements automatic capacity management by:</p>
     * <ol>
     *   <li>Requesting an immediate split of the full segment</li>
     *   <li>Waiting for the split to complete (up to 5 seconds)</li>
     *   <li>Routing the insert to the appropriate child segment</li>
     * </ol>
     *
     * <p><strong>Domain Correctness:</strong> the child is selected with the very bit
     * the split partitioned on, read back from the parent via {@link Segment#splitBit()}.
     * Deriving a bit independently here is what makes a split lose half its keys.</p>
     *
     * @param key The key to insert
     * @param value The value to insert
     * @param keyHash The pre-computed key hash
     * @param segment The segment that is at capacity
     * @param segmentIndex The segment index
     * @throws SegmentAtCapacityException if split cannot complete in time
     */
    private long putWithSplitHandling(byte[] key, byte[] value, long keyHash,
                                       Segment segment, int segmentIndex) {
        if (engine.splitWorker == null || !engine.splitWorker.isRunning()) {
            // No split worker available - cannot handle capacity automatically
            throw new SegmentAtCapacityException(
                "Segment " + segmentIndex + " at capacity. No automatic splitting available. " +
                "Ensure StorageEngine is started to enable automatic capacity management.");
        }

        // The bit a doubling of the routing table newly exposes. The worker uses the
        // routing table's current value, so this is advisory only.
        int expectedSplitBit = Integer.numberOfTrailingZeros(engine.routingSegmentCount);
        engine.splitWorker.requestSplit(segment, expectedSplitBit);

        awaitSplit(segment, segmentIndex);

        // Route to the child using the bit the split actually partitioned on.
        int splitBit = segment.splitBit();
        boolean goesRight = Segment.shouldGoToRightChild(keyHash, splitBit);
        Segment child = goesRight ? segment.rightChild() : segment.leftChild();

        if (child == null) {
            throw new IllegalStateException(
                "Split completed but child segment is null. Segment: " + segmentIndex);
        }

        // Insert into child segment
        child.put(key, value);

        long catalogIndex = engine.routingTable.catalogIndexOf(child, segmentIndex);
        engine.catalog.updateEntryCount(catalogIndex, 1);

        engine.visibilityTracker.advanceEpoch();
        engine.statistics.incrementWriteOperations();
        return catalogIndex;
    }

    /**
     * Waits for an in-progress split of the given segment to publish its children.
     *
     * @param segment      The segment being split
     * @param segmentIndex The segment's directory index, for error messages
     * @throws SegmentAtCapacityException if the split does not complete in time
     */
    private void awaitSplit(Segment segment, int segmentIndex) {
        long deadline = System.currentTimeMillis() + SPLIT_WAIT_TIMEOUT_MS;
        while (!segment.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
                throw new SegmentAtCapacityException(
                    "Segment " + segmentIndex + " at capacity. Split in progress but not complete. " +
                    "Please retry the operation.");
            }
            try {
                Thread.sleep(10); // Brief sleep before checking again
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new SegmentAtCapacityException(
                    "Interrupted while waiting for segment split", ie);
            }
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
