package com.eonmux.excalibur.graph.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the in-memory edge buffer grows, up to the most edges one build can hold.
 *
 * <p>Exercised on the growth rule rather than by adding two billion edges: what matters is that
 * growth near the top of the {@code int} range neither overflows into a one-slot-at-a-time crawl
 * nor asks for an array the JVM will not allocate.</p>
 */
@DisplayName("Edge buffer capacity")
class EdgeStoreWriterCapacityTest {

    @Test
    @DisplayName("grows by half again, or to what is needed if that is more")
    void growsGeometrically() {
        assertThat(EdgeStoreWriter.grownCapacity(1024, 1025)).isEqualTo(1536);
        assertThat(EdgeStoreWriter.grownCapacity(1024, 5000)).isEqualTo(5000);
    }

    @Test
    @DisplayName("growth near the top of the int range is clamped, not overflowed")
    void growthIsClamped() {
        // Half again of 1.5 billion is past Integer.MAX_VALUE. Computed in int it wraps negative
        // and the buffer would grow by one slot per edge, copying every column each time.
        int grown = EdgeStoreWriter.grownCapacity(1_500_000_000, 1_500_000_001);

        assertThat(grown).isEqualTo(EdgeStoreWriter.MAX_EDGES);
        assertThat(EdgeStoreWriter.MAX_EDGES)
            .as("no larger than the largest array every JVM allocates")
            .isLessThan(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("more than the cap is refused with the cap named")
    void beyondTheCapIsRefused() {
        assertThatThrownBy(() -> EdgeStoreWriter.grownCapacity(EdgeStoreWriter.MAX_EDGES,
                EdgeStoreWriter.MAX_EDGES + 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(EdgeStoreWriter.MAX_EDGES));
    }
}
