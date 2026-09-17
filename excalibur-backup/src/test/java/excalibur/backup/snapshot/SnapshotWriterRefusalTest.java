package excalibur.backup.snapshot;

import excalibur.backup.manifest.Consistency;
import excalibur.backup.manifest.ManifestCodec;
import excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a snapshot refuses to produce, and what it leaves behind when it refuses.
 *
 * <p>An image with no manifest is worth nothing, so a refused snapshot cleans up after
 * itself rather than leaving a directory that occupies disk and makes the obvious retry
 * fail for the wrong reason.
 */
@DisplayName("Refusing to take a snapshot")
class SnapshotWriterRefusalTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a data directory that is not there is reported, not invented")
    void absentDataDirectoryIsReported() {
        assertThatThrownBy(() -> new SnapshotWriter().take(new SnapshotOptions(
            tempDir.resolve("nothing-here"), tempDir.resolve("snap"), true, false)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("no data directory");
    }

    @Test
    @DisplayName("a destination that already holds an image is refused")
    void occupiedDestinationIsRefused() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = Files.createDirectories(tempDir.resolve("snap"));
        Files.writeString(snapshotDir.resolve("something"), "in the way\n");

        assertThatThrownBy(() -> new SnapshotWriter()
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false)))
            .isInstanceOf(SnapshotRefusedException.class)
            .extracting(e -> ((SnapshotRefusedException) e).reason())
            .isEqualTo(SnapshotRefusedException.Reason.DESTINATION_NOT_EMPTY);
    }

    @Test
    @DisplayName("a snapshot cannot be written inside the directory it is copying")
    void destinationInsideTheSourceIsRejected() {
        assertThatThrownBy(() -> new SnapshotOptions(tempDir.resolve("data"),
            tempDir.resolve("data/backup"), true, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a directory that moves underneath the copy is refused")
    void movingSourceIsRefused() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        assertThatThrownBy(() -> movingSourceWriter(dataDir)
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false)))
            .isInstanceOf(SnapshotRefusedException.class)
            .extracting(e -> ((SnapshotRefusedException) e).reason())
            .isEqualTo(SnapshotRefusedException.Reason.SOURCE_CHANGED_UNDERNEATH);
    }

    @Test
    @DisplayName("a refused snapshot removes the destination it created")
    void refusalRemovesWhatItWrote() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        assertThatThrownBy(() -> movingSourceWriter(dataDir)
            .take(new SnapshotOptions(dataDir, snapshotDir, true, false)))
            .isInstanceOf(SnapshotRefusedException.class);

        assertThat(snapshotDir).doesNotExist();
    }

    @Test
    @DisplayName("--allow-live produces an image and says what it is")
    void allowLiveRecordsTheRisk() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        Path snapshotDir = tempDir.resolve("snap");

        SnapshotResult result = movingSourceWriter(dataDir)
            .take(new SnapshotOptions(dataDir, snapshotDir, true, true));

        assertThat(result.manifest().consistency()).isEqualTo(Consistency.LIVE_UNSAFE);
        assertThat(ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME))
            .consistency()).isEqualTo(Consistency.LIVE_UNSAFE);
    }

    /**
     * Returns a writer that disturbs the source while it is copying it.
     *
     * <p>Standing in for a running node, which is what the check exists to catch. The
     * disturbance is made from the identifier supplier, which the writer consults once the
     * payload is written and before it re-examines the directory - exactly the window a
     * running node would write in, and reached without a sleep or a race.</p>
     *
     * @param dataDir The directory to disturb
     * @return A writer that will find the directory moved underneath it
     */
    private static SnapshotWriter movingSourceWriter(Path dataDir) {
        return new SnapshotWriter(java.time.Clock.systemUTC(), () -> {
            try {
                Files.writeString(dataDir.resolve("segment_0.dat"), "a node is running\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return "00000000-0000-0000-0000-000000000000";
        });
    }
}
