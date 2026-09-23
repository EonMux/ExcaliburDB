package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing directory has to come back the way it went away.
 *
 * <p>Routing is {@code hash & (routingSegmentCount - 1)}, and a split leaves several directory
 * slots sharing one segment. On restart the engine rebuilds that directory from the segment files
 * it finds — and file names alone do not carry enough information to reproduce it once more than
 * one segment has split. A slot that resolves to the wrong segment does not lose the data in the
 * file; it makes it unaddressable, and sends the next write for those keys somewhere else again.
 */
@DisplayName("Routing survives a restart")
class RoutingSurvivesRestartTest {

    /** Slot to the file backing it, which is what routing has to preserve. */
    private static Map<Long, String> routingMap(StorageEngine engine) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (long slot = 0; slot < engine.getSegmentCount(); slot++) {
            Segment segment = engine.getSegment(slot);
            map.put(slot, segment == null ? null : segment.path().getFileName().toString());
        }
        return map;
    }

    private static void splitAndAwait(StorageEngine engine, long slot) throws InterruptedException {
        Segment target = engine.getSegment(slot);
        assertThat(target).as("segment at slot %s", slot).isNotNull();

        engine.getSplitWorker().requestSplit(target);

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!target.isSplitComplete() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(target.isSplitComplete())
            .as("the split of slot %s must publish", slot)
            .isTrue();
    }

    @Test
    @DisplayName("two splits at different slots survive reopening")
    void twoSplitsSurviveRestart(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("routing"));

        Map<Long, String> before;
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();

            for (int i = 0; i < 200; i++) {
                engine.put(("routing-key-" + i).getBytes(StandardCharsets.UTF_8),
                    ("routing-value-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Two splits, the second on a segment the first has already given a second slot to,
            // so it re-points one of them instead of widening the directory again. Then the one
            // deep segment the first split left behind splits, and the directory doubles.
            splitAndAwait(engine, 7);
            splitAndAwait(engine, 3);
            splitAndAwait(engine, 71);

            before = routingMap(engine);
            assertThat(engine.getSegmentCount())
                .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS * 4);
        }

        try (StorageEngine reopened = new StorageEngine(dataDir)) {
            reopened.start();

            assertThat(reopened.getSegmentCount())
                .as("directory width")
                .isEqualTo(before.size());
            assertThat(routingMap(reopened))
                .as("every slot must resolve to the file it resolved to before the restart")
                .isEqualTo(before);
        }
    }
    @Test
    @DisplayName("keys written before two splits are still readable after a restart")
    void keysRemainAddressableAcrossRestart(@TempDir Path tempDir) throws Exception {
        // The consequence of getting the directory wrong. The data in the mis-routed file is
        // not deleted; it stops being reachable, and the next write for those keys lands
        // somewhere else while the old values stay where they were.
        Path dataDir = Files.createDirectories(tempDir.resolve("addressable"));
        int keys = 2000;

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < keys; i++) {
                engine.put(("addr-key-" + i).getBytes(StandardCharsets.UTF_8),
                    ("addr-value-" + i).getBytes(StandardCharsets.UTF_8));
            }
            splitAndAwait(engine, 7);
            splitAndAwait(engine, 3);
        }

        try (StorageEngine reopened = new StorageEngine(dataDir)) {
            reopened.start();

            int missing = 0;
            for (int i = 0; i < keys; i++) {
                if (reopened.get(("addr-key-" + i).getBytes(StandardCharsets.UTF_8)) == null) {
                    missing++;
                }
            }
            assertThat(missing).as("keys unreachable after the restart").isZero();
        }
    }

    @Test
    @DisplayName("a store with no routing file still starts, reconstructing what it can")
    void missingDirectoryFallsBack(@TempDir Path tempDir) throws Exception {
        // Stores written by an earlier build have no routing file. Starting is more important
        // than being exact, so the engine falls back to the heuristic and says so.
        Path dataDir = Files.createDirectories(tempDir.resolve("legacy"));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            engine.put("legacy".getBytes(StandardCharsets.UTF_8),
                "value".getBytes(StandardCharsets.UTF_8));
            splitAndAwait(engine, 7);
        }
        Files.delete(dataDir.resolve("routing.dir"));

        try (StorageEngine reopened = new StorageEngine(dataDir)) {
            reopened.start();

            assertThat(reopened.getSegmentCount())
                .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS * 2);
            assertThat(reopened.get("legacy".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(Files.exists(dataDir.resolve("routing.dir")))
                .as("and writes one, so the next start does not have to guess again")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a corrupt routing file is ignored rather than fatal")
    void corruptDirectoryFallsBack(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("corrupt"));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            engine.put("corrupt".getBytes(StandardCharsets.UTF_8),
                "value".getBytes(StandardCharsets.UTF_8));
        }
        Files.write(dataDir.resolve("routing.dir"), new byte[] { 1, 2, 3 });

        try (StorageEngine reopened = new StorageEngine(dataDir)) {
            reopened.start();
            assertThat(reopened.get("corrupt".getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

}
