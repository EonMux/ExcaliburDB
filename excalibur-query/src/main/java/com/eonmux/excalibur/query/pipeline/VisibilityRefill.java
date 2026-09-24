package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.executor.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Ranks deeper when the atomicity filter withheld results the answer needed.
 *
 * <h2>Why</h2>
 * <p>Stage 3 withholds the chunks of documents that are being written. It filters the whole
 * oversampled candidate set before cutting it to topK, so a withheld chunk is usually replaced
 * by the next candidate; but when more are withheld than the oversampling left spare, the answer
 * comes back short while visible matches lie just below the depth searched. Callers read a short
 * answer as the end of the matches - the query route reports an exact total from it and a caller
 * paging by offset stops there - so a short answer that is not the end of the matches is a wrong
 * one.</p>
 *
 * <h2>How</h2>
 * <p>Stages 2 and 3 run again at a greater depth, over the same epoch and the same candidate
 * segments Stages 0 and 1 settled, until the answer holds topK results, the segments run dry, or
 * the depth reaches {@link com.eonmux.excalibur.query.QueryRequest#maxSearchDepth()}. Each round
 * is at least twice as deep as the last and reaches past every result withheld so far, so the
 * rounds are few and the extra depth is bounded by how much of the corpus is being written at
 * that moment, never by its size. Nothing runs again unless something was withheld: a query over
 * a corpus nobody is writing to searches once, as it did before.</p>
 *
 * <h2>Bounds</h2>
 * <ul>
 *   <li>The depth is the per-segment result count and sets the beam: a segment's graph searches
 *       with {@code ef = max(efSearch, depth * oversampling)}, and the rerank takes twice the
 *       depth. The ceiling on the depth is therefore the ceiling on both.</li>
 *   <li>The nodes a segment's LSH index names are the query's buckets, the same whatever the
 *       depth: Stage 1 is not run again, and a deeper round measures the same named set and keeps
 *       more of it, up to the oversampled depth. Their number is bounded by the index's shape, not
 *       by the refill.</li>
 *   <li>Each round checks for cancellation first, and each segment search inside it runs on the
 *       share of the request's deadline Stage 2 always leaves itself.</li>
 *   <li>A round the deadline catches - one in which a segment did not answer that answered the
 *       round before - is discarded rather than kept: its ranking is missing what that segment
 *       holds, which the previous round's is not. The answer is then the previous round's, and it
 *       says the deadline cut it short.</li>
 * </ul>
 */
final class VisibilityRefill {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisibilityRefill.class);

    private VisibilityRefill() {
    }

    /**
     * Ranks deeper until the answer is full, nothing more can be found, or the request's bounds
     * stop it.
     *
     * @param ranked       Stage 3's ranking at the request's own depth
     * @param token        The cancellation token
     * @param deadline     When the query is abandoned
     * @param searchAndRank Runs Stages 2 and 3 on a context deepened to a new search depth
     * @param published    Told of each deeper ranking kept, so a timeout returns the latest
     * @return The ranking to answer from
     */
    static PipelineContext fill(PipelineContext ranked, CancellationToken token,
                                QueryDeadline deadline,
                                UnaryOperator<PipelineContext> searchAndRank,
                                Consumer<PipelineContext> published) {
        PipelineContext best = ranked;
        int ceiling = best.request.maxSearchDepth();
        while (wanting(best) && best.searchDepth < ceiling) {
            token.checkCancelled("Refill cancelled before ranking deeper");
            if (deadline.remainingMillis() <= 0) {
                return cut(best);
            }
            int depth = deeper(best.searchDepth, best.resultsWithheld, ceiling);
            LOGGER.debug("Refill: {} result(s) withheld left {} of {}; ranking {} deep",
                best.resultsWithheld, best.mergedResults.size(), best.request.topK(), depth);
            PipelineContext deeper = searchAndRank.apply(best.deepened(depth));
            if (deeper.segmentsTimedOut > best.segmentsTimedOut) {
                return cut(best);
            }
            best = deeper;
            published.accept(best);
        }
        return best;
    }

    /**
     * Whether a ranking is short of topK because of what was withheld, with more to find.
     *
     * @param ranked Stage 3's ranking
     * @return true when ranking deeper could fill it
     */
    private static boolean wanting(PipelineContext ranked) {
        return ranked.resultsWithheld > 0
            && !ranked.retrievalExhausted
            && ranked.mergedResults.size() < ranked.request.topK();
    }

    /**
     * The ranking the deadline left, marked as cut short.
     *
     * @param best The deepest ranking kept
     * @return It, not exhausted and marked cut
     */
    private static PipelineContext cut(PipelineContext best) {
        LOGGER.debug("Refill: the deadline stopped ranking deeper than {} with {} of {} results",
            best.searchDepth, best.mergedResults.size(), best.request.topK());
        return best.cutShort();
    }

    /**
     * How deep to rank next: at least twice as deep, past every result withheld, never past the
     * ceiling.
     *
     * @param depth    How deep the last round ranked
     * @param withheld How many results it withheld
     * @param ceiling  The request's ceiling on the depth
     * @return The next depth
     */
    static int deeper(int depth, int withheld, int ceiling) {
        long next = (long) depth + Math.max(depth, withheld);
        return (int) Math.min(ceiling, next);
    }
}
