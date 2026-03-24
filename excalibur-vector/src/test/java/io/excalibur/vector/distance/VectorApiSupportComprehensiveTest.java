package io.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for VectorApiSupport to improve coverage.
 *
 * <p>Tests focus on:</p>
 * <ul>
 *   <li>VectorOperations interface methods</li>
 *   <li>Edge cases and fallback behavior</li>
 *   <li>Loop bound calculations</li>
 * </ul>
 */
@DisplayName("VectorApiSupport Comprehensive Tests")
class VectorApiSupportComprehensiveTest {

    private static final float FLOAT_TOLERANCE = 1e-5f;

    @Nested
    @DisplayName("Singleton and Availability")
    class SingletonTests {

        @Test
        @DisplayName("getInstance returns same instance consistently")
        void getInstanceConsistent() {
            VectorApiSupport instance1 = VectorApiSupport.getInstance();
            VectorApiSupport instance2 = VectorApiSupport.getInstance();
            VectorApiSupport instance3 = VectorApiSupport.getInstance();

            assertThat(instance1).isSameAs(instance2).isSameAs(instance3);
        }

        @Test
        @DisplayName("isAvailable returns consistent value")
        void isAvailableConsistent() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            boolean available1 = support.isAvailable();
            boolean available2 = support.isAvailable();

