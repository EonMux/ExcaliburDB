package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.LLMException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Embedding generation through {@link LLMHttpClient}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Embedding vector: non-null, length in [1, maxDimension], all values finite (not NaN/Inf)</li>
 *   <li>Input text: non-null, non-empty string</li>
 *   <li>HTTP errors are properly categorized</li>
 *   <li>Malformed responses are handled gracefully</li>
 * </ul>
 *
 * <p>Covers what {@code generateEmbedding} will and will not hand back to a caller: a
 * vector is returned verbatim only when every component is finite and the dimension
 * falls in [1, 4096]; NaN, infinity, an empty array, an oversized array, a missing
 * {@code embedding} field or non-numeric elements each become an {@link LLMException}
 * rather than a silently degraded vector. Also covers input validation and the
 * classification of HTTP errors on the embedding endpoint.</p>
 *
 * <h2>Determinism</h2>
 * <p>All tests are deterministic - same mock responses always produce the same results.</p>
 */
@Timeout(value = 10)
class LLMHttpClientEmbeddingTest extends LLMHttpClientTestBase {

    // ============= Embedding Generation Success Path Tests =============

    @Test
    void generateEmbedding_validText_returnsEmbeddingVector() {
        float[] expectedEmbedding = new float[]{0.1f, -0.2f, 0.3f, 0.4f, 0.5f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Test text for embedding");

        assertNotNull(result, "Embedding must not be null");
        assertEquals(expectedEmbedding.length, result.length, "Embedding dimension must match");
        for (int i = 0; i < expectedEmbedding.length; i++) {
            assertEquals(expectedEmbedding[i], result[i], 0.0001f, "Embedding values must match");
        }
    }

    @Test
    void generateEmbedding_singleDimension_validEmbedding() {
        // Domain correctness: minimum valid dimension is 1
        float[] expectedEmbedding = new float[]{0.123f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Single dimension test");

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals(0.123f, result[0], 0.0001f);
    }

    @Test
    void generateEmbedding_maxDimension_validEmbedding() {
        // Domain correctness: maximum supported dimension is 4096
        float[] expectedEmbedding = new float[4096];
        for (int i = 0; i < 4096; i++) {
            expectedEmbedding[i] = (float) (Math.sin(i) * 0.5); // Values in [-0.5, 0.5]
        }
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Max dimension test");

        assertNotNull(result);
        assertEquals(4096, result.length);
        // Verify all values are finite and match
        for (int i = 0; i < 4096; i++) {
            assertTrue(Float.isFinite(result[i]), "All values must be finite");
            assertEquals(expectedEmbedding[i], result[i], 0.0001f);
        }
    }

    @Test
    void generateEmbedding_withCorrelationId_includesHeader() {
        float[] expectedEmbedding = new float[]{0.5f, 0.5f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        client.generateEmbedding("Test text", "embedding-correlation-456");

        List<MockLLMServer.CapturedRequest> requests = server.getCapturedEmbeddingRequests();
        assertEquals("embedding-correlation-456", requests.get(0).getHeader("X-Correlation-ID"));
    }

    @Test
    void generateEmbedding_nullCorrelationId_works() {
        float[] expectedEmbedding = new float[]{0.1f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expectedEmbedding));

        float[] result = client.generateEmbedding("Test", (String) null);

        assertNotNull(result);
        assertEquals(1, result.length);
    }

    // ============= Embedding Input Validation Tests =============

    @Test
    void generateEmbedding_nullText_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> client.generateEmbedding(null));
    }

