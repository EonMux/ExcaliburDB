package excalibur.query.pipeline;

import excalibur.query.QueryOptions;
import excalibur.query.QueryRequest;
import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.ParallelQueryDispatcher;
import excalibur.query.executor.PqSearchSettings;
import excalibur.query.executor.SegmentQueryTask;
import excalibur.shard.visibility.EpochIsolationValidator;
import io.excalibur.vector.distance.DistanceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Stage 3: HNSW Vector Search with Tail Scan.
 *
 * <p>Performs precise ANN search within candidate segments using HNSW.
 * For segments where lastIntegratedOffset &lt; writeLogHead, also performs
 * brute-force tail scan over un-integrated entries.</p>
 *
 * <p><strong>Candidate-Based Execution:</strong> Uses the candidates from
 * stages 0-2 which have been filtered by visibility tracking, Cuckoo filter
 * cascade, and LSH pre-filtering. This ensures that only segments visible
 * at the captured epoch are searched.</p>
 *
 * <p><strong>Parallel Execution:</strong> Searches are executed in parallel
 * using the {@link ParallelQueryDispatcher} for improved latency across
 * multiple segments.</p>
 *
 * <p><strong>Offset Comparison:</strong> For each segment, compares
 * lastIntegratedOffset vs writeLogHead to determine if tail scanning is needed.</p>
 */
