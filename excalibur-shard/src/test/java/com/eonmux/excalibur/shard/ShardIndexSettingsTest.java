package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ShardConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every key the orchestrator reads has to arrive somewhere that uses it.
 *
 * <p>These assertions exist because the failure they guard against is silent. A key that is
 * parsed, range-checked and echoed back at startup looks delivered from the outside; the only
 * way to tell it was dropped on the floor is to check the value that came out the other end.
 * {@code compaction-delay-ms} spent several releases in exactly that state.</p>
 */
@DisplayName("Shard index settings from configuration")
class ShardIndexSettingsTest {

    private static ShardConfig shardWith(Map<String, String> overrides) {
        return new Config(new HashMap<>(overrides)).shard();
    }

    @Test
    @DisplayName("every configured key reaches the settings it is read for")
    void configuredKeysReachTheSettings() {
        ShardIndexSettings settings = ShardIndexSettings.fromConfig(shardWith(Map.of(
            "excalibur.shard.hnsw-integration-queue-depth-limit", "4242",
            "excalibur.shard.compaction-delay-ms", "7000",
            "excalibur.shard.segment-sync-interval-ms", "5000",
            "excalibur.shard.volumes", "/data/one, /data/two",
            "excalibur.shard.compaction-interval-ms", "45000",
            "excalibur.shard.pq-subspaces", "24")));

        assertThat(settings.hnswQueueLimit()).isEqualTo(4242);
        assertThat(settings.compactionStartDelayMs()).isEqualTo(7000L);
        assertThat(settings.segmentSyncIntervalMs()).isEqualTo(5000L);
        assertThat(settings.volumes()).containsExactly("/data/one", "/data/two");
        assertThat(settings.compaction().segmentIntervalMs()).isEqualTo(45000L);
        assertThat(settings.pqTraining().numSubspaces()).isEqualTo(24);
    }

    @Test
    @DisplayName("an unconfigured shard yields the defaults both workers already apply")
    void defaultsMatchTheStockPolicy() {
        ShardIndexSettings settings = ShardIndexSettings.fromConfig(shardWith(Map.of()));

        assertThat(settings.compaction()).isEqualTo(CompactionSettings.DEFAULTS);
        assertThat(settings.volumes()).isEmpty();
        assertThat(settings.compactionStartDelayMs()).isNotNegative();

        // The configuration layer and the orchestrator each carry their own default for this,
        // in modules that cannot see one another. They have to agree, or the sweep period
        // silently depends on which construction path a deployment took.
        assertThat(settings.segmentSyncIntervalMs())
            .isEqualTo(IndexOrchestrator.DEFAULT_SEGMENT_SYNC_INTERVAL_MS);
    }

    @Test
    @DisplayName("a value a worker would refuse is reported against the key that carried it")
    void outOfRangeValuesAreReportedAgainstTheirKey() {
        assertThatThrownBy(() -> ShardIndexSettings.fromConfig(shardWith(Map.of(
            "excalibur.shard.hnsw-compaction-interval-ms", "500"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excalibur.shard.hnsw-compaction-interval-ms");
    }

    @Test
    @DisplayName("a sweep period below the sweep's own floor names the key that carried it")
    void aSweepPeriodBelowTheFloorIsRefused() {
        // The configuration layer only range-checks the shape; this floor is the sweep's, and
        // an operator reading the failure needs the property name, not a scheduler's complaint.
        assertThatThrownBy(() -> ShardIndexSettings.fromConfig(shardWith(Map.of(
            "excalibur.shard.segment-sync-interval-ms",
            String.valueOf(SegmentSyncScheduler.MIN_INTERVAL_MS - 1)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excalibur.shard.segment-sync-interval-ms");
    }

    @Test
    @DisplayName("a null shard section is refused rather than defaulted")
    void nullShardIsRefused() {
        assertThatThrownBy(() -> ShardIndexSettings.fromConfig(null))
            .isInstanceOf(NullPointerException.class);
    }
}