    @Test
    void generateEmbedding_emptyText_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> client.generateEmbedding(""));
    }

    // ============= Embedding Domain Correctness: Finite Values Tests =============

    @Test
    void generateEmbedding_responseContainsNaN_throwsLLMException() {
        // Domain correctness: NaN is not a valid embedding value
        server.setEmbeddingHandler(server.embeddingWithNaNHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test with NaN"));

        assertTrue(ex.getMessage().contains("NaN"), "Error message should mention NaN");
    }

    @Test
    void generateEmbedding_responseContainsPositiveInfinity_throwsLLMException() {
        // Domain correctness: Infinity is not a valid embedding value
        server.setEmbeddingHandler(server.embeddingWithInfinityHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Test with Infinity"));

        // Error message should mention non-finite values (Infinity or Infinite)
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("infinite") || msg.contains("infinity") || msg.contains("finite"),
                "Error message should mention non-finite values: " + ex.getMessage());
    }

    @Test
    void generateEmbedding_allFiniteValues_accepted() {
        // Domain correctness: all values must be finite (bounded, not NaN/Inf)
        float[] validEmbedding = new float[]{
            -1.0f, -0.5f, 0.0f, 0.5f, 1.0f,
            Float.MAX_VALUE, Float.MIN_VALUE, -Float.MAX_VALUE,
            0.000001f, -0.000001f
        };
        server.setEmbeddingHandler(server.successEmbeddingHandler(validEmbedding));

        float[] result = client.generateEmbedding("Test with boundary values");

        assertNotNull(result);
        assertEquals(validEmbedding.length, result.length);
        for (float v : result) {
            assertTrue(Float.isFinite(v), "All values must be finite");
        }
    }

    // ============= Embedding Domain Correctness: Dimension Range Tests =============

    @Test
    void generateEmbedding_zeroDimension_throwsLLMException() {
        // Domain correctness: embedding dimension must be >= 1
        // Custom handler that returns empty embedding array
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[],\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Empty embedding test"));

        assertTrue(ex.getMessage().contains("dimension") ||
                   ex.getMessage().contains("range") ||
                   ex.getMessage().contains("Empty"),
                "Error message should mention dimension constraint or empty array: " + ex.getMessage());
    }

    @Test
    void generateEmbedding_exceedsMaxDimension_throwsLLMException() {
        // Domain correctness: embedding dimension must be <= 4096
        // Use a custom handler to bypass MockLLMServer's validation and test client-side validation
        server.setEmbeddingHandler(exchange -> {
            // Build a JSON response with 4097 dimensions
            StringBuilder sb = new StringBuilder();
            sb.append("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
            for (int i = 0; i < 4097; i++) {
                if (i > 0) sb.append(",");
                sb.append("0.1");
            }
            sb.append("],\"index\":0}]}");
            String response = sb.toString();
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Oversized embedding test"));

        assertTrue(ex.getMessage().contains("dimension") || ex.getMessage().contains("range"),
                "Error message should mention dimension constraint: " + ex.getMessage());
    }

    // ============= Embedding Error Handling Tests =============

    @Test
    void generateEmbedding_httpError_throwsLLMException() {
        server.setEmbeddingHandler(server.errorHandler(500, "Internal Server Error"));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Error test"));

        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void generateEmbedding_http401_throwsLLMException() {
        server.setEmbeddingHandler(server.unauthorizedHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Auth error test"));

        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void generateEmbedding_emptyResponse_throwsLLMException() {
        server.setEmbeddingHandler(server.emptyResponseHandler());

        assertThrows(LLMException.class, () -> client.generateEmbedding("Empty response test"));
    }

    @Test
    void generateEmbedding_missingEmbeddingField_throwsLLMException() {
        server.setEmbeddingHandler(server.missingEmbeddingFieldHandler());

        LLMException ex = assertThrows(LLMException.class,
                () -> client.generateEmbedding("Missing field test"));

        assertTrue(ex.getMessage().contains("embedding"));
    }

    @Test
    void generateEmbedding_malformedEmbeddingArray_throwsLLMException() {
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"data\":[{\"embedding\":[\"not\",\"a\",\"number\"]}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThrows(LLMException.class, () -> client.generateEmbedding("Malformed test"));
    }
}
