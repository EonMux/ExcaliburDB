package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounds the second validation pass applies to the shard section.
 *
 * <p>They were written as literals at the call site while every other validator read a
 * constant, and one pair of them was declared on {@link IngestConfig} although it governs a
 * shard key. A bound that lives only as a literal is a bound nothing can be checked against,
 * which is how the copy on the wrong section went unnoticed.</p>
 */
@DisplayName("Shard bounds")
class ShardBoundsTest {

    @Test
    @DisplayName("each bound the shard validator applies is named by a constant")
    void boundsAreNamedConstants() {
        assertThat(ShardConfig.MIN_LOAD_FACTOR).isEqualTo(0.5);
        assertThat(ShardConfig.MAX_LOAD_FACTOR).isEqualTo(0.9);
        assertThat(ShardConfig.MIN_EF_CONSTRUCTION).isEqualTo(16);
        assertThat(ShardConfig.MAX_EF_CONSTRUCTION).isEqualTo(1024);
        assertThat(ShardConfig.MIN_EF_SEARCH).isEqualTo(8);
        assertThat(ShardConfig.MAX_EF_SEARCH).isEqualTo(1024);
        assertThat(ShardConfig.MIN_HNSW_QUEUE_DEPTH_LIMIT).isEqualTo(10_000);
        assertThat(ShardConfig.MAX_HNSW_QUEUE_DEPTH_LIMIT).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("the queue-depth bound is declared by the section whose key it governs")
    void queueDepthBoundBelongsToShard() {
        // excalibur.shard.hnsw-integration-queue-depth-limit is a shard key. The pair that
        // bounded it used to sit on IngestConfig, where nothing read it and nobody comparing
        // the two sections would think to look.
        assertThat(IngestConfig.class.getDeclaredFields())
            .as("no queue-depth bound may be declared on the ingest section")
            .noneMatch(field -> field.getName().contains("QUEUE_DEPTH_LIMIT"));
    }
}
