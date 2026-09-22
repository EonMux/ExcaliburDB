package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compaction policy an operator writes down is the policy the workers run.
 *
 * <p>Six keys describe when compaction happens - how often the loop wakes, how much dead weight
 * a segment needs before it is rewritten, how large a write log has to grow to force the issue,
 * how many rewrites may run at once, and the same interval and ratio for the HNSW graphs. All
 * six were parsed and validated at startup and then ignored, because both workers were built
 * from compile-time constants. A deployment that wanted compaction to back off got the stock
 * schedule and no indication that its settings had gone nowhere.</p>
 */
@DisplayName("Compaction policy from configuration")
class IndexOrchestratorCompactionConfigTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    private Config configWith(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>(overrides);
        return new Config(props);
    }

    private IndexOrchestrator orchestratorFrom(Config config) {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        orchestrator = IndexOrchestrator.fromConfig(
            storageEngine.getCatalog(), storageEngine, null, config.shard());
        return orchestrator;
    }

    @Test
    @DisplayName("every configured threshold reaches the segment compaction worker")
    void segmentCompactionThresholdsReachTheWorker() {
        Config config = configWith(Map.of(
            "excalibur.shard.compaction-interval-ms", "777",
            "excalibur.shard.compaction-tombstone-ratio", "0.45",
            "excalibur.shard.compaction-write-log-mb", "7",
            "excalibur.shard.compaction-max-concurrent", "2"));

        CompactionWorker worker = orchestratorFrom(config).getCompactionWorker();

        assertThat(worker).isNotNull();
        assertThat(worker.getCompactionIntervalMs()).isEqualTo(777L);
        assertThat(worker.getTombstoneRatioThreshold()).isEqualTo(0.45);
        assertThat(worker.getWriteLogSizeThreshold()).isEqualTo(7L * 1024 * 1024);
        assertThat(worker.getMaxConcurrentCompactions()).isEqualTo(2);
    }

    @Test
    @DisplayName("the configured ratio decides which segments the worker would pick")
    void theConfiguredRatioChangesWhatIsPicked() throws IOException {
        Config config = configWith(Map.of(
            "excalibur.shard.compaction-tombstone-ratio", "0.45"));

        CompactionWorker worker = orchestratorFrom(config).getCompactionWorker();

        Segment below = segmentWithTombstones("below.dat", 3);
        Segment above = segmentWithTombstones("above.dat", 5);

        assertThat(worker.isCompactionWorthwhile(below)).isFalse();
        assertThat(worker.isCompactionWorthwhile(above)).isTrue();
    }

    @Test
    @DisplayName("the configured graph interval and ratio reach the HNSW compaction worker")
    void graphCompactionSettingsReachTheWorker() {
        Config config = configWith(Map.of(
            "excalibur.shard.hnsw-compaction-interval-ms", "120000",
            "excalibur.shard.hnsw-compaction-tombstone-ratio", "0.35"));

        var worker = orchestratorFrom(config).getHnswCompactionWorker();

        assertThat(worker.getIntervalMs()).isEqualTo(120_000L);
        assertThat(worker.getTombstoneThreshold()).isEqualTo(0.35);
    }

    @Test
    @DisplayName("an unconfigured deployment keeps the settings it has always had")
    void defaultsAreUnchanged() {
        IndexOrchestrator built = orchestratorFrom(configWith(Map.of()));

        CompactionWorker worker = built.getCompactionWorker();
        assertThat(worker.getCompactionIntervalMs())
            .isEqualTo(CompactionWorker.DEFAULT_COMPACTION_INTERVAL_MS);
        assertThat(worker.getTombstoneRatioThreshold())
            .isEqualTo(CompactionWorker.DEFAULT_TOMBSTONE_RATIO_THRESHOLD);
        assertThat(worker.getWriteLogSizeThreshold())
            .isEqualTo(CompactionWorker.DEFAULT_WRITE_LOG_SIZE_THRESHOLD);
        assertThat(worker.getMaxConcurrentCompactions())
            .isEqualTo(CompactionWorker.DEFAULT_MAX_CONCURRENT_COMPACTIONS);
        assertThat(built.getHnswCompactionWorker().getIntervalMs())
            .isEqualTo(com.eonmux.excalibur.shard.worker.HnswCompactionWorker.DEFAULT_INTERVAL_MS);
        assertThat(built.getHnswCompactionWorker().getTombstoneThreshold())
            .isEqualTo(com.eonmux.excalibur.shard.worker.HnswCompactionWorker.DEFAULT_TOMBSTONE_THRESHOLD);
    }

    /**
     * Builds a stand-alone segment holding ten entries, of which some are deleted.
     *
     * @param name       File name for the segment
     * @param tombstones How many of the ten entries to delete
     * @return The segment, left open
     * @throws IOException if the segment cannot be created
     */
    private Segment segmentWithTombstones(String name, int tombstones) throws IOException {
        Segment segment = new Segment(tempDir.resolve(name), 1024 * 1024, arena);
        for (int i = 0; i < 10; i++) {
            segment.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
        }
        for (int i = 0; i < tombstones; i++) {
            segment.delete(("key-" + i).getBytes());
        }
        return segment;
    }
}
