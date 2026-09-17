package excalibur.shard;

import excalibur.shard.visibility.EpochIsolationValidator;
import excalibur.shard.worker.HnswCompactionWorker;
import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What has to be forgotten, or retired, after an HNSW graph is rebuilt.
 *
 * <p>A graph compaction re-inserts the surviving nodes into a fresh graph, so every node id
 * changes. Node-level visibility records are keyed by node id, so keeping them across a
 * rebuild maps live nodes onto the epochs of unrelated dead ones - a query would then filter
 * out rows it is entitled to see, or admit rows it is not, with nothing to indicate why.</p>
 *
 * <p>A failed rebuild is the more dangerous case: it can leave the graph closed while the
 * workers go on feeding it, which turns every subsequent integration cycle into an exception
 * on a background thread. The segment is retired from those workers instead; its stored
 * entries stay readable through the storage engine.</p>
 */
final class GraphCompactionBookkeeping implements HnswCompactionWorker.CompactionListener {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(GraphCompactionBookkeeping.class);

    private final SegmentRegistry registry;
    private final ShardWorkers workers;
    private final EpochIsolationValidator epochIsolation;

    /**
     * Creates the graph compaction listener for one shard.
     *
     * @param registry       This shard's segment registrations
     * @param workers        The workers that write into the graphs
     * @param epochIsolation The node-level visibility records that a rebuild invalidates
     */
    GraphCompactionBookkeeping(SegmentRegistry registry, ShardWorkers workers,
                               EpochIsolationValidator epochIsolation) {
        this.registry = registry;
        this.workers = workers;
        this.epochIsolation = epochIsolation;
    }

    /**
     * Called after a segment's HNSW graph was compacted.
     *
     * <p>Compaction re-inserts the surviving nodes into a fresh graph, so every node ID
     * changes. Node visibility records are keyed by node ID, so retaining them would map
     * live nodes to unrelated epochs; they are dropped, which is correct because every
     * surviving node predates the compaction and is visible to every query.</p>
     *
     * @param graph        The compacted graph
     * @param nodesRemoved Number of tombstoned nodes removed
     */
    @Override
    public void onGraphCompacted(HnswGraph graph, long nodesRemoved) {
        long segmentId = registry.idOfGraph(graph);
        if (segmentId < 0) {
            LOGGER.debug("Compacted graph {} belongs to no registered segment", graph.path());
            return;
        }

        epochIsolation.resetSegmentNodeEpochs(segmentId);
        LOGGER.info("HNSW graph for segment {} compacted: {} nodes removed, node visibility "
            + "records reset", segmentId, nodesRemoved);
    }

    /**
     * Called when an HNSW graph compaction attempt failed.
     *
     * <p>A failure that released the graph's mapping leaves it closed. Continuing to feed
     * such a graph makes every integration cycle throw, so the segment is retired from the
     * workers that write to it. The segment's stored entries stay readable through the
     * storage engine.</p>
     *
     * @param graph   The graph that failed to compact
     * @param failure The failure
     */
    @Override
    public void onGraphCompactionFailed(HnswGraph graph, Exception failure) {
        long segmentId = registry.idOfGraph(graph);
        if (segmentId < 0 || !graph.isClosed()) {
            return;
        }

        SegmentIndexInfo info = registry.get(segmentId);
        if (info != null) {
            workers.hnsw().unregisterSegment(info.segment());
            workers.warming().unregisterSegment(segmentId);
        }
        LOGGER.error("HNSW graph for segment {} was left closed by a failed compaction; the "
            + "segment is no longer indexed and is unsearchable until it is reopened: {}",
            segmentId, failure.getMessage());
    }
}
