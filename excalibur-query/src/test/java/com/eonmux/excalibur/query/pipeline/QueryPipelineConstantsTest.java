package com.eonmux.excalibur.query.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight unit tests for QueryPipeline constants.
 *
 * <p>Separated from QueryPipelineTest to avoid Mockito initialization overhead
 * for simple constant verification.</p>
 */
@DisplayName("QueryPipeline constants")
class QueryPipelineConstantsTest {

    @Test
    @DisplayName("DEFAULT_COVERAGE_THRESHOLD is 0.5")
    void defaultCoverageThreshold() {
        assertThat(QueryPipeline.DEFAULT_COVERAGE_THRESHOLD).isEqualTo(0.5);
    }

    @Test
    @DisplayName("DEFAULT_LLM_THRESHOLD is 0.3")
    void defaultLlmThreshold() {
        assertThat(QueryPipeline.DEFAULT_LLM_THRESHOLD).isEqualTo(0.3f);
    }
}