final class HnswSearchStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswSearchStage.class);

    /**
     * The share of the remaining query budget Stage 3 leaves for a merge alone.
     *
     * <p>Stage 3 is the only stage that waits on work it did not do itself, so it is the only
     * one that needs a deadline of its own - and that deadline has to end early enough for
     * Stage 4 to still merge what came back. Reranking is bounded work over a bounded candidate
     * set and holds no locks and does no I/O beyond reading vectors already mapped, so the
     * margin it needs is small; what is held back is recall, and holding back more than the
     * merge can use trades recall for nothing.</p>
     */
    private static final double MERGE_SHARE_HELD_BACK = 0.05;

    /**
     * The share Stage 3 leaves when Stage 5 will also run.
     *
     * <p>Relevancy scoring is a call to a model, so unlike the merge its cost is not a function
     * of the candidate count and cannot be bounded from here. The wider margin buys it a
     * realistic chance to answer; if it overruns anyway, the pipeline's own deadline fires and
     * Stage 4's ranking is returned, which is the fallback this stage already has.</p>
     */
    private static final double SCORING_SHARE_HELD_BACK = 0.20;

    /**
     * Supplies the dispatcher, resolved per use so the pipeline still creates it lazily on
     * first query.
     */
    private final Supplier<ParallelQueryDispatcher> dispatcher;

    private final DistanceFunction distanceFunction;
    private final EpochIsolationValidator epochIsolationValidator;

    /** Whether a segment holding a trained quantizer may be searched through it. */
    private final PqSearchSettings pqSearch;

    /**
     * Whether Stage 5 will score this request, which widens the margin this stage leaves
     * behind. The pipeline supplies its own predicate so the stages cannot drift apart.
     */
    private final Predicate<QueryRequest> llmScoringWillRun;

    HnswSearchStage(Supplier<ParallelQueryDispatcher> dispatcher,
                    DistanceFunction distanceFunction,
                    EpochIsolationValidator epochIsolationValidator,
                    Predicate<QueryRequest> llmScoringWillRun,
                    PqSearchSettings pqSearch) {
        this.dispatcher = dispatcher;
        this.distanceFunction = distanceFunction;
        this.epochIsolationValidator = epochIsolationValidator;
        this.llmScoringWillRun = llmScoringWillRun;
        this.pqSearch = pqSearch;
    }

    /**
     * Fans the query out across the candidate segments and collects what they return.
     *
     * @param context The pipeline context with the surviving candidates
     * @param token The cancellation token
     * @param deadline When this query is abandoned, which this stage must finish inside of
     * @return Context carrying the per-segment results
     */
    PipelineContext search(PipelineContext context, CancellationToken token, QueryDeadline deadline) {
        token.checkCancelled("Stage 3 cancelled before HNSW search");

        LOGGER.trace("Stage 3: Running HNSW vector search with {} candidates", context.candidates.size());

        if (context.candidates.isEmpty()) {
            LOGGER.debug("Stage 3: No candidates to search");
            return context.withSegmentResults(List.of(), 0, 0);
        }

        float[] queryVector = context.request.queryVectorUnsafe();

        long startTime = System.nanoTime();

        QueryOptions options = fanOutOptions(context.request, deadline);

        List<ParallelQueryDispatcher.SegmentInfo> segmentsToSearch = segmentsToSearch(context);

        LOGGER.trace("Stage 3: Dispatching to {} valid segments", segmentsToSearch.size());

        // Use ParallelQueryDispatcher for parallel execution
        ParallelQueryDispatcher.DispatchResult dispatchResult = dispatcher.get().dispatchToSpecificSegments(
            segmentsToSearch,
            queryVector,
            options,
            distanceFunction,
            pqSearch
        );

        // Check cancellation after dispatch completes
        token.checkCancelled("Stage 3 cancelled after segment search");

        List<SegmentQueryTask.SegmentQueryResult> segmentResults = dispatchResult.segmentResults();
        long totalTailScanEntries = dispatchResult.tailScanEntries();
        int segmentsTimedOut = dispatchResult.segmentsTimedOut();

        // Apply epoch isolation filtering to HNSW results to prevent torn reads
        // Filter out nodes that were inserted after the query's captured epoch
        segmentResults = EpochIsolationFilter.retainVisible(
            epochIsolationValidator, segmentResults, context.epoch);

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        LOGGER.debug("Stage 3: Search completed - {} segments, {} tail scan entries, {}ms",
            segmentResults.size(), totalTailScanEntries, durationMs);

        return context.withSegmentResults(segmentResults, totalTailScanEntries, segmentsTimedOut);
    }

    /**
     * The options the fan-out runs under, and the slice of the query's budget it may spend.
     *
     * <p>The fan-out gets what is left of the query's budget, less the share Stage 4 needs to
     * merge the answers - not the request's timeout, which is measured from before Stage 0
     * and so would always expire after the pipeline's own deadline. Handing it the full
     * timeout means the pipeline is abandoned first and every segment that did answer is
     * thrown away, which is how a partial result becomes an empty one.</p>
     *
     * @param request The query request, for the shape of the search it asked for
     * @param deadline When this query is abandoned
     * @return The options to dispatch the segment searches with
     */
    private QueryOptions fanOutOptions(QueryRequest request, QueryDeadline deadline) {
        return QueryOptions.builder()
            .topK(request.topK())
            .oversamplingFactor(request.oversamplingFactor())
            .timeout(deadline.budgetHoldingBack(searchShareHeldBack(request)))
            .includePartialSegments(request.includePartialSegments())
            .build();
    }

    /**
     * Describes each candidate to the dispatcher, carrying across what stage 2 decided about it.
     *
     * @param context The pipeline context with the surviving candidates
     * @return The segments to fan out to
     */
    private static List<ParallelQueryDispatcher.SegmentInfo> segmentsToSearch(PipelineContext context) {
        // Convert SegmentCandidates to SegmentInfo for the dispatcher, carrying across what
        // stage 2 decided about each segment. A candidate set that reached here is non-empty and
        // names nodes of this segment's graph, so the search is confined to it; a segment stage 2
        // left alone - no index, an unreadable one, or one whose emptiness proved nothing - is
        // handed a null set and searched whole.
        List<ParallelQueryDispatcher.SegmentInfo> segmentsToSearch = new ArrayList<>();
        for (SegmentCandidate candidate : context.candidates) {
            if (candidate.isValid()) {
                segmentsToSearch.add(ParallelQueryDispatcher.createSegmentInfo(
                    candidate.segmentId(),
                    candidate.segment(),
                    candidate.graph(),
                    candidate.hasLshCandidates() ? candidate.lshCandidates() : null
                ));
            }
        }
        return segmentsToSearch;
    }

    /**
     * The share of the remaining budget Stage 3 must leave behind for the stages after it.
     *
     * @param request The query request
     * @return The fraction to hold back
     */
    private double searchShareHeldBack(QueryRequest request) {
        return llmScoringWillRun.test(request) ? SCORING_SHARE_HELD_BACK : MERGE_SHARE_HELD_BACK;
    }
}
