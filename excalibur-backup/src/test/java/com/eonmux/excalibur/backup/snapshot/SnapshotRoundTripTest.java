package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.backup.manifest.Consistency;
import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.manifest.SnapshotManifest;
import com.eonmux.excalibur.backup.restore.RestoreOptions;
import com.eonmux.excalibur.backup.restore.RestoreResult;
import com.eonmux.excalibur.backup.restore.SnapshotRestorer;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import com.eonmux.excalibur.backup.verify.SnapshotVerifier;
import com.eonmux.excalibur.backup.verify.VerificationReport;
import com.eonmux.excalibur.backup.verify.VerifyDepth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A snapshot is only worth having if what comes back out is what went in.
 *
 * <p>These tests take a real engine's data directory through the whole cycle - snapshot,
 * verify, restore - and compare the restored tree against the original byte for byte. The
 * comparison is on content rather than on file metadata deliberately: a write-ahead log is
 * a 64 MiB mapping with a few kilobytes written into it, and a restore that materialises the
 * hole has produced an identical file even though it occupies a different number of blocks.
 */
@DisplayName("Snapshot and restore round-trip a data directory")
class SnapshotRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a real engine's directory restores byte-identically")
    void realEngineDirectoryRestoresByteIdentically() throws IOException {
        Path dataDir = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("data"), 200);
        Map<String, String> before = DataDirectoryFixture.digestTree(dataDir);

        Path snapshotDir = tempDir.resolve("snap");
        SnapshotResult snapshot = new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        assertThat(snapshot.manifest().consistency()).isEqualTo(Consistency.OFFLINE);
        assertThat(snapshot.manifest().files()).isNotEmpty();

        VerificationReport report =
            new SnapshotVerifier().verify(snapshotDir, VerifyDepth.CHECKSUM);
        assertThat(report.problems()).isEmpty();
        assertThat(report.isValid()).isTrue();

        Path target = tempDir.resolve("restored");
        RestoreResult restored = new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(restored.filesRestored()).isEqualTo(snapshot.manifest().files().size());
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(before);
    }

    @Test
    @DisplayName("the manifest names every file with its size and checksum")
    void manifestDescribesEveryFile() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        SnapshotResult snapshot = new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        SnapshotManifest reread =
            ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME));
        assertThat(reread).isEqualTo(snapshot.manifest());
        for (var entry : reread.files()) {
            Path payload = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
                .resolve(entry.path());
            assertThat(payload).exists();
            assertThat(Files.size(payload)).isEqualTo(entry.size());
            assertThat(entry.sha256()).hasSize(64);
            assertThat(entry.crc32c()).hasSize(8);
        }
    }

    @Test
    @DisplayName("the restored tree holds only what the manifest named")
    void restoreWritesNothingTheManifestDidNotName() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        Path target = tempDir.resolve("restored");
        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        SnapshotManifest manifest =
            ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME));
        assertThat(DataDirectoryFixture.digestTree(target).keySet())
            .containsExactlyInAnyOrderElementsOf(manifest.files().stream().map(f -> f.path())
                .toList());
    }

    @Test
    @DisplayName("a write-ahead log's hole survives the round trip")
    void sparseFilesStaySparse() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        Path copied = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
            .resolve("segment_0.wal");
        assertThat(Files.size(copied)).isEqualTo(DataDirectoryFixture.WAL_APPARENT_SIZE);
        assertThat(DataDirectoryFixture.digest(copied))
            .isEqualTo(DataDirectoryFixture.digest(dataDir.resolve("segment_0.wal")));
    }

    @Test
    @DisplayName("the Lucene chunk index is kept or left out whole, its erasure log with it")
    void theLuceneChunkIndexTravelsWhole() throws IOException {
        Path dataDir = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("data"), 24);
        DataDirectoryFixture.writeLuceneChunkIndex(dataDir);
        String index = DataDirectoryFixture.LUCENE_CHUNK_INDEX + "/";

        Path whole = tempDir.resolve("whole");
        SnapshotResult withDerived = new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, whole, true, false));
        Path slim = tempDir.resolve("slim");
        SnapshotResult withoutDerived = new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, slim, false, false));

        assertThat(withDerived.manifest().files())
            .anyMatch(entry -> entry.path().equals(index + "erasure.log"))
            .anyMatch(entry -> entry.path().startsWith(index + "segments_"));
        assertThat(withoutDerived.manifest().files())
            .as("an image never holds the index without the promises made against it")
            .noneMatch(entry -> entry.path().startsWith(index));

        VerificationReport deep = new SnapshotVerifier().verify(whole, VerifyDepth.DEEP);
        assertThat(deep.problems()).isEmpty();
        assertThat(deep.contentChecks())
            .anyMatch(check -> check.path().equals(DataDirectoryFixture.LUCENE_CHUNK_INDEX)
                && check.proven());

        RestoreResult restored = new SnapshotRestorer().restore(new RestoreOptions(
            whole, tempDir.resolve("restored-whole"), false, VerifyDepth.CHECKSUM));
        assertThat(restored.rebuildRequired())
            .noneSatisfy(line -> assertThat(line).contains("lexical chunk index"));
        RestoreResult rebuilt = new SnapshotRestorer().restore(new RestoreOptions(
            slim, tempDir.resolve("restored-slim"), false, VerifyDepth.CHECKSUM));
        assertThat(rebuilt.rebuildRequired())
            .anySatisfy(line -> assertThat(line).contains("lexical chunk index"));
    }
}
