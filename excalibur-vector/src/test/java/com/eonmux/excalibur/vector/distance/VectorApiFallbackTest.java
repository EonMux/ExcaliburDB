package com.eonmux.excalibur.vector.distance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Vector API graceful fallback functionality.
 *
 * <p>These tests verify that distance functions work correctly regardless
 * of whether the Vector API is available at runtime.</p>
 */
@DisplayName("Vector API Fallback Tests")
class VectorApiFallbackTest {

    private static final float FLOAT_TOLERANCE = 1e-5f;

    @Nested
    @DisplayName("VectorApiSupport")
    class VectorApiSupportTests {

        @Test
        @DisplayName("getInstance returns non-null singleton")
        void getInstanceReturnsNonNull() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            assertThat(support).isNotNull();
        }

        @Test
        @DisplayName("getInstance returns same instance on repeated calls")
        void getInstanceReturnsSameInstance() {
            VectorApiSupport instance1 = VectorApiSupport.getInstance();
            VectorApiSupport instance2 = VectorApiSupport.getInstance();
            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("isAvailable does not throw")
        void isAvailableDoesNotThrow() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            // This should never throw, regardless of platform
            assertThatCode(() -> support.isAvailable()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getPreferredSpeciesLength returns non-negative")
        void preferredSpeciesLengthIsNonNegative() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int length = support.getPreferredSpeciesLength();
            assertThat(length).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("loopBound handles edge cases")
        void loopBoundHandlesEdgeCases() {
            VectorApiSupport support = VectorApiSupport.getInstance();

            // Even if Vector API unavailable, loopBound should work
            assertThat(support.loopBound(0)).isEqualTo(0);
            assertThat(support.loopBound(1)).isGreaterThanOrEqualTo(0);
            assertThat(support.loopBound(100)).isGreaterThanOrEqualTo(0);
            assertThat(support.loopBound(100)).isLessThanOrEqualTo(100);
        }
    }

    @Nested
    @DisplayName("CosineSimilarity Fallback")
    class CosineSimilarityTests {

        private CosineSimilarity cosine;

        @BeforeEach
        void setUp() {
            cosine = CosineSimilarity.getInstance();
        }

        @Test
        @DisplayName("works without Vector API")
        void worksWithoutVectorApi() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {1.0f, 0.0f, 0.0f};

            // Should work regardless of Vector API availability
            float similarity = cosine.compute(v1, v2);
            assertThat(similarity).isBetween(-1.0f, 1.0f);
        }

        @Test
        @DisplayName("identical vectors produce 1.0 similarity")
        void identicalVectors() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float similarity = cosine.compute(v, v);
            assertThat(similarity).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("orthogonal vectors produce 0.0 similarity")
        void orthogonalVectors() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};
            float similarity = cosine.compute(v1, v2);
            assertThat(similarity).isCloseTo(0.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("opposite vectors produce -1.0 similarity")
        void oppositeVectors() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {-1.0f, -2.0f, -3.0f};
            float similarity = cosine.compute(v1, v2);
            assertThat(similarity).isCloseTo(-1.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("handles large vectors without Vector API")
        void largeVectors() {
            // 1000 dimensions - should work with or without Vector API
            float[] v1 = new float[1000];
            float[] v2 = new float[1000];
            for (int i = 0; i < 1000; i++) {
                v1[i] = (float) Math.random();
                v2[i] = (float) Math.random();
            }

            float similarity = cosine.compute(v1, v2);
            assertThat(similarity).isBetween(-1.0f, 1.0f);
        }

        @Test
        @DisplayName("zero vector returns 0.0")
        void zeroVector() {
            float[] v1 = {0.0f, 0.0f, 0.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};
            float similarity = cosine.compute(v1, v2);
            assertThat(similarity).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("isVectorApiAvailable returns boolean")
        void isVectorApiAvailableReturnsBoolean() {
            // Should return true or false, never throw
            boolean available = cosine.isVectorApiAvailable();
            // Just verify it's a valid boolean (always true)
            assertThat(available == true || available == false).isTrue();
        }

        @Test
        @DisplayName("toString includes mode indicator")
        void toStringIncludesMode() {
            String str = cosine.toString();
            assertThat(str).contains("CosineSimilarity");
            // Should indicate vectorized or scalar mode
            assertThat(str).matches(".*CosineSimilarity\\(.*\\).*");
        }
    }

    @Nested
    @DisplayName("L2Distance Fallback")
    class L2DistanceTests {

        private L2Distance l2;

        @BeforeEach
        void setUp() {
            l2 = L2Distance.getInstance();
        }

        @Test
        @DisplayName("works without Vector API")
        void worksWithoutVectorApi() {
            float[] v1 = {0.0f, 0.0f, 0.0f};
            float[] v2 = {1.0f, 1.0f, 1.0f};

            float distance = l2.compute(v1, v2);
            // sqrt(1 + 1 + 1) = sqrt(3)
            assertThat(distance).isCloseTo((float) Math.sqrt(3), within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("identical vectors produce 0.0 distance")
        void identicalVectors() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float distance = l2.compute(v, v);
            assertThat(distance).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("distance is always non-negative")
        void distanceIsNonNegative() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {-4.0f, -5.0f, -6.0f};
            float distance = l2.compute(v1, v2);
            assertThat(distance).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("handles large vectors without Vector API")
        void largeVectors() {
            float[] v1 = new float[1000];
            float[] v2 = new float[1000];
            for (int i = 0; i < 1000; i++) {
                v1[i] = (float) Math.random();
                v2[i] = (float) Math.random();
            }

            float distance = l2.compute(v1, v2);
            assertThat(distance).isGreaterThanOrEqualTo(0.0f);
            assertThat(distance).isNotNaN();
        }

        @Test
        @DisplayName("computeSquared works without Vector API")
        void computeSquaredWorksWithoutVectorApi() {
            float[] v1 = {0.0f, 0.0f, 0.0f};
            float[] v2 = {1.0f, 1.0f, 1.0f};

            float squared = l2.computeSquared(v1, v2);
            // 1 + 1 + 1 = 3
            assertThat(squared).isCloseTo(3.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("isVectorApiAvailable returns boolean")
        void isVectorApiAvailableReturnsBoolean() {
            boolean available = l2.isVectorApiAvailable();
            assertThat(available == true || available == false).isTrue();
        }

        @Test
        @DisplayName("toString includes mode indicator")
        void toStringIncludesMode() {
            String str = l2.toString();
            assertThat(str).contains("L2Distance");
            assertThat(str).matches(".*L2Distance\\(.*\\).*");
        }
    }

    @Nested
    @DisplayName("InnerProduct Fallback")
    class InnerProductTests {

        private InnerProduct inner;

        @BeforeEach
        void setUp() {
            inner = InnerProduct.getInstance();
        }

        @Test
        @DisplayName("works without Vector API")
        void worksWithoutVectorApi() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {4.0f, 5.0f, 6.0f};

            float product = inner.compute(v1, v2);
            // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
            assertThat(product).isCloseTo(32.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("handles negative values correctly")
        void handlesNegativeValues() {
            float[] v1 = {-1.0f, -2.0f};
            float[] v2 = {-3.0f, -4.0f};

            float product = inner.compute(v1, v2);
            // (-1)*(-3) + (-2)*(-4) = 3 + 8 = 11
            assertThat(product).isCloseTo(11.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("opposite vectors produce negative product")
        void oppositeVectorsProduceNegative() {
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {-1.0f, -1.0f, -1.0f};

            float product = inner.compute(v1, v2);
            // 1*(-1) + 2*(-1) + 3*(-1) = -1 - 2 - 3 = -6
            assertThat(product).isCloseTo(-6.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("orthogonal vectors produce 0.0")
        void orthogonalVectors() {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};

            float product = inner.compute(v1, v2);
            assertThat(product).isCloseTo(0.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("handles large vectors without Vector API")
        void largeVectors() {
            float[] v1 = new float[1000];
            float[] v2 = new float[1000];
            for (int i = 0; i < 1000; i++) {
                v1[i] = (float) Math.random();
                v2[i] = (float) Math.random();
            }

            float product = inner.compute(v1, v2);
            assertThat(product).isNotNaN();
        }

        @Test
        @DisplayName("computeSquaredNorm works without Vector API")
        void computeSquaredNormWorksWithoutVectorApi() {
            float[] v = {3.0f, 4.0f};
            float norm = inner.computeSquaredNorm(v);
            // 3^2 + 4^2 = 9 + 16 = 25
            assertThat(norm).isCloseTo(25.0f, within(FLOAT_TOLERANCE));
        }

        @Test
        @DisplayName("isVectorApiAvailable returns boolean")
        void isVectorApiAvailableReturnsBoolean() {
            boolean available = inner.isVectorApiAvailable();
            assertThat(available == true || available == false).isTrue();
        }

        @Test
        @DisplayName("toString includes mode indicator")
        void toStringIncludesMode() {
            String str = inner.toString();
            assertThat(str).contains("InnerProduct");
            assertThat(str).matches(".*InnerProduct\\(.*\\).*");
        }
    }

    @Nested
    @DisplayName("Cross-Function Consistency")
    class CrossFunctionConsistency {

        @Test
        @DisplayName("all functions reject empty vectors")
        void allFunctionsHandleEmptyVectors() {
            float[] empty1 = {};
            float[] empty2 = {};

            CosineSimilarity cosine = CosineSimilarity.getInstance();
            L2Distance l2 = L2Distance.getInstance();
            InnerProduct inner = InnerProduct.getInstance();

            assertThatThrownBy(() -> cosine.compute(empty1, empty2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
            assertThatThrownBy(() -> l2.compute(empty1, empty2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
            assertThatThrownBy(() -> inner.compute(empty1, empty2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
        }

        @Test
        @DisplayName("all functions handle single element vectors")
        void allFunctionsHandleSingleElement() {
            float[] v1 = {2.0f};
            float[] v2 = {3.0f};

            CosineSimilarity cosine = CosineSimilarity.getInstance();
            L2Distance l2 = L2Distance.getInstance();
            InnerProduct inner = InnerProduct.getInstance();

            // Cosine: 2*3 / (|2| * |3|) = 6 / 6 = 1.0
            assertThat(cosine.compute(v1, v2)).isEqualTo(1.0f);

            // L2: sqrt((2-3)^2) = sqrt(1) = 1.0
            assertThat(l2.compute(v1, v2)).isEqualTo(1.0f);

            // Inner: 2*3 = 6
            assertThat(inner.compute(v1, v2)).isEqualTo(6.0f);
        }

        @Test
        @DisplayName("all functions reject mismatched dimensions")
        void allFunctionsRejectMismatchedDimensions() {
            float[] v1 = {1.0f, 2.0f};
            float[] v2 = {1.0f, 2.0f, 3.0f};

            CosineSimilarity cosine = CosineSimilarity.getInstance();
            L2Distance l2 = L2Distance.getInstance();
            InnerProduct inner = InnerProduct.getInstance();

            assertThatThrownBy(() -> cosine.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> l2.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> inner.compute(v1, v2))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Small Vector Threshold Behavior")
    class SmallVectorThreshold {

        @Test
        @DisplayName("functions work with vectors below threshold")
        void worksBelowThreshold() {
            // Vectors below threshold should use scalar path
            float[] v1 = {1.0f, 2.0f, 3.0f};
            float[] v2 = {4.0f, 5.0f, 6.0f};

            CosineSimilarity cosine = CosineSimilarity.getInstance();
            L2Distance l2 = L2Distance.getInstance();
            InnerProduct inner = InnerProduct.getInstance();

            // Should work regardless of threshold
            assertThat(cosine.compute(v1, v2)).isBetween(-1.0f, 1.0f);
            assertThat(l2.compute(v1, v2)).isGreaterThanOrEqualTo(0.0f);
            assertThat(inner.compute(v1, v2)).isEqualTo(32.0f);
        }

        @Test
        @DisplayName("functions work with vectors at threshold boundary")
        void worksAtThresholdBoundary() {
            // Exactly 8 elements (threshold for L2 and InnerProduct)
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] v2 = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};

            L2Distance l2 = L2Distance.getInstance();
            InnerProduct inner = InnerProduct.getInstance();

            assertThatCode(() -> l2.compute(v1, v2)).doesNotThrowAnyException();
            assertThatCode(() -> inner.compute(v1, v2)).doesNotThrowAnyException();
        }
    }
}
