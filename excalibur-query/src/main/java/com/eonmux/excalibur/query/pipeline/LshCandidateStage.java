package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.query.executor.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stage 2: LSH candidates.
 *
 * <p>Asks each segment's LSH index which of its entries share a bucket with the query, and
 * attaches those node ids to the segment for stage 3 to measure exactly beside its graph search.
 * The stage never removes a segment and never narrows what stage 3 may return: it can only add
 * nodes to the pool stage 3 ranks.</p>
 *
 * <h2>Why the index may not filter</h2>
 * <p>A query asks for the k nearest vectors, which exist in every non-empty segment however far
 * away they are. An LSH bucket says which entries hashed alongside the query, a likelihood that
 * falls off with distance, and puts no bound on the distance of anything outside it. So an empty
 * answer does not mean "no neighbours here", only "nothing within the radius the hash shape
 * favours"; and a non-empty answer does not mean "the neighbours are among these", only "these are
 * some likely ones". Excluding a segment on the first, or confining its search to the second,
 * drops results the graph would have returned, with nothing failing and coverage reading as
 * complete - under the default shape most queries miss their buckets in most segments, so it drops
 * most of them. No property of the index makes either decision safe, so the stage makes neither.</p>
 *
 * <h2>What it contributes instead</h2>
 * <p>The graph search is approximate: its beam can pass over a near neighbour the query's buckets
 * would have named. Stage 3 therefore measures the named nodes exactly and ranks them together with
 * what the graph returned. Adding exactly-measured nodes to a pool ranked by exact distance can
 * displace a result only with a closer one, so the answer is never worse than the graph search
 * alone and is better wherever the beam missed something the buckets caught.</p>
 *
 * <p>Because the stage can only add, an index that disagrees with its graph costs work and nothing
 * else: an index that has not yet seen every node names fewer of them, and an id naming no live
 * node is skipped when stage 3 measures it.</p>
 */
final class LshCandidateStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(LshCandidateStage.class);

    private LshCandidateStage() {
    }

    /**
     * Attaches to each candidate segment the entries its LSH index names for the query.
     *
     * @param context The pipeline context with segment candidates
     * @param token The cancellation token
     * @return Context with the same candidates, each carrying the entries its index named
     */
    static PipelineContext apply(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 2 cancelled before LSH candidate lookup");

        float[] queryVector = context.request.queryVectorUnsafe();

        List<SegmentCandidate> annotated = new ArrayList<>(context.candidates.size());
        int segmentsWithLsh = 0;
        long totalLshCandidates = 0;

        for (SegmentCandidate candidate : context.candidates) {
            token.checkCancelled("Stage 2 cancelled during LSH query");

            LshIndex lsh = candidate.lshIndex();
            if (lsh == null) {
                annotated.add(candidate);
                continue;
            }

            segmentsWithLsh++;
            Set<Long> named = namedEntries(candidate, lsh, queryVector);
            totalLshCandidates += named.size();
            annotated.add(named.isEmpty() ? candidate : candidate.withLshCandidates(named));
        }

        LOGGER.debug("Stage 2: {} segments, {} with an LSH index, {} LSH candidates to measure",
            annotated.size(), segmentsWithLsh, totalLshCandidates);

        return context.withCandidates(annotated);
    }

    /**
     * Returns the entries one segment's LSH index names for the query.
     *
     * <p>An index whose query throws names nothing, which costs this segment nothing but the
     * extra candidates: the graph search does not depend on them.</p>
     *
     * @param candidate The segment candidate, for logging
     * @param lsh The segment's LSH index
     * @param queryVector The query vector
     * @return The node ids the index named, possibly empty
     */
    private static Set<Long> namedEntries(SegmentCandidate candidate, LshIndex lsh, float[] queryVector) {
        try {
            return lsh.query(queryVector);
        } catch (Exception e) {
            LOGGER.warn("LSH query failed for segment {}: {}. Searching without its candidates.",
                candidate.segmentId(), e.getMessage());
            return Set.of();
        }
    }
}
