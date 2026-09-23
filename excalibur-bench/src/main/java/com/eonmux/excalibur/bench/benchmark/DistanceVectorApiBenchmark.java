package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.bench.kernel.ScalarDistances;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.InnerProduct;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.distance.VectorApiSupport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing scalar vs Vector API distance computation performance.
 *
 * <p>This benchmark explicitly tests both scalar and vectorized implementations
 * to measure the speedup from SIMD acceleration via the JDK Vector API.</p>
 *
 * <h2>Benchmark Categories</h2>
 * <ul>
 *   <li>Scalar implementations: Pure Java loops without SIMD</li>
 *   <li>Vector API implementations: Using jdk.incubator.vector when available</li>
 *   <li>Comparison: Speedup factor from vectorization</li>
 * </ul>
 *
 * <h2>Distance Types</h2>
 * <ul>
 *   <li>Cosine similarity: angle between vectors, range [-1.0, 1.0]</li>
 *   <li>L2 (Euclidean) distance: straight-line distance, range [0.0, +inf)</li>
 *   <li>Inner product (dot product): element-wise product sum, range (-inf, +inf)</li>
 * </ul>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Scalar throughput: >=5M ops/sec for 1536-dim vectors</li>
 *   <li>Vector API throughput: >=20M ops/sec for 1536-dim vectors</li>
 *   <li>Speedup factor: 2x-8x depending on platform SIMD width</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Vector dimensions: 64, 128, 256, 512, 768, 1536 (common embedding sizes)</li>
 *   <li>Values in range [-1.0, 1.0] (typical for normalized embeddings)</li>
 *   <li>Cosine results clamped to [-1.0, 1.0]</li>
 *   <li>L2 results non-negative</li>
 *   <li>Inner product handles both positive and negative correctly</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>JMH handles all thread safety. Distance functions are stateless and thread-safe.
 * Scalar implementations are pure functions with no shared state.</p>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector",
    "-Xms2g",
    "-Xmx2g"
})
public class DistanceVectorApiBenchmark {

    /**
     * Vector dimensions to test (common embedding sizes).
     */
    @Param({"64", "128", "256", "512", "768", "1536"})
    public int dimension;

    /**
     * Number of vector pairs for batch operations.
     */
    @Param({"1000"})
    public int batchSize;

    // Test vectors
    private float[][] vectors1;
    private float[][] vectors2;

    // Normalized vectors for cosine similarity tests
    private float[][] normalizedVectors1;
    private float[][] normalizedVectors2;

    // Distance function instances (auto-detect Vector API)
    private CosineSimilarity cosineSimilarity;
    private L2Distance l2Distance;
    private InnerProduct innerProduct;

    // Vector API availability flag
    private boolean vectorApiAvailable;

    // Current index for cycling through vectors
    private int currentIndex;

    @Setup(Level.Trial)
    public void setup() {
        // Initialize distance functions
        cosineSimilarity = CosineSimilarity.getInstance();
        l2Distance = L2Distance.getInstance();
        innerProduct = InnerProduct.getInstance();

        // Check Vector API availability
        vectorApiAvailable = VectorApiSupport.getInstance().isAvailable();

        // Generate random test vectors
        Random random = new Random(42);
        vectors1 = new float[batchSize][dimension];
        vectors2 = new float[batchSize][dimension];
        normalizedVectors1 = new float[batchSize][dimension];
        normalizedVectors2 = new float[batchSize][dimension];

        for (int i = 0; i < batchSize; i++) {
            for (int j = 0; j < dimension; j++) {
                // Generate values in [-1.0, 1.0] range
                vectors1[i][j] = random.nextFloat() * 2.0f - 1.0f;
                vectors2[i][j] = random.nextFloat() * 2.0f - 1.0f;
            }
            // Create normalized copies for cosine tests
            System.arraycopy(vectors1[i], 0, normalizedVectors1[i], 0, dimension);
            System.arraycopy(vectors2[i], 0, normalizedVectors2[i], 0, dimension);
            cosineSimilarity.normalizeInPlace(normalizedVectors1[i]);
            cosineSimilarity.normalizeInPlace(normalizedVectors2[i]);
        }

        currentIndex = 0;

        // Log benchmark configuration
        System.out.println("Distance Vector API Benchmark Setup:");
        System.out.println("  Dimension: " + dimension);
        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Vector API available: " + vectorApiAvailable);
        if (vectorApiAvailable) {
            System.out.println("  SIMD width: " + VectorApiSupport.getInstance().getPreferredSpeciesLength() + " floats");
        }
    }

    // ==================== COSINE SIMILARITY: SCALAR VS VECTOR API ====================

