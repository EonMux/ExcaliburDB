package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static com.eonmux.excalibur.vector.hnsw.HnswGraphTestVectors.generateNormalizedVector;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full invariant verification over a graph deep enough to have a real hierarchy.
 *
 * <p>The other suite that runs {@link HnswInvariantVerifier#verifyAllInvariants} on a
 * populated graph — {@code HnswGraphStructureTest.shouldVerifyAllEdgeCountsWithInvariantVerifier}
 * — builds 150 nodes at {@code mL = 1/ln(12)}, which in practice tops out at level 1:
 * one layer above the base. The two invariants that only mean something across more than
 * one hop of hierarchy — that the entry point sits at the highest occupied layer, and that
 * a node's neighbours at level L themselves reach level L — are therefore barely
 * exercised there.</p>
 *
 * <p>This suite raises {@code mL} to 0.5 over 200 nodes, which lifts the expected top
 * layer to 2-3, and asserts the verifier finds no violation on the result.</p>
 */
class HnswMultiLevelInvariantTest {

    /**
     * {@code mL = 0.5} gives {@code P(level >= 1) = e^-2 ~ 0.135}, so the 200 inserts
     * below populate the upper layers instead of collapsing to a flat graph.
     */
    private static final HnswConfig MULTI_LEVEL_CONFIG = new HnswConfig(16, 32, 200, 100, 0.5, 64);

    private static final int NODE_COUNT = 200;
    private static final int DIMENSION = 64;

    /** Seeds the inserted vectors so a failure names a graph that can be rebuilt. */
    private static final long VECTOR_SEED = 42L;

    @TempDir
    Path tempDir;

    @Test
    void shouldSatisfyAllInvariantsOnAMultiLevelGraph() throws IOException {
        Path graphPath = tempDir.resolve("multi-level.hnsw");

        try (HnswGraph graph = new HnswGraph(
                graphPath, MULTI_LEVEL_CONFIG, L2Distance.getInstance(), 1000)) {
            Random random = new Random(VECTOR_SEED);
            for (int i = 0; i < NODE_COUNT; i++) {
                graph.insert(generateNormalizedVector(DIMENSION, random));
            }

            // Node levels are drawn from ThreadLocalRandom inside the inserter, so the exact
            // top layer varies per run; only the presence of a hierarchy is guaranteed. The
            // probability that no node among 200 reaches level 1 at mL = 0.5 is ~2e-13.
            assertThat(graph.maxLevel())
                .as("Fixture must stay hierarchical for this suite to mean anything. "
                    + "Graph: %s", HnswInvariantVerifier.summarizeGraph(graph))
                .isGreaterThanOrEqualTo(1);

            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            assertThat(result.violations())
                .as("Multi-level graph should satisfy every HNSW invariant. Graph: %s",
                    HnswInvariantVerifier.summarizeGraph(graph))
                .isEmpty();
            assertThat(result.isValid()).isTrue();
            assertThat(result.nodesChecked())
                .as("Every inserted node should have been checked")
                .isEqualTo(NODE_COUNT);
            assertThat(result.edgesChecked())
                .as("A 200-node graph at M=16 should have edges to check")
                .isPositive();
        }
    }
}
