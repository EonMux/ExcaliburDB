package com.eonmux.excalibur.llm.embedding;

import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.CircuitBreakerState;
import com.eonmux.excalibur.llm.client.MockLLMServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmbeddingClient}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Embedding vector: non-null, length in [1, 4096], all values finite</li>
 *   <li>Input text: non-null, non-empty string</li>
 *   <li>Batch embeddings: preserve input order</li>
 *   <li>Circuit breaker: proper state transitions</li>
 *   <li>HTTP errors: properly categorized by error type</li>
 * </ul>
 */
@Timeout(value = 10)
class EmbeddingClientTest {

    private MockLLMServer server;
    private LLMClientConfig config;
    private EmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .embeddingModel("text-embedding-3-small")
                .embeddingDimension(1536)
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new EmbeddingClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ==================== Single Embedding Tests ====================

    @Test
    @DisplayName("embed: valid text returns embedding vector")
    void embed_validText_returnsEmbeddingVector() {
        float[] expected = createTestEmbedding(1536, 0.1f);
        server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

        float[] result = client.embed("Test text for embedding");

        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(1536);
        assertThat(result[0]).isEqualTo(0.1f);
    }

    @Test
    @DisplayName("embed: single dimension embedding is valid when configured")
    void embed_singleDimension_validEmbedding() {
        // Configure client for 1-dimensional embeddings
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .embeddingModel("text-embedding-3-small")
                .embeddingDimension(1)
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new EmbeddingClient(config);

        float[] expected = new float[]{0.5f};
        server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

        float[] result = client.embed("Single dimension test");

        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(1);
        assertThat(result[0]).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("embed: max dimension embedding is valid when configured")
    void embed_maxDimension_validEmbedding() {
        // Configure client for 4096-dimensional embeddings (maximum allowed)
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .embeddingModel("text-embedding-3-large")
                .embeddingDimension(4096)
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new EmbeddingClient(config);

        float[] expected = createTestEmbedding(4096, 0.42f);
        server.setEmbeddingHandler(server.successEmbeddingHandler(expected));

        float[] result = client.embed("Max dimension test");

        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(4096);
        assertThat(result[0]).isEqualTo(0.42f);
    }

    @Test
    @DisplayName("embed: null text throws NullPointerException")
    void embed_nullText_throwsNullPointerException() {
        assertThatThrownBy(() -> client.embed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text cannot be null");
    }

    @Test
    @DisplayName("embed: empty text throws IllegalArgumentException")
    void embed_emptyText_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> client.embed(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Text cannot be empty");
    }

    @Test
    @DisplayName("embed: with correlation ID includes header")
    void embed_withCorrelationId_includesHeader() {
        server.setEmbeddingHandler(server.successEmbeddingHandler(createTestEmbedding(1536, 0.1f)));

        client.embed("Test text", "test-correlation-123");

        var requests = server.getCapturedEmbeddingRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getHeader("X-Correlation-ID")).isEqualTo("test-correlation-123");
    }

    @Test
    @DisplayName("embed: null correlation ID works")
    void embed_nullCorrelationId_works() {
        server.setEmbeddingHandler(server.successEmbeddingHandler(createTestEmbedding(1536, 0.1f)));

        float[] result = client.embed("Test", (String) null);

        assertThat(result).isNotNull();
    }

    // ==================== Batch Embedding Tests ====================

    @Test
    @DisplayName("embedBatch: multiple texts returns embeddings in order")
    void embedBatch_multipleTexts_returnsEmbeddingsInOrder() {
        float[][] expected = {
                createTestEmbedding(1536, 0.1f),
                createTestEmbedding(1536, 0.2f),
                createTestEmbedding(1536, 0.3f)
        };
        server.setEmbeddingHandler(createBatchEmbeddingHandler(expected));

        List<float[]> results = client.embedBatch(List.of("First", "Second", "Third"));

        assertThat(results).hasSize(3);
        assertThat(results.get(0)[0]).isEqualTo(0.1f);
        assertThat(results.get(1)[0]).isEqualTo(0.2f);
        assertThat(results.get(2)[0]).isEqualTo(0.3f);
    }

    @Test
    @DisplayName("embedBatch: single text returns single embedding")
    void embedBatch_singleText_returnsSingleEmbedding() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        server.setEmbeddingHandler(createBatchEmbeddingHandler(new float[][]{expected}));

        List<float[]> results = client.embedBatch(List.of("Single"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0)[0]).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("embedBatch: null texts throws NullPointerException")
    void embedBatch_nullTexts_throwsNullPointerException() {
        assertThatThrownBy(() -> client.embedBatch(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Texts cannot be null");
    }

    @Test
    @DisplayName("embedBatch: empty list throws IllegalArgumentException")
    void embedBatch_emptyList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> client.embedBatch(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Texts cannot be empty");
    }

    @Test
    @DisplayName("embedBatch: null element throws NullPointerException")
    void embedBatch_nullElement_throwsNullPointerException() {
        // Use Arrays.asList because List.of() doesn't allow null elements
        assertThatThrownBy(() -> client.embedBatch(java.util.Arrays.asList("Valid", null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text at index 1 cannot be null");
    }

    @Test
    @DisplayName("embedBatch: empty element throws IllegalArgumentException")
    void embedBatch_emptyElement_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> client.embedBatch(List.of("Valid", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Text at index 1 cannot be empty");
    }

    // ==================== Embedding with Metadata Tests ====================

    @Test
    @DisplayName("embedWithMetadata: returns response with embedding and metadata")
    void embedWithMetadata_validText_returnsResponseWithMetadata() {
        float[] expected = createTestEmbedding(1536, 0.7f);
        server.setEmbeddingHandler(createEmbeddingHandlerWithUsage(expected, 15));

        EmbeddingResponse response = client.embedWithMetadata("Test text");

        assertThat(response).isNotNull();
        assertThat(response.embedding()).isEqualTo(expected);
        assertThat(response.model()).isEqualTo("text-embedding-3-small");
        assertThat(response.totalTokens()).isEqualTo(15);
        assertThat(response.dimension()).isEqualTo(1536);
        assertThat(response.hasTokenInfo()).isTrue();
    }

    @Test
    @DisplayName("embedWithMetadata: zero tokens returns hasTokenInfo false")
    void embedWithMetadata_zeroTokens_returnsHasTokenInfoFalse() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        server.setEmbeddingHandler(createEmbeddingHandlerWithUsage(expected, 0));

        EmbeddingResponse response = client.embedWithMetadata("Test");

        assertThat(response.hasTokenInfo()).isFalse();
    }

    // ==================== Error Handling Tests ====================

    @Test
    @DisplayName("embed: HTTP 500 error throws LLMException")
    void embed_httpError_throwsLLMException() {
        server.setEmbeddingHandler(server.errorHandler(500, "Internal Server Error"));

        assertThatThrownBy(() -> client.embed("Error test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.HTTP_ERROR);
    }

    @Test
    @DisplayName("embed: HTTP 401 error throws AUTH_ERROR")
    void embed_http401_throwsAuthError() {
        server.setEmbeddingHandler(server.unauthorizedHandler());

        assertThatThrownBy(() -> client.embed("Auth error test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.AUTH_ERROR);
    }

    @Test
    @DisplayName("embed: HTTP 429 error throws RATE_LIMITED")
    void embed_http429_throwsRateLimited() {
        server.setEmbeddingHandler(server.errorHandler(429, "Too Many Requests"));

        assertThatThrownBy(() -> client.embed("Rate limit test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.RATE_LIMITED);
    }

    @Test
    @DisplayName("embed: empty response throws PARSE_ERROR")
    void embed_emptyResponse_throwsParseError() {
        server.setEmbeddingHandler(exchange -> {
            byte[] bytes = "".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThatThrownBy(() -> client.embed("Empty response test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("embed: missing embedding field throws PARSE_ERROR")
    void embed_missingEmbeddingField_throwsParseError() {
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("Missing field test"));
        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
        assertThat(ex.getMessage()).contains("embedding");
    }

    @Test
    @DisplayName("embed: NaN in embedding throws PARSE_ERROR")
    void embed_responseContainsNaN_throwsParseError() {
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[1.0,NaN,2.0],\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThatThrownBy(() -> client.embed("NaN test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("embed: Infinity in embedding throws PARSE_ERROR")
    void embed_responseContainsInfinity_throwsParseError() {
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[1.0,Infinity,2.0],\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThatThrownBy(() -> client.embed("Infinity test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("embed: zero dimension throws PARSE_ERROR")
    void embed_zeroDimension_throwsParseError() {
        server.setEmbeddingHandler(exchange -> {
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[],\"index\":0}]}";
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThatThrownBy(() -> client.embed("Zero dimension test"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("embed: dimension mismatch with config throws PARSE_ERROR")
    void embed_dimensionMismatch_throwsParseError() {
        // Client is configured for 1536 dimensions (default from setup)
        // But server returns only 512 dimensions
        float[] wrongDimension = createTestEmbedding(512, 0.5f);
        server.setEmbeddingHandler(server.successEmbeddingHandler(wrongDimension));

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("Dimension mismatch test"));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
        assertThat(ex.getMessage()).contains("512");
        assertThat(ex.getMessage()).contains("1536");
        assertThat(ex.getMessage()).contains("does not match configured dimensionality");
    }

    @Test
    @DisplayName("embedBatch: dimension mismatch throws PARSE_ERROR")
    void embedBatch_dimensionMismatch_throwsParseError() {
        // Client is configured for 1536 dimensions
        // But server returns 768-dimensional embeddings
        float[][] wrongDimensions = {
                createTestEmbedding(768, 0.1f),
                createTestEmbedding(768, 0.2f)
        };
        server.setEmbeddingHandler(createBatchEmbeddingHandler(wrongDimensions));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.embedBatch(List.of("First", "Second")));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
        assertThat(ex.getMessage()).contains("768");
        assertThat(ex.getMessage()).contains("1536");
        assertThat(ex.getMessage()).contains("does not match configured dimensionality");
    }

    // ==================== Circuit Breaker Tests ====================

    @Test
    @DisplayName("embedWithCircuitBreaker: success records success")
    void embedWithCircuitBreaker_success_recordsSuccess() {
        server.setEmbeddingHandler(server.successEmbeddingHandler(createTestEmbedding(1536, 0.5f)));

        client.embedWithCircuitBreaker("Test");

        assertThat(client.getCircuitBreaker().getState()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(client.getCircuitBreaker().getFailureCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("embedWithCircuitBreaker: failure records failure")
    void embedWithCircuitBreaker_failure_recordsFailure() {
        server.setEmbeddingHandler(server.errorHandler(500, "Error"));

        assertThatThrownBy(() -> client.embedWithCircuitBreaker("Test"))
                .isInstanceOf(LLMException.class);

        assertThat(client.getCircuitBreaker().getFailureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("embedWithCircuitBreaker: open circuit fails fast")
    void embedWithCircuitBreaker_openCircuit_failsFast() {
        // Create client with low failure threshold
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(60), "test");
        client = new EmbeddingClient(config, cb);

        // Trigger the circuit to open
        server.setEmbeddingHandler(server.errorHandler(500, "Error"));
        assertThatThrownBy(() -> client.embedWithCircuitBreaker("First"))
                .isInstanceOf(LLMException.class);

        // Next call should fail fast with CIRCUIT_OPEN
        assertThatThrownBy(() -> client.embedWithCircuitBreaker("Second"))
                .isInstanceOf(LLMException.class)
                .extracting("errorType")
                .isEqualTo(LLMException.ErrorType.CIRCUIT_OPEN);

        // Verify no additional HTTP requests
        assertThat(server.getEmbeddingRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("embedWithCircuitBreaker: custom circuit breaker")
    void embedWithCircuitBreaker_customCircuitBreaker_usesProvided() {
        CircuitBreaker customCb = new CircuitBreaker(3, Duration.ofSeconds(30), "custom");
        client = new EmbeddingClient(config, customCb);

        server.setEmbeddingHandler(server.successEmbeddingHandler(createTestEmbedding(1536, 0.5f)));

        client.embedWithCircuitBreaker("Test");

        assertThat(client.getCircuitBreaker()).isSameAs(customCb);
        assertThat(customCb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    // ==================== Configuration Tests ====================

    @Test
    @DisplayName("getConfig: returns configuration")
    void getConfig_returnsConfiguration() {
        assertThat(client.getConfig()).isSameAs(config);
    }

    @Test
    @DisplayName("getModel: returns embedding model name")
    void getModel_returnsEmbeddingModelName() {
        assertThat(client.getModel()).isEqualTo("text-embedding-3-small");
    }

    @Test
    @DisplayName("getDimension: returns configured dimension")
    void getDimension_returnsConfiguredDimension() {
        assertThat(client.getDimension()).isEqualTo(1536);
    }

    @Test
    @DisplayName("toString: contains model and dimension")
    void toString_containsModelAndDimension() {
        String str = client.toString();

        assertThat(str).contains("text-embedding-3-small");
        assertThat(str).contains("1536");
        assertThat(str).contains("CLOSED");
    }

    // ==================== EmbeddingResponse Tests ====================

    @Test
    @DisplayName("EmbeddingResponse: defensive copy on construction")
    void embeddingResponse_defensiveCopyOnConstruction() {
        float[] original = new float[]{0.1f, 0.2f, 0.3f};
        EmbeddingResponse response = new EmbeddingResponse(original, "test-model", 10);

        original[0] = 0.9f; // Modify original

        // Response should have original value
        assertThat(response.embedding()[0]).isEqualTo(0.1f);
    }

    @Test
    @DisplayName("EmbeddingResponse: defensive copy on accessor")
    void embeddingResponse_defensiveCopyOnAccessor() {
        EmbeddingResponse response = new EmbeddingResponse(new float[]{0.1f, 0.2f}, "model", 5);

        float[] first = response.embedding();
        first[0] = 0.9f; // Modify returned array

        float[] second = response.embedding();

        // Second call should return unmodified copy
        assertThat(second[0]).isEqualTo(0.1f);
    }

    @Test
    @DisplayName("EmbeddingResponse: null embedding throws NullPointerException")
    void embeddingResponse_nullEmbedding_throwsNPE() {
        assertThatThrownBy(() -> new EmbeddingResponse(null, "model", 5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("EmbeddingResponse: null model throws NullPointerException")
    void embeddingResponse_nullModel_throwsNPE() {
        assertThatThrownBy(() -> new EmbeddingResponse(new float[]{0.1f}, null, 5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("EmbeddingResponse: empty model throws IllegalArgumentException")
    void embeddingResponse_emptyModel_throwsIAE() {
        assertThatThrownBy(() -> new EmbeddingResponse(new float[]{0.1f}, "", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EmbeddingResponse: negative tokens throws IllegalArgumentException")
    void embeddingResponse_negativeTokens_throwsIAE() {
        assertThatThrownBy(() -> new EmbeddingResponse(new float[]{0.1f}, "model", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EmbeddingResponse: NaN in embedding throws IllegalArgumentException")
    void embeddingResponse_nanInEmbedding_throwsIAE() {
        assertThatThrownBy(() -> new EmbeddingResponse(new float[]{0.1f, Float.NaN}, "model", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EmbeddingResponse: equals and hashCode")
    void embeddingResponse_equalsAndHashCode() {
        float[] emb1 = new float[]{0.1f, 0.2f};
        float[] emb2 = new float[]{0.1f, 0.2f};

        EmbeddingResponse r1 = new EmbeddingResponse(emb1, "model", 5);
        EmbeddingResponse r2 = new EmbeddingResponse(emb2, "model", 5);

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    // ==================== Helper Methods ====================

    private float[] createTestEmbedding(int dimension, float startValue) {
        float[] embedding = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            embedding[i] = startValue + (i * 0.0001f);
        }
        return embedding;
    }

    private HttpHandler createBatchEmbeddingHandler(float[][] embeddings) {
        return exchange -> {
            StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");

            for (int i = 0; i < embeddings.length; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"object\":\"embedding\",\"embedding\":[");
                float[] emb = embeddings[i];
                for (int j = 0; j < emb.length; j++) {
                    if (j > 0) sb.append(',');
                    sb.append(emb[j]);
                }
                sb.append("],\"index\":").append(i).append("}");
            }

            sb.append("],\"model\":\"text-embedding-3-small\",\"usage\":{\"total_tokens\":100}}");

            byte[] bytes = sb.toString().getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    private HttpHandler createEmbeddingHandlerWithUsage(float[] embedding, int totalTokens) {
        return exchange -> {
            StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(embedding[i]);
            }
            sb.append("],\"index\":0}],\"model\":\"text-embedding-3-small\",\"usage\":{\"total_tokens\":")
              .append(totalTokens).append("}}");

            byte[] bytes = sb.toString().getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }
}
