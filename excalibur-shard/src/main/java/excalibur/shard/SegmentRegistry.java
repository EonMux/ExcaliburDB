package excalibur.shard;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.shard.visibility.EpochIsolationValidator;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which segments this shard is indexing, and everything that has to be told when that changes.
 *
 * <p>Registration is not a boot-time snapshot. The storage engine creates a segment lazily on
 * the first write that routes to it and replaces one with two on a split, so the set drifts
 * under the orchestrator's feet; {@link #syncFromStorage()} is what converges it again. A
 * segment nobody registered is integrated into no graph, compacted by nobody and invisible to
 * epoch-based visibility - it fails silently rather than loudly, which is why the sweep exists
 * at all.</p>
 *
 * <p>Six things track a segment - the visibility tracker, the node-level epoch validator, and
 * the integration, warming, Cuckoo and compaction workers - and three of them key their
 * registrations by {@link Segment} object identity rather than by id. That split is the reason
 * this is one object: a caller that updates the id map without re-pointing the identity-keyed
 * workers leaves them polling an instance the directory has retired, and nothing reports it.</p>
 */
final class SegmentRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentRegistry.class);

    private final StorageEngine engine;
    private final ShardWorkers workers;
    private final VisibilityTracker visibility;
    private final EpochIsolationValidator epochIsolation;

    private final Map<Long, SegmentIndexInfo> byId = new ConcurrentHashMap<>();

    /**
     * Hierarchical Cuckoo filter for corpus -> cluster -> segment membership testing.
     * Used to register segments in the hierarchy and enable efficient query pruning.
     * May be null if not configured.
     */
    private volatile HierarchicalCuckooFilter hierarchicalFilter;

    /**
     * Creates a registry over the engine whose directory it converges on.
     *
     * @param engine         The storage engine that owns the segment directory
     * @param workers        The workers a registration has to reach
     * @param visibility     The epoch tracker segments become visible through
     * @param epochIsolation The node-level visibility records, scoped by segment id
     */
    SegmentRegistry(StorageEngine engine, ShardWorkers workers, VisibilityTracker visibility,
                    EpochIsolationValidator epochIsolation) {
        this.engine = engine;
        this.workers = workers;
        this.visibility = visibility;
        this.epochIsolation = epochIsolation;
    }

    /**
     * Registers a segment for index orchestration.
     *
     * <p>This registers the segment with all appropriate workers:</p>
     * <ul>
     *   <li>HNSW integration (if it has a graph)</li>
     *   <li>Cuckoo maintenance (if it has a filter)</li>
     *   <li>Cuckoo metrics (for FP rate monitoring, if it has a filter)</li>
     *   <li>Compaction worker (for tombstone cleanup)</li>
     *   <li>Warming phase worker (for PQ training during WARMING state)</li>
     *   <li>Hierarchical Cuckoo filter (for corpus → cluster → segment membership)</li>
     * </ul>
     *
     * @param segmentId The segment ID
     * @param segment   The segment
     * @param graph     The HNSW graph (can be null)
     * @param filter    The Cuckoo filter (can be null)
     * @param tracker   The tail offset tracker (can be null)
     */
    void register(long segmentId, Segment segment, HnswGraph graph,
                  CuckooFilter filter, TailOffsetTracker tracker) {
        if (segment == null) {
            return;
        }

        // Store segment index info
        byId.put(segmentId, new SegmentIndexInfo(segment, graph, filter, tracker));

        // Register with visibility tracker for epoch-based visibility
        visibility.registerSegment(segmentId, visibility.currentEpoch());

        // Register with HNSW worker if graph exists
        if (graph != null && tracker != null) {
            workers.hnsw().registerSegment(segment, graph, tracker);
            // Register with warming worker for PQ training during WARMING phase
            workers.warming().registerSegment(segmentId, segment, graph, tracker);
            // Register with HNSW compaction worker for tombstone cleanup
            workers.graphCompaction().registerGraph(graph);
        }

        // Register with Cuckoo worker if filter exists
        if (filter != null) {
            workers.cuckoo().registerFilter(segmentId, filter);
            // Register with Cuckoo metrics worker for FP rate monitoring
            workers.cuckooMetrics().registerFilter(segmentId, filter);
        }

        workers.registerForCompaction(segment);

        // Register with hierarchical Cuckoo filter for corpus → cluster → segment membership
        // This enables efficient query pruning via Stage 1 Cuckoo filter cascade
        if (hierarchicalFilter != null) {
            String segmentIdStr = String.valueOf(segmentId);
            // Use "default" cluster for segments without explicit cluster assignment
            // This allows the hierarchy to function with a single default cluster
            hierarchicalFilter.registerSegment(segmentIdStr, "default");
            LOGGER.debug("Registered segment {} in hierarchical Cuckoo filter with cluster 'default'", segmentId);
        }

        LOGGER.debug("Registered segment {} with index orchestrator", segmentId);
    }

    /**
     * Unregisters a segment from index orchestration.
     *
     * <p>Registrations keyed by segment ID are always dropped. The HNSW integration
     * worker, the store compaction worker and the HNSW compaction worker key their
     * registrations by object identity, and the routing directory can reach one
     * {@link Segment} - and one graph - from several IDs, so those are retired only once
     * the last ID referencing them is gone.</p>
     *
     * @param segmentId The segment ID
     */
    void unregister(long segmentId) {
        unregister(segmentId, true);
    }

    /**
     * Unregisters a segment, optionally keeping its place in the filter hierarchy.
     *
     * <p>An ID that is about to be re-registered with a different {@link Segment}
     * instance - a compacted replacement, or the left child of a split, which inherits
     * its parent's index - must keep its hierarchical Cuckoo filter. That filter holds
     * every term the segment contributed, and Stage 1 of the query pipeline treats a
     * filter miss as ground truth, so dropping and recreating it turns those terms into
     * empty query results until the data is ingested again.</p>
     *
     * @param segmentId           The segment ID
     * @param dropHierarchyEntry  Whether to remove the segment from the filter hierarchy
     */
    void unregister(long segmentId, boolean dropHierarchyEntry) {
        SegmentIndexInfo info = byId.remove(segmentId);
        if (info == null) {
            return;
        }

        // Unregister from visibility tracker
        visibility.unregisterSegment(segmentId);

        // Node visibility records are scoped by segment ID and must not outlive the
        // segment they describe.
        epochIsolation.unregisterSegment(segmentId);

        // Unregister from workers keyed by segment ID
        workers.warming().unregisterSegment(segmentId);
        workers.cuckoo().unregisterFilter(segmentId);
        workers.cuckooMetrics().unregisterFilter(segmentId);

        if (!isSegmentRegisteredElsewhere(info.segment())) {
            workers.hnsw().unregisterSegment(info.segment());
            workers.unregisterFromCompaction(info.segment());
        }
        if (info.graph() != null && !isGraphRegisteredElsewhere(info.graph())) {
            workers.graphCompaction().unregisterGraph(info.graph());
        }

        // Unregister from hierarchical Cuckoo filter
        if (dropHierarchyEntry && hierarchicalFilter != null) {
            String segmentIdStr = String.valueOf(segmentId);
            hierarchicalFilter.unregisterSegment(segmentIdStr);
            LOGGER.debug("Unregistered segment {} from hierarchical Cuckoo filter", segmentId);
        }

        LOGGER.debug("Unregistered segment {} from index orchestrator", segmentId);
    }

    /**
     * Returns whether another registration still references a segment instance.
     *
     * @param segment The segment instance
     * @return true if some remaining registration holds this exact instance
     */
    private boolean isSegmentRegisteredElsewhere(Segment segment) {
        for (SegmentIndexInfo info : byId.values()) {
            if (info.segment() == segment) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether another registration still references a graph instance.
     *
     * @param graph The graph instance
     * @return true if some remaining registration holds this exact instance
     */
    private boolean isGraphRegisteredElsewhere(HnswGraph graph) {
        for (SegmentIndexInfo info : byId.values()) {
            if (info.graph() == graph) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reconciles the orchestrator's registrations with the storage engine's directory.
     *
     * <p>The engine owns the segment directory and changes it without telling anyone: it
     * creates a segment lazily on the first write that routes to it, and a split replaces
     * one segment with two children. A segment the orchestrator does not know about is
     * integrated into no HNSW graph, compacted by nobody and invisible to epoch-based
     * visibility, so the registry has to converge on the directory rather than being
     * populated once at boot.</p>
     *
     * <p>Three cases are handled: an index the orchestrator has never seen is registered;
     * an index whose {@link Segment} instance was replaced is re-registered, because the
     * workers key their registrations by object identity; and an index that has left the
     * directory is retired.</p>
     *
     * @return Number of registrations added, replaced or retired
     */
    int syncFromStorage() {
        Map<Long, Segment> live = engine.getSegments();
        int changes = 0;

        for (Map.Entry<Long, Segment> entry : live.entrySet()) {
            long segmentId = entry.getKey();
            Segment segment = entry.getValue();

            SegmentIndexInfo info = byId.get(segmentId);
            if (info != null && info.segment() == segment) {
                continue;
            }

            CuckooFilter filter = info != null ? info.filter() : null;
            if (info != null) {
                // The ID is re-registered immediately below, so its filter hierarchy
                // entry - which still describes this ID's terms - is kept.
                unregister(segmentId, false);
            }
            register(segmentId, segment, segment.getHnswGraph(), filter,
                segment.getTailOffsetTracker());
            changes++;
        }

        for (Long segmentId : List.copyOf(byId.keySet())) {
            if (!live.containsKey(segmentId)) {
                unregister(segmentId);
                changes++;
            }
        }

        if (changes > 0) {
            LOGGER.info("Segment registry reconciled with storage directory: {} change(s), "
                + "{} segment(s) registered", changes, byId.size());
        }
        return changes;
    }

    /**
     * Returns the number of registered segments.
     *
     * @return Registered segment count
     */
    int size() {
        return byId.size();
    }

    /**
     * Returns the ID a segment instance is registered under.
     *
     * @param segment The segment instance
     * @return The segment ID, or -1 if the instance is not registered
     */
    long registeredIdOf(Segment segment) {
        for (Map.Entry<Long, SegmentIndexInfo> entry : byId.entrySet()) {
            if (entry.getValue().segment() == segment) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Returns the ID of the registered segment owning a graph instance.
     *
     * @param graph The graph instance
     * @return The segment ID, or -1 if no registered segment holds it
     */
    long idOfGraph(HnswGraph graph) {
        for (Map.Entry<Long, SegmentIndexInfo> entry : byId.entrySet()) {
            if (entry.getValue().graph() == graph) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Returns the directory index the storage engine holds a segment instance at.
     *
     * @param segment The segment instance
     * @return The directory index, or -1 if the engine does not hold this instance
     */
    long directoryIndexOf(Segment segment) {
        for (Map.Entry<Long, Segment> entry : engine.getSegments().entrySet()) {
            if (entry.getValue() == segment) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Returns the registration held under an id.
     *
     * @param segmentId The segment ID
     * @return The registration, or null if the id is not registered
     */
    SegmentIndexInfo get(long segmentId) {
        return byId.get(segmentId);
    }

    /**
     * Installs a registration under an id, replacing whatever was there.
     *
     * @param segmentId The segment ID
     * @param info      The registration
     */
    void put(long segmentId, SegmentIndexInfo info) {
        byId.put(segmentId, info);
    }

    /**
     * Drops a registration only if the id still holds exactly the one given.
     *
     * <p>Conditional because the caller is repairing an entry it read earlier: between the read
     * and the removal a reconciliation sweep may have re-registered that id against the live
     * instance, and dropping it then would retire a registration that is correct.</p>
     *
     * @param segmentId The segment ID
     * @param info      The registration the caller expects to find
     */
    void removeIfPresent(long segmentId, SegmentIndexInfo info) {
        byId.remove(segmentId, info);
    }

    /**
     * Installs the hierarchical Cuckoo filter and puts every known segment into it.
     *
     * <p><strong>CRITICAL:</strong> the back-fill is not a convenience. Stage 1 of the query
     * pipeline treats a filter miss as ground truth, so a segment registered before the filter
     * arrived - which is every segment on a node that configures the filter after boot - would
     * be pruned out of every query it should have answered.</p>
     *
     * @param filter The hierarchical Cuckoo filter (may be null)
     */
    void setHierarchicalFilter(HierarchicalCuckooFilter filter) {
        this.hierarchicalFilter = filter;
        this.workers.cuckoo().setHierarchicalFilter(filter);

        // Register all existing segments with the new hierarchical filter
        // This is CRITICAL for Stage 1 Cuckoo filter cascade to function correctly
        if (filter != null) {
            int registeredCount = 0;
            for (Map.Entry<Long, SegmentIndexInfo> entry : byId.entrySet()) {
                long segmentId = entry.getKey();
                String segmentIdStr = String.valueOf(segmentId);
                // Use "default" cluster for segments without explicit cluster assignment
                // This allows the hierarchy to function with a single default cluster
                filter.registerSegment(segmentIdStr, "default");
                registeredCount++;
            }
            if (registeredCount > 0) {
                LOGGER.info("Registered {} existing segments with hierarchical Cuckoo filter", registeredCount);
            }
        }
    }

    /**
     * Returns the hierarchical Cuckoo filter segments are registered into.
     *
     * @return The filter, or null if none is configured
     */
    HierarchicalCuckooFilter hierarchicalFilter() {
        return hierarchicalFilter;
    }
}
