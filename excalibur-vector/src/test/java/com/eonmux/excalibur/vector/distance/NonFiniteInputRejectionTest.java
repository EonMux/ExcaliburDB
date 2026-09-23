package com.eonmux.excalibur.vector.distance;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every distance rejects a non-finite input component, on both the scalar and SIMD paths.
 *
 * <p>The distances check finiteness only when an accumulated sum comes back non-finite, which
 * every non-finite component forces: its square, its difference with anything finite, and its
 * product with anything are all infinite or NaN. That lets a well-formed pair skip two scalar
 * passes over its inputs. These cases pin the contract the shortcut must keep, including the
 * ones a result-based check could plausibly miss: an infinity facing a zero, which multiplies
 * to NaN; opposite infinities that cancel to NaN; an inner product whose infinite sum would be
 * clamped to a finite float; and a cosine whose non-finite terms are clamped into range.</p>
 */
class NonFiniteInputRejectionTest {

    private static final int SCALAR_LENGTH = 16;
    private static final int SIMD_LENGTH = 1536;

    static Stream<Arguments> nonFinitePairs() {
        List<Arguments> cases = new ArrayList<>();
        List<DistanceFunction> functions = List.of(
            CosineSimilarity.getInstance(), L2Distance.getInstance(), InnerProduct.getInstance());
        for (DistanceFunction function : functions) {
            for (int length : new int[] {SCALAR_LENGTH, SIMD_LENGTH}) {
                for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                    for (int position : new int[] {0, length / 2 + 1, length - 1}) {
                        float[] a = filled(length, 0.5f);
                        float[] b = filled(length, 0.25f);
                        a[position] = bad;
                        cases.add(Arguments.of(function.name() + " len=" + length + " a[" + position + "]=" + bad,
                            function, a, b));
                        cases.add(Arguments.of(function.name() + " len=" + length + " b[" + position + "]=" + bad,
                            function, b.clone(), a.clone()));
                    }
                    float[] facingZero = filled(length, 0.5f);
                    float[] zeros = new float[length];
                    facingZero[length - 1] = bad;
                    cases.add(Arguments.of(function.name() + " len=" + length + " " + bad + " facing zero",
                        function, facingZero, zeros));
                }
                float[] cancelling = filled(length, 1.0f);
                cancelling[1] = Float.POSITIVE_INFINITY;
                cancelling[length - 2] = Float.NEGATIVE_INFINITY;
                cases.add(Arguments.of(function.name() + " len=" + length + " cancelling infinities",
                    function, cancelling, filled(length, 1.0f)));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonFinitePairs")
    void aNonFiniteComponentIsRejected(String description, DistanceFunction function, float[] a, float[] b) {
        assertThatThrownBy(() -> function.compute(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-finite");
    }

    private static float[] filled(int length, float value) {
        float[] v = new float[length];
        Arrays.fill(v, value);
        return v;
    }
}
