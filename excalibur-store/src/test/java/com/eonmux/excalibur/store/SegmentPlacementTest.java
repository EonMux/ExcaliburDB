package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which directory a segment's files are written to, and finding them all again.
 *
 * <p>Spreading segments over several volumes is only half of the feature. The other half is
 * that a restart looks everywhere the segments might be: a volume that is not scanned is a
 * volume whose segments the engine will conclude never existed, and it will then create empty
 * files over their indices. That failure is silent and total, so the engine refuses to start
 * when the catalog names segments no scanned directory produced.</p>
 */
@DisplayName("Segment placement across volumes")
class SegmentPlacementTest {

    @TempDir
    Path tempDir;

    private Path dataDir() throws IOException {
        return Files.createDirectories(tempDir.resolve("data"));
    }

    private Path volume(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    /**
     * Places even indices in the first directory and odd ones in the second.
     *
     * @param primary The data directory
     * @param other   The extra volume
     * @return A placement spreading segments over both
     */
    private static SegmentPlacement alternating(Path primary, Path other) {
        return SegmentPlacement.across(List.of(primary, other),
            index -> index % 2 == 0 ? primary : other);
    }

    private static long segmentFilesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches("segment_\\d+\\.dat"))
                .count();
        }
    }

    @Test
    @DisplayName("an engine told nothing keeps every file in the data directory")
    void theDefaultIsTheDataDirectory() throws IOException {
        Path dataDir = dataDir();
        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena);
            try {
                assertThat(engine.segmentPath(7))
                    .isEqualTo(dataDir.resolve("segment_7.dat"));
                assertThat(engine.writeLogPath(7))
                    .isEqualTo(dataDir.resolve("segment_7.wal"));
                assertThat(engine.segmentPlacement().directories())
                    .containsExactly(dataDir);
            } finally {
                engine.close();
            }
        }
    }

    @Test
    @DisplayName("two volumes each get their share of the segment files")
    void segmentsSpreadOverBothVolumes() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");

        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(alternating(dataDir, second));
            try {
                engine.start();

                assertThat(segmentFilesIn(dataDir)).isPositive();
                assertThat(segmentFilesIn(second)).isPositive();
                assertThat(segmentFilesIn(dataDir) + segmentFilesIn(second))
                    .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS);
                assertThat(engine.segmentPath(3)).isEqualTo(second.resolve("segment_3.dat"));
            } finally {
                engine.close();
            }
        }
    }

    @Test
    @DisplayName("a restart over the same volumes finds every segment again")
    void restartRediscoversEveryVolume() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");
        byte[] key = "across-the-volumes".getBytes();
        byte[] value = "still-here".getBytes();

        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(alternating(dataDir, second));
            engine.start();
            engine.put(key, value);
            engine.close();
        }

        long onSecond = segmentFilesIn(second);
        assertThat(onSecond).isPositive();

        try (Arena arena = Arena.ofShared()) {
            StorageEngine reopened = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(alternating(dataDir, second));
            try {
                reopened.start();

                assertThat(reopened.getSegmentCount())
                    .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS);
                assertThat(segmentFilesIn(second)).isEqualTo(onSecond);
                assertThat(reopened.get(key)).isNotNull();
            } finally {
                reopened.close();
            }
        }
    }

    @Test
    @DisplayName("a restart that no longer covers a volume refuses rather than losing it")
    void aMissingVolumeIsRefusedLoudly() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");

        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(alternating(dataDir, second));
            engine.start();
            engine.close();
        }

        try (Arena arena = Arena.ofShared()) {
            StorageEngine reopened = new StorageEngine(dataDir, arena);
            try {
                assertThatThrownBy(reopened::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("segment")
                    .hasMessageContaining(second.toString());
            } finally {
                reopened.close();
            }
        }
    }

    @Test
    @DisplayName("a volume that is present but empty is refused just as loudly")
    void anEmptiedVolumeIsRefusedLoudly() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");

        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(alternating(dataDir, second));
            engine.start();
            engine.close();
        }

        try (Stream<Path> files = Files.list(second)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }

        try (Arena arena = Arena.ofShared()) {
            StorageEngine reopened = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(alternating(dataDir, second));
            try {
                assertThatThrownBy(reopened::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(second.toString());
            } finally {
                reopened.close();
            }
        }
    }

    @Test
    @DisplayName("the same segment index on two volumes is refused rather than chosen between")
    void aDuplicateSegmentIndexIsRefused() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");
        Files.createFile(dataDir.resolve("segment_4.dat"));
        Files.createFile(second.resolve("segment_4.dat"));

        SegmentDiscovery discovery = SegmentDiscovery.across(List.of(dataDir, second));

        assertThatThrownBy(discovery::scanDirectory)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("segment_4.dat");
    }

    @Test
    @DisplayName("placement cannot be changed once the engine has started")
    void placementIsFixedAtStart() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");

        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena);
            try {
                engine.start();

                assertThatThrownBy(() -> engine.withSegmentPlacement(alternating(dataDir, second)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("start");
            } finally {
                engine.close();
            }
        }
    }

    @Test
    @DisplayName("a placement that leaves the data directory out is rejected")
    void placementMustCoverTheDataDirectory() throws IOException {
        Path dataDir = dataDir();
        Path second = volume("volume-b");

        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena);
            try {
                assertThatThrownBy(() -> engine.withSegmentPlacement(
                    SegmentPlacement.across(List.of(second), index -> second)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(dataDir.toString());
            } finally {
                engine.close();
            }
        }
    }
}
