package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Losing the routing directory must not turn into keys quietly pointing at the wrong segment.
 *
 * <p>Routing is {@code hash & (width - 1)} and a split leaves several slots sharing one
 * segment. Which slot went to which child is decided by the bit the split partitioned on, and
 * that bit exists nowhere in the segment file names. The reconstruction the engine falls back
 * to - slot {@code i} without a file of its own belongs to {@code i - highestOneBit(i)} - is
 * exact while at most one segment has ever split, and wrong after that: the data in the
 * mis-addressed file is not deleted, it simply stops being reachable while the next write for
 * those keys lands somewhere else.</p>
 *
 * <p>So a store that has split more than once and has no directory to read is a store this
 * build cannot address correctly. Saying so and stopping is recoverable; guessing is not,
 * because the guess is indistinguishable from the truth until the divergence is permanent.</p>
 */
@DisplayName("A routing directory that went missing after more than one split")
class RoutingDirectoryLossTest {

    private static void splitAndAwait(StorageEngine engine, long slot) throws InterruptedException {
        Segment target = engine.getSegment(slot);
        assertThat(target).as("segment at slot %s", slot).isNotNull();

        int widthBefore = engine.getSegmentCount();
        engine.getSplitWorker().requestSplit(target);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (engine.getSegmentCount() == widthBefore && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(engine.getSegmentCount()).isEqualTo(widthBefore * 2);
    }

    private static Path storeWithTwoSplits(Path tempDir, String name) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve(name));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 200; i++) {
                engine.put(("loss-key-" + i).getBytes(StandardCharsets.UTF_8),
                    ("loss-value-" + i).getBytes(StandardCharsets.UTF_8));
            }
            splitAndAwait(engine, 7);
            splitAndAwait(engine, 3);
        }
        return dataDir;
    }

    @Test
    @DisplayName("a missing directory after two splits refuses to start rather than guess")
    void aMissingDirectoryAfterTwoSplitsRefusesToStart(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeWithTwoSplits(tempDir, "missing");
        Files.delete(dataDir.resolve(RoutingDirectory.FILE_NAME));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RoutingDirectory.FILE_NAME)
                .hasMessageContaining("split");
        }
    }

    @Test
    @DisplayName("a corrupt directory after two splits refuses to start rather than guess")
    void aCorruptDirectoryAfterTwoSplitsRefusesToStart(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeWithTwoSplits(tempDir, "corrupt");
        Files.write(dataDir.resolve(RoutingDirectory.FILE_NAME), new byte[] { 1, 2, 3 });

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RoutingDirectory.FILE_NAME);
        }
    }

    @Test
    @DisplayName("the refusal does not destroy anything: restoring the directory restores the store")
    void restoringTheDirectoryRestoresTheStore(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeWithTwoSplits(tempDir, "restorable");
        Path directory = dataDir.resolve(RoutingDirectory.FILE_NAME);
        byte[] saved = Files.readAllBytes(directory);
        Files.delete(directory);

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            assertThatThrownBy(engine::start).isInstanceOf(IllegalStateException.class);
        }

        Files.write(directory, saved);
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 200; i++) {
                assertThat(engine.get(("loss-key-" + i).getBytes(StandardCharsets.UTF_8)))
                    .as("key %s", i)
                    .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("the directory it writes is complete, leaving no staging file behind")
    void theWrittenDirectoryIsCompleteAndLeavesNoTemp(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeWithTwoSplits(tempDir, "complete");

        assertThat(dataDir.resolve(RoutingDirectory.FILE_NAME + ".tmp"))
            .as("the staging file is renamed into place, never left behind")
            .doesNotExist();
        assertThat(RoutingDirectory.read(dataDir))
            .as("and what it wrote is a directory the next start can read")
            .hasSize(StorageEngine.DEFAULT_INITIAL_SEGMENTS * 4);
    }
}
