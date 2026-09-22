package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configured volume list is what the storage engine actually writes across.
 *
 * <p>{@code excalibur.shard.volumes} was parsed and validated and then read by nobody. The
 * consistent hash ring that answers "which volume does this segment belong on" had no caller,
 * the volume manager was handed to the orchestrator and never consulted, and the storage engine
 * resolved every segment file into the data directory regardless. A deployment that named four
 * disks filled one.</p>
 *
 * <p>The part to be careful with is not the spreading, it is the finding: a volume that is
 * configured but absent must stop the node rather than be created empty underneath it.</p>
 */
@DisplayName("Volume placement from configuration")
class IndexOrchestratorVolumePlacementTest {

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

    private Path dataDir() throws IOException {
        return Files.createDirectories(tempDir.resolve("data"));
    }

    private static Config configWithVolumes(String volumes) {
        Map<String, String> props = new HashMap<>();
        if (volumes != null) {
            props.put("excalibur.shard.volumes", volumes);
        }
        return new Config(props);
    }

    private IndexOrchestrator buildUnstarted(Path dataDir, Config config, VolumeManager manager) {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(dataDir, arena);
        orchestrator = IndexOrchestrator.fromConfig(
            storageEngine.getCatalog(), storageEngine, manager, config.shard());
        return orchestrator;
    }

    private static long segmentFilesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches("segment_\\d+\\.dat"))
                .count();
        }
    }

    @Test
    @DisplayName("with no volumes configured every segment stays in the data directory")
    void theDefaultIsUnchanged() throws IOException {
        Path dataDir = dataDir();
        VolumeManager manager = new VolumeManager();

        buildUnstarted(dataDir, configWithVolumes(null), manager);
        storageEngine.start();

        assertThat(storageEngine.segmentPlacement().directories()).containsExactly(dataDir);
        assertThat(segmentFilesIn(dataDir))
            .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS);
    }

    @Test
    @DisplayName("the data directory is registered first, then each configured volume")
    void volumesAreRegisteredInOrder() throws IOException {
        Path dataDir = dataDir();
        Path volumeB = Files.createDirectories(tempDir.resolve("vol-b"));
        Path volumeC = Files.createDirectories(tempDir.resolve("vol-c"));
        VolumeManager manager = new VolumeManager();

        buildUnstarted(dataDir, configWithVolumes(volumeB + "," + volumeC), manager);

        assertThat(storageEngine.segmentPlacement().directories())
            .containsExactly(dataDir, volumeB, volumeC);
        assertThat(manager.getVolumeCount()).isEqualTo(3);
        assertThat(manager.hasVolume(dataDir)).isTrue();
        assertThat(manager.hasVolume(volumeB)).isTrue();
        assertThat(manager.hasVolume(volumeC)).isTrue();
    }

    @Test
    @DisplayName("segments are spread over the data directory and the configured volumes")
    void segmentsAreSpread() throws IOException {
        Path dataDir = dataDir();
        Path volumeB = Files.createDirectories(tempDir.resolve("vol-b"));
        VolumeManager manager = new VolumeManager();

        buildUnstarted(dataDir, configWithVolumes(volumeB.toString()), manager);
        storageEngine.start();

        assertThat(segmentFilesIn(dataDir)).isPositive();
        assertThat(segmentFilesIn(volumeB)).isPositive();
        assertThat(segmentFilesIn(dataDir) + segmentFilesIn(volumeB))
            .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS);
        assertThat(manager.getTotalSegmentCount()).isPositive();
    }

    @Test
    @DisplayName("a configured volume that is not there is refused, not created")
    void anAbsentVolumeIsRefused() throws IOException {
        Path dataDir = dataDir();
        Path absent = tempDir.resolve("never-mounted");
        VolumeManager manager = new VolumeManager();

        arena = Arena.ofShared();
        storageEngine = new StorageEngine(dataDir, arena);

        assertThatThrownBy(() -> IndexOrchestrator.fromConfig(
            storageEngine.getCatalog(), storageEngine, manager,
            configWithVolumes(absent.toString()).shard()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(absent.toString());

        assertThat(Files.exists(absent)).isFalse();
    }

    @Test
    @DisplayName("volumes configured with no volume manager to place them is refused")
    void volumesWithoutAManagerAreRefused() throws IOException {
        Path dataDir = dataDir();
        Path volumeB = Files.createDirectories(tempDir.resolve("vol-b"));

        arena = Arena.ofShared();
        storageEngine = new StorageEngine(dataDir, arena);

        assertThatThrownBy(() -> IndexOrchestrator.fromConfig(
            storageEngine.getCatalog(), storageEngine, null,
            configWithVolumes(volumeB.toString()).shard()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("volume");
    }

    @Test
    @DisplayName("a restart over the same volumes finds every segment again")
    void restartRediscoversEverySegment() throws IOException {
        Path dataDir = dataDir();
        Path volumeB = Files.createDirectories(tempDir.resolve("vol-b"));
        Config config = configWithVolumes(volumeB.toString());

        buildUnstarted(dataDir, config, new VolumeManager());
        storageEngine.start();
        storageEngine.put("volume-spanning-key".getBytes(), "value".getBytes());
        long onVolumeB = segmentFilesIn(volumeB);
        tearDown();
        orchestrator = null;
        storageEngine = null;
        arena = null;

        buildUnstarted(dataDir, config, new VolumeManager());
        storageEngine.start();

        assertThat(segmentFilesIn(volumeB)).isEqualTo(onVolumeB);
        assertThat(storageEngine.getSegmentCount())
            .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS);
        assertThat(storageEngine.get("volume-spanning-key".getBytes())).isNotNull();
    }
}
