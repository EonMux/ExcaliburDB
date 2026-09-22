package com.eonmux.excalibur.vector.hnsw;

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
 * Integrity tests for the persisted HNSW graph file.
 *
 * <p>A graph whose bytes have rotted must be refused by name rather than served. A silently
 * accepted bit flip changes a vector, a neighbour id or a tombstone flag, and the graph then
 * answers every query that touches it with a plausible but wrong result.</p>
 */
@DisplayName("HnswGraph file integrity")
class HnswGraphIntegrityTest {

    private static final int DIMENSION = 16;
    private static final long MAX_NODES = 1000;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a flipped payload byte is refused when the graph is opened")
    void flippedPayloadByteIsRefusedOnOpen() throws IOException {
        Path graphPath = writeGraph("payload.hnsw");

        flipByte(graphPath, dataRegionStart() + 40);

        assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(graphPath.toString());
    }

    @Test
    @DisplayName("a flipped payload byte is refused when the graph is re-attached")
    void flippedPayloadByteIsRefusedOnAttach() throws IOException {
        Path graphPath = writeGraph("attach.hnsw");

        flipByte(graphPath, dataRegionStart() + 40);

        assertThatThrownBy(() -> new HnswGraph(graphPath, HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), MAX_NODES))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(graphPath.toString());
    }

    @Test
    @DisplayName("a flipped header byte is refused rather than read as a different graph")
    void flippedHeaderByteIsRefused() throws IOException {
        Path graphPath = writeGraph("header.hnsw");

        // The node count field: a flip here silently drops or invents nodes.
        flipByte(graphPath, 20);

        assertThatThrownBy(() -> HnswGraph.open(graphPath, L2Distance.getInstance()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(graphPath.toString());
    }

    @Test
    @DisplayName("a graph that has lost its magic number is refused, not rebuilt as empty")
    void lostHeaderIsRefusedRatherThanRebuilt() throws IOException {
        Path graphPath = writeGraph("headerless.hnsw");

        try (RandomAccessFile handle = new RandomAccessFile(graphPath.toFile(), "rw")) {
            handle.seek(0);
            handle.writeLong(0L);
        }

        assertThatThrownBy(() -> new HnswGraph(graphPath, HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), MAX_NODES))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(graphPath.toString())
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a file of zeroes is still adopted as a graph that was never written")
    void blankFileIsStillAdopted() throws IOException {
        Path graphPath = tempDir.resolve("blank.hnsw");
        java.nio.file.Files.write(graphPath, new byte[64 * 1024]);

        try (HnswGraph graph = new HnswGraph(graphPath, HnswConfig.create(DIMENSION),
                L2Distance.getInstance(), MAX_NODES)) {
            assertThat(graph.nodeCount()).isZero();
        }
    }

    @Test
    @DisplayName("an intact graph still opens and keeps its nodes")
    void intactGraphStillOpens() throws IOException {
        Path graphPath = writeGraph("intact.hnsw");

        try (HnswGraph graph = HnswGraph.open(graphPath, L2Distance.getInstance())) {
            assertThat(graph.nodeCount()).isEqualTo(40);
        }
    }

    private Path writeGraph(String name) throws IOException {
        Path graphPath = tempDir.resolve(name);
        Random random = new Random(7);
        try (HnswGraph graph = new HnswGraph(graphPath, HnswConfig.create(DIMENSION),
                L2Distance.getInstance(), MAX_NODES)) {
            for (int i = 0; i < 40; i++) {
                graph.insert(randomVector(random));
            }
        }
        return graphPath;
    }

    private static long dataRegionStart() {
        return 4096L + MAX_NODES * 24L;
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
}
