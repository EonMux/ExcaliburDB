package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a start does with a store it cannot fully trust.
 *
 * <p>Two policies meet here. Damage to one entry costs that entry and nothing else, so derived
 * data built from the entries at start - the term sketch - is built from the rest and the node
 * serves. Damage that leaves a segment's committed state unknown - a write-ahead log that will
 * not replay - refuses the start; and a refused start must let go of everything it opened, or
 * every retry leaves another set of descriptors behind an engine nobody will close.</p>
 */
@DisplayName("Engine start over damaged files")
class EngineStartRecoveryTest {

    @TempDir
    Path tempDir;

    private static byte[] key(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] chunk(String text) {
        return ChunkPayloadCodec.encode(text, new float[] {0.1f, 0.2f});
    }

    @Test
    @DisplayName("a damaged entry does not keep the engine from starting, and the rest are counted")
    void aDamagedEntryDoesNotFailTheStart() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("damaged-entry"));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            engine.put(key("healthy"), chunk("alpha"));
            engine.put(key("damaged"), chunk("zanzibar"));
        }
        // No sidecar, so the start has to rebuild every sketch from the entries; and no log, as
        // for entries a log had long since moved past, or the replay would rewrite the entry
        // over its damage before the sketch is built.
        deleteAll(dataDir, ".tfs");
        deleteAll(dataDir, ".wal");
        flipByteInsideSegment(dataDir, "zanzibar".getBytes(StandardCharsets.UTF_8));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();

            long alpha = 0;
            long damaged = 0;
            for (Segment segment : engine.getSegments().values()) {
                alpha += segment.termFrequencySketch().estimate(key("alpha"));
                // The flip only changes case, which tokenising folds away: had the damaged
                // entry been read rather than left out, it would count as "zanzibar".
                damaged += segment.termFrequencySketch().estimate(key("zanzibar"));
            }
            assertThat(alpha).as("the healthy entry is counted").isEqualTo(1);
            assertThat(damaged).as("the damaged entry is left out, not counted as read").isZero();
            assertThat(engine.get(key("healthy"))).isNotNull();
        }
    }

    @Test
    @DisplayName("a start refused over a damaged log closes every segment it opened")
    void aRefusedStartReleasesWhatItOpened() throws IOException {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")),
            "open descriptors are read from /proc");
        Path dataDir = Files.createDirectories(tempDir.resolve("refused"));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            for (int i = 0; i < 64; i++) {
                engine.put(key("key-" + i), chunk("value " + i));
            }
        }
        Path damagedLog = largestFile(dataDir, ".wal");
        corruptFirstRecord(damagedLog);

        StorageEngine engine = new StorageEngine(dataDir);
        try {
            // Refused either as the log is opened or as it is replayed; which one depends on
            // where the damage falls, and either way the segments opened before it stay open
            // unless the start lets them go.
            assertThatThrownBy(engine::start).isInstanceOf(RuntimeException.class);

            assertThat(engine.getSegments()).as("nothing is left registered").isEmpty();
            assertThat(descriptorsUnder(dataDir))
                .as("no segment file, log or sidecar stays open behind the refused start; the "
                    + "catalog is the engine's, and its close releases it")
                .allSatisfy(open -> assertThat(open.getFileName().toString())
                    .doesNotStartWith("segment_"));
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("staging files an interrupted sketch write left are removed when the segment "
        + "is opened, and only that segment's")
    void interruptedSketchWritesAreSwept() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("staging"));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            engine.put(key("a"), chunk("alpha"));
        }
        Path sidecar = TermSketchFile.pathFor(largestFile(dataDir, ".dat"));
        String name = sidecar.getFileName().toString();
        Path orphan = Files.write(sidecar.resolveSibling(name + ".0f3c.tmp"), new byte[] {1});
        Path otherSegments = Files.write(
            sidecar.resolveSibling(name.replace(".tfs", "9.tfs") + ".0f3c.tmp"), new byte[] {1});
        Path notStaging = Files.write(sidecar.resolveSibling(name + ".tmp"), new byte[] {1});

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
        }

        assertThat(orphan).doesNotExist();
        assertThat(otherSegments).as("another segment's name").exists();
        assertThat(notStaging).as("not a name a write stages under").exists();
    }

    @Test
    @DisplayName("the sweep reports nothing for a directory that is not there")
    void sweepingAMissingDirectoryRemovesNothing() {
        assertThat(TermSketchFile.sweepStaging(tempDir.resolve("gone").resolve("segment_0.tfs")))
            .isZero();
    }

    private static void deleteAll(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(suffix)).toList()) {
                Files.delete(file);
            }
        }
    }

    private static Path largestFile(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(suffix))
                .max(Comparator.comparingLong(EngineStartRecoveryTest::sizeOf))
                .orElseThrow();
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Flips a bit inside the one stored copy of a byte run among the segment files. */
    private static void flipByteInsideSegment(Path dir, byte[] marker) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".dat")).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                int at = indexOf(bytes, marker);
                if (at >= 0) {
                    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                        raf.seek(at + 2);
                        byte original = raf.readByte();
                        raf.seek(at + 2);
                        raf.writeByte(original ^ 0x20);
                    }
                    return;
                }
            }
        }
        throw new IllegalStateException("marker not found in any segment file");
    }

    /**
     * Damages the first record of a log that holds several, so the damage is not a torn tail
     * the replay may drop but a hole in the middle of the committed history.
     */
    private static void corruptFirstRecord(Path log) throws IOException {
        byte[] bytes = Files.readAllBytes(log);
        byte[] first = key("key-");
        int at = indexOf(bytes, first);
        if (at < 0) {
            throw new IllegalStateException("no record found in " + log);
        }
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "rw")) {
            raf.seek(at);
            byte original = raf.readByte();
            raf.seek(at);
            raf.writeByte(original ^ 0x40);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The files under a directory this process holds a descriptor for. */
    private static List<Path> descriptorsUnder(Path dir) throws IOException {
        Path real = dir.toRealPath();
        List<Path> open = new ArrayList<>();
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            for (Path fd : fds.toList()) {
                try {
                    Path target = Files.readSymbolicLink(fd);
                    if (target.startsWith(real)) {
                        open.add(target);
                    }
                } catch (IOException | UnsupportedOperationException e) {
                    // The descriptor closed while the list was being read, or is not a file.
                }
            }
        }
        return open;
    }
}
