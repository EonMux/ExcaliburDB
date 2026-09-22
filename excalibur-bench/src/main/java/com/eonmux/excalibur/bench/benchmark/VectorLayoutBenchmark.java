package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.distance.InnerProduct;
import com.eonmux.excalibur.vector.layout.DistanceType;
import com.eonmux.excalibur.vector.layout.VectorBlock;
import com.eonmux.excalibur.vector.layout.VectorLayout;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing optimized SoA (Structure of Arrays) memory layout
 * against traditional AoS (Array of Structures) for batch distance computations.
 *
 * <h2>Benchmark Scenarios</h2>
 * <ul>
 *   <li>Traditional AoS: Iterate through float[][] arrays</li>
 *   <li>Optimized SoA: Use VectorBlock with transposed layout</li>
 * </ul>
 *
 * <h2>Expected Results</h2>
 * <p>SoA layout should show significant performance improvement for batch
 * distance computations due to:</p>
 * <ul>
 *   <li>Contiguous memory access patterns</li>
 *   <li>Better cache utilization</li>
 *   <li>Improved SIMD auto-vectorization</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The benchmark state is managed by JMH's @State annotations which
 * provide proper isolation between threads. The VectorBlock and distance functions
 * being tested are either stateless or have their own thread-safety guarantees.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector"})
public class VectorLayoutBenchmark {

    /**
     * Vector dimensions to test.
     */
    @Param({"128", "768", "1536"})
    public int dimension;

    /**
     * Number of vectors in the block.
     */
    @Param({"1000", "10000"})
    public int vectorCount;

    // Traditional AoS storage
    private float[][] aosVectors;
    private float[] aosQuery;

    // Optimized SoA storage
    private VectorBlock soaBlock;
    private float[] soaQuery;
    private float[] soaResults;

    // Distance functions
    private CosineSimilarity cosineSimilarity;
    private L2Distance l2Distance;
    private InnerProduct innerProduct;

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(42);

        // Initialize distance functions
        cosineSimilarity = CosineSimilarity.getInstance();
        l2Distance = L2Distance.getInstance();
        innerProduct = InnerProduct.getInstance();

        // Generate AoS vectors
        aosVectors = new float[vectorCount][dimension];
        for (int i = 0; i < vectorCount; i++) {
            for (int j = 0; j < dimension; j++) {
                aosVectors[i][j] = random.nextFloat() * 2.0f - 1.0f;
            }
        }
        aosQuery = new float[dimension];
        for (int j = 0; j < dimension; j++) {
            aosQuery[j] = random.nextFloat() * 2.0f - 1.0f;
        }

        // Generate SoA block (same data, transposed layout)
        VectorLayout layout = VectorLayout.create(dimension, vectorCount);
        soaBlock = new VectorBlock(layout);
        for (int i = 0; i < vectorCount; i++) {
            soaBlock.putVector(i, aosVectors[i]);
        }
        soaQuery = aosQuery.clone();
        soaResults = new float[vectorCount];

        System.out.println("Vector Layout Benchmark Setup:");
        System.out.println("  Dimension: " + dimension);
        System.out.println("  Vector count: " + vectorCount);
        System.out.println("  SoA block size: " + (soaBlock.size() / 1024.0 / 1024.0) + " MB");
    }

    // ==================== COSINE SIMILARITY BENCHMARKS ====================

    /**
     * Traditional AoS cosine similarity: iterate through float[][].
     */
    @Benchmark
    public int cosineSimilarity_aos(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < vectorCount; i++) {
            float result = cosineSimilarity.compute(aosQuery, aosVectors[i]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Optimized SoA cosine similarity: batch computation.
     */
    @Benchmark
    public int cosineSimilarity_soa(Blackhole bh) {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.COSINE);
        for (int i = 0; i < vectorCount; i++) {
            bh.consume(soaResults[i]);
        }
        return vectorCount;
    }

    // ==================== L2 DISTANCE BENCHMARKS ====================

    /**
     * Traditional AoS L2 distance: iterate through float[][].
     */
    @Benchmark
    public int l2Distance_aos(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < vectorCount; i++) {
            float result = l2Distance.compute(aosQuery, aosVectors[i]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Optimized SoA L2 distance: batch computation.
     */
    @Benchmark
    public int l2Distance_soa(Blackhole bh) {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.L2);
        for (int i = 0; i < vectorCount; i++) {
            bh.consume(soaResults[i]);
        }
        return vectorCount;
    }

    // ==================== INNER PRODUCT BENCHMARKS ====================

    /**
     * Traditional AoS inner product: iterate through float[][].
     */
    @Benchmark
    public int innerProduct_aos(Blackhole bh) {
        int count = 0;
        for (int i = 0; i < vectorCount; i++) {
            float result = innerProduct.compute(aosQuery, aosVectors[i]);
            bh.consume(result);
            count++;
        }
        return count;
    }

    /**
     * Optimized SoA inner product: batch computation.
     */
    @Benchmark
    public int innerProduct_soa(Blackhole bh) {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.INNER_PRODUCT);
        for (int i = 0; i < vectorCount; i++) {
            bh.consume(soaResults[i]);
        }
        return vectorCount;
    }

    // ==================== NEAREST NEIGHBOR SEARCH BENCHMARKS ====================

    /**
     * Traditional AoS nearest neighbor search with cosine similarity.
     */
    @Benchmark
    public int nearestNeighbor_aos_cosine() {
        int bestIdx = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < vectorCount; i++) {
            float similarity = cosineSimilarity.compute(aosQuery, aosVectors[i]);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestIdx = i;
            }
        }

        return bestIdx;
    }

    /**
     * Optimized SoA nearest neighbor search with cosine similarity.
     */
    @Benchmark
    public int nearestNeighbor_soa_cosine() {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.COSINE);

        int bestIdx = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < vectorCount; i++) {
            if (soaResults[i] > bestSimilarity) {
                bestSimilarity = soaResults[i];
                bestIdx = i;
            }
        }

        return bestIdx;
    }

    /**
     * Traditional AoS nearest neighbor search with L2 distance.
     */
    @Benchmark
    public int nearestNeighbor_aos_l2() {
        int bestIdx = -1;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < vectorCount; i++) {
            float distance = l2Distance.compute(aosQuery, aosVectors[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIdx = i;
            }
        }

        return bestIdx;
    }

    /**
     * Optimized SoA nearest neighbor search with L2 distance.
     */
    @Benchmark
    public int nearestNeighbor_soa_l2() {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.L2);

        int bestIdx = -1;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < vectorCount; i++) {
            if (soaResults[i] < bestDistance) {
                bestDistance = soaResults[i];
                bestIdx = i;
            }
        }

        return bestIdx;
    }
}
