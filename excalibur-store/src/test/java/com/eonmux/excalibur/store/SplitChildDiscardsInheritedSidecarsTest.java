package com.eonmux.excalibur.store;

import com.eonmux.excalibur.vector.hnsw.HnswGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a split child keeps none of the files its parent's graph left behind.
 *
 * <p>The left child takes its parent's canonical name, so every path derived from that name is
 * inherited: the graph itself, the document mapping beside it, and the product-quantisation
 * codebook. The graph describes the parent's contents and is discarded; a codebook left behind
 * would be adopted by the child's new graph and used to answer searches over vectors it was
 * never trained on.</p>
 */
@DisplayName("A split child inherits none of its parent's graph sidecars")
class SplitChildDiscardsInheritedSidecarsTest {

    private static final int KEY_COUNT = 400;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("split-sidecars");
        Files.createDirectories(dataDir);
        engine = new StorageEngine(dataDir);
        engine.setHnswInitializer(new HnswInitializer());
        engine.start();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("the codebook trained on the parent's vectors is removed with the graph")
    void discardsTheInheritedCodebook() throws Exception {
        for (int i = 0; i < KEY_COUNT; i++) {
            engine.put(("sidecar-key-" + i).getBytes(StandardCharsets.UTF_8),
                ("sidecar-value-" + i).getBytes(StandardCharsets.UTF_8));
        }

        int routingBefore = engine.getSegmentCount();
        Segment parent = findPopulatedSegment(routingBefore);
        Path graphPath = engine.getHnswInitializer().computeGraphPath(parent.path());
        Path codebook = Path.of(graphPath + HnswGraph.PRODUCT_QUANTIZER_SUFFIX);
        Path documentMapping = Path.of(graphPath + HnswGraph.DOCUMENT_MAPPING_SUFFIX);
        Files.write(codebook, new byte[] {1, 2, 3, 4});
        Files.write(documentMapping, new byte[] {5, 6, 7, 8});

        engine.getSplitWorker().requestSplit(parent, Integer.numberOfTrailingZeros(routingBefore));
        awaitSplit(parent);

        assertThat(codebook)
            .as("a codebook trained on the parent's vectors must not survive onto the child")
            .doesNotExist();
        assertThat(documentMapping)
            .as("a document mapping naming the parent's nodes must not survive either")
            .doesNotExist();
    }

    private Segment findPopulatedSegment(int routingCount) {
        for (int i = 0; i < routingCount; i++) {
            Segment segment = engine.getSegment(i);
            if (segment != null && segment.entryCount() > 0) {
                return segment;
            }
        }
        throw new AssertionError("No populated segment to split");
    }

    private void awaitSplit(Segment parent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!parent.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Split did not complete within the timeout");
            }
            Thread.sleep(10);
        }
    }
}
