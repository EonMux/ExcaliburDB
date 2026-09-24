package com.eonmux.excalibur.backup.restore;

import com.eonmux.excalibur.backup.io.FileDigest;
import com.eonmux.excalibur.backup.io.FileDigests;
import com.eonmux.excalibur.backup.manifest.FileEntry;
import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.manifest.SnapshotManifest;
import com.eonmux.excalibur.backup.snapshot.ClassifiedFile;
import com.eonmux.excalibur.backup.snapshot.DataDirectoryInventory;
import com.eonmux.excalibur.backup.snapshot.QuiescenceProbe;
import com.eonmux.excalibur.backup.snapshot.SnapshotLayout;
import com.eonmux.excalibur.backup.snapshot.SnapshotOptions;
import com.eonmux.excalibur.backup.snapshot.SnapshotResult;
import com.eonmux.excalibur.backup.snapshot.SnapshotWriter;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import com.eonmux.excalibur.backup.verify.VerifyDepth;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A data directory that is the root of a mounted filesystem.
 *
 * <p>An ext4 volume mounted at the data directory holds the {@code lost+found} {@code mkfs} made,
 * owned by root and unreadable to the node's user, which no one but root can move. It is the
 * filesystem's, not the store's: a snapshot must be taken without opening it, a restore must
 * treat a target holding only it as empty and must never try to move it, and an image carries
 * none, so one taken on one volume restores onto another - or onto a plain directory - leaving
 * each target's own {@code lost+found} where it was.</p>
 *
 * <p>Root ownership is stood in for by permissions: a directory with no permissions at all can be
 * neither listed nor moved to another parent by its owner, which is the refusal a real
 * {@code lost+found} gives. Where the tests run as a user who can do anything, the checks that
 * depend on it are skipped.</p>
 */
@DisplayName("A data directory that is a mounted volume's root")
class MountedVolumeTest {

    private static final Set<PosixFilePermission> NONE = PosixFilePermissions.fromString("---------");

    private static final Set<PosixFilePermission> OWNER = PosixFilePermissions.fromString("rwx------");

    /** What the filesystem put in its lost+found, which must survive everything here. */
    private static final String RECOVERED = "#131073";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a snapshot is taken without opening an unreadable lost+found, and carries none")
    void aSnapshotPassesOverAnUnreadableLostAndFound() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path lostAndFound = lostAndFoundIn(dataDir);
        Path snapshotDir = tempDir.resolve("snap");

        SnapshotResult result;
        try (Unreadable ignored = unreadable(lostAndFound)) {
            result = new SnapshotWriter()
                .take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        }

