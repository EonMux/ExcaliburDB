package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which engine indexes chunk text, and what it reclaims.
 *
 * <p>The engine name is the one setting here that cannot be allowed to fall back on a default when
 * it is not understood. The two engines write to different directories, so a node that quietly
 * chose the other one answers queries from a corpus the operator never built - and does it without
 * any error, because an index with nothing in it is indistinguishable from a corpus that does not
 * match. The tests below pin that it fails at startup instead.
 */
@DisplayName("IngestConfig chunk index")
class ConfigIngestLexicalTest {

    private static IngestConfig ingest(Map<String, String> props) {
        return new IngestConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("an empty configuration indexes with the engine's own index")
        void documentedDefaults() {
            IngestConfig settings = ingest(Map.of());

            assertThat(settings.getLexicalEngine())
                .isEqualTo(IngestConfig.LEXICAL_ENGINE_NATIVE);
            assertThat(settings.getLexicalTombstoneRatio())
                .isEqualTo(IngestConfig.DEFAULT_LEXICAL_TOMBSTONE_RATIO);
            assertThat(settings.getLexicalMaxLatencySeconds())
                .isEqualTo(IngestConfig.DEFAULT_LEXICAL_MAX_LATENCY_SECONDS);
            assertThat(settings.getLexicalMaxSegments())
                .isEqualTo(IngestConfig.DEFAULT_LEXICAL_MAX_SEGMENTS);
        }

        @Test
        @DisplayName("either engine can be asked for by name, in any case")
        void bothEnginesAreSelectable() {
            assertThat(ingest(Map.of("excalibur.ingest.lexical-engine", "lucene"))
                .getLexicalEngine()).isEqualTo(IngestConfig.LEXICAL_ENGINE_LUCENE);
            assertThat(ingest(Map.of("excalibur.ingest.lexical-engine", "  NATIVE "))
                .getLexicalEngine()).isEqualTo(IngestConfig.LEXICAL_ENGINE_NATIVE);
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("an engine nobody implements fails startup rather than falling back")
        void unknownEngineIsRefused() {
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.lexical-engine", "elastic")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexical-engine")
                .hasMessageContaining("elastic");
        }

        @Test
        @DisplayName("a tombstone ratio outside (0, 1] is refused")
        void ratioIsRangeChecked() {
            assertThatThrownBy(() ->
                ingest(Map.of("excalibur.ingest.lexical-tombstone-ratio", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexical-tombstone-ratio");
            assertThatThrownBy(() ->
                ingest(Map.of("excalibur.ingest.lexical-tombstone-ratio", "1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexical-tombstone-ratio");
            assertThat(ingest(Map.of("excalibur.ingest.lexical-tombstone-ratio", "1.0"))
                .getLexicalTombstoneRatio()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a non-positive erasure deadline is refused")
        void latencyMustBePositive() {
            // Zero would make every erasure permanently overdue, so every run would compact the
            // whole store and the ratio would never get a say.
            assertThatThrownBy(() ->
                ingest(Map.of("excalibur.ingest.lexical-max-latency-seconds", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexical-max-latency-seconds");
        }

        @Test
        @DisplayName("a store must be allowed at least one segment")
        void segmentsMustBePositive() {
            assertThatThrownBy(() ->
                ingest(Map.of("excalibur.ingest.lexical-max-segments", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lexical-max-segments");
            assertThat(ingest(Map.of("excalibur.ingest.lexical-max-segments", "64"))
                .getLexicalMaxSegments()).isEqualTo(64);
        }
    }
}
