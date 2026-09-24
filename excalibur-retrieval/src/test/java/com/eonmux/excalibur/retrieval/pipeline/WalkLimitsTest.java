package com.eonmux.excalibur.retrieval.pipeline;

import java.util.HashMap;
import java.util.Map;

import com.eonmux.excalibur.config.GraphConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalkLimitsTest {

    @Test
    @DisplayName("the defaults are the configuration's defaults")
    void defaultsMatchTheConfiguration() {
        assertThat(WalkLimits.defaults()).isEqualTo(new WalkLimits(
            GraphConfig.DEFAULT_MAX_FRONTIER, GraphConfig.DEFAULT_MAX_WALK_FACTS));
    }

    @Test
    @DisplayName("configured limits are read from the graph section")
    void readsTheGraphSection() {
        WalkLimits limits = WalkLimits.of(new GraphConfig(new HashMap<>(Map.of(
            "excalibur.graph.max-frontier", "12",
            "excalibur.graph.max-walk-facts", "34"))));

        assertThat(limits).isEqualTo(new WalkLimits(12, 34));
    }

    @Test
    @DisplayName("a limit below one is refused")
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new WalkLimits(0, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxFrontier");
        assertThatThrownBy(() -> new WalkLimits(1, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxFacts");
    }

    @Test
    @DisplayName("a truncation says whether anything was left out, and never a negative count")
    void truncation() {
        assertThat(WalkTruncation.NONE.truncated()).isFalse();
        assertThat(new WalkTruncation(1, 0).truncated()).isTrue();
        assertThat(new WalkTruncation(0, 1).truncated()).isTrue();
        assertThatThrownBy(() -> new WalkTruncation(-1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WalkTruncation(0, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
