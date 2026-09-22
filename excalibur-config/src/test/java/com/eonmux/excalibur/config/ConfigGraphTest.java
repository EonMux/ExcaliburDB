package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the graph-RAG configuration section.
 *
 * <p>These values decide whether {@code /v1/graph/query} answers at all: the abstention
 * thresholds are what separates "here is the evidence" from "I do not trust this". A silently
 * mis-parsed threshold turns one into the other, so the cases here are the malformed ones.
 */
@DisplayName("GraphConfig")
class ConfigGraphTest {

    private static GraphConfig graph(Map<String, String> props) {
        return new GraphConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("an empty configuration produces the documented defaults")
        void documentedDefaults() {
            GraphConfig settings = graph(Map.of());

            assertThat(settings.getPath()).isNull();
            assertThat(settings.getTopK()).isEqualTo(GraphConfig.DEFAULT_TOP_K);
            assertThat(settings.getContextTokenBudget())
                .isEqualTo(GraphConfig.DEFAULT_CONTEXT_TOKEN_BUDGET);
            assertThat(settings.getMinScore()).isEqualTo(GraphConfig.DEFAULT_MIN_SCORE);
            assertThat(settings.getMinSeparation())
                .isEqualTo(GraphConfig.DEFAULT_MIN_SEPARATION);
            assertThat(settings.getMinMeanConfidence())
                .isEqualTo(GraphConfig.DEFAULT_MIN_MEAN_CONFIDENCE);
            assertThat(settings.getMaxAmbiguousCandidates())
                .isEqualTo(GraphConfig.DEFAULT_MAX_AMBIGUOUS_CANDIDATES);
            assertThat(settings.getConfidenceTemperature())
                .as("the honest default is no correction, because none has been measured")
                .isEqualTo(GraphConfig.DEFAULT_CONFIDENCE_TEMPERATURE);
            assertThat(settings.getRamBufferMb()).isEqualTo(GraphConfig.DEFAULT_RAM_BUFFER_MB);
        }

        @Test
        @DisplayName("the score gates default to values that mean something on the unit relevance scale")
        void scoreGatesDefaultOnTheUnitScale() {
            // Retrieval reports relevance in [0, 1]: a share of the attainable BM25 score, or a
            // vector similarity. Five percent of a question's term weight, and a one-percent gap,
            // are gates that fire on real questions; thresholds sized for a reciprocal-rank score
            // near 1/61 would pass everything on this scale.
            GraphConfig settings = graph(Map.of());

            assertThat(settings.getMinScore()).isEqualTo(0.05);
            assertThat(settings.getMinSeparation()).isEqualTo(0.01);
        }

        @Test
        @DisplayName("a null path means the data directory decides, not the empty string")
        void nullPathIsDistinctFromBlank() {
            // GraphRagFactory treats null and blank alike, but the accessor must not turn an
            // unset key into a relative path of "" that resolves to the working directory.
            assertThat(graph(Map.of()).getPath()).isNull();
        }
    }

    @Nested
    @DisplayName("Explicit values")
    class ExplicitValues {

        @Test
        @DisplayName("every key is read")
        void everyKeyIsRead() {
            GraphConfig settings = graph(Map.of(
                "excalibur.graph.path", "/srv/graph",
                "excalibur.graph.top-k", "42",
                "excalibur.graph.context-token-budget", "8000",
                "excalibur.graph.min-score", "0.25",
                "excalibur.graph.min-separation", "0.1",
                "excalibur.graph.min-mean-confidence", "0.75",
                "excalibur.graph.max-ambiguous-candidates", "3",
                "excalibur.graph.confidence-temperature", "2.5",
                "excalibur.graph.ram-buffer-mb", "64"));

            assertThat(settings.getPath()).isEqualTo("/srv/graph");
            assertThat(settings.getTopK()).isEqualTo(42);
            assertThat(settings.getContextTokenBudget()).isEqualTo(8000);
            assertThat(settings.getMinScore()).isEqualTo(0.25);
            assertThat(settings.getMinSeparation()).isEqualTo(0.1);
            assertThat(settings.getMinMeanConfidence()).isEqualTo(0.75);
            assertThat(settings.getMaxAmbiguousCandidates()).isEqualTo(3);
            assertThat(settings.getConfidenceTemperature()).isEqualTo(2.5);
            assertThat(settings.getRamBufferMb()).isEqualTo(64.0);
        }

        @Test
        @DisplayName("the namespace tiers are read as lists, trimmed and without blanks")
        void namespaceTiersAreLists() {
            GraphConfig settings = graph(Map.of(
                "excalibur.graph.hot-namespaces", " vip , , flagship ",
                "excalibur.graph.cold-namespaces", "bulk-import,archive,"));

            assertThat(settings.getHotNamespaces()).containsExactly("vip", "flagship");
            assertThat(settings.getColdNamespaces()).containsExactly("bulk-import", "archive");
        }

        @Test
        @DisplayName("no namespace is hot or cold unless it is named")
        void namespaceTiersDefaultToEmpty() {
            // Empty means the shipped default: the shared index pinned, everything else warm and
            // evicted by recency. A deployment gets a different policy only by asking for one.
            assertThat(graph(Map.of()).getHotNamespaces()).isEmpty();
            assertThat(graph(Map.of()).getColdNamespaces()).isEmpty();
        }

        @Test
        @DisplayName("the boundary values of a threshold are accepted")
        void thresholdBoundaries() {
            assertThat(graph(Map.of("excalibur.graph.min-score", "0.0")).getMinScore()).isZero();
            assertThat(graph(Map.of("excalibur.graph.min-score", "1.0")).getMinScore()).isOne();
        }
    }

