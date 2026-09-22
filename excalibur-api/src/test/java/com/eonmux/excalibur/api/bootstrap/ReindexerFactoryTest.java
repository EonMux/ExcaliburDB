package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.LlmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a reindex on this node embeds what it rewrites.
 *
 * <p>The question used to be asked one level up, and asked as "should there be a reindexer at
 * all": a node with no embedding endpoint got none, so {@code POST /v1/reindex} answered 503 for
 * the life of the process and a BM25-only corpus could never follow its source. It is the
 * embedding half of a reindex that is unavailable, not the reindex.</p>
 */
@DisplayName("Reindexer factory")
class ReindexerFactoryTest {

    private static LlmConfig llm(Map<String, String> properties) {
        return new LlmConfig(new HashMap<>(properties));
    }

    @Test
    @DisplayName("a node with no API key gets no embedding client")
    void noKeyMeansNoEmbedding() {
        assertThat(ReindexerFactory.embeddingClient(llm(Map.of())))
            .as("there is no endpoint to call, and a client pointed at nothing fails every "
                + "chunk of every document rather than indexing them lexically")
            .isNull();
    }

    @Test
    @DisplayName("a node that declined corpus embedding gets no embedding client")
    void decliningEmbeddingsMeansNoEmbedding() {
        assertThat(ReindexerFactory.embeddingClient(llm(Map.of(
            "excalibur.llm.api-key", "sk-test",
            "excalibur.llm.embeddings-enabled", "false"))))
            .as("ingestion honoured this setting and stored no vectors; a reindex that "
                + "produced them would write a corpus indexed two different ways")
            .isNull();
    }

    @Test
    @DisplayName("a node that embeds its corpus gets a client")
    void keyedNodeEmbeds() {
        assertThat(ReindexerFactory.embeddingClient(llm(Map.of(
            "excalibur.llm.api-key", "sk-test"))))
            .isNotNull();
    }
}
