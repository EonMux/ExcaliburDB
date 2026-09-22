package com.eonmux.excalibur.vector.distance;

import com.eonmux.excalibur.config.QueryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the name-to-implementation registry that turns a configured metric
 * name into the distance function the index will actually use.
 */
@DisplayName("DistanceFunctions registry")
class DistanceFunctionsTest {

    @Test
    @DisplayName("Each canonical name resolves to its singleton")
    void resolvesCanonicalNames() {
        assertThat(DistanceFunctions.byName("cosine")).isSameAs(CosineSimilarity.getInstance());
        assertThat(DistanceFunctions.byName("l2")).isSameAs(L2Distance.getInstance());
        assertThat(DistanceFunctions.byName("inner_product")).isSameAs(InnerProduct.getInstance());
    }

    @Test
    @DisplayName("Lookup ignores surrounding whitespace and case")
    void lookupIsLenientAboutSpellingNoise() {
        assertThat(DistanceFunctions.byName("  COSINE ")).isSameAs(CosineSimilarity.getInstance());
        assertThat(DistanceFunctions.byName("L2")).isSameAs(L2Distance.getInstance());
        assertThat(DistanceFunctions.byName("Inner_Product")).isSameAs(InnerProduct.getInstance());
    }

    @Test
    @DisplayName("An unknown name is rejected with the accepted values in the message")
    void rejectsUnknownName() {
        assertThatThrownBy(() -> DistanceFunctions.byName("euclidean"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("euclidean")
            .hasMessageContaining("cosine")
            .hasMessageContaining("l2")
            .hasMessageContaining("inner_product");
    }

    @Test
    @DisplayName("A blank or absent name is rejected the same way")
    void rejectsBlankAndNullNames() {
        assertThatThrownBy(() -> DistanceFunctions.byName("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cosine");
        assertThatThrownBy(() -> DistanceFunctions.byName(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cosine");
    }

    @Test
    @DisplayName("names() lists exactly the resolvable metrics and cannot be modified")
    void namesListsTheResolvableMetrics() {
        assertThat(DistanceFunctions.names())
            .containsExactlyInAnyOrder("cosine", "l2", "inner_product");

        assertThatThrownBy(() -> DistanceFunctions.names().add("euclidean"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Every listed name resolves to a function that reports that same name")
    void namesRoundTripThroughByName() {
        for (String name : DistanceFunctions.names()) {
            assertThat(DistanceFunctions.byName(name).name()).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("Every metric the configuration accepts resolves here")
    void coversTheConfiguredMetricVocabulary() {
        Set<String> configured = Set.of(
            QueryConfig.METRIC_COSINE,
            QueryConfig.METRIC_L2,
            QueryConfig.METRIC_INNER_PRODUCT);

        assertThat(DistanceFunctions.names()).containsAll(configured);
        for (String name : configured) {
            assertThat(DistanceFunctions.byName(name)).isNotNull();
        }
    }
}
