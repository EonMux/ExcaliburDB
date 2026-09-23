package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.SegmentQueryTask;
import com.eonmux.excalibur.query.validation.DomainCorrectnessValidator;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import com.eonmux.excalibur.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Turns a pipeline outcome - an answer or an interruption - into the response the caller sees.
 *
 * <p>Both paths report the same coverage counts, because a degraded response that says nothing
 * about the corpus is indistinguishable from one served by an empty index.</p>
 */
final class ResponseAssembler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseAssembler.class);

    private final CoverageCounts coverageCounts;
    private final double coverageThreshold;
    private final EmptyResultDiagnostics emptyResultDiagnostics;

    ResponseAssembler(CoverageCounts coverageCounts,
                      double coverageThreshold,
                      EmptyResultDiagnostics emptyResultDiagnostics) {
        this.coverageCounts = coverageCounts;
        this.coverageThreshold = coverageThreshold;
        this.emptyResultDiagnostics = emptyResultDiagnostics;
    }

    /**
     * Turns whatever the run produced into the caller's response, and closes the query out.
     *
     * <p>An error here is the abandoned-query path rather than the failed-query one: the stages
     * are still running, so they are interrupted before the response is assembled, and the
     * response is assembled from the stages that did finish. The correlation id is cleared in a
     * {@code finally} because this thread is handed straight back to the caller's executor, and
     * a stale id left behind would be stamped on whatever it logs next.</p>
     *
     * @param request The query request
     * @param token The cancellation token
     * @param run The run being closed out, for its task handle and its partial results
     * @param context The final pipeline context, or null when the run did not reach one
     * @param error What the run failed or timed out with, or null when it completed
     * @param durationMs How long the query took, measured from before Stage 0
     * @return The response to hand the caller
     */
    QueryResponse finish(QueryRequest request,
                         CancellationToken token,
                         PipelineRun run,
                         PipelineContext context,
                         Throwable error,
                         long durationMs) {
        // Record query latency for JMX observability
        // This enables monitoring of p50/p90/p95/p99 latencies
        Metrics.recordQueryLatency(durationMs);
        // And the count the IngestionThroughput MBean reports, which had no writer outside
        // the tests: the MBean was registered and advertised four attributes that were zero
        // on a server that had served millions of queries.
        Metrics.recordQueryExecution();

        try {
            if (error != null) {
                // Stop the abandoned pipeline: cancelling with an interrupt unblocks
                // the stage that is running and stops the segment searches and tail
                // scans it dispatched, which all check the interrupt flag. Safe to
                // interrupt, unlike the storage workers: those threads read graphs, write
                // logs and segments through memory mappings, which an interrupt cannot
                // close, and block only on the scoring model's network call - no channel
                // another thread writes through is ever in their hands.
                run.task().cancel(true);
                return handlePipelineError(
                    error, request, durationMs, token, run.partialContext().get());
            }

            return buildResponse(context, durationMs);
        } finally {
            // Clear MDC context after pipeline completes
            StructuredLoggingHelper.clearCorrelationId();
        }
    }

    /**
     * Builds the final response from pipeline context.
     *
     * <h3>Domain Correctness Validation</h3>
     * <p>This method validates that empty results are only returned when legitimately
     * expected. If empty results are returned unexpectedly, diagnostic information
     * is logged to help identify the root cause.</p>
     *
     * <p><strong>Empty results are ONLY acceptable when no documents match the query vector.</strong></p>
     */
    QueryResponse buildResponse(PipelineContext context, long durationMs) {
        // A response is degraded when coverage is below the threshold, when a segment errored or
        // timed out - all three of which computeDegraded reads off the numbers below - and for
        // three reasons no count records, which are ORed in afterwards: LLM scoring that fell
        // back to vector-only ranking, a segment that answered from part of its write log, and
        // results Stage 3 found and dropped because nothing names them.
        IndexCoverage coverage = coverageCounts.census().onto(IndexCoverage.builder())
            .tailScanEntries(context.totalTailScanEntries)
            .segmentsSearched(CoverageCounts.segmentsAnswering(context.segmentResults))
            .segmentsExcluded(context.segmentsExcluded)
            .segmentsWithErrors(CoverageCounts.segmentsFailing(context.segmentResults, context.segmentsTimedOut))
            .segmentsTimedOut(context.segmentsTimedOut)
            .computeCoverage()
            .computeDegraded(coverageThreshold)
            .orDegraded(context.llmFallback)
            // A segment whose write-log tail scan stopped early answered from part of itself.
            // No count records that: it is not an error and not a timeout, and its results are
            // kept, so without this the response would say the whole corpus was searched.
            .orDegraded(CoverageCounts.anySegmentIncomplete(context.segmentResults))
            .orDegraded(context.unnamedResultsDropped > 0)
            .build();
        Metrics.recordUnnamedResultsDropped(context.unnamedResultsDropped);

        // Whether Stage 4 ordered the ranking is part of the answer, not of how it was made:
        // it decides whether a score is a similarity or a relevancy judgement.
        QueryResponse response = new QueryResponse(
            context.mergedResults,
            coverage,
            durationMs,
            false,
            false
        ).withLlmScored(context.llmScored)
            .withRetrievalExhausted(context.retrievalExhausted);

        // DOMAIN CORRECTNESS VALIDATION:
        // Validate the response meets all domain correctness constraints
        // This ensures: scores in [0.0, 1.0], coverage in [0.0, 1.0], results sorted, no duplicates
        DomainCorrectnessValidator.ValidationResult validationResult =
            DomainCorrectnessValidator.validateResponseWithResult(response);
        if (!validationResult.isValid()) {
            LOGGER.warn("Query response failed domain correctness validation: {}",
                validationResult.summary());
        }

        // Empty results are ONLY acceptable when no documents match the query vector
        // Log diagnostic information when empty results are returned to help identify issues
        if (response.isEmpty()) {
            emptyResultDiagnostics.validateAndLogEmptyResults(context, coverage, response);
        }

        return response;
    }

    /**
     * Handles pipeline errors and creates appropriate response.
     *
     * <p>When the pipeline runs out of time, any results that were already computed by
     * earlier stages are returned as best-effort partial results. A cancellation returns none:
     * the caller withdrew the question, and handing back whatever the stages had reached
     * answers part of a question nobody is asking any more. Hard failures - and timeouts that
     * happened before any stage completed - produce an empty result set too: a stage that
     * threw did not produce an answer, and whatever earlier stages left behind is not one
     * either.</p>
     *
     * @param error         The error that terminated the pipeline
     * @param request       The original query request
     * @param durationMs    Elapsed time before the error was observed
     * @param token         Cancellation token (cancelled here to propagate to running stages)
     * @param partialContext The most recently completed stage's context, or {@code null}
     *                      if no stage completed before the error
     */
    QueryResponse handlePipelineError(
            Throwable error,
            QueryRequest request,
            long durationMs,
            CancellationToken token,
            PipelineContext partialContext) {

        boolean timedOut = error instanceof TimeoutException ||
            (error.getCause() instanceof TimeoutException);
        boolean cancelled = error instanceof CancellationException ||
            (error.getCause() instanceof CancellationException);

        logPipelineError(error, durationMs, token, timedOut, cancelled);

        // Return partial results only for a timeout, which is what the contract promises. A
        // hard failure means a stage did not do its job, so whatever earlier stages happened
        // to accumulate is not an answer to the query - returning it would present an
        // arbitrary prefix of the pipeline as a successful (if degraded) result. A withdrawal
        // is answered with nothing for the reason the caller gave: the question was taken
        // back.
        boolean partial = timedOut && !cancelled;
        // Stage 3 leaves its ranking oversampled when Stage 4 is going to run, because Stage 4's
        // threshold filter is meant to choose from it. If Stage 4 is what the interruption
        // caught, that list goes to the caller as it stands - so it is cut here to the size the
        // caller actually asked for.
        List<QueryResultItem> partialResults = (partial && partialContext != null)
            ? QueryPipeline.truncate(partialContext.mergedResults, request.topK())
            : Collections.emptyList();

        IndexCoverage coverage = degradedCoverage(partial ? partialContext : null);

        // Cancelled is reported separately from timedOut: both answers are degraded, and a
        // cancelled one is always empty, so only this flag tells the caller which of them
        // happened, or that either did rather than the corpus holding no match.
        return new QueryResponse(
            null,
            partialResults,
            coverage,
            durationMs,
            false,
            timedOut,
            cancelled
        ).withLlmScored(partial && partialContext != null && partialContext.llmScored);
    }

    /**
     * Says what went wrong, and propagates a timeout to the stages still running.
     */
    private void logPipelineError(Throwable error, long durationMs, CancellationToken token,
                                  boolean timedOut, boolean cancelled) {
        if (timedOut) {
            LOGGER.warn("Query timed out after {}ms", durationMs);
            // Cancel the token to propagate to any running stages
            token.cancel("Query timeout after " + durationMs + "ms");
        } else if (cancelled) {
            LOGGER.info("Query cancelled");
        } else {
            LOGGER.error("Query failed: {}", error.getMessage(), error);
        }
    }

    /**
     * The coverage reported for a query that did not finish.
     *
     * @param partialContext The most recently completed stage's context, or null when no
     *                       partial answer may be reported - a hard failure, a
     *                       withdrawal, or a timeout that caught no stage
     * @return Coverage marked degraded, carrying whatever was actually reached
     */
    private IndexCoverage degradedCoverage(PipelineContext partialContext) {
        // A partial answer is still an answer about a corpus, and which segments contributed to
        // it is the difference between "nothing matched" and "not everything was asked". The
        // verdict is already settled - degraded - so these are the counts behind it, not inputs
        // to it.
        List<SegmentQueryTask.SegmentQueryResult> partialSegments =
            partialContext != null ? partialContext.segmentResults : List.of();
        long partialTailScanEntries =
            partialContext != null ? partialContext.totalTailScanEntries : 0L;
        int partialSegmentsTimedOut =
            partialContext != null ? partialContext.segmentsTimedOut : 0;
        int partialSegmentsSearched = CoverageCounts.segmentsAnswering(partialSegments);

        // The census is reported even here: a response that says the index holds no segments,
        // because the query was cut off before it could look, reads exactly like one served by
        // an empty index. It is read defensively because the pipeline may have failed for
        // precisely the reason the catalog cannot be read, and the caller is still owed the
        // degraded response rather than a second failure in place of it.
        CoverageCounts.Census census;
        try {
            census = coverageCounts.census();
        } catch (RuntimeException e) {
            LOGGER.warn("Catalog census unavailable for a degraded response: {}", e.toString());
            census = new CoverageCounts.Census(0, 0, 0, 0, 0, 0);
        }

        return census.onto(IndexCoverage.builder())
            .degraded(true)
            .tailScanEntries(partialTailScanEntries)
            .segmentsSearched(partialSegmentsSearched)
            .segmentsExcluded(partialContext != null ? partialContext.segmentsExcluded : 0)
            .segmentsWithErrors(CoverageCounts.segmentsFailing(partialSegments, partialSegmentsTimedOut))
            .segmentsTimedOut(partialSegmentsTimedOut)
            // Not computeCoverage(): a stage abandoned midway leaves segments that are neither
            // searched nor counted as having failed, and subtracting only what is counted would
            // report the rest as read. What was read is what answered.
            .estimatedCorpusCoverage(census.nonPurged() <= 0 ? 0.0
                : Math.min(1.0, (double) partialSegmentsSearched / census.nonPurged()))
            .build();
    }
}
