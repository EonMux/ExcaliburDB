package com.eonmux.excalibur.llm.client;

import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.LLMException.ErrorType;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A 401 from a model endpoint names the setting that fixes it.
 *
 * <p>A node calls a keyless endpoint whenever one is configured, so a server that wants a key is
 * discovered by its refusal. That refusal has to read as a credential problem - whether a key was
 * sent at all, and where to put one - not as a generic HTTP failure.</p>
 */
@Timeout(value = 30)
@DisplayName("Endpoint authentication failures")
class AuthFailureTest {

    private MockLLMServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        server.setChatHandler(server.unauthorizedHandler());
        server.setEmbeddingHandler(server.unauthorizedHandler());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private LLMClientConfig config(String apiKey) {
        return LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(5))
            .maxRetries(2)
            .embeddingDimension(5)
            .build();
    }

    @Test
    @DisplayName("a keyless chat call refused with 401 says no key is configured")
    void keylessChatRefusal() {
        LLMHttpClient client = new LLMHttpClient(config(""));

        assertThatThrownBy(() -> client.scoreRelevancy("query", List.of("candidate")))
            .isInstanceOfSatisfying(LLMException.class, e -> {
                assertThat(e.getErrorType()).isEqualTo(ErrorType.AUTH_ERROR);
                assertThat(e.getStatusCode()).isEqualTo(401);
            })
            .hasMessageContaining("requires an API key and none is configured")
            .hasMessageContaining("excalibur.llm.chat-api-key")
            .hasMessageContaining("EXCALIBUR_LLM_API_KEY");
        // Not retried: a missing credential does not appear on the second attempt.
        assertThat(server.getChatRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a keyed embedding call refused with 401 says the key was rejected")
    void rejectedEmbeddingKey() {
        EmbeddingClient client = new EmbeddingClient(config("sk-wrong"));

        assertThatThrownBy(() -> client.embed("hello"))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("Embedding API")
            .hasMessageContaining("configured API key was rejected")
            .hasMessageNotContaining("sk-wrong");
    }

    @Test
    @DisplayName("a keyless embedding call refused with 401 says no key is configured")
    void keylessEmbeddingRefusal() {
        EmbeddingClient client = new EmbeddingClient(config(""));

        assertThatThrownBy(() -> client.embed("hello"))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("none is configured")
            .hasMessageContaining("excalibur.llm.embedding-api-key");
    }

    @Test
    @DisplayName("only 401 and 403 are authentication refusals")
    void authStatuses() {
        assertThat(AuthFailure.isAuthStatus(401)).isTrue();
        assertThat(AuthFailure.isAuthStatus(403)).isTrue();
        assertThat(AuthFailure.isAuthStatus(400)).isFalse();
        assertThat(AuthFailure.isAuthStatus(429)).isFalse();
    }

    @Test
    @DisplayName("the first refusal is logged at ERROR, later ones only at DEBUG")
    void reportedOnce() {
        RecordingLogger log = new RecordingLogger();
        AuthFailure failure = new AuthFailure();

        failure.report(log, "first");
        failure.report(log, "second");
        failure.report(log, "third");

        assertThat(log.events).containsExactly("ERROR first", "DEBUG second", "DEBUG third");
    }

    /** Records every call as {@code LEVEL message}, with every level enabled. */
    private static final class RecordingLogger extends LegacyAbstractLogger {

        private final List<String> events = new ArrayList<>();

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return RecordingLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String message,
                                                   Object[] arguments, Throwable throwable) {
            events.add(level + " " + message);
        }
    }
}
