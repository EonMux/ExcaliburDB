package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.worker.SegmentGraphRebuilder;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a split leaves for this shard to finish once the storage engine has published it.
 *
 * <p>By the time the engine calls back, both children are renamed, given a write log and a
 * graph, and routed to. Nothing has told this shard: the parent's registrations describe a
 * segment that serves no key any more, and the children are registered with nobody. The
 * reconciliation sweep would converge on all of that within a second, which is a second in
 * which a query routes to a child with no graph and no filter and simply finds nothing.</p>
 *
 * <p>Two repairs here cannot wait for that sweep at all, because it would bake the damage in
 * rather than fix it: the left child inherits the parent's HNSW file and with it an
 * integration checkpoint measured against a log the split has just replaced, and both children
 * hold entries that reached no graph, because vectors only ever reach one through a write log
 * and theirs is brand new and empty.</p>
 *
 * <p>Nothing here may throw. The split is already published when this runs, and the worker
 * treats a listener failure as a reason to discard children that keys already route to.</p>
 */
final class SplitBookkeeping {

    private static final Logger LOGGER = LoggerFactory.getLogger(SplitBookkeeping.class);

    private final SegmentRegistry registry;

    /**
     * Creates the split handler for one shard.
     *
     * @param registry This shard's segment registrations
     */
    SplitBookkeeping(SegmentRegistry registry) {
        this.registry = registry;
    }

    /**
     * Called when a segment split has been published by the storage engine.
     *
     * <p>The engine has already renamed both children to their canonical names, given
     * each a write log and an HNSW graph, and re-pointed the routing directory. What is
     * left is this shard's own bookkeeping: the parent's registrations describe a segment
     * that no longer serves any key, and the children are registered with nobody.</p>
     *
     * <p>This method must not throw: the split is already published by the time it runs,
     * and the worker treats a listener failure as a reason to discard the children.</p>
     *
     * @param parent     The segment that was split
     * @param leftChild  The child holding the entries whose split bit is 0
     * @param rightChild The child holding the entries whose split bit is 1
     */
    void onSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
        try {
            long parentId = registry.registeredIdOf(parent);
            if (parentId >= 0) {
                registry.unregister(parentId);
            }

            installSplitChild(leftChild);
            installSplitChild(rightChild);

            LOGGER.info("Split published for segment {}: children {} and {} registered",
                parentId, leftChild.path(), rightChild.path());
        } catch (RuntimeException e) {
            // The split is already published in the routing directory by the time this
            // runs, so failing it back to the worker would discard children that keys
            // already route to. The reconciliation sweep repairs the registrations.
            LOGGER.error("Split bookkeeping failed for parent {}: {}. The registry will be "
                + "reconciled by the next sweep.", parent.path(), e.getMessage(), e);
        }
    }

    /**
     * Registers one split child under the directory index it now occupies.
     *
     * @param child The child segment
     */
    private void installSplitChild(Segment child) {
        // First, and before the directory check: the left child inherits the parent's HNSW file
        // and with it the parent's integration checkpoint, measured against a write log that
        // prepareSplitChild has just replaced with an empty one. Doing this after the early
        // return below would leave a child that is not yet in the directory holding a stale
        // checkpoint, and the reconciliation sweep that eventually registers it would bake that
        // in -- the same silent failure, reached a few seconds later.
        TailOffsetTracker tracker = child.getTailOffsetTracker();
        IntegrationCheckpoint.rebaseIfStale(child, tracker, "a split");

        // A split gives each child a share of the parent's entries and a brand new, empty log.
        // Vectors only ever reach a graph through that log, so without this the child's data is
        // retrievable by key and invisible to vector search until something re-ingests it -
        // which nothing does.
        HnswGraph childGraph = child.getHnswGraph();
        if (childGraph != null && childGraph.nodeCount() == 0 && child.entryCount() > 0) {
            SegmentGraphRebuilder.rebuild(child, childGraph, tracker);
        }

        long childId = registry.directoryIndexOf(child);
        if (childId < 0) {
            LOGGER.warn("Split child {} is not in the storage directory; it will be picked up "
                + "by the next reconciliation sweep", child.path());
            return;
        }

        registry.register(childId, child, child.getHnswGraph(), tracker);
    }
}
