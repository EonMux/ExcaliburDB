package io.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The knobs that decide how a corpus is scored and how its rankings are fused.
 *
 * <p>Each of these was a constant compiled into the engine, which meant a deployment whose
 * corpus did not resemble the one the constant was chosen against had no way to say so. They
 * are validated where they are read rather than where they are used, so a value outside its
 * domain is a node that refuses to start rather than a ranking quietly computed from
 * nonsense.</p>
 */
@DisplayName("Retrieval tuning configuration")
class ConfigRetrievalTuningTest {

    private static QueryConfig query(Map<String, String> props) {
        return new QueryConfig(new HashMap<>(props));
    }

    private static IngestConfig ingest(Map<String, String> props) {
        return new IngestConfig(new HashMap<>(props));
    }

    private static GraphConfig graph(Map<String, String> props) {
        return new GraphConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("Distance metric")
    class DistanceMetric {

        @Test
        @DisplayName("a node searches by cosine unless it is told otherwise")
        void defaultsToCosine() {
            assertThat(query(Map.of()).getDistanceMetric()).isEqualTo("cosine");
        }

        @Test
        @DisplayName("the three metrics the index can be built with are accepted")
        void acceptsEveryImplementedMetric() {
            for (String metric : QueryConfig.METRICS) {
                assertThat(query(Map.of("excalibur.query.distance-metric", metric))
                    .getDistanceMetric()).isEqualTo(metric);
            }
        }

        @Test
        @DisplayName("case and surrounding space do not change which metric is meant")
        void normalisesTheName() {
            assertThat(query(Map.of("excalibur.query.distance-metric", "  L2 "))
                .getDistanceMetric()).isEqualTo(QueryConfig.METRIC_L2);
        }

        @Test
        @DisplayName("an unimplemented metric refuses to start rather than falling back")
        void refusesAnUnknownMetric() {
            // Falling back would answer every query, and rank every answer by a measure the
            // operator did not choose - a wrong ranking is not visible the way a failed start is.
            assertThatThrownBy(() ->
                query(Map.of("excalibur.query.distance-metric", "manhattan")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distance-metric")
                .hasMessageContaining("manhattan")
                .hasMessageContaining("cosine");
        }
    }

    @Nested
    @DisplayName("Fusion")
    class Fusion {

        @Test
        @DisplayName("fusion uses the published rank offset and over-fetches three deep")
        void documentedDefaults() {
            QueryConfig settings = query(Map.of());

            assertThat(settings.getRrfK()).isEqualTo(60);
            assertThat(settings.getPerSignalOverFetch()).isEqualTo(3);
        }

        @Test
        @DisplayName("a rank offset below one is refused")
        void rrfKMustBePositive() {
            assertThatThrownBy(() -> query(Map.of("excalibur.query.rrf-k", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rrf-k");
        }

        @Test
        @DisplayName("a signal must contribute at least as many candidates as are asked for")
        void overFetchMustBePositive() {
            assertThatThrownBy(() ->
                query(Map.of("excalibur.query.per-signal-over-fetch", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-signal-over-fetch");
        }
    }

    @Nested
    @DisplayName("BM25")
    class Bm25 {

        @Test
        @DisplayName("the defaults are the ones the method is usually published with")
        void documentedDefaults() {
            IngestConfig settings = ingest(Map.of());

            assertThat(settings.getBm25K1()).isEqualTo(1.2);
            assertThat(settings.getBm25B()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("saturation may be switched off but not made negative")
        void k1MustBeNonNegative() {
            assertThat(ingest(Map.of("excalibur.ingest.bm25-k1", "0")).getBm25K1()).isZero();
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.bm25-k1", "-0.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bm25-k1");
        }

        @Test
        @DisplayName("length normalisation is a fraction, and anything else is refused")
        void bIsAFraction() {
            assertThat(ingest(Map.of("excalibur.ingest.bm25-b", "0")).getBm25B()).isZero();
            assertThat(ingest(Map.of("excalibur.ingest.bm25-b", "1")).getBm25B()).isEqualTo(1.0);
            assertThatThrownBy(() -> ingest(Map.of("excalibur.ingest.bm25-b", "1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bm25-b");
        }
    }

    @Nested
    @DisplayName("Graph reranking")
    class GraphReranking {

        @Test
        @DisplayName("the reranker reads four candidates per result by default")
        void documentedDefault() {
            assertThat(graph(Map.of()).getRerankOverFetch()).isEqualTo(4);
        }

        @Test
        @DisplayName("a reranker that reads nothing is refused")
        void mustBePositive() {
            assertThatThrownBy(() -> graph(Map.of("excalibur.graph.rerank-over-fetch", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rerank-over-fetch");
        }
    }
}
