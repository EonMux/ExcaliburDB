package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.SegmentQueryTask;

import java.util.Collections;
import java.util.List;

/**
 * Context passed through pipeline stages.
 *
 * <p><strong>Thread Safety:</strong> Each pipeline execution uses its own context.</p>
 */
final class PipelineContext {
    final QueryRequest request;
    final CancellationToken token;
    final long epoch;
    final List<SegmentCandidate> candidates;
    final List<SegmentQueryTask.SegmentQueryResult> segmentResults;
    final List<QueryResultItem> mergedResults;
    final long totalTailScanEntries;
    final int segmentsTimedOut;
    /** Queryable segments stage 0 was not permitted to hand on. */
    final int segmentsExcluded;
    final boolean llmScored;
    final boolean llmFallback;
    /**
     * Whether nothing past Stage 3's ranking was left unconsidered: every segment answered with
     * less than it was asked for, and the merge came back shallower than the candidate count it
     * asked for.
     */
    final boolean retrievalExhausted;
    /** Results Stage 3 dropped because they carried no name and the request needs one. */
    final int unnamedResultsDropped;
    /**
     * How deep Stages 2 and 3 rank: the request's topK, unless the answer is being refilled
     * past documents the atomicity filter withheld.
     */
    final int searchDepth;
    /** Whether every segment Stage 2 heard from answered with fewer results than it was asked. */
    final boolean segmentsRanDry;
    /** Results Stage 3's atomicity filter withheld, being chunks of documents being written. */
    final int resultsWithheld;
    /** Whether the deadline stopped a refill before the answer was full. */
    final boolean refillCut;

    private PipelineContext(
            QueryRequest request,
            CancellationToken token,
            long epoch,
            List<SegmentCandidate> candidates,
            List<SegmentQueryTask.SegmentQueryResult> segmentResults,
            List<QueryResultItem> mergedResults,
            long totalTailScanEntries,
            int segmentsTimedOut,
            int segmentsExcluded,
            boolean llmScored,
            boolean llmFallback,
            boolean retrievalExhausted,
            int unnamedResultsDropped,
            int searchDepth,
            boolean segmentsRanDry,
            int resultsWithheld,
            boolean refillCut) {
        this.request = request;
        this.token = token;
        this.epoch = epoch;
        this.candidates = candidates;
        this.segmentResults = segmentResults;
        this.mergedResults = mergedResults;
        this.totalTailScanEntries = totalTailScanEntries;
        this.segmentsTimedOut = segmentsTimedOut;
        this.segmentsExcluded = segmentsExcluded;
        this.llmScored = llmScored;
        this.llmFallback = llmFallback;
        this.retrievalExhausted = retrievalExhausted;
        this.unnamedResultsDropped = unnamedResultsDropped;
        this.searchDepth = searchDepth;
        this.segmentsRanDry = segmentsRanDry;
        this.resultsWithheld = resultsWithheld;
        this.refillCut = refillCut;
    }

    PipelineContext(QueryRequest request, CancellationToken token, long epoch,
            List<SegmentCandidate> candidates, int segmentsExcluded) {
        this(request, token, epoch, candidates, Collections.emptyList(),
            Collections.emptyList(), 0, 0, segmentsExcluded, false, false, false, 0,
            request.topK(), false, 0, false);
    }

