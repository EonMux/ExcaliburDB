package com.eonmux.excalibur.shard.visibility;

import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Validates epoch isolation guarantees to ensure no torn reads at the HNSW node level.
 *
 * <p>This class is the core of the epoch isolation guarantee. It ensures that
 * each query sees a consistent snapshot of data as it existed at the query's
 * captured epoch, preventing "torn reads" where a query might see partially
 * visible or inconsistent data.</p>
 *
 * <h2>Epoch Isolation Guarantee</h2>
 * <p>A "torn read" occurs when:</p>
 * <ul>
 *   <li>A query captures epoch E</li>
 *   <li>During query execution, new data is ingested at epoch E+1, E+2, etc.</li>
 *   <li>The query inadvertently sees some of this new data (which shouldn't be visible at epoch E)</li>
 * </ul>
 *
 * <h2>Node Identity Is Per Segment</h2>
 * <p>HNSW node IDs are dense and allocated per graph, so node 7 of segment 0 and node 7
 * of segment 5 are different vectors. Every visibility record is therefore scoped by
 * segment ID; a bare node ID is not a usable key.</p>
 *
 * <h2>Epoch Checkpoints Instead of Per-Node Entries</h2>
 * <p>Within one graph, node IDs are handed out by a monotonically increasing counter, so
 * visibility epochs are monotone in node ID. Instead of one map entry per vector - which
 * grows without bound for the life of the process - each segment keeps a sorted list of
 * <em>checkpoints</em> mapping "first node ID of an integration batch" to "epoch that
 * batch became visible at". A node's visibility epoch is the epoch of the greatest
 * checkpoint whose node ID does not exceed it, answered in O(log n) with memory
 * proportional to the number of integration batches rather than the number of vectors.</p>
 *
 * <p>A checkpoint must be registered <strong>before</strong> the batch it describes is
 * linked into the searchable graph. Because the checkpoint covers every node ID from its
 * floor upward, a node that a concurrent query reaches mid-insert already resolves to the
 * batch's epoch and is correctly hidden.</p>
 *
 * <p>Node IDs below a segment's lowest checkpoint - nodes that were already on disk when
 * the process started, or whose checkpoint has aged out - resolve to "visible". That is
 * correct rather than merely permissive: such nodes predate every epoch this process has
 * issued, so they are visible at any query epoch.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Node visibility epochs are non-negative and monotonically increasing per segment</li>
 *   <li>A node is visible at epoch E if its visibility epoch &lt;= E</li>
 *   <li>Filtered result counts are always &lt;= original result counts</li>
 *   <li>No null results in filtered output</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Checkpoint maps are concurrent and lock-free for readers</li>
 *   <li>Read operations never block writers</li>
 *   <li>Validation operations create defensive copies</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
public final class EpochIsolationValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(EpochIsolationValidator.class);

    /**
     * Maximum checkpoints retained per segment.
     *
     * <p>When the cap is exceeded the oldest checkpoints are dropped. Their node IDs then
     * resolve to "visible", which is sound: the dropped epochs are far below any epoch a
     * live query could have captured, so those nodes were already visible to every
     * in-flight query.</p>
     */
    public static final int MAX_CHECKPOINTS_PER_SEGMENT = 4096;

    /**
     * Number of checkpoints retained after a prune, so pruning is amortised.
     */
    private static final int CHECKPOINTS_AFTER_PRUNE = (MAX_CHECKPOINTS_PER_SEGMENT * 3) / 4;

    /**
     * Per-segment checkpoints: segment ID to (first node ID of a batch to its visible epoch).
     */
    private final ConcurrentHashMap<Long, ConcurrentSkipListMap<Long, Long>> segmentCheckpoints;

    /**
     * Map of segment ID to the epoch at which the segment was last updated.
     * Used to determine if a segment has entries visible at a given epoch.
     */
    private final ConcurrentHashMap<Long, Long> segmentLastUpdateEpoch;

    /**
     * Creates a new EpochIsolationValidator.
     */
    public EpochIsolationValidator() {
        this.segmentCheckpoints = new ConcurrentHashMap<>();
        this.segmentLastUpdateEpoch = new ConcurrentHashMap<>();
        LOGGER.debug("EpochIsolationValidator created");
    }

    // ==================== Node Registration ====================

    /**
     * Records that every node from {@code firstNodeId} upward in a segment's graph
     * becomes visible at {@code visibleEpoch}.
     *
     * <p>Call this <strong>before</strong> inserting the batch into the graph. The
     * checkpoint covers node IDs the batch has not allocated yet, so a query that reaches
     * a node mid-insert still sees the correct epoch.</p>
     *
     * @param segmentId    The segment owning the graph
     * @param firstNodeId  The lowest node ID the batch will use (the graph's next node ID)
     * @param visibleEpoch The epoch at which those nodes become visible
     * @throws IllegalArgumentException if firstNodeId or visibleEpoch is negative
     */
    public void registerNodeRange(long segmentId, long firstNodeId, long visibleEpoch) {
        if (firstNodeId < 0) {
            throw new IllegalArgumentException("firstNodeId must be non-negative: " + firstNodeId);
        }
        if (visibleEpoch < 0) {
            throw new IllegalArgumentException("visibleEpoch must be non-negative: " + visibleEpoch);
        }

        ConcurrentSkipListMap<Long, Long> checkpoints =
            segmentCheckpoints.computeIfAbsent(segmentId, k -> new ConcurrentSkipListMap<>());

        // Coalesce against the checkpoint that would already cover this range, not against the
        // highest one. Both are the same entry on the normal path, where each batch starts at the
        // graph's next node id and so registers in increasing order. They differ when a range
        // arrives below one already recorded, and comparing against the highest then drops the
        // new checkpoint entirely: every node between its first id and the next recorded
        // checkpoint is left uncovered, which resolves to "visible at every epoch". That is the
        // torn read this class exists to prevent, arrived at by skipping a write.
        Map.Entry<Long, Long> covering = checkpoints.floorEntry(firstNodeId);
        if (covering != null && covering.getValue().longValue() == visibleEpoch) {
            // Consecutive batches at the same epoch are one contiguous range.
            return;
        }

        checkpoints.put(firstNodeId, visibleEpoch);
        pruneOldestCheckpoints(segmentId, checkpoints);

        LOGGER.trace("Segment {} nodes from {} registered with visibility epoch {}",
            segmentId, firstNodeId, visibleEpoch);
    }

    /**
     * Drops the oldest checkpoints of a segment once the retention cap is exceeded.
     *
     * @param segmentId   The segment being pruned (for logging)
     * @param checkpoints The segment's checkpoint map
     */
    private void pruneOldestCheckpoints(long segmentId, ConcurrentSkipListMap<Long, Long> checkpoints) {
        if (checkpoints.size() <= MAX_CHECKPOINTS_PER_SEGMENT) {
            return;
        }

        int pruned = 0;
        while (checkpoints.size() > CHECKPOINTS_AFTER_PRUNE && checkpoints.pollFirstEntry() != null) {
            pruned++;
        }
        LOGGER.debug("Pruned {} aged visibility checkpoints for segment {}", pruned, segmentId);
    }



    /**
     * Registers a segment's last update epoch.
     *
     * <p>This is used to quickly determine if a segment has any entries
     * visible at a given query epoch.</p>
     *
     * @param segmentId The segment ID
     * @param updateEpoch The epoch of the last update to this segment
     */
    public void registerSegmentUpdate(long segmentId, long updateEpoch) {
        if (updateEpoch < 0) {
            throw new IllegalArgumentException("updateEpoch must be non-negative: " + updateEpoch);
        }

        segmentLastUpdateEpoch.compute(segmentId, (k, existing) -> {
            if (existing == null || updateEpoch > existing) {
                return updateEpoch;
            }
            return existing;
        });
    }

    /**
     * Forgets everything known about a segment.
     *
     * <p>Call this when a segment leaves the shard - unregistered, tombstoned and purged,
     * or replaced by compaction - so its visibility records do not outlive its data.</p>
     *
     * @param segmentId The segment to forget
     * @return true if the segment had visibility records
     */
    public boolean unregisterSegment(long segmentId) {
        boolean hadCheckpoints = segmentCheckpoints.remove(segmentId) != null;
        boolean hadUpdate = segmentLastUpdateEpoch.remove(segmentId) != null;
        if (hadCheckpoints || hadUpdate) {
            LOGGER.debug("Dropped visibility records for segment {}", segmentId);
        }
        return hadCheckpoints || hadUpdate;
    }

    /**
     * Discards a segment's node checkpoints after its graph was renumbered.
     *
     * <p>HNSW compaction re-inserts the surviving nodes into a fresh graph, so every node
     * ID changes. Retained checkpoints would map live node IDs to unrelated epochs, so
     * they are dropped: the compacted nodes all predate the compaction and are visible to
     * every query.</p>
     *
     * @param segmentId The segment whose graph was compacted
     */
    public void resetSegmentNodeEpochs(long segmentId) {
        if (segmentCheckpoints.remove(segmentId) != null) {
            LOGGER.debug("Reset node visibility checkpoints for segment {} after graph compaction",
                segmentId);
        }
    }


    // ==================== Visibility Queries ====================

    /**
     * Checks if a node of a segment is visible at the specified query epoch.
     *
     * @param segmentId  The segment owning the node
     * @param nodeId     The node ID to check
     * @param queryEpoch The epoch at which the query was captured
     * @return true if the node is visible at the query epoch
     */
    public boolean isNodeVisible(long segmentId, long nodeId, long queryEpoch) {
        return visibilityEpochOf(segmentId, nodeId) <= queryEpoch;
    }

    /**
     * Returns the epoch at which a node became visible.
     *
     * @param segmentId The segment owning the node
     * @param nodeId    The node ID
     * @return The visibility epoch, or -1 if the segment has no checkpoint covering it
     */
    public long getNodeVisibilityEpoch(long segmentId, long nodeId) {
        ConcurrentSkipListMap<Long, Long> checkpoints = segmentCheckpoints.get(segmentId);
        if (checkpoints == null) {
            return -1;
        }
        Map.Entry<Long, Long> covering = checkpoints.floorEntry(nodeId);
        return covering != null ? covering.getValue() : -1;
    }

    /**
     * Resolves the effective visibility epoch of a node, treating an uncovered node as
     * pre-existing (visible at every epoch).
     *
     * @param segmentId The segment owning the node
     * @param nodeId    The node ID
     * @return The visibility epoch, or 0 when no checkpoint covers the node
     */
    private long visibilityEpochOf(long segmentId, long nodeId) {
        long epoch = getNodeVisibilityEpoch(segmentId, nodeId);
        return epoch < 0 ? 0 : epoch;
    }


    /**
     * Checks if a segment has any entries visible at the specified query epoch.
     *
     * <p>This is a quick check that can be used to skip segments entirely
     * if they have no visible entries.</p>
     *
     * @param segmentId The segment ID to check
     * @param queryEpoch The epoch at which the query was captured
     * @return true if the segment may have visible entries
     */
    public boolean segmentHasVisibleEntries(long segmentId, long queryEpoch) {
        Long lastUpdate = segmentLastUpdateEpoch.get(segmentId);
        if (lastUpdate == null) {
            // Segment not tracked - assume it has visible entries
            return true;
        }
        // Segment has entries if its first entry epoch <= queryEpoch
        // Since we only track last update, we conservatively return true
        // if the segment has been updated at all
        return lastUpdate > 0;
    }

    // ==================== Result Filtering ====================

    /**
     * Filters one segment's HNSW search results to the nodes visible at the query epoch.
     *
     * <p>This is the primary method for preventing torn reads. It ensures that
     * query results only contain data that was visible when the query captured
     * its epoch.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>Returned list contains only nodes with visibility epoch &lt;= queryEpoch</li>
     *   <li>Original ordering (by distance) is preserved</li>
     *   <li>No null elements in returned list</li>
     * </ul>
     *
     * @param segmentId  The segment the results came from
     * @param results    The original HNSW search results
     * @param queryEpoch The epoch at which the query was captured
     * @return Filtered results containing only visible nodes
     */
    public List<HnswSearchResult> filterResults(long segmentId, List<HnswSearchResult> results,
                                                long queryEpoch) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        List<HnswSearchResult> filtered = new ArrayList<>(results.size());
        int filteredOut = 0;

        for (HnswSearchResult result : results) {
            if (result == null) {
                continue;
            }

            long nodeId = result.nodeId();
            if (isNodeVisible(segmentId, nodeId, queryEpoch)) {
                filtered.add(result);
            } else {
                filteredOut++;
                LOGGER.trace("Filtered out segment {} node {} with visibility epoch {} > query epoch {}",
                    segmentId, nodeId, getNodeVisibilityEpoch(segmentId, nodeId), queryEpoch);
            }
        }

        if (filteredOut > 0) {
            LOGGER.debug("Filtered {} results of segment {} not visible at epoch {}",
                filteredOut, segmentId, queryEpoch);
        }

        return Collections.unmodifiableList(filtered);
    }



    // ==================== Validation ====================

    /**
     * Validates that one segment's results respect epoch isolation.
     *
     * <p>This method is used for verification and testing. It checks that
     * all results are visible at the specified query epoch.</p>
     *
     * @param segmentId  The segment the results came from
     * @param results    The results to validate
     * @param queryEpoch The query epoch
     * @return Validation result with details of any violations
     */
    public ValidationResult validateResults(long segmentId, List<HnswSearchResult> results,
                                            long queryEpoch) {
        if (results == null || results.isEmpty()) {
            return new ValidationResult(true, 0, 0, Collections.emptyList());
        }

        int totalChecked = 0;
        int violations = 0;
        List<ViolationDetails> violationDetails = new ArrayList<>();

        for (HnswSearchResult result : results) {
            if (result == null) {
                continue;
            }

            totalChecked++;
            long nodeId = result.nodeId();
            long visibleEpoch = getNodeVisibilityEpoch(segmentId, nodeId);

            if (visibleEpoch >= 0 && visibleEpoch > queryEpoch) {
                violations++;
                violationDetails.add(new ViolationDetails(
                    nodeId,
                    visibleEpoch,
                    queryEpoch,
                    result.distance()
                ));
            }
        }

        boolean valid = violations == 0;

        if (!valid) {
            LOGGER.warn("Epoch isolation validation found {} violations out of {} results in segment {}",
                violations, totalChecked, segmentId);
        }

        return new ValidationResult(valid, totalChecked, violations, violationDetails);
    }


    // ==================== Statistics ====================

    /**
     * Returns the number of visibility checkpoints held across all segments.
     *
     * <p>This is bounded by {@link #MAX_CHECKPOINTS_PER_SEGMENT} per segment, not by the
     * number of indexed vectors.</p>
     *
     * @return Checkpoint count
     */
    public int getCheckpointCount() {
        int total = 0;
        for (ConcurrentSkipListMap<Long, Long> checkpoints : segmentCheckpoints.values()) {
            total += checkpoints.size();
        }
        return total;
    }

    /**
     * Returns the number of segments with visibility checkpoints.
     *
     * @return Segment count
     */
    public int getCheckpointedSegmentCount() {
        return segmentCheckpoints.size();
    }

    /**
     * Returns the number of segments with a recorded last-update epoch.
     *
     * @return Segment count
     */
    public int getRegisteredSegmentCount() {
        return segmentLastUpdateEpoch.size();
    }

    /**
     * Returns statistics about this validator.
     *
     * @return Statistics snapshot
     */
    public ValidatorStats getStats() {
        return new ValidatorStats(
            getCheckpointCount(),
            segmentLastUpdateEpoch.size()
        );
    }

    /**
     * Clears all registrations.
     *
     * <p>This is typically only used during testing or reset scenarios.</p>
     */
    public void clear() {
        segmentCheckpoints.clear();
        segmentLastUpdateEpoch.clear();
        LOGGER.debug("EpochIsolationValidator cleared");
    }

    // ==================== Value Classes ====================

    /**
     * Result of epoch isolation validation.
     *
     * @param valid Whether all results respect epoch isolation
     * @param totalChecked Total number of results checked
     * @param violations Number of epoch isolation violations
     * @param violationDetails Details of each violation
     */
    public record ValidationResult(
        boolean valid,
        int totalChecked,
        int violations,
        List<ViolationDetails> violationDetails
    ) {
        /**
         * Returns the ratio of violations to total checked.
         *
         * @return Violation ratio (0.0 to 1.0), or 0.0 if no results checked
         */
        public double violationRatio() {
            return totalChecked > 0 ? (double) violations / totalChecked : 0.0;
        }

        @Override
        public String toString() {
            return String.format("ValidationResult{valid=%s, checked=%d, violations=%d (%.1f%%)}",
                valid, totalChecked, violations, violationRatio() * 100);
        }
    }

    /**
     * Details of an epoch isolation violation.
     *
     * @param nodeId The node ID that violated isolation
     * @param visibleEpoch The epoch at which the node became visible
     * @param queryEpoch The query's captured epoch
     * @param score The score/distance of the result
     */
    public record ViolationDetails(
        long nodeId,
        long visibleEpoch,
        long queryEpoch,
        float score
    ) {
        @Override
        public String toString() {
            return String.format("Violation{node=%d, visibleEpoch=%d > queryEpoch=%d, score=%.4f}",
                nodeId, visibleEpoch, queryEpoch, score);
        }
    }

    /**
     * Statistics about the epoch isolation validator.
     *
     * @param checkpoints Number of visibility checkpoints held
     * @param registeredSegments Number of segments with a recorded update epoch
     */
    public record ValidatorStats(
        int checkpoints,
        int registeredSegments
    ) {
        @Override
        public String toString() {
            return String.format("ValidatorStats{checkpoints=%d, segments=%d}",
                checkpoints, registeredSegments);
        }
    }

    @Override
    public String toString() {
        return String.format("EpochIsolationValidator{checkpoints=%d, segments=%d}",
            getCheckpointCount(), segmentLastUpdateEpoch.size());
    }
}
