package excalibur.query.pipeline;

import excalibur.filter.lsh.LshIndex;
import excalibur.query.executor.CancellationToken;
import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stage 2: LSH Pre-filter.
 *
 * <p>Coarse vector similarity filtering using Locality-Sensitive Hashing.
 * Reduces candidates to approximately 10x-100x top_k.</p>
 *
 * <h2>LSH Filtering Algorithm</h2>
 * <ol>
 *   <li>For each segment candidate, query its LSH index with the query vector</li>
 *   <li>If the LSH index returns candidates, attach them to the segment candidate</li>
 *   <li>If no LSH index is available, pass the segment through unchanged</li>
 *   <li>If the index covers the segment and returns no candidates, the segment is
 *       excluded from further processing</li>
 *   <li>If the index is empty or covers only a small fraction of the segment's
 *       entries, its verdict carries no information and the segment passes through</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>LSH candidates are entry IDs (long values) that potentially match the query vector</li>
 *   <li>A segment is only excluded on the word of an index that actually covers it -
 *       an empty or barely-populated index means "unknown", not "no matches"</li>
 *   <li>Segments without an LSH index pass through to ensure no data loss</li>
 * </ul>
 */
final class LshPreFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LshPreFilter.class);

    private LshPreFilter() {
    }

    /**
     * Narrows the candidate segments, and the entries within them, by LSH.
     *
     * @param context The pipeline context with segment candidates
     * @param token The cancellation token
     * @return Context with the surviving candidates
     */
    static PipelineContext apply(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 2 cancelled before LSH pre-filter");

        LOGGER.trace("Stage 2: Running LSH pre-filter");

        float[] queryVector = context.request.queryVectorUnsafe();

        List<SegmentCandidate> passed = new ArrayList<>();
        int segmentsWithLsh = 0;
        int segmentsFiltered = 0;
        long totalLshCandidates = 0;

        for (SegmentCandidate candidate : context.candidates) {
            token.checkCancelled("Stage 2 cancelled during LSH query");

            LshIndex lsh = candidate.lshIndex();
            if (lsh == null) {
                // No LSH index - pass through to ensure no data loss
                passed.add(candidate);
                continue;
            }

            segmentsWithLsh++;
            Verdict verdict = narrowSegment(candidate, lsh, queryVector);
            if (verdict.searchable() == null) {
                segmentsFiltered++;
            } else {
                totalLshCandidates += verdict.lshCandidateCount();
                passed.add(verdict.searchable());
            }
        }

        LOGGER.debug("Stage 2: {} segments passed LSH pre-filter ({} with LSH, {} filtered out, {} total LSH candidates)",
            passed.size(), segmentsWithLsh, segmentsFiltered, totalLshCandidates);

        return context.withCandidates(passed);
    }

    /**
     * Asks one segment's LSH index which of its entries the query could match.
     *
     * <p>Every answer other than "this index covers the segment and named nothing" leaves the
     * segment in the search. An index that cannot speak for its segment, and an index whose
     * query threw, are both silences rather than exclusions, and excluding on a silence loses
     * results that are in the corpus.</p>
     *
     * @param candidate The segment candidate
     * @param lsh The segment's LSH index
     * @param queryVector The query vector
     * @return The verdict for this segment
     */
    private static Verdict narrowSegment(SegmentCandidate candidate, LshIndex lsh, float[] queryVector) {
        try {
            PrefilterCoverage.Standing standing = prefilterStanding(candidate, lsh);
            if (standing == PrefilterCoverage.Standing.UNKNOWN) {
                // The index has missed nodes the graph holds, so neither of its answers can
                // be acted on: the nodes it missed are the ones it cannot name. Treat it
                // exactly like a missing index and search the segment whole.
                LOGGER.debug("Segment {} LSH index holds {} entries for {} graph nodes; "
                    + "its answer is not authoritative, passing through",
                    candidate.segmentId(), lsh.entryCount(), graphNodeCount(candidate));
                return Verdict.searchWhole(candidate);
            }

            Set<Long> lshCandidates = lsh.query(queryVector);
            if (!lshCandidates.isEmpty()) {
                return new Verdict(candidate.withLshCandidates(lshCandidates), lshCandidates.size());
            }
            if (standing == PrefilterCoverage.Standing.COVERS_SEGMENT) {
                // The index covers this segment and found nothing: a real exclusion
                LOGGER.trace("Segment {} filtered out by LSH (no candidates)", candidate.segmentId());
                return Verdict.EXCLUDED;
            }
            // The index covers the graph but the graph is behind the log, so the segment
            // still has an unindexed tail to scan. Nothing to narrow the search to.
            LOGGER.trace("Segment {} has no LSH candidates but an unindexed tail; "
                + "passing through", candidate.segmentId());
            return Verdict.searchWhole(candidate);
        } catch (Exception e) {
            // On LSH error, pass the segment through to ensure no data loss
            LOGGER.warn("LSH query failed for segment {}: {}. Passing through.",
                candidate.segmentId(), e.getMessage());
            return Verdict.searchWhole(candidate);
        }
    }

    /**
     * What the pre-filter decided about one segment.
     *
     * @param searchable The candidate to go on searching, narrowed to the entries the index
     *                   named where it named any, or null when the segment is excluded
     * @param lshCandidateCount How many entries the index named, for the stage's tally
     */
    private record Verdict(SegmentCandidate searchable, long lshCandidateCount) {

        /** The one verdict that takes a segment out of the search. */
        static final Verdict EXCLUDED = new Verdict(null, 0);

        /**
         * A verdict that leaves the segment searched in full, the index having narrowed nothing.
         *
         * @param candidate The segment candidate, unchanged
         * @return The verdict
         */
        static Verdict searchWhole(SegmentCandidate candidate) {
            return new Verdict(candidate, 0);
        }
    }

    /**
     * Measures how far a segment's pre-filter may be believed.
     *
     * <p>Reads the four quantities {@link PrefilterCoverage} compares - what the index holds, what
     * the graph holds, and how far the graph has absorbed the write log - and treats any of them
     * being unreadable as a reason to believe none of it. The rule itself is documented there.</p>
     *
     * @param candidate The segment candidate
     * @param lsh The segment's LSH index
     * @return The standing of this pre-filter's answer for this segment
     */
    private static PrefilterCoverage.Standing prefilterStanding(SegmentCandidate candidate, LshIndex lsh) {
        HnswGraph graph = candidate.graph();
        Segment segment = candidate.segment();
        if (graph == null || segment == null) {
            // Nothing to measure the index against, so it speaks for nothing.
            return PrefilterCoverage.Standing.UNKNOWN;
        }

        try {
            return PrefilterCoverage.standing(
                lsh.entryCount(),
                graph.nodeCount(),
                graph.getLastIntegratedOffset(),
                segment.getWriteLogHead());
        } catch (Exception e) {
            LOGGER.trace("Could not measure pre-filter coverage for segment {}: {}",
                candidate.segmentId(), e.getMessage());
            return PrefilterCoverage.Standing.UNKNOWN;
        }
    }

    /**
     * Returns the number of nodes a candidate's graph holds, for logging, or -1 if unknown.
     */
    private static long graphNodeCount(SegmentCandidate candidate) {
        HnswGraph graph = candidate.graph();
        if (graph == null) {
            return -1;
        }
        try {
            return graph.nodeCount();
        } catch (Exception e) {
            LOGGER.trace("Could not read node count for segment {}: {}",
                candidate.segmentId(), e.getMessage());
            return -1;
        }
    }
}
