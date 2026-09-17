package excalibur.shard;

import io.excalibur.config.Config;
import io.excalibur.config.ShardConfig;
import io.excalibur.store.CompactionWorker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Configuration the workers will refuse is refused here, against the key that carried it.
 *
 * <p>The configuration layer range-checks what it can tell about a value from its name - that
 * an interval is positive, that a ratio is a fraction - and leaves the rest to whichever worker
 * enforces it. Everything it leaves is checked in {@link CompactionSettings#fromConfig}, because
 * the alternative is a deployment that starts, echoes the setting back in its startup log and
 * then aborts several frames down inside a constructor whose message names no property at all.
 * An operator reading that log has no way to know which of the six keys to change.</p>
 */
@DisplayName("Compaction settings from configuration")
class CompactionSettingsTest {

    private static ShardConfig shardWith(Map<String, String> overrides) {
        return new Config(Map.copyOf(overrides)).shard();
    }

    @Test
    @DisplayName("a concurrency above the worker's ceiling is refused by property name")
    void aConcurrencyAboveTheCeilingIsRefusedByName() {
        ShardConfig shard = shardWith(Map.of("excalibur.shard.compaction-max-concurrent", "32"));

        assertThatThrownBy(() -> CompactionSettings.fromConfig(shard))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excalibur.shard.compaction-max-concurrent")
            .hasMessageContaining(String.valueOf(CompactionWorker.MAX_CONCURRENT_COMPACTIONS))
            .hasMessageContaining("32");
    }

    @Test
    @DisplayName("the ceiling itself is a usable setting")
    void theCeilingItselfIsAccepted() {
        ShardConfig shard = shardWith(Map.of("excalibur.shard.compaction-max-concurrent",
            String.valueOf(CompactionWorker.MAX_CONCURRENT_COMPACTIONS)));

        CompactionSettings settings = CompactionSettings.fromConfig(shard);

        assertThat(settings.maxConcurrentSegmentCompactions())
            .isEqualTo(CompactionWorker.MAX_CONCURRENT_COMPACTIONS);
        assertThatCode(() -> new CompactionWorker(java.lang.foreign.Arena.ofAuto(),
            (oldSegment, newSegment) -> io.excalibur.store.CompactionOutcome.INSTALLED,
            settings.segmentIntervalMs(), settings.segmentTombstoneRatio(),
            settings.segmentWriteLogBytes(), settings.maxConcurrentSegmentCompactions()))
            .as("every setting fromConfig accepts is one the worker accepts")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a write-log threshold the log can never reach is refused by property name")
    void aWriteLogThresholdBeyondTheLogIsRefusedByName() {
        // 64 MiB is the write log's whole capacity. A trigger there never fires, the log fills,
        // and the segment refuses writes for good - so it has to be refused at configuration
        // time rather than accepted and silently disabled.
        ShardConfig shard = shardWith(Map.of("excalibur.shard.compaction-write-log-mb", "64"));

        assertThatThrownBy(() -> CompactionSettings.fromConfig(shard))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excalibur.shard.compaction-write-log-mb")
            .hasMessageContaining("64");
    }

    @Test
    @DisplayName("the write-log ceiling is one the worker accepts")
    void theWriteLogCeilingIsAccepted() {
        long ceilingMb = CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD / (1024L * 1024L);
        ShardConfig shard = shardWith(Map.of("excalibur.shard.compaction-write-log-mb",
            String.valueOf(ceilingMb)));

        CompactionSettings settings = CompactionSettings.fromConfig(shard);

        assertThat(settings.segmentWriteLogBytes())
            .isEqualTo(CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD);
        assertThatCode(() -> new CompactionWorker(java.lang.foreign.Arena.ofAuto(),
            (oldSegment, newSegment) -> io.excalibur.store.CompactionOutcome.INSTALLED,
            settings.segmentIntervalMs(), settings.segmentTombstoneRatio(),
            settings.segmentWriteLogBytes(), settings.maxConcurrentSegmentCompactions()))
            .as("every setting fromConfig accepts is one the worker accepts")
            .doesNotThrowAnyException();
    }
}