    @Nested
    @DisplayName("Refusing values outside their domain")
    class Refusing {

        @Test
        @DisplayName("a threshold above one is refused")
        void thresholdAboveOne() {
            // Above 1.0 nothing can ever clear it, so the endpoint would decline every question
            // while reporting a healthy configuration.
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.min-score", "1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-score");
        }

        @Test
        @DisplayName("a tombstone ratio of zero is refused")
        void zeroTombstoneRatio() {
            // Every store is at or above a ratio of zero, so every tenant would compact on open.
            assertThatThrownBy(
                () -> graph(Map.of("excalibur.graph.erasure-tombstone-ratio", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("erasure-tombstone-ratio");
            assertThat(graph(Map.of("excalibur.graph.erasure-tombstone-ratio", "1"))
                .getErasureTombstoneRatio()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a negative threshold is refused")
        void negativeThreshold() {
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.min-separation", "-0.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-separation");
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.min-mean-confidence", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-mean-confidence");
        }

        @Test
        @DisplayName("a temperature outside the range a fit can produce is refused")
        void temperatureOutOfRange() {
            assertThatThrownBy(
                () -> graph(Map.of("excalibur.graph.confidence-temperature", "0")))
                .as("dividing log-odds by zero is not a cautious node, it is a broken one")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence-temperature");
            assertThatThrownBy(
                () -> graph(Map.of("excalibur.graph.confidence-temperature", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence-temperature");
            assertThatThrownBy(
                () -> graph(Map.of("excalibur.graph.confidence-temperature", "100")))
                .as("no fit can produce this, so it is a typo rather than a decision")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence-temperature");
        }

        @Test
        @DisplayName("a non-positive count is refused")
        void nonPositiveCounts() {
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.top-k", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top-k");
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.context-token-budget", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-token-budget");
            assertThatThrownBy(() ->
                graph(Map.of("excalibur.graph.max-ambiguous-candidates", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-ambiguous-candidates");
        }

        @Test
        @DisplayName("one ambiguous candidate is refused here, not later at graph-open")
        void oneAmbiguousCandidateIsRefused() {
            // The abstention policy needs two readings to call anything ambiguous and throws on
            // one. Accepting it here only moved the failure to the graph open, where it reads as
            // a broken store rather than a configuration value the operator can correct.
            assertThatThrownBy(() ->
                graph(Map.of("excalibur.graph.max-ambiguous-candidates", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-ambiguous-candidates must be at least 2");
        }

        @Test
        @DisplayName("a non-positive RAM buffer is refused")
        void nonPositiveRamBuffer() {
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.ram-buffer-mb", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ram-buffer-mb");
        }
    }

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("two sections built from the same properties are equal")
        void equalityFollowsTheProperties() {
            Map<String, String> props = Map.of("excalibur.graph.top-k", "7");

            assertThat(graph(props)).isEqualTo(graph(props)).hasSameHashCodeAs(graph(props));
            assertThat(graph(props)).isNotEqualTo(graph(Map.of("excalibur.graph.top-k", "8")));
            assertThat(graph(props)).isNotEqualTo(null).isNotEqualTo("not a config");
            assertThat(graph(props)).isEqualTo(graph(props));
        }

        @Test
        @DisplayName("every configured key is part of the section's identity")
        void equalityCoversEveryKey() {
            // Equality is what tells a caller two configurations are the same configuration.
            // A field left out of it says "identical" about sections that would run differently.
            Map<String, String> base = Map.of("excalibur.graph.top-k", "7");
            Map<String, String> keys = Map.of(
                "excalibur.graph.path", "/srv/graph",
                "excalibur.graph.extraction-enabled", "true",
                "excalibur.graph.extractor-version", "3",
                "excalibur.graph.entity-vectors-enabled", "false",
                "excalibur.graph.single-valued-relations", "employed-by",
                "excalibur.graph.hot-namespaces", "vip",
                "excalibur.graph.cold-namespaces", "bulk-import",
                "excalibur.graph.max-open-namespaces", "32",
                "excalibur.graph.erasure-tombstone-ratio", "0.4",
                "excalibur.graph.erasure-max-latency-seconds", "600");

            keys.forEach((key, value) -> {
                Map<String, String> changed = new HashMap<>(base);
                changed.put(key, value);
                assertThat(graph(changed))
                    .as("%s must change the section's identity", key)
                    .isNotEqualTo(graph(base));
            });
        }

        @Test
        @DisplayName("toString names the graph root without pretending one was configured")
        void toStringIsHonestAboutTheDefault() {
            // The key names the root the per-namespace graphs live under, not a graph directory.
            assertThat(graph(Map.of()).toString()).contains("root=<data dir>,");
            assertThat(graph(Map.of("excalibur.graph.path", "/srv/g")).toString())
                .contains("/srv/g");
        }
    }

    @Test
    @DisplayName("the section is reachable from a whole Config, and taken into its identity")
    void reachableFromConfig() {
        Config withGraph = new Config(new HashMap<>(Map.of("excalibur.graph.top-k", "5")));
        Config withDefault = new Config(new HashMap<>());

        assertThat(withGraph.graph().getTopK()).isEqualTo(5);
        assertThat(withGraph.toString()).contains("graph=");
        assertThat(withGraph).isNotEqualTo(withDefault);
        assertThat(withGraph.hashCode()).isNotEqualTo(withDefault.hashCode());
    }
}
