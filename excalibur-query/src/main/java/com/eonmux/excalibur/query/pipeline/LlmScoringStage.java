package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.ResultAggregator;
import com.eonmux.excalibur.query.payload.ResultPayloadResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Predicate;

/**
 * Stage 5: LLM Relevancy Scoring.
 *
 * <p>Sends top-k candidates + original query text to LLM for relevancy
 * scoring. Discards results below threshold. Falls back to vector-only
 * ranking if LLM is unavailable.</p>
 *
 * <h2>Candidate Text</h2>
 * <p>Stage 4 hands over results that identify nodes, not document bodies. This stage
 * resolves each surviving result's stored chunk text through
 * {@link ResultPayloadResolver} before scoring; a write-log tail hit already carries
 * its text from the scan and is left alone.</p>
 *
 * <h2>Sorting After LLM Scoring</h2>
 * <p>After LLM scoring, results are sorted by relevance score in descending
 * order (highest relevance first). This ensures the most relevant results
 * appear at the top of the response.</p>
 */
final class LlmScoringStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmScoringStage.class);

    private final QueryPipeline.LlmScorer llmScorer;
    private final ResultPayloadResolver payloadResolver;

    /**
     * Whether this stage will actually score a given request. The pipeline supplies its own
     * predicate, so this stage and the two before it cannot drift apart on the answer.
     */
    private final Predicate<QueryRequest> llmScoringWillRun;

    LlmScoringStage(QueryPipeline.LlmScorer llmScorer,
                    ResultPayloadResolver payloadResolver,
                    Predicate<QueryRequest> llmScoringWillRun) {
        this.llmScorer = llmScorer;
        this.payloadResolver = payloadResolver;
        this.llmScoringWillRun = llmScoringWillRun;
    }

    /**
     * Scores the ranked candidates against the query text, or leaves them as they are.
     *
     * @param context The pipeline context carrying Stage 4's ranking
     * @param token The cancellation token
     * @return Context with the scored ranking, or the vector ranking on fallback
     */
    PipelineContext score(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 5 cancelled before LLM scoring");

        if (!llmScoringWillRun.test(context.request)) {
            LOGGER.trace("Stage 5: Skipping LLM scoring (disabled, no scorer, or no query text)");
            // Stage 4 already truncated to topK in this case
            return context.withLlmScored(false);
        }

        LOGGER.trace("Stage 5: Running LLM relevancy scoring");

        int topK = context.request.topK();

        // Relevancy scoring compares the query against candidate TEXT, and a search result
        // identifies a node rather than a document body. Resolve the stored chunk text now
        // - after selection has narrowed the candidate set, because each resolution is a
        // storage read - so the scorer has something real to rank.
        List<QueryResultItem> candidates = payloadResolver.attachPayloads(context.mergedResults);

        try {
            List<QueryResultItem> scoredResults = llmScorer.scoreResults(
                context.request.queryText(),
                candidates,
                context.request.llmThreshold()
            );

            // Sort by relevance score descending (highest first)
            // LLM relevancy scores are in range [0.0, 1.0] where higher is better
            scoredResults = ResultAggregator.sortByRelevanceDescending(scoredResults);

            LOGGER.debug("Stage 5: LLM scored {} results, {} after threshold filtering",
                candidates.size(), scoredResults.size());

            // Truncate only now, so the threshold filter chose from the full candidate set
            return context.withMergedResults(QueryPipeline.truncate(scoredResults, topK))
                .withLlmScored(true);

        } catch (Exception e) {
            LOGGER.warn("LLM scoring failed, using vector-only ranking: {}", e.getMessage());
            // Stage 4 deliberately left the list oversampled for this stage; fall back to
            // the vector ranking, truncated to the requested size.
            return context
                .withMergedResults(QueryPipeline.truncate(candidates, topK))
                .withLlmScored(false)
                .withLlmFallback(true);
        }
    }
}
