package excalibur.bench.benchmark;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.InnerProduct;
import io.excalibur.vector.distance.L2Distance;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for vector distance functions.
 *
 * <p>Measures throughput of distance/similarity computations to validate
 * the Phase 4 requirement for high-performance vector operations.</p>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Cosine similarity: Millions of computations/sec</li>
 *   <li>L2 distance: Millions of computations/sec</li>
 *   <li>Inner product: Millions of computations/sec</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Vector dimensions in range [64, 1536] (typical embedding sizes)</li>
 *   <li>Values in range [-1.0, 1.0] for normalized embeddings</li>
 *   <li>Both scalar and Vector API paths tested</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector"})
public class DistanceBenchmark {

    /**
     * Vector dimensions to test (common embedding sizes).
     */
    @Param({"64", "128", "256", "512", "768", "1536"})
    public int dimension;

    /**
     * Number of vector pairs to pre-generate for batch operations.
     */
    @Param({"1000"})
    public int batchSize;

    // Distance function instances
    private CosineSimilarity cosineSimilarity;
    private L2Distance l2Distance;
    private InnerProduct innerProduct;

    // Test vectors
    private float[][] vectors1;
    private float[][] vectors2;

    // Index for iterating through vector pairs
    private int currentIndex;

    @Setup(Level.Trial)
    public void setup() {
        // Initialize distance functions
        cosineSimilarity = CosineSimilarity.getInstance();
        l2Distance = L2Distance.getInstance();
        innerProduct = InnerProduct.getInstance();

        // Generate random test vectors
        Random random = new Random(42);
        vectors1 = new float[batchSize][dimension];
        vectors2 = new float[batchSize][dimension];

        for (int i = 0; i < batchSize; i++) {
            for (int j = 0; j < dimension; j++) {
                // Generate values in [-1.0, 1.0] range (typical for embeddings)
                vectors1[i][j] = random.nextFloat() * 2.0f - 1.0f;
                vectors2[i][j] = random.nextFloat() * 2.0f - 1.0f;
            }
        }

        currentIndex = 0;

        // Log benchmark setup
        System.out.println("Distance Benchmark Setup:");
        System.out.println("  Dimension: " + dimension);
        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Vector API available: " + isVectorApiAvailable());
    }

    /**
     * Checks if Vector API is available on this platform.
     */
    private boolean isVectorApiAvailable() {
        try {
            Class<?> speciesClass = Class.forName("jdk.incubator.vector.FloatVector");
            return speciesClass != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ==================== COSINE SIMILARITY BENCHMARKS ====================

    /**
     * Benchmark single cosine similarity computation.
     */
    @Benchmark
    public float cosineSimilarity_single() {
        int idx = currentIndex++ % batchSize;
        return cosineSimilarity.compute(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark batch cosine similarity computations.
     */
    @Benchmark
    public int cosineSimilarity_batch(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = cosineSimilarity.compute(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Benchmark cosine similarity with pre-computed norms.
     */
    @Benchmark
    public float cosineSimilarity_withPrecomputedNorms(Blackhole bh) {
        int idx = currentIndex++ % batchSize;
        float[] v1 = vectors1[idx];
        float[] v2 = vectors2[idx];
        double norm1Sq = cosineSimilarity.computeSquaredNorm(v1);
        double norm2Sq = cosineSimilarity.computeSquaredNorm(v2);
        bh.consume(norm1Sq);
        bh.consume(norm2Sq);
        return cosineSimilarity.computeWithNorms(v1, v2, norm1Sq, norm2Sq);
    }

    // ==================== L2 DISTANCE BENCHMARKS ====================

    /**
     * Benchmark single L2 distance computation.
     */
    @Benchmark
    public float l2Distance_single() {
        int idx = currentIndex++ % batchSize;
        return l2Distance.compute(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark batch L2 distance computations.
     */
    @Benchmark
    public int l2Distance_batch(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = l2Distance.compute(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Benchmark squared L2 distance (no square root).
     */
    @Benchmark
    public float l2Distance_squared() {
        int idx = currentIndex++ % batchSize;
        return l2Distance.computeSquared(vectors1[idx], vectors2[idx]);
    }

    // ==================== INNER PRODUCT BENCHMARKS ====================

    /**
     * Benchmark single inner product computation.
     */
    @Benchmark
    public float innerProduct_single() {
        int idx = currentIndex++ % batchSize;
        return innerProduct.compute(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark batch inner product computations.
     */
    @Benchmark
    public int innerProduct_batch(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = innerProduct.compute(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Benchmark negated inner product (for distance metric usage).
     */
    @Benchmark
    public float innerProduct_negated() {
        int idx = currentIndex++ % batchSize;
        return innerProduct.computeNegated(vectors1[idx], vectors2[idx]);
    }

    // ==================== COMPARISON BENCHMARKS ====================

    /**
     * Benchmark all three distance functions on the same vectors.
     */
    @Benchmark
    public float[] allDistances_comparison(Blackhole bh) {
        int idx = currentIndex++ % batchSize;
        float[] v1 = vectors1[idx];
        float[] v2 = vectors2[idx];

        float cosine = cosineSimilarity.compute(v1, v2);
        float l2 = l2Distance.compute(v1, v2);
        float ip = innerProduct.compute(v1, v2);

        bh.consume(cosine);
        bh.consume(l2);
        bh.consume(ip);

        return new float[]{cosine, l2, ip};
    }

    /**
     * Benchmark distance computation for nearest neighbor search simulation.
     * Computes distance from one query vector to 100 candidates.
     */
    @Benchmark
    public int nearestNeighborSearch_cosine(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize; // Skip query vector
            float similarity = cosineSimilarity.compute(query, vectors2[idx]);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestIdx = idx;
            }
        }

        bh.consume(bestSimilarity);
        return bestIdx;
    }

    /**
     * Benchmark distance computation for nearest neighbor search with L2.
     */
    @Benchmark
    public int nearestNeighborSearch_l2(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize; // Skip query vector
            float distance = l2Distance.compute(query, vectors2[idx]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIdx = idx;
            }
        }

        bh.consume(bestDistance);
        return bestIdx;
    }

    /**
     * Benchmark squared L2 for nearest neighbor search (avoids sqrt).
     */
    @Benchmark
    public int nearestNeighborSearch_l2Squared(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize;
            float distance = l2Distance.computeSquared(query, vectors2[idx]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIdx = idx;
            }
        }

        bh.consume(bestDistance);
        return bestIdx;
    }
}
