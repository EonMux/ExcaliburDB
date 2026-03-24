package excalibur.query.scorer;

import excalibur.query.QueryResultItem;
import excalibur.query.pipeline.QueryPipeline.LlmScorer;
import io.excalibur.llm.RelevancyScore;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.llm.LLMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * LLM-based relevancy scorer for query results.
 *
 * <p>LlmRelevancyScorer implements the {@link LlmScorer} interface to provide
 * LLM-based semantic relevancy scoring for query results. It extracts candidate
 * text from query result payloads and sends them to an LLM endpoint for scoring.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Scores are in range [0.0, 1.0] where higher is more relevant</li>
 *   <li>Results with score below threshold are filtered out</li>
 *   <li>Empty or null payloads are handled gracefully (empty string sent to LLM)</li>
 *   <li>Original result metadata (documentId, segmentId, nodeId) is preserved</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li>LLM unavailability: throws exception to allow fallback in QueryPipeline</li>
 *   <li>Invalid responses: throws exception for caller to handle</li>
 *   <li>Empty candidate list: returns empty result list (no LLM call made)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is thread-safe.</strong> The underlying {@link LLMHttpClient}
 * must be thread-safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class LlmRelevancyScorer implements LlmScorer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmRelevancyScorer.class);

    private final LLMHttpClient llmClient;

    /**
     * Creates a new LlmRelevancyScorer.
     *
     * @param llmClient The LLM HTTP client for making relevancy scoring requests
     * @throws NullPointerException if llmClient is null
     */
    public LlmRelevancyScorer(LLMHttpClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "LLMHttpClient cannot be null");
    }

    /**
     * Scores query results using LLM-based relevancy scoring.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Extracts candidate text from each result's payload</li>
     *   <li>Sends the query and candidates to the LLM for scoring</li>
     *   <li>Maps the scores back to the original results</li>
     *   <li>Filters out results below the threshold</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Threshold: should be in [0.0, 1.0], values outside are clamped</li>
     *   <li>Scores: LLM returns scores in [0.0, 1.0], already clamped by RelevancyScore</li>
     *   <li>Results: preserves all original metadata, only updates score</li>
     * </ul>
     *
     * @param queryText The query text to score against (must not be null or empty)
     * @param results The results to score (must not be null, may be empty)
     * @param threshold The minimum score threshold (results below are filtered)
     * @return Scored and filtered results, sorted by score descending
     * @throws NullPointerException if queryText or results is null
     * @throws LLMException if the LLM call fails
     */
    @Override
    public List<QueryResultItem> scoreResults(
            String queryText,
            List<QueryResultItem> results,
            float threshold) {

        Objects.requireNonNull(queryText, "queryText cannot be null");
        Objects.requireNonNull(results, "results cannot be null");

        // Handle empty results - no LLM call needed
        if (results.isEmpty()) {
            LOGGER.debug("No results to score, returning empty list");
            return Collections.emptyList();
        }

        // Clamp threshold to valid range [0.0, 1.0]
        float clampedThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
        LOGGER.debug("Scoring {} results for query '{}' with threshold {}",
            results.size(), truncateForLogging(queryText), clampedThreshold);

        // Extract candidate text from result payloads
        List<String> candidates = extractCandidates(results);

        // Call LLM for relevancy scoring
        List<RelevancyScore> scores;
        try {
            scores = llmClient.scoreRelevancy(queryText, candidates);
        } catch (LLMException e) {
            LOGGER.warn("LLM relevancy scoring failed: {}", e.getMessage());
            throw e;
        }

        // Validate response size matches input
        if (scores.size() != results.size()) {
            LOGGER.warn("LLM returned {} scores for {} candidates, using available scores",
                scores.size(), results.size());
        }

        // Map scores back to results and filter by threshold
        List<QueryResultItem> scoredResults = new ArrayList<>();
        for (RelevancyScore rs : scores) {
            int index = rs.getIndex();

            // Validate index is in bounds
            if (index < 0 || index >= results.size()) {
                LOGGER.warn("LLM returned invalid index {}, skipping", index);
                continue;
            }

            // Check if score meets threshold
            if (rs.getScore() >= clampedThreshold) {
                QueryResultItem original = results.get(index);
                // Create new result with LLM relevancy score
                QueryResultItem scored = original.withScore((float) rs.getScore());
                scoredResults.add(scored);
            }
        }

        LOGGER.debug("LLM scoring complete: {} results passed threshold of {}",
            scoredResults.size(), clampedThreshold);

        // Sort by score descending (highest relevance first)
        scoredResults.sort((a, b) -> Float.compare(b.score(), a.score()));

        return scoredResults;
    }

    /**
     * Extracts candidate text from query result payloads.
     *
     * <p>Each result's payload is decoded as UTF-8 text. If a result has no
     * payload or an empty payload, an empty string is used as the candidate.</p>
     *
     * @param results The results to extract text from
     * @return List of candidate strings, one per result
     */
    private List<String> extractCandidates(List<QueryResultItem> results) {
        List<String> candidates = new ArrayList<>(results.size());
        for (QueryResultItem item : results) {
            String text = extractText(item);
            candidates.add(text != null ? text : "");
        }
        return candidates;
    }

    /**
     * Extracts text from a single query result's payload.
     *
     * @param item The query result item
     * @return The extracted text, or null if no payload
     */
    private String extractText(QueryResultItem item) {
        if (item.hasPayload()) {
            return new String(item.payload(), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Truncates text for logging purposes.
     *
     * @param text The text to truncate
     * @return Truncated text with ellipsis if needed
     */
    private String truncateForLogging(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= 50) {
            return text;
        }
        return text.substring(0, 47) + "...";
    }

    @Override
    public String toString() {
        return "LlmRelevancyScorer{llmClient=" + llmClient.getClass().getSimpleName() + "}";
    }
}
