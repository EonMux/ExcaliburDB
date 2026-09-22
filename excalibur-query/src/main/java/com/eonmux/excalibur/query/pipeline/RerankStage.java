package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.ResultAggregator;
import com.eonmux.excalibur.query.visibility.DocumentAtomicityFilter;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Stage 4: Full Vector Rerank.
 *
 * <p>Re-measurement of the top candidates against the query, on the full-precision vectors
 * the graph holds now. Stage 3 measured them exactly too, so the number is the same for a
 * node that has not moved; the second look is what drops one tombstoned since, or whose id a
 * compaction has since given to another node.</p>
 *
 * <h2>Reranking Process</h2>
 * <ol>
 *   <li>Aggregate results from Stage 3 using ResultAggregator</li>
 *   <li>Group candidates by segment ID</li>
 *   <li>For each segment, use VectorReranker to compute exact distances from full-precision vectors stored in HNSW graph</li>
 *   <li>Merge all reranked results and take top-k</li>
 *   <li>Apply DocumentAtomicityFilter to exclude partial documents</li>
 * </ol>
 *
 * <h2>Why Reranking is Critical</h2>
 * <ul>
 *   <li><strong>Freshness:</strong> each candidate is re-read under the generation it was
 *       found at, so nodes tombstoned or displaced by compaction between the stages drop out</li>
 *   <li><strong>Tail scan integration:</strong> Results from HNSW and tail scan are merged with consistent exact distance computation</li>
 *   <li><strong>One ordering:</strong> the oversampled candidates from every segment are ranked together before the top-k cut</li>
 * </ul>
 *
 * <p><strong>Document Atomicity:</strong> After merging results, this stage
 * applies the DocumentAtomicityFilter to ensure queries never see partially-
 * ingested documents. All chunks from a visible document are included; none
 * from partial documents.</p>
 */
