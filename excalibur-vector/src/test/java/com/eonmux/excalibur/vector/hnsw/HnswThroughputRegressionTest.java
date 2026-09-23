package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the build time and search latency of a default-configured graph against regressing to
 * the per-access cost that once dominated both.
 *
 * <h2>What the bounds are</h2>
 * <p>Building 10,000 128-dimensional vectors took 100-240 s and a search 10-16 ms, when every
 * vector component was a separately locked
 * {@link com.eonmux.excalibur.core.mapped.MappedFile} read, every visited node was read
 * through a fresh view and stamped with its document id, and the SIMD kernels were reached by
 * reflection. With those removed this workload measures about 6 s and 0.45 ms per search.</p>
 *
 * <p>The Vector API is also fragile in a way no unit test sees: a lane operation C2 does not
 * intrinsify boxes every lane, and whether it does can differ from one JVM run to the next -
 * one such case measured 31 s and 1.7-1.9 ms here. The bounds sit several times above the
 * healthy figures and below that, so what fails this is a return of per-access or per-lane
 * cost rather than a busy host. They are sanity floors, not targets: the project's 10 ms
 * search target is graded by the JMH recall benchmark.</p>
 *
 * <h2>Tagging</h2>
 * <p>Tagged {@code benchmark}: it is a wall-clock measurement taking several seconds, so it runs
 * under {@code ./gradlew :excalibur-vector:benchmarkTest}, which also runs without the coverage
 * agent, rather than in every build.</p>
 */
@Tag("benchmark")
@DisplayName("HNSW build and search throughput regression floor")
class HnswThroughputRegressionTest {

    private static final int VECTOR_COUNT = 10_000;
    private static final int DIMENSION = 128;
    private static final int QUERY_COUNT = 200;
    private static final int WARMUP_QUERIES = 200;
    private static final int K = 10;

    /** Ceiling on the whole build, in seconds; measured at about 6. */
    private static final double MAX_BUILD_SECONDS = 25.0;

    /** Ceiling on the median search, in milliseconds; measured at about 0.45. */
    private static final double MAX_MEDIAN_SEARCH_MS = 1.5;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("10K x 128 builds and searches within the regression floor")
    void shouldBuildAndSearchWithinTheRegressionFloor() throws IOException {
        Random random = new Random(42L);
        float[][] vectors = gaussian(random, VECTOR_COUNT);
        float[][] queries = gaussian(random, QUERY_COUNT);

        HnswConfig config = HnswConfig.create(DIMENSION);
        try (HnswGraph graph = new HnswGraph(
                tempDir.resolve("throughput.hnsw"), config, L2Distance.getInstance(), VECTOR_COUNT)) {

            long buildStart = System.nanoTime();
            for (float[] vector : vectors) {
                graph.insert(vector);
            }
            double buildSeconds = (System.nanoTime() - buildStart) / 1e9;

            for (int i = 0; i < WARMUP_QUERIES; i++) {
                graph.search(queries[i % QUERY_COUNT], K);
            }
            double[] searchMs = new double[QUERY_COUNT];
            for (int i = 0; i < QUERY_COUNT; i++) {
                long start = System.nanoTime();
                List<HnswSearchResult> results = graph.search(queries[i], K);
                searchMs[i] = (System.nanoTime() - start) / 1e6;
                assertThat(results).hasSize(K);
            }
            Arrays.sort(searchMs);
            double medianMs = searchMs[QUERY_COUNT / 2];

            System.out.printf("HNSW %d x %d: build %.1f s, median search %.3f ms (p99 %.3f ms)%n",
                VECTOR_COUNT, DIMENSION, buildSeconds, medianMs,
                searchMs[(int) Math.ceil(QUERY_COUNT * 0.99) - 1]);

            assertThat(buildSeconds)
                .as("building %d x %d vectors, in seconds", VECTOR_COUNT, DIMENSION)
                .isLessThan(MAX_BUILD_SECONDS);
            assertThat(medianMs)
                .as("median search latency over %d vectors, in milliseconds", VECTOR_COUNT)
                .isLessThan(MAX_MEDIAN_SEARCH_MS);
        }
    }

    private static float[][] gaussian(Random random, int count) {
        float[][] vectors = new float[count][DIMENSION];
        for (float[] vector : vectors) {
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = (float) random.nextGaussian();
            }
        }
        return vectors;
    }
}
