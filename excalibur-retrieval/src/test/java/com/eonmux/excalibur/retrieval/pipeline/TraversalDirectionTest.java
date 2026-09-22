package com.eonmux.excalibur.retrieval.pipeline;

import com.eonmux.excalibur.graph.store.RelationSelector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraversalDirectionTest {

    @Test
    @DisplayName("each direction reads back from its wire name, whatever the case and padding")
    void parsesWireNames() {
        for (TraversalDirection direction : TraversalDirection.values()) {
            assertThat(TraversalDirection.parse(direction.wireName())).isEqualTo(direction);
        }
        assertThat(TraversalDirection.parse("  Reverse ")).isEqualTo(TraversalDirection.REVERSE);
        assertThat(TraversalDirection.FORWARD.wireName()).isEqualTo("forward");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "outgoing", "in", "forwards", "both ways" })
    @DisplayName("anything but the three names is refused, naming what was sent")
    void refusesOtherValues(String sent) {
        assertThatThrownBy(() -> TraversalDirection.parse(sent))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'direction' must be")
            .hasMessageContaining("got: " + sent);
    }

    @Test
    @DisplayName("a missing direction is refused rather than defaulted here")
    void refusesNull() {
        assertThatThrownBy(() -> TraversalDirection.parse(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each direction becomes the selectors the store follows")
    void selectorsFollowTheDirection() {
        assertThat(TraversalDirection.FORWARD.selectorsFor(3))
            .containsExactly(RelationSelector.forward(3));
        assertThat(TraversalDirection.REVERSE.selectorsFor(3))
            .containsExactly(RelationSelector.reverse(3));
        assertThat(TraversalDirection.BOTH.selectorsFor(3))
            .containsExactly(RelationSelector.both(3));
    }
}
