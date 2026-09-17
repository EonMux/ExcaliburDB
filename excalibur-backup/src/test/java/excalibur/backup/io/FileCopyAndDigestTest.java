package excalibur.backup.io;

import excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two primitives everything else is built out of: digesting a file and copying one.
 *
 * <p>Both have to cope with the shape this engine's files actually have. A write-ahead log
 * is a 64 MiB mapping holding a few kilobytes; an HNSW graph is three quarters of a gigabyte
 * of pre-allocated address space holding a handful of nodes. A copier that materialises
 * those holes turns a fifty-megabyte backup into a fifty-gigabyte one, and a digest that
 * skips them describes a file that is not the file.
 */
@DisplayName("Digesting and copying data-directory files")
class FileCopyAndDigestTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a digest covers every logical byte, holes included")
    void digestCoversHoles() throws IOException {
        Path sparse = tempDir.resolve("segment_0.wal");
        DataDirectoryFixture.writeSparse(sparse, 4096, 7L);

        FileDigest digest = FileDigests.of(sparse);

        assertThat(digest.size()).isEqualTo(DataDirectoryFixture.WAL_APPARENT_SIZE);
        assertThat(digest.sha256()).isEqualTo(DataDirectoryFixture.digest(sparse));
        assertThat(digest.crc32c()).hasSize(8);
    }

    @Test
    @DisplayName("an empty file digests without complaint")
    void emptyFileDigests() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("document-registry"));

        FileDigest digest = FileDigests.of(empty);

        assertThat(digest.size()).isZero();
        assertThat(digest.sha256()).isEqualTo(DataDirectoryFixture.digest(empty));
    }

    @Test
    @DisplayName("two different files do not share a checksum")
    void differentContentDiffers() throws IOException {
        DataDirectoryFixture.writeRandom(tempDir.resolve("a"), 4096, 1L);
        DataDirectoryFixture.writeRandom(tempDir.resolve("b"), 4096, 2L);

        assertThat(FileDigests.of(tempDir.resolve("a")).crc32c())
            .isNotEqualTo(FileDigests.of(tempDir.resolve("b")).crc32c());
    }

    @Test
    @DisplayName("a copy reproduces the file exactly")
    void copyIsExact() throws IOException {
        Path source = tempDir.resolve("segment_0.dat");
        DataDirectoryFixture.writeRandom(source, 200_000, 11L);
        Path destination = tempDir.resolve("out/segment_0.dat");

        long written = SparseFileCopy.copy(source, destination);

        assertThat(written).isEqualTo(Files.size(source));
        assertThat(DataDirectoryFixture.digest(destination))
            .isEqualTo(DataDirectoryFixture.digest(source));
    }

    @Test
    @DisplayName("a copy of a sparse file stays sparse")
    void copyOfASparseFileStaysSparse() throws IOException {
        Path source = tempDir.resolve("segment_0.wal");
        DataDirectoryFixture.writeSparse(source, 4096, 12L);
        Path destination = tempDir.resolve("out/segment_0.wal");

        SparseFileCopy.copy(source, destination);

        assertThat(Files.size(destination)).isEqualTo(Files.size(source));
        assertThat(DataDirectoryFixture.digest(destination))
            .isEqualTo(DataDirectoryFixture.digest(source));
        assumeTrue(allocatedBytes(source) >= 0, "this platform does not report allocation");
        assertThat(allocatedBytes(destination))
            .as("the hole must not have been written out as zeros")
            .isLessThan(Files.size(source) / 2);
    }

    @Test
    @DisplayName("a file that ends in a hole keeps its length")
    void trailingHoleKeepsLength() throws IOException {
        Path source = tempDir.resolve("trailing");
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
            channel.position(1_000_000);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 0 }));
        }
        Path destination = tempDir.resolve("out/trailing");

        SparseFileCopy.copy(source, destination);

        assertThat(Files.size(destination)).isEqualTo(1_000_001L);
        assertThat(DataDirectoryFixture.digest(destination))
            .isEqualTo(DataDirectoryFixture.digest(source));
    }

    @Test
    @DisplayName("publishing stages, forces and renames rather than writing in place")
    void publishIsAtomic() throws IOException {
        Path target = tempDir.resolve("routing.dir");
        DataDirectoryFixture.writeRandom(target, 272, 20L);
        Path staged = tempDir.resolve("routing.dir.staged");
        DataDirectoryFixture.writeRandom(staged, 272, 21L);
        String expected = DataDirectoryFixture.digest(staged);

        AtomicPublish.publish(staged, target);

        assertThat(staged).doesNotExist();
        assertThat(DataDirectoryFixture.digest(target)).isEqualTo(expected);
    }

    @Test
    @DisplayName("forcing a tree that is not there says so rather than passing")
    void syncingAMissingTreeFails() {
        assertThatThrownBy(() -> AtomicPublish.syncTree(tempDir.resolve("absent")))
            .isInstanceOf(IOException.class);
    }

    /**
     * Returns how many bytes a file actually occupies on the device.
     *
     * <p>Java exposes no portable attribute for this, and the apparent size is precisely the
     * number that hides the property under test, so the platform is asked directly. A
     * platform that will not answer returns -1 and the assertion is skipped rather than
     * being quietly weakened into one that always passes.
     *
     * @param file The file
     * @return Allocated bytes, or -1 where the platform does not report them
     */
    private static long allocatedBytes(Path file) {
        try {
            Process process = new ProcessBuilder("du", "--block-size=1", "-s",
                file.toAbsolutePath().toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                || process.exitValue() != 0) {
                return -1L;
            }
            return Long.parseLong(output.trim().split("\\s+")[0]);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Thread.currentThread().interrupt();
            return -1L;
        }
    }
}