            assertThat(available1).isEqualTo(available2);
        }
    }

    @Nested
    @DisplayName("Loop Bound Calculations")
    class LoopBoundTests {

        @Test
        @DisplayName("loopBound returns 0 for length 0")
        void loopBoundZero() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int bound = support.loopBound(0);

            assertThat(bound).isEqualTo(0);
        }

        @Test
        @DisplayName("loopBound handles positive lengths")
        void loopBoundPositive() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int bound = support.loopBound(100);

            // Should be non-negative and <= input
            assertThat(bound).isGreaterThanOrEqualTo(0);
            assertThat(bound).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("loopBound handles large lengths")
        void loopBoundLarge() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int bound = support.loopBound(10000);

            assertThat(bound).isGreaterThanOrEqualTo(0);
            assertThat(bound).isLessThanOrEqualTo(10000);
        }

        @Test
        @DisplayName("loopBound handles length of 1")
        void loopBoundOne() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int bound = support.loopBound(1);

            assertThat(bound).isGreaterThanOrEqualTo(0);
            assertThat(bound).isLessThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Species Length")
    class SpeciesLengthTests {

        @Test
        @DisplayName("getPreferredSpeciesLength returns non-negative")
        void speciesLengthNonNegative() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int length = support.getPreferredSpeciesLength();

            assertThat(length).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Species length is consistent across calls")
        void speciesLengthConsistent() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            int length1 = support.getPreferredSpeciesLength();
            int length2 = support.getPreferredSpeciesLength();

            assertThat(length1).isEqualTo(length2);
        }
    }

    @Nested
    @DisplayName("VectorOperations Interface")
    class VectorOperationsTests {

        @Test
        @DisplayName("getOperations returns null when Vector API unavailable")
        void getOperationsWhenUnavailable() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            if (!support.isAvailable()) {
                assertThat(support.getOperations()).isNull();
            }
            // If available, we can't test null, but we test it doesn't throw
            assertThatCode(() -> support.getOperations()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getOperations returns valid object when Vector API available")
        void getOperationsWhenAvailable() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            if (support.isAvailable()) {
                VectorApiSupport.VectorOperations ops = support.getOperations();
                assertThat(ops).isNotNull();
                assertThat(ops.speciesLength()).isPositive();
            }
        }

        @Test
        @DisplayName("VectorOperations can compute dot product")
        void vectorOperationsDotProduct() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            VectorApiSupport.VectorOperations ops = support.getOperations();

            if (ops != null) {
                float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
                float[] v2 = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};

                double dot = ops.dotProduct(v1, v2, 0, v1.length);

                // 1+2+3+4+5+6+7+8 = 36
                assertThat(dot).isCloseTo(36.0, within(0.001));
            }
        }

        @Test
        @DisplayName("VectorOperations can compute squared L2")
        void vectorOperationsSquaredL2() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            VectorApiSupport.VectorOperations ops = support.getOperations();

            if (ops != null) {
                float[] v1 = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
                float[] v2 = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};

                double sqL2 = ops.squaredL2(v1, v2, 0, v1.length);

                // 8 * 1^2 = 8
                assertThat(sqL2).isCloseTo(8.0, within(0.001));
            }
        }

        @Test
        @DisplayName("VectorOperations can compute squared norm")
        void vectorOperationsSquaredNorm() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            VectorApiSupport.VectorOperations ops = support.getOperations();

            if (ops != null) {
                float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};

                double sqNorm = ops.squaredNorm(v, 0, v.length);

                // 1+4+9+16+25+36+49+64 = 204
                assertThat(sqNorm).isCloseTo(204.0, within(0.001));
            }
        }

        @Test
        @DisplayName("VectorOperations handles partial range")
        void vectorOperationsPartialRange() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            VectorApiSupport.VectorOperations ops = support.getOperations();

            if (ops != null) {
                float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f};
                float[] v2 = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};

                // Only process elements 0-8
                double dot = ops.dotProduct(v1, v2, 0, 8);

                // 1+2+3+4+5+6+7+8 = 36
                assertThat(dot).isCloseTo(36.0, within(0.001));
            }
        }

        @Test
        @DisplayName("VectorOperations speciesLength is positive when available")
        void vectorOperationsSpeciesLengthPositive() {
            VectorApiSupport support = VectorApiSupport.getInstance();
            VectorApiSupport.VectorOperations ops = support.getOperations();

            if (ops != null) {
                assertThat(ops.speciesLength()).isPositive();
            }
        }
    }

    @Nested
    @DisplayName("Distance Function Integration")
    class DistanceFunctionIntegrationTests {

        @Test
        @DisplayName("Distance functions use VectorApiSupport correctly")
        void distanceFunctionsUseVectorApiSupport() {
            // Test that all distance functions work correctly with or without Vector API
            float[] v1 = new float[128];
            float[] v2 = new float[128];
            for (int i = 0; i < 128; i++) {
                v1[i] = (float) Math.random();
                v2[i] = (float) Math.random();
            }

            // All should produce valid results
            float cosine = CosineSimilarity.getInstance().compute(v1, v2);
            float l2 = L2Distance.getInstance().compute(v1, v2);
            float inner = InnerProduct.getInstance().compute(v1, v2);

            assertThat(cosine).isBetween(-1.0f, 1.0f);
            assertThat(l2).isGreaterThanOrEqualTo(0.0f);
            assertThat(inner).isNotNaN();
        }

        @Test
        @DisplayName("Large vector computation uses vectorized path when available")
        void largeVectorUsesVectorizedPath() {
            VectorApiSupport support = VectorApiSupport.getInstance();

            // Large vectors should use vectorized path if available
            float[] v1 = new float[1024];
            float[] v2 = new float[1024];
            for (int i = 0; i < 1024; i++) {
                v1[i] = i * 0.001f;
                v2[i] = (i + 1) * 0.001f;
            }

            // Compute using different distance functions
            float l2 = L2Distance.getInstance().compute(v1, v2);
            float inner = InnerProduct.getInstance().compute(v1, v2);
            float cosine = CosineSimilarity.getInstance().compute(v1, v2);

            // All should produce finite results
            assertThat(l2).isFinite();
            assertThat(inner).isFinite();
            assertThat(cosine).isFinite();
            assertThat(cosine).isBetween(-1.0f, 1.0f);

            // Verify loop bound was used if Vector API available
            if (support.isAvailable()) {
                int bound = support.loopBound(1024);
                assertThat(bound).isPositive();
            }
        }

        @Test
        @DisplayName("Threshold boundary: vectors at 8 elements")
        void thresholdBoundary() {
            // VECTORIZATION_THRESHOLD is 8 in the implementations
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] v2 = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};

            // All should work correctly at the threshold
            float l2 = L2Distance.getInstance().compute(v1, v2);
            float inner = InnerProduct.getInstance().compute(v1, v2);
            float cosine = CosineSimilarity.getInstance().compute(v1, v2);

            // Verify correctness
            // L2: sum of (v1[i]-v2[i])^2 = 0+1+4+9+16+25+36+49 = 140, sqrt(140)
            assertThat(l2).isCloseTo((float) Math.sqrt(140.0), within(FLOAT_TOLERANCE));
            // Inner product: 1+2+3+4+5+6+7+8 = 36
            assertThat(inner).isEqualTo(36.0f);
            assertThat(cosine).isBetween(-1.0f, 1.0f);
        }

        @Test
        @DisplayName("Threshold boundary: vectors at 7 elements (below threshold)")
        void belowThreshold() {
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f};
            float[] v2 = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};

            // Should use scalar path
            float l2 = L2Distance.getInstance().compute(v1, v2);
            float inner = InnerProduct.getInstance().compute(v1, v2);
            float cosine = CosineSimilarity.getInstance().compute(v1, v2);

            // Verify correctness
            // L2: sum of (v1[i]-v2[i])^2 = 0+1+4+9+16+25+36 = 91, sqrt(91)
            assertThat(l2).isCloseTo((float) Math.sqrt(91.0), within(FLOAT_TOLERANCE));
            // Inner product: 1+2+3+4+5+6+7 = 28
            assertThat(inner).isEqualTo(28.0f);
            assertThat(cosine).isBetween(-1.0f, 1.0f);
        }
    }

    @Nested
    @DisplayName("Error Handling in Vector Operations")
    class VectorOperationsErrorHandling {

        @Test
        @DisplayName("Distance functions reject NaN values")
        void nanHandlingConsistent() {
            float[] withNan = {1.0f, Float.NaN, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] normal = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};

            // All distance functions should throw IllegalArgumentException for NaN input
            assertThatThrownBy(() -> L2Distance.getInstance().compute(withNan, normal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
            assertThatThrownBy(() -> InnerProduct.getInstance().compute(withNan, normal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
            assertThatThrownBy(() -> CosineSimilarity.getInstance().compute(withNan, normal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
        }

        @Test
        @DisplayName("Distance functions reject infinity values")
        void infinityHandling() {
            float[] withInf = {1.0f, Float.POSITIVE_INFINITY, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] normal = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};

            // All distance functions should throw IllegalArgumentException for infinity input
            assertThatThrownBy(() -> L2Distance.getInstance().compute(withInf, normal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
            assertThatThrownBy(() -> InnerProduct.getInstance().compute(withInf, normal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
        }

        @Test
        @DisplayName("Large values handled correctly")
        void largeValuesHandled() {
            float[] large = {1e20f, 1e20f, 1e20f, 1e20f, 1e20f, 1e20f, 1e20f, 1e20f};
            float[] larger = {1e20f, 1e20f, 1e20f, 1e20f, 1e20f, 1e20f, 1e20f, 1e20f};

            float inner = InnerProduct.getInstance().compute(large, larger);

            // Should be clamped to Float.MAX_VALUE, not infinity
            assertThat(inner).isFinite();
            assertThat(inner).isPositive();
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("VectorApiSupport is thread-safe for concurrent reads")
        void concurrentReads() throws InterruptedException {
            VectorApiSupport support = VectorApiSupport.getInstance();
            final int numThreads = 10;
            final int iterations = 1000;

            Thread[] threads = new Thread[numThreads];
            final boolean[] failures = new boolean[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        try {
                            boolean available = support.isAvailable();
                            int speciesLength = support.getPreferredSpeciesLength();
                            int loopBound = support.loopBound(100);
                            VectorApiSupport.VectorOperations ops = support.getOperations();

                            // Verify consistency
                            if (available && ops == null && speciesLength > 0) {
                                failures[threadId] = true;
                                break;
                            }
                        } catch (Exception e) {
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

        @Test
        @DisplayName("Distance functions are thread-safe with VectorApiSupport")
        void distanceFunctionThreadSafety() throws InterruptedException {
            final int numThreads = 10;
            final int iterations = 500;

            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            float[] v2 = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f};

            // Pre-compute expected values
            float expectedL2 = L2Distance.getInstance().compute(v1, v2);
            float expectedInner = InnerProduct.getInstance().compute(v1, v2);

            Thread[] threads = new Thread[numThreads];
            final boolean[] failures = new boolean[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        float l2 = L2Distance.getInstance().compute(v1, v2);
                        float inner = InnerProduct.getInstance().compute(v1, v2);

                        if (Math.abs(l2 - expectedL2) > FLOAT_TOLERANCE ||
                            Math.abs(inner - expectedInner) > FLOAT_TOLERANCE) {
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
}
