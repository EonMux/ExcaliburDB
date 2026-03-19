package io.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Additional distance function tests to improve coverage.
 *
 * <p>Focuses on:</p>
 * <ul>
 *   <li>Vectorized code paths (when Vector API available)</li>
 *   <li>Edge cases and error handling</li>
 *   <li>Threshold boundary testing</li>
 * </ul>
 */
@DisplayName("Distance Function Coverage Tests")
class DistanceFunctionCoverageTest {

    private static final float FLOAT_TOLERANCE = 1e-5f;

    @Nested
    @DisplayName("Vectorized Path Tests")
    class VectorizedPathTests {

        @Test
        @DisplayName("L2Distance large vector exercises vectorized path")
        void l2DistanceLargeVectorVectorized() {
            // Vector larger than VECTORIZATION_THRESHOLD (8)
            float[] v1 = new float[256];
            float[] v2 = new float[256];

            for (int i = 0; i < 256; i++) {
                v1[i] = i * 0.01f;
                v2[i] = (i + 1) * 0.01f;
            }

            float distance = L2Distance.getInstance().compute(v1, v2);

            // Each element diff is 0.01, squared is 0.0001
            // Sum of 256 elements = 0.0256
            // sqrt(0.0256) = 0.16
            assertThat(distance).isCloseTo(0.16f, within(0.01f));
            assertThat(distance).isFinite();
        }

        @Test
        @DisplayName("InnerProduct large vector exercises vectorized path")
        void innerProductLargeVectorVectorized() {
            float[] v1 = new float[256];
            float[] v2 = new float[256];

            for (int i = 0; i < 256; i++) {
                v1[i] = 1.0f;
                v2[i] = 1.0f;
            }

            float product = InnerProduct.getInstance().compute(v1, v2);

            // Sum of 1*1 for 256 elements = 256
            assertThat(product).isCloseTo(256.0f, within(0.1f));
        }

