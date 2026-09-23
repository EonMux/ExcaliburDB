package com.eonmux.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The SIMD kernels every distance function's vectorized path runs on.
 *
 * <p>The test JVM resolves {@code jdk.incubator.vector} (see this module's build script), as a
 * node does, so {@link VectorApiSupport} must hand out the compiled kernels. The kernels it
 * handed out before dispatched every lane-wide step through {@code Method.invoke}, boxing each
 * partial sum: C2 cannot intrinsify a vector operation it reaches reflectively, so the
 * "vectorized" path ran several times slower than the plain scalar loop it was meant to beat,
 * and every distance in the engine paid for it.</p>
 */
@DisplayName("SIMD vector operations")
class SimdVectorOperationsTest {

    private static final VectorApiSupport SUPPORT = VectorApiSupport.getInstance();

    @Test
    @DisplayName("finite components whose float lane sums overflow are summed in double instead")
    void kernelsDoNotOverflowOnFiniteInputs() {
        // Each square or product fits a float (1e38 < 3.4e38); sixty-four of them do not.
        int length = 64;
        float[] large = new float[length];
        float[] zero = new float[length];
        java.util.Arrays.fill(large, 1e19f);
        double expected = length * (double) 1e19f * (double) 1e19f;
        VectorApiSupport.VectorOperations ops = SUPPORT.getOperations();

        assertThat(ops.squaredL2(large, zero, 0, length)).isCloseTo(expected, within(expected * 1e-6));
        assertThat(ops.dotProduct(large, large, 0, length)).isCloseTo(expected, within(expected * 1e-6));
        assertThat(ops.squaredNorm(large, 0, length)).isCloseTo(expected, within(expected * 1e-6));
    }

    @Test
    @DisplayName("distances over finite components too large for float sums match the scalar path")
    void distancesOverLargeFiniteComponentsAreFinite() {
        int length = 64;
        float[] large = new float[length];
        float[] zero = new float[length];
        java.util.Arrays.fill(large, 1e19f);

        assertThat(L2Distance.getInstance().compute(large, zero))
            .isCloseTo((float) Math.sqrt(length * (double) 1e19f * (double) 1e19f), within(1e14f));
        assertThat(CosineSimilarity.getInstance().compute(large, large)).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    @DisplayName("with the Vector API resolved, distances run on the compiled SIMD kernels")
    void theResolvedVectorApiIsUsedDirectly() {
        assertThat(SUPPORT.isAvailable()).isTrue();
        assertThat(SUPPORT.getOperations()).isInstanceOf(SimdVectorOperations.class);
        assertThat(SUPPORT.getOperations().speciesLength())
            .isEqualTo(SUPPORT.getPreferredSpeciesLength())
            .isPositive();
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 7, 8, 15, 16, 31, 32, 33, 63, 64, 65, 127, 128, 257, 768, 1536, 4099})
    @DisplayName("every kernel agrees with a double-precision reference over any range, tail included")
    void kernelsMatchADoublePrecisionReference(int length) {
        Random random = new Random(length);
        float[] a = randomVector(random, length);
        float[] b = randomVector(random, length);
        VectorApiSupport.VectorOperations ops = SUPPORT.getOperations();

        for (int from : new int[] {0, Math.min(3, length)}) {
            double dot = 0.0;
            double l2 = 0.0;
            double norm = 0.0;
            double magnitude = 0.0;
            for (int i = from; i < length; i++) {
                dot += (double) a[i] * b[i];
                double d = (double) a[i] - b[i];
                l2 += d * d;
                norm += (double) a[i] * a[i];
                magnitude += Math.abs((double) a[i] * b[i]);
            }
            // Float lanes accumulate about length / lanes terms each, so the error bound grows
            // with the sum of magnitudes, not with the (possibly cancelling) result.
            double tolerance = 1e-6 * Math.max(1.0, magnitude + norm + l2);

            assertThat(ops.dotProduct(a, b, from, length)).isCloseTo(dot, within(tolerance));
            assertThat(ops.squaredL2(a, b, from, length)).isCloseTo(l2, within(tolerance));
            assertThat(ops.squaredNorm(a, from, length)).isCloseTo(norm, within(tolerance));
        }
    }

    @Test
    @DisplayName("an empty range sums to zero")
    void emptyRangeIsZero() {
        float[] v = {1.0f, 2.0f, 3.0f};
        VectorApiSupport.VectorOperations ops = SUPPORT.getOperations();

        assertThat(ops.dotProduct(v, v, 2, 2)).isZero();
        assertThat(ops.squaredL2(v, v, 0, 0)).isZero();
        assertThat(ops.squaredNorm(v, 3, 3)).isZero();
    }

    @Test
    @DisplayName("NaN in any lane propagates to the sum")
    void nanPropagates() {
        float[] a = new float[64];
        float[] b = new float[64];
        java.util.Arrays.fill(a, 1.0f);
        java.util.Arrays.fill(b, 1.0f);
        a[37] = Float.NaN;
        VectorApiSupport.VectorOperations ops = SUPPORT.getOperations();

        assertThat(ops.dotProduct(a, b, 0, 64)).isNaN();
        assertThat(ops.squaredL2(a, b, 0, 64)).isNaN();
        assertThat(ops.squaredNorm(a, 0, 64)).isNaN();
    }

    private static float[] randomVector(Random random, int length) {
        float[] v = new float[length];
        for (int i = 0; i < length; i++) {
            v[i] = random.nextFloat() * 2.0f - 1.0f;
        }
        return v;
    }
}
