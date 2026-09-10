package io.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a node embeds its corpus, separately from whether it can reach a model at all.
 *
 * <p>One API key opens both the embeddings endpoint and the chat endpoint, so without this switch
 * the two capabilities arrive together and leave together. That rules out the deployment lexical
 * retrieval was built for: a corpus of identifiers and error codes indexed for BM25, with the chat
 * model spent on expanding the query instead of on embedding every document.</p>
 */
@DisplayName("Config.Llm corpus embedding")
class ConfigLlmEmbeddingsTest {

    private static Config.Llm llm(Map<String, String> props) {
        return new Config.Llm(new HashMap<>(props));
    }

    @Test
    @DisplayName("a node with a key embeds its corpus unless told otherwise")
    void embeddingIsTheDefault() {
        assertThat(llm(Map.of()).isEmbeddingsEnabled()).isTrue();
    }

    @Test
    @DisplayName("embedding can be turned off while the key stays configured")
    void embeddingCanBeDeclined() {
        assertThat(llm(Map.of("excalibur.llm.embeddings-enabled", "false"))
            .isEmbeddingsEnabled()).isFalse();
    }

    @Test
    @DisplayName("it is spelled the same way as every other flag")
    void itAcceptsTheUsualSpellings() {
        assertThat(llm(Map.of("excalibur.llm.embeddings-enabled", "TRUE"))
            .isEmbeddingsEnabled()).isTrue();
        assertThat(llm(Map.of("excalibur.llm.embeddings-enabled", "  false  "))
            .isEmbeddingsEnabled()).isFalse();
    }
}
