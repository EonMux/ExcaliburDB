package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configurable segment load factor.
 *
 * <p>{@code excalibur.shard.segment-load-factor} was documented in the README and read into
 * {@code Config}, but nothing consumed it — the threshold was a compile-time constant, so an
 * operator tuning it changed nothing at all.
 */
@DisplayName("Segment load factor")
class SegmentLoadFactorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a lower threshold refuses inserts sooner")
    void lowerThresholdFillsSooner() throws IOException {
        // The observable consequence of the knob: the same segment size accepts fewer entries.
        int atDefault = insertUntilFull(Segment.LOAD_FACTOR_THRESHOLD, "default");
        int atHalf = insertUntilFull(0.35, "low");

        assertThat(atHalf)
            .as("a threshold half the default must fill at roughly half the entries")
            .isLessThan(atDefault);
    }

    @Test
    @DisplayName("the default matches the published constant")
    void defaultMatchesConstant() throws IOException {
        assertThat(insertUntilFull(Segment.LOAD_FACTOR_THRESHOLD, "explicit"))
            .isEqualTo(insertUntilFull(null, "implicit"));
    }

    @Test
    @DisplayName("a reopened segment keeps the configured threshold, not the default")
    void reopenPreservesTheThreshold() throws IOException {
        // The failure this prevents is unrecoverable rather than merely wrong: a segment filled
        // under a configured 0.9 and reopened at the compile-time 0.7 rejects replay of entries
        // it had already acknowledged, so the process fails to start and fails again every time.
        Path path = tempDir.resolve("reopened.dat");
        double loadFactor;
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(path, 4 * 1024 * 1024, arena, 0.9)) {
            for (int i = 0; i < 200; i++) {
                segment.put(("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    ("v" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            loadFactor = segment.loadFactor();
        }
        assertThat(loadFactor).isPositive();

        // Two opens of the same file, straddling its load factor. They can only disagree if
        // each is using the threshold it was opened with.
        double below = loadFactor / 2;
        double above = Math.min(0.99, loadFactor + (1 - loadFactor) / 2);

        try (Arena arena = Arena.ofShared();
             Segment strict = Segment.openExisting(path, arena, below)) {
            assertThat(strict.loadFactor()).isEqualTo(loadFactor);
            assertThat(strict.shouldSplit()).as("over a threshold of %s", below).isTrue();
        }

        try (Arena arena = Arena.ofShared();
             Segment lenient = Segment.openExisting(path, arena, above)) {
            assertThat(lenient.shouldSplit()).as("under a threshold of %s", above).isFalse();
        }
    }

    @Test
    @DisplayName("the engine opens discovered segments with its configured threshold")
    void engineAppliesItsThresholdOnRecovery() throws IOException {
        // The wiring, not just the constructor: discovery runs inside start(), after the
        // threshold has been set, and used to ignore it.
        Path dataDir = Files.createDirectories(tempDir.resolve("engine"));
        try (Arena arena = Arena.ofShared();
             Segment seeded = new Segment(dataDir.resolve("segment_0.dat"),
                 4 * 1024 * 1024, arena, 0.9)) {
            for (int i = 0; i < 200; i++) {
                seeded.put(("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    ("v" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            // 200 entries in a 131072-slot table is a load factor around 0.0015, so this
            // threshold sits just under it. Under the default 0.7 the same segment reports
            // false, which is what the recovered segment used to do whatever was configured.
            engine.withLoadFactorThreshold(0.001);
            engine.start();

            Segment recovered = engine.getSegment(0);
            assertThat(recovered).isNotNull();
            assertThat(recovered.entryCount()).isEqualTo(200);
            assertThat(recovered.loadFactor()).isGreaterThan(0.001);
            assertThat(recovered.shouldSplit())
                .as("the engine's configured threshold, not the compile-time default")
                .isTrue();
        }
    }

    @Test
    @DisplayName("reopening validates the threshold like creating does")
    void reopenValidatesTheThreshold() throws IOException {
        Path path = tempDir.resolve("validated.dat");
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(path, 1024 * 1024, arena, 0.5)) {
            segment.put("k".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "v".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        try (Arena arena = Arena.ofShared()) {
            for (double bad : new double[] { 0.0, 1.0, -0.5, Double.NaN }) {
                assertThatThrownBy(() -> Segment.openExisting(path, arena, bad))
                    .as("threshold %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    /** Inserts until the segment refuses, returning how many were accepted. */
    private static int fill(Segment segment) {
        int accepted = 0;
        for (int i = 0; i < 100_000; i++) {
            try {
                segment.put(("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    ("value-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                accepted++;
            } catch (RuntimeException e) {
                break;
            }
        }
        return accepted;
    }

    @Test
    @DisplayName("a segment that stops accepting inserts asks to be split")
    void fullSegmentAsksToBeSplit() throws IOException {
        // These were two separate calculations that disagreed by one entry, and the
        // disagreement disabled automatic splitting outright: inserts were refused when
        // entryCount + 1 reached the threshold, so entryCount never got there, so
        // loadFactor() >= threshold was never true and the SplitWorker -- scanning every
        // 100ms -- always saw false. A segment would fill, refuse every subsequent write, and
        // never once report that it wanted splitting.
        Path path = tempDir.resolve("full.dat");
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(path, 4 * 1024 * 1024, arena, 0.05)) {

            assertThat(segment.shouldSplit()).as("an empty segment").isFalse();

            boolean refused = false;
            for (int i = 0; i < 1_000_000 && !refused; i++) {
                try {
                    segment.put(("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        ("v" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (SegmentCapacityExceededException e) {
                    assertThat(e.reason())
                        .as("the load factor, not the data region")
                        .isEqualTo(SegmentCapacityExceededException.Reason.LOAD_FACTOR);
                    refused = true;
                }
            }

            assertThat(refused).as("the segment must reach its load-factor limit").isTrue();
            assertThat(segment.shouldSplit())
                .as("a segment that has refused an insert is exactly one that needs splitting")
                .isTrue();
        }
    }

    @Test
    @DisplayName("the split is asked for before any write is refused")
    void splitIsRequestedBeforeTheFirstRefusal() throws IOException {
        // Not merely at the same moment: the last accepted insert is what makes shouldSplit()
        // true, so the worker has the segment queued before a client is ever told it is full.
        Path path = tempDir.resolve("boundary.dat");
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(path, 4 * 1024 * 1024, arena, 0.05)) {

            boolean sawSplitWantedWhileStillAccepting = false;
            for (int i = 0; i < 1_000_000; i++) {
                try {
                    segment.put(("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        ("v" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (SegmentCapacityExceededException e) {
                    break;
                }
                if (segment.shouldSplit()) {
                    sawSplitWantedWhileStillAccepting = true;
                    break;
                }
            }

            assertThat(sawSplitWantedWhileStillAccepting)
                .as("shouldSplit() must become true on an accepted insert, not only after a "
                    + "rejected one")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a threshold outside (0, 1) is refused")
    void thresholdValidated() throws IOException {
        Path path = tempDir.resolve("invalid.dat");
        try (Arena arena = Arena.ofShared()) {
            for (double bad : new double[] { 0.0, 1.0, -0.5, 1.5, Double.NaN }) {
                assertThatThrownBy(() -> new Segment(path, 1024 * 1024, arena, bad))
                    .as("threshold %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    @DisplayName("the engine refuses an out-of-range load factor and refuses it late")
    void engineValidates() throws IOException {
        Path dataDir = tempDir.resolve("engine");
        Files.createDirectories(dataDir);
        StorageEngine engine = new StorageEngine(dataDir);
        try {
            assertThatThrownBy(() -> engine.withLoadFactorThreshold(0.0))
                .isInstanceOf(IllegalArgumentException.class);

            engine.withLoadFactorThreshold(0.5);
            assertThat(engine.loadFactorThreshold()).isEqualTo(0.5);

            engine.start();
            assertThatThrownBy(() -> engine.withLoadFactorThreshold(0.6))
                .as("segments are pre-allocated in start(); changing it after would apply two policies")
                .isInstanceOf(IllegalStateException.class);
        } finally {
            engine.close();
        }
    }

    /** Inserts until the segment refuses, returning how many it accepted. */
    private int insertUntilFull(Double threshold, String name) throws IOException {
        Path path = tempDir.resolve(name + ".dat");
        Files.deleteIfExists(path);
        try (Arena arena = Arena.ofShared();
             Segment segment = threshold == null
                 ? new Segment(path, 1024 * 1024, arena)
                 : new Segment(path, 1024 * 1024, arena, threshold)) {

            int accepted = 0;
            for (int i = 0; i < 100_000; i++) {
                try {
                    segment.put(("k" + i).getBytes(), ("v" + i).getBytes());
                    accepted++;
                } catch (SegmentCapacityExceededException e) {
                    break;
                }
            }
            return accepted;
        }
    }
}
