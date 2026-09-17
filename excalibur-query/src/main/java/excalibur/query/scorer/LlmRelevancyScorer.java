package excalibur.query.scorer;

import excalibur.query.QueryResultItem;
import excalibur.query.pipeline.QueryPipeline.LlmScorer;
import io.excalibur.llm.RelevancyScore;
import io.excalibur.llm.client.CircuitBreaker;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.llm.LLMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * The circuit breaker this scorer's calls are made through.
     *
     * <p>Exposed so the node can report it. When it opens, stage 5 is bypassed, the pipeline
     * keeps the ranking it already had and the node goes on answering 200 - so without this
     * the only evidence that every ranking changed is a log line.</p>
     *
     * @return The breaker guarding relevancy scoring
     */
    public CircuitBreaker getCircuitBreaker() {
        return llmClient.getCircuitBreaker();
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
     * <p>Scoring is only meaningful for results that actually carry text. A result with an
     * empty payload keeps its incoming vector score and is never dropped by the threshold;
     * if no result carries text at all, the input list is returned unchanged rather than
     * asking the LLM to rank a list of empty strings.</p>
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

        // Relevancy scoring compares the query against candidate TEXT. If no candidate
        // carries any - which is the case whenever the pipeline has not attached payloads -
        // the LLM would be asked to rank a list of empty strings, and the scores it
        // returned would then re-rank and threshold-filter real results on the strength of
        // nothing. Keep the vector ranking instead.
        if (candidates.stream().allMatch(String::isBlank)) {
            LOGGER.warn("Skipping LLM relevancy scoring: none of the {} candidates carry text. " +
                "Returning the vector ranking unchanged.", results.size());
            return List.copyOf(results);
        }

        List<RelevancyScore> scores = askModel(queryText, candidates);
        Map<Integer, RelevancyScore> scoreByIndex = byCandidateIndex(scores, results.size());

        List<QueryResultItem> scoredResults =
            applyScores(results, candidates, scoreByIndex, clampedThreshold);

        LOGGER.debug("LLM scoring complete: {} results passed threshold of {}",
            scoredResults.size(), clampedThreshold);

        // Sort by score descending (highest relevance first)
        scoredResults.sort((a, b) -> Float.compare(b.score(), a.score()));

        return scoredResults;
    }

    /**
     * Puts the scoring question to the model.
     *
     * <p>The call goes through the circuit breaker so that a run of failures opens it instead
     * of every query paying the timeout again. A failure is rethrown rather than turned into
     * an empty answer: the pipeline's fallback is to keep the vector ranking, and it can only
     * choose that if it is told the scoring did not happen.</p>
     *
     * @param queryText The query text to score against
     * @param candidates The candidate texts, one per result and in result order
     * @return The scores the model answered with, which need not cover every candidate
     * @throws LLMException if the LLM call fails
     */
    private List<RelevancyScore> askModel(String queryText, List<String> candidates) {
        // Call LLM for relevancy scoring with circuit breaker protection
        // This ensures failures are tracked and the circuit opens after consecutive failures
        try {
            return llmClient.scoreRelevancyWithCircuitBreaker(queryText, candidates, null);
        } catch (LLMException e) {
            LOGGER.warn("LLM relevancy scoring failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Indexes the model's answer by the candidate each score is about.
     *
     * <p>Indexing the response lets the walk that follows go over the candidates rather than
     * over the scores. A model that answers about fewer candidates than it was asked about is
     * not unusual: the batching layer caps how many prompts one query may send, and the parser
     * keeps only the score objects it recognised. A score naming a candidate that was never
     * sent is dropped, since there is no result for it to be about.</p>
     *
     * @param scores The scores the model answered with
     * @param candidateCount How many candidates the model was asked about
     * @return The scores keyed by candidate index
     */
    private static Map<Integer, RelevancyScore> byCandidateIndex(List<RelevancyScore> scores,
                                                                 int candidateCount) {
        Map<Integer, RelevancyScore> scoreByIndex = new HashMap<>();
        for (RelevancyScore rs : scores) {
            if (rs.getIndex() < 0 || rs.getIndex() >= candidateCount) {
                LOGGER.warn("LLM returned invalid index {}, skipping", rs.getIndex());
                continue;
            }
            scoreByIndex.put(rs.getIndex(), rs);
        }

        if (scoreByIndex.size() != candidateCount) {
            LOGGER.warn("LLM scored {} of {} candidates; the rest keep their vector ranking",
                scoreByIndex.size(), candidateCount);
        }

        return scoreByIndex;
    }

    /**
     * Rebuilds the result list from the model's verdict on each candidate.
     *
     * @param results The results as vector search ranked them
     * @param candidates The candidate texts, one per result and in result order
     * @param scoreByIndex The model's scores, keyed by candidate index
     * @param clampedThreshold The minimum score a judged result must reach to be kept
     * @return The surviving results, still in vector-search order
     */
    private static List<QueryResultItem> applyScores(List<QueryResultItem> results,
                                                     List<String> candidates,
                                                     Map<Integer, RelevancyScore> scoreByIndex,
                                                     float clampedThreshold) {
        // Every candidate, not every score. Iterating the scores dropped whatever the model did
        // not mention - silently, and without any threshold decision having been made about it.
        // A model that answered nothing at all emptied a result set that vector search had
        // filled, and the query still reported itself complete.
        List<QueryResultItem> scoredResults = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            QueryResultItem original = results.get(index);
            RelevancyScore rs = scoreByIndex.get(index);

            // A candidate with no text was never scored on its merits, and one the model did not
            // answer about was not judged at all. Neither is evidence of irrelevance, so both
            // keep the vector score that put them in the candidate set.
            if (rs == null || candidates.get(index).isBlank()) {
                scoredResults.add(original);
                continue;
            }

            // Check if score meets threshold
            if (rs.getScore() >= clampedThreshold) {
                // Create new result with LLM relevancy score
                scoredResults.add(original.withScore((float) rs.getScore()));
            }
        }

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
