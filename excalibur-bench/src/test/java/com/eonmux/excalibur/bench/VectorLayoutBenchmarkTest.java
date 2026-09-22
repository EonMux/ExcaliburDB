package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.benchmark.VectorLayoutBenchmark;
import com.eonmux.excalibur.vector.layout.DistanceType;
import com.eonmux.excalibur.vector.layout.VectorBlock;
import com.eonmux.excalibur.vector.layout.VectorLayout;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.distance.InnerProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Verification test for VectorLayoutBenchmark functionality.
 * Tests that the benchmark setup and core operations work correctly.
 */
class VectorLayoutBenchmarkTest {

    private static final int DIMENSION = 128;
    private static final int VECTOR_COUNT = 100;
    private static final long SEED = 42L;

    private float[][] aosVectors;
    private float[] aosQuery;
    private VectorBlock soaBlock;
    private float[] soaQuery;
    private float[] soaResults;

    private CosineSimilarity cosineSimilarity;
    private L2Distance l2Distance;
    private InnerProduct innerProduct;

    @BeforeEach
    void setUp() {
        Random random = new Random(SEED);

        // Initialize distance functions
        cosineSimilarity = CosineSimilarity.getInstance();
        l2Distance = L2Distance.getInstance();
        innerProduct = InnerProduct.getInstance();

        // Generate AoS vectors
        aosVectors = new float[VECTOR_COUNT][DIMENSION];
        for (int i = 0; i < VECTOR_COUNT; i++) {
            for (int j = 0; j < DIMENSION; j++) {
                aosVectors[i][j] = random.nextFloat() * 2.0f - 1.0f;
            }
        }
        aosQuery = new float[DIMENSION];
        for (int j = 0; j < DIMENSION; j++) {
            aosQuery[j] = random.nextFloat() * 2.0f - 1.0f;
        }

        // Generate SoA block (same data, transposed layout)
        VectorLayout layout = VectorLayout.create(DIMENSION, VECTOR_COUNT);
        soaBlock = new VectorBlock(layout);
        for (int i = 0; i < VECTOR_COUNT; i++) {
            soaBlock.putVector(i, aosVectors[i]);
        }
        soaQuery = aosQuery.clone();
        soaResults = new float[VECTOR_COUNT];
    }

    @Test
    @DisplayName("Benchmark setup initializes all components correctly")
    void testSetup() {
        assertThat(aosVectors).hasDimensions(VECTOR_COUNT, DIMENSION);
        assertThat(aosQuery).hasSize(DIMENSION);
        assertThat(soaBlock).isNotNull();
        assertThat(soaBlock.dimension()).isEqualTo(DIMENSION);
        assertThat(soaBlock.vectorCount()).isEqualTo(VECTOR_COUNT);
        assertThat(soaResults).hasSize(VECTOR_COUNT);

        assertThat(cosineSimilarity).isNotNull();
        assertThat(l2Distance).isNotNull();
        assertThat(innerProduct).isNotNull();
    }

    @Test
    @DisplayName("AoS cosine similarity produces valid results")
    void testCosineSimilarityAoS() {
        int count = 0;
        for (int i = 0; i < VECTOR_COUNT; i++) {
            float result = cosineSimilarity.compute(aosQuery, aosVectors[i]);
            assertThat(result).isBetween(-1.0f, 1.0f);
            count++;
        }
        assertThat(count).isEqualTo(VECTOR_COUNT);
    }

    @Test
    @DisplayName("SoA cosine similarity produces valid results")
    void testCosineSimilaritySoA() {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.COSINE);

