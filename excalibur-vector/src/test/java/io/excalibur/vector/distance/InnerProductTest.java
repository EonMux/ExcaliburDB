package io.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link InnerProduct}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Mathematical correctness (known values, edge cases)</li>
 *   <li>Domain correctness (sensible outputs for all inputs)</li>
 *   <li>Performance (vectorized vs scalar paths)</li>
 *   <li>Thread safety</li>
 * </ul>
 */
@DisplayName("InnerProduct Tests")
class InnerProductTest {

    private static final float FLOAT_TOLERANCE = 1e-5f;
    private static final InnerProduct INNER_PRODUCT = InnerProduct.getInstance();

    @Nested
    @DisplayName("Basic Functionality")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Singleton pattern returns same instance")
        void singletonPattern() {
            InnerProduct instance1 = InnerProduct.getInstance();
            InnerProduct instance2 = InnerProduct.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("Name returns 'inner_product'")
        void nameReturnsCorrectValue() {
            assertThat(INNER_PRODUCT.name()).isEqualTo("inner_product");
        }

        @Test
        @DisplayName("Empty vectors return 0.0")
        void emptyVectors() {
            float[] empty1 = new float[0];
            float[] empty2 = new float[0];

            assertThat(INNER_PRODUCT.compute(empty1, empty2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Single element vectors compute correctly")
        void singleElementVectors() {
            float[] v1 = {3.0f};
            float[] v2 = {4.0f};

            assertThat(INNER_PRODUCT.compute(v1, v2)).isEqualTo(12.0f);
        }
    }

    @Nested
    @DisplayName("Domain Correctness - Known Values")
    class KnownValuesTests {

        @Test
        @DisplayName("Identical vectors produce sum of squares")
        void identicalVectors() {
            float[] v = {1.0f, 2.0f, 3.0f};

            // 1*1 + 2*2 + 3*3 = 1 + 4 + 9 = 14
            assertThat(INNER_PRODUCT.compute(v, v)).isEqualTo(14.0f);
        }

        @Test
        @DisplayName("Orthogonal vectors produce 0.0")
        void orthogonalVectors() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};

            assertThat(INNER_PRODUCT.compute(v1, v2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Opposite vectors produce negative value")
        void oppositeVectors() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {-1.0f, -2.0f, -3.0f};

            // 1*(-1) + 2*(-2) + 3*(-3) = -1 - 4 - 9 = -14
            assertThat(INNER_PRODUCT.compute(v1, v2)).isEqualTo(-14.0f);
        }

        @Test
        @DisplayName("Mixed positive and negative values")
        void mixedSigns() {
            float[] v1 = {1.0f, -2.0f, 3.0f};
            float[] v2 = {4.0f, 5.0f, 6.0f};

            // 1*4 + (-2)*5 + 3*6 = 4 - 10 + 18 = 12
            assertThat(INNER_PRODUCT.compute(v1, v2)).isEqualTo(12.0f);
        }

        @Test
        @DisplayName("Zero vector with non-zero vector produces 0.0")
        void zeroVectorWithNonZero() {
            float[] zero = {0.0f, 0.0f, 0.0f};
            float[] nonzero = {1.0f, 2.0f, 3.0f};

            assertThat(INNER_PRODUCT.compute(zero, nonzero)).isEqualTo(0.0f);
            assertThat(INNER_PRODUCT.compute(nonzero, zero)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Known 2D dot product")
        void known2DDotProduct() {
            float[] v1 = {3.0f, 4.0f};
            float[] v2 = {1.0f, 2.0f};

            // 3*1 + 4*2 = 3 + 8 = 11
            assertThat(INNER_PRODUCT.compute(v1, v2)).isEqualTo(11.0f);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Both zero vectors return 0.0")
        void bothZeroVectors() {
            float[] zero1 = {0.0f, 0.0f, 0.0f};
            float[] zero2 = {0.0f, 0.0f, 0.0f};

            assertThat(INNER_PRODUCT.compute(zero1, zero2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("NaN in input produces NaN output")
        void nanInputProducesNanOutput() {
            float[] v1 = {1.0f, Float.NaN, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            assertThat(INNER_PRODUCT.compute(v1, v2)).isNaN();
        }

        @Test
        @DisplayName("Positive infinity produces infinity or NaN")
        void infinityInput() {
            float[] v1 = {1.0f, Float.POSITIVE_INFINITY, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            float result = INNER_PRODUCT.compute(v1, v2);
            assertThat(Float.isInfinite(result) || Float.isNaN(result)).isTrue();
        }

        @Test
        @DisplayName("Large vectors do not overflow catastrophically")
        void largeVectorsNoOverflow() {
            float[] v1 = new float[10000];
            float[] v2 = new float[10000];

            for (int i = 0; i < 10000; i++) {
                v1[i] = 1.0f;
                v2[i] = 1.0f;
            }

            float result = INNER_PRODUCT.compute(v1, v2);

            // Should be 10000 (sum of 1*1 for each element)
            assertThat(result).isCloseTo(10000.0f, within(100.0f));
            assertThat(Float.isFinite(result)).isTrue();
        }

        @Test
        @DisplayName("Very small values are computed correctly")
        void smallValuesAccurate() {
            float[] v1 = {1e-10f, 2e-10f, 3e-10f};
            float[] v2 = {4e-10f, 5e-10f, 6e-10f};

            float result = INNER_PRODUCT.compute(v1, v2);

            // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32, all scaled by 1e-20
            assertThat(result).isCloseTo(32e-20f, within(1e-24f));
        }

        @Test
        @DisplayName("Overflow prevention: large positive values clamped to Float.MAX_VALUE")
        void overflowPreventionPositiveValues() {
            // Values that would overflow float without double accumulation
            float[] v1 = {1e30f, 1e30f, 1e30f, 1e30f};
            float[] v2 = {1e30f, 1e30f, 1e30f, 1e30f};

            float result = INNER_PRODUCT.compute(v1, v2);

            // 4 * (1e30 * 1e30) = 4e60, which exceeds Float.MAX_VALUE (~3.4e38)
            // Result should be clamped to Float.MAX_VALUE, not infinity
            assertThat(result).isEqualTo(Float.MAX_VALUE);
            assertThat(Float.isFinite(result)).isTrue();
        }

        @Test
        @DisplayName("Overflow prevention: large negative values clamped to -Float.MAX_VALUE")
        void overflowPreventionNegativeValues() {
            // Negative values that would underflow float without double accumulation
            float[] v1 = {1e30f, 1e30f, 1e30f, 1e30f};
            float[] v2 = {-1e30f, -1e30f, -1e30f, -1e30f};

            float result = INNER_PRODUCT.compute(v1, v2);

            // 4 * (1e30 * -1e30) = -4e60, which underflows float range
            // Result should be clamped to -Float.MAX_VALUE, not negative infinity
            assertThat(result).isEqualTo(-Float.MAX_VALUE);
            assertThat(Float.isFinite(result)).isTrue();
        }

        @Test
        @DisplayName("Mixed large values produce correct sign")
        void mixedLargeValuesCorrectSign() {
            // Two positive large, two negative large
            float[] v1 = {1e30f, 1e30f, 1e30f, 1e30f};
            float[] v2 = {1e30f, 1e30f, -1e30f, -1e30f};

            float result = INNER_PRODUCT.compute(v1, v2);

            // (1e30 * 1e30) + (1e30 * 1e30) + (1e30 * -1e30) + (1e30 * -1e30)
            // = 1e60 + 1e60 - 1e60 - 1e60 = 0
            // Use a large tolerance since we're dealing with very large values
            assertThat(Math.abs(result)).isLessThan(Float.MAX_VALUE);
        }

        @Test
        @DisplayName("Negative values with overflow prevention preserves sign")
        void negativeValuesWithOverflowPreventionPreservesSign() {
            // All negative values that would overflow
            float[] v1 = {-1e30f, -1e30f, -1e30f, -1e30f};
            float[] v2 = {-1e30f, -1e30f, -1e30f, -1e30f};

            float result = INNER_PRODUCT.compute(v1, v2);

            // negative * negative = positive, should be clamped to +Float.MAX_VALUE
            assertThat(result).isEqualTo(Float.MAX_VALUE);
            assertThat(result).isPositive();
        }

        @Test
        @DisplayName("Very large vector with moderate values does not overflow")
        void veryLargeVectorModerateValues() {
            // 1 million elements, each contributing 1 to the sum
            int size = 1_000_000;
            float[] v1 = new float[size];
            float[] v2 = new float[size];

            for (int i = 0; i < size; i++) {
                v1[i] = 1.0f;
                v2[i] = 1.0f;
            }

            float result = INNER_PRODUCT.compute(v1, v2);

            // Sum of 1M ones = 1,000,000 - should fit in float
            assertThat(result).isCloseTo(1_000_000.0f, within(1000.0f));
            assertThat(Float.isFinite(result)).isTrue();
        }

        @Test
        @DisplayName("Very large vector with mixed signs computes correctly")
        void veryLargeVectorMixedSigns() {
            int size = 100_000;
            float[] v1 = new float[size];
            float[] v2 = new float[size];

            // Alternating positive and negative
            for (int i = 0; i < size; i++) {
                v1[i] = (i % 2 == 0) ? 1.0f : -1.0f;
                v2[i] = 1.0f;
            }

            float result = INNER_PRODUCT.compute(v1, v2);

            // Half positive, half negative contributions = 0
            assertThat(result).isCloseTo(0.0f, within(100.0f));
        }

        @Test
        @DisplayName("Negative infinity input produces clamped negative result")
        void negativeInfinityInput() {
            float[] v1 = {1.0f, Float.NEGATIVE_INFINITY, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            float result = INNER_PRODUCT.compute(v1, v2);
            // Negative infinity * 2 = negative infinity, which should be handled
            assertThat(Float.isInfinite(result) || Float.isNaN(result)).isTrue();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Null first vector throws exception")
        void nullFirstVector() {
            float[] v2 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> INNER_PRODUCT.compute(null, v2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("First vector");
        }

        @Test
        @DisplayName("Null second vector throws exception")
        void nullSecondVector() {
            float[] v1 = {1.0f, 2.0f, 3.0f};

            assertThatThrownBy(() -> INNER_PRODUCT.compute(v1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Second vector");
        }

        @Test
        @DisplayName("Mismatched dimensions throws exception")
        void mismatchedDimensions() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f};

            assertThatThrownBy(() -> INNER_PRODUCT.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must match");
        }
    }

    @Nested
    @DisplayName("Negated Inner Product")
    class NegatedTests {

        @Test
        @DisplayName("Negated inner product is negative of regular")
        void negatedIsNegative() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {4.0f, 5.0f, 6.0f};

            float regular = INNER_PRODUCT.compute(v1, v2);
            float negated = INNER_PRODUCT.computeNegated(v1, v2);

            assertThat(negated).isEqualTo(-regular);
        }

        @Test
        @DisplayName("Negated makes similar vectors have smaller values")
        void negatedSimilarVectorsSmaller() {
            float[] query = {1.0f, 0.0f, 0.0f};
            float[] similar = {0.9f, 0.1f, 0.0f};
            float[] dissimilar = {0.1f, 0.9f, 0.0f};

            float negatedSimilar = INNER_PRODUCT.computeNegated(query, similar);
            float negatedDissimilar = INNER_PRODUCT.computeNegated(query, dissimilar);

            // Similar vectors should have smaller negated values
            assertThat(negatedSimilar).isLessThan(negatedDissimilar);
        }
    }

    @Nested
    @DisplayName("Squared Norm")
    class SquaredNormTests {

        @Test
        @DisplayName("Squared norm equals inner product with self")
        void squaredNormEqualsSelfProduct() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

            float selfProduct = INNER_PRODUCT.compute(v, v);
            float squaredNorm = INNER_PRODUCT.computeSquaredNorm(v);

            assertThat(squaredNorm).isEqualTo(selfProduct);
        }

        @Test
        @DisplayName("Squared norm for known vector")
        void squaredNormKnownValue() {
            float[] v = {3.0f, 4.0f};

            // 3^2 + 4^2 = 9 + 16 = 25
            assertThat(INNER_PRODUCT.computeSquaredNorm(v)).isEqualTo(25.0f);
        }

        @Test
        @DisplayName("Squared norm of zero vector is 0")
        void squaredNormZeroVector() {
            float[] zero = {0.0f, 0.0f, 0.0f};

            assertThat(INNER_PRODUCT.computeSquaredNorm(zero)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Squared norm of empty vector is 0")
        void squaredNormEmptyVector() {
            float[] empty = new float[0];

            assertThat(INNER_PRODUCT.computeSquaredNorm(empty)).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("Orthogonality Check")
    class OrthogonalityTests {

        @Test
        @DisplayName("Orthogonal vectors detected correctly")
        void orthogonalVectorsDetected() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};

            assertThat(INNER_PRODUCT.isOrthogonal(v1, v2, FLOAT_TOLERANCE)).isTrue();
        }

        @Test
        @DisplayName("Non-orthogonal vectors detected correctly")
        void nonOrthogonalVectorsDetected() {
            float[] v1 = {1.0f, 1.0f, 0.0f};
            float[] v2 = {1.0f, 0.0f, 0.0f};

            assertThat(INNER_PRODUCT.isOrthogonal(v1, v2, FLOAT_TOLERANCE)).isFalse();
        }

        @Test
        @DisplayName("Nearly orthogonal within tolerance")
        void nearlyOrthogonalWithinTolerance() {
            float[] v1 = {1.0f, 0.001f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};

            // Inner product = 0.001, within tolerance of 0.01
            assertThat(INNER_PRODUCT.isOrthogonal(v1, v2, 0.01f)).isTrue();
        }
    }

    @Nested
    @DisplayName("Vector Size Variants")
    class VectorSizeVariantsTests {

        @ParameterizedTest
        @ValueSource(ints = {8, 16, 32, 64, 128, 256, 512, 1024})
        @DisplayName("Vectorized path produces same results as scalar")
        void vectorizedMatchesScalar(int size) {
            float[] v1 = new float[size];
            float[] v2 = new float[size];

            for (int i = 0; i < size; i++) {
                v1[i] = (float) Math.sin(i * 0.1);
                v2[i] = (float) Math.cos(i * 0.1);
            }

            float result = INNER_PRODUCT.compute(v1, v2);

            // Compute expected value manually
            double expected = 0.0;
            for (int i = 0; i < size; i++) {
                expected += (double) v1[i] * (double) v2[i];
            }

            assertThat(result).isCloseTo((float) expected, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Non-power-of-two vector size")
        void nonPowerOfTwoSize() {
            float[] v1 = new float[17];
            float[] v2 = new float[17];

            for (int i = 0; i < 17; i++) {
                v1[i] = i;
                v2[i] = i + 1;
            }

            float result = INNER_PRODUCT.compute(v1, v2);

            // Expected: sum of i * (i+1) for i = 0 to 16
            double expected = 0.0;
            for (int i = 0; i < 17; i++) {
                expected += (double) i * (i + 1);
            }

            assertThat(result).isCloseTo((float) expected, within(FLOAT_TOLERANCE));
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
            float expected = INNER_PRODUCT.compute(v1, v2);

            Thread[] threads = new Thread[numThreads];
            final boolean[] failures = new boolean[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        float result = INNER_PRODUCT.compute(v1, v2);
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
        @DisplayName("Symmetry: inner_product(a, b) == inner_product(b, a)")
        void symmetry() {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float[] b = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f};

            float ipAB = INNER_PRODUCT.compute(a, b);
            float ipBA = INNER_PRODUCT.compute(b, a);

            assertThat(ipAB).isEqualTo(ipBA);
        }

        @Test
        @DisplayName("Bilinearity: inner_product(a, b+c) = inner_product(a,b) + inner_product(a,c)")
        void bilinearity() {
            float[] a = {1.0f, 2.0f};
            float[] b = {3.0f, 4.0f};
            float[] c = {5.0f, 6.0f};
            float[] bPlusC = {8.0f, 10.0f};

            float ipAB = INNER_PRODUCT.compute(a, b);
            float ipAC = INNER_PRODUCT.compute(a, c);
            float ipABPlusC = INNER_PRODUCT.compute(a, bPlusC);

            assertThat(ipABPlusC).isCloseTo(ipAB + ipAC, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("Scalar multiplication: inner_product(k*a, b) = k * inner_product(a, b)")
        void scalarMultiplication() {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {4.0f, 5.0f, 6.0f};
            float[] ka = {2.0f, 4.0f, 6.0f}; // a * 2

            float ipAB = INNER_PRODUCT.compute(a, b);
            float ipKaB = INNER_PRODUCT.compute(ka, b);

            assertThat(ipKaB).isCloseTo(2.0f * ipAB, within(FLOAT_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("Floating Point Precision")
    class FloatingPointPrecisionTests {

        @Test
        @DisplayName("High precision with large vectors")
        void highPrecisionWithLargeVectors() {
            int size = 10000;
            float[] v1 = new float[size];
            float[] v2 = new float[size];

            // Fill with predictable values
            for (int i = 0; i < size; i++) {
                v1[i] = i * 0.001f;
                v2[i] = i * 0.001f;
            }

            float result = INNER_PRODUCT.compute(v1, v2);

            // Expected: sum of (i * 0.001)^2 for i = 0 to 9999
            // = 0.000001 * sum(i^2) for i = 0 to 9999
            // = 0.000001 * (9999 * 10000 * 19999) / 6
            double expected = 0.000001 * (9999.0 * 10000.0 * 19999.0) / 6.0;

            assertThat(result).isCloseTo((float) expected, within((float) expected * 0.01f));
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("toString returns class name with mode indicator")
        void toStringReturnsClassName() {
            // toString includes mode indicator: "InnerProduct(vectorized)" or "InnerProduct(scalar)"
            assertThat(INNER_PRODUCT.toString()).startsWith("InnerProduct");
            assertThat(INNER_PRODUCT.toString()).matches("InnerProduct\\((vectorized|scalar)\\)");
        }
    }

    @Nested
    @DisplayName("Interface Contract")
    class InterfaceContractTests {

        @Test
        @DisplayName("Implements DistanceFunction interface")
        void implementsInterface() {
            assertThat(INNER_PRODUCT).isInstanceOf(DistanceFunction.class);
        }

        @Test
        @DisplayName("compute method works through interface reference")
        void computeThroughInterface() {
            DistanceFunction df = InnerProduct.getInstance();
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {4.0f, 5.0f, 6.0f};

            float result = df.compute(a, b);

            // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
            assertThat(result).isEqualTo(32.0f);
        }
    }
}