    /**
     * Benchmark scalar-only cosine similarity.
     */
    @Benchmark
    public float cosine_scalar() {
        int idx = currentIndex++ % batchSize;
        return ScalarDistances.cosineSimilarity(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark Vector API cosine similarity.
     * Falls back to scalar if Vector API unavailable.
     */
    @Benchmark
    public float cosine_vectorApi() {
        int idx = currentIndex++ % batchSize;
        return cosineSimilarity.compute(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark cosine similarity on normalized vectors (scalar).
     * For normalized vectors, inner product equals cosine similarity.
     */
    @Benchmark
    public float cosine_normalized_scalar() {
        int idx = currentIndex++ % batchSize;
        return ScalarDistances.innerProduct(normalizedVectors1[idx], normalizedVectors2[idx]);
    }

    /**
     * Benchmark cosine similarity on normalized vectors (Vector API).
     */
    @Benchmark
    public float cosine_normalized_vectorApi() {
        int idx = currentIndex++ % batchSize;
        return innerProduct.compute(normalizedVectors1[idx], normalizedVectors2[idx]);
    }

    // ==================== L2 DISTANCE: SCALAR VS VECTOR API ====================

    /**
     * Benchmark scalar-only L2 distance.
     */
    @Benchmark
    public float l2_scalar() {
        int idx = currentIndex++ % batchSize;
        return ScalarDistances.l2Distance(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark Vector API L2 distance.
     */
    @Benchmark
    public float l2_vectorApi() {
        int idx = currentIndex++ % batchSize;
        return l2Distance.compute(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark scalar-only squared L2 distance (no sqrt).
     * More efficient when only relative ordering is needed.
     */
    @Benchmark
    public float l2Squared_scalar() {
        int idx = currentIndex++ % batchSize;
        return ScalarDistances.l2Squared(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark Vector API squared L2 distance.
     */
    @Benchmark
    public float l2Squared_vectorApi() {
        int idx = currentIndex++ % batchSize;
        return l2Distance.computeSquared(vectors1[idx], vectors2[idx]);
    }

    // ==================== INNER PRODUCT: SCALAR VS VECTOR API ====================

    /**
     * Benchmark scalar-only inner product.
     */
    @Benchmark
    public float innerProduct_scalar() {
        int idx = currentIndex++ % batchSize;
        return ScalarDistances.innerProduct(vectors1[idx], vectors2[idx]);
    }

    /**
     * Benchmark Vector API inner product.
     */
    @Benchmark
    public float innerProduct_vectorApi() {
        int idx = currentIndex++ % batchSize;
        return innerProduct.compute(vectors1[idx], vectors2[idx]);
    }

    // ==================== BATCH OPERATIONS ====================

    /**
     * Benchmark batch cosine similarity (100 computations) - scalar.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int cosine_batch_scalar(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = ScalarDistances.cosineSimilarity(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Benchmark batch cosine similarity (100 computations) - Vector API.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int cosine_batch_vectorApi(Blackhole bh) {
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
     * Benchmark batch L2 distance (100 computations) - scalar.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int l2_batch_scalar(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = ScalarDistances.l2Distance(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Benchmark batch L2 distance (100 computations) - Vector API.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int l2_batch_vectorApi(Blackhole bh) {
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
     * Benchmark batch inner product (100 computations) - scalar.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int innerProduct_batch_scalar(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = ScalarDistances.innerProduct(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Benchmark batch inner product (100 computations) - Vector API.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int innerProduct_batch_vectorApi(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            int idx = i % batchSize;
            float result = innerProduct.compute(vectors1[idx], vectors2[idx]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    // ==================== NEAREST NEIGHBOR SEARCH SIMULATION ====================

    /**
     * Simulate nearest neighbor search with 100 candidates - scalar L2.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int nearestNeighbor_l2_scalar(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize;
            float distance = ScalarDistances.l2Distance(query, vectors2[idx]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIdx = idx;
            }
        }

        bh.consume(bestDistance);
        return bestIdx;
    }

    /**
     * Simulate nearest neighbor search with 100 candidates - Vector API L2.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int nearestNeighbor_l2_vectorApi(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize;
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
     * Simulate nearest neighbor search with 100 candidates - scalar cosine.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int nearestNeighbor_cosine_scalar(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize;
            float similarity = ScalarDistances.cosineSimilarity(query, vectors2[idx]);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestIdx = idx;
            }
        }

        bh.consume(bestSimilarity);
        return bestIdx;
    }

    /**
     * Simulate nearest neighbor search with 100 candidates - Vector API cosine.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int nearestNeighbor_cosine_vectorApi(Blackhole bh) {
        float[] query = vectors1[0];
        int bestIdx = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < 100; i++) {
            int idx = (i + 1) % batchSize;
            float similarity = cosineSimilarity.compute(query, vectors2[idx]);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestIdx = idx;
            }
        }

        bh.consume(bestSimilarity);
        return bestIdx;
    }

    // ==================== COMBINED THROUGHPUT ====================

    /**
     * Benchmark all three distance types combined - scalar.
     */
    @Benchmark
    public float[] allDistances_scalar(Blackhole bh) {
        int idx = currentIndex++ % batchSize;
        float[] v1 = vectors1[idx];
        float[] v2 = vectors2[idx];

        float cosine = ScalarDistances.cosineSimilarity(v1, v2);
        float l2 = ScalarDistances.l2Distance(v1, v2);
        float ip = ScalarDistances.innerProduct(v1, v2);

        bh.consume(cosine);
        bh.consume(l2);

        return new float[]{cosine, l2, ip};
    }

    /**
     * Benchmark all three distance types combined - Vector API.
     */
    @Benchmark
    public float[] allDistances_vectorApi(Blackhole bh) {
        int idx = currentIndex++ % batchSize;
        float[] v1 = vectors1[idx];
        float[] v2 = vectors2[idx];

        float cosine = cosineSimilarity.compute(v1, v2);
        float l2 = l2Distance.compute(v1, v2);
        float ip = innerProduct.compute(v1, v2);

        bh.consume(cosine);
        bh.consume(l2);

        return new float[]{cosine, l2, ip};
    }

    /**
     * Returns whether Vector API is available for this benchmark run.
     */
    public boolean isVectorApiAvailable() {
        return vectorApiAvailable;
    }
}