    PipelineContext withCandidates(List<SegmentCandidate> candidates) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, retrievalExhausted,
            unnamedResultsDropped, searchDepth, segmentsRanDry, resultsWithheld, refillCut);
    }

    /**
     * Stage 2's answers, judged dry or not by what they hold.
     *
     * @param results         Each segment's answer
     * @param tailScanEntries How many write-log entries were scanned
     * @param timedOut        How many segments did not answer in time
     * @return The context carrying them
     */
    PipelineContext withSegmentResults(
            List<SegmentQueryTask.SegmentQueryResult> results, long tailScanEntries, int timedOut) {
        return withSegmentResults(results, tailScanEntries, timedOut,
            ranDry(results, searchDepth));
    }

    /**
     * Whether every segment that answered ran out of results before the depth it was asked for.
     *
     * <p>A segment answers with at most the depth it is asked for, so one that filled it may hold
     * more and one that did not has nothing further. Only the second says there is nothing past
     * the ranking to find. A segment that failed is not judged here: what it holds is unknown,
     * which the coverage it is counted in already says.</p>
     *
     * @param results Each segment's answer, as the segment gave it
     * @param depth   The depth each was asked for
     * @return true when no segment that answered could have given more
     */
    static boolean ranDry(List<SegmentQueryTask.SegmentQueryResult> results, int depth) {
        for (SegmentQueryTask.SegmentQueryResult result : results) {
            if (result.success() && result.resultCount() >= depth) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stage 2's answers.
     *
     * @param results         Each segment's answer
     * @param tailScanEntries How many write-log entries were scanned
     * @param timedOut        How many segments did not answer in time
     * @param ranDry          Whether every segment answered with less than it was asked for
     * @return The context carrying them
     */
    PipelineContext withSegmentResults(List<SegmentQueryTask.SegmentQueryResult> results,
                                       long tailScanEntries, int timedOut, boolean ranDry) {
        return new PipelineContext(request, token, epoch, candidates,
            results, mergedResults, tailScanEntries, timedOut,
            segmentsExcluded, llmScored, llmFallback, retrievalExhausted,
            unnamedResultsDropped, searchDepth, ranDry, resultsWithheld, refillCut);
    }

    PipelineContext withMergedResults(List<QueryResultItem> results) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, results, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, retrievalExhausted,
            unnamedResultsDropped, searchDepth, segmentsRanDry, resultsWithheld, refillCut);
    }

    PipelineContext withLlmScored(boolean scored) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, scored, llmFallback, retrievalExhausted,
            unnamedResultsDropped, searchDepth, segmentsRanDry, resultsWithheld, refillCut);
    }

    PipelineContext withLlmFallback(boolean fallback) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, fallback, retrievalExhausted,
            unnamedResultsDropped, searchDepth, segmentsRanDry, resultsWithheld, refillCut);
    }

    /**
     * Stage 3's ranking, with what it established about the candidates behind it.
     *
     * @param results               The ranking
     * @param exhausted             Whether nothing past the ranking was left unconsidered
     * @param unnamedResultsDropped How many results were dropped for want of a name
     * @return The context carrying them
     */
    PipelineContext withRanking(List<QueryResultItem> results, boolean exhausted,
                                int unnamedResultsDropped) {
        return withRanking(results, exhausted, unnamedResultsDropped, 0);
    }

    /**
     * Stage 3's ranking, with what it established about the candidates behind it.
     *
     * @param results               The ranking
     * @param exhausted             Whether nothing past the ranking was left unconsidered
     * @param unnamedResultsDropped How many results were dropped for want of a name
     * @param withheld              How many the atomicity filter withheld
     * @return The context carrying them
     */
    PipelineContext withRanking(List<QueryResultItem> results, boolean exhausted,
                                int unnamedResultsDropped, int withheld) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, results, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, exhausted, unnamedResultsDropped,
            searchDepth, segmentsRanDry, withheld, refillCut);
    }

    /**
     * The same query, to be searched again at a greater depth.
     *
     * <p>Keeps the epoch and the candidate segments Stages 0 and 1 settled, so the deeper search
     * reads the same snapshot, and nothing Stages 2 and 3 established: they establish it again
     * at the new depth.</p>
     *
     * @param depth How deep to rank
     * @return The context to run Stages 2 and 3 on
     */
    PipelineContext deepened(int depth) {
        return new PipelineContext(request, token, epoch, candidates, Collections.emptyList(),
            Collections.emptyList(), 0, 0, segmentsExcluded, false, false, false, 0,
            depth, false, 0, false);
    }

    /**
     * This ranking, as the answer a refill the deadline stopped leaves behind.
     *
     * <p>Not exhausted, whatever the ranking established: the refill stopped because there was
     * no time to look further, not because there was nothing further to find.</p>
     *
     * @return The context marked as cut short
     */
    PipelineContext cutShort() {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, false, unnamedResultsDropped,
            searchDepth, segmentsRanDry, resultsWithheld, true);
    }
}
