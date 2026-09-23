package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured load factor has to reach the segments an engine creates for itself.
 *
 * <p>It is set once, on the engine, and every segment after the first generation is created by
 * the store rather than by the caller: split children and the rebuilds compaction swaps in. A
 * segment created with the default instead splits at a density the operator did not ask for, and
 * since every segment eventually descends from a split, the configured value stops applying to
 * the whole store.</p>
 */
@DisplayName("Configured load factor")
class ConfiguredLoadFactorInheritanceTest {

    /** Deliberately not {@link Segment#LOAD_FACTOR_THRESHOLD}, so inheriting is visible. */
    private static final double CONFIGURED = 0.85;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("split children inherit the parent's threshold")
    void splitChildrenInheritTheThreshold() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            Segment parent = new Segment(tempDir.resolve("parent.dat"), 4 * 1024 * 1024,
                arena, CONFIGURED);
            for (int i = 0; i < 200; i++) {
                parent.put(key(i), value(i));
            }

            AtomicReference<Segment> left = new AtomicReference<>();
            AtomicReference<Segment> right = new AtomicReference<>();
            CountDownLatch published = new CountDownLatch(1);

            SplitWorker worker = new SplitWorker(arena, (p, l, r) -> {
                left.set(l);
                right.set(r);
                published.countDown();
            }, () -> 0);

            worker.requestSplit(parent, 0);
            assertThat(published.await(30, TimeUnit.SECONDS))
                .as("the split published its children")
                .isTrue();

            // The callback runs in the middle of the split, and this arena closes at the end of
            // the block: without draining the requested split first, the rest of it would be
            // reading and writing memory that is being unmapped underneath it.
            worker.stop(Duration.ofSeconds(10));

            assertThat(left.get().loadFactorThreshold()).isEqualTo(CONFIGURED);
            assertThat(right.get().loadFactorThreshold()).isEqualTo(CONFIGURED);
        }
    }

    @Test
    @DisplayName("a compacted segment inherits the threshold of the one it replaces")
    void compactionInheritsTheThreshold() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            Path path = tempDir.resolve("compacting.dat");
            Segment segment = new Segment(path, 4 * 1024 * 1024, arena, CONFIGURED);
            for (int i = 0; i < 100; i++) {
                segment.put(key(i), value(i));
            }
            for (int i = 0; i < 50; i++) {
                segment.delete(key(i));
            }

            AtomicReference<Segment> replacement = new AtomicReference<>();
            CountDownLatch swapped = new CountDownLatch(1);

            CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> {
                replacement.set(newSegment);
                swapped.countDown();
                return CompactionOutcome.INSTALLED;
            });

            worker.requestCompaction(segment);
            assertThat(swapped.await(30, TimeUnit.SECONDS))
                .as("compaction swapped in a replacement")
                .isTrue();

            // Same reason as above: the swap is not the end of the rewrite, and stop() is what
            // waits for the rest of it before this arena goes away.
            worker.stop(Duration.ofSeconds(10));

            assertThat(replacement.get().loadFactorThreshold()).isEqualTo(CONFIGURED);
        }
    }

    private static byte[] key(int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
