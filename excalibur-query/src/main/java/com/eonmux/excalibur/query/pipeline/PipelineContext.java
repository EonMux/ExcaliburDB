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
            boolean llmFallback) {
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
    }

    PipelineContext(QueryRequest request, CancellationToken token, long epoch,
            List<SegmentCandidate> candidates, int segmentsExcluded) {
        this(request, token, epoch, candidates, Collections.emptyList(),
            Collections.emptyList(), 0, 0, segmentsExcluded, false, false);
    }

    PipelineContext withCandidates(List<SegmentCandidate> candidates) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback);
    }

    PipelineContext withSegmentResults(
            List<SegmentQueryTask.SegmentQueryResult> results, long tailScanEntries, int timedOut) {
        return new PipelineContext(request, token, epoch, candidates,
            results, mergedResults, tailScanEntries, timedOut,
            segmentsExcluded, llmScored, llmFallback);
    }

    PipelineContext withMergedResults(List<QueryResultItem> results) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, results, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, llmFallback);
    }

    PipelineContext withLlmScored(boolean scored) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, scored, llmFallback);
    }

    PipelineContext withLlmFallback(boolean fallback) {
        return new PipelineContext(request, token, epoch, candidates,
            segmentResults, mergedResults, totalTailScanEntries, segmentsTimedOut,
            segmentsExcluded, llmScored, fallback);
    }
}
