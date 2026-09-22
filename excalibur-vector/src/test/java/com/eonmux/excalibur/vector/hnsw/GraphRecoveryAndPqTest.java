package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.quantization.PQDistanceTable;
import com.eonmux.excalibur.vector.quantization.ProductQuantizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two ways a graph gave wrong answers without failing.
 *
 * <p>A crash can leave the header's node count ahead of the index slot it names, because the two
 * are separate unforced stores into the same mapping. Believing the count derives the append
 * position from an unwritten slot, and the next insert then writes over node 0 - typically the
 * entry point, so the graph navigates from a corrupted vector.</p>
 *
 * <p>Separately, PQ codes are keyed by node id, and compaction renumbers node ids. Codes left
 * behind describe a different vector than the node they are now attached to, and codes simply
 * dropped leave the segment holding a quantizer with nothing to quantise against.</p>
 */
@DisplayName("Graph recovery and PQ")
class GraphRecoveryAndPqTest {

    private static final int DIMENSION = 16;
    /** Byte offset of the node-count field in the graph header. */
    private static final long OFFSET_NODE_COUNT = 16;

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("recovery.hnsw");
        config = HnswConfig.create(DIMENSION);
        distanceFunction = CosineSimilarity.getInstance();
    }

    @Test
    @DisplayName("a node count ahead of the index entries is not believed")
    void tornNodeCountIsClampedToWhatWasWritten() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector());
            }
            graph.flush();
        }

        // The header reaching disk without the index slot behind it is exactly what an
        // unforced, unordered pair of stores permits.
        writeHeaderNodeCount(graphPath, 11L);

        try (HnswGraph reopened = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(reopened.nodeCount())
                .as("only the nodes whose index entries were written are counted")
                .isEqualTo(10);

            // The vector of node 0 survives the next insert. Deriving the append position from
            // the unwritten slot 10 gave a data offset of zero, and this insert landed on it.
            float[] nodeZeroBefore = reopened.readNode(0L).vector();
            reopened.insert(randomVector());

            assertThat(reopened.readNode(0L).vector())
                .as("the next insert appends rather than overwriting node 0")
                .isEqualTo(nodeZeroBefore);
        }
    }

    @Test
    @DisplayName("a header that matches its entries is left alone")
    void intactHeaderIsTrusted() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 10; i++) {
                graph.insert(randomVector());
            }
            graph.flush();
        }

        try (HnswGraph reopened = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(reopened.nodeCount()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("compaction re-keys PQ codes to the numbering it produced")
    void compactionRekeysPqCodes() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 40; i++) {
                graph.insert(randomVector());
            }

            graph.trainProductQuantizer(40, 4, 16);
            graph.encodeAllVectors();
            assertThat(graph.encodedVectorCount()).isPositive();

            // Deleting from the front is what makes the renumbering shift: new node k is the
            // (k+1)-th survivor, whose old id is larger than k.
            for (int i = 0; i < 10; i++) {
                graph.delete(i);
            }

            assertThat(graph.compact(tempDir)).isEqualTo(10);

            float[] query = randomVector();
            ProductQuantizer quantizer = graph.getProductQuantizer();
            PQDistanceTable table = quantizer.createDistanceTable(query, distanceFunction);

            List<HnswSearchResult> results = graph.searchWithPQ(query, 5);
            assertThat(results).isNotEmpty();
            for (HnswSearchResult result : results) {
                assertThat(result.distance())
                    .as("node %d must be scored against its own quantisation, not against "
                        + "whichever vector held its id before the renumbering", result.nodeId())
                    .isEqualTo(table.lookup(
                        quantizer.encode(graph.readNode(result.nodeId()).vector())));
            }
        }
    }

    @Test
    @DisplayName("a PQ search returns as many results as asked for")
    void pqSearchHonoursK() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            // More nodes than the beam the default efSearch allows, so a beam that ignores k
            // is visible as a short answer rather than as "the graph is small".
            for (int i = 0; i < 300; i++) {
                graph.insert(randomVector());
            }
            graph.trainProductQuantizer(200, 4, 16);
            graph.encodeAllVectors();

            // Above the default efSearch, which used to cap the beam regardless of k.
            int k = config.efSearch() + 20;
            List<HnswSearchResult> results = graph.searchWithPQ(randomVector(), k);

            assertThat(results).hasSize(k);
        }
    }

    /**
     * Leaves the file in the state a crash between the two stores would have left it in: the
     * header's count ahead of the index slot it names, and the graph unsealed because nothing
     * closed it. The seal has to go with the count - a sealed file whose header has been
     * edited is corruption rather than a torn write, and the integrity check refuses it.
     */
    private static void writeHeaderNodeCount(Path path, long nodeCount) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
            buffer.putLong(nodeCount).flip();
            channel.write(buffer, OFFSET_NODE_COUNT);

            ByteBuffer seal = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
            seal.putInt(HnswGraphIntegrity.STATE_OPEN).flip();
            channel.write(seal, HnswGraphIntegrity.OFFSET_STATE);
        }
    }

    private final Random random = new Random(20240831L);

    private float[] randomVector() {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }
}
