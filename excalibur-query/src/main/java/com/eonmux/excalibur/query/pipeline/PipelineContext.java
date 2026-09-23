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
     * Whether Stage 3's merge came back shallower than the candidate count it asked for, so
     * nothing past the ranking was left unconsidered.
     */
    final boolean retrievalExhausted;
    /** Results Stage 3 dropped because they carried no name and the request needs one. */
    final int unnamedResultsDropped;

    PipelineContext(
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
            int unnamedResultsDropped) {
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
    }

    PipelineContext(QueryRequest request, CancellationToken token, long epoch,
            List<SegmentCandidate> candidates, int segmentsExcluded) {
        this(request, token, epoch, candidates, Collections.emptyList(),
            Collections.emptyList(), 0, 0, segmentsExcluded, false, false, false, 0);
    }

    PipelineContext withCandidates(List<SegmentCandidate> candidates) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, retrievalExhausted,
            unnamedResultsDropped);
    }

    PipelineContext withSegmentResults(
            List<SegmentQueryTask.SegmentQueryResult> results, long tailScanEntries, int timedOut) {
        return new PipelineContext(request, token, epoch, candidates,
            results, mergedResults, tailScanEntries, timedOut,
            segmentsExcluded, llmScored, llmFallback, retrievalExhausted,
            unnamedResultsDropped);
    }

    PipelineContext withMergedResults(List<QueryResultItem> results) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, results, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, retrievalExhausted,
            unnamedResultsDropped);
    }

    PipelineContext withLlmScored(boolean scored) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, scored, llmFallback, retrievalExhausted,
            unnamedResultsDropped);
    }

    PipelineContext withLlmFallback(boolean fallback) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, fallback, retrievalExhausted,
            unnamedResultsDropped);
    }

    /**
     * Stage 3's ranking, with what it established about the candidates behind it.
     *
     * @param results               The ranking
     * @param exhausted             Whether the merge ran out of candidates before its limit
     * @param unnamedResultsDropped How many results were dropped for want of a name
     * @return The context carrying them
     */
    PipelineContext withRanking(List<QueryResultItem> results, boolean exhausted,
                                int unnamedResultsDropped) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, results, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback, exhausted, unnamedResultsDropped);
    }
}
