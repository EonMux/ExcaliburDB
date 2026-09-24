package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the chat and embedding endpoints are resolved, and when each one is used.
 *
 * <p>The two roles can live on different servers - a hosted chat model and an embedding model on
 * a local GPU box is the ordinary case - and neither needs a key when the server it names does
 * not ask for one. What a key decides is only whether the hosted default endpoint, which always
 * asks, can be called.</p>
 */
@DisplayName("LlmConfig endpoints")
class ConfigLlmEndpointsTest {

    private static LlmConfig llm(Map<String, String> props) {
        return new LlmConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("both roles inherit the shared base URL and key")
        void bothRolesInheritTheSharedSettings() {
            LlmConfig config = llm(Map.of(
                "excalibur.llm.base-url", "https://models.example.test/v1",
                "excalibur.llm.api-key", "sk-shared"));

            assertThat(config.chatEndpoint().baseUrl()).isEqualTo("https://models.example.test/v1");
            assertThat(config.chatEndpoint().apiKey()).isEqualTo("sk-shared");
            assertThat(config.embeddingEndpoint().baseUrl())
                .isEqualTo("https://models.example.test/v1");
            assertThat(config.embeddingEndpoint().apiKey()).isEqualTo("sk-shared");
        }

        @Test
        @DisplayName("a role's own base URL and key win over the shared ones")
        void roleSettingsWin() {
            LlmConfig config = llm(Map.of(
                "excalibur.llm.base-url", "https://models.example.test/v1",
                "excalibur.llm.api-key", "sk-shared",
                "excalibur.llm.embedding-base-url", "http://localhost:11434",
                "excalibur.llm.chat-api-key", "sk-chat"));

            assertThat(config.embeddingEndpoint().baseUrl()).isEqualTo("http://localhost:11434");
            assertThat(config.embeddingEndpoint().apiKey()).isEqualTo("sk-shared");
            assertThat(config.chatEndpoint().baseUrl()).isEqualTo("https://models.example.test/v1");
            assertThat(config.chatEndpoint().apiKey()).isEqualTo("sk-chat");
        }

        @Test
        @DisplayName("a blank role setting is unset, not an override")
        void blankRoleSettingInherits() {
            LlmConfig config = llm(Map.of(
                "excalibur.llm.base-url", "http://localhost:8000",
                "excalibur.llm.chat-base-url", "   ",
                "excalibur.llm.api-key", "sk-shared",
                "excalibur.llm.chat-api-key", " "));

            assertThat(config.chatEndpoint().baseUrl()).isEqualTo("http://localhost:8000");
            assertThat(config.chatEndpoint().apiKey()).isEqualTo("sk-shared");
        }

        @Test
        @DisplayName("each role carries its own role and enabled flag")
        void rolesAreDistinct() {
            LlmConfig config = llm(Map.of(
                "excalibur.llm.chat-enabled", "false"));

            assertThat(config.chatEndpoint().role()).isEqualTo(LlmEndpoint.Role.CHAT);
            assertThat(config.chatEndpoint().enabled()).isFalse();
            assertThat(config.embeddingEndpoint().role()).isEqualTo(LlmEndpoint.Role.EMBEDDING);
            assertThat(config.embeddingEndpoint().enabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("enablement")
    class Enablement {

        @Test
        @DisplayName("the shipped defaults call nothing: the hosted endpoint needs a key")
        void defaultsAreKeylessAndHosted() {
            LlmConfig config = llm(Map.of());

            assertThat(config.chatEndpoint().status())
                .isEqualTo(LlmEndpoint.Status.MISSING_API_KEY);
            assertThat(config.embeddingEndpoint().status())
                .isEqualTo(LlmEndpoint.Status.MISSING_API_KEY);
            assertThat(config.chatEndpoint().isActive()).isFalse();
            assertThat(config.embeddingEndpoint().isActive()).isFalse();
        }

        @Test
        @DisplayName("a key turns the hosted endpoint on for both roles")
        void keyEnablesHostedDefault() {
            LlmConfig config = llm(Map.of("excalibur.llm.api-key", "sk-test"));

            assertThat(config.chatEndpoint().isActive()).isTrue();
            assertThat(config.embeddingEndpoint().isActive()).isTrue();
        }

        @Test
        @DisplayName("a keyless local server is used: Ollama, vLLM and TGI need no key")
        void keylessLocalServerIsActive() {
            LlmConfig config = llm(Map.of("excalibur.llm.base-url", "http://localhost:11434"));

            assertThat(config.chatEndpoint().status()).isEqualTo(LlmEndpoint.Status.ACTIVE);
            assertThat(config.embeddingEndpoint().status()).isEqualTo(LlmEndpoint.Status.ACTIVE);
            assertThat(config.embeddingEndpoint().hasApiKey()).isFalse();
        }

        @Test
        @DisplayName("the hosted default is recognised however its root is spelled")
        void hostedDefaultSpellings() {
            for (String spelling : new String[]{"https://api.openai.com", "https://api.openai.com/",
                "https://API.OpenAI.com/v1/", "https://api.openai.com/v1"}) {
                assertThat(llm(Map.of("excalibur.llm.base-url", spelling)).chatEndpoint()
                    .requiresApiKey()).as(spelling).isTrue();
            }
            assertThat(llm(Map.of("excalibur.llm.base-url", "https://api.example.com/v1"))
                .chatEndpoint().requiresApiKey()).isFalse();
        }

        @Test
        @DisplayName("an explicit false wins over a key")
        void flagWinsOverKey() {
            LlmConfig config = llm(Map.of(
                "excalibur.llm.api-key", "sk-test",
                "excalibur.llm.chat-enabled", "false",
                "excalibur.llm.embeddings-enabled", "false"));

            assertThat(config.chatEndpoint().status()).isEqualTo(LlmEndpoint.Status.DISABLED);
            assertThat(config.embeddingEndpoint().status()).isEqualTo(LlmEndpoint.Status.DISABLED);
        }

        @Test
        @DisplayName("a local embedding server is used beside a hosted chat model with no key")
        void rolesAreDecidedIndependently() {
            LlmConfig config = llm(Map.of(
                "excalibur.llm.embedding-base-url", "http://127.0.0.1:8080"));

            assertThat(config.embeddingEndpoint().isActive()).isTrue();
            assertThat(config.chatEndpoint().status())
                .isEqualTo(LlmEndpoint.Status.MISSING_API_KEY);
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("the description of a missing key says which settings enable the role")
        void missingKeyNamesTheSettings() {
            String description = llm(Map.of()).embeddingEndpoint().describe();

            assertThat(description)
                .contains("embedding endpoint")
                .contains("excalibur.llm.embedding-api-key")
                .contains("excalibur.llm.api-key")
                .contains("EXCALIBUR_LLM_API_KEY")
                .contains("excalibur.llm.embedding-base-url");
        }

        @Test
        @DisplayName("the description of a disabled role names the flag that disabled it")
        void disabledNamesTheFlag() {
            assertThat(llm(Map.of("excalibur.llm.chat-enabled", "false")).chatEndpoint()
                .describe()).contains("excalibur.llm.chat-enabled=false");
            assertThat(llm(Map.of("excalibur.llm.embeddings-enabled", "false"))
                .embeddingEndpoint().describe()).contains("excalibur.llm.embeddings-enabled=false");
        }

        @Test
        @DisplayName("an active keyless endpoint says it sends no key, and what a 401 means")
        void activeKeylessSaysSo() {
            String description = llm(Map.of("excalibur.llm.base-url", "http://localhost:11434"))
                .chatEndpoint().describe();

            assertThat(description).contains("active").contains("http://localhost:11434")
                .contains("no API key").contains("401");
        }

        @Test
        @DisplayName("an active keyed endpoint says a key is sent, without the key")
        void activeKeyedDoesNotLeak() {
            LlmEndpoint endpoint = llm(Map.of("excalibur.llm.api-key", "sk-secret")).chatEndpoint();

            assertThat(endpoint.describe()).contains("active").doesNotContain("sk-secret");
            assertThat(endpoint.toString()).doesNotContain("sk-secret");
        }
    }
}
