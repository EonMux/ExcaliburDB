package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.RelevancyScore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BatchHandler}.
 *
 * <h2>Key Test Coverage</h2>
 * <ul>
 *   <li>Single batch (no splitting needed)</li>
 *   <li>Multiple batches with correct splitting</li>
 *   <li>CRITICAL: Merged scores returned in original candidate order</li>
 *   <li>Index remapping correctness</li>
 *   <li>Token estimation</li>
 *   <li>Circuit breaker integration</li>
 *   <li>Edge cases (empty, single candidate)</li>
 * </ul>
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Returned scores preserve original candidate order</li>
 *   <li>Each score's index corresponds to position in original list</li>
 *   <li>Token estimation: character count / 4.0</li>
 *   <li>Max batch tokens respected</li>
 * </ul>
 */
@Timeout(value = 10)
class BatchHandlerTest {

    private HttpServer mockServer;
    private int mockServerPort;
    private LLMClientConfig config;
    private LLMHttpClient httpClient;
    private BatchHandler batchHandler;

    // Track requests for verification
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final List<String> requestBodies = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        // Start mock HTTP server
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.setExecutor(null);
        mockServerPort = mockServer.getAddress().getPort();
        mockServer.start();

        // Reset request tracking
        requestCount.set(0);
        requestBodies.clear();

        // Create config with low batch token limit for testing
        config = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(500) // Low limit to force batching
                .build();

