package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.filter.sketch.CountMinSketch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A segment's term-frequency sketch: counted as writes are applied, exact after a rewrite, and
 * kept across restarts only while it still describes the segment.
 */
@DisplayName("Segment term-frequency sketch")
class SegmentTermSketchTest {

    @TempDir
    Path tempDir;

    private static byte[] key(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] chunk(String text) {
        return ChunkPayloadCodec.encode(text, new float[] {0.1f, 0.2f});
    }

    private static long count(Segment segment, String term) {
        return segment.termFrequencySketch().estimate(term.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        private Arena arena;

        @BeforeEach
        void setUp() {
            arena = Arena.ofShared();
        }

        @AfterEach
        void tearDown() {
            arena.close();
        }

        @Test
        @DisplayName("every applied put counts its chunk text's terms, repetition included")
        void countsAppliedPuts() throws IOException {
            try (Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1 << 20, arena)) {
                segment.put(key("a"), chunk("Retrieval pipelines rank retrieval results"));
                segment.put(key("b"), chunk("graphs and retrieval"));

                assertThat(count(segment, "retrieval")).isEqualTo(3);
                assertThat(count(segment, "graphs")).isEqualTo(1);
                assertThat(count(segment, "and"))
                    .as("the same stop words the membership filters drop are dropped here")
                    .isZero();
            }
        }

        @Test
        @DisplayName("a value with no chunk text counts nothing")
        void ignoresValuesWithoutText() throws IOException {
            try (Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1 << 20, arena)) {
                segment.put(key("vector"), ChunkPayloadCodec.encode(new float[] {1f, 2f}));
                segment.put(key("short"), new byte[] {1});

                assertThat(segment.termFrequencySketch().totalCount()).isZero();
            }
        }

