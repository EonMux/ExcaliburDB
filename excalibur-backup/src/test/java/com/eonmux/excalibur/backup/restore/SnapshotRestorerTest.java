package com.eonmux.excalibur.backup.restore;

import com.eonmux.excalibur.backup.manifest.ManifestCodec;
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
    @DisplayName("an image without term sketches says the node rebuilds them at start-up")
    void anImageWithoutSketchesSaysTheyAreRebuilt() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.write(dataDir.resolve("segment_0.tfs"), new byte[48]);
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, false, false));

        RestoreResult result = new SnapshotRestorer().restore(new RestoreOptions(
            snapshotDir, tempDir.resolve("restored"), false, VerifyDepth.CHECKSUM));

        assertThat(tempDir.resolve("restored/segment_0.tfs"))
            .as("derived state left out on request").doesNotExist();
        assertThat(result.rebuildRequired())
            .anySatisfy(line -> assertThat(line).contains("term sketches", "start-up"));
    }

    @Test
    @DisplayName("an image with its term sketches has nothing to say about them")
    void anImageWithSketchesSaysNothingAboutThem() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.write(dataDir.resolve("segment_0.tfs"), new byte[48]);
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        RestoreResult result = new SnapshotRestorer().restore(new RestoreOptions(
            snapshotDir, tempDir.resolve("restored"), false, VerifyDepth.CHECKSUM));

        assertThat(tempDir.resolve("restored/segment_0.tfs")).exists();
        assertThat(result.rebuildRequired())
            .noneSatisfy(line -> assertThat(line).contains("term sketches"));
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
