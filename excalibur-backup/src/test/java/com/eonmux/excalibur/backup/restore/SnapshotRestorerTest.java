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
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.DataDirectoryLockedException;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * What a restore must refuse.
 *
 * <p>A restore writes over a data directory, so every one of its refusals is load-bearing.
 * It must not begin from an image it cannot prove intact, because the failure would be
 * discovered halfway through with the original already gone; it must not overwrite a
 * directory that holds anything, because the operator who typed the wrong target has no
 * second chance; and when it does refuse, the target must be exactly as it was.
 *
 * <p>And it must work where data directories actually live. The target is replaced in place,
 * never renamed, so a mount point is restored like any other directory; and because that
 * replacement is a sequence of renames rather than one, every way the sequence can stop - an
 * entry that will not move, an unwinding that fails too, the process dying between two renames
 * - is driven here and shown to end in the image or in what was there before, never in a
 * mixture a node would start on.</p>
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
            .as("a restore leaves the snapshot's tree, not a merge of the two").doesNotExist();
        assertThat(target.resolve("catalog.dat")).exists();
    }

    @Test
    @DisplayName("restoring leaves no journal or scratch in the target, and nothing beside it")
    void restoreLeavesNoScratch() throws IOException {
        Path snapshotDir = snapshot();
        Path parent = Files.createDirectories(tempDir.resolve("volume"));
        Path target = parent.resolve("restored");

        RestoreResult result = new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(result.recovery()).isEmpty();
        assertThat(namesIn(target))
            .noneMatch(name -> InterruptedRestore.isRestoreEntry(name)
                && !name.equals(DataDirectoryLock.FILE_NAME));
        assertThat(namesIn(parent)).containsExactly("restored");
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
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
    @DisplayName("a target whose parent cannot be written - a mount point - is restored in place")
    void aMountPointIsRestoredInPlace() throws IOException {
        Path snapshotDir = snapshot();
        Path parent = Files.createDirectories(tempDir.resolve("root-owned"));
        Path target = Files.createDirectories(parent.resolve("var-lib-excalibur"));
        DataDirectoryFixture.writeRandom(target.resolve("stale-file"), 4096, 906L);
        DataDirectoryFixture.writeRandom(target.resolve("nested/stale"), 1024, 907L);
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(parent);
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(parent), "running as a user who can write anywhere");

            new SnapshotRestorer()
                .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM));
        } finally {
            Files.setPosixFilePermissions(parent, writable);
        }

        assertThat(DataDirectoryFixture.digestTree(target))
            .as("the target's name never moved, and it now holds exactly the image")
            .isEqualTo(imageOf(snapshotDir));
        assertThat(target.resolve("nested")).doesNotExist();
    }

    @Test
    @DisplayName("a target holding only the lock file a stopped node left counts as empty")
    void aTargetHoldingOnlyALockFileIsEmpty() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("stopped-node"));
        DataDirectoryLock.acquire(target, "excalibur node").close();

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
    }

    @Test
    @DisplayName("a target another holder has locked is refused, and left as it was")
    void aHeldTargetIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 908L);
        Map<String, String> before = DataDirectoryFixture.digestTree(target);

        try (DataDirectoryLock held = DataDirectoryLock.acquire(target, "excalibur node")) {
            assertThatThrownBy(() -> new SnapshotRestorer()
                .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
                .isInstanceOf(RestoreRefusedException.class)
                .hasMessageContaining(target.toString())
                .hasMessageContaining("Nothing was restored")
                .extracting(e -> ((RestoreRefusedException) e).reason())
                .isEqualTo(RestoreRefusedException.Reason.TARGET_IN_USE);
        }
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(before);
    }

    @Test
    @DisplayName("the target is locked while the restore writes it")
    void theTargetIsLockedWhileRestoring() throws IOException {
        Path snapshotDir = snapshot();
        Path target = tempDir.resolve("restored");
        List<Boolean> lockedDuringInstall = new ArrayList<>();
        SnapshotRestorer restorer = new SnapshotRestorer((source, destination) -> {
            lockedDuringInstall.add(isHeld(target));
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        });

        restorer.restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(lockedDuringInstall).isNotEmpty().containsOnly(true);
        assertThat(isHeld(target)).as("and released once it is done").isFalse();
    }

    @Test
    @DisplayName("an entry that will not move in is unwound, putting the target back")
    void aFailedInstallIsUnwound() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        Map<String, String> before = DataDirectoryFixture.digestTree(target);
        SnapshotRestorer restorer = new SnapshotRestorer(failing(Step.INSTALL, "routing.dir"));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("routing.dir will not move")
            .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());

        assertThat(DataDirectoryFixture.digestTree(target))
            .as("the entries already installed went back, and the displaced ones returned")
            .isEqualTo(before);
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("an entry that will not move aside is unwound, putting the target back")
    void aFailedDisplaceIsUnwound() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        Map<String, String> before = DataDirectoryFixture.digestTree(target);
        SnapshotRestorer restorer = new SnapshotRestorer(failing(Step.DISPLACE, "nested"));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("nested will not move");

        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(before);
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("a failed restore into a target it created removes the target again")
    void aFailedRestoreRemovesTheTargetItCreated() throws IOException {
        Path snapshotDir = snapshot();
        Path parent = Files.createDirectories(tempDir.resolve("volume"));
        Path target = parent.resolve("fresh");
        SnapshotRestorer restorer = new SnapshotRestorer(failing(Step.INSTALL, "routing.dir"));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class);

        assertThat(namesIn(parent)).isEmpty();
    }

    @Test
    @DisplayName("an unwinding that fails too leaves the journal, and the next restore finishes it")
    void aFailedUnwindingIsFinishedByTheNextRestore() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        Map<String, String> before = DataDirectoryFixture.digestTree(target);
        SnapshotRestorer restorer = new SnapshotRestorer(
            failing(Step.INSTALL, "routing.dir").or(failing(Step.UNINSTALL, "catalog.dat")));

        assertThatThrownBy(() -> restorer
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .satisfies(failure -> assertThat(failure.getSuppressed())
                .anySatisfy(suppressed -> assertThat(suppressed)
                    .hasMessageContaining("could not be unwound")
                    .hasMessageContaining("excalibur-backup restore")));
        assertThatThrownBy(() -> InterruptedRestore.requireNoneIn(target))
            .as("a node refuses the directory while it is a mixture")
            .isInstanceOf(IOException.class);

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
            .as("the rerun finishes the unwinding, and then, without --force, refuses to "
                + "replace what it put back")
            .isInstanceOf(RestoreRefusedException.class)
            .extracting(e -> ((RestoreRefusedException) e).reason())
            .isEqualTo(RestoreRefusedException.Reason.TARGET_NOT_EMPTY);
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(before);
        assertThat(restoreEntriesIn(target)).isEmpty();
        InterruptedRestore.requireNoneIn(target);
    }

    @Test
    @DisplayName("a crash while installing is carried forward to the image by the next restore")
    void aCrashWhileInstallingIsCarriedForward() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        SnapshotRestorer crashing = new SnapshotRestorer(crashing(Step.INSTALL, 3));

        assertThatThrownBy(() -> crashing
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(Crash.class);
        assertThat(RestoreJournal.read(target)).hasValueSatisfying(journal ->
            assertThat(journal.phase()).isEqualTo(RestoreJournal.Phase.INSTALLING));
        assertThatThrownBy(() -> InterruptedRestore.requireNoneIn(target))
            .isInstanceOf(IOException.class);

        RestoreResult rerun = new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(rerun.recovery()).hasValueSatisfying(recovery ->
            assertThat(recovery).contains("completed"));
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
        assertThat(restoreEntriesIn(target)).isEmpty();
        InterruptedRestore.requireNoneIn(target);
    }

    @Test
    @DisplayName("a crash while moving the old entries aside is carried forward too")
    void aCrashWhileDisplacingIsCarriedForward() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        SnapshotRestorer crashing = new SnapshotRestorer(crashing(Step.DISPLACE, 1));

        assertThatThrownBy(() -> crashing
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(Crash.class);

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("a crash while unwinding is unwound by the next restore")
    void aCrashWhileUnwindingIsUnwound() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        Map<String, String> before = DataDirectoryFixture.digestTree(target);
        SnapshotRestorer crashing = new SnapshotRestorer(
            failing(Step.INSTALL, "routing.dir").or(crashing(Step.UNDISPLACE, 1)));

        assertThatThrownBy(() -> crashing
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(Crash.class);
        assertThat(RestoreJournal.read(target)).hasValueSatisfying(journal ->
            assertThat(journal.phase()).isEqualTo(RestoreJournal.Phase.UNDISPLACING));

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM));

        assertThat(DataDirectoryFixture.digestTree(target))
            .as("unwound first, then restored again since --force was given")
            .isEqualTo(imageOf(snapshotDir))
            .isNotEqualTo(before);
    }

    @Test
    @DisplayName("a restore of another image finishes the interrupted one, then its own")
    void anInterruptedRestoreOfAnotherImageIsFinishedFirst() throws IOException {
        Path snapshotDir = snapshot();
        Path otherData = Files.createDirectories(tempDir.resolve("other-data"));
        DataDirectoryFixture.writeRandom(otherData.resolve("catalog.dat"), 2048, 909L);
        Path otherSnapshot = tempDir.resolve("other-snap");
        new SnapshotWriter().take(new SnapshotOptions(otherData, otherSnapshot, true, false));
        Path target = occupiedTarget();
        assertThatThrownBy(() -> new SnapshotRestorer(crashing(Step.INSTALL, 2))
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(Crash.class);

        RestoreResult rerun = new SnapshotRestorer()
            .restore(new RestoreOptions(otherSnapshot, target, true, VerifyDepth.CHECKSUM));

        String interrupted = ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME))
            .snapshotId();
        assertThat(rerun.recovery()).hasValueSatisfying(recovery -> assertThat(recovery)
            .contains(interrupted).contains("completed"));
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(otherSnapshot));
    }

    @Test
    @DisplayName("a restore refused after finishing an interrupted one still reports the recovery")
    void aRecoveryIsReportedWhenTheRestoreAfterItIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Path otherData = Files.createDirectories(tempDir.resolve("other-data"));
        DataDirectoryFixture.writeRandom(otherData.resolve("catalog.dat"), 2048, 911L);
        Path otherSnapshot = tempDir.resolve("other-snap");
        new SnapshotWriter().take(new SnapshotOptions(otherData, otherSnapshot, true, false));
        Path target = occupiedTarget();
        assertThatThrownBy(() -> new SnapshotRestorer(crashing(Step.INSTALL, 2))
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(Crash.class);
        List<String> reported = new ArrayList<>();

        assertThatThrownBy(() -> new SnapshotRestorer().restore(
            new RestoreOptions(otherSnapshot, target, false, VerifyDepth.CHECKSUM),
            reported::add))
            .as("without --force the finished image is an occupied target")
            .isInstanceOf(RestoreRefusedException.class);

        String interrupted = ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME))
            .snapshotId();
        assertThat(reported).singleElement().satisfies(recovery -> assertThat(recovery)
            .contains(interrupted).contains("completed"));
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
    }

    @Test
    @DisplayName("a journal left while the image was still being staged is discarded")
    void aStagingJournalIsDiscarded() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("restored"));
        RestoreJournal journal = RestoreJournal.begin("an-earlier-image");
        journal.write(target);
        DataDirectoryFixture.writeRandom(journal.stagingIn(target).resolve("catalog.dat"), 100,
            910L);

        RestoreResult result = new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(result.recovery()).hasValueSatisfying(recovery ->
            assertThat(recovery).contains("an-earlier-image").contains("undone"));
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("a journal that cannot be read is refused, and nothing is moved")
    void anUnreadableJournalIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedTarget();
        Files.writeString(target.resolve(InterruptedRestore.JOURNAL_FILE_NAME), "garbage\n");
        Map<String, String> before = DataDirectoryFixture.digestTree(target);

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cannot be read")
            .hasMessageContaining(InterruptedRestore.DISPLACED_PREFIX);

        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(before);
    }

    @Test
    @DisplayName("a target that is a file is refused")
    void aFileTargetIsRefused() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.writeString(tempDir.resolve("a-file"), "x");

        assertThatThrownBy(() -> new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("is a file");
        assertThat(target).hasContent("x");
    }

    @Test
    @DisplayName("a journal survives a round trip, and a scratch name leaving the target is refused")
    void theJournalRoundTripsAndConfinesItsNames() {
        RestoreJournal journal = RestoreJournal.begin("snap-1")
            .at(RestoreJournal.Phase.UNINSTALLING);

        assertThat(RestoreJournal.parse(journal.render().lines().toList())).isEqualTo(journal);
        assertThatThrownBy(() -> new RestoreJournal(RestoreJournal.Phase.STAGING, "snap-1",
            InterruptedRestore.STAGING_PREFIX + "../../etc", journal.displaced()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreJournal(RestoreJournal.Phase.STAGING, "snap-1",
            "elsewhere", journal.displaced()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestoreJournal.parse(List.of(RestoreJournal.HEADER,
            "phase=STAGING")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("snapshot");
        assertThatThrownBy(() -> RestoreJournal.parse(List.of(RestoreJournal.HEADER, "junk")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreJournal(RestoreJournal.Phase.STAGING, " ",
            journal.staging(), journal.displaced()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Creates a target that holds a data directory of its own, a nested one included.
     *
     * @return The target
     * @throws IOException if it cannot be written
     */
    private Path occupiedTarget() throws IOException {
        Path target = Files.createDirectories(tempDir.resolve("live"));
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 903L);
        DataDirectoryFixture.writeRandom(target.resolve("nested/segment_0.dat"), 2048, 904L);
        DataDirectoryFixture.writeRandom(target.resolve("zz-last"), 64, 905L);
        return target;
    }

    /**
     * Returns what a restore of an image must produce: its payload, byte for byte.
     *
     * @param snapshotDir The image
     * @return The payload's digests
     * @throws IOException if it cannot be read
     */
    private static Map<String, String> imageOf(Path snapshotDir) throws IOException {
        return DataDirectoryFixture.digestTree(
            snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY));
    }

    private static List<String> namesIn(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> restoreEntriesIn(Path target) throws IOException {
        return namesIn(target).stream().filter(InterruptedRestore::isRestoreEntry).toList();
    }

    /**
     * Whether some holder has the directory's lock, tested from a second process's view.
     *
     * @param directory The directory
     * @return True if the lock is held
     * @throws IOException if it cannot be probed
     */
    private static boolean isHeld(Path directory) throws IOException {
        try {
            DataDirectoryLock.acquire(directory, "probe").close();
            return false;
        } catch (DataDirectoryLockedException e) {
            return true;
        }
    }

    /** Which of the publish's renames a mover is looking at. */
    private enum Step {

        /** A previous entry of the target, moving into the displaced directory. */
        DISPLACE,

        /** A staged entry, moving into the target. */
        INSTALL,

        /** An installed entry, moving back into the staging directory. */
        UNINSTALL,

        /** A displaced entry, moving back into the target. */
        UNDISPLACE;

        static Step of(Path source, Path destination) {
            String from = source.getParent().getFileName().toString();
            String to = destination.getParent().getFileName().toString();
            if (to.startsWith(InterruptedRestore.DISPLACED_PREFIX)) {
                return DISPLACE;
            }
            if (from.startsWith(InterruptedRestore.STAGING_PREFIX)) {
                return INSTALL;
            }
            if (to.startsWith(InterruptedRestore.STAGING_PREFIX)) {
                return UNINSTALL;
            }
            return UNDISPLACE;
        }
    }

    /** Stands in for the process dying: nothing after it runs, as nothing would. */
    private static final class Crash extends Error {

        private static final long serialVersionUID = 1L;

        Crash() {
            super("the process died here");
        }
    }

    /**
     * A mover that stands in for the renames a real filesystem refuses, every other one being
     * real.
     */
    @FunctionalInterface
    private interface FaultyMover extends SnapshotRestorer.EntryMover {

        /**
         * Decides whether this rename fails, and how.
         *
         * @param step        Which rename it is
         * @param source      What is renamed
         * @param destination Its new name
         * @return True if the fault was raised
         * @throws IOException if the rename fails
         */
        boolean fault(Step step, Path source, Path destination) throws IOException;

        @Override
        default void move(Path source, Path destination) throws IOException {
            fault(Step.of(source, destination), source, destination);
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }

        default FaultyMover or(FaultyMover other) {
            return (step, source, destination) -> fault(step, source, destination)
                || other.fault(step, source, destination);
        }
    }

    /**
     * Refuses the one rename of the given entry at the given step.
     *
     * @param step  Where the refusal happens
     * @param entry The entry's name
     * @return The mover
     */
    private static FaultyMover failing(Step step, String entry) {
        return (at, source, destination) -> {
            if (at == step && source.getFileName().toString().equals(entry)) {
                throw new IOException(entry + " will not move");
            }
            return false;
        };
    }

    /**
     * Dies before the n-th rename, counting from one, of the given step.
     *
     * @param step  Where the process dies
     * @param nth   Before which of its renames
     * @return The mover
     */
    private static FaultyMover crashing(Step step, int nth) {
        int[] seen = {0};
        return (at, source, destination) -> {
            if (at == step && ++seen[0] == nth) {
                throw new Crash();
            }
            return false;
        };
    }
}