        assertThat(result.manifest().files()).extracting(FileEntry::path)
            .noneMatch(path -> path.startsWith("lost+found"));
        assertThat(result.skipped()).extracting(ClassifiedFile::path)
            .as("not left out as though it were data; not considered at all")
            .noneMatch(path -> path.startsWith("lost+found"));
        assertThat(snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY).resolve("lost+found"))
            .doesNotExist();
    }

    @Test
    @DisplayName("a readable lost+found is not in the inventory or the fingerprint either")
    void aReadableLostAndFoundIsNotData() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path lostAndFound = lostAndFoundIn(dataDir);

        assertThat(DataDirectoryInventory.of(dataDir)).extracting(ClassifiedFile::path)
            .noneMatch(path -> path.startsWith("lost+found"));
        var before = QuiescenceProbe.fingerprint(dataDir);
        Files.writeString(lostAndFound.resolve("#131074"), "e2fsck reattached another");
        assertThat(QuiescenceProbe.differences(before, QuiescenceProbe.fingerprint(dataDir)))
            .as("what the filesystem does in its own directory does not tear an image of the "
                + "store")
            .isEmpty();
    }

    @Test
    @DisplayName("a regular file named lost+found is not the filesystem's, and is kept")
    void aFileNamedLostAndFoundIsKept() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.writeString(dataDir.resolve("lost+found"), "not a directory");

        assertThat(DataDirectoryInventory.of(dataDir)).extracting(ClassifiedFile::path)
            .contains("lost+found");
    }

    @Test
    @DisplayName("a snapshot may go to a freshly mounted volume holding only its lost+found")
    void aSnapshotGoesToAFreshVolume() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path destination = Files.createDirectories(tempDir.resolve("backup-volume"));
        Path lostAndFound = lostAndFoundIn(destination);

        try (Unreadable ignored = unreadable(lostAndFound)) {
            new SnapshotWriter().take(new SnapshotOptions(dataDir, destination, true, false));
        }

        assertThat(destination.resolve(ManifestCodec.FILE_NAME)).exists();
        assertThat(lostAndFound.resolve(RECOVERED)).exists();
        assertThat(new com.eonmux.excalibur.backup.verify.SnapshotVerifier()
            .verify(destination, VerifyDepth.CHECKSUM).isValid()).isTrue();
    }

    @Test
    @DisplayName("without --force, a target holding only lost+found and a lock is empty")
    void aFreshVolumeIsRestoredWithoutForce() throws IOException {
        Path snapshotDir = snapshot();
        Path target = Files.createDirectories(tempDir.resolve("volume"));
        Path lostAndFound = lostAndFoundIn(target);
        DataDirectoryLock.acquire(target, "excalibur node").close();
        RecordingMover mover = new RecordingMover();

        try (Unreadable ignored = unreadable(lostAndFound)) {
            new SnapshotRestorer(mover)
                .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));
            assertThat(Files.getPosixFilePermissions(lostAndFound, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .as("left exactly as the filesystem made it").isEqualTo(NONE);
        }

        assertThat(mover.moved).noneMatch(MountedVolumeTest::isLostAndFound);
        assertThat(dataOf(target)).isEqualTo(imageOf(snapshotDir));
        assertThat(lostAndFound.resolve(RECOVERED)).exists();
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("with --force, the old data is replaced and lost+found is never moved")
    void anOccupiedVolumeIsReplacedWithForce() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedVolume();
        Path lostAndFound = target.resolve("lost+found");
        RecordingMover mover = new RecordingMover();

        try (Unreadable ignored = unreadable(lostAndFound)) {
            assertThatThrownBy(() -> new SnapshotRestorer(mover)
                .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM)))
                .as("the stale data is still data, so --force is still required for it")
                .isInstanceOf(RestoreRefusedException.class)
                .extracting(e -> ((RestoreRefusedException) e).reason())
                .isEqualTo(RestoreRefusedException.Reason.TARGET_NOT_EMPTY);

            new SnapshotRestorer(mover)
                .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM));
        }

        assertThat(mover.moved).isNotEmpty().noneMatch(MountedVolumeTest::isLostAndFound);
        assertThat(dataOf(target)).isEqualTo(imageOf(snapshotDir));
        assertThat(lostAndFound.resolve(RECOVERED)).exists();
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("an unwound restore puts the old data back and leaves lost+found where it was")
    void anUnwoundRestoreLeavesLostAndFound() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedVolume();
        Path lostAndFound = target.resolve("lost+found");
        Map<String, String> before = dataOf(target);
        RecordingMover mover = new RecordingMover("routing.dir");

        try (Unreadable ignored = unreadable(lostAndFound)) {
            assertThatThrownBy(() -> new SnapshotRestorer(mover)
                .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("routing.dir will not move")
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        }

        assertThat(mover.moved).noneMatch(MountedVolumeTest::isLostAndFound);
        assertThat(dataOf(target)).isEqualTo(before);
        assertThat(lostAndFound.resolve(RECOVERED)).exists();
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("a restore interrupted mid-publish is finished without moving lost+found")
    void anInterruptedRestoreIsFinishedAroundLostAndFound() throws IOException {
        Path snapshotDir = snapshot();
        Path target = occupiedVolume();
        Path lostAndFound = target.resolve("lost+found");
        RecordingMover dying = new RecordingMover();
        dying.dieAfter = 1;
        RecordingMover mover = new RecordingMover();

        try (Unreadable ignored = unreadable(lostAndFound)) {
            assertThatThrownBy(() -> new SnapshotRestorer(dying)
                .restore(new RestoreOptions(snapshotDir, target, true, VerifyDepth.CHECKSUM)))
                .isInstanceOf(Died.class);
            assertThat(InterruptedRestore.journalIn(target)).isPresent();

            new SnapshotRestorer(mover)
                .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));
        }

        assertThat(dying.moved).noneMatch(MountedVolumeTest::isLostAndFound);
        assertThat(mover.moved).noneMatch(MountedVolumeTest::isLostAndFound);
        assertThat(dataOf(target)).isEqualTo(imageOf(snapshotDir));
        assertThat(lostAndFound.resolve(RECOVERED)).exists();
        assertThat(restoreEntriesIn(target)).isEmpty();
    }

    @Test
    @DisplayName("an image that carries a lost+found restores without it, over the target's own")
    void anImageCarryingLostAndFoundRestoresWithoutIt() throws IOException {
        Path snapshotDir = imageCarryingLostAndFound();
        Path target = Files.createDirectories(tempDir.resolve("volume"));
        Path lostAndFound = lostAndFoundIn(target);

        RestoreResult result;
        try (Unreadable ignored = unreadable(lostAndFound)) {
            result = new SnapshotRestorer()
                .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));
        }

        assertThat(result.filesRestored()).isEqualTo(result.manifest().files().size() - 1);
        assertThat(lostAndFound.resolve("#1")).as("the image's was not written").doesNotExist();
        assertThat(lostAndFound.resolve(RECOVERED)).as("the target's was not touched").exists();
        Map<String, String> expected = new TreeMap<>(imageOf(snapshotDir));
        expected.keySet().removeIf(path -> path.startsWith("lost+found/"));
        assertThat(dataOf(target)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an image from a volume restores onto a plain directory with no lost+found")
    void anImageFromAVolumeRestoresOntoAPlainDirectory() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        lostAndFoundIn(dataDir);
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        Path target = tempDir.resolve("plain");

        new SnapshotRestorer()
            .restore(new RestoreOptions(snapshotDir, target, false, VerifyDepth.CHECKSUM));

        assertThat(target.resolve("lost+found"))
            .as("a filesystem's directory is the filesystem's business, not the image's")
            .doesNotExist();
        assertThat(DataDirectoryFixture.digestTree(target)).isEqualTo(imageOf(snapshotDir));
    }

    private Path snapshot() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        return snapshotDir;
    }

    /**
     * Builds the image an earlier build could have taken of a volume whose lost+found it could
     * read: this build's, with a recovered file under lost+found in the payload and the manifest.
     *
     * @return The image
     * @throws IOException if it cannot be written
     */
    private Path imageCarryingLostAndFound() throws IOException {
        Path snapshotDir = snapshot();
        Path recovered = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
            .resolve("lost+found/#1");
        DataDirectoryFixture.writeRandom(recovered, 512, 71L);
        FileDigest digest = FileDigests.of(recovered);
        Path manifestFile = snapshotDir.resolve(ManifestCodec.FILE_NAME);
        SnapshotManifest manifest = ManifestCodec.read(manifestFile);
        List<FileEntry> files = new ArrayList<>(manifest.files());
        files.add(new FileEntry("lost+found/#1", digest.size(), digest.crc32c(),
            digest.sha256()));
        files.sort(Comparator.comparing(FileEntry::path));
        ManifestCodec.write(new SnapshotManifest(manifest.manifestVersion(),
            manifest.snapshotId(), manifest.createdAt(), manifest.producer(),
            manifest.sourceDataDir(), manifest.epoch(), manifest.epochSource(),
            manifest.consistency(), manifest.formatVersions(), files), manifestFile);
        return snapshotDir;
    }

    /**
     * Makes the lost+found a filesystem gives its root, with one recovered file in it.
     *
     * @param root The root
     * @return The directory
     * @throws IOException if it cannot be made
     */
    private static Path lostAndFoundIn(Path root) throws IOException {
        Path lostAndFound = Files.createDirectories(root.resolve("lost+found"));
        DataDirectoryFixture.writeRandom(lostAndFound.resolve(RECOVERED), 256, 70L);
        return lostAndFound;
    }

    private Path occupiedVolume() throws IOException {
        Path target = Files.createDirectories(tempDir.resolve("volume"));
        lostAndFoundIn(target);
        DataDirectoryFixture.writeRandom(target.resolve("catalog.dat"), 4096, 903L);
        DataDirectoryFixture.writeRandom(target.resolve("nested/segment_0.dat"), 2048, 904L);
        DataDirectoryFixture.writeRandom(target.resolve("zz-last"), 64, 905L);
        return target;
    }

    /**
     * Takes every permission away from a directory until closed.
     *
     * @param directory The directory
     * @return What gives them back
     * @throws IOException if the permissions cannot be changed
     */
    private static Unreadable unreadable(Path directory) throws IOException {
        Files.setPosixFilePermissions(directory, NONE);
        Unreadable restore = () -> Files.setPosixFilePermissions(directory, OWNER);
        if (Files.isReadable(directory)) {
            restore.close();
            assumeFalse(true, "running as a user who can read anything");
        }
        return restore;
    }

    /** Gives a directory its permissions back. */
    @FunctionalInterface
    private interface Unreadable extends AutoCloseable {

        @Override
        void close() throws IOException;
    }

    /**
     * What a target holds, less its lock and the filesystem's directory.
     *
     * @param target The target
     * @return Its files' digests
     * @throws IOException if it cannot be read
     */
    private static Map<String, String> dataOf(Path target) throws IOException {
        Map<String, String> digests = new TreeMap<>(DataDirectoryFixture.digestTree(target));
        digests.keySet().removeIf(path -> path.startsWith("lost+found/"));
        return digests;
    }

    private static Map<String, String> imageOf(Path snapshotDir) throws IOException {
        return new TreeMap<>(DataDirectoryFixture.digestTree(
            snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)));
    }

    private static List<String> restoreEntriesIn(Path target) throws IOException {
        try (var entries = Files.list(target)) {
            return entries.map(entry -> entry.getFileName().toString())
                .filter(InterruptedRestore::isRestoreEntry).toList();
        }
    }

    private static boolean isLostAndFound(Path source) {
        return source.getFileName().toString().equals("lost+found");
    }

    /** Stands in for the process dying: nothing after it runs. */
    private static final class Died extends Error {

        private static final long serialVersionUID = 1L;

        Died() {
            super("the process died here");
        }
    }

    /**
     * Performs every rename for real and records what it was asked to move; refuses one entry
     * on its way into the target, or dies after a number of renames, when told to.
     */
    private static final class RecordingMover implements SnapshotRestorer.EntryMover {

        private final List<Path> moved = new ArrayList<>();

        private final String refusedOnInstall;

        private int dieAfter = -1;

        RecordingMover() {
            this(null);
        }

        RecordingMover(String refusedOnInstall) {
            this.refusedOnInstall = refusedOnInstall;
        }

        @Override
        public void move(Path source, Path destination) throws IOException {
            if (dieAfter == moved.size()) {
                throw new Died();
            }
            moved.add(source);
            boolean installing = source.getParent().getFileName().toString()
                .startsWith(InterruptedRestore.STAGING_PREFIX);
            if (installing && source.getFileName().toString().equals(refusedOnInstall)) {
                throw new IOException(refusedOnInstall + " will not move");
            }
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
