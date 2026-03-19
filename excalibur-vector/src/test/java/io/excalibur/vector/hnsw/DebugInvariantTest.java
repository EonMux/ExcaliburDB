package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/**
 * Debug test to print invariant violations.
 */
class DebugInvariantTest {

    @TempDir
    Path tempDir;

    @Test
    void debugInvariantViolations() throws IOException {
        Path graphPath = tempDir.resolve("debug.hnsw");
        HnswConfig config = HnswConfig.create(64);
        L2Distance distFn = L2Distance.getInstance();

        // Use higher mL for multi-level graph
        HnswConfig multiLevelConfig = new HnswConfig(16, 32, 200, 100, 0.5, 64);

        try (HnswGraph graph = new HnswGraph(graphPath, multiLevelConfig, distFn, 1000)) {
            Random random = new Random(42);
            for (int i = 0; i < 200; i++) {
                float[] vec = new float[64];
                for (int j = 0; j < 64; j++) {
                    vec[j] = (float) random.nextGaussian();
                }
                graph.insert(vec);
            }

            System.out.println("Graph summary:");
            System.out.println(HnswInvariantVerifier.summarizeGraph(graph));
            System.out.println();

            HnswInvariantVerifier.VerificationResult result =
                HnswInvariantVerifier.verifyAllInvariants(graph);

            System.out.println("Verification result: " + result.isValid());
            System.out.println("Nodes checked: " + result.nodesChecked());
            System.out.println("Edges checked: " + result.edgesChecked());

            if (!result.isValid()) {
                System.out.println("\nViolations (" + result.violations().size() + "):");
                for (String v : result.violations()) {
                    System.out.println("  - " + v);
                }
            }
        }
    }
}
