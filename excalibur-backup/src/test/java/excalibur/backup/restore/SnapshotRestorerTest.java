package excalibur.backup.restore;

import excalibur.backup.manifest.ManifestCodec;
import excalibur.backup.snapshot.SnapshotLayout;
import excalibur.backup.snapshot.SnapshotOptions;
import excalibur.backup.snapshot.SnapshotWriter;
import excalibur.backup.testing.DataDirectoryFixture;
import excalibur.backup.verify.VerifyDepth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
