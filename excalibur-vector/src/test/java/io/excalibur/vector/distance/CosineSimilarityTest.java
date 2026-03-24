package io.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link CosineSimilarity}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Mathematical correctness (range constraints, edge cases)</li>
 *   <li>Domain correctness (sensible outputs for all inputs)</li>
 *   <li>Performance (vectorized vs scalar paths)</li>
 *   <li>Thread safety</li>
 * </ul>
 */
@DisplayName("CosineSimilarity Tests")
class CosineSimilarityTest {

    private static final float FLOAT_TOLERANCE = 1e-6f;
    private static final CosineSimilarity SIMILARITY = CosineSimilarity.getInstance();

    @Nested
    @DisplayName("Basic Functionality")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Singleton pattern returns same instance")
        void singletonPattern() {
            CosineSimilarity instance1 = CosineSimilarity.getInstance();
            CosineSimilarity instance2 = CosineSimilarity.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("Name returns 'cosine'")
        void nameReturnsCorrectValue() {
            assertThat(SIMILARITY.name()).isEqualTo("cosine");
        }

        @Test
        @DisplayName("Empty vectors throw IllegalArgumentException")
        void emptyVectors() {
            float[] empty1 = new float[0];
            float[] empty2 = new float[0];

            assertThatThrownBy(() -> SIMILARITY.compute(empty1, empty2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
        }

        @Test
        @DisplayName("Single element vectors compute correctly")
        void singleElementVectors() {
            float[] v1 = {1.0f};
            float[] v2 = {1.0f};
            float[] v3 = {-1.0f};
            float[] v4 = {0.5f};

            assertThat(SIMILARITY.compute(v1, v2)).isEqualTo(1.0f);
            assertThat(SIMILARITY.compute(v1, v3)).isEqualTo(-1.0f);
            assertThat(SIMILARITY.compute(v1, v4)).isEqualTo(1.0f); // Same direction
        }
    }

    @Nested
    @DisplayName("Domain Correctness - Range Constraints")
    class RangeConstraintTests {

        @Test
        @DisplayName("Identical normalized vectors produce 1.0")
        void identicalNormalizedVectors() {
            float[] v = {1.0f, 0.0f, 0.0f};

            assertThat(SIMILARITY.compute(v, v)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("Opposite vectors produce -1.0")
        void oppositeVectors() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {-1.0f, 0.0f, 0.0f};

            assertThat(SIMILARITY.compute(v1, v2)).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("Orthogonal vectors produce 0.0")
        void orthogonalVectors() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};

            assertThat(SIMILARITY.compute(v1, v2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Result is always in range [-1.0, 1.0]")
        void resultAlwaysInRange() {
            // Test various vector combinations
            float[][] vectors = {
                {1.0f, 2.0f, 3.0f, 4.0f, 5.0f},
                {-1.0f, -2.0f, -3.0f, -4.0f, -5.0f},
                {0.1f, 0.2f, 0.3f, 0.4f, 0.5f},
                {100.0f, 200.0f, 300.0f, 400.0f, 500.0f},
                {-100.0f, 200.0f, -300.0f, 400.0f, -500.0f}
            };

            for (float[] v1 : vectors) {
                for (float[] v2 : vectors) {
                    float result = SIMILARITY.compute(v1, v2);
                    assertThat(result)
                        .as("Similarity between %s and %s", java.util.Arrays.toString(v1), java.util.Arrays.toString(v2))
                        .isGreaterThanOrEqualTo(-1.0f)
                        .isLessThanOrEqualTo(1.0f);
                }
            }
        }

        @Test
        @DisplayName("Large vectors do not overflow")
        void largeVectorsNoOverflow() {
            // Create large vectors that could cause overflow without Kahan summation
            float[] v1 = new float[10000];
            float[] v2 = new float[10000];

            for (int i = 0; i < 10000; i++) {
                v1[i] = 1000.0f;
                v2[i] = 1000.0f;
            }

            float result = SIMILARITY.compute(v1, v2);

            // Should be 1.0 (identical direction)
            assertThat(result).isCloseTo(1.0f, within(FLOAT_TOLERANCE));
            // Must be in valid range
            assertThat(result).isGreaterThanOrEqualTo(-1.0f).isLessThanOrEqualTo(1.0f);
        }

        @ParameterizedTest
        @ValueSource(ints = {64, 128, 256, 512, 1024, 4096})
        @DisplayName("Vectorized path produces same results as scalar")
        void vectorizedMatchesScalar(int size) {
            // Create test vectors
            float[] v1 = new float[size];
            float[] v2 = new float[size];

            for (int i = 0; i < size; i++) {
                v1[i] = (float) Math.sin(i * 0.1);
                v2[i] = (float) Math.cos(i * 0.1);
            }

            float result = SIMILARITY.compute(v1, v2);

            // Compute expected value manually
            double dotProduct = 0.0;
            double norm1 = 0.0;
            double norm2 = 0.0;
            for (int i = 0; i < size; i++) {
                dotProduct += (double) v1[i] * (double) v2[i];
                norm1 += (double) v1[i] * (double) v1[i];
                norm2 += (double) v2[i] * (double) v2[i];
            }
            float expected = (float) (dotProduct / Math.sqrt(norm1 * norm2));

            assertThat(result).isCloseTo(expected, within(FLOAT_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Zero vector with non-zero vector returns 0.0")
        void zeroVectorWithNonZero() {
            float[] zero = {0.0f, 0.0f, 0.0f};
            float[] nonzero = {1.0f, 2.0f, 3.0f};

            assertThat(SIMILARITY.compute(zero, nonzero)).isEqualTo(0.0f);
            assertThat(SIMILARITY.compute(nonzero, zero)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Both zero vectors return 0.0")
        void bothZeroVectors() {
            float[] zero1 = {0.0f, 0.0f, 0.0f};
            float[] zero2 = {0.0f, 0.0f, 0.0f};

            assertThat(SIMILARITY.compute(zero1, zero2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("NaN in input throws IllegalArgumentException")
        void nanInputThrowsException() {
            float[] v1 = {1.0f, Float.NaN, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> SIMILARITY.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
        }

        @Test
        @DisplayName("Positive infinity throws IllegalArgumentException")
        void infinityInput() {
            float[] v1 = {1.0f, Float.POSITIVE_INFINITY, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> SIMILARITY.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
        }

        @Test
        @DisplayName("Very small values do not underflow to zero")
        void smallValuesNoUnderflow() {
            float[] v1 = {1e-20f, 2e-20f, 3e-20f};
            float[] v2 = {1e-20f, 2e-20f, 3e-20f};

            float result = SIMILARITY.compute(v1, v2);
            // Should still be 1.0 (same direction)
            assertThat(result).isCloseTo(1.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Mixed positive and negative values")
        void mixedSigns() {
            float[] v1 = {1.0f, -2.0f, 3.0f, -4.0f, 5.0f};
            float[] v2 = {-1.0f, 2.0f, -3.0f, 4.0f, -5.0f};

            float result = SIMILARITY.compute(v1, v2);
            // These are opposite vectors, should be -1.0
            assertThat(result).isCloseTo(-1.0f, within(FLOAT_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Null first vector throws exception")
        void nullFirstVector() {
            float[] v2 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> SIMILARITY.compute(null, v2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("v1");
        }

        @Test
        @DisplayName("Null second vector throws exception")
        void nullSecondVector() {
            float[] v1 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> SIMILARITY.compute(v1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("v2");
        }

        @Test
        @DisplayName("Mismatched dimensions throws exception")
        void mismatchedDimensions() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f};

            assertThatThrownBy(() -> SIMILARITY.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must match");
        }
    }

    @Nested
    @DisplayName("With Pre-computed Norms")
    class PrecomputedNormsTests {

        @Test
        @DisplayName("Pre-computed norms produce same result")
        void precomputedNormsMatch() {
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f};

            double norm1Sq = SIMILARITY.computeSquaredNorm(v1);
            double norm2Sq = SIMILARITY.computeSquaredNorm(v2);

            float resultWithNorms = SIMILARITY.computeWithNorms(v1, v2, norm1Sq, norm2Sq);
            float resultDirect = SIMILARITY.compute(v1, v2);

            assertThat(resultWithNorms).isCloseTo(resultDirect, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Zero norm returns 0.0")
        void zeroNormReturnsZero() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            float result = SIMILARITY.computeWithNorms(v1, v2, 0.0, 10.0);
            assertThat(result).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Negative norm throws exception")
        void negativeNormThrowsException() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> SIMILARITY.computeWithNorms(v1, v2, -1.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    @Nested
    @DisplayName("Normalization Utilities")
    class NormalizationTests {

        @Test
        @DisplayName("Squared norm computed correctly")
        void squaredNormCorrect() {
            float[] v = {3.0f, 4.0f};
            // 3^2 + 4^2 = 9 + 16 = 25

            assertThat(SIMILARITY.computeSquaredNorm(v)).isEqualTo(25.0);
        }

        @Test
        @DisplayName("normalizeInPlace produces unit vector")
        void normalizeInPlaceProducesUnitVector() {
            float[] v = {3.0f, 4.0f};
            SIMILARITY.normalizeInPlace(v);

            // After normalization: (3/5, 4/5) = (0.6, 0.8)
            assertThat(v[0]).isCloseTo(0.6f, within(FLOAT_TOLERANCE));
            assertThat(v[1]).isCloseTo(0.8f, within(FLOAT_TOLERANCE));

            // Check normalized (within float precision tolerance)
            assertThat(SIMILARITY.computeSquaredNorm(v)).isCloseTo(1.0, within(1e-6));
        }

        @Test
        @DisplayName("normalize returns new vector")
        void normalizeReturnsNewVector() {
            float[] original = {3.0f, 4.0f};
            float[] normalized = SIMILARITY.normalize(original);

            // Original unchanged
            assertThat(original[0]).isEqualTo(3.0f);
            assertThat(original[1]).isEqualTo(4.0f);

            // New vector normalized
            assertThat(normalized[0]).isCloseTo(0.6f, within(FLOAT_TOLERANCE));
            assertThat(normalized[1]).isCloseTo(0.8f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("isNormalized detects unit vectors")
        void isNormalizedDetectsUnitVectors() {
            float[] unitVector = {1.0f, 0.0f, 0.0f};
            float[] nonUnitVector = {2.0f, 0.0f, 0.0f};

            assertThat(SIMILARITY.isNormalized(unitVector, 1e-6f)).isTrue();
            assertThat(SIMILARITY.isNormalized(nonUnitVector, 1e-6f)).isFalse();
        }

        @Test
        @DisplayName("Normalizing zero vector throws exception")
        void normalizeZeroVectorThrows() {
            float[] zero = {0.0f, 0.0f, 0.0f};

            assertThatThrownBy(() -> SIMILARITY.normalizeInPlace(zero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero vector");

            assertThatThrownBy(() -> SIMILARITY.normalize(zero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero vector");
        }

        @Test
        @DisplayName("Normalizing empty vector throws exception")
        void normalizeEmptyVectorThrows() {
            float[] empty = new float[0];

            assertThatThrownBy(() -> SIMILARITY.normalizeInPlace(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-length");
        }

        @Test
        @DisplayName("computeSquaredNorm null throws NPE")
        void computeSquaredNormNull() {
            assertThatThrownBy(() -> SIMILARITY.computeSquaredNorm(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Vector must not be null");
        }

        @Test
        @DisplayName("isNormalized null throws NPE")
        void isNormalizedNull() {
            assertThatThrownBy(() -> SIMILARITY.isNormalized(null, 0.01f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Vector must not be null");
        }

        @Test
        @DisplayName("normalizeInPlace null throws NPE")
        void normalizeInPlaceNull() {
            assertThatThrownBy(() -> SIMILARITY.normalizeInPlace(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Vector must not be null");
        }

        @Test
        @DisplayName("normalize null throws NPE")
        void normalizeNull() {
            assertThatThrownBy(() -> SIMILARITY.normalize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Vector must not be null");
        }

        @Test
        @DisplayName("computeSquaredNorm with large vector uses vectorized path")
        void computeSquaredNormLargeVector() {
            float[] v = new float[128];
            for (int i = 0; i < 128; i++) {
                v[i] = 1.0f;
            }

            double normSq = SIMILARITY.computeSquaredNorm(v);

            assertThat(normSq).isEqualTo(128.0);
        }

        @Test
        @DisplayName("computeWithNorms null first vector throws NPE")
        void computeWithNormsNullFirst() {
            float[] v2 = {1.0f, 2.0f, 3.0f};
            assertThatThrownBy(() -> SIMILARITY.computeWithNorms(null, v2, 10.0, 10.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("First vector");
        }

        @Test
        @DisplayName("computeWithNorms null second vector throws NPE")
        void computeWithNormsNullSecond() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            assertThatThrownBy(() -> SIMILARITY.computeWithNorms(v1, null, 10.0, 10.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Second vector");
        }

        @Test
        @DisplayName("computeWithNorms mismatched dimensions throws IAE")
        void computeWithNormsMismatchedDimensions() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f};
            assertThatThrownBy(() -> SIMILARITY.computeWithNorms(v1, v2, 10.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must match");
        }

        @Test
        @DisplayName("computeWithNorms both zero norms returns 0")
        void computeWithNormsBothZeroNorms() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};
            float result = SIMILARITY.computeWithNorms(v1, v2, 0.0, 0.0);
            assertThat(result).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("computeWithNorms second negative norm throws IAE")
        void computeWithNormsSecondNegativeNorm() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};
            assertThatThrownBy(() -> SIMILARITY.computeWithNorms(v1, v2, 10.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent access is safe")
        void concurrentAccess() throws InterruptedException {
            final int numThreads = 10;
            final int iterations = 1000;

            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f};

            // Pre-compute expected value
            float expected = SIMILARITY.compute(v1, v2);

            Thread[] threads = new Thread[numThreads];
            final boolean[] failures = new boolean[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        float result = SIMILARITY.compute(v1, v2);
                        if (Math.abs(result - expected) > FLOAT_TOLERANCE) {
                            failures[threadId] = true;
                            break;
                        }
                    }
                });
            }

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }

            for (boolean failed : failures) {
                assertThat(failed).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Mathematical Properties")
    class MathematicalPropertiesTests {

        @Test
        @DisplayName("Symmetry: similarity(a, b) == similarity(b, a)")
        void symmetry() {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float[] b = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f};

            float simAB = SIMILARITY.compute(a, b);
            float simBA = SIMILARITY.compute(b, a);

            assertThat(simAB).isCloseTo(simBA, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Self-similarity is 1.0 for non-zero vectors")
        void selfSimilarity() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

            assertThat(SIMILARITY.compute(v, v)).isCloseTo(1.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Scalar multiplication preserves similarity")
        void scalarMultiplicationPreservesSimilarity() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f};

            float[] v1Scaled = {10.0f, 20.0f, 30.0f}; // v1 * 10

            float simOriginal = SIMILARITY.compute(v1, v2);
            float simScaled = SIMILARITY.compute(v1Scaled, v2);

            assertThat(simScaled).isCloseTo(simOriginal, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Normalized vectors have similarity equal to dot product")
        void normalizedSimilarityEqualsDotProduct() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {4.0f, 5.0f, 6.0f};

            float[] v1Norm = SIMILARITY.normalize(v1);
            float[] v2Norm = SIMILARITY.normalize(v2);

            float similarity = SIMILARITY.compute(v1Norm, v2Norm);

            // For normalized vectors, dot product equals cosine similarity
            double dotProduct = 0.0;
            for (int i = 0; i < v1Norm.length; i++) {
                dotProduct += v1Norm[i] * v2Norm[i];
            }

            assertThat(similarity).isCloseTo((float) dotProduct, within(FLOAT_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("Floating Point Precision")
    class FloatingPointPrecisionTests {

        @Test
        @DisplayName("Result clamped when floating point error exceeds 1.0")
        void resultClampedToUpperBound() {
            // Create vectors that might produce result slightly > 1.0 due to FP errors
            float[] v = new float[1000];
            for (int i = 0; i < 1000; i++) {
                v[i] = (float) Math.random();
            }

            // Self-similarity should be exactly 1.0, not 1.0000001
            float result = SIMILARITY.compute(v, v);
            assertThat(result).isLessThanOrEqualTo(1.0f);
            assertThat(result).isCloseTo(1.0f, within(1e-5f));
        }

        @Test
        @DisplayName("Result clamped when floating point error goes below -1.0")
        void resultClampedToLowerBound() {
            // Create opposite vectors that might produce result slightly < -1.0
            float[] v1 = new float[1000];
            float[] v2 = new float[1000];
            for (int i = 0; i < 1000; i++) {
                v1[i] = (float) Math.random();
                v2[i] = -v1[i];
            }

            float result = SIMILARITY.compute(v1, v2);
            assertThat(result).isGreaterThanOrEqualTo(-1.0f);
            assertThat(result).isCloseTo(-1.0f, within(1e-5f));
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("toString returns class name with mode indicator")
        void toStringReturnsClassName() {
            // toString includes mode indicator: "CosineSimilarity(vectorized)" or "CosineSimilarity(scalar)"
            assertThat(SIMILARITY.toString()).startsWith("CosineSimilarity");
            assertThat(SIMILARITY.toString()).matches("CosineSimilarity\\((vectorized|scalar)\\)");
        }
    }
}
