package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the HNSW graph's verification entry point and its handling of older files.
 *
 * <p>Verification exists for tooling that has to survey a node's files, so it must reach a
 * verdict on a file it never opens as a graph, and it must distinguish a file it checked from
 * a file it could not check.</p>
 */
@DisplayName("HnswGraph verification")
class HnswGraphVerificationTest {

    private static final int DIMENSION = 16;
    private static final long MAX_NODES = 500;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a closed graph verifies as intact without being opened")
    void closedGraphVerifiesIntact() throws IOException {
        Path graphPath = writeGraph("sealed.hnsw");

        assertThat(HnswGraph.verifyIntegrity(graphPath).status())
            .isEqualTo(IntegrityStatus.INTACT);
        assertThat(HnswGraph.verifyIntegrity(graphPath).verified()).isTrue();
    }

    @Test
    @DisplayName("a graph a writer still holds is reported unsealed, not intact")
    void openGraphIsReportedUnsealed() throws IOException {
        Path graphPath = tempDir.resolve("live.hnsw");
        try (HnswGraph graph = new HnswGraph(graphPath, HnswConfig.create(DIMENSION),
                L2Distance.getInstance(), MAX_NODES)) {
            graph.insert(randomVector(new Random(1)));

            assertThat(HnswGraph.verifyIntegrity(graphPath).status())
                .isEqualTo(IntegrityStatus.UNSEALED);
            assertThat(HnswGraph.verifyIntegrity(graphPath).loadable()).isTrue();
        }
    }

    @Test
    @DisplayName("a damaged graph is reported corrupt and refused, never treated as empty")
    void damagedGraphIsReportedCorrupt() throws IOException {
        Path graphPath = writeGraph("rotted.hnsw");
        flipByte(graphPath, 4096L + MAX_NODES * 24L + 16);

        var report = HnswGraph.verifyIntegrity(graphPath);

        assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
        assertThat(report.loadable()).isFalse();
        assertThat(report.detail()).contains("graph data");
    }

    @Test
    @DisplayName("a graph written before checksums existed is named as unprotected, not refused")
    void olderGraphIsUnprotectedRatherThanRefused() throws IOException {
        Path graphPath = writeGraph("legacy.hnsw");
        writeInt(graphPath, 8, 2);

        assertThat(HnswGraph.verifyIntegrity(graphPath).status())
            .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);

        try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
            assertThat(reopened.nodeCount()).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("a sealed graph at a version this build does not read is unreadable, not intact")
    void newerGraphIsUnreadableRatherThanIntact() throws IOException {
        Path graphPath = writeGraph("newer.hnsw");
        writeInt(graphPath, 8, HnswGraphHeader.VERSION + 1);
        resealHeader(graphPath);

        var report = HnswGraph.verifyIntegrity(graphPath);

        assertThat(report.status()).isEqualTo(IntegrityStatus.UNREADABLE);
        assertThat(report.detail()).contains("format version " + (HnswGraphHeader.VERSION + 1));
        assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a graph claiming a version below any ever written is unreadable, not legacy")
    void graphBelowTheOldestVersionIsUnreadable() throws IOException {
        Path graphPath = writeGraph("zero.hnsw");
        writeInt(graphPath, 8, 0);

        assertThat(HnswGraph.verifyIntegrity(graphPath).status())
            .isEqualTo(IntegrityStatus.UNREADABLE);
    }

    @Test
    @DisplayName("a graph file that is not there is reported missing")
    void absentGraphIsReportedMissing() {
        assertThat(HnswGraph.verifyIntegrity(tempDir.resolve("absent.hnsw")).status())
            .isEqualTo(IntegrityStatus.MISSING);
    }

    private Path writeGraph(String name) throws IOException {
        Path graphPath = tempDir.resolve(name);
        Random random = new Random(5);
        try (HnswGraph graph = new HnswGraph(graphPath, HnswConfig.create(DIMENSION),
                L2Distance.getInstance(), MAX_NODES)) {
            for (int i = 0; i < 20; i++) {
                graph.insert(randomVector(random));
            }
        }
        return graphPath;
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            int original = handle.readUnsignedByte();
            handle.seek(offset);
            handle.writeByte(original ^ 0x40);
        }
    }

    /** Recomputes the header checksum, so the header vouches for whatever it now says. */
    private static void resealHeader(Path file) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            byte[] header = new byte[HnswGraphIntegrity.HEADER_COVERED_BYTES];
            handle.readFully(header);
            handle.seek(HnswGraphIntegrity.OFFSET_HEADER_CRC);
            handle.writeLong(IndexChecksum.of(header, 0, header.length));
        }
    }

    private static void writeInt(Path file, long offset, int value) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            handle.writeInt(value);
        }
    }
}
