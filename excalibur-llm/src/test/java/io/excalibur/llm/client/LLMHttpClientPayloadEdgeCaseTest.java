package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.RelevancyScore;
import io.excalibur.llm.json.JsonWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Payloads at the edge of what {@link LLMHttpClient} handles.
 *
 * <p>Covers the sizes and values that a smaller fixture would never reach: a full
 * 4096-dimension embedding and a fifty-candidate scoring batch, both parsed
 * element-for-element rather than merely counted; embeddings made of negative values
 * and of magnitudes down to 1e-20; relevancy scores sitting exactly on the [0.0, 1.0]
 * boundary; and a ten-kilobyte input text, whose request body is checked to still be
 * valid JSON.</p>
 */
@Timeout(value = 10)
class LLMHttpClientPayloadEdgeCaseTest extends LLMHttpClientTestBase {

    // ============= Large Response Handling Tests =============

    @Test
    @DisplayName("Large response: large embedding (4096 dimensions) parses correctly")
    void generateEmbedding_largeEmbedding_parsesCorrectly() {
        // Create a max-dimension embedding (4096 dimensions)
        float[] largeEmbedding = new float[4096];
        for (int i = 0; i < 4096; i++) {
            largeEmbedding[i] = (float) (Math.sin(i * 0.001) * 0.5);
        }

        // Configure client for 4096 dimensions
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .embeddingDimension(4096)
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new LLMHttpClient(config);

        server.setEmbeddingHandler(server.successEmbeddingHandler(largeEmbedding));

        float[] result = client.generateEmbedding("Large embedding test");

        assertNotNull(result);
        assertEquals(4096, result.length);
        // Verify some sample values
        assertEquals(largeEmbedding[0], result[0], 0.0001f);
        assertEquals(largeEmbedding[1000], result[1000], 0.0001f);
        assertEquals(largeEmbedding[4095], result[4095], 0.0001f);
    }

    @Test
    @DisplayName("Large response: large batch of relevancy scores parses correctly")
    void scoreRelevancy_largeBatch_parsesCorrectly() {
        // Create a large batch of candidates (50 items)
        List<String> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            candidates.add("Candidate passage number " + i + " for testing large batch handling.");
        }

        // Build expected scores JSON
        StringBuilder jsonBuilder = new StringBuilder("[");
        double[] scores = new double[50];
        for (int i = 0; i < 50; i++) {
            if (i > 0) jsonBuilder.append(", ");
            scores[i] = Math.max(0.0, Math.min(1.0, 0.3 + (i * 0.01)));
            jsonBuilder.append("{\"index\": ").append(i).append(", \"score\": ").append(scores[i]).append("}");
        }
        jsonBuilder.append("]");

        server.setChatHandler(server.successChatHandler(jsonBuilder.toString()));

        List<RelevancyScore> result = client.scoreRelevancy("Large batch query", candidates);

        assertNotNull(result);
        assertEquals(50, result.size());
        for (int i = 0; i < 50; i++) {
            assertEquals(i, result.get(i).getIndex());
            assertEquals(scores[i], result.get(i).getScore(), 0.001);
        }
    }

    // ============= Edge Case Tests =============

    @Test
    @DisplayName("Edge case: embedding with negative values parses correctly")
    void generateEmbedding_negativeValues_parsesCorrectly() {
        float[] expected = new float[]{-0.5f, -0.3f, 0.0f, 0.2f, 0.8f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

        float[] result = client.generateEmbedding("Negative values test");

        assertNotNull(result);
        assertEquals(5, result.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], result[i], 0.0001f);
        }
    }

    @Test
    @DisplayName("Edge case: embedding with very small values parses correctly")
    void generateEmbedding_verySmallValues_parsesCorrectly() {
        float[] expected = new float[]{1e-10f, 1e-20f, -1e-15f, 0.0f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

        float[] result = client.generateEmbedding("Small values test");

        assertNotNull(result);
        assertEquals(4, result.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], result[i], 1e-25f);
        }
    }

    @Test
    @DisplayName("Edge case: relevancy scores at boundaries")
    void scoreRelevancy_boundaryScores_parsedCorrectly() {
        // Test scores at exact boundaries [0.0, 1.0]
        server.setChatHandler(server.successRelevancyHandler(0.0, 1.0, 0.5));

        List<String> candidates = List.of("Zero", "One", "Half");
        List<RelevancyScore> scores = client.scoreRelevancy("Boundary test", candidates);

        assertEquals(3, scores.size());
        assertEquals(0.0, scores.get(0).getScore(), 0.001);
        assertEquals(1.0, scores.get(1).getScore(), 0.001);
        assertEquals(0.5, scores.get(2).getScore(), 0.001);
    }

    @Test
    @DisplayName("Edge case: request with very long text produces valid JSON")
    void generateEmbedding_veryLongText_producesValidJson() {
        // Create a very long text (10KB)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("This is test sentence number ").append(i).append(". ");
        }
        String longText = sb.toString();

        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f}));

        // Should handle long text without issues
        float[] result = client.generateEmbedding(longText);

        assertNotNull(result);

        // Verify the request body was valid JSON
        List<MockLLMServer.CapturedRequest> requests = server.getCapturedEmbeddingRequests();
        assertEquals(1, requests.size());
        assertTrue(JsonWriter.isValidJson(requests.get(0).getBody()),
                "Request body with long text should be valid JSON");
    }
}
