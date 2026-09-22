package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.RelevancyScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Handles batching of large candidate sets for relevancy scoring.
 *
 * <p>When a set of candidates exceeds the estimated token limit, this handler
 * splits the candidates into multiple batches, calls the LLM API for each batch,
 * and merges the results while preserving the original candidate order.</p>
 *
 * <h2>Token Estimation</h2>
 * <p>Tokens are estimated using {@link TokenEstimator} which provides the
 * approximation: {@code characterCount / 4.0}. This is a rough estimate that
 * works well for most text.</p>
 *
 * <h2>Order Preservation (CRITICAL)</h2>
 * <p>When candidates are split into multiple batches, each batch is scored
 * independently with indices relative to that batch. The handler remaps these
 * indices back to the original candidate positions before returning the merged
 * results. This ensures the returned scores always reference the correct
 * candidate from the original input list.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state is passed as method parameters, and
 * the class has no mutable instance fields.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Max batch tokens: positive integer, default 4000</li>
 *   <li>Token estimation: character count / 4.0 (approximation)</li>
 *   <li>Returned scores preserve original candidate order (indices remapped)</li>
 *   <li>Scores from multiple calls are combined correctly (same format as single call)</li>
 *   <li>Empty candidates list returns empty scores list</li>
 * </ul>
 */
