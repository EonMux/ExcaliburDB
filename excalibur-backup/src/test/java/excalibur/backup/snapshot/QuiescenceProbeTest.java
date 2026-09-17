package excalibur.backup.snapshot;

import excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing in this engine holds a lock on its data directory, so a snapshot cannot ask
 * whether a node is running. What it can do is notice that one is.
 *
 * <p>The probe fingerprints the tree, and the snapshot compares the fingerprint taken before
 * the copy with one taken after it. That cannot prove the node was stopped - a node that
 * happened to write nothing during the copy is indistinguishable from one that was not
 * there - but it is a positive test for the failure that matters, which is an image torn by
 * a writer that was running after all.
 */
@DisplayName("Noticing that a data directory changed underneath a snapshot")
class QuiescenceProbeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an unchanged tree produces no differences")
    void unchangedTreeIsQuiet() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));

        DirectoryFingerprint before = QuiescenceProbe.fingerprint(dataDir);
        DirectoryFingerprint after = QuiescenceProbe.fingerprint(dataDir);

        assertThat(QuiescenceProbe.differences(before, after)).isEmpty();
    }

    @Test
    @DisplayName("a file that grew is reported")
    void growthIsReported() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        DirectoryFingerprint before = QuiescenceProbe.fingerprint(dataDir);
        Files.write(dataDir.resolve("segment_0.dat"), new byte[16384]);

        assertThat(QuiescenceProbe.differences(before, QuiescenceProbe.fingerprint(dataDir)))
            .anySatisfy(difference -> assertThat(difference).contains("segment_0.dat"));
    }

    @Test
    @DisplayName("a file rewritten to the same length is reported by its timestamp")
    void inPlaceRewriteIsReported() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        DirectoryFingerprint before = QuiescenceProbe.fingerprint(dataDir);
        Path victim = dataDir.resolve("segment_0.dat");
        Files.setLastModifiedTime(victim,
            FileTime.fromMillis(Files.getLastModifiedTime(victim).toMillis() + 5_000L));

        assertThat(QuiescenceProbe.differences(before, QuiescenceProbe.fingerprint(dataDir)))
            .anySatisfy(difference -> assertThat(difference).contains("segment_0.dat"));
    }

    @Test
    @DisplayName("a file that appeared is reported")
    void appearanceIsReported() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        DirectoryFingerprint before = QuiescenceProbe.fingerprint(dataDir);
        DataDirectoryFixture.writeRandom(dataDir.resolve("segment_5.dat"), 64, 99L);

        assertThat(QuiescenceProbe.differences(before, QuiescenceProbe.fingerprint(dataDir)))
            .anySatisfy(difference -> assertThat(difference).contains("segment_5.dat"));
    }

    @Test
    @DisplayName("a file that vanished is reported")
    void disappearanceIsReported() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        DirectoryFingerprint before = QuiescenceProbe.fingerprint(dataDir);
        Files.delete(dataDir.resolve("routing.dir"));

        assertThat(QuiescenceProbe.differences(before, QuiescenceProbe.fingerprint(dataDir)))
            .anySatisfy(difference -> assertThat(difference).contains("routing.dir"));
    }
}
