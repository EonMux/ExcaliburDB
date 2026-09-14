package excalibur.query.pipeline;

import excalibur.query.executor.SegmentQueryTask;
import excalibur.shard.visibility.EpochIsolationValidator;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops search results for nodes that were not yet visible at the query's captured epoch.
 *
 * <p>Without this a query reads nodes inserted after it began, which is the torn read
 * snapshot isolation exists to prevent.</p>
 */
final class EpochIsolationFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EpochIsolationFilter.class);

    private EpochIsolationFilter() {
    }

    /**
     * Returns the per-segment results with post-epoch nodes removed.
     *
     * @param validator  The validator, or null when epoch isolation is not configured, in which
     *                   case the results are returned as they stand
     * @param results    The per-segment results from the fan-out
     * @param queryEpoch The epoch this query captured in stage 0
     * @return The results visible at {@code queryEpoch}
     */
    static List<SegmentQueryTask.SegmentQueryResult> retainVisible(
            EpochIsolationValidator validator,
            List<SegmentQueryTask.SegmentQueryResult> results,
            long queryEpoch) {

        if (validator == null) {
            return results;
        }

        int totalFiltered = 0;
        List<SegmentQueryTask.SegmentQueryResult> filteredResults = new ArrayList<>(results.size());

        for (SegmentQueryTask.SegmentQueryResult sr : results) {
            if (sr != null && sr.results() != null) {
                // Scope the lookup by segment. Node IDs are only unique within one
                // segment's graph, and the integration worker registers them per
                // segment; the segment-less overload looks them up in an unrelated,
                // never-populated scope, where every node resolves to "always
                // visible" and the isolation guarantee silently disappears.
                List<HnswSearchResult> filtered = validator.filterResults(
                    sr.segmentId(), sr.results(), queryEpoch);
                totalFiltered += sr.results().size() - filtered.size();

                // Create new result with filtered list, keeping the tail-scan chunk
                // text so the surviving results still carry their payloads
                filteredResults.add(new SegmentQueryTask.SegmentQueryResult(
                    sr.segmentId(),
                    filtered,
                    sr.tailScanEntries(),
                    sr.durationMs(),
                    sr.success(),
                    sr.errorMessage(),
                    sr.payloads()
                ));
            } else {
                filteredResults.add(sr);
            }
        }

        if (totalFiltered > 0) {
            LOGGER.debug("Stage 3: Epoch isolation filtered out {} results not visible at epoch {}",
                totalFiltered, queryEpoch);
        }

        return filteredResults;
    }
}