final class RerankStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(RerankStage.class);

    /**
     * Multiplier applied to topK when collecting Stage 4 rerank candidates.
     */
    private static final int RERANK_CANDIDATE_MULTIPLIER = 2;

    /**
     * Epoch handed to the document atomicity filter, meaning "no epoch bound".
     *
     * <p>The pipeline's own epoch ({@code PipelineContext.epoch}) comes from the shard's
     * {@link VisibilityTracker}, which counts segment lifecycle transitions and periodic
     * ticks. Document commit epochs come from the ingest-side visibility tracker, a
     * separate counter advanced once per published document. The two numbers have no
     * defined ordering relationship, so comparing a document's commit epoch against the
     * shard epoch decides visibility on whichever counter happens to be running faster -
     * dropping committed documents when ingest is ahead, and asserting nothing when the
     * shard tick is.</p>
     *
     * <p>Rather than perform a comparison across two unrelated domains, the filter is
     * applied without an epoch bound, which reduces it to exactly the guarantee it exists
     * to provide: a result is dropped only when its document is registered as not yet
     * complete. Epoch-based snapshot isolation is still enforced, in its own domain, by
     * the shard {@code EpochIsolationValidator} in Stage 3.</p>
     *
     * <p>Restoring an epoch bound here requires the query epoch to be drawn from the same
     * counter that stamps document commits - i.e. an accessor for the current commit epoch
     * on {@code DocumentVisibilityChecker}, captured in Stage 0 alongside the segment
     * epoch.</p>
     */
    private static final long DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED = Long.MAX_VALUE;

    private final DistanceFunction distanceFunction;
    private final double coverageThreshold;
    private final DocumentVisibilityChecker visibilityChecker;

    /**
     * Whether Stage 5 will score this request, which decides whether this stage may truncate
     * to topK or must leave spare candidates behind. The pipeline supplies its own predicate
     * so that the two stages cannot drift apart.
     */
    private final Predicate<QueryRequest> llmScoringWillRun;

    RerankStage(DistanceFunction distanceFunction,
                double coverageThreshold,
                DocumentVisibilityChecker visibilityChecker,
                Predicate<QueryRequest> llmScoringWillRun) {
        this.distanceFunction = distanceFunction;
        this.coverageThreshold = coverageThreshold;
        this.visibilityChecker = visibilityChecker;
        this.llmScoringWillRun = llmScoringWillRun;
    }

    /**
     * Reranks, filters and (when Stage 5 will not run) truncates the merged results.
     *
     * @param context The pipeline context carrying the per-segment results
     * @param token The cancellation token
     * @return Context with the final ranking
     */
    PipelineContext rerank(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 4 cancelled before reranking");

        LOGGER.trace("Stage 4: Running full vector rerank");

        // Step 1: Aggregate results from Stage 3 to get candidates
        // Oversample so reranking and filtering have spare candidates to work with.
        // topK is client-supplied, so widen before multiplying and saturate instead of
        // wrapping to a negative candidate count.
        ResultAggregator aggregator = new ResultAggregator(
            rerankCandidateCount(context.request.topK()),
            context.request.threshold(),
            coverageThreshold
        );

        List<QueryResultItem> candidates = aggregator.mergeAndRankWithNormalization(
            context.segmentResults, distanceFunction);

        if (candidates.isEmpty()) {
            LOGGER.debug("Stage 4: No candidates to rerank");
            return context.withMergedResults(candidates);
        }

        List<QueryResultItem> rerankedResults = rerankBySegment(context, candidates);

        // Step 4: Sort by normalized score descending (highest/best first).
        // Normalized scores are in [0.0, 1.0] where higher is better (more similar).
        rerankedResults = ResultAggregator.sortByRelevanceDescending(rerankedResults);

        int beforeAtomicityFilter = rerankedResults.size();

        // Step 5: Apply the document atomicity filter to the FULL candidate list, before
        // truncating. Filtering after truncation would return fewer than topK results
        // whenever a partial document happened to rank inside the first topK, even though
        // enough valid candidates were available.
        rerankedResults = applyAtomicityFilter(rerankedResults, context.epoch);

        // Step 6: Truncate to topK once the result set is final. When Stage 5 is going to
        // run, leave the oversampled list intact: its threshold filter would otherwise be
        // applied to an already-truncated list and return fewer than topK results even
        // though enough candidates passed the threshold.
        if (!llmScoringWillRun.test(context.request)) {
            rerankedResults = QueryPipeline.truncate(rerankedResults, context.request.topK());
        }

        LOGGER.debug("Stage 4: Reranked {} candidates to {} results (atomicity filtered from {})",
            candidates.size(), rerankedResults.size(), beforeAtomicityFilter);

        return context.withMergedResults(rerankedResults);
    }

    /**
     * Recomputes exact distances a segment at a time, against the graph that holds the vectors.
     *
     * @param context The pipeline context, which carries the segment candidates and their graphs
     * @param candidates The merged candidates from Stage 3
     * @return The reranked candidates, in no particular order
     */
    private List<QueryResultItem> rerankBySegment(PipelineContext context,
                                                  List<QueryResultItem> candidates) {
        // Step 2: Group candidates by segment ID for efficient reranking
        Map<Long, List<QueryResultItem>> bySegment = candidates.stream()
            .collect(Collectors.groupingBy(QueryResultItem::segmentId));

        // Step 3: Create VectorReranker and rerank each segment's candidates
        VectorReranker reranker = new VectorReranker(distanceFunction);
        float[] queryVector = context.request.queryVectorUnsafe();

        List<QueryResultItem> rerankedResults = new ArrayList<>();

        for (Map.Entry<Long, List<QueryResultItem>> entry : bySegment.entrySet()) {
            long segmentId = entry.getKey();
            List<QueryResultItem> segmentCandidates = entry.getValue();

            // Find the SegmentCandidate for this segment to get the HNSW graph
            SegmentCandidate segmentCandidate = context.candidates.stream()
                .filter(sc -> sc.segmentId() == segmentId)
                .findFirst()
                .orElse(null);

            if (segmentCandidate == null || segmentCandidate.graph() == null) {
                LOGGER.warn("Stage 4: No graph found for segment {}, keeping {} candidates with approximate distances",
                    segmentId, segmentCandidates.size());
                rerankedResults.addAll(segmentCandidates);
                continue;
            }

            // Rerank by computing exact distances on full-precision vectors
            List<QueryResultItem> segmentReranked = reranker.rerank(
                segmentCandidates,
                queryVector,
                segmentCandidate.graph(),
                segmentCandidates.size() // Keep all for final merge
            );

            rerankedResults.addAll(segmentReranked);
        }

        return rerankedResults;
    }

    /**
     * Returns the number of candidates Stage 4 collects for reranking.
     *
     * @param topK The requested result count (client-controlled)
     * @return {@code topK * RERANK_CANDIDATE_MULTIPLIER}, saturated at {@link Integer#MAX_VALUE}
     */
    private static int rerankCandidateCount(int topK) {
        long widened = (long) topK * RERANK_CANDIDATE_MULTIPLIER;
        return widened > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) widened;
    }

    /**
     * The epoch to bound the atomicity filter by.
     *
     * <p>The query's own captured epoch when the checker publishes on the clock that epoch came
     * from, and {@link #DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED} otherwise. A rule rather than an
     * inline condition because getting it wrong is silent in both directions: bounding by a
     * foreign counter excludes documents arbitrarily, and never bounding gives up snapshot
     * isolation without saying so.
     *
     * @param checker    The visibility checker the filter will consult
     * @param queryEpoch The epoch captured in stage 0
     * @return The bound to filter with
     */
    static long atomicityBoundFor(DocumentVisibilityChecker checker, long queryEpoch) {
        if (checker == null || !checker.sharesNodeEpochClock()) {
            return DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED;
        }
        // A negative epoch is not a snapshot anyone captured; the filter rejects it outright,
        // so falling back keeps a malformed context from failing the whole query.
        return queryEpoch >= 0 ? queryEpoch : DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED;
    }

    /**
     * Applies the document atomicity filter, if it can say anything useful.
     *
     * <p>The filter excludes results belonging to documents that are not yet fully
     * visible. It can only do that for documents the visibility checker knows about; when
     * the checker holds no registrations at all - which is the case whenever ingestion
     * runs its per-chunk path rather than atomic document batching - it has nothing to
     * compare against, and running it would express "unknown" as "not visible" for every
     * result in the response.</p>
     *
     * <h3>Which epoch bounds it</h3>
     * <p>The query's own captured epoch, when the checker publishes on the same clock the
     * query captured from - which {@link DocumentVisibilityChecker#sharesNodeEpochClock()}
     * reports. That is a real snapshot bound: a document committed after this query started is
     * excluded from it, so a long-running query does not pick up writes partway through.</p>
     *
     * <p>Otherwise {@link #DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED}, and the filter degrades to a
     * completeness check. Bounding by a foreign counter is worse than not bounding: the two
     * numbers are unrelated, so the comparison excludes or admits documents arbitrarily, and it
     * does so while looking exactly like a snapshot.</p>
     *
     * @param results The reranked results
     * @param queryEpoch The epoch this query captured in stage 0
     * @return The results that survive the filter, or the input unchanged when the filter
     *         is not configured or holds no document registrations
     */
    private List<QueryResultItem> applyAtomicityFilter(List<QueryResultItem> results,
                                                       long queryEpoch) {
        if (visibilityChecker == null) {
            return results;
        }

        int registeredDocuments = visibilityChecker.getDocumentCount();
        if (registeredDocuments == 0) {
            LOGGER.trace("Stage 4: Skipping document atomicity filter - no documents registered");
            return results;
        }

        long bound = atomicityBoundFor(visibilityChecker, queryEpoch);
        boolean bounded = bound != DOCUMENT_ATOMICITY_EPOCH_UNBOUNDED;

        DocumentAtomicityFilter atomicityFilter = new DocumentAtomicityFilter(visibilityChecker);
        List<QueryResultItem> filtered = atomicityFilter.filterPartialDocuments(results, bound);

        if (filtered.size() < results.size()) {
            LOGGER.debug("Stage 4: Document atomicity filter removed {} partial document results "
                + "({} documents registered, bound={})", results.size() - filtered.size(),
                registeredDocuments, bounded ? String.valueOf(bound) : "unbounded");
        }

        return filtered;
    }
}
