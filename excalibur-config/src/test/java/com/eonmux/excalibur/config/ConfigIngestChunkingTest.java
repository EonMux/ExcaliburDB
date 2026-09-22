package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a document is split before it is indexed.
 *
 * <p>The pair has to be validated together rather than one key at a time: each is individually
 * reasonable at values that are impossible as a pair. An overlap at or past the target makes
 * every chunk start where the last one started, so chunking never advances through the
 * document. The chunker refuses that combination itself; these tests pin that the refusal
 * happens while the node is reading its configuration, not on the first document of a crawl
 * that had already been accepted.</p>
 */
@DisplayName("IngestConfig chunking")
class ConfigIngestChunkingTest {

    private static IngestConfig ingest(Map<String, String> props) {
        return new IngestConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("an empty configuration chunks at 512 tokens overlapping by 64")
        void documentedDefaults() {
            IngestConfig settings = ingest(Map.of());

            assertThat(settings.getChunkTargetTokens()).isEqualTo(512);
            assertThat(settings.getChunkOverlapTokens()).isEqualTo(64);
        }
    }

    @Nested
    @DisplayName("Bounds")
    class Bounds {

        @Test
        @DisplayName("a chunk must hold at least one token")
        void targetMustBePositive() {
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.chunk-target-tokens", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk-target-tokens");
        }

        @Test
        @DisplayName("an overlap may be zero but not negative")
        void overlapMustNotBeNegative() {
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.chunk-overlap-tokens", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk-overlap-tokens");

            assertThat(ingest(Map.of("excalibur.ingest.chunk-overlap-tokens", "0"))
                .getChunkOverlapTokens()).isZero();
        }

        @Test
        @DisplayName("an overlap at the target is refused, and the message names both keys")
        void overlapMustBeSmallerThanTheTarget() {
            assertThatThrownBy(() -> ingest(Map.of(
                "excalibur.ingest.chunk-target-tokens", "256",
                "excalibur.ingest.chunk-overlap-tokens", "256")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk-overlap-tokens")
                .hasMessageContaining("chunk-target-tokens");
        }

        @Test
        @DisplayName("a smaller chunk with a proportionate overlap is taken as given")
        void acceptsATunedPair() {
            IngestConfig settings = ingest(Map.of(
                "excalibur.ingest.chunk-target-tokens", "256",
                "excalibur.ingest.chunk-overlap-tokens", "32"));

            assertThat(settings.getChunkTargetTokens()).isEqualTo(256);
            assertThat(settings.getChunkOverlapTokens()).isEqualTo(32);
        }
    }
}
