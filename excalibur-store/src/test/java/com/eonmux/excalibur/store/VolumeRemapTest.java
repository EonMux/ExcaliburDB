package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What happens to a store whose volume set changed between runs.
 *
 * <p>{@link SegmentPlacement} maps a segment index to a directory, and on a multi-volume node
 * that map is a consistent hash ring over the configured volume set. Adding or removing a
 * volume moves some indices to a different ring position, so the directory the placement names
 * for an index stops being the directory that index's file is actually in. Every path the
 * engine derives from the index alone - a replacement write-ahead log, the canonical name a
 * split child is renamed onto - then points at the wrong volume, and the store ends up with
 * a segment and its log on different disks, or with two {@code segment_N.dat} files and a node
 * that refuses to start.</p>
 *
 * <p>The invariant these tests pin is that a segment's location is decided by what is on disk
 * once there is something on disk. The placement decides only where a segment that does not
 * exist yet should go.</p>
 */
@DisplayName("A volume set that changed between runs")
class VolumeRemapTest {

    private static final long MOVED_INDEX = 3L;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path volume;

    /**
     * Creates the two directories a remap needs and returns the data directory.
     *
     * @return The data directory
     * @throws IOException if the directories cannot be created
     */
    private Path directories() throws IOException {
        dataDir = Files.createDirectories(tempDir.resolve("data"));
        volume = Files.createDirectories(tempDir.resolve("volume-b"));
        return dataDir;
    }

    /**
     * Returns a placement that sends exactly one index to the extra volume.
     *
     * @param moved The index the ring currently assigns to the extra volume, or -1 for none
     * @return The placement over both directories
     */
    private SegmentPlacement placing(long moved) {
        return SegmentPlacement.across(List.of(dataDir, volume),
            index -> index == moved ? volume : dataDir);
    }

    /**
     * Writes enough keys that every base segment holds entries.
     *
     * @param engine The engine to write into
     */
    private static void fill(StorageEngine engine) {
        for (int i = 0; i < 2000; i++) {
            engine.put(("remap-key-" + i).getBytes(), ("remap-value-" + i).getBytes());
        }
    }

    /**
     * Returns every directory holding a file for the given segment index.
     *
     * @param suffix The file suffix to look for, {@code .dat} or {@code .wal}
     * @param index  The segment index
     * @return The directories that hold one
     */
    private List<Path> holdersOf(String suffix, long index) {
        List<Path> holders = new ArrayList<>();
        for (Path directory : List.of(dataDir, volume)) {
            if (Files.isRegularFile(directory.resolve("segment_" + index + suffix))) {
                holders.add(directory);
            }
        }
        return holders;
    }

    /**
     * Runs a first generation of the store with the moved index on the extra volume.
     *
     * @throws IOException if the store cannot be created
     */
    private void firstRun() throws IOException {
        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(placing(MOVED_INDEX));
            try {
                engine.start();
                fill(engine);
            } finally {
                engine.close();
            }
        }
    }

    @Test
    @DisplayName("a write-ahead log lost to a crash is recreated beside its segment, not on the "
        + "volume the ring now prefers")
    void aRecreatedLogLandsBesideItsSegment() throws IOException {
        directories();
        firstRun();

        assertThat(holdersOf(".dat", MOVED_INDEX)).containsExactly(volume);
        Files.deleteIfExists(volume.resolve("segment_" + MOVED_INDEX + ".wal"));

        try (Arena arena = Arena.ofShared()) {
            StorageEngine reopened = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(placing(-1L));
            try {
                reopened.start();

                assertThat(holdersOf(".wal", MOVED_INDEX))
                    .as("a segment's log belongs on the volume the segment was found on")
                    .containsExactly(volume);
            } finally {
                reopened.close();
            }
        }
    }

    @Test
    @DisplayName("a split after a remap does not leave two files for one segment index")
    void aSplitAfterARemapKeepsOneFilePerIndex() throws Exception {
        directories();
        firstRun();

        try (Arena arena = Arena.ofShared()) {
            StorageEngine reopened = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(placing(-1L));
            try {
                reopened.start();

                Segment parent = reopened.getSegment((int) MOVED_INDEX);
                assertThat(parent).isNotNull();
                assertThat(parent.entryCount()).isPositive();

                reopened.getSplitWorker().requestSplit(parent,
                    Integer.numberOfTrailingZeros(reopened.getSegmentCount()));
                awaitSplit(parent);

                assertThat(holdersOf(".dat", MOVED_INDEX))
                    .as("a split child must take over the parent's file, not add a second one")
                    .hasSize(1);
            } finally {
                reopened.close();
            }
        }

        try (Arena arena = Arena.ofShared()) {
            StorageEngine afterSplit = new StorageEngine(dataDir, arena)
                .withSegmentPlacement(placing(-1L));
            try {
                assertThatCode(afterSplit::start)
                    .as("a node must still start after a split that followed a remap")
                    .doesNotThrowAnyException();
            } finally {
                afterSplit.close();
            }
        }
    }

    /**
     * Waits until the split of a segment has been published.
     *
     * @param parent The segment being split
     * @throws InterruptedException if interrupted while waiting
     */
    private static void awaitSplit(Segment parent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!parent.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Split did not complete within the timeout");
            }
            Thread.sleep(10);
        }
    }
}