        httpClient = new LLMHttpClient(config);
        batchHandler = new BatchHandler(httpClient, config);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(1);
        }
    }

    // ============= Order Preservation Tests (CRITICAL) =============

    @Test
    @DisplayName("CRITICAL: Merged scores returned in original candidate order")
    void scoreWithBatching_multipleBatches_returnsScoresInOriginalOrder() {
        // Create candidates that will require multiple batches
        List<String> candidates = createLargeCandidateList(20);

        // Setup mock to return scores with batch-relative indices
        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestBodies.add(body);
            requestCount.incrementAndGet();

            // Count candidates in this batch by counting newlines in the prompt
            int candidateCount = countCandidatesInRequest(body);

            // Return scores with indices 0 to candidateCount-1
            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                response.append(String.format("{\"index\": %d, \"score\": %.2f}", i, 0.5 + (i * 0.03)));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Verify we got multiple batches
        assertTrue(requestCount.get() > 1, "Should have made multiple API calls");

        // CRITICAL: Verify scores are sorted by original index
        for (int i = 0; i < scores.size() - 1; i++) {
            assertTrue(scores.get(i).getIndex() <= scores.get(i + 1).getIndex(),
                    "Scores should be sorted by index: index " + i + " = " + scores.get(i).getIndex() +
                    ", index " + (i + 1) + " = " + scores.get(i + 1).getIndex());
        }

        // Verify all original candidates have scores
        assertEquals(candidates.size(), scores.size(),
                "Should have a score for each original candidate");
    }

    @Test
    @DisplayName("CRITICAL: Each score's index corresponds to original candidate position")
    void scoreWithBatching_remappedIndices_matchOriginalPositions() {
        // Use an extremely low batch token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180) // Very low limit to force batching
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        // Create candidates that will definitely require multiple batches with 180 token limit
        List<String> candidates = List.of(
                "First candidate about apples with a lot of additional padding text to ensure " +
                        "this candidate consumes a significant portion of the available token budget. " +
                        "We need enough tokens to trigger batching behavior.",
                "Second candidate about bananas with a lot of additional padding text to ensure " +
                        "this candidate consumes a significant portion of the available token budget. " +
                        "We need enough tokens to trigger batching behavior.",
                "Third candidate about cherries with a lot of additional padding text to ensure " +
                        "this candidate consumes a significant portion of the available token budget. " +
                        "We need enough tokens to trigger batching behavior.",
                "Fourth candidate about dates with a lot of additional padding text to ensure " +
                        "this candidate consumes a significant portion of the available token budget. " +
                        "We need enough tokens to trigger batching behavior.",
                "Fifth candidate about elderberries with a lot of additional padding text to ensure " +
                        "this candidate consumes a significant portion of the available token budget. " +
                        "We need enough tokens to trigger batching behavior."
        );

        // Setup mock to return scores based on batch size
        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestBodies.add(body);
            requestCount.incrementAndGet();

            int candidateCount = countCandidatesInRequest(body);
            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                response.append(String.format("{\"index\": %d, \"score\": %.2f}", i, 0.5 + (i * 0.1)));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Verify we got multiple batches
        assertTrue(requestCount.get() > 1, "Should have made multiple API calls, got " + requestCount.get());

        // Find scores for each original index
        for (int originalIndex = 0; originalIndex < candidates.size(); originalIndex++) {
            final int idx = originalIndex;
            RelevancyScore scoreForIndex = scores.stream()
                    .filter(s -> s.getIndex() == idx)
                    .findFirst()
                    .orElse(null);

            assertNotNull(scoreForIndex, "Should have score for original index " + originalIndex);

            // CRITICAL: Verify candidate text matches the index
            assertEquals(candidates.get(originalIndex), scoreForIndex.getCandidateText(),
                    "Score at index " + originalIndex + " should have correct candidate text");
        }
    }

    @Test
    @DisplayName("CRITICAL: Index remapping works with out-of-order LLM responses")
    void scoreWithBatching_outOfOrderResponse_remappedCorrectly() {
        // Use an extremely low batch token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180) // Very low limit to force batching with our candidates
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        // Create candidates that will definitely require multiple batches
        // Each candidate needs to be large enough to exceed ~180 tokens when combined
        List<String> candidates = List.of(
                "Candidate zero with a lot of additional padding text to ensure this candidate alone " +
                        "consumes a significant portion of the available token budget. We need to make " +
                        "sure the token estimation triggers batching for this test scenario.",
                "Candidate one with a lot of additional padding text to ensure this candidate alone " +
                        "consumes a significant portion of the available token budget. We need to make " +
                        "sure the token estimation triggers batching for this test scenario.",
                "Candidate two with a lot of additional padding text to ensure this candidate alone " +
                        "consumes a significant portion of the available token budget. We need to make " +
                        "sure the token estimation triggers batching for this test scenario.",
                "Candidate three with a lot of additional padding text to ensure this candidate alone " +
                        "consumes a significant portion of the available token budget. We need to make " +
                        "sure the token estimation triggers batching for this test scenario."
        );

        // Setup mock to return scores out of order within the batch
        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestBodies.add(body);
            requestCount.incrementAndGet();

            int candidateCount = countCandidatesInRequest(body);

            // Return indices out of order (reversed)
            StringBuilder response = new StringBuilder("[");
            for (int i = candidateCount - 1; i >= 0; i--) {
                if (i < candidateCount - 1) response.append(",");
                response.append(String.format("{\"index\": %d, \"score\": %.2f}", i, 0.5 + (i * 0.1)));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Verify we got multiple batches
        assertTrue(requestCount.get() > 1, "Should have made multiple API calls, got " + requestCount.get());

        // Verify all scores are present and correctly remapped
        assertEquals(candidates.size(), scores.size(), "Should have " + candidates.size() + " scores");

        // Verify each original index has correct candidate text
        for (RelevancyScore score : scores) {
            assertEquals(candidates.get(score.getIndex()), score.getCandidateText(),
                    "Score at index " + score.getIndex() + " should have correct candidate text");
        }

        // Verify scores are sorted by index
        for (int i = 0; i < scores.size() - 1; i++) {
            assertTrue(scores.get(i).getIndex() <= scores.get(i + 1).getIndex(),
                    "Scores should be sorted by index after merging");
        }
    }

    // ============= Single Batch Tests =============

    @Test
    @DisplayName("Single batch: small candidate list uses single API call")
    void scoreWithBatching_smallList_singleApiCall() {
        List<String> candidates = List.of("Short candidate one", "Short candidate two");

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.8}, {\"index\": 1, \"score\": 0.6}]");
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("test query", candidates, "test-corr");

        assertEquals(1, requestCount.get(), "Should make exactly one API call");
        assertEquals(2, scores.size());
        assertEquals(0, scores.get(0).getIndex());
        assertEquals(1, scores.get(1).getIndex());
    }

    @Test
    @DisplayName("Single batch: single candidate")
    void scoreWithBatching_singleCandidate_singleApiCall() {
        List<String> candidates = List.of("Single candidate");

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.95}]");
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("test query", candidates, "test-corr");

        assertEquals(1, requestCount.get());
        assertEquals(1, scores.size());
        assertEquals(0, scores.get(0).getIndex());
        assertEquals(0.95, scores.get(0).getScore(), 0.001);
    }

    // ============= Multiple Batches Tests =============

    @Test
    @DisplayName("Multiple batches: large candidate list splits correctly")
    void scoreWithBatching_largeList_createsMultipleBatches() {
        // Create candidates that exceed the 500 token limit
        List<String> candidates = createLargeCandidateList(30);

        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestBodies.add(body);
            requestCount.incrementAndGet();

            int candidateCount = countCandidatesInRequest(body);
            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                response.append(String.format("{\"index\": %d, \"score\": 0.5}", i));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Verify multiple batches were created
        assertTrue(requestCount.get() > 1, "Should have created multiple batches");

        // Verify all candidates got scores
        assertEquals(candidates.size(), scores.size(),
                "Should have score for every candidate");

        // Verify indices cover all original positions
        for (int i = 0; i < candidates.size(); i++) {
            final int idx = i;
            assertTrue(scores.stream().anyMatch(s -> s.getIndex() == idx),
                    "Should have score for index " + i);
        }
    }

    @Test
    @DisplayName("Multiple batches: each batch respects token limit")
    void scoreWithBatching_eachBatch_withinTokenLimit() {
        List<String> candidates = createLargeCandidateList(25);

        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestBodies.add(body);
            requestCount.incrementAndGet();

            // Verify the request body is not too large
            int estimatedTokens = estimateTokensForRequest(body);
            assertTrue(estimatedTokens <= config.getMaxBatchTokens() + 100,
                    "Each batch should be within token limit (with small buffer for rounding)");

            int candidateCount = countCandidatesInRequest(body);
            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                response.append(String.format("{\"index\": %d, \"score\": 0.5}", i));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        batchHandler.scoreWithBatching("test query", candidates, "test-corr");

        assertTrue(requestCount.get() > 1, "Should have created multiple batches");
    }

    // ============= Edge Cases =============

    @Test
    @DisplayName("Edge case: empty candidates returns empty list")
    void scoreWithBatching_emptyCandidates_returnsEmpty() {
        List<String> candidates = List.of();

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("test query", candidates, "test-corr");

        assertEquals(0, scores.size());
        assertEquals(0, requestCount.get(), "Should not make any API calls");
    }

    @Test
    @DisplayName("Edge case: null query throws NullPointerException")
    void scoreWithBatching_nullQuery_throwsNPE() {
        List<String> candidates = List.of("test");

        assertThrows(NullPointerException.class,
                () -> batchHandler.scoreWithBatching(null, candidates, "test-corr"));
    }

    @Test
    @DisplayName("Edge case: null candidates throws NullPointerException")
    void scoreWithBatching_nullCandidates_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> batchHandler.scoreWithBatching("query", null, "test-corr"));
    }

    @Test
    @DisplayName("Edge case: null correlation ID works")
    void scoreWithBatching_nullCorrelationId_works() {
        List<String> candidates = List.of("test candidate");

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.8}]");
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("query", candidates, null);

        assertEquals(1, scores.size());
    }

    // ============= Token Estimation Tests =============

    @Test
    @DisplayName("Token estimation: character count / 4.0 approximation")
    void estimateTokens_usesApproximation() {
        // Create candidates with known character counts
        String query = "test query"; // 11 chars -> ~3 tokens
        List<String> candidates = List.of(
                "12345678901234567890", // 20 chars -> 5 tokens
                "123456789012345678901234567890" // 30 chars -> 8 tokens
        );

        int tokens = batchHandler.estimateTokens(query, candidates);

        // Should be roughly: overhead (150) + query (3) + candidate1 (5+5) + candidate2 (8+5)
        // Total: ~176 tokens (with ceiling applied)
        assertTrue(tokens >= 150, "Should include prompt overhead");
        assertTrue(tokens < 300, "Should be reasonable estimate");
    }

    @Test
    @DisplayName("Token estimation: handles null text")
    void estimateTokens_nullText_returnsOverhead() {
        int tokens = batchHandler.estimateTokens(null, List.of());
        assertTrue(tokens >= 0, "Should return valid estimate even with null query");
    }

    @Test
    @DisplayName("Token estimation: handles null candidate in list")
    void estimateTokens_nullCandidateInList_handlesGracefully() {
        List<String> candidates = new ArrayList<>();
        candidates.add("valid candidate");
        candidates.add(null);
        candidates.add("another valid");

        int tokens = batchHandler.estimateTokens("query", candidates);
        assertTrue(tokens > 0, "Should return valid estimate even with null candidate");
    }

    // ============= Circuit Breaker Tests =============

    @Test
    @DisplayName("Circuit breaker: uses protected method when called")
    void scoreWithBatchingAndCircuitBreaker_usesProtectedMethod() {
        List<String> candidates = List.of("test candidate");

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.8}]");
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatchingAndCircuitBreaker(
                "query", candidates, "test-corr");

        assertEquals(1, scores.size());
        assertEquals(CircuitBreakerState.CLOSED, httpClient.getCircuitBreaker().getState());
    }

    @Test
    @DisplayName("Circuit breaker: open circuit fails fast")
    void scoreWithBatchingAndCircuitBreaker_openCircuit_failsFast() {
        // Trip the circuit breaker
        httpClient.getCircuitBreaker().trip();

        List<String> candidates = List.of("test candidate");

        LLMException ex = assertThrows(LLMException.class,
                () -> batchHandler.scoreWithBatchingAndCircuitBreaker("query", candidates, "test-corr"));

        assertEquals(LLMException.ErrorType.CIRCUIT_OPEN, ex.getErrorType());
        assertEquals(0, requestCount.get(), "Should not make any API calls when circuit is open");
    }

    @Test
    @DisplayName("Circuit breaker: multiple batches with circuit breaker")
    void scoreWithBatchingAndCircuitBreaker_multipleBatches_allProtected() {
        List<String> candidates = createLargeCandidateList(20);

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            int candidateCount = countCandidatesInRequest(
                    new String(exchange.getRequestBody().readAllBytes()));

            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                response.append(String.format("{\"index\": %d, \"score\": 0.5}", i));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatchingAndCircuitBreaker(
                "query", candidates, "test-corr");

        assertTrue(requestCount.get() > 1, "Should have multiple batches");
        assertEquals(candidates.size(), scores.size());
        assertEquals(CircuitBreakerState.CLOSED, httpClient.getCircuitBreaker().getState());
    }

    // ============= Domain Correctness Tests =============

    @Test
    @DisplayName("Domain correctness: scores clamped to [0.0, 1.0]")
    void scoreWithBatching_outOfRangeScores_clamped() {
        List<String> candidates = List.of("candidate one", "candidate two");

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            // Return out-of-range scores
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 1.5}, {\"index\": 1, \"score\": -0.3}]");
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("query", candidates, "test-corr");

        assertEquals(1.0, scores.get(0).getScore(), 0.001, "1.5 should be clamped to 1.0");
        assertEquals(0.0, scores.get(1).getScore(), 0.001, "-0.3 should be clamped to 0.0");
    }

    @Test
    @DisplayName("Domain correctness: candidate text matches index")
    void scoreWithBatching_candidateText_matchesIndex() {
        List<String> candidates = List.of(
                "First specific text",
                "Second specific text",
                "Third specific text"
        );

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.8}, " +
                    "{\"index\": 1, \"score\": 0.6}, {\"index\": 2, \"score\": 0.4}]");
        });

        List<RelevancyScore> scores = batchHandler.scoreWithBatching("query", candidates, "test-corr");

        assertEquals("First specific text", scores.get(0).getCandidateText());
        assertEquals("Second specific text", scores.get(1).getCandidateText());
        assertEquals("Third specific text", scores.get(2).getCandidateText());
    }

    // ============= Score Combination Tests (CRITICAL - Task 24) =============
    // Task 24: "Scores from multiple calls must be combined correctly (same format as single call)"

    @Test
    @DisplayName("CRITICAL Task 24: Multiple batch scores combined in same format as single call")
    void scoreWithBatching_multipleBatches_sameFormatAsSingleCall() {
        // Use a low token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180)
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        List<String> candidates = List.of(
                "Candidate zero with enough text to consume tokens in the batch for testing purposes and more padding",
                "Candidate one with enough text to consume tokens in the batch for testing purposes and more padding",
                "Candidate two with enough text to consume tokens in the batch for testing purposes and more padding",
                "Candidate three with enough text to consume tokens in the batch for testing purposes and more padding",
                "Candidate four with enough text to consume tokens in the batch for testing purposes and more padding"
        );

        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestBodies.add(body);
            int batchNum = requestCount.incrementAndGet();

            int candidateCount = countCandidatesInRequest(body);

            // Return scores for all candidates in this batch
            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                // Score varies by batch and position for verification
                double score = 0.1 * batchNum + 0.01 * i;
                response.append(String.format("{\"index\": %d, \"score\": %.2f}", i, score));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Verify multiple batches were made
        assertTrue(requestCount.get() > 1, "Should have made multiple API calls");

        // CRITICAL: Verify same format as single call
        // 1. Return type is List<RelevancyScore>
        assertNotNull(scores, "Should return non-null list");
        assertTrue(scores instanceof List, "Should return List type");

        // 2. All elements are RelevancyScore with valid properties
        for (RelevancyScore score : scores) {
            assertTrue(score.getIndex() >= 0, "Index should be non-negative");
            assertTrue(score.getScore() >= 0.0 && score.getScore() <= 1.0, "Score should be in [0.0, 1.0]");
            assertNotNull(score.getCandidateText(), "Candidate text should not be null");
        }

        // 3. Indices are unique (no duplicates)
        long uniqueIndices = scores.stream().mapToInt(RelevancyScore::getIndex).distinct().count();
        assertEquals(scores.size(), uniqueIndices, "All indices should be unique");

        // 4. Indices are sorted in ascending order
        for (int i = 1; i < scores.size(); i++) {
            assertTrue(scores.get(i).getIndex() > scores.get(i - 1).getIndex(),
                    "Indices should be strictly increasing");
        }

        // 5. Candidate text matches the index
        for (RelevancyScore score : scores) {
            assertEquals(candidates.get(score.getIndex()), score.getCandidateText(),
                    "Candidate text at index " + score.getIndex() + " should match original");
        }
    }

    @Test
    @DisplayName("CRITICAL Task 24: Duplicate indices from LLM are handled gracefully")
    void scoreWithBatching_duplicateIndicesFromLLM_deduplicated() {
        // Use a low token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180)
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        List<String> candidates = List.of(
                "Candidate zero with enough text to consume tokens in the batch and more padding text here",
                "Candidate one with enough text to consume tokens in the batch and more padding text here",
                "Candidate two with enough text to consume tokens in the batch and more padding text here",
                "Candidate three with enough text to consume tokens in the batch and more padding text here"
        );

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();

            // Return DUPLICATE indices (simulating malformed LLM response)
            // This tests that deduplication works correctly
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.8}, " +
                    "{\"index\": 0, \"score\": 0.7}, " +  // Duplicate index 0
                    "{\"index\": 1, \"score\": 0.6}]");
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Should have deduplicated - no duplicate indices in output
        long uniqueIndices = scores.stream().mapToInt(RelevancyScore::getIndex).distinct().count();
        assertEquals(scores.size(), uniqueIndices,
                "Output should have no duplicate indices (same format as single call)");

        // First occurrence of index 0 should be kept (score 0.8)
        assertEquals(0.8, scores.get(0).getScore(), 0.001,
                "First occurrence of duplicate index should be kept");
    }

    @Test
    @DisplayName("CRITICAL Task 24: Out-of-range batch indices are skipped (not clamped)")
    void scoreWithBatching_outOfRangeBatchIndices_skipped() {
        // Use a low token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180)
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        List<String> candidates = List.of(
                "Candidate zero with enough text to consume tokens in the batch and more padding",
                "Candidate one with enough text to consume tokens in the batch and more padding",
                "Candidate two with enough text to consume tokens in the batch and more padding"
        );

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();

            // Return an out-of-range index (99 doesn't exist in this small batch)
            // This should be SKIPPED, not clamped, to avoid creating wrong associations
            sendSuccessResponse(exchange, "[{\"index\": 0, \"score\": 0.8}, " +
                    "{\"index\": 99, \"score\": 0.5}, " +  // Invalid - out of batch range
                    "{\"index\": 1, \"score\": 0.6}]");
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Should have only valid indices (0 and 1), index 99 should be skipped
        assertTrue(scores.size() <= 3, "Should have at most 3 scores (invalid index skipped)");

        // Verify no score has wrong candidate text association
        for (RelevancyScore score : scores) {
            int idx = score.getIndex();
            assertTrue(idx >= 0 && idx < candidates.size(),
                    "Index should be within valid range");
            assertEquals(candidates.get(idx), score.getCandidateText(),
                    "Candidate text should match index");
        }
    }

    @Test
    @DisplayName("CRITICAL Task 24: Empty batch results handled correctly")
    void scoreWithBatching_emptyBatchResults_noErrors() {
        // Use a low token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180)
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        List<String> candidates = List.of(
                "Candidate zero with enough text to consume tokens in the batch and more padding",
                "Candidate one with enough text to consume tokens in the batch and more padding",
                "Candidate two with enough text to consume tokens in the batch and more padding"
        );

        setMockHandler(exchange -> {
            requestCount.incrementAndGet();

            // Return empty array (LLM returned no scores)
            sendSuccessResponse(exchange, "[]");
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Should return empty list (same format as single call with empty response)
        assertNotNull(scores, "Should return non-null list");
        assertEquals(0, scores.size(), "Should return empty list when LLM returns no scores");
    }

    @Test
    @DisplayName("CRITICAL Task 24: Merged scores maintain RelevancyScore contract")
    void scoreWithBatching_mergedScores_maintainContract() {
        // Use a low token limit to force multiple batches
        LLMClientConfig smallBatchConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:" + mockServerPort)
                .chatModel("test-model")
                .timeout(Duration.ofSeconds(5))
                .maxBatchTokens(180)
                .build();
        LLMHttpClient smallClient = new LLMHttpClient(smallBatchConfig);
        BatchHandler smallBatchHandler = new BatchHandler(smallClient, smallBatchConfig);

        List<String> candidates = List.of(
                "Candidate zero with enough text to consume tokens in the batch and more padding text",
                "Candidate one with enough text to consume tokens in the batch and more padding text",
                "Candidate two with enough text to consume tokens in the batch and more padding text",
                "Candidate three with enough text to consume tokens in the batch and more padding text"
        );

        setMockHandler(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requestCount.incrementAndGet();

            int candidateCount = countCandidatesInRequest(body);

            // Return scores with various edge cases
            StringBuilder response = new StringBuilder("[");
            for (int i = 0; i < candidateCount; i++) {
                if (i > 0) response.append(",");
                // Include edge case scores
                double score = (i == 0) ? 0.0 : ((i == candidateCount - 1) ? 1.0 : 0.5);
                response.append(String.format("{\"index\": %d, \"score\": %.2f}", i, score));
            }
            response.append("]");

            sendSuccessResponse(exchange, response.toString());
        });

        List<RelevancyScore> scores = smallBatchHandler.scoreWithBatching("test query", candidates, "test-corr");

        // Verify multiple batches
        assertTrue(requestCount.get() > 1, "Should have made multiple API calls");

        // Verify each score maintains the RelevancyScore contract
        for (RelevancyScore score : scores) {
            // Index contract: non-negative
            assertTrue(score.getIndex() >= 0, "Index must be >= 0");

            // Score contract: in [0.0, 1.0]
            assertTrue(score.getScore() >= 0.0 && score.getScore() <= 1.0,
                    "Score must be in [0.0, 1.0]");

            // Candidate text contract: matches the index
            assertEquals(candidates.get(score.getIndex()), score.getCandidateText(),
                    "Candidate text must match the index");

            // Immutability: verify getters return expected values
            assertNotNull(score.getCandidateText(), "Candidate text should not be null");
        }
    }

    // ============= Helper Methods =============

    private void setMockHandler(HttpHandler handler) {
        try {
            mockServer.removeContext("/v1/chat/completions");
        } catch (IllegalArgumentException e) {
            // Context didn't exist
        }
        mockServer.createContext("/v1/chat/completions", handler);
    }

    private void sendSuccessResponse(HttpExchange exchange, String contentJson) throws IOException {
        String response = "{\"choices\":[{\"message\":{\"content\":\"" + escapeForJson(contentJson) + "\"}}]}";
        byte[] bytes = response.getBytes();
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String escapeForJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Creates a list of large candidates to force batching.
     */
    private List<String> createLargeCandidateList(int count) {
        List<String> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Each candidate is ~100 chars to ensure batching is needed
            candidates.add("This is candidate number " + i + " with some additional text " +
                    "to make it longer and consume more tokens in the request. " +
                    "The quick brown fox jumps over the lazy dog.");
        }
        return candidates;
    }

    /**
     * Counts candidates in the request body by counting numbered lines.
     */
    private int countCandidatesInRequest(String body) {
        // Candidates are listed from zero, matching the index the reply is parsed against.
        int count = 0;
        for (int i = 0; i < 100; i++) {
            if (body.contains(i + ". ")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rough token estimation for verification.
     */
    private int estimateTokensForRequest(String body) {
        return (int) Math.ceil(body.length() / 4.0);
    }
}
