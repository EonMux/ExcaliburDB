package com.eonmux.excalibur.backup.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The manifest is the only thing that can say an image is complete, so it has to be able to
 * say that it is itself complete first.
 */
@DisplayName("The snapshot manifest on disk")
class ManifestCodecTest {

    @TempDir
    Path tempDir;

    private static SnapshotManifest sample() {
        return new SnapshotManifest(
            ManifestCodec.MANIFEST_VERSION,
            "0189c2f0-0000-7000-8000-000000000001",
            Instant.parse("2026-09-14T10:15:30Z"),
            "excalibur-backup/1.0.0-SNAPSHOT",
            "/var/lib/excalibur/data",
            4711L,
            EpochSource.CATALOG_AND_LEXICAL_STAMPS,
            Consistency.OFFLINE,
            Map.of("catalog", "2", "manifest", "1"),
            List.of(
                new FileEntry("catalog.dat", 61440L, "deadbeef", "a".repeat(64)),
                new FileEntry("shared/graph-staging/triples.log", 360L, "0000cafe",
                    "b".repeat(64))));
    }

    @Test
    @DisplayName("round-trips every field")
    void roundTrips() throws IOException {
        Path file = tempDir.resolve("MANIFEST");
        ManifestCodec.write(sample(), file);

        assertThat(ManifestCodec.read(file)).isEqualTo(sample());
    }

    @Test
    @DisplayName("is readable text, so an operator can read it without this tool")
    void isReadableText() throws IOException {
        Path file = tempDir.resolve("MANIFEST");
        ManifestCodec.write(sample(), file);

        String body = Files.readString(file);
        assertThat(body).startsWith(ManifestCodec.MAGIC_LINE);
        assertThat(body).contains("epoch: 4711");
        assertThat(body).contains("catalog.dat");
    }

    @Test
    @DisplayName("refuses a body that has been edited")
    void refusesAnEditedBody() throws IOException {
        Path file = tempDir.resolve("MANIFEST");
        ManifestCodec.write(sample(), file);
        Files.writeString(file, Files.readString(file).replace("61440", "61441"));

        assertThatThrownBy(() -> ManifestCodec.read(file))
            .isInstanceOf(ManifestFormatException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("refuses a file that is not a manifest")
    void refusesForeignContent() throws IOException {
        Path file = Files.writeString(tempDir.resolve("MANIFEST"), "hello\n");

        assertThatThrownBy(() -> ManifestCodec.read(file))
            .isInstanceOf(ManifestFormatException.class);
    }

    @Test
    @DisplayName("refuses a manifest written by a newer format")
    void refusesANewerFormat() throws IOException {
        Path file = tempDir.resolve("MANIFEST");
        SnapshotManifest future = new SnapshotManifest(
            ManifestCodec.MANIFEST_VERSION + 1, sample().snapshotId(), sample().createdAt(),
            sample().producer(), sample().sourceDataDir(), sample().epoch(),
            sample().epochSource(), sample().consistency(), sample().formatVersions(),
            sample().files());
        ManifestCodec.write(future, file);

        assertThatThrownBy(() -> ManifestCodec.read(file))
            .isInstanceOf(ManifestFormatException.class)
            .hasMessageContaining("version");
    }

    @Test
    @DisplayName("sums what it names")
    void reportsTotalBytes() {
        assertThat(sample().totalBytes()).isEqualTo(61440L + 360L);
    }
}
