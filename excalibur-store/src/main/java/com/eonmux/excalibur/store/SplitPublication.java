package com.eonmux.excalibur.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Turning two freshly built children into the two segments the engine routes to.
 *
 * <p>{@link SplitWorker} does the copying; it cannot do any of this. The children exist under
 * temporary build names, with no write log, holding whatever index files their parent's name
 * happened to open, and the worker has no idea which directory indices they belong at -
 * that is the directory's knowledge, and the directory is the engine's. So publication is a
 * separate step, and it is the step with all of the hazards in it.</p>
 *
 * <h2>The order is the argument</h2>
 * <p>Every part of {@link #handleSplitComplete(Segment, Segment, Segment)} is sequenced so
 * that a reader arriving at any instant reaches a segment that holds its key, and so that a
 * process that dies mid-way leaves something recoverable behind. The right child is renamed
 * first because its target name is unused, so a failure there changes nothing. The left
 * child's target is the parent's own file, so that move goes last - and even if the process
 * dies between the two, the parent file still holds every key both children were built from.
 * The upper half of the directory is filled before the mask is widened, and the parent's own
 * slots are repointed only after that, so no reader can ever observe a widened mask over a
 * directory that has not been filled for it.</p>
 *
 * <h2>Two mappings over one file is not survivable</h2>
 * <p>The left child takes over the parent's file names, the HNSW graph file among them. If
 * both the parent's graph and the child's are open on it, the parent's {@code close()} forces
 * its own stale pages over everything the child has written since. Releasing the parent's
 * graph before the child opens one is what keeps retiring the parent from corrupting the
 * child - and the parent must be retired, because until it was, its descriptor, its mapping
 * and its write log stayed open for the life of the process, and the left child's rename had
 * already unlinked its file, so the disk blocks went with them. One of each, per split,
 * forever.</p>
 *
 * <h2>What happens outside the lock</h2>
 * <p>Only the directory mutation holds {@code StorageEngine.segmentLock}. Persisting the
 * directory, notifying observers and retiring the parent all happen after it is released:
 * observers re-register index structures against the children, which means opening graphs,
 * and that work must not run while every routing decision in the engine is blocked.
 * An observer that throws is logged and skipped - by that point the split is durable and the
 * directory already points at the children, so unwinding is not possible and letting one
 * observer's failure abort the notification would leave the rest permanently unaware of a
 * split that did happen.</p>
 */
final class SplitPublication {

    /** The engine whose directory these splits are published into. */
    private final StorageEngine engine;

    /**
     * Observers notified after a split is published. Copy-on-write: splits are rare and
     * notification happens while readers may be registering, so a snapshot iterator is worth
     * more than write throughput here.
     */
    private final List<SplitWorker.SplitListener> splitObservers = new CopyOnWriteArrayList<>();

    /**
     * Binds this publication path to its engine.
     *
     * @param engine The owning engine
     */
    SplitPublication(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Publishes a completed split into the routing directory.
     *
     * <p>Called by the {@link SplitWorker} once both children are built. This method owns
     * everything the worker cannot do, because only the directory knows the children's
     * indices: moving each child from its temporary build name onto the canonical
     * {@code segment_<index>.dat} name that {@link SegmentDiscovery} matches, handing the
     * parent's slots to the children, and doubling the directory when that takes a new
     * slot.</p>
     *
     * <h3>Extendible hashing</h3>
     * <p>The parent may be reachable from several directory indices - it has a smaller local
     * depth than the directory's global depth. Then the split bit is the lowest bit those
     * indices differ in: the ones with it clear go to the left child and the ones with it set to
     * the right, and the directory keeps its width. Only a parent reachable from a single index
     * {@code j} is as deep as the directory; then the directory doubles, the split bit is the one
     * the doubling exposes, {@code j} goes to the left child and {@code j + oldCount} to the
     * right, and every other index {@code i} gets a buddy pointer at {@code i + oldCount} to the
     * segment it already had, so its keys keep resolving once the mask widens.
     * {@link EngineRouting#planSplit(Segment)} decides which.</p>
     *
     * <h3>Publication order</h3>
     * <p>When the directory doubles, the new upper half is filled first, then the mask is
     * widened, and only then are the parent's own slots repointed at the left child; when it
     * does not, the right child's slots are repointed before the left child's. At every instant a
     * reader - using either the old mask or the new one - reaches a segment that holds its
     * key, and the routing snapshot readers actually use is replaced only once, at the end. Both
     * children's catalog rows are written before any slot points at them, so a reader that
     * reaches a child also finds its row. If publication fails the directory is left untouched
     * and the exception propagates, which makes the worker discard the children and return the
     * parent to service.</p>
     *
     * <h3>Refused publication</h3>
     * <p>A child's index held in the catalog by a segment created for a source, rather than by
     * a routing segment, is refused with an {@link IllegalStateException} before any file is
     * moved; see {@link SegmentCatalog#checkRoutingRegistration(long)}. Adopting that record
     * would give the live child another source's lifecycle state - often TOMBSTONED, which
     * hides it from every query.</p>
     *
     * @param parent     The parent segment that was split
     * @param leftChild  The left child (entries whose split bit is 0)
     * @param rightChild The right child (entries whose split bit is 1)
     * @throws IllegalStateException if a child's index is held by a created segment, or if a
     *         child cannot be moved into place
     */
    void handleSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
        long[] published;
        synchronized (engine.segmentLock) {
            published = publishUnderLock(parent, leftChild, rightChild);
        }

        StorageEngine.LOGGER.info(String.format(
            "Split published: parent=%s -> left=%d, right=%d, routingCount=%d",
            parent.path(), published[0], published[1], engine.routingSegmentCount));

        finishPublication(parent, leftChild, rightChild);
    }

    /**
     * The part of publication that mutates the directory. The caller holds
     * {@code StorageEngine.segmentLock}.
     *
     * @param parent     The parent segment that was split
     * @param leftChild  The left child
     * @param rightChild The right child
     * @return The left and right indices the children were published at
     * @throws IllegalStateException if the publication is refused, or a child cannot be moved
     *                               into place
     */
    private long[] publishUnderLock(Segment parent, Segment leftChild, Segment rightChild) {
        // The same plan the worker partitioned by: it depends only on the parent's own slots,
        // and nothing but this publication re-points those.
        EngineRouting.SplitPlan plan = engine.routingTable.planSplit(parent);

        long leftIndex = plan.parentSlots().get(0);
        long rightIndex = plan.rightIndex();

        // Before anything is moved: the catalog must be able to take both children at
        // their indices. The only thing that can stop it is a segment created for a source
        // under an older catalog at one of those ids, and once the renames below have run
        // there is no way back - so the refusal has to come while the parent is untouched.
        engine.catalog.checkRoutingRegistration(leftIndex);
        engine.catalog.checkRoutingRegistration(rightIndex);

        // Step 1: give the children discoverable identities before publishing them.
        // The right child goes first: its target name is unused, so a failure there
        // leaves everything untouched. The left child's target is the parent's own
        // file, so that move is done last - and even if the process dies between the
        // two, the parent file still holds every key both children were built from.
        prepareSplitChild(rightChild, rightIndex);

        // The left child is about to take over the parent's file names, the HNSW graph
        // file among them. Two graphs mapping one file is not survivable: the parent's
        // close() forces its own stale pages over whatever the child has written since.
        // Releasing the parent's graph here, before the child opens one, is what keeps the
        // retirement below from corrupting the child.
        com.eonmux.excalibur.vector.hnsw.HnswGraph parentGraph = parent.detachHnswGraph();
        if (parentGraph != null) {
            try {
                parentGraph.close();
            } catch (Exception e) {
                StorageEngine.LOGGER.log(java.util.logging.Level.WARNING,
                    "Failed to close the parent's HNSW graph during split of "
                        + parent.path(), e);
            }
        }

        prepareSplitChild(leftChild, leftIndex);

        // Catalog rows go in before either child becomes reachable, so every reader that
        // routes to a child also finds its row; a segment routed to without one is skipped
        // by the queryable-segment scans. The left child inherits the parent's entry (same
        // index), which may be in any lifecycle state, so it is re-pointed rather than
        // re-added.
        engine.catalog.registerFile(leftIndex, leftChild.path()).setEntryCount(leftChild.entryCount());
        engine.catalog.add(rightIndex, rightChild.path()).setEntryCount(rightChild.entryCount());

        if (plan.doubles()) {
            // Step 2: fill the new upper half. These indices are unreachable under the
            // old mask, so this is invisible to concurrent readers. The parent's buddy is
            // the right child's slot, so it is given the right child directly.
            int oldRouting = engine.routingSegmentCount;
            for (int i = 0; i < oldRouting; i++) {
                Segment current = engine.segments.get((long) i);
                if (current == null) {
                    continue;
                }
                engine.segments.put(i + (long) oldRouting,
                    current == parent ? rightChild : current);
            }

            // Step 3: widen the mask. The parent still serves its own slot and holds
            // every key that maps to it, so this is safe to publish now.
            engine.routingSegmentCount = oldRouting * 2;
        } else {
            // Step 2, without a doubling: the parent's slots with the split bit set go to
            // the right child. The parent still holds every key both children were built
            // from, so a reader that reaches either child or the parent finds its key.
            for (long slot : plan.parentSlots()) {
                if (Segment.shouldGoToRightChild(slot, plan.splitBit())) {
                    engine.segments.put(slot, rightChild);
                }
            }
        }

        // Step 4: retire the parent from the slots the left child keeps.
        for (long slot : plan.parentSlots()) {
            if (!Segment.shouldGoToRightChild(slot, plan.splitBit())) {
                engine.segments.put(slot, leftChild);
            }
        }

        engine.canonicalIndex.remove(parent);
        engine.canonicalIndex.put(leftChild, leftIndex);
        engine.canonicalIndex.put(rightChild, rightIndex);

        if (engine.splitWorker != null) {
            engine.splitWorker.unregisterSegment(parent);
        }

        if (engine.splitWorker != null && engine.splitWorker.isRunning()) {
            engine.splitWorker.registerSegment(leftChild);
            engine.splitWorker.registerSegment(rightChild);
        }

        // Increment visibility epoch to signal state change
        engine.visibilityTracker.advanceEpoch();

        engine.routingTable.publishRouting();
        return new long[] {leftIndex, rightIndex};
    }

    /**
     * Makes a published split durable, tells observers, and retires the parent.
     *
     * @param parent     The parent segment that was split
     * @param leftChild  The left child, now routed to
     * @param rightChild The right child, now routed to
     */
    private void finishPublication(Segment parent, Segment leftChild, Segment rightChild) {
        // The directory just changed shape, and the shape is not recoverable from the segment
        // file names. Written before the observers run, so a crash during that work still leaves
        // a directory describing the split that is already durable.
        engine.routingTable.persistRoutingDirectory();

        // Outside the lock, and only after publication. Observers re-register index structures
        // against the children, which means opening graphs -- work that must not run
        // while every routing decision in the engine is blocked behind segmentLock.
        notifySplitObservers(parent, leftChild, rightChild);

        // The routing table no longer references the parent, so nothing can newly resolve it and
        // it can be retired -- the same point compaction retires the segment it replaced. Until
        // this was here the parent was simply dropped: its descriptor, its mapping and its write
        // log stayed open for the life of the process, and because the left child's move
        // unlinked the parent's file, so did the disk blocks behind it. One of each, per split,
        // forever. Its graph was already released above.
        try {
            parent.close();
        } catch (RuntimeException e) {
            StorageEngine.LOGGER.log(java.util.logging.Level.WARNING,
                "Failed to retire the parent segment after splitting " + parent.path(), e);
        }
    }

    /**
     * Tells observers a split has been published.
     *
     * <p>An observer that throws is logged and skipped rather than allowed to propagate. By this
     * point the split is already durable and the routing table already points at the children;
     * unwinding is not possible, and letting one observer's failure abort the notification would
     * leave the remaining observers permanently unaware of a split that did happen.
     */
    private void notifySplitObservers(Segment parent, Segment leftChild, Segment rightChild) {
        for (SplitWorker.SplitListener observer : splitObservers) {
            try {
                observer.onSplitComplete(parent, leftChild, rightChild);
            } catch (RuntimeException e) {
                StorageEngine.LOGGER.log(java.util.logging.Level.SEVERE,
                    "Split observer failed for parent " + parent.path()
                        + "; the split stands and other observers still run", e);
            }
        }
    }

    /**
     * Moves a split child into its canonical name and completes its initialization.
     *
     * <p>A child left under its temporary build name would be invisible to
     * {@link SegmentDiscovery} on the next start, so every write routed to it after the
     * split would be lost. The rename happens while the child stays mapped: the mapping
     * and descriptor follow the inode.</p>
     *
     * @param child        The child segment to publish
     * @param segmentIndex The directory index the child will occupy
     * @throws IllegalStateException if the child cannot be moved into place
     */
    private void prepareSplitChild(Segment child, long segmentIndex) {
        Path source = child.path();
        Path target = installSplitChild(child, segmentIndex);

        if (child.writeLog() == null) {
            try {
                // The parent's log is fully integrated into the children's files, so the
                // child starts from an empty log at its own canonical name, beside the file
                // it actually ended up at.
                Path logPath = StorageEngine.walPathFor(target);
                java.nio.file.Files.deleteIfExists(logPath);
                child.attachWriteLog(new WriteLog(logPath));
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Failed to create write log for split child " + source
                        + " at index " + segmentIndex, e);
            }
        }

        engine.indexes.discardInheritedGraph(child, segmentIndex);
        engine.indexes.discardInheritedPrefilter(child, segmentIndex);
        engine.indexes.initializeHnswGraph(child, segmentIndex);
        engine.indexes.initializeVectorPrefilter(child, segmentIndex);
        alignCheckpointToLog(child, segmentIndex);
    }

    /**
     * Renames a split child onto its canonical name and returns where it landed.
     *
     * <p>A child is built beside its parent, so a placement that puts the child's index on
     * another volume asks for a rename across file stores. That rename cannot be atomic, and
     * copy-then-delete is not an option either: the child is mapped, and the mapping follows
     * the inode, so a copy would leave every subsequent write going to the file that was
     * deleted. The child therefore stays on its parent's volume, which is always a directory
     * a restart scans, and the placement's preference is logged as unmet rather than
     * silently obeyed.</p>
     *
     * @param child        The child segment, still under its build name
     * @param segmentIndex The directory index the child will occupy
     * @return The path the child now lives at
     * @throws IllegalStateException if the child cannot be renamed at all
     */
    private Path installSplitChild(Segment child, long segmentIndex) {
        Path source = child.path();
        Path target = engine.segmentPath(segmentIndex);
        if (source.equals(target)) {
            engine.locations.adopt(segmentIndex, target);
            return target;
        }

        try {
            moveIntoPlace(source, target);
        } catch (java.nio.file.AtomicMoveNotSupportedException crossStore) {
            Path beside = source.resolveSibling(SegmentLocations.fileName(segmentIndex));
            StorageEngine.LOGGER.log(Level.WARNING,
                "Split child {0} cannot be moved to {1} atomically because they are on "
                    + "different file stores; keeping it beside its parent at {2}",
                new Object[] { source, target, beside });
            try {
                moveIntoPlace(source, beside);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Failed to move split child " + source + " to " + beside, e);
            }
            child.renamedTo(beside);
            engine.locations.adopt(segmentIndex, beside);
            return beside;
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to move split child " + source + " to " + target, e);
        }

        child.renamedTo(target);
        engine.locations.adopt(segmentIndex, target);
        return target;
    }

    /**
     * Renames a file onto a canonical name, replacing whatever is there.
     *
     * <p>The term sketch sidecar at the target goes first: it describes the file the name held
     * before - the parent, for the left child - and the child's own sketch is written there on
     * its next flush.</p>
     *
     * @param source The current path
     * @param target The canonical path
     * @throws IOException if the rename fails
     */
    private static void moveIntoPlace(Path source, Path target) throws IOException {
        java.nio.file.Files.move(source, target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Brings a child's integration checkpoint back within its own write log.
     *
     * <p>The checkpoint says how far the integration worker has read; the child's log starts
     * empty. A checkpoint left pointing into the parent's discarded log is ahead of the child's
     * head, and the integration worker returns early whenever the head is at or behind it - so
     * every vector written to the child afterwards is stored, found by key, and never enters the
     * graph, which is to say never returned by a vector search. Compaction guards against
     * exactly this; the split path did not.</p>
     *
     * @param child The child being prepared
     * @param segmentIndex The child's canonical index
     */
    private void alignCheckpointToLog(Segment child, long segmentIndex) {
        long head = child.getWriteLogHead();

        com.eonmux.excalibur.vector.hnsw.HnswGraph graph = child.getHnswGraph();
        if (graph != null && graph.getLastIntegratedOffset() > head) {
            StorageEngine.LOGGER.log(Level.INFO,
                "Rebasing the HNSW checkpoint of split child {0} from {1} to {2}",
                new Object[] { segmentIndex, graph.getLastIntegratedOffset(), head });
            graph.setLastIntegratedOffset(head);
        }

        com.eonmux.excalibur.vector.hnsw.TailOffsetTracker tracker = child.getTailOffsetTracker();
        if (tracker != null && tracker.getLastIntegratedOffset() > head) {
            tracker.resetTo(head);
        }
    }

    /**
     * Registers an observer notified after each split is published.
     *
     * @param observer The observer
     */
    void addObserver(SplitWorker.SplitListener observer) {
        splitObservers.add(observer);
    }

    /**
     * Removes a previously registered observer.
     *
     * @param observer The observer
     * @return true if it was registered
     */
    boolean removeObserver(SplitWorker.SplitListener observer) {
        return splitObservers.remove(observer);
    }
}
