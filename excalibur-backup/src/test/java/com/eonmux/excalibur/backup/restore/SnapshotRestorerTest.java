package com.eonmux.excalibur.backup.restore;

import com.eonmux.excalibur.backup.io.FileDigest;
import com.eonmux.excalibur.backup.io.FileDigests;
import com.eonmux.excalibur.backup.manifest.FileEntry;
import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.manifest.SnapshotManifest;
import com.eonmux.excalibur.backup.snapshot.SnapshotLayout;
import com.eonmux.excalibur.backup.snapshot.SnapshotOptions;
import com.eonmux.excalibur.backup.snapshot.SnapshotWriter;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import com.eonmux.excalibur.backup.verify.VerifyDepth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a restore must refuse.
 *
 * <p>A restore writes over a data directory, so every one of its refusals is load-bearing.
 * It must not begin from an image it cannot prove intact, because the failure would be
 * discovered halfway through with the original already gone; it must not overwrite a
 * directory that holds anything, because the operator who typed the wrong target has no
 * second chance; and when it does refuse, the target must be exactly as it was.
 */
@DisplayName("Restoring a data directory from a snapshot")
class SnapshotRestorerTest {

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
    @DisplayName("a snapshot leaves out an earlier build's term sketch")
    void aSnapshotLeavesOutAnObsoleteSketch() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.write(dataDir.resolve("segment_0.tfs"), new byte[48]);
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        assertThat(payload(snapshotDir, "segment_0.tfs"))
            .as("nothing in this build reads it, even with derived state kept").doesNotExist();
        assertThat(ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME)).files())
            .noneMatch(entry -> entry.path().equals("segment_0.tfs"));
    }

    @Test
    @DisplayName("an earlier build's image restores without its term sketches")
    void anEarlierBuildsImageRestoresWithoutItsSketches() throws IOException {
        Path snapshotDir = imageWithSketches();

        RestoreResult result = new SnapshotRestorer().restore(new RestoreOptions(
            snapshotDir, tempDir.resolve("restored"), false, VerifyDepth.CHECKSUM));

        assertThat(tempDir.resolve("restored/segment_0.tfs"))
            .as("verified with the image, then left out rather than restored for the node to "
                + "remove")
            .doesNotExist();
        assertThat(tempDir.resolve("restored/segment_0.dat")).exists();
        assertThat(result.filesRestored()).isEqualTo(result.manifest().files().size() - 1);
        assertThat(result.rebuildRequired())
            .noneSatisfy(line -> assertThat(line).containsIgnoringCase("sketch"));
    }

    @Test
    @DisplayName("an earlier build's image whose term sketch was altered is still refused")
    void anAlteredSketchInAnEarlierBuildsImageIsRefused() throws IOException {
        Path snapshotDir = imageWithSketches();
        DataDirectoryFixture.flipAByte(payload(snapshotDir, "segment_0.tfs"));
        Path target = tempDir.resolve("restored");

        assertThatThrownBy(() -> new SnapshotRestorer().restore(new RestoreOptions(
            snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .as("the file is not restored, but its bytes are still part of what the image "
                + "certifies")
            .isInstanceOf(RestoreRefusedException.class);
        assertThat(target).doesNotExist();
    }

    /**
     * Builds the image an earlier build would have taken: this build's, with a term sketch
     * beside the first segment in the payload and named in the manifest.
     *
     * @return The image
     * @throws IOException if it cannot be written
     */
    private Path imageWithSketches() throws IOException {
        Path snapshotDir = snapshot();
        Path sketch = payload(snapshotDir, "segment_0.tfs");
        Files.write(sketch, new byte[48]);
        FileDigest digest = FileDigests.of(sketch);
        Path manifestFile = snapshotDir.resolve(ManifestCodec.FILE_NAME);
        SnapshotManifest manifest = ManifestCodec.read(manifestFile);
        List<FileEntry> files = new ArrayList<>(manifest.files());
        files.add(new FileEntry("segment_0.tfs", digest.size(), digest.crc32c(),
            digest.sha256()));
        files.sort(Comparator.comparing(FileEntry::path));
        ManifestCodec.write(new SnapshotManifest(manifest.manifestVersion(),
            manifest.snapshotId(), manifest.createdAt(), manifest.producer(),
            manifest.sourceDataDir(), manifest.epoch(), manifest.epochSource(),
            manifest.consistency(), manifest.formatVersions(), files), manifestFile);
        return snapshotDir;
    }

    @Test
    @DisplayName("a compaction's lone staged mapping survives an image without derived state")
    void aLoneStagedMappingIsRestored() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.write(dataDir.resolve("segment_0.hnsw.compact.docmap"), new byte[24]);
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, false, false));

        new SnapshotRestorer().restore(new RestoreOptions(
            snapshotDir, tempDir.resolve("restored"), false, VerifyDepth.CHECKSUM));

        assertThat(tempDir.resolve("restored/segment_0.hnsw.compact.docmap"))
            .hasBinaryContent(new byte[24]);
    }

    @Test
    @DisplayName("an empty target is restored into")
    void emptyTargetIsRestored() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("restored"));

        RestoreResult result = new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(result.filesRestored()).isGreaterThan(0);
        assertThat(target.resolve("catalog.dat")).exists();
    }

    @Test
    @DisplayName("a target that does not exist yet is created")
    void absentTargetIsCreated() throws IOException {
        Path snapshotDir = snapshot();
        Path target = tempDir.resolve("does-not-exist-yet");

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(target.resolve("routing.dir")).exists();
    }

    @Test
    @DisplayName("a snapshot whose manifest does not verify is refused")
    void damagedSnapshotIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        DataDirectoryFixture.flipAByte(payload(snapshotDir, "segment_0.dat"));
        Path target = Files.createDirectories(tempDir.resolve("restored"));

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .isInstanceOf(RestoreRefusedException.class)
            .extracting(e -> ((RestoreRefusedException) e).reason())
            .isEqualTo(RestoreRefusedException.Reason.SNAPSHOT_DOES_NOT_VERIFY);
    }

    @Test
    @DisplayName("a snapshot with an unreadable manifest is refused")
    void unreadableManifestIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Files.writeString(snapshotDir.resolve(ManifestCodec.FILE_NAME), "not a manifest\n");
        Path target = Files.createDirectories(tempDir.resolve("restored"));

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .isInstanceOf(RestoreRefusedException.class)
            .extracting(e -> ((RestoreRefusedException) e).reason())
            .isEqualTo(RestoreRefusedException.Reason.SNAPSHOT_DOES_NOT_VERIFY);
    }

    @Test
    @DisplayName("a refused restore leaves the target exactly as it was")
    void refusedRestoreLeavesTheTargetUntouched() throws IOException {
        Path snapshotDir = snapshot();
        DataDirectoryFixture.flipAByte(payload(snapshotDir, "segment_0.dat"));
        Path target = Files.createDirectories(tempDir.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 900L);
        Map<String, String> before = DataDirectoryFixture.digestTree(target);

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(RestoreRefusedException.class);

        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(before);
    }

    @Test
    @DisplayName("a non-empty target is refused without the explicit flag")
    void nonEmptyTargetIsRefusedWithoutTheFlag() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 901L);
        Map<String, String> before = DataDirectoryFixture.digestTree(target);

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .isInstanceOf(RestoreRefusedException.class)
            .extracting(e -> ((RestoreRefusedException) e).reason())
            .isEqualTo(RestoreRefusedException.Reason.TARGET_NOT_EMPTY);

        assertThat(DataDirectoryFixture.digestTree(target))
            .as("a refusal must not have removed or replaced anything")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a non-empty target is replaced when the flag is given")
    void nonEmptyTargetIsReplacedWithTheFlag() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("stale-file"), 4096, 902L);

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM));

        assertThat(target.resolve("stale-file"))
            .as("a swap leaves the snapshot's tree, not a merge of the two").doesNotExist();
        assertThat(target.resolve("catalog.dat")).exists();
    }

    @Test
    @DisplayName("restoring stages beside the target and swaps, leaving no scratch behind")
    void restoreLeavesNoScratchDirectory() throws IOException {
        Path snapshotDir = snapshot();
        Path target = tempDir.resolve("restored");

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        try (var siblings = Files.list(tempDir)) {
            assertThat(siblings.map(p -> p.getFileName().toString()))
                .noneMatch(name -> name.contains(SnapshotLayout.STAGING_INFIX));
        }
    }

    @Test
    @DisplayName("a deep restore of a sound image walks every format and restores it")
    void deepRestoreOfASoundImageSucceeds() throws IOException {
        Path dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        Path target = tempDir.resolve("restored");

        RestoreResult result = new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.DEEP));

        assertThat(result.filesRestored()).isEqualTo(result.manifest().files().size());
        assertThat(DataDirectoryFixture.digestTree(target))
            .isEqualTo(DataDirectoryFixture.digestTree(dataDir));
    }

    @Test
    @DisplayName("a deep restore refuses an image whose graph was damaged before it was taken")
    void deepRestoreRefusesDamageTheChecksumsCopiedFaithfully() throws IOException {
        Path dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
        DataDirectoryFixture.flipAByte(dataDir.resolve(DataDirectoryFixture.DOCUMENT_MAPPING));
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        Path target = tempDir.resolve("restored");

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, tempDir.resolve("shallow"), false,
                VerifyDepth.CHECKSUM));
        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.DEEP)))
            .isInstanceOf(RestoreRefusedException.class)
            .hasMessageContaining(DataDirectoryFixture.DOCUMENT_MAPPING)
            .extracting(e -> ((RestoreRefusedException) e).reason())
            .isEqualTo(RestoreRefusedException.Reason.SNAPSHOT_DOES_NOT_VERIFY);
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("a swap that fails puts the displaced target back as it was")
    void failedSwapRestoresTheDisplacedTarget() throws IOException {
        Path snapshotDir = snapshot();
        Path parent = Files.createDirectories(tempDir.resolve("volume"));
        Path target = Files.createDirectories(parent.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 903L);
        DataDirectoryFixture.writeRandom(target.resolve("nested/segment_0.dat"), 2048, 904L);
        Map<String, String> before = DataDirectoryFixture.digestTree(target);

        SnapshotRestorer restorer = new SnapshotRestorer(failingOnto(target, false));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("the swap onto the target failed");

        assertThat(DataDirectoryFixture.digestTree(target))
            .as("the operator must be left with the directory they had, not with none")
            .isEqualTo(before);
        try (var siblings = Files.list(parent)) {
            assertThat(siblings.map(p -> p.getFileName().toString()))
                .as("neither the staged tree nor the displaced one may be left beside it")
                .containsExactly("live");
        }
    }

    @Test
    @DisplayName("a rollback that fails too is reported on the failure, not lost")
    void failedRollbackIsAttachedToTheFailure() throws IOException {
        Path snapshotDir = snapshot();
        Path parent = Files.createDirectories(tempDir.resolve("volume"));
        Path target = Files.createDirectories(parent.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 905L);

        SnapshotRestorer restorer = new SnapshotRestorer(failingOnto(target, true));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .satisfies(failure -> assertThat(failure.getSuppressed())
                .anySatisfy(suppressed -> assertThat(suppressed)
                    .hasMessageContaining("the rollback failed")));
        try (var siblings = Files.list(parent)) {
            assertThat(siblings.map(p -> p.getFileName().toString()))
                .as("the displaced directory is still there, under the name the error gives")
                .anyMatch(name -> name.contains(SnapshotLayout.STAGING_INFIX + "replaced-"));
        }
    }

    @Test
    @DisplayName("a swap that fails with no previous target leaves nothing behind")
    void failedSwapWithNoPreviousTargetLeavesNothing() throws IOException {
        Path snapshotDir = snapshot();
        Path parent = Files.createDirectories(tempDir.resolve("volume"));
        Path target = parent.resolve("fresh");

        SnapshotRestorer restorer = new SnapshotRestorer(failingOnto(target, false));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class);
        try (var siblings = Files.list(parent)) {
            assertThat(siblings).isEmpty();
        }
    }

    /**
     * Returns a mover that refuses to rename anything onto the target.
     *
     * <p>Standing in for the one failure the swap guards against: the old directory has been
     * renamed aside and the new one will not take its name. Every other rename is real, so
     * what the restorer does next - putting the old directory back, and clearing up the
     * staged tree - happens on the filesystem and is asserted there.</p>
     *
     * @param target         The name nothing may be renamed onto
     * @param rollbackFails  Whether the rename that puts the old directory back fails too
     * @return The mover
     */
    private static SnapshotRestorer.DirectoryMover failingOnto(Path target,
                                                               boolean rollbackFails) {
        return (source, destination) -> {
            boolean onto = destination.toAbsolutePath().normalize()
                .equals(target.toAbsolutePath().normalize());
            boolean rollback = source.getFileName().toString()
                .contains(SnapshotLayout.STAGING_INFIX + "replaced-");
            if (onto && !rollback) {
                throw new IOException("the swap onto the target failed");
            }
            if (onto && rollbackFails) {
                throw new IOException("the rollback failed");
            }
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        };
    }
}
