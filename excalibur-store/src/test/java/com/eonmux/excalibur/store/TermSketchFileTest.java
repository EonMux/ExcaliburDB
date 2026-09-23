package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.filter.sketch.CountMinSketch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The term sketch sidecar's format: what it holds, and what it refuses.
 */
@DisplayName("Term sketch sidecar file")
class TermSketchFileTest {

    private static final byte[] TERM = "retrieval".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    @DisplayName("sits beside the segment in place of its .dat suffix")
    void pathReplacesTheDataSuffix() {
        assertThat(TermSketchFile.pathFor(dir.resolve("segment_7.dat")))
            .isEqualTo(dir.resolve("segment_7.tfs"));
        assertThat(TermSketchFile.pathFor(dir.resolve("segment_7.dat.compact")))
            .isEqualTo(dir.resolve("segment_7.dat.compact.tfs"));
    }

    @Test
    @DisplayName("round-trips the watermark and every counter")
    void roundTrips() throws IOException {
        CountMinSketch sketch = SegmentTermSketch.newSketch();
        sketch.insert(TERM, 5);
        TermSketchFile.Watermark watermark = new TermSketchFile.Watermark(42, 9, 7);
        Path file = dir.resolve("segment_0.tfs");

        TermSketchFile.write(file, watermark, sketch.toBytes());
        TermSketchFile.Stored stored = TermSketchFile.read(file);

        assertThat(stored.watermark()).isEqualTo(watermark);
        assertThat(stored.sketch().estimate(TERM)).isEqualTo(5);
        assertThat(stored.sketch().totalCount()).isEqualTo(5);
        assertThat(TermSketchFile.verifyIntegrity(file).status())
            .isEqualTo(IntegrityStatus.INTACT);
        try (var siblings = Files.list(dir)) {
            assertThat(siblings).as("the staging file is consumed by the rename")
                .containsExactly(file);
        }
    }

    @Test
    @DisplayName("a changed counter fails the checksum rather than being believed")
    void refusesAFlippedBodyByte() throws IOException {
        Path file = written();
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        assertThatThrownBy(() -> TermSketchFile.read(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("checksum");
        assertThat(TermSketchFile.verifyIntegrity(file).status())
            .isEqualTo(IntegrityStatus.CORRUPT);
    }

    @Test
    @DisplayName("a changed header fails the header checksum")
    void refusesAFlippedHeaderByte() throws IOException {
        Path file = written();
        byte[] bytes = Files.readAllBytes(file);
        bytes[13] ^= 0x01;
        Files.write(file, bytes);

        assertThatThrownBy(() -> TermSketchFile.read(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("header checksum");
    }

    @Test
    @DisplayName("a truncated or extended file is refused")
    void refusesTheWrongLength() throws IOException {
        Path file = written();
        byte[] bytes = Files.readAllBytes(file);

        Files.write(file, Arrays.copyOf(bytes, bytes.length - 8));
        assertThatThrownBy(() -> TermSketchFile.read(file)).isInstanceOf(IOException.class);

        Files.write(file, Arrays.copyOf(bytes, bytes.length + 8));
        assertThatThrownBy(() -> TermSketchFile.read(file)).isInstanceOf(IOException.class);

        Files.write(file, Arrays.copyOf(bytes, 10));
        assertThatThrownBy(() -> TermSketchFile.read(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("truncated");
    }

    @Test
    @DisplayName("a sidecar from before the sketch's columns changed is refused by its version")
    void refusesThePreviousVersion() throws IOException {
        Path file = written();
        byte[] bytes = Files.readAllBytes(file);
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(bytes);
        header.putInt(8, TermSketchFile.VERSION - 1);
        // Re-sealed, so what refuses it is the version and not the damaged header checksum.
        header.putLong(24, com.eonmux.excalibur.core.integrity.IndexChecksum.of(bytes, 0, 24));
        Files.write(file, bytes);

        assertThatThrownBy(() -> TermSketchFile.read(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("at version " + (TermSketchFile.VERSION - 1));
    }

    @Test
    @DisplayName("another file under the name is not read as a sketch")
    void refusesAnotherFormat() throws IOException {
        Path file = dir.resolve("segment_0.tfs");
        Files.write(file, new byte[64]);

        assertThatThrownBy(() -> TermSketchFile.read(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("magic");
    }

    @Test
    @DisplayName("a missing sidecar reports as missing")
    void reportsMissing() {
        assertThat(TermSketchFile.verifyIntegrity(dir.resolve("segment_9.tfs")).status())
            .isEqualTo(IntegrityStatus.MISSING);
    }

    @Test
    @DisplayName("a sidecar the operating system will not read is unreadable, not corrupt")
    void reportsARefusedReadAsUnreadable() throws IOException {
        Path file = written();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(file),
                "a process that reads through file permissions (root) cannot be refused");

            IntegrityReport report = TermSketchFile.verifyIntegrity(file);

            assertThat(report.status()).isEqualTo(IntegrityStatus.UNREADABLE);
            assertThat(report.detail()).contains("could not be read");
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    @DisplayName("a name that holds no file's bytes is unreadable, not corrupt")
    void reportsADirectoryAsUnreadable() throws IOException {
        Path file = Files.createDirectory(dir.resolve("segment_0.tfs"));

        assertThat(TermSketchFile.verifyIntegrity(file).status())
            .isEqualTo(IntegrityStatus.UNREADABLE);
    }

    @Test
    @DisplayName("deleting beside a segment removes only its sidecar")
    void deletesTheSidecarBesideASegment() throws IOException {
        Path file = written();
        Path other = dir.resolve("segment_1.tfs");
        Files.copy(file, other);

        TermSketchFile.deleteFor(dir.resolve("segment_0.dat"));

        assertThat(file).doesNotExist();
        assertThat(other).exists();
    }

    private Path written() throws IOException {
        CountMinSketch sketch = SegmentTermSketch.newSketch();
        sketch.insert(TERM, 3);
        Path file = dir.resolve("segment_0.tfs");
        TermSketchFile.write(file, new TermSketchFile.Watermark(1, 1, 1), sketch.toBytes());
        return file;
    }
}
