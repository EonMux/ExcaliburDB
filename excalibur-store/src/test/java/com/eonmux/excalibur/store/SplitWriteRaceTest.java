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
 * What happens to an operation that resolved a segment just before that segment split.
 *
 * <p>A split retires the parent: it leaves the directory, and its file is taken over by the left
 * child. An operation that resolved the parent a moment earlier still holds a reference to it,
 * and the parent used to accept the write - into a slot table and a write log that map an inode
 * nothing can reach any more. The entry was invisible to every lookup and gone at process exit,
 * with the write having reported success.</p>
 */
@DisplayName("Writes across a split")
class SplitWriteRaceTest {

    private static void splitAndAwait(StorageEngine engine, Segment target) throws Exception {
        int widthBefore = engine.getSegmentCount();
        engine.getSplitWorker().requestSplit(target);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (engine.getSegmentCount() == widthBefore && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(engine.getSegmentCount())
            .as("the split must widen the routing table")
            .isEqualTo(widthBefore * 2);
    }

    /**
     * Waits for the parent to reach the retired state, not merely for the table to widen.
     *
     * <p>Those are two transitions, and the routing table widens first. In between, the parent is
     * still SPLITTING and refuses writes with the copy-on-write error rather than the retirement
     * one - a refusal either way, so nothing is lost, but a different message. Waiting for the
     * state the assertion is about is what makes the assertion about the state rather than about
     * how loaded the machine was.</p>
     */
    private static void awaitRetired(Segment parent) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!parent.isSplitComplete() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(parent.isSplitComplete())
            .as("the parent must be retired once its children are published")
            .isTrue();
    }

    @Test
    @DisplayName("a retired parent refuses a write instead of swallowing it")
    void retiredParentRefusesWrites(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("retire"));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 500; i++) {
                engine.put(key(i), value(i));
            }

            // The reference a writer would be holding across the publication.
            Segment parent = engine.getSegment(0);
            assertThat(parent).isNotNull();

            splitAndAwait(engine, parent);
            awaitRetired(parent);

            assertThatThrownBy(() -> parent.put(key(9999), value(9999)))
                .isInstanceOf(SegmentRetiredException.class);
            assertThatThrownBy(() -> parent.delete(key(0)))
                .isInstanceOf(SegmentRetiredException.class);
        }
    }

    @Test
    @DisplayName("a write routed while a split publishes still lands somewhere reachable")
    void writesAcrossASplitStayReachable(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("race"));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 500; i++) {
                engine.put(key(i), value(i));
            }

            Segment parent = engine.getSegment(0);
            splitAndAwait(engine, parent);

            // Every key the engine accepted before the split is still addressable through the
            // directory afterwards. A slot resolved against the pre-split mask would send the
            // half whose split bit is 1 to the wrong child.
            for (int i = 0; i < 500; i++) {
                assertThat(engine.get(key(i)))
                    .as("key %s is still reachable after the split", i)
                    .isNotNull();
            }

            // And the engine keeps accepting writes for the keys the parent used to own.
            for (int i = 500; i < 700; i++) {
                engine.put(key(i), value(i));
            }
            for (int i = 500; i < 700; i++) {
                assertThat(engine.get(key(i)))
                    .as("key %s written after the split is reachable", i)
                    .isNotNull();
            }
        }
    }

    private static byte[] key(int i) {
        return ("split-race-key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("split-race-value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