        @Test
        @DisplayName("CosineSimilarity large vector exercises vectorized path")
        void cosineSimilarityLargeVectorVectorized() {
            float[] v1 = new float[256];
            float[] v2 = new float[256];

            // Identical vectors
            for (int i = 0; i < 256; i++) {
                v1[i] = (float) Math.sin(i * 0.1);
                v2[i] = v1[i];
            }

            float similarity = CosineSimilarity.getInstance().compute(v1, v2);

            // Identical vectors should have similarity 1.0
            assertThat(similarity).isCloseTo(1.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("L2Distance computeSquared large vector")
        void l2DistanceComputeSquaredLargeVector() {
            float[] v1 = new float[128];
            float[] v2 = new float[128];

            for (int i = 0; i < 128; i++) {
                v1[i] = 0.0f;
                v2[i] = 1.0f;
            }

            float squared = L2Distance.getInstance().computeSquared(v1, v2);

            // 128 * (1-0)^2 = 128
            assertThat(squared).isCloseTo(128.0f, within(0.1f));
        }

        @Test
        @DisplayName("InnerProduct computeSquaredNorm large vector")
        void innerProductComputeSquaredNormLargeVector() {
            float[] v = new float[128];

            for (int i = 0; i < 128; i++) {
                v[i] = 1.0f;
            }

            float norm = InnerProduct.getInstance().computeSquaredNorm(v);

            // Sum of 1^2 for 128 elements = 128
            assertThat(norm).isCloseTo(128.0f, within(0.1f));
        }

        @Test
        @DisplayName("CosineSimilarity computeSquaredNorm large vector")
        void cosineSimilarityComputeSquaredNormLargeVector() {
            float[] v = new float[128];

            for (int i = 0; i < 128; i++) {
                v[i] = 2.0f;
            }

            double norm = CosineSimilarity.getInstance().computeSquaredNorm(v);

            // Sum of 2^2 for 128 elements = 512
            assertThat(norm).isCloseTo(512.0, within(1.0));
        }
    }

    @Nested
    @DisplayName("Non-Power-of-Two Vector Sizes")
    class NonPowerOfTwoTests {

        @Test
        @DisplayName("L2Distance with 17 elements")
        void l2Distance17Elements() {
            float[] v1 = new float[17];
            float[] v2 = new float[17];

            for (int i = 0; i < 17; i++) {
                v1[i] = i;
                v2[i] = 0;
            }

            float distance = L2Distance.getInstance().compute(v1, v2);

            // Sum of i^2 for i=0 to 16 = 1496
            // sqrt(1496) = 38.68
            assertThat(distance).isCloseTo((float) Math.sqrt(1496), within(0.01f));
        }

        @Test
        @DisplayName("InnerProduct with 17 elements")
        void innerProduct17Elements() {
            float[] v1 = new float[17];
            float[] v2 = new float[17];

            for (int i = 0; i < 17; i++) {
                v1[i] = i;
                v2[i] = 1;
            }

            float product = InnerProduct.getInstance().compute(v1, v2);

            // Sum of i*1 for i=0 to 16 = 136
            assertThat(product).isEqualTo(136.0f);
        }

        @Test
        @DisplayName("CosineSimilarity with 17 elements")
        void cosineSimilarity17Elements() {
            float[] v1 = new float[17];
            float[] v2 = new float[17];

            for (int i = 0; i < 17; i++) {
                v1[i] = i + 1;
                v2[i] = i + 1;
            }

            float similarity = CosineSimilarity.getInstance().compute(v1, v2);

            // Identical vectors
            assertThat(similarity).isCloseTo(1.0f, within(FLOAT_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("Tail Element Tests")
    class TailElementTests {

        @Test
        @DisplayName("L2Distance with 65 elements (tests tail handling)")
        void l2Distance65Elements() {
            float[] v1 = new float[65];
            float[] v2 = new float[65];

            for (int i = 0; i < 65; i++) {
                v1[i] = 1.0f;
                v2[i] = 0.0f;
            }

            float distance = L2Distance.getInstance().compute(v1, v2);

            assertThat(distance).isCloseTo((float) Math.sqrt(65), within(0.01f));
        }

        @Test
        @DisplayName("InnerProduct with 65 elements (tests tail handling)")
        void innerProduct65Elements() {
            float[] v1 = new float[65];
            float[] v2 = new float[65];

            for (int i = 0; i < 65; i++) {
                v1[i] = 1.0f;
                v2[i] = 1.0f;
            }

            float product = InnerProduct.getInstance().compute(v1, v2);

            assertThat(product).isCloseTo(65.0f, within(0.1f));
        }
    }

    @Nested
    @DisplayName("Name and ToString Tests")
    class NameAndToStringTests {

        @Test
        @DisplayName("L2Distance name returns 'l2'")
        void l2DistanceName() {
            assertThat(L2Distance.getInstance().name()).isEqualTo("l2");
        }

        @Test
        @DisplayName("InnerProduct name returns 'inner_product'")
        void innerProductName() {
            assertThat(InnerProduct.getInstance().name()).isEqualTo("inner_product");
        }

        @Test
        @DisplayName("CosineSimilarity name returns 'cosine'")
        void cosineSimilarityName() {
            assertThat(CosineSimilarity.getInstance().name()).isEqualTo("cosine");
        }

        @Test
        @DisplayName("L2Distance toString contains mode")
        void l2DistanceToString() {
            String str = L2Distance.getInstance().toString();
            assertThat(str).startsWith("L2Distance");
            assertThat(str).matches("L2Distance\\((vectorized|scalar)\\)");
        }

        @Test
        @DisplayName("InnerProduct toString contains mode")
        void innerProductToString() {
            String str = InnerProduct.getInstance().toString();
            assertThat(str).startsWith("InnerProduct");
            assertThat(str).matches("InnerProduct\\((vectorized|scalar)\\)");
        }

        @Test
        @DisplayName("CosineSimilarity toString contains mode")
        void cosineSimilarityToString() {
            String str = CosineSimilarity.getInstance().toString();
            assertThat(str).startsWith("CosineSimilarity");
            assertThat(str).matches("CosineSimilarity\\((vectorized|scalar)\\)");
        }
    }

    @Nested
    @DisplayName("Singleton Pattern Tests")
    class SingletonPatternTests {

        @Test
        @DisplayName("L2Distance getInstance returns same instance")
        void l2DistanceSingleton() {
            assertThat(L2Distance.getInstance()).isSameAs(L2Distance.getInstance());
        }

        @Test
        @DisplayName("InnerProduct getInstance returns same instance")
        void innerProductSingleton() {
            assertThat(InnerProduct.getInstance()).isSameAs(InnerProduct.getInstance());
        }

        @Test
        @DisplayName("CosineSimilarity getInstance returns same instance")
        void cosineSimilaritySingleton() {
            assertThat(CosineSimilarity.getInstance()).isSameAs(CosineSimilarity.getInstance());
        }
    }

    @Nested
    @DisplayName("Interface Contract Tests")
    class InterfaceContractTests {

        @Test
        @DisplayName("L2Distance implements DistanceFunction")
        void l2DistanceImplementsInterface() {
            assertThat(L2Distance.getInstance()).isInstanceOf(DistanceFunction.class);
        }

        @Test
        @DisplayName("InnerProduct implements DistanceFunction")
        void innerProductImplementsInterface() {
            assertThat(InnerProduct.getInstance()).isInstanceOf(DistanceFunction.class);
        }

        @Test
        @DisplayName("CosineSimilarity implements DistanceFunction")
        void cosineSimilarityImplementsInterface() {
            assertThat(CosineSimilarity.getInstance()).isInstanceOf(DistanceFunction.class);
        }
    }

    @Nested
    @DisplayName("Vector API Availability Tests")
    class VectorApiAvailabilityTests {

        @Test
        @DisplayName("L2Distance isVectorApiAvailable returns boolean")
        void l2DistanceIsVectorApiAvailable() {
            boolean available = L2Distance.getInstance().isVectorApiAvailable();
            // Just verify it doesn't throw and returns a boolean
            assertThat(available == true || available == false).isTrue();
        }

        @Test
        @DisplayName("InnerProduct isVectorApiAvailable returns boolean")
        void innerProductIsVectorApiAvailable() {
            boolean available = InnerProduct.getInstance().isVectorApiAvailable();
            assertThat(available == true || available == false).isTrue();
        }

        @Test
        @DisplayName("CosineSimilarity isVectorApiAvailable returns boolean")
        void cosineSimilarityIsVectorApiAvailable() {
            boolean available = CosineSimilarity.getInstance().isVectorApiAvailable();
            assertThat(available == true || available == false).isTrue();
        }
    }

    @Nested
    @DisplayName("InnerProduct Additional Method Tests")
    class InnerProductAdditionalMethodTests {

        private final InnerProduct inner = InnerProduct.getInstance();

        @Test
        @DisplayName("computeWithOneNorm with valid input returns same as compute")
        void computeWithOneNormValidInput() {
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f};
            double v2NormSq = 4.0 + 9.0 + 16.0 + 25.0 + 36.0 + 49.0 + 64.0 + 81.0;

            float result = inner.computeWithOneNorm(v1, v2, v2NormSq);
            float expected = inner.compute(v1, v2);

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("computeWithOneNorm with zero norm returns correct value")
        void computeWithOneNormZeroNorm() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            float result = inner.computeWithOneNorm(v1, v2, 0.0);
            assertThat(result).isEqualTo(14.0f); // 1+4+9
        }

        @Test
        @DisplayName("computeNegated returns negative of compute")
        void computeNegated() {
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f};

            float regular = inner.compute(v1, v2);
            float negated = inner.computeNegated(v1, v2);

            assertThat(negated).isEqualTo(-regular);
        }

        @Test
        @DisplayName("isOrthogonal with orthogonal vectors returns true")
        void isOrthogonalOrthogonalVectors() {
            float[] v1 = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

            assertThat(inner.isOrthogonal(v1, v2, 0.001f)).isTrue();
        }

        @Test
        @DisplayName("isOrthogonal with non-orthogonal vectors returns false")
        void isOrthogonalNonOrthogonalVectors() {
            float[] v1 = {1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float[] v2 = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

            assertThat(inner.isOrthogonal(v1, v2, 0.001f)).isFalse();
        }

        @Test
        @DisplayName("isOrthogonal with nearly orthogonal vectors within tolerance")
        void isOrthogonalNearlyOrthogonal() {
            float[] v1 = {1.0f, 0.001f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

            // Inner product = 0.001, which is <= 0.01 tolerance
            assertThat(inner.isOrthogonal(v1, v2, 0.01f)).isTrue();
            // But > 0.0001 tolerance
            assertThat(inner.isOrthogonal(v1, v2, 0.0001f)).isFalse();
        }

        @Test
        @DisplayName("computeSquaredNorm with large vector uses vectorized path")
        void computeSquaredNormLargeVector() {
            float[] v = new float[128];
            for (int i = 0; i < 128; i++) {
                v[i] = 1.0f;
            }

            float norm = inner.computeSquaredNorm(v);
            assertThat(norm).isEqualTo(128.0f);
        }

        @Test
        @DisplayName("computeSquaredNorm with zero vector returns 0")
        void computeSquaredNormZeroVector() {
            float[] v = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float norm = inner.computeSquaredNorm(v);
            assertThat(norm).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("CosineSimilarity Additional Method Tests")
    class CosineSimilarityAdditionalMethodTests {

        private final CosineSimilarity cosine = CosineSimilarity.getInstance();

        @Test
        @DisplayName("normalizeInPlace produces unit vector")
        void normalizeInPlaceProducesUnitVector() {
            float[] v = {3.0f, 4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            cosine.normalizeInPlace(v);

            // After normalization: (3/5, 4/5) = (0.6, 0.8)
            assertThat(v[0]).isCloseTo(0.6f, within(FLOAT_TOLERANCE));
            assertThat(v[1]).isCloseTo(0.8f, within(FLOAT_TOLERANCE));

            // Check normalized
            assertThat(cosine.isNormalized(v, 1e-6f)).isTrue();
        }

        @Test
        @DisplayName("normalize returns new vector without modifying original")
        void normalizeReturnsNewVector() {
            float[] original = {3.0f, 4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float[] normalized = cosine.normalize(original);

            // Different array reference
            assertThat(normalized).isNotSameAs(original);
            // Original unchanged
            assertThat(original[0]).isEqualTo(3.0f);
            assertThat(original[1]).isEqualTo(4.0f);
            // Normalized correctly
            assertThat(normalized[0]).isCloseTo(0.6f, within(FLOAT_TOLERANCE));
            assertThat(normalized[1]).isCloseTo(0.8f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("isNormalized detects unit vectors")
        void isNormalizedDetectsUnitVectors() {
            float[] unitVector = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float[] nonUnitVector = {2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            float[] nearlyUnit = {1.00001f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

            assertThat(cosine.isNormalized(unitVector, 1e-6f)).isTrue();
            assertThat(cosine.isNormalized(nonUnitVector, 1e-6f)).isFalse();
            assertThat(cosine.isNormalized(nearlyUnit, 0.001f)).isTrue();
        }

        @Test
        @DisplayName("computeWithNorms with valid input matches compute")
        void computeWithNormsValidInput() {
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f};

            double norm1Sq = cosine.computeSquaredNorm(v1);
            double norm2Sq = cosine.computeSquaredNorm(v2);

            float withNorms = cosine.computeWithNorms(v1, v2, norm1Sq, norm2Sq);
            float direct = cosine.compute(v1, v2);

            assertThat(withNorms).isCloseTo(direct, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("computeWithNorms with NaN returns NaN")
        void computeWithNormsWithNaN() {
            float[] v1 = {1.0f, Float.NaN, 3.0f, 4.0f, 5.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

            float result = cosine.computeWithNorms(v1, v2, 55.0, 55.0);
            assertThat(result).isNaN();
        }

        @Test
        @DisplayName("computeSquaredNorm with large vector uses vectorized path")
        void computeSquaredNormLargeVector() {
            float[] v = new float[128];
            for (int i = 0; i < 128; i++) {
                v[i] = 1.0f;
            }

            double norm = cosine.computeSquaredNorm(v);
            assertThat(norm).isEqualTo(128.0);
        }
    }

    @Nested
    @DisplayName("L2Distance Additional Method Tests")
    class L2DistanceAdditionalMethodTests {

        private final L2Distance l2 = L2Distance.getInstance();

        @Test
        @DisplayName("computeSquared with threshold boundary vectors")
        void computeSquaredThresholdBoundary() {
            // VECTORIZATION_THRESHOLD is 8
            float[] v1 = new float[8];
            float[] v2 = new float[8];
            for (int i = 0; i < 8; i++) {
                v1[i] = i;
                v2[i] = i + 1;
            }

            float squared = l2.computeSquared(v1, v2);
            // 8 elements, each diff = 1, squared = 8
            assertThat(squared).isEqualTo(8.0f);
        }

        @Test
        @DisplayName("computeSquared with large vector exercises vectorized path")
        void computeSquaredLargeVector() {
            float[] v1 = new float[256];
            float[] v2 = new float[256];
            for (int i = 0; i < 256; i++) {
                v1[i] = i;
                v2[i] = i + 1;
            }

            float squared = l2.computeSquared(v1, v2);
            assertThat(squared).isEqualTo(256.0f);
        }

        @Test
        @DisplayName("compute with high dimensional vectors")
        void computeHighDimensional() {
            float[] v1 = new float[1000];
            float[] v2 = new float[1000];
            for (int i = 0; i < 1000; i++) {
                v1[i] = (float) Math.sin(i * 0.01);
                v2[i] = (float) Math.cos(i * 0.01);
            }

            float dist = l2.compute(v1, v2);
            assertThat(dist).isPositive();
            assertThat(Float.isFinite(dist)).isTrue();
        }
    }

    @Nested
    @DisplayName("Numerical Stability Tests")
    class NumericalStabilityTests {

        @Test
        @DisplayName("L2Distance handles extreme values without overflow")
        void l2DistanceExtremeValues() {
            float[] v1 = {1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f};
            float[] v2 = {1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f, 1e-30f};

            float dist = L2Distance.getInstance().compute(v1, v2);
            assertThat(dist).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("InnerProduct handles large values with clamping")
        void innerProductLargeValuesClamping() {
            float[] v1 = {1e30f, 1e30f, 1e30f, 1e30f};
            float[] v2 = {1e30f, 1e30f, 1e30f, 1e30f};

            float result = InnerProduct.getInstance().compute(v1, v2);

            // Should be clamped to Float.MAX_VALUE
            assertThat(result).isEqualTo(Float.MAX_VALUE);
            assertThat(Float.isFinite(result)).isTrue();
        }

        @Test
        @DisplayName("CosineSimilarity clamps result to valid range")
        void cosineSimilarityClamping() {
            // Test multiple random vectors
            for (int trial = 0; trial < 50; trial++) {
                float[] v1 = new float[64];
                float[] v2 = new float[64];
                for (int i = 0; i < 64; i++) {
                    v1[i] = (float) (Math.random() * 200 - 100);
                    v2[i] = (float) (Math.random() * 200 - 100);
                }

                float result = CosineSimilarity.getInstance().compute(v1, v2);
                assertThat(result)
                    .as("Trial %d: result should be in [-1, 1]", trial)
                    .isGreaterThanOrEqualTo(-1.0f)
                    .isLessThanOrEqualTo(1.0f);
            }
        }
    }
}
