package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timeout configuration for {@link LLMHttpClient}.
 *
 * <p>Covers the one knob a deployment is most likely to get wrong: how long the client
 * waits. A missing or null timeout falls back to 30 seconds, any positive duration is
 * taken as given down to a single millisecond, zero and negative durations are rejected
 * at build time rather than producing a client that never waits, and the value the
 * builder accepted is the value the constructed client reports.</p>
 */
@Timeout(value = 10)
class LLMHttpClientTimeoutConfigTest extends LLMHttpClientTestBase {

    @Test
    void config_defaultTimeout_is30Seconds() {
        LLMClientConfig defaultConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .build();

        assertEquals(Duration.ofMillis(30_000L), defaultConfig.getTimeout(),
                "Default timeout should be 30000ms (30 seconds)");
    }

    @Test
    void config_zeroTimeout_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .timeout(Duration.ZERO)
                        .build(),
                "Zero timeout should be rejected");
    }

    @Test
    void config_negativeTimeout_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                LLMClientConfig.builder()
                        .baseUrl("http://localhost:11434")
                        .timeout(Duration.ofMillis(-1000))
                        .build(),
                "Negative timeout should be rejected");
    }

    @Test
    void config_positiveTimeout_isAccepted() {
        Duration customTimeout = Duration.ofSeconds(60);
        LLMClientConfig customConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .timeout(customTimeout)
                .build();

        assertEquals(customTimeout, customConfig.getTimeout(),
                "Positive timeout should be accepted");
    }

    @Test
    void config_nullTimeout_usesDefault() {
        LLMClientConfig configWithNullTimeout = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .timeout(null)
                .build();

        assertEquals(Duration.ofSeconds(30), configWithNullTimeout.getTimeout(),
                "Null timeout should use default of 30 seconds");
    }

    @Test
    void config_smallPositiveTimeout_isAccepted() {
        Duration smallTimeout = Duration.ofMillis(1);
        LLMClientConfig smallConfig = LLMClientConfig.builder()
                .baseUrl("http://localhost:11434")
                .timeout(smallTimeout)
                .build();

        assertEquals(smallTimeout, smallConfig.getTimeout(),
                "Even 1ms timeout should be accepted (positive value)");
    }

    @Test
    void client_usesConfiguredTimeout() {
        Duration customTimeout = Duration.ofSeconds(45);
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .timeout(customTimeout)
                .build();

        client = new LLMHttpClient(config);

        // Verify the config is stored and accessible
        assertEquals(customTimeout, client.getConfig().getTimeout(),
                "Client should use the configured timeout");
    }
}