        @Test
        @DisplayName("a refused write counts nothing")
        void ignoresRefusedWrites() throws IOException {
            try (Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1 << 20, arena)) {
                int accepted = 0;
                try {
                    for (int i = 0; i < 1 << 20; i++) {
                        segment.put(key("k" + i), chunk("alpha"));
                        accepted++;
                    }
                } catch (SegmentCapacityExceededException full) {
                    // The segment refused the next write; it must not have been counted.
                }
                assertThat(count(segment, "alpha")).isEqualTo(accepted);
            }
        }

        @Test
        @DisplayName("an overwrite adds the new value's terms and keeps the old ones counted")
        void overwritesOverCount() throws IOException {
            try (Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1 << 20, arena)) {
                segment.put(key("a"), chunk("alpha"));
                segment.put(key("a"), chunk("beta"));
                segment.delete(key("a"));

                assertThat(count(segment, "alpha"))
                    .as("a sketch cannot take a count back; over-counting only reorders")
                    .isEqualTo(1);
                assertThat(count(segment, "beta")).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a segment without a write log writes no sidecar")
        void unloggedSegmentsDoNotPersist() throws IOException {
            Path path = tempDir.resolve("segment_0.dat");
            try (Segment segment = new Segment(path, 1 << 20, arena)) {
                segment.put(key("a"), chunk("alpha"));
                segment.flush();
            }
            assertThat(TermSketchFile.pathFor(path))
                .as("a scratch segment is not yet the owner of any name a restart looks under")
                .doesNotExist();
        }

        @Test
        @DisplayName("a new file removes the sidecar left at its name")
        void newFileDiscardsAStaleSidecar() throws IOException {
            Path path = tempDir.resolve("segment_0.dat");
            Path sidecar = TermSketchFile.pathFor(path);
            TermSketchFile.write(sidecar, new TermSketchFile.Watermark(0, 0, 0),
                SegmentTermSketch.newSketch().toBytes());

            try (Segment ignored = new Segment(path, 1 << 20, arena)) {
                assertThat(sidecar).doesNotExist();
            }
        }

        @Test
        @DisplayName("a detached sketch is neither counted into nor written")
        void detachedSketchIsLeftAlone() throws IOException {
            Path path = tempDir.resolve("segment_0.dat");
            try (Segment segment = new Segment(path, 1 << 20, arena)) {
                segment.attachWriteLog(new WriteLog(tempDir.resolve("segment_0.wal")));
                segment.put(key("a"), chunk("alpha"));
                CountMinSketch detached = segment.detachTermFrequencySketch();
                segment.put(key("b"), chunk("alpha"));

                assertThat(detached.estimate(key("alpha"))).isEqualTo(1);
                assertThat(segment.termFrequencySketch()).isNull();
            }
            assertThat(TermSketchFile.pathFor(path)).doesNotExist();
        }
    }

    @Nested
    @DisplayName("across a restart of one segment")
    class SegmentRestart {

        private Arena arena;

        @BeforeEach
        void setUp() {
            arena = Arena.ofShared();
        }

        @AfterEach
        void tearDown() {
            arena.close();
        }

        private Segment reopen(Path dat, Path wal) throws IOException {
            Segment segment = Segment.openExisting(dat, arena);
            segment.attachWriteLog(new WriteLog(wal));
            segment.recoverFromLog();
            return segment;
        }

        @Test
        @DisplayName("a sidecar written at close is adopted as it was")
        void adoptsACurrentSidecar() throws IOException {
            Path dat = tempDir.resolve("segment_0.dat");
            Path wal = tempDir.resolve("segment_0.wal");
            try (Segment segment = new Segment(dat, 1 << 20, arena)) {
                segment.attachWriteLog(new WriteLog(wal));
                segment.put(key("a"), chunk("alpha alpha"));
                segment.put(key("a"), chunk("beta"));
            }
            assertThat(TermSketchFile.pathFor(dat)).exists();

            try (Segment segment = reopen(dat, wal)) {
                assertThat(segment.terms.loadOrRebuild()).as("the sidecar is current").isTrue();
                assertThat(count(segment, "alpha"))
                    .as("adopted, not rebuilt: a rebuild counts only the live value")
                    .isEqualTo(2);
                assertThat(count(segment, "beta")).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("writes that reached the log after the last sidecar force a rebuild")
        void rebuildsAfterACrash() throws IOException {
            Path dat = tempDir.resolve("segment_0.dat");
            Path wal = tempDir.resolve("segment_0.wal");
            Path crashed = Files.createDirectories(tempDir.resolve("crashed"));
            try (Segment segment = new Segment(dat, 1 << 20, arena)) {
                segment.attachWriteLog(new WriteLog(wal));
                segment.put(key("a"), chunk("alpha"));
                segment.flush();
                segment.put(key("b"), chunk("gamma"));
                // What a crash leaves: the log has the second write, the sidecar does not.
                for (Path file : new Path[] {dat, wal, TermSketchFile.pathFor(dat)}) {
                    Files.copy(file, crashed.resolve(file.getFileName()));
                }
            }

            Path crashedDat = crashed.resolve("segment_0.dat");
            try (Segment segment = reopen(crashedDat, crashed.resolve("segment_0.wal"))) {
                assertThat(segment.terms.loadOrRebuild()).isFalse();
                assertThat(count(segment, "alpha")).isEqualTo(1);
                assertThat(count(segment, "gamma"))
                    .as("the write the stale sidecar never saw is counted by the rebuild")
                    .isEqualTo(1);
            }
            assertThat(TermSketchFile.pathFor(crashedDat))
                .as("closing writes the rebuilt sketch, dated to the recovered state")
                .satisfies(sidecar -> assertThat(
                    TermSketchFile.read(sidecar).sketch().estimate(key("gamma"))).isEqualTo(1));
        }

        @Test
        @DisplayName("a damaged sidecar is rebuilt rather than trusted")
        void rebuildsADamagedSidecar() throws IOException {
            Path dat = tempDir.resolve("segment_0.dat");
            Path wal = tempDir.resolve("segment_0.wal");
            try (Segment segment = new Segment(dat, 1 << 20, arena)) {
                segment.attachWriteLog(new WriteLog(wal));
                segment.put(key("a"), chunk("alpha"));
            }
            Path sidecar = TermSketchFile.pathFor(dat);
            byte[] bytes = Files.readAllBytes(sidecar);
            bytes[bytes.length - 1] ^= 0x7F;
            Files.write(sidecar, bytes);

            try (Segment segment = reopen(dat, wal)) {
                assertThat(segment.terms.loadOrRebuild()).isFalse();
                assertThat(count(segment, "alpha")).isEqualTo(1);
            }
            assertThat(TermSketchFile.read(sidecar).sketch().estimate(key("alpha"))).isEqualTo(1);
        }

        @Test
        @DisplayName("a close after a flush with no write in between writes the sidecar once")
        void unchangedSketchIsNotRewritten() throws IOException {
            Path dat = tempDir.resolve("segment_0.dat");
            Path sidecar = TermSketchFile.pathFor(dat);
            try (Segment segment = new Segment(dat, 1 << 20, arena)) {
                segment.attachWriteLog(new WriteLog(tempDir.resolve("segment_0.wal")));
                segment.put(key("a"), chunk("alpha"));
                segment.flush();
                Files.write(sidecar, new byte[] {1});
            }
            assertThat(Files.readAllBytes(sidecar))
                .as("nothing changed since the flush, so the close leaves the file alone")
                .containsExactly(1);
        }
    }

    @Nested
    @DisplayName("through the engine")
    class ThroughTheEngine {

        private Path dataDir;
        private StorageEngine engine;

        @BeforeEach
        void setUp() throws IOException {
            dataDir = Files.createDirectories(tempDir.resolve("engine"));
            engine = new StorageEngine(dataDir);
            engine.start();
        }

        @AfterEach
        void tearDown() {
            if (engine != null) {
                engine.close();
            }
        }

        private long total(String term) {
            long total = 0;
            for (Segment segment : distinctSegments()) {
                total += count(segment, term);
            }
            return total;
        }

        private Set<Segment> distinctSegments() {
            Map<Segment, Boolean> distinct = new IdentityHashMap<>();
            for (Segment segment : engine.getSegments().values()) {
                distinct.put(segment, true);
            }
            return distinct.keySet();
        }

        @Test
        @DisplayName("a restart adopts the sketches written at shutdown")
        void survivesARestart() throws IOException {
            engine.put(key("a"), chunk("alpha"));
            engine.put(key("a"), chunk("beta"));
            engine.put(key("b"), chunk("alpha"));
            engine.close();
            engine = new StorageEngine(dataDir);
            engine.start();

            assertThat(total("alpha"))
                .as("the overwritten value's count survives, so the sketch was loaded, not rebuilt")
                .isEqualTo(2);
            assertThat(total("beta")).isEqualTo(1);
            for (Segment segment : distinctSegments()) {
                assertThat(segment.termFrequencySketch())
                    .as("every reopened segment has a sketch, sidecar or not")
                    .isNotNull();
            }
        }

        @Test
        @DisplayName("a compacted segment's sketch counts only what survived")
        void compactionLeavesAnExactSketch() throws IOException {
            engine.put(key("keep"), chunk("alpha"));
            engine.put(key("drop"), chunk("omega omega"));
            engine.delete(key("drop"));
            Segment original = null;
            long originalIndex = -1;
            for (Map.Entry<Long, Segment> entry : engine.getSegments().entrySet()) {
                if (entry.getValue().tombstoneCount() > 0) {
                    original = entry.getValue();
                    originalIndex = entry.getKey();
                }
            }
            assertThat(original).isNotNull();
            long index = originalIndex;
            Path sidecar = TermSketchFile.pathFor(original.path());
            original.flush();
            assertThat(sidecar).exists();

            CompactionWorker worker = new CompactionWorker(engine.arena(),
                (oldSegment, newSegment) -> engine.replaceSegment(index, oldSegment, newSegment)
                    ? CompactionOutcome.INSTALLED : CompactionOutcome.REFUSED);
            assertThat(worker.compactNow(original, true)).isTrue();

            assertThat(original.isClosed()).isTrue();
            assertThat(total("omega")).as("the deleted entry's terms went with it").isZero();
            assertThat(total("alpha")).isEqualTo(1);
            assertThat(sidecar)
                .as("the original's sidecar is gone, and closing it did not write it back")
                .doesNotExist();

            engine.flush();
            assertThat(TermSketchFile.read(sidecar).sketch().estimate(key("omega"))).isZero();
        }

        @Test
        @DisplayName("split children each count exactly what they were given")
        void splitChildrenPartitionTheCounts() throws Exception {
            for (int i = 0; i < 400; i++) {
                engine.put(key("split-" + i), chunk("common term" + i));
            }
            Segment parent = null;
            for (Segment segment : distinctSegments()) {
                if (segment.entryCount() > 0) {
                    parent = segment;
                }
            }
            assertThat(parent).isNotNull();
            long parentCommon = count(parent, "common");
            Path parentSidecar = TermSketchFile.pathFor(parent.path());
            parent.flush();

            int routing = engine.getSegmentCount();
            engine.getSplitWorker().requestSplit(parent, Integer.numberOfTrailingZeros(routing));
            long deadline = System.currentTimeMillis() + 10_000;
            while (!parent.isSplitComplete()) {
                assertThat(System.currentTimeMillis()).isLessThan(deadline);
                Thread.sleep(10);
            }

            Segment left = parent.leftChild();
            Segment right = parent.rightChild();
            assertThat(count(left, "common") + count(right, "common")).isEqualTo(parentCommon);
            assertThat(count(left, "common")).isEqualTo(left.liveEntryCount());
            assertThat(count(right, "common")).isEqualTo(right.liveEntryCount());
            assertThat(parent.termFrequencySketch())
                .as("the retired parent gave its sketch up before closing")
                .isNull();

            engine.flush();
            assertThat(left.path()).isEqualTo(parent.path());
            assertThat(TermSketchFile.read(parentSidecar).sketch().estimate(key("common")))
                .as("the sidecar at the parent's name is the left child's")
                .isEqualTo(left.liveEntryCount());
        }
    }
}
