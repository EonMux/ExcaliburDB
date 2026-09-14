package excalibur.query.pipeline;

import excalibur.filter.sketch.CountMinSketch;
import excalibur.query.executor.CancellationToken;
import excalibur.query.plan.SelectivityEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Orders a query's candidate segments so the ones most likely to answer are searched first.
 *
 * <p>Estimates query selectivity for each segment using Count-Min Sketch term frequency data,
 * then sorts by that estimate.</p>
 *
 * <h2>Selectivity-Based Optimization</h2>
 * <ul>
 *   <li><strong>Segment prioritization:</strong> Segments with higher selectivity
 *       (more relevant terms) are searched first</li>
 *   <li><strong>Order only:</strong> every candidate segment is still searched. A low
 *       selectivity moves a segment down the list, it does not take it off: the estimate
 *       comes from a Count-Min sketch, which over-counts, and dropping a segment on an
 *       over-count loses results that are in the corpus. What the ordering buys is that a
 *       query which runs out of time has spent it on the segments most likely to
 *       answer</li>
 *   <li><strong>Conservative defaults:</strong> Segments without frequency sketches
 *       are assigned default selectivity (1.0) to ensure no data loss</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Selectivity values are clamped to [0.0, 1.0]</li>
 *   <li>Segments without sketches get default selectivity (1.0)</li>
 *   <li>Empty query text results in no reordering</li>
 * </ul>
 */
final class SegmentPrioritizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentPrioritizer.class);

    private SegmentPrioritizer() {
    }

    /**
     * Returns the context with its candidates ordered by estimated selectivity, descending.
     *
     * @param context The pipeline context with segment candidates
     * @param token The cancellation token
     * @return Context with candidates sorted by selectivity
     */
    static PipelineContext prioritize(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 0b cancelled before selectivity estimation");

        LOGGER.trace("Stage 0b: Running selectivity estimation");

        // If no query text, skip selectivity estimation
        if (context.request.queryText() == null || context.request.queryText().isEmpty()) {
            LOGGER.debug("Stage 0b: No query text, skipping selectivity estimation");
            return context;
        }

        // Extract query terms for selectivity estimation
        List<String> queryTerms = extractQueryTerms(context.request.queryText());
        if (queryTerms.isEmpty()) {
            LOGGER.debug("Stage 0b: No query terms extracted, skipping selectivity estimation");
            return context;
        }

        List<SegmentCandidate> candidates = new ArrayList<>(context.candidates);
        SelectivityScores scores = scoreBySelectivity(candidates, queryTerms, token);
        List<SegmentCandidate> prioritizedCandidates = mostSelectiveFirst(scores.scored());

        LOGGER.debug("Stage 0b: Prioritized {} segments by selectivity ({} with sketch, {} without)",
            prioritizedCandidates.size(), scores.segmentsWithSketch(), scores.segmentsWithoutSketch());

        return context.withCandidates(prioritizedCandidates);
    }

    /**
     * Estimates every candidate's selectivity, leaving them in the order they arrived.
     *
     * <p>Counts the segments the estimate could actually be measured from separately from the
     * ones that fell back to the default. A run where every segment took the default has
     * ordered nothing, and the two counts in the stage's log are what distinguishes that from
     * an ordering the sketches genuinely produced.</p>
     *
     * @param candidates The candidate segments
     * @param queryTerms The query terms to estimate against
     * @param token The cancellation token, checked once per segment
     * @return The candidates paired with their estimates, and the two counts
     */
    private static SelectivityScores scoreBySelectivity(List<SegmentCandidate> candidates,
                                                        List<String> queryTerms,
                                                        CancellationToken token) {
        int segmentsWithSketch = 0;
        int segmentsWithoutSketch = 0;

        // Calculate selectivity scores for each segment
        List<SegmentCandidateWithScore> scoredCandidates = new ArrayList<>(candidates.size());
        for (SegmentCandidate candidate : candidates) {
            token.checkCancelled("Stage 0b cancelled during selectivity calculation");

            double selectivity = calculateSegmentSelectivity(candidate, queryTerms);

            if (candidate.hasTermFrequencySketch()) {
                segmentsWithSketch++;
            } else {
                segmentsWithoutSketch++;
            }

            scoredCandidates.add(new SegmentCandidateWithScore(candidate, selectivity));
        }

        return new SelectivityScores(scoredCandidates, segmentsWithSketch, segmentsWithoutSketch);
    }

    /**
     * Drops the estimates and keeps the candidates, highest estimate first.
     *
     * @param scored The candidates paired with their selectivity estimates
     * @return The candidates alone, in the order the query should search them
     */
    private static List<SegmentCandidate> mostSelectiveFirst(List<SegmentCandidateWithScore> scored) {
        // Sort by selectivity descending (highest first)
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // Extract sorted candidates
        List<SegmentCandidate> prioritizedCandidates = new ArrayList<>(scored.size());
        for (SegmentCandidateWithScore s : scored) {
            prioritizedCandidates.add(s.candidate);
        }
        return prioritizedCandidates;
    }

    /**
     * The candidates with their selectivity estimates, and how those estimates were arrived at.
     *
     * @param scored The candidates paired with their estimates
     * @param segmentsWithSketch How many carried a term-frequency sketch to estimate from
     * @param segmentsWithoutSketch How many fell back to the default estimate
     */
    private record SelectivityScores(List<SegmentCandidateWithScore> scored,
                                     int segmentsWithSketch,
                                     int segmentsWithoutSketch) {
    }

    /**
     * Extracts query terms from query text for selectivity estimation.
     *
     * <p>Uses whitespace tokenization to match the ingestion pipeline.</p>
     *
     * @param queryText The query text
     * @return List of query terms
     */
    private static List<String> extractQueryTerms(String queryText) {
        if (queryText == null || queryText.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();
        StringBuilder currentTerm = new StringBuilder();

        for (int i = 0; i < queryText.length(); i++) {
            char c = queryText.charAt(i);
            if (Character.isWhitespace(c)) {
                if (currentTerm.length() > 0) {
                    terms.add(currentTerm.toString().toLowerCase(Locale.ROOT));
                    currentTerm.setLength(0);
                }
            } else {
                currentTerm.append(c);
            }
        }

        // Add final term if any
        if (currentTerm.length() > 0) {
            terms.add(currentTerm.toString().toLowerCase(Locale.ROOT));
        }

        return terms;
    }

    /**
     * Calculates selectivity for a segment based on query terms.
     *
     * <p>Uses the segment's Count-Min Sketch to estimate how many documents
     * in the segment contain the query terms. Returns 1.0 (default) if no
     * sketch is available.</p>
     *
     * @param candidate The segment candidate
     * @param queryTerms The query terms
     * @return Estimated selectivity (0.0 to 1.0)
     */
    private static double calculateSegmentSelectivity(SegmentCandidate candidate, List<String> queryTerms) {
        CountMinSketch sketch = candidate.termFrequencySketch();

        if (sketch == null) {
            // No sketch available - use default selectivity
            return SelectivityEstimator.DEFAULT_SELECTIVITY;
        }

        SelectivityEstimator estimator = new SelectivityEstimator(sketch);
        return estimator.estimateCombinedSelectivity(queryTerms);
    }

    /**
     * Helper class to associate a segment candidate with its selectivity score.
     */
    private static final class SegmentCandidateWithScore {
        final SegmentCandidate candidate;
        final double score;

        SegmentCandidateWithScore(SegmentCandidate candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }
}