public class BatchHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BatchHandler.class);

    /**
     * Fixed token overhead for the prompt template (system message, instructions, etc.).
     * Domain correctness: this accounts for the non-candidate portion of the prompt.
     * Estimated based on the Phase 7 prompt template.
     */
    private static final int PROMPT_OVERHEAD_TOKENS = 150;

    /**
     * Tokens per candidate line overhead (number prefix, formatting).
     * Domain correctness: "1. " prefix and newline for each candidate.
     */
    private static final int CANDIDATE_LINE_OVERHEAD = 5;

    private final LLMHttpClient httpClient;
    private final LLMClientConfig config;

    /**
     * Creates a new BatchHandler with the given HTTP client and configuration.
     *
     * @param httpClient The LLM HTTP client for making API calls (must not be null)
     * @param config The client configuration containing batch token limits (must not be null)
     * @throws NullPointerException if httpClient or config is null
     */
    public BatchHandler(LLMHttpClient httpClient, LLMClientConfig config) {
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP client cannot be null");
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
    }

    /**
     * Scores relevancy for a list of candidates, automatically batching if necessary.
     *
     * <p>If the estimated token count exceeds the configured limit, candidates are
     * split into multiple batches. Each batch is scored independently, and the
     * results are merged with indices remapped to the original candidate positions.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Returned scores preserve original candidate order</li>
     *   <li>Each score's index corresponds to the position in the original candidates list</li>
     *   <li>Scores are clamped to [0.0, 1.0] by the underlying RelevancyScore class</li>
     *   <li>Empty candidates returns empty list</li>
     * </ul>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null, may be empty)
     * @param correlationId Optional correlation ID for request tracing (may be null)
     * @return List of RelevancyScore objects in original candidate order
     * @throws LLMException if any API call fails
     */
    public List<RelevancyScore> scoreWithBatching(
            String query,
            List<String> candidates,
            String correlationId) {

        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");

        if (candidates.isEmpty()) {
            LOG.debug("Empty candidates list, returning empty scores (correlationId={})", correlationId);
            return Collections.emptyList();
        }

        int maxBatchTokens = config.getMaxBatchTokens();

        // Calculate estimated tokens for the entire batch
        int estimatedTokens = estimateTokens(query, candidates);

        LOG.debug("Scoring {} candidates, estimated {} tokens, max {} tokens per batch (correlationId={})",
                candidates.size(), estimatedTokens, maxBatchTokens, correlationId);

        // If within limit, use single API call
        if (estimatedTokens <= maxBatchTokens) {
            LOG.debug("Single batch sufficient (correlationId={})", correlationId);
            return httpClient.scoreRelevancyUnbatched(query, candidates, correlationId);
        }

        // Split into batches and score each
        LOG.debug("Splitting into multiple batches (correlationId={})", correlationId);
        List<Batch> batches = splitIntoBatches(query, candidates, maxBatchTokens);

        // Apply maxPromptsPerQuery limit to control API call volume
        int maxPrompts = config.getMaxPromptsPerQuery();
        if (batches.size() > maxPrompts) {
            LOG.warn("Candidate set requires {} batches but maxPromptsPerQuery is {}, truncating to first {} batches (correlationId={})",
                    batches.size(), maxPrompts, maxPrompts, correlationId);
            batches = batches.subList(0, maxPrompts);
        }

        LOG.debug("Created {} batches from {} candidates (correlationId={})",
                batches.size(), candidates.size(), correlationId);

        // Score each batch and collect results
        List<RelevancyScore> allScores = new ArrayList<>(candidates.size());

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            Batch batch = batches.get(batchIndex);
            String batchCorrelationId = formatBatchCorrelationId(correlationId, batchIndex, batches.size());

            LOG.debug("Processing batch {} of {} with {} candidates (original indices {}-{}) (correlationId={})",
                    batchIndex + 1, batches.size(), batch.candidates.size(),
                    batch.startIndex, batch.startIndex + batch.candidates.size() - 1,
                    batchCorrelationId);

            // Score this batch
            List<RelevancyScore> batchScores = httpClient.scoreRelevancyUnbatched(
                    query, batch.candidates, batchCorrelationId);

            // Remap indices to original positions and add to results
            // Pass batch size to validate indices are within batch bounds
            List<RelevancyScore> remappedScores = remapIndices(
                    batchScores, batch.startIndex, candidates, batch.candidates.size());
            allScores.addAll(remappedScores);
        }

        // Sort by original index to maintain order
        allScores.sort(Comparator.comparingInt(RelevancyScore::getIndex));

        // CRITICAL: Deduplicate by index to ensure "same format as single call"
        // If LLM returns duplicate indices, keep only the first occurrence
        allScores = deduplicateByIndex(allScores, correlationId);

        LOG.debug("Merged {} scores from {} batches, preserving original order (correlationId={})",
                allScores.size(), batches.size(), correlationId);

        return allScores;
    }

    /**
     * Scores relevancy with circuit breaker protection, automatically batching if necessary.
     *
     * <p>Same as {@link #scoreWithBatching(String, List, String)} but with circuit
     * breaker protection on each API call.</p>
     *
     * @param query The query text to score against (must not be null or empty)
     * @param candidates List of candidate passages to score (must not be null, may be empty)
     * @param correlationId Optional correlation ID for request tracing (may be null)
     * @return List of RelevancyScore objects in original candidate order
     * @throws LLMException if the circuit is open or any API call fails
     */
    public List<RelevancyScore> scoreWithBatchingAndCircuitBreaker(
            String query,
            List<String> candidates,
            String correlationId) {

        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        int maxBatchTokens = config.getMaxBatchTokens();
        int estimatedTokens = estimateTokens(query, candidates);

        if (estimatedTokens <= maxBatchTokens) {
            return httpClient.scoreRelevancyUnbatchedWithCircuitBreaker(query, candidates, correlationId);
        }

        List<Batch> batches = splitIntoBatches(query, candidates, maxBatchTokens);

        // Apply maxPromptsPerQuery limit to control API call volume
        int maxPrompts = config.getMaxPromptsPerQuery();
        if (batches.size() > maxPrompts) {
            LOG.warn("Candidate set requires {} batches but maxPromptsPerQuery is {}, truncating to first {} batches (correlationId={})",
                    batches.size(), maxPrompts, maxPrompts, correlationId);
            batches = batches.subList(0, maxPrompts);
        }

        List<RelevancyScore> allScores = new ArrayList<>(candidates.size());

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            Batch batch = batches.get(batchIndex);
            String batchCorrelationId = formatBatchCorrelationId(correlationId, batchIndex, batches.size());

            List<RelevancyScore> batchScores = httpClient.scoreRelevancyUnbatchedWithCircuitBreaker(
                    query, batch.candidates, batchCorrelationId);

            // Remap indices to original positions and add to results
            // Pass batch size to validate indices are within batch bounds
            List<RelevancyScore> remappedScores = remapIndices(
                    batchScores, batch.startIndex, candidates, batch.candidates.size());
            allScores.addAll(remappedScores);
        }

        allScores.sort(Comparator.comparingInt(RelevancyScore::getIndex));

        // CRITICAL: Deduplicate by index to ensure "same format as single call"
        allScores = deduplicateByIndex(allScores, correlationId);

        return allScores;
    }

    // ==================== Token Estimation ====================

    /**
     * Estimates the token count for a query and list of candidates.
     *
     * <p>Domain correctness: uses the approximation {@code characterCount / 4.0}
     * plus overhead for the prompt template and formatting.</p>
     *
     * @param query The query text
     * @param candidates List of candidate texts
     * @return Estimated token count (always positive)
     */
    public int estimateTokens(String query, List<String> candidates) {
        // Base overhead for the prompt template
        int totalTokens = PROMPT_OVERHEAD_TOKENS;

        // Add tokens for the query
        if (query != null) {
            totalTokens += TokenEstimator.estimateTokens(query);
        }

        // Add tokens for each candidate (text + line overhead)
        for (String candidate : candidates) {
            if (candidate != null) {
                totalTokens += TokenEstimator.estimateTokens(candidate);
            }
            totalTokens += CANDIDATE_LINE_OVERHEAD;
        }

        return totalTokens;
    }

    // ==================== Batch Splitting ====================

    /**
     * Splits candidates into batches that fit within the token limit.
     *
     * <p>Each batch includes the query text, so the available space for candidates
     * is calculated as: {@code maxTokens - queryTokens - overhead}.</p>
     *
     * @param query The query text (included in every batch)
     * @param candidates List of candidates to split
     * @param maxTokens Maximum tokens per batch
     * @return List of batches
     */
    private List<Batch> splitIntoBatches(String query, List<String> candidates, int maxTokens) {
        List<Batch> batches = new ArrayList<>();

        // Calculate tokens used by query and overhead
        int queryTokens = TokenEstimator.estimateTokens(query);
        int availableForCandidates = maxTokens - PROMPT_OVERHEAD_TOKENS - queryTokens;

        // Ensure we have at least some space for candidates
        if (availableForCandidates < 100) {
            // If query is too large, still allow at least one candidate per batch
            availableForCandidates = 100;
            LOG.warn("Query is very large ({} tokens), reducing available space for candidates",
                    queryTokens);
        }

        List<String> currentBatch = new ArrayList<>();
        int currentBatchTokens = 0;
        int batchStartIndex = 0;

        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            int candidateTokens = TokenEstimator.estimateTokens(candidate) + CANDIDATE_LINE_OVERHEAD;

            // Check if adding this candidate would exceed the limit
            if (!currentBatch.isEmpty() && currentBatchTokens + candidateTokens > availableForCandidates) {
                // Finalize current batch
                batches.add(new Batch(new ArrayList<>(currentBatch), batchStartIndex));
                LOG.debug("Batch {} created with {} candidates (tokens: {})",
                        batches.size(), currentBatch.size(), currentBatchTokens);

                // Start new batch
                currentBatch.clear();
                currentBatchTokens = 0;
                batchStartIndex = i;
            }

            currentBatch.add(candidate);
            currentBatchTokens += candidateTokens;
        }

        // Add final batch if not empty
        if (!currentBatch.isEmpty()) {
            batches.add(new Batch(currentBatch, batchStartIndex));
            LOG.debug("Final batch {} created with {} candidates (tokens: {})",
                    batches.size(), currentBatch.size(), currentBatchTokens);
        }

        return batches;
    }

    // ==================== Index Remapping ====================

    /**
     * Deduplicates scores by index, keeping only the first occurrence of each index.
     *
     * <p>CRITICAL for "same format as single call" requirement: A single API call
     * would never return duplicate indices. This method ensures that even if the
     * LLM returns duplicate indices across batches (which shouldn't happen but we
     * handle defensively), the output format remains consistent.</p>
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>Input must be pre-sorted by index (for deterministic first-occurrence selection)</li>
     *   <li>Output preserves order of first occurrence for each index</li>
     *   <li>Duplicate indices are logged at DEBUG level for troubleshooting</li>
     * </ul>
     *
     * @param scores Sorted list of scores (may contain duplicate indices)
     * @param correlationId Correlation ID for logging
     * @return New list with duplicate indices removed
     */
    private List<RelevancyScore> deduplicateByIndex(List<RelevancyScore> scores, String correlationId) {
        if (scores.isEmpty()) {
            return scores;
        }

        List<RelevancyScore> deduplicated = new ArrayList<>(scores.size());
        int lastSeenIndex = -1;

        for (RelevancyScore score : scores) {
            int currentIndex = score.getIndex();

            if (currentIndex != lastSeenIndex) {
                deduplicated.add(score);
                lastSeenIndex = currentIndex;
            } else {
                LOG.debug("Duplicate index {} found, keeping first occurrence only (correlationId={})",
                        currentIndex, correlationId);
            }
        }

        return deduplicated;
    }

    /**
     * Remaps batch-relative indices to original candidate positions.
     *
     * <p>CRITICAL: This is the key method for preserving original candidate order.
     * When candidates are split into batches, each batch is scored with indices
     * starting from 0. This method adds the batch's start offset to each score's
     * index, ensuring the returned scores reference the correct original candidate.</p>
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>Index offset is added to each score's index</li>
     *   <li>Candidate text is looked up from the original list using the remapped index</li>
     *   <li>Invalid indices (out of bounds) are logged and skipped (not clamped to avoid duplicates)</li>
     * </ul>
     *
     * @param batchScores Scores from the batch API call (indices relative to batch)
     * @param indexOffset The starting index of this batch in the original list
     * @param originalCandidates The original complete list of candidates
     * @param batchSize The number of candidates in this batch (for validation)
     * @return New list of scores with remapped indices
     */
    private List<RelevancyScore> remapIndices(
            List<RelevancyScore> batchScores,
            int indexOffset,
            List<String> originalCandidates,
            int batchSize) {

        List<RelevancyScore> remapped = new ArrayList<>(batchScores.size());

        for (RelevancyScore score : batchScores) {
            // Remap the index by adding the batch offset
            int batchRelativeIndex = score.getIndex();
            int originalIndex = batchRelativeIndex + indexOffset;

            // Domain correctness: validate index is within the batch's range
            // Do NOT clamp to avoid creating duplicate indices
            if (batchRelativeIndex < 0 || batchRelativeIndex >= batchSize) {
                LOG.warn("Score has invalid batch-relative index {} (batch size {}), skipping. " +
                         "This indicates LLM returned an out-of-range index.",
                         batchRelativeIndex, batchSize);
                continue;
            }

            // Validate original index is within total candidates
            if (originalIndex < 0 || originalIndex >= originalCandidates.size()) {
                LOG.warn("Remapped index {} is out of bounds (total candidates {}), skipping",
                         originalIndex, originalCandidates.size());
                continue;
            }

            // Get the correct candidate text from the original list
            String candidateText = originalCandidates.get(originalIndex);

            // Create new score with remapped index and correct candidate text
            RelevancyScore remappedScore = new RelevancyScore(
                    originalIndex,
                    score.getScore(),
                    candidateText
            );

            remapped.add(remappedScore);

            LOG.trace("Remapped index {} -> {} for score {}",
                    batchRelativeIndex, originalIndex, score.getScore());
        }

        return remapped;
    }

    // ==================== Utility Methods ====================

    /**
     * Formats a correlation ID for a specific batch.
     *
     * @param baseCorrelationId The base correlation ID (may be null)
     * @param batchIndex The index of this batch (0-based)
     * @param totalBatches Total number of batches
     * @return Formatted correlation ID for the batch
     */
    private String formatBatchCorrelationId(String baseCorrelationId, int batchIndex, int totalBatches) {
        if (baseCorrelationId == null || baseCorrelationId.isBlank()) {
            return String.format("batch-%d-of-%d", batchIndex + 1, totalBatches);
        }
        return String.format("%s-batch-%d-of-%d", baseCorrelationId, batchIndex + 1, totalBatches);
    }

    // ==================== Inner Classes ====================

    /**
     * Represents a batch of candidates with their starting index in the original list.
     */
    private static final class Batch {
        final List<String> candidates;
        final int startIndex;

        Batch(List<String> candidates, int startIndex) {
            this.candidates = candidates;
            this.startIndex = startIndex;
        }
    }
}
