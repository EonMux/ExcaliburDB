package io.excalibur.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigBounds}.
 *
 * <p>The wording of a rejection is part of this class's contract, not an implementation
 * detail: the message names the property key the operator wrote, and operators - and the
 * sections that share these helpers - read it to find out which line of the file to fix.
 * These tests pin that wording alongside the bounds themselves.</p>
 */
@DisplayName("ConfigBounds")
class ConfigBoundsTest {

    private static final String KEY = "excalibur.shard.pq-oversample";

    @Nested
    @DisplayName("atLeast()")
    class AtLeast {

        @Test
        @DisplayName("falls back when the key is absent")
        void fallsBackWhenAbsent() {
            assertThat(ConfigBounds.atLeast(Map.of(), KEY, 4, 1)).isEqualTo(4);
        }

        @Test
        @DisplayName("returns a configured value that meets the bound")
        void acceptsValueAtBound() {
            assertThat(ConfigBounds.atLeast(Map.of(KEY, "1"), KEY, 4, 1)).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects a value below the bound, naming the key")
        void rejectsBelowBound() {
            assertThatThrownBy(() -> ConfigBounds.atLeast(Map.of(KEY, "0"), KEY, 4, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(KEY + " must be at least 1, got: 0");
        }

        @Test
        @DisplayName("rejects a fallback below the bound, so a bad default cannot slip through")
        void rejectsFallbackBelowBound() {
            assertThatThrownBy(() -> ConfigBounds.atLeast(Map.of(), KEY, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(KEY + " must be at least 1, got: 0");
        }
    }

    @Nested
    @DisplayName("atLeastLong()")
    class AtLeastLong {

        private static final String LONG_KEY = "excalibur.shard.compaction-interval-ms";

        @Test
        @DisplayName("falls back when the key is absent")
        void fallsBackWhenAbsent() {
            assertThat(ConfigBounds.atLeastLong(Map.of(), LONG_KEY, 5000L, 100L))
                .isEqualTo(5000L);
        }

        @Test
        @DisplayName("rejects a value below the bound, naming the key")
        void rejectsBelowBound() {
            assertThatThrownBy(
                () -> ConfigBounds.atLeastLong(Map.of(LONG_KEY, "99"), LONG_KEY, 5000L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LONG_KEY + " must be at least 100, got: 99");
        }
    }

    @Nested
    @DisplayName("inRange()")
    class InRange {

        private static final String RANGE_KEY = "excalibur.shard.lsh-probes";

        @Test
        @DisplayName("accepts both ends of the range")
        void acceptsBothEnds() {
            assertThat(ConfigBounds.inRange(Map.of(RANGE_KEY, "1"), RANGE_KEY, 3, 1, 64))
                .isEqualTo(1);
            assertThat(ConfigBounds.inRange(Map.of(RANGE_KEY, "64"), RANGE_KEY, 3, 1, 64))
                .isEqualTo(64);
        }

        @Test
        @DisplayName("rejects a value above the maximum, naming the key")
        void rejectsAboveMaximum() {
            assertThatThrownBy(
                () -> ConfigBounds.inRange(Map.of(RANGE_KEY, "65"), RANGE_KEY, 3, 1, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RANGE_KEY + " must be at most 64, got: 65");
        }

        @Test
        @DisplayName("reports the lower bound first when the value is below it")
        void rejectsBelowMinimum() {
            assertThatThrownBy(
                () -> ConfigBounds.inRange(Map.of(RANGE_KEY, "0"), RANGE_KEY, 3, 1, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RANGE_KEY + " must be at least 1, got: 0");
        }
    }

    @Nested
    @DisplayName("parseList()")
    class ParseList {

        private static final String LIST_KEY = "excalibur.graph.hot-namespaces";

        @Test
        @DisplayName("an absent key is an empty list")
        void absentIsEmpty() {
            assertThat(ConfigBounds.parseList(Map.of(), LIST_KEY)).isEmpty();
        }

        @Test
        @DisplayName("a key written out with nothing after it is an empty list, not one blank")
        void blankIsEmpty() {
            assertThat(ConfigBounds.parseList(Map.of(LIST_KEY, "   "), LIST_KEY)).isEmpty();
        }

        @Test
        @DisplayName("entries are trimmed and kept in the order given")
        void trimsAndPreservesOrder() {
            assertThat(ConfigBounds.parseList(Map.of(LIST_KEY, " beta , alpha "), LIST_KEY))
                .containsExactly("beta", "alpha");
        }

        @Test
        @DisplayName("empty entries between separators are dropped")
        void dropsEmptyEntries() {
            assertThat(ConfigBounds.parseList(Map.of(LIST_KEY, "a,,b,"), LIST_KEY))
                .containsExactly("a", "b");
        }

        @Test
        @DisplayName("the returned list is unmodifiable")
        void returnsUnmodifiableList() {
            List<String> parsed = ConfigBounds.parseList(Map.of(LIST_KEY, "a"), LIST_KEY);
            assertThatThrownBy(() -> parsed.add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
