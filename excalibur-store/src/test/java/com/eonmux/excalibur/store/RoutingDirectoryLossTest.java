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
 * how this build assigns slots, but a store written by a build that doubled the directory on
 * every split leaves the same file names behind and assigns them differently. Past twice the
 * base width the two can disagree: the data in a mis-addressed file is not deleted, it simply
 * stops being reachable while the next write for those keys lands somewhere else.</p>
 *
 * <p>So a store whose directory has doubled more than once and has no directory to read is a
 * store this build cannot be sure of addressing correctly. Saying so and stopping is
 * recoverable; guessing is not, because the guess is indistinguishable from the truth until the
 * divergence is permanent.</p>
 */
@DisplayName("A routing directory that went missing after the directory doubled twice")
class RoutingDirectoryLossTest {

    private static void splitAndAwait(StorageEngine engine, long slot) throws InterruptedException {
        Segment target = engine.getSegment(slot);
        assertThat(target).as("segment at slot %s", slot).isNotNull();

        engine.getSplitWorker().requestSplit(target);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!target.isSplitComplete() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(target.isSplitComplete()).as("the split of slot %s publishes", slot).isTrue();
    }

    private static Path storeDoubledTwice(Path tempDir, String name) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve(name));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 200; i++) {
                engine.put(("loss-key-" + i).getBytes(StandardCharsets.UTF_8),
                    ("loss-value-" + i).getBytes(StandardCharsets.UTF_8));
            }
            // Slot 7's segment, then its left child: the second split is of a segment as deep
            // as the directory, so the directory doubles twice, to four times its base width.
            splitAndAwait(engine, 7);
            splitAndAwait(engine, 7);
            splitAndAwait(engine, 3);
            assertThat(engine.getSegmentCount())
                .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS * 4);
        }
        return dataDir;
    }

    @Test
    @DisplayName("a missing directory after two doublings refuses to start rather than guess")
    void aMissingDirectoryAfterTwoDoublingsRefusesToStart(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeDoubledTwice(tempDir, "missing");
        Files.delete(dataDir.resolve(RoutingDirectory.FILE_NAME));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RoutingDirectory.FILE_NAME)
                .hasMessageContaining("split");
        }
    }

    @Test
    @DisplayName("a corrupt directory after two doublings refuses to start rather than guess")
    void aCorruptDirectoryAfterTwoDoublingsRefusesToStart(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeDoubledTwice(tempDir, "corrupt");
        Files.write(dataDir.resolve(RoutingDirectory.FILE_NAME), new byte[] { 1, 2, 3 });

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RoutingDirectory.FILE_NAME);
        }
    }

    @Test
    @DisplayName("a directory that fails its checksum after two doublings refuses to start, saying so")
    void aChecksumFailureAfterTwoDoublingsRefusesToStart(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeDoubledTwice(tempDir, "checksum");
        Path directory = dataDir.resolve(RoutingDirectory.FILE_NAME);
        byte[] bytes = Files.readAllBytes(directory);
        // One slot now names another segment that exists: well-formed, and wrong.
        bytes[RoutingDirectory.HEADER_BYTES + 19 * Integer.BYTES] ^= 1;
        Files.write(directory, bytes);

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RoutingDirectory.FILE_NAME)
                .hasMessageContaining("checksum did not match");
        }
    }

    @Test
    @DisplayName("a directory an older build wrote is applied, and the next split adds a checksum")
    void anUncheckedDirectoryIsAppliedAndUpgradedByTheNextSplit(@TempDir Path tempDir)
            throws Exception {
        Path dataDir = storeDoubledTwice(tempDir, "upgraded");
        Path directory = dataDir.resolve(RoutingDirectory.FILE_NAME);
        int[] slots = RoutingDirectory.decode(Files.readAllBytes(directory)).canonicalPerSlot();
        java.nio.ByteBuffer legacy = java.nio.ByteBuffer
            .allocate(RoutingDirectory.HEADER_BYTES + slots.length * Integer.BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(RoutingDirectory.MAGIC)
            .putInt(RoutingDirectory.UNCHECKSUMMED_VERSION)
            .putInt(slots.length);
        for (int slot : slots) {
            legacy.putInt(slot);
        }
        Files.write(directory, legacy.array());

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 200; i++) {
                assertThat(engine.get(("loss-key-" + i).getBytes(StandardCharsets.UTF_8)))
                    .as("key %s, routed by the directory the older build wrote", i)
                    .isNotNull();
            }
            splitAndAwait(engine, 5);
        }
        assertThat(RoutingDirectory.decode(Files.readAllBytes(directory)).version())
            .isEqualTo(RoutingDirectory.VERSION);
    }

    @Test
    @DisplayName("the refusal does not destroy anything: restoring the directory restores the store")
    void restoringTheDirectoryRestoresTheStore(@TempDir Path tempDir) throws Exception {
        Path dataDir = storeDoubledTwice(tempDir, "restorable");
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
        Path dataDir = storeDoubledTwice(tempDir, "complete");

        assertThat(dataDir.resolve(RoutingDirectory.FILE_NAME + ".tmp"))
            .as("the staging file is renamed into place, never left behind")
            .doesNotExist();
        assertThat(RoutingDirectory.read(dataDir))
            .as("and what it wrote is a directory the next start can read")
            .hasSize(StorageEngine.DEFAULT_INITIAL_SEGMENTS * 4);
    }
}
