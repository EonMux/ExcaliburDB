package com.eonmux.excalibur.backup.verify;

import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.snapshot.SnapshotLayout;
import com.eonmux.excalibur.backup.snapshot.SnapshotOptions;
import com.eonmux.excalibur.backup.snapshot.SnapshotWriter;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification is the half of a backup that makes the other half worth keeping.
 *
 * <p>An unverifiable image is a rumour: it is indistinguishable from a good one until the
 * moment it is needed, which is the moment at which finding out is most expensive. These
 * tests damage a snapshot in each of the ways a snapshot is actually damaged - a flipped
 * byte, a truncated file, a missing file, an extra file, a mangled manifest - and require
 * that verification says so without touching the data directory it would restore into.
 */
@DisplayName("Verifying a snapshot against its manifest")
class SnapshotVerifierTest {

    @TempDir
    Path tempDir;

    private Path snapshot() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        return snapshotDir;
    }

    private static Path payload(Path snapshotDir, String name) {
        return snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY).resolve(name);
    }

    @Test
    @DisplayName("an untouched snapshot verifies")
    void cleanSnapshotVerifies() throws IOException {
        VerificationReport report =
            new SnapshotVerifier().verify(snapshot(), VerifyDepth.CHECKSUM);

        assertThat(report.problems()).isEmpty();
        assertThat(report.isValid()).isTrue();
        assertThat(report.filesChecked()).isEqualTo(report.manifest().files().size());
    }

    @Test
    @DisplayName("a single flipped byte is caught")
    void flippedByteIsCaught() throws IOException {
        Path snapshotDir = snapshot();
        DataDirectoryFixture.flipAByte(payload(snapshotDir, "segment_0.dat"));

        VerificationReport report =
            new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM);

        assertThat(report.isValid()).isFalse();
        assertThat(report.problems()).singleElement()
            .satisfies(problem -> {
                assertThat(problem.path()).isEqualTo("segment_0.dat");
                assertThat(problem.detail()).contains("checksum");
            });
    }

    @Test
    @DisplayName("a flipped byte inside a tenant's staging log is caught")
    void flippedByteInNestedFileIsCaught() throws IOException {
        Path snapshotDir = snapshot();
        DataDirectoryFixture.flipAByte(payload(snapshotDir, "shared/graph-staging/triples.log"));

        VerificationReport report =
            new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM);

        assertThat(report.isValid()).isFalse();
        assertThat(report.problems()).singleElement()
            .satisfies(p -> assertThat(p.path()).isEqualTo("shared/graph-staging/triples.log"));
    }

    @Test
    @DisplayName("a truncated file is caught by its size before its checksum")
    void truncatedFileIsCaught() throws IOException {
        Path snapshotDir = snapshot();
        Path victim = payload(snapshotDir, "routing.dir");
        Files.write(victim, new byte[16]);

        VerificationReport report =
            new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM);

        assertThat(report.isValid()).isFalse();
        assertThat(report.problems()).singleElement()
            .satisfies(p -> assertThat(p.detail()).contains("size"));
    }

    @Test
    @DisplayName("a missing file is caught")
    void missingFileIsCaught() throws IOException {
        Path snapshotDir = snapshot();
        Files.delete(payload(snapshotDir, "catalog.dat"));

        VerificationReport report =
            new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM);

        assertThat(report.isValid()).isFalse();
        assertThat(report.problems()).singleElement()
            .satisfies(p -> assertThat(p.detail()).contains("missing"));
    }

    @Test
    @DisplayName("a file the manifest does not name is caught")
    void unexpectedFileIsCaught() throws IOException {
        Path snapshotDir = snapshot();
        Files.write(payload(snapshotDir, "segment_9.dat"), new byte[] { 1, 2, 3 });

        VerificationReport report =
            new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM);

        assertThat(report.isValid()).isFalse();
        assertThat(report.problems()).singleElement()
            .satisfies(p -> assertThat(p.detail()).contains("not named"));
    }

    @Test
    @DisplayName("a manifest damaged in its own body is refused, not half-believed")
    void damagedManifestIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Path manifest = snapshotDir.resolve(ManifestCodec.FILE_NAME);
        String body = Files.readString(manifest).replace("segment_0.dat", "segment_X.dat");
        Files.writeString(manifest, body);

        assertThatThrownBy(() -> new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("manifest");
    }

    @Test
    @DisplayName("a snapshot with no manifest at all is refused")
    void absentManifestIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Files.delete(snapshotDir.resolve(ManifestCodec.FILE_NAME));

        assertThatThrownBy(() -> new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("manifest");
    }

    @Test
    @DisplayName("a deep verification asks each format about itself")
    void deepVerificationConsultsTheEngineCheckers() throws IOException {
        Path dataDir = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("data"), 50);
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        VerificationReport report = new SnapshotVerifier().verify(snapshotDir, VerifyDepth.DEEP);

        assertThat(report.isValid()).isTrue();
        assertThat(report.contentChecks())
            .as("every segment file must have been walked by the engine's own checker")
            .anySatisfy(check -> assertThat(check.format()).isEqualTo("segment"));
        assertThat(report.contentChecks()).allSatisfy(check ->
            assertThat(check.healthy()).as("%s: %s", check.path(), check.detail()).isTrue());
    }
}
