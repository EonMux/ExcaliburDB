package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.backup.manifest.EpochSource;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The epoch a snapshot is coherent at.
 *
 * <p>This engine keeps no durable epoch counter: the number a node resumes at is recomputed
 * on open from the highest epoch stamped on anything in the directory - the catalog's
 * creation and tombstone epochs and the lexical index manifest's latest published epoch.
 * A snapshot reads the same stamps from the same files, so the epoch it records is the
 * epoch a node opening that image would resume from, rather than a number invented for the
 * manifest.
 */
@DisplayName("Reading the epoch a data directory is coherent at")
class SnapshotEpochTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a directory with no stamps at all reports epoch zero")
    void emptyDirectoryHasNoEpoch() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        EpochStamp stamp = DurableEpoch.read(dataDir);

        assertThat(stamp.epoch()).isZero();
        assertThat(stamp.source()).isEqualTo(EpochSource.NOTHING_STAMPED);
        assertThat(stamp.detail()).isNotBlank();
    }

    @Test
    @DisplayName("a real engine's catalog is read for its stamps")
    void catalogIsRead() throws IOException {
        Path dataDir = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("data"), 20);

        EpochStamp stamp = DurableEpoch.read(dataDir);

        assertThat(stamp.epoch()).isGreaterThanOrEqualTo(0L);
        assertThat(stamp.source()).isIn(EpochSource.CATALOG_STAMPS,
            EpochSource.CATALOG_AND_LEXICAL_STAMPS, EpochSource.NOTHING_STAMPED);
    }

    @Test
    @DisplayName("the epoch reaches the manifest")
    void epochReachesTheManifest() throws IOException {
        Path dataDir = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("data"), 20);
        Path snapshotDir = tempDir.resolve("snap");

        SnapshotResult result = new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false));

        assertThat(result.manifest().epoch()).isEqualTo(DurableEpoch.read(dataDir).epoch());
        assertThat(result.manifest().epochSource()).isNotNull();
    }

    @Test
    @DisplayName("a catalog that cannot be read is reported rather than assumed to be zero")
    void unreadableCatalogIsReported() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Files.write(dataDir.resolve("catalog.dat"), new byte[] { 1, 2, 3, 4 });

        EpochStamp stamp = DurableEpoch.read(dataDir);

        assertThat(stamp.source()).isEqualTo(EpochSource.UNREADABLE);
        assertThat(stamp.detail()).isNotBlank();
    }
}
