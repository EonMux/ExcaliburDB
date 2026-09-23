package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.LshTableCheck;
import com.eonmux.excalibur.fsck.check.VectorIndexCheck;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vector and filter sidecars, which are read through their own modules' verifiers.
 *
 * <p>What is pinned here is not the checksum arithmetic - that belongs to the module that
 * owns each format - but that this tool routes each file to the right verifier and never
 * turns an absent or unreadable one into silence.</p>
 */
class VectorAndFilterCheckTest {

    @Test
    void anAbsentGraphIsMissingRatherThanAbsentFromTheReport(@TempDir Path dir) {
        var result = VectorIndexCheck.graph(dir.resolve("segment_0.hnsw"), "segment_0.hnsw");

        assertThat(result.status()).isEqualTo(CheckStatus.MISSING);
        assertThat(result.sizeBytes()).isEqualTo(-1);
    }

    @Test
    void aGraphTooShortForItsHeaderIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("segment_0.hnsw");
        Files.write(file, new byte[128]);

        var result = VectorIndexCheck.graph(file, "segment_0.hnsw");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.format()).contains("HNSW");
        assertThat(result.sizeBytes()).isEqualTo(128);
    }

    @Test
    void aDocumentMappingThatIsNotOneIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("segment_0.hnsw.docmap");
        Files.write(file, new byte[64]);

        var result = VectorIndexCheck.documentMapping(file, "segment_0.hnsw.docmap");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
    }

    /** Writes a sealed mapping of two chunks, as this build writes one. */
    private static Path chunkMapping(Path dir) throws IOException {
        Path file = dir.resolve("segment_0.hnsw.docmap");
        NodeDocumentMapping mapping = new NodeDocumentMapping(file);
        mapping.register(0L, "docs:a.md:chunk:0");
        mapping.register(1L, "docs:a.md:chunk:1");
        mapping.persist();
        return file;
    }

    @Test
    void aChunkKeyedMappingIsIntact(@TempDir Path dir) throws IOException {
        var result = VectorIndexCheck.documentMapping(chunkMapping(dir), "segment_0.hnsw.docmap");

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.detail()).doesNotContain("name documents");
    }

    @Test
    void aDocumentKeyedMappingFromAnOlderBuildIsIntactAndSaysSo(@TempDir Path dir)
            throws IOException {
        // The same sealed layout at the version an older build wrote, whose entries name
        // documents: sound, and not a repair case, since the node re-keys it itself.
        Path file = chunkMapping(dir);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(Long.BYTES, NodeDocumentMapping.VERSION_DOCUMENT_KEYED);
        header.putLong(40, IndexChecksum.of(bytes, 0, 40));
        Files.write(file, bytes);

        var result = VectorIndexCheck.documentMapping(file, "segment_0.hnsw.docmap");

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.detail()).contains("version 2", "name documents");
    }

    @Test
    void anAbsentDocumentMappingIsMissing(@TempDir Path dir) {
        var result = VectorIndexCheck.documentMapping(
            dir.resolve("segment_0.hnsw.docmap"), "segment_0.hnsw.docmap");

        assertThat(result.status()).isEqualTo(CheckStatus.MISSING);
    }

    @Test
    void aCodebookThatIsNotOneIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("segment_0.hnsw.pqcb");
        Files.write(file, new byte[48]);

        var result = VectorIndexCheck.codebook(file, "segment_0.hnsw.pqcb");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
    }

    @Test
    void anAbsentCodebookIsMissing(@TempDir Path dir) {
        var result = VectorIndexCheck.codebook(
            dir.resolve("segment_0.hnsw.pqcb"), "segment_0.hnsw.pqcb");

        assertThat(result.status()).isEqualTo(CheckStatus.MISSING);
    }

    @Test
    void anLshTableThatIsNotOneIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lsh_table_0.dat");
        Files.write(file, new byte[200]);

        var result = LshTableCheck.check(file, "lsh_table_0.dat");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.sizeBytes()).isEqualTo(200);
    }

    @Test
    void anAbsentLshTableIsMissing(@TempDir Path dir) {
        var result = LshTableCheck.check(dir.resolve("lsh_table_0.dat"), "lsh_table_0.dat");

        assertThat(result.status()).isEqualTo(CheckStatus.MISSING);
        assertThat(result.sizeBytes()).isEqualTo(-1);
    }

    @Test
    void onlyAnLshTableNameMatchesTheLshTablePattern() {
        assertThat(LshTableCheck.matches("lsh_table_0.dat")).isTrue();
        assertThat(LshTableCheck.matches("lsh_table_42.dat")).isTrue();
        assertThat(LshTableCheck.matches("lsh_table_.dat")).isFalse();
        assertThat(LshTableCheck.matches("segment_0.dat")).isFalse();
    }

    @Test
    void damagedSidecarsInARealDirectoryFailTheRun(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Files.write(dataDir.resolve("segment_0.hnsw"), new byte[128]);
        Files.write(dataDir.resolve("segment_0.hnsw.docmap"), new byte[64]);
        Files.write(dataDir.resolve("lsh_table_0.dat"), new byte[200]);

        var out = new java.io.ByteArrayOutputStream();
        int exitCode;
        try (var stream = new java.io.PrintStream(out, true,
                java.nio.charset.StandardCharsets.UTF_8)) {
            exitCode = ExcaliburFsck.run(new String[] { dataDir.toString() }, stream, stream);
        }
        String report = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(exitCode).isNotZero();
        assertThat(report).contains("segment_0.hnsw", "segment_0.hnsw.docmap", "lsh_table_0.dat");
    }
}
