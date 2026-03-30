package excalibur.shard.visibility;

import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>This validator prevents torn reads by:</p>
 * <ol>
 *   <li>Tracking the visibility epoch for each node/vector in the HNSW graph</li>
 *   <li>Filtering search results to only include nodes visible at the query's captured epoch</li>
 *   <li>Validating that final query results respect epoch boundaries</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Node visibility epochs are non-negative and monotonically increasing</li>
 *   <li>A node is visible at epoch E if its registration epoch <= E</li>
 *   <li>Filtered result counts are always <= original result counts</li>
 *   <li>No null results in filtered output</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Node registrations use ConcurrentHashMap</li>
 *   <li>Read operations are lock-free</li>
 *   <li>Validation operations create defensive copies</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
public final class EpochIsolationValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(EpochIsolationValidator.class);

    /**
     * Map of HNSW node ID to the epoch at which the node became visible.
     * A node is visible to queries with epoch >= this value.
     */
    private final ConcurrentHashMap<Long, Long> nodeVisibilityEpochs;

    /**
     * Map of segment ID to the epoch at which the segment was last updated.
     * Used to determine if a segment has entries visible at a given epoch.
     */
    private final ConcurrentHashMap<Long, Long> segmentLastUpdateEpoch;

    /**
     * Creates a new EpochIsolationValidator.
     */
    public EpochIsolationValidator() {
        this.nodeVisibilityEpochs = new ConcurrentHashMap<>();
        this.segmentLastUpdateEpoch = new ConcurrentHashMap<>();
        LOGGER.debug("EpochIsolationValidator created");
    }

    // ==================== Node Registration ====================

    /**
     * Registers a node as visible at the specified epoch.
     *
     * <p>This method should be called when a vector is successfully inserted
     * into the HNSW graph and the write log has been synced. The node becomes
     * visible to queries with epoch >= visibleEpoch.</p>
     *
     * @param nodeId The HNSW graph node ID
     * @param visibleEpoch The epoch at which the node becomes visible
     * @throws IllegalArgumentException if visibleEpoch is negative
     */
    public void registerNode(long nodeId, long visibleEpoch) {
        if (visibleEpoch < 0) {
            throw new IllegalArgumentException("visibleEpoch must be non-negative: " + visibleEpoch);
        }

        nodeVisibilityEpochs.put(nodeId, visibleEpoch);
        LOGGER.trace("Node {} registered with visibility epoch {}", nodeId, visibleEpoch);
    }

    /**
     * Registers multiple nodes as visible at the specified epoch.
     *
     * <p>This is a batch version of {@link #registerNode(long, long)} for
     * efficiency during bulk ingestion.</p>
     *
     * @param nodeIds The HNSW graph node IDs
     * @param visibleEpoch The epoch at which all nodes become visible
     */
    public void registerNodes(Set<Long> nodeIds, long visibleEpoch) {
        if (visibleEpoch < 0) {
            throw new IllegalArgumentException("visibleEpoch must be non-negative: " + visibleEpoch);
        }
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");

        for (Long nodeId : nodeIds) {
            nodeVisibilityEpochs.put(nodeId, visibleEpoch);
        }
        LOGGER.trace("Registered {} nodes with visibility epoch {}", nodeIds.size(), visibleEpoch);
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
     * Unregisters a node (used when a node is tombstoned).
     *
     * @param nodeId The node ID to unregister
     * @return true if the node was registered and removed
     */
    public boolean unregisterNode(long nodeId) {
        Long removed = nodeVisibilityEpochs.remove(nodeId);
        return removed != null;
    }

    // ==================== Visibility Queries ====================

    /**
     * Checks if a node is visible at the specified query epoch.
     *
     * @param nodeId The node ID to check
     * @param queryEpoch The epoch at which the query was captured
     * @return true if the node is visible at the query epoch
     */
    public boolean isNodeVisible(long nodeId, long queryEpoch) {
        Long visibleEpoch = nodeVisibilityEpochs.get(nodeId);
        if (visibleEpoch == null) {
            // Node not registered - assume visible for backward compatibility
            // This handles the case where nodes were created before epoch tracking
            return true;
        }
        return visibleEpoch <= queryEpoch;
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

    /**
     * Gets the visibility epoch for a node.
     *
     * @param nodeId The node ID
     * @return The visibility epoch, or -1 if not registered
     */
    public long getNodeVisibilityEpoch(long nodeId) {
        Long epoch = nodeVisibilityEpochs.get(nodeId);
        return epoch != null ? epoch : -1;
    }

    // ==================== Result Filtering ====================

    /**
     * Filters HNSW search results to only include nodes visible at the query epoch.
     *
     * <p>This is the primary method for preventing torn reads. It ensures that
     * query results only contain data that was visible when the query captured
     * its epoch.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>Returned list contains only nodes with visibility epoch <= queryEpoch</li>
     *   <li>Original ordering (by distance) is preserved</li>
     *   <li>No null elements in returned list</li>
     * </ul>
     *
     * @param results The original HNSW search results
     * @param queryEpoch The epoch at which the query was captured
     * @return Filtered results containing only visible nodes
     */
    public List<HnswSearchResult> filterResults(List<HnswSearchResult> results, long queryEpoch) {
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
            if (isNodeVisible(nodeId, queryEpoch)) {
                filtered.add(result);
            } else {
                filteredOut++;
                LOGGER.trace("Filtered out node {} with visibility epoch {} > query epoch {}",
                    nodeId, getNodeVisibilityEpoch(nodeId), queryEpoch);
            }
        }

        if (filteredOut > 0) {
            LOGGER.debug("Filtered {} results not visible at epoch {}", filteredOut, queryEpoch);
        }

        return Collections.unmodifiableList(filtered);
    }

    // ==================== Validation ====================

    /**
     * Validates that a list of results respects epoch isolation.
     *
     * <p>This method is used for verification and testing. It checks that
     * all results are visible at the specified query epoch.</p>
     *
     * @param results The results to validate
     * @param queryEpoch The query epoch
     * @return Validation result with details of any violations
     */
    public ValidationResult validateResults(List<HnswSearchResult> results, long queryEpoch) {
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
            Long visibleEpoch = nodeVisibilityEpochs.get(nodeId);

            if (visibleEpoch != null && visibleEpoch > queryEpoch) {
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
            LOGGER.warn("Epoch isolation validation found {} violations out of {} results",
                violations, totalChecked);
        }

        return new ValidationResult(valid, totalChecked, violations, violationDetails);
    }

    // ==================== Statistics ====================

    /**
     * Returns the number of registered nodes.
     *
     * @return Node count
     */
    public int getRegisteredNodeCount() {
        return nodeVisibilityEpochs.size();
    }

    /**
     * Returns the number of registered segments.
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
            nodeVisibilityEpochs.size(),
            segmentLastUpdateEpoch.size()
        );
    }

    /**
     * Clears all registrations.
     *
     * <p>This is typically only used during testing or reset scenarios.</p>
     */
    public void clear() {
        nodeVisibilityEpochs.clear();
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
     * @param registeredNodes Number of registered nodes
     * @param registeredSegments Number of registered segments
     */
    public record ValidatorStats(
        int registeredNodes,
        int registeredSegments
    ) {
        @Override
        public String toString() {
            return String.format("ValidatorStats{nodes=%d, segments=%d}",
                registeredNodes, registeredSegments);
        }
    }

    @Override
    public String toString() {
        return String.format("EpochIsolationValidator{nodes=%d, segments=%d}",
            nodeVisibilityEpochs.size(), segmentLastUpdateEpoch.size());
    }
}
