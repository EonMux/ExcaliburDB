package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.backup.manifest.Consistency;
import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A snapshot and the lock a node holds on its data directory.
 *
 * <p>The before-and-after fingerprint can only prove that something wrote during the copy.
 * The lock answers the question it cannot - is a node running at all - before a byte is
 * copied, and holding it for the copy keeps a node from starting halfway through.</p>
 */
@DisplayName("Snapshots and the data directory lock")
class SnapshotLockTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a directory a node holds is refused before anything is written")
    void aHeldDirectoryIsRefused() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDir, "excalibur node")) {
            assertThatThrownBy(() -> new SnapshotWriter()
                .take(new SnapshotOptions(dataDir, snapshotDir, true, false)))
                .isInstanceOf(SnapshotRefusedException.class)
                .hasMessageContaining(dataDir.toString())
                .hasMessageContaining("excalibur node")
                .hasMessageContaining("--allow-live")
                .extracting(e -> ((SnapshotRefusedException) e).reason())
                .isEqualTo(SnapshotRefusedException.Reason.SOURCE_IN_USE);
        }
        assertThat(snapshotDir).doesNotExist();
    }

    @Test
    @DisplayName("--allow-live copies a held directory and records the image as live")
    void allowLiveCopiesAHeldDirectory() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        SnapshotResult result;
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDir, "excalibur node")) {
            result = new SnapshotWriter()
                .take(new SnapshotOptions(dataDir, snapshotDir, true, true));
        }
        assertThat(result.manifest().consistency()).isEqualTo(Consistency.LIVE_UNSAFE);
    }

    @Test
    @DisplayName("the lock is held for the copy and released after it")
    void theLockIsHeldForTheCopy() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        boolean[] heldDuringCopy = new boolean[1];
        SnapshotWriter writer = new SnapshotWriter(java.time.Clock.systemUTC(), () -> {
            try {
                heldDuringCopy[0] = DataDirectoryLock.probe(dataDir).isPresent();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return "00000000-0000-0000-0000-000000000001";
        });

        SnapshotResult result = writer.take(
            new SnapshotOptions(dataDir, tempDir.resolve("snap"), true, false));

        assertThat(heldDuringCopy[0]).isTrue();
        assertThat(DataDirectoryLock.probe(dataDir)).isEmpty();
        assertThat(result.manifest().consistency()).isEqualTo(Consistency.OFFLINE);
    }

    @Test
    @DisplayName("the lock file is neither copied nor reported as omitted")
    void theLockFileIsNotData() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.writeString(dataDir.resolve(DataDirectoryLock.FILE_NAME), "pid=1\n");
        Path snapshotDir = tempDir.resolve("snap");

        SnapshotResult result = new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        assertThat(snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY)
            .resolve(DataDirectoryLock.FILE_NAME)).doesNotExist();
        assertThat(ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME)).files())
            .noneMatch(entry -> entry.path().equals(DataDirectoryLock.FILE_NAME));
        assertThat(result.skipped())
            .noneMatch(file -> file.path().equals(DataDirectoryLock.FILE_NAME));
    }

    @Test
    @DisplayName("the inventory leaves out the lock and a restore's scratch")
    void theInventoryLeavesOutReservedEntries() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.writeString(dataDir.resolve(DataDirectoryLock.FILE_NAME), "pid=1\n");
        Path scratch = Files.createDirectories(
            dataDir.resolve(InterruptedRestore.DISPLACED_PREFIX + "x"));
        Files.writeString(scratch.resolve("catalog.dat"), "old");

        assertThat(DataDirectoryInventory.of(dataDir))
            .extracting(ClassifiedFile::path)
            .isNotEmpty()
            .noneMatch(path -> path.equals(DataDirectoryLock.FILE_NAME))
            .noneMatch(path -> path.startsWith(InterruptedRestore.DISPLACED_PREFIX));
    }

    @Test
    @DisplayName("a directory a restore stopped part-way through is refused")
    void anInterruptedRestoreIsRefused() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Files.writeString(dataDir.resolve(InterruptedRestore.JOURNAL_FILE_NAME), "journal");
        Path snapshotDir = tempDir.resolve("snap");

        assertThatThrownBy(() -> new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("excalibur-backup restore");
        assertThat(snapshotDir).doesNotExist();
        assertThat(DataDirectoryLock.probe(dataDir)).isEmpty();
    }
}