        for (int i = 0; i < VECTOR_COUNT; i++) {
            assertThat(soaResults[i]).isBetween(-1.0f, 1.0f);
        }
    }

    @Test
    @DisplayName("AoS and SoA cosine similarity produce equivalent results")
    void testCosineSimilarityEquivalence() {
        // Compute AoS results
        float[] aosResults = new float[VECTOR_COUNT];
        for (int i = 0; i < VECTOR_COUNT; i++) {
            aosResults[i] = cosineSimilarity.compute(aosQuery, aosVectors[i]);
        }

        // Compute SoA results
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.COSINE);

        // Verify equivalence within tolerance
        for (int i = 0; i < VECTOR_COUNT; i++) {
            assertThat(soaResults[i])
                .withFailMessage("Mismatch at index %d: AoS=%.6f, SoA=%.6f", i, aosResults[i], soaResults[i])
                .isCloseTo(aosResults[i], within(0.001f));
        }
    }

    @Test
    @DisplayName("AoS L2 distance produces valid non-negative results")
    void testL2DistanceAoS() {
        for (int i = 0; i < VECTOR_COUNT; i++) {
            float result = l2Distance.compute(aosQuery, aosVectors[i]);
            assertThat(result).isGreaterThanOrEqualTo(0.0f);
        }
    }

    @Test
    @DisplayName("SoA L2 distance produces valid non-negative results")
    void testL2DistanceSoA() {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.L2);

        for (int i = 0; i < VECTOR_COUNT; i++) {
            assertThat(soaResults[i]).isGreaterThanOrEqualTo(0.0f);
        }
    }

    @Test
    @DisplayName("AoS and SoA L2 distance produce equivalent results")
    void testL2DistanceEquivalence() {
        // Compute AoS results
        float[] aosResults = new float[VECTOR_COUNT];
        for (int i = 0; i < VECTOR_COUNT; i++) {
            aosResults[i] = l2Distance.compute(aosQuery, aosVectors[i]);
        }

        // Compute SoA results
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.L2);

        // Verify equivalence within tolerance
        for (int i = 0; i < VECTOR_COUNT; i++) {
            assertThat(soaResults[i])
                .withFailMessage("Mismatch at index %d: AoS=%.6f, SoA=%.6f", i, aosResults[i], soaResults[i])
                .isCloseTo(aosResults[i], within(0.001f));
        }
    }

    @Test
    @DisplayName("AoS inner product produces valid results")
    void testInnerProductAoS() {
        for (int i = 0; i < VECTOR_COUNT; i++) {
            float result = innerProduct.compute(aosQuery, aosVectors[i]);
            assertThat(result).isFinite();
        }
    }

    @Test
    @DisplayName("SoA inner product produces valid results")
    void testInnerProductSoA() {
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.INNER_PRODUCT);

        for (int i = 0; i < VECTOR_COUNT; i++) {
            assertThat(soaResults[i]).isFinite();
        }
    }

    @Test
    @DisplayName("AoS and SoA inner product produce equivalent results")
    void testInnerProductEquivalence() {
        // Compute AoS results
        float[] aosResults = new float[VECTOR_COUNT];
        for (int i = 0; i < VECTOR_COUNT; i++) {
            aosResults[i] = innerProduct.compute(aosQuery, aosVectors[i]);
        }

        // Compute SoA results
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.INNER_PRODUCT);

        // Verify equivalence within tolerance
        for (int i = 0; i < VECTOR_COUNT; i++) {
            assertThat(soaResults[i])
                .withFailMessage("Mismatch at index %d: AoS=%.6f, SoA=%.6f", i, aosResults[i], soaResults[i])
                .isCloseTo(aosResults[i], within(0.01f));
        }
    }

    @Test
    @DisplayName("AoS nearest neighbor search finds valid best index")
    void testNearestNeighborAoS() {
        // Cosine similarity (higher = more similar)
        int bestIdxCosine = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < VECTOR_COUNT; i++) {
            float similarity = cosineSimilarity.compute(aosQuery, aosVectors[i]);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestIdxCosine = i;
            }
        }
        assertThat(bestIdxCosine).isBetween(0, VECTOR_COUNT - 1);
        assertThat(bestSimilarity).isBetween(-1.0f, 1.0f);

        // L2 distance (lower = more similar)
        int bestIdxL2 = -1;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (int i = 0; i < VECTOR_COUNT; i++) {
            float distance = l2Distance.compute(aosQuery, aosVectors[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIdxL2 = i;
            }
        }
        assertThat(bestIdxL2).isBetween(0, VECTOR_COUNT - 1);
        assertThat(bestDistance).isGreaterThanOrEqualTo(0.0f);
    }

    @Test
    @DisplayName("SoA nearest neighbor search finds valid best index")
    void testNearestNeighborSoA() {
        // Cosine similarity
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.COSINE);
        int bestIdxCosine = -1;
        float bestSimilarity = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < VECTOR_COUNT; i++) {
            if (soaResults[i] > bestSimilarity) {
                bestSimilarity = soaResults[i];
                bestIdxCosine = i;
            }
        }
        assertThat(bestIdxCosine).isBetween(0, VECTOR_COUNT - 1);

        // L2 distance
        soaBlock.computeDistances(soaQuery, soaResults, DistanceType.L2);
        int bestIdxL2 = -1;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (int i = 0; i < VECTOR_COUNT; i++) {
            if (soaResults[i] < bestDistance) {
                bestDistance = soaResults[i];
                bestIdxL2 = i;
            }
        }
        assertThat(bestIdxL2).isBetween(0, VECTOR_COUNT - 1);
    }

    @Test
    @DisplayName("VectorBlock size calculation is correct")
    void testVectorBlockSize() {
        VectorLayout layout = VectorLayout.create(DIMENSION, VECTOR_COUNT);
        VectorBlock block = new VectorBlock(layout);

        // Size should be at least: header (64) + dimension * (vectorCount * 4 aligned to 64)
        long minExpectedSize = 64 + (long) DIMENSION * (((long) VECTOR_COUNT * 4 + 63) & ~63);
        assertThat(block.size()).isGreaterThanOrEqualTo(minExpectedSize);
    }

    @Test
    @DisplayName("VectorBlock stores and retrieves vectors correctly")
    void testVectorBlockStoreRetrieve() {
        VectorLayout layout = VectorLayout.create(DIMENSION, VECTOR_COUNT);
        VectorBlock block = new VectorBlock(layout);

        // Store vectors
        for (int i = 0; i < VECTOR_COUNT; i++) {
            block.putVector(i, aosVectors[i]);
        }

        // Retrieve and verify
        for (int i = 0; i < VECTOR_COUNT; i++) {
            float[] retrieved = block.getVector(i);
            assertThat(retrieved).containsExactly(aosVectors[i]);
        }
    }
}
