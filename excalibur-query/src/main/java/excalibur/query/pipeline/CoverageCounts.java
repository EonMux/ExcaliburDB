package excalibur.query.pipeline;

import excalibur.query.IndexCoverage;
import excalibur.query.executor.SegmentQueryTask;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The counts a query response reports about the corpus and about what the query reached.
 *
 * <p>Kept apart from the ranking stages because these numbers are read on every path out of
 * the pipeline, including the degraded ones where no stage produced an answer.</p>
 */
final class CoverageCounts {

    private final StorageEngine storageEngine;

    CoverageCounts(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
    }

    /**
     * The catalog's count of segments in each lifecycle state, read at one instant.
     *
     * <p>Every response says what the index holds, including one cut short. A response that
     * reports no segments at all - because the query was interrupted before it could look -
     * is indistinguishable from one served by an empty index.</p>
     *
     * @param total      Segments the catalog holds
     * @param active     Segments in the ACTIVE state
     * @param indexing   Segments in the INDEXING state
     * @param warming    Segments in the WARMING state
     * @param compacting Segments in the COMPACTING state
     * @param tombstoned Segments in the TOMBSTONED state
     */
    record Census(long total, long active, long indexing, long warming,
                  long compacting, long tombstoned) {

        /** The live corpus: everything the catalog holds that has not been tombstoned. */
        long nonPurged() {
            return total - tombstoned;
        }

        IndexCoverage.Builder onto(IndexCoverage.Builder builder) {
            return builder
                .totalSegments(total)
                .activeSegments(active)
                .indexingSegments(indexing)
                .warmingSegments(warming)
                .compactingSegments(compacting)
                .tombstonedSegments(tombstoned);
        }
    }

    /**
     * Reads the catalog's census of segment states.
     *
     * @return The counts, all zero for an empty catalog
     */
    Census census() {
        // One snapshot, not two reads: a total taken separately from the states it is broken
        // down into can disagree with them, and the disagreement shows up in the response.
        Map<Long, SegmentCatalog.SegmentMetadata> segments = storageEngine.getCatalog().getAll();
        long activeSegments = 0, indexingSegments = 0, warmingSegments = 0;
        long compactingSegments = 0, tombstonedSegments = 0;

        for (SegmentCatalog.SegmentMetadata metadata : segments.values()) {
            switch (metadata.getLifecycleState()) {
                case ACTIVE -> activeSegments++;
                case INDEXING -> indexingSegments++;
                case WARMING -> warmingSegments++;
                case COMPACTING -> compactingSegments++;
                case TOMBSTONED -> tombstonedSegments++;
                default -> {}
            }
        }

        return new Census(segments.size(), activeSegments, indexingSegments,
            warmingSegments, compactingSegments, tombstonedSegments);
    }

    /**
     * How many segments came back with an answer.
     *
     * @param results The per-segment results, which may be empty
     * @return The number that succeeded
     */
    static int segmentsAnswering(List<SegmentQueryTask.SegmentQueryResult> results) {
        int answered = 0;
        for (SegmentQueryTask.SegmentQueryResult result : results) {
            if (result.success()) {
                answered++;
            }
        }
        return answered;
    }

    /**
     * Whether any segment answered from only part of itself.
     *
     * @param results The per-segment results, which may be empty
     * @return true when at least one segment's tail scan stopped early
     */
    static boolean anySegmentIncomplete(
            List<SegmentQueryTask.SegmentQueryResult> results) {
        for (SegmentQueryTask.SegmentQueryResult result : results) {
            if (result.success() && !result.complete()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many segments failed for a reason other than running out of time.
     *
     * <p>The dispatcher reports a segment that did not answer in time as both an unsuccessful
     * result and a timeout. Coverage subtracts the two counts separately and a client reads them
     * as separate problems, so the timeouts come out of the failures here rather than being
     * charged twice.</p>
     *
     * @param results    The per-segment results, which may be empty
     * @param timedOut   How many of them did not answer in time
     * @return The number that failed for some other reason
     */
    static int segmentsFailing(
            List<SegmentQueryTask.SegmentQueryResult> results, int timedOut) {
        int failed = 0;
        for (SegmentQueryTask.SegmentQueryResult result : results) {
            if (!result.success()) {
                failed++;
            }
        }
        // An interrupted fan-out reports every segment as timed out and returns no results at
        // all, so the subtraction can go negative.
        return Math.max(0, failed - timedOut);
    }
}
