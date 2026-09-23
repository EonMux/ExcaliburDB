package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code excalibur.shard.max-routing-width}: how many slots the routing directory may grow to.
 */
@DisplayName("excalibur.shard.max-routing-width")
class ConfigShardRoutingWidthTest {

    private static final String KEY = "excalibur.shard.max-routing-width";

    @Test
    @DisplayName("defaults to 2^20 slots")
    void defaultsToTheEngineDefault() {
        assertThat(new Config(Map.of()).shard().getMaxRoutingWidth())
            .isEqualTo(ShardConfig.DEFAULT_MAX_ROUTING_WIDTH)
            .isEqualTo(1 << 20);
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 4096, 1 << 30})
    @DisplayName("accepts a power of two from the base width to 2^30")
    void acceptsAPowerOfTwoInRange(int width) {
        assertThat(new Config(Map.of(KEY, Integer.toString(width))).shard().getMaxRoutingWidth())
            .isEqualTo(width);
    }

    @ParameterizedTest
    @ValueSource(strings = {"32", "0", "-64", "100", "3000000"})
    @DisplayName("refuses a width that is not a power of two in range, naming the key")
    void refusesAnythingElse(String width) {
        assertThatThrownBy(() -> new Config(Map.of(KEY, width)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(KEY);
    }

    @Test
    @DisplayName("takes part in equality")
    void takesPartInEquality() {
        assertThat(new Config(Map.of(KEY, "4096")).shard())
            .isNotEqualTo(new Config(Map.of()).shard());
    }
}
