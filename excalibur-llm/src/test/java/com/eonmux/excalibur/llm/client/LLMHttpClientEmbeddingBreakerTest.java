package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.LLMException.ErrorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Holds this client's embedding calls behind a breaker, and keeps that breaker separate from
 * the one guarding chat.
 *
 * <p>Reindexing embeds every chunk of every document through {@link LLMHttpClient}. Left
 * unguarded, a dead provider is rediscovered by each of those chunks in turn, and a reindex
 * that should abort in seconds instead grinds through the retry schedule once per chunk.</p>
 *
 * <p>The two breakers are deliberately not one. {@code /v1/embeddings} and
 * {@code /v1/chat/completions} are different endpoints, usually different models and often
 * different deployments behind the same base URL; either can be saturated or unloaded while
 * the other serves normally. Sharing one breaker couples the failure domains in both
 * directions - a reranker that falls over would stop ingestion, and an embedding model being
 * reloaded would stop reranking - turning each outage into twice the outage.</p>
 */
@Timeout(value = 60)
class LLMHttpClientEmbeddingBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;

    private MockLLMServer server;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .failureThreshold(FAILURE_THRESHOLD)
            .cooldownPeriod(Duration.ofSeconds(60))
            .embeddingDimension(3)
            .build();
        client = new LLMHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("generateEmbedding stops calling a provider that has failed the threshold")
    void embeddingFailsFastOnceOpen() {
        server.setEmbeddingHandler(server.internalServerErrorHandler());

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(LLMException.class, () -> client.generateEmbedding("hello"));
        }

        int reached = server.getEmbeddingRequestCount();
        LLMException thrown = assertThrows(LLMException.class,
            () -> client.generateEmbedding("hello"));

        assertEquals(ErrorType.CIRCUIT_OPEN, thrown.getErrorType(),
            "The embedding path must fail fast once the provider has proved it is down");
        assertEquals(reached, server.getEmbeddingRequestCount(),
            "Nothing may reach a provider the breaker has given up on");
    }

    @Test
    @DisplayName("the embedding breaker is not the chat breaker")
    void breakersAreSeparateInstances() {
        assertNotSame(client.getCircuitBreaker(), client.getEmbeddingCircuitBreaker(),
            "Two endpoints that fail independently need two breakers");
    }

    @Test
    @DisplayName("a failing chat endpoint does not stop embedding")
    void chatFailuresDoNotBlockEmbedding() {
        server.setChatHandler(server.internalServerErrorHandler());
        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f}));

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(LLMException.class,
                () -> client.scoreRelevancyWithCircuitBreaker("q", List.of("a"), null));
        }
        assertEquals(CircuitBreakerState.OPEN, client.getCircuitBreaker().getState());

        float[] embedding = client.generateEmbedding("hello");

        assertEquals(3, embedding.length,
            "A reranker that is down is no reason to stop ingesting documents");
        assertEquals(CircuitBreakerState.CLOSED,
            client.getEmbeddingCircuitBreaker().getState());
    }

    @Test
    @DisplayName("a failing embedding endpoint does not stop chat")
    void embeddingFailuresDoNotBlockChat() {
        server.setEmbeddingHandler(server.internalServerErrorHandler());
        server.setChatHandler(server.successRelevancyHandler(0.5));

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(LLMException.class, () -> client.generateEmbedding("hello"));
        }
        assertEquals(CircuitBreakerState.OPEN, client.getEmbeddingCircuitBreaker().getState());

        assertEquals(1, client.scoreRelevancyWithCircuitBreaker("q", List.of("a"), null).size(),
            "An embedding model being reloaded is no reason to stop reranking");
        assertEquals(CircuitBreakerState.CLOSED, client.getCircuitBreaker().getState());
    }

    @Test
    @DisplayName("a recovered embedding endpoint closes its circuit again")
    void embeddingRecoveryClosesTheCircuit() throws Exception {
        LLMClientConfig quick = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .failureThreshold(FAILURE_THRESHOLD)
            .cooldownPeriod(Duration.ofMillis(150))
            .embeddingDimension(3)
            .build();
        LLMHttpClient quickClient = new LLMHttpClient(quick);

        server.setEmbeddingHandler(server.internalServerErrorHandler());
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertThrows(LLMException.class, () -> quickClient.generateEmbedding("hello"));
        }
        assertEquals(CircuitBreakerState.OPEN, quickClient.getEmbeddingCircuitBreaker().getState());

        server.setEmbeddingHandler(server.successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f}));
        Thread.sleep(200);

        assertEquals(3, quickClient.generateEmbedding("hello").length);
        assertEquals(CircuitBreakerState.CLOSED,
            quickClient.getEmbeddingCircuitBreaker().getState());
    }
}
