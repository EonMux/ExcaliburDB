package io.excalibur.vector.layout;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunctions;
import io.excalibur.vector.distance.InnerProduct;
import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the batch-operation metric enum and the distance function registry
 * describe the same three metrics, so the two vocabularies cannot drift apart.
 */
@DisplayName("DistanceType to DistanceFunction")
class DistanceTypeTest {

    @Test
    @DisplayName("Each constant maps to the matching distance function singleton")
    void mapsEachConstantToItsSingleton() {
        assertThat(DistanceType.COSINE.toDistanceFunction())
            .isSameAs(CosineSimilarity.getInstance());
        assertThat(DistanceType.L2.toDistanceFunction())
            .isSameAs(L2Distance.getInstance());
        assertThat(DistanceType.INNER_PRODUCT.toDistanceFunction())
            .isSameAs(InnerProduct.getInstance());
    }

    @Test
    @DisplayName("Every constant maps onto a name the registry knows")
    void coversTheRegistryVocabulary() {
        for (DistanceType type : DistanceType.values()) {
            String name = type.toDistanceFunction().name();
            assertThat(DistanceFunctions.names()).contains(name);
            assertThat(DistanceFunctions.byName(name)).isSameAs(type.toDistanceFunction());
        }
    }
}
