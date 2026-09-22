package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps an untrusted correlation id from deciding what this node writes on the wire.
 *
 * <p>The id arrives from whatever called the API and is copied into a header on the request the
 * node makes to its model provider. Nothing validated it on the way. Two things follow: a value
 * carrying a carriage return or newline is a header-injection attempt, and a value of
 * unbounded length is an unbounded write to a third party on every single request.</p>
 *
 * <p>The JDK's own request builder rejects control characters, so the injection does not land -
 * but it lands as an {@link IllegalArgumentException} thrown from inside the client, an
 * exception no caller declares or catches, which bypasses the retry loop and the circuit
 * breaker's accounting on its way out. Either way the input has to be checked before it is
 * used, which is what these tests require.</p>
 */
@Timeout(value = 30)
class CorrelationIdHeaderTest {

    private static final String HEADER = "X-Correlation-ID";

    private MockLLMServer server;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .embeddingDimension(5)
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
    @DisplayName("a well-formed correlation id is passed through untouched")
    void wellFormedIdIsPreserved() {
        client.generateEmbedding("hello", "7f3c1a20-0b1e-4a6d-9f11-2c3d4e5f6071");

        assertEquals("7f3c1a20-0b1e-4a6d-9f11-2c3d4e5f6071",
            server.getLastRequestHeader(HEADER),
            "Tracing is the whole point; a legitimate id must survive");
    }

    @Test
    @DisplayName("a correlation id carrying a newline never reaches the wire")
    void newlineIdIsNotSent() {
        String hostile = "abc\r\nX-Injected: yes";

        assertDoesNotThrow(() -> client.generateEmbedding("hello", hostile),
            "A malformed trace header must not take the request down with it");

        String sent = server.getLastRequestHeader(HEADER);
        assertNotNull(sent, "A replacement id is still an id");
        assertTrue(sent.indexOf('\r') < 0 && sent.indexOf('\n') < 0,
            "No line break may reach the header: " + sent);
        assertTrue(server.getLastRequestHeader("X-Injected") == null,
            "The injected header must not exist");
    }

    @Test
    @DisplayName("an overlong correlation id is not written verbatim")
    void overlongIdIsBounded() {
        String overlong = "a".repeat(100_000);

        assertDoesNotThrow(() -> client.generateEmbedding("hello", overlong));

        String sent = server.getLastRequestHeader(HEADER);
        assertNotNull(sent);
        assertTrue(sent.length() <= 128,
            "An unbounded value is an unbounded write to the provider on every request; "
                + "sent " + sent.length() + " characters");
    }

    @Test
    @DisplayName("a correlation id carrying control characters is replaced")
    void controlCharactersAreReplaced() {
        assertDoesNotThrow(() -> client.generateEmbedding("hello", "ab\u0000cd\u007f"));

        String sent = server.getLastRequestHeader(HEADER);
        assertNotNull(sent);
        for (int i = 0; i < sent.length(); i++) {
            char c = sent.charAt(i);
            assertTrue(c >= 0x20 && c < 0x7f, "Control character at " + i + " in: " + sent);
        }
    }

    @Test
    @DisplayName("a blank correlation id sends no header at all")
    void blankIdSendsNoHeader() {
        client.generateEmbedding("hello", "   ");

        assertEquals(null, server.getLastRequestHeader(HEADER),
            "Absent is absent; inventing an id for a caller that supplied none adds noise");
    }

    @Test
    @DisplayName("the dedicated embedding client validates the correlation id too")
    void embeddingClientIsAlsoValidated() {
        com.eonmux.excalibur.llm.embedding.EmbeddingClient embeddingClient =
            new com.eonmux.excalibur.llm.embedding.EmbeddingClient(LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .embeddingDimension(5)
                .build());

        assertDoesNotThrow(() -> embeddingClient.embed("hello", "bad\r\nX-Injected: yes"));

        String sent = server.getLastRequestHeader(HEADER);
        assertNotNull(sent);
        assertTrue(sent.indexOf('\r') < 0 && sent.indexOf('\n') < 0,
            "No line break may reach the header: " + sent);
    }

    @Test
    @DisplayName("the chat path validates the correlation id too")
    void chatPathIsAlsoValidated() {
        server.setChatHandler(server.successRelevancyHandler(0.5));

        assertDoesNotThrow(
            () -> client.scoreRelevancy("q", List.of("a"), "bad\r\nX-Injected: yes"));

        String sent = server.getLastRequestHeader(HEADER);
        assertNotNull(sent);
        assertTrue(sent.indexOf('\r') < 0 && sent.indexOf('\n') < 0,
            "No line break may reach the header: " + sent);
    }
}
