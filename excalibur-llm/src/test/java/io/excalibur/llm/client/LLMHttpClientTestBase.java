package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.time.Duration;

/**
 * Shared fixture for the {@link LLMHttpClient} suites.
 *
 * <p>Every suite talks to a real HTTP server — a {@link MockLLMServer} bound to a fresh
 * ephemeral port for each test — rather than to a mocked client, so the code under test
 * is the actual request building, response parsing and error classification. This base
 * class starts that server, points a client at it with the default chat and embedding
 * models and a 5 second timeout, and stops the server afterwards.</p>
 *
 * <p>{@code config} and {@code client} are reassignable on purpose: a test that needs a
 * different retry budget, timeout or API key rebuilds them against the same
 * {@code server.getBaseUrl()}.</p>
 */
abstract class LLMHttpClientTestBase {

    MockLLMServer server;
    LLMClientConfig config;
    LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .chatModel("test-model")
                .embeddingModel("test-embedding")
                .timeout(Duration.ofSeconds(5))
                .build();
        client = new LLMHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
}
