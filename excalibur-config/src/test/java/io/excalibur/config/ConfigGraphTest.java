package io.excalibur.config;

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
@DisplayName("Config.Graph")
class ConfigGraphTest {

    private static Config.Graph graph(Map<String, String> props) {
        return new Config.Graph(new HashMap<>(props));
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("an empty configuration produces the documented defaults")
        void documentedDefaults() {
            Config.Graph settings = graph(Map.of());

            assertThat(settings.getPath()).isNull();
            assertThat(settings.getTopK()).isEqualTo(Config.Graph.DEFAULT_TOP_K);
            assertThat(settings.getContextTokenBudget())
                .isEqualTo(Config.Graph.DEFAULT_CONTEXT_TOKEN_BUDGET);
            assertThat(settings.getMinScore()).isEqualTo(Config.Graph.DEFAULT_MIN_SCORE);
            assertThat(settings.getMinSeparation())
                .isEqualTo(Config.Graph.DEFAULT_MIN_SEPARATION);
            assertThat(settings.getMinMeanConfidence())
                .isEqualTo(Config.Graph.DEFAULT_MIN_MEAN_CONFIDENCE);
            assertThat(settings.getMaxAmbiguousCandidates())
                .isEqualTo(Config.Graph.DEFAULT_MAX_AMBIGUOUS_CANDIDATES);
            assertThat(settings.getRamBufferMb()).isEqualTo(Config.Graph.DEFAULT_RAM_BUFFER_MB);
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
            Config.Graph settings = graph(Map.of(
                "excalibur.graph.path", "/srv/graph",
                "excalibur.graph.top-k", "42",
                "excalibur.graph.context-token-budget", "8000",
                "excalibur.graph.min-score", "0.25",
                "excalibur.graph.min-separation", "0.1",
                "excalibur.graph.min-mean-confidence", "0.75",
                "excalibur.graph.max-ambiguous-candidates", "3",
                "excalibur.graph.ram-buffer-mb", "64"));

            assertThat(settings.getPath()).isEqualTo("/srv/graph");
            assertThat(settings.getTopK()).isEqualTo(42);
            assertThat(settings.getContextTokenBudget()).isEqualTo(8000);
            assertThat(settings.getMinScore()).isEqualTo(0.25);
            assertThat(settings.getMinSeparation()).isEqualTo(0.1);
            assertThat(settings.getMinMeanConfidence()).isEqualTo(0.75);
            assertThat(settings.getMaxAmbiguousCandidates()).isEqualTo(3);
            assertThat(settings.getRamBufferMb()).isEqualTo(64.0);
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
        @DisplayName("toString names the directory without pretending one was configured")
        void toStringIsHonestAboutTheDefault() {
            assertThat(graph(Map.of()).toString()).contains("<data dir>/graph");
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
