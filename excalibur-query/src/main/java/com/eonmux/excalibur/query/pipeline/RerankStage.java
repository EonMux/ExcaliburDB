package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.core.text.ChunkSources;
import com.eonmux.excalibur.core.text.SourceScope;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Stage 3: Full Vector Rerank.
 *
 * <p>Re-measurement of the top candidates against the query, on the full-precision vectors
 * the graph holds now. Stage 2 measured them exactly too, so the number is the same for a
 * node that has not moved; the second look is what drops one tombstoned since, or whose id a
 * compaction has since given to another node.</p>
 *
 * <h2>Reranking Process</h2>
 * <ol>
 *   <li>Aggregate results from Stage 2 using ResultAggregator</li>
 *   <li>Group candidates by segment ID</li>
 *   <li>For each segment, use VectorReranker to compute exact distances from full-precision vectors stored in HNSW graph</li>
 *   <li>Merge all reranked results and take top-k</li>
 *   <li>Apply DocumentAtomicityFilter to exclude partial documents</li>
 *   <li>Keep only the chunks of the sources the request is scoped to, when it names any - see
 *       {@link QueryRequest#sourceScope()}</li>
 *   <li>Drop results that carry no chunk identity, when the request can only use named
 *       ones - see {@link QueryRequest#requireNamedResults()}</li>
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
 * from partial documents. The stage reports how many it withheld, and when that leaves the
 * answer short with more to find, {@link VisibilityRefill} runs Stages 2 and 3 again deeper.</p>
 */
final class RerankStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(RerankStage.class);

    /**
     * Multiplier applied to topK when collecting Stage 3 rerank candidates.
     */
    private static final int RERANK_CANDIDATE_MULTIPLIER = 2;

    private final DistanceFunction distanceFunction;
    private final double coverageThreshold;
    private final DocumentVisibilityChecker visibilityChecker;

    /** Names each chunk's source, for a request scoped to some; null refuses such a request. */
    private final ChunkSources chunkSources;

    /**
     * Whether Stage 4 will score this request, which decides whether this stage may truncate
     * to topK or must leave spare candidates behind. The pipeline supplies its own predicate
     * so that the two stages cannot drift apart.
     */
    private final Predicate<QueryRequest> llmScoringWillRun;

    RerankStage(DistanceFunction distanceFunction,
                double coverageThreshold,
                DocumentVisibilityChecker visibilityChecker,
                ChunkSources chunkSources,
                Predicate<QueryRequest> llmScoringWillRun) {
        this.distanceFunction = distanceFunction;
        this.coverageThreshold = coverageThreshold;
        this.visibilityChecker = visibilityChecker;
        this.chunkSources = chunkSources;
        this.llmScoringWillRun = llmScoringWillRun;
    }

    /**
     * Reranks, filters and (when Stage 4 will not run) truncates the merged results.
     *
     * @param context The pipeline context carrying the per-segment results
     * @param token The cancellation token
     * @return Context with the final ranking
     */
    PipelineContext rerank(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 3 cancelled before reranking");

        LOGGER.trace("Stage 3: Running full vector rerank");

        // Step 1: Aggregate results from Stage 2 to get candidates
        // Oversample so reranking and filtering have spare candidates to work with, from the
        // depth this round searched to - the request's topK, or deeper on a refill. Both are
        // client-bounded, so widen before multiplying and saturate instead of wrapping to a
        // negative candidate count.
        int candidateCount = rerankCandidateCount(context.searchDepth);
        ResultAggregator aggregator = new ResultAggregator(
            candidateCount,
            context.request.threshold(),
            coverageThreshold
        );

        List<QueryResultItem> candidates = aggregator.mergeAndRankWithNormalization(
            context.segmentResults, distanceFunction);
        // A merge that came back shallower than it was asked, over segments that each came back
        // shallower than they were asked, had nothing further to give, so however this ranking
        // is narrowed afterwards - by the atomicity filter or a relevancy threshold included -
        // nothing past it was left unconsidered. That is what lets a short answer's total be
        // exact. The merge alone cannot say it: each segment answers with at most the depth, so
        // on a corpus of one segment the merge is always shallower than twice the depth.
        boolean exhausted = candidates.size() < candidateCount && context.segmentsRanDry;

        if (candidates.isEmpty()) {
            LOGGER.debug("Stage 3: No candidates to rerank");
            return context.withRanking(candidates, exhausted, 0);
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
        int withheld = beforeAtomicityFilter - rerankedResults.size();

        // Before truncation, for the same reason as the atomicity filter: a dropped result is
        // replaced by the next named one rather than leaving the page short. Before the scope,
        // too, so a result with no name is reported as the damage it is rather than counted as
        // out of scope, which would hide it on every scoped request.
        int beforeNaming = rerankedResults.size();
        if (context.request.requireNamedResults()) {
            rerankedResults = namedOnly(rerankedResults);
        }
        int unnamedDropped = beforeNaming - rerankedResults.size();

        // Out of scope counts as withheld: either way a result the answer cannot use was ranked
        // above ones it can, and the refill ranks deeper past both alike.
        int beforeScope = rerankedResults.size();
        rerankedResults = inScope(rerankedResults, context.request.sourceScope());
        withheld += beforeScope - rerankedResults.size();

        // Step 6: Truncate to topK once the result set is final. When Stage 4 is going to
        // run, leave the oversampled list intact: its threshold filter would otherwise be
        // applied to an already-truncated list and return fewer than topK results even
        // though enough candidates passed the threshold.
        if (!llmScoringWillRun.test(context.request)) {
            rerankedResults = QueryPipeline.truncate(rerankedResults, context.request.topK());
        }

        LOGGER.debug("Stage 3: Reranked {} candidates to {} results (atomicity filtered from {})",
            candidates.size(), rerankedResults.size(), beforeAtomicityFilter);

        return context.withRanking(rerankedResults, exhausted, unnamedDropped, withheld);
    }

    /**
     * Keeps the results from the sources a request is scoped to.
     *
     * <p>Applied to the whole candidate list before it is cut to topK, for the reason the
     * atomicity filter is: filtering the cut list returns fewer than topK whenever a refused
     * result ranked inside it. A result carrying no chunk id cannot be placed in any source and
     * is refused.</p>
     *
     * @param results The reranked results
     * @param scope   The request's scope
     * @return The results the scope admits, or the input unchanged for {@link SourceScope#ALL}
     * @throws IllegalStateException if the request is scoped and this pipeline cannot say which
     *                               source a chunk is from - answering it from every source
     *                               would hand the caller results it excluded
     */
    private List<QueryResultItem> inScope(List<QueryResultItem> results, SourceScope scope) {
        if (scope.isUnrestricted()) {
            return results;
        }
        if (chunkSources == null) {
            throw new IllegalStateException("this pipeline cannot attribute chunks to sources, "
                + "so it cannot answer a request scoped to " + scope.sourceIds());
        }
        List<QueryResultItem> admitted = new ArrayList<>(results.size());
        for (QueryResultItem item : results) {
            if (scope.admits(item.documentIdStr(), chunkSources)) {
                admitted.add(item);
            }
        }
        return admitted;
    }

    /**
     * The results that carry the identifier ingestion assigned.
     *
     * <p>A result without one is a graph node the node-to-chunk mapping has no entry for:
     * its mapping file could not be opened, or lost entries it had not yet persisted. The
     * search found it, but the only name it has is its node id, which compaction renumbers
     * and which names nothing a caller outside this process can fetch, fuse or erase by.
     * Dropping it is logged with the segments involved, because nothing rebuilds a mapping in
     * place - the cure is restoring those segments' graphs together with their mappings, or
     * reindexing what they hold - and the answer says it is degraded, because the node found
     * something it could not return.</p>
     *
     * @param results The ranking
     * @return The ranking without unnamed results, the same list when there were none
     */
    private static List<QueryResultItem> namedOnly(List<QueryResultItem> results) {
        List<QueryResultItem> named = new ArrayList<>(results.size());
        Set<Long> unnamedSegments = new TreeSet<>();
        for (QueryResultItem item : results) {
            String name = item.documentIdStr();
            if (name == null || name.isEmpty()) {
                unnamedSegments.add(item.segmentId());
            } else {
                named.add(item);
            }
        }
        if (named.size() == results.size()) {
            return results;
        }
        LOGGER.warn("Stage 3: dropped {} result(s) that carry no chunk identity, from "
            + "segment(s) {}; their node-to-chunk mapping is missing entries. Run "
            + "excalibur-fsck, then restore each graph with its mapping or reindex its source",
            results.size() - named.size(),
            unnamedSegments);
        return named;
    }

    /**
     * Recomputes exact distances a segment at a time, against the graph that holds the vectors.
     *
     * @param context The pipeline context, which carries the segment candidates and their graphs
     * @param candidates The merged candidates from Stage 2
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
                LOGGER.warn("Stage 3: No graph found for segment {}, keeping {} candidates with approximate distances",
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
     * Returns the number of candidates Stage 3 collects for reranking.
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
     * <p>{@link DocumentAtomicityFilter#boundFor}, the one rule every filtering path applies: the
     * query's own captured epoch when the checker publishes on the clock that epoch came from,
     * and {@link DocumentAtomicityFilter#UNBOUNDED} otherwise. A negative epoch is not a snapshot
     * anyone captured; the filter rejects it outright, so falling back keeps a malformed context
     * from failing the whole query.</p>
     *
     * @param checker    The visibility checker the filter will consult
     * @param queryEpoch The epoch captured in stage 0
     * @return The bound to filter with
     */
    static long atomicityBoundFor(DocumentVisibilityChecker checker, long queryEpoch) {
        return DocumentAtomicityFilter.boundFor(checker, queryEpoch);
    }

    /**
     * Applies the document atomicity filter, if it can say anything useful.
     *
     * <p>The filter excludes results belonging to documents that are not yet fully
     * visible. It can only do that for documents the visibility checker knows about; when
     * the checker holds nothing at all, committed or in flight, it has nothing to compare
     * against, and a checker that cannot tell an unknown document from an incomplete one would
     * express "unknown" as "not visible" for every result in the response. Documents in flight
     * count as something to compare against: a node restarted over documents a previous process
     * left half-written has published nothing yet, and those are exactly the documents to
     * withhold - see {@link DocumentAtomicityFilter#appliesTo}.</p>
     *
     * <h3>Which epoch bounds it</h3>
     * <p>The query's own captured epoch, when the checker publishes on the same clock the
     * query captured from - which {@link DocumentVisibilityChecker#sharesNodeEpochClock()}
     * reports. That is a real snapshot bound: a document committed after this query started is
     * excluded from it, so a long-running query does not pick up writes partway through.</p>
     *
     * <p>Otherwise {@link DocumentAtomicityFilter#UNBOUNDED}, and the filter degrades to a
     * completeness check. Bounding by a foreign counter is worse than not bounding: the two
     * numbers are unrelated, so the comparison excludes or admits documents arbitrarily, and it
     * does so while looking exactly like a snapshot.</p>
     *
     * @param results The reranked results
     * @param queryEpoch The epoch this query captured in stage 0
     * @return The results that survive the filter, or the input unchanged when the filter
     *         is not configured or holds no document, committed or in flight
     */
    private List<QueryResultItem> applyAtomicityFilter(List<QueryResultItem> results,
                                                       long queryEpoch) {
        if (!DocumentAtomicityFilter.appliesTo(visibilityChecker)) {
            LOGGER.trace("Stage 3: Skipping document atomicity filter - no document tracked");
            return results;
        }
        int registeredDocuments = visibilityChecker.getDocumentCount();

        long bound = atomicityBoundFor(visibilityChecker, queryEpoch);
        boolean bounded = bound != DocumentAtomicityFilter.UNBOUNDED;

        DocumentAtomicityFilter atomicityFilter = new DocumentAtomicityFilter(visibilityChecker);
        List<QueryResultItem> filtered = atomicityFilter.filterPartialDocuments(results, bound);

        if (filtered.size() < results.size()) {
            LOGGER.debug("Stage 3: Document atomicity filter removed {} partial document results "
                + "({} documents registered, bound={})", results.size() - filtered.size(),
                registeredDocuments, bounded ? String.valueOf(bound) : "unbounded");
        }

        return filtered;
    }
}
