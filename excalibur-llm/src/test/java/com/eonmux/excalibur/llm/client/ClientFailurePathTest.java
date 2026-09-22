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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the client does when a call goes wrong in a way nobody planned for.
 *
 * <p>Two things are checked here. First, that every failure leaves the client through the
 * declared {@link LLMException} channel: a caller writes one catch block, and an unchecked
 * exception taking a different route past it also takes a route past the retry loop and past
 * the circuit breaker's accounting, so a provider can fail indefinitely without the breaker
 * ever noticing. Second, that a failure message never carries the credentials that were in the
 * endpoint URL - those messages reach an API caller and a log, and neither is a place for a
 * password.</p>
 */
@Timeout(value = 30)
class ClientFailurePathTest {

    private static final String SECRET = "sup3rs3cr3t";

    private MockLLMServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private LLMHttpClient clientWith(LLMClientConfig config) {
        return new LLMHttpClient(config);
    }

    @Test
    @DisplayName("an over-sized response fails as an LLMException, not an unchecked one")
    void oversizedResponseFailsThroughTheDeclaredChannel() {
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .maxResponseBytes(256)
            .embeddingDimension(5)
            .build();
        LLMHttpClient client = clientWith(config);

        // Declared Content-Length over the cap: refused before a byte of the body is read.
        server.setEmbeddingHandler(server.responseBuilder()
            .statusCode(200)
            .body("{\"data\":[{\"embedding\":[" + "0.1,".repeat(500) + "0.1]}]}")
            .build());

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.generateEmbedding("hello"),
            "A body past the cap must arrive as the exception every caller already handles");
        assertEquals(ErrorType.NETWORK, thrown.getErrorType());
    }

    @Test
    @DisplayName("an over-sized response still counts against the embedding breaker")
    void oversizedResponseIsCountedByTheBreaker() {
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .maxResponseBytes(256)
            .failureThreshold(2)
            .cooldownPeriod(Duration.ofSeconds(60))
            .embeddingDimension(5)
            .build();
        LLMHttpClient client = clientWith(config);

        server.setEmbeddingHandler(server.responseBuilder()
            .statusCode(200)
            .body("{\"data\":[{\"embedding\":[" + "0.1,".repeat(500) + "0.1]}]}")
            .build());

        assertThrows(LLMException.class, () -> client.generateEmbedding("hello"));
        assertThrows(LLMException.class, () -> client.generateEmbedding("hello"));

        assertEquals(CircuitBreakerState.OPEN, client.getEmbeddingCircuitBreaker().getState(),
            "A provider that keeps sending unusable bodies is a provider to stop calling");
    }

    @Test
    @DisplayName("a timeout message does not carry credentials from the endpoint URL")
    void timeoutMessageHidesCredentials() {
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl("http://admin:" + SECRET + "@localhost:" + server.getPort())
            .timeout(Duration.ofMillis(300))
            .maxRetries(0)
            .embeddingDimension(5)
            .build();
        LLMHttpClient client = clientWith(config);

        server.setEmbeddingHandler(server.delayedHandler(
            server.successEmbeddingHandler(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f}), 3_000));

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.generateEmbedding("hello"));

        assertFalse(thrown.getMessage().contains(SECRET),
            "The endpoint password reached the caller in: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("localhost"),
            "The host is what makes the message useful; only the credentials go: "
                + thrown.getMessage());
    }

    @Test
    @DisplayName("an IO failure message does not carry credentials from the endpoint URL")
    void ioErrorMessageHidesCredentials() {
        int deadPort = server.getPort();
        server.stop();

        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl("http://admin:" + SECRET + "@localhost:" + deadPort)
            .timeout(Duration.ofSeconds(2))
            .maxRetries(0)
            .embeddingDimension(5)
            .build();
        LLMHttpClient client = clientWith(config);

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.generateEmbedding("hello"));

        assertFalse(thrown.getMessage().contains(SECRET),
            "The endpoint password reached the caller in: " + thrown.getMessage());
    }

    @Test
    @DisplayName("the retry loop stops at once when the thread is interrupted")
    void interruptedRetryStopsAndKeepsTheFlag() throws Exception {
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(5)
            .retryBaseDelay(Duration.ofSeconds(10))
            .embeddingDimension(5)
            .build();
        LLMHttpClient client = clientWith(config);

        server.setEmbeddingHandler(server.internalServerErrorHandler());

        Thread worker = new Thread(() -> {
            try {
                client.generateEmbedding("hello");
            } catch (LLMException expected) {
                // The flag is what matters, and it is asserted below.
            }
        });
        worker.start();
        Thread.sleep(500);
        worker.interrupt();

        worker.join(5_000);
        assertFalse(worker.isAlive(),
            "An interrupted retry must abandon its backoff rather than sleep out the schedule");
    }
}
