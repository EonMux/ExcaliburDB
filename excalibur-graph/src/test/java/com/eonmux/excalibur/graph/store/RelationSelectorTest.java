package com.eonmux.excalibur.graph.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationSelectorTest {

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 12_345, Integer.MAX_VALUE - 1, Integer.MAX_VALUE })
    @DisplayName("every non-negative relation round-trips in both directions")
    void roundTrip(int relation) {
        int forward = RelationSelector.forward(relation);
        int reverse = RelationSelector.reverse(relation);

        assertThat(RelationSelector.isReverse(forward)).isFalse();
        assertThat(RelationSelector.isReverse(reverse)).isTrue();
        assertThat(RelationSelector.relationOf(forward)).isEqualTo(relation);
        assertThat(RelationSelector.relationOf(reverse)).isEqualTo(relation);
    }

    @Test
    @DisplayName("relation 0 encodes distinctly in each direction")
    void zeroIsNotAmbiguous() {
        // The reason the encoding is ~r rather than -r: negation maps 0 to 0, so a reverse
        // traversal of relation 0 would be indistinguishable from a forward one.
        assertThat(RelationSelector.forward(0)).isNotEqualTo(RelationSelector.reverse(0));
        assertThat(RelationSelector.isReverse(RelationSelector.reverse(0))).isTrue();
    }

    @Test
    @DisplayName("MAX_VALUE encodes without overflow")
    void maxValueEncodes() {
        // -r would be representable here but MIN_VALUE has no positive counterpart, so ~r is what
        // keeps the encoding total over the whole non-negative range.
        int reverse = RelationSelector.reverse(Integer.MAX_VALUE);
        assertThat(reverse).isEqualTo(Integer.MIN_VALUE);
        assertThat(RelationSelector.relationOf(reverse)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("a negative relation id is refused")
    void negativeRelationRefused() {
        assertThatThrownBy(() -> RelationSelector.forward(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> RelationSelector.reverse(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("both() covers a relation in each direction")
    void both() {
        int[] selectors = RelationSelector.both(7);
        assertThat(selectors).hasSize(2);
        assertThat(RelationSelector.relationOf(selectors[0])).isEqualTo(7);
        assertThat(RelationSelector.relationOf(selectors[1])).isEqualTo(7);
        assertThat(RelationSelector.isReverse(selectors[0])).isFalse();
        assertThat(RelationSelector.isReverse(selectors[1])).isTrue();
    }

    @Test
    @DisplayName("toString shows the direction")
    void readableForm() {
        assertThat(RelationSelector.toString(RelationSelector.forward(5))).isEqualTo("->5");
        assertThat(RelationSelector.toString(RelationSelector.reverse(5))).isEqualTo("<-5");
    }
}
