package io.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link L2Distance}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Domain correctness: output &gt;= 0.0, identical vectors = 0.0</li>
 *   <li>Symmetry: distance(a, b) == distance(b, a)</li>
 *   <li>Triangle inequality (metric property)</li>
 *   <li>Edge cases: empty vectors, single element, NaN handling</li>
 *   <li>Both scalar and Vector API code paths</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
@DisplayName("L2Distance Tests")
class L2DistanceTest {

    private final L2Distance distance = L2Distance.getInstance();

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectness {

        @Test
        @DisplayName("Identical vectors produce exactly 0.0")
        void identicalVectors_produceZero() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            assertThat(distance.compute(v, v)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Output is always non-negative")
        void output_isNonNegative() {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {4.0f, 5.0f, 6.0f};
            assertThat(distance.compute(a, b)).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("Output is always non-negative for opposite vectors")
        void oppositeVectors_produceNonNegative() {
            float[] a = {1.0f, -2.0f, 3.0f, -4.0f};
            float[] b = {-1.0f, 2.0f, -3.0f, 4.0f};
            assertThat(distance.compute(a, b)).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("Zero vectors produce 0.0 distance")
        void zeroVectors_produceZero() {
            float[] a = {0.0f, 0.0f, 0.0f, 0.0f};
            float[] b = {0.0f, 0.0f, 0.0f, 0.0f};
            assertThat(distance.compute(a, b)).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("Known Values")
    class KnownValues {

        @Test
        @DisplayName("Simple 2D vectors produce correct distance")
        void simple2D_correctDistance() {
            // Points (0,0) and (3,4) -> distance = 5 (3-4-5 triangle)
            float[] a = {0.0f, 0.0f};
            float[] b = {3.0f, 4.0f};
            assertThat(distance.compute(a, b)).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("3D unit vectors produce correct distance")
        void unitVectors3D_correctDistance() {
            // (1,0,0) and (0,1,0) -> distance = sqrt(2)
            float[] a = {1.0f, 0.0f, 0.0f};
            float[] b = {0.0f, 1.0f, 0.0f};
            assertThat(distance.compute(a, b)).isEqualTo((float) Math.sqrt(2));
        }

        @Test
        @DisplayName("Single element vectors produce absolute difference")
        void singleElement_absoluteDifference() {
            float[] a = {5.0f};
            float[] b = {2.0f};
            assertThat(distance.compute(a, b)).isEqualTo(3.0f);
        }

        @Test
        @DisplayName("Negative values handled correctly")
        void negativeValues_correctDistance() {
            // (-1,-1) and (1,1) -> distance = sqrt(8) = 2*sqrt(2)
            float[] a = {-1.0f, -1.0f};
            float[] b = {1.0f, 1.0f};
            float expected = (float) Math.sqrt(8.0);
            assertThat(distance.compute(a, b)).isCloseTo(expected, within(1e-6f));
        }
    }

    @Nested
    @DisplayName("Mathematical Properties")
    class MathematicalProperties {

        @Test
        @DisplayName("Distance is symmetric")
        void distance_isSymmetric() {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float[] b = {5.0f, 4.0f, 3.0f, 2.0f, 1.0f};

            float distAB = distance.compute(a, b);
            float distBA = distance.compute(b, a);

            assertThat(distAB).isEqualTo(distBA);
        }

        @Test
        @DisplayName("Triangle inequality holds")
        void triangleInequality_holds() {
            float[] a = {0.0f, 0.0f};
            float[] b = {3.0f, 0.0f};
            float[] c = {3.0f, 4.0f};

            float distAB = distance.compute(a, b);
            float distBC = distance.compute(b, c);
            float distAC = distance.compute(a, c);

            // Triangle inequality: d(a,c) <= d(a,b) + d(b,c)
            assertThat(distAC).isLessThanOrEqualTo(distAB + distBC);
        }

        @Test
        @DisplayName("Identity of indiscernibles: d(x,y) = 0 iff x = y")
        void identityOfIndiscernibles() {
            // If distance is 0, vectors should be identical (for non-NaN values)
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {1.0f, 2.0f, 3.0f};

            assertThat(distance.compute(a, b)).isEqualTo(0.0f);

            // Different vectors should have positive distance
            float[] c = {1.0f, 2.0f, 3.1f};
            assertThat(distance.compute(a, c)).isPositive();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty vectors throw IllegalArgumentException")
        void emptyVectors_throwException() {
            float[] a = {};
            float[] b = {};
            assertThatThrownBy(() -> distance.compute(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
        }

        @Test
        @DisplayName("NaN in first vector throws IllegalArgumentException")
        void nanInFirstVector_throwsException() {
            float[] a = {1.0f, Float.NaN, 3.0f};
            float[] b = {1.0f, 2.0f, 3.0f};
            assertThatThrownBy(() -> distance.compute(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
        }

        @Test
        @DisplayName("NaN in second vector throws IllegalArgumentException")
        void nanInSecondVector_throwsException() {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {1.0f, Float.NaN, 3.0f};
            assertThatThrownBy(() -> distance.compute(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite value");
        }

        @Test
        @DisplayName("Null first vector throws NullPointerException")
        void nullFirstVector_throwsNPE() {
            float[] b = {1.0f, 2.0f};
            assertThatThrownBy(() -> distance.compute(null, b))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null second vector throws NullPointerException")
        void nullSecondVector_throwsNPE() {
            float[] a = {1.0f, 2.0f};
            assertThatThrownBy(() -> distance.compute(a, null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Mismatched dimensions throws IllegalArgumentException")
        void mismatchedDimensions_throwsIAE() {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {1.0f, 2.0f};
            assertThatThrownBy(() -> distance.compute(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must match");
        }
    }

    @Nested
    @DisplayName("Vector Size Variants")
    class VectorSizeVariants {

        @Test
        @DisplayName("Small vector (dim=4) uses scalar path")
        void smallVector_scalarPath() {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] b = {2.0f, 3.0f, 4.0f, 5.0f};

            float dist = distance.compute(a, b);
            // sqrt(1 + 1 + 1 + 1) = sqrt(4) = 2
            assertThat(dist).isEqualTo(2.0f);
        }

        @Test
        @DisplayName("Medium vector (dim=16) uses Vector API")
        void mediumVector_vectorApiPath() {
            float[] a = new float[16];
            float[] b = new float[16];
            for (int i = 0; i < 16; i++) {
                a[i] = i;
                b[i] = i + 1;
            }

            float dist = distance.compute(a, b);
            // sqrt(16 * 1) = 4
            assertThat(dist).isEqualTo(4.0f);
        }

        @Test
        @DisplayName("Large vector (dim=128) uses Vector API")
        void largeVector_vectorApiPath() {
            float[] a = new float[128];
            float[] b = new float[128];
            for (int i = 0; i < 128; i++) {
                a[i] = i;
                b[i] = i + 0.5f;
            }

            float dist = distance.compute(a, b);
            // sqrt(128 * 0.25) = sqrt(32) = 4*sqrt(2)
            float expected = (float) Math.sqrt(32.0);
            assertThat(dist).isCloseTo(expected, within(1e-4f));
        }

        @Test
        @DisplayName("Non-power-of-two vector size")
        void nonPowerOfTwoSize() {
            float[] a = new float[17];
            float[] b = new float[17];
            for (int i = 0; i < 17; i++) {
                a[i] = i;
                b[i] = i + 1;
            }

            float dist = distance.compute(a, b);
            // sqrt(17 * 1) = sqrt(17)
            float expected = (float) Math.sqrt(17.0);
            assertThat(dist).isCloseTo(expected, within(1e-5f));
        }
    }

    @Nested
    @DisplayName("Squared Distance")
    class SquaredDistance {

        @Test
        @DisplayName("Squared distance equals distance squared")
        void squaredDistance_matchesDistanceSquared() {
            float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
            float[] b = {2.0f, 3.0f, 4.0f, 5.0f, 6.0f};

            float dist = distance.compute(a, b);
            float squaredDist = distance.computeSquared(a, b);

            assertThat(squaredDist).isEqualTo(dist * dist);
        }

        @Test
        @DisplayName("Squared distance for identical vectors is 0")
        void squaredDistance_identical_isZero() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
            assertThat(distance.computeSquared(v, v)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Squared distance is non-negative")
        void squaredDistance_nonNegative() {
            float[] a = {-1.0f, -2.0f, -3.0f};
            float[] b = {1.0f, 2.0f, 3.0f};
            assertThat(distance.computeSquared(a, b)).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("Squared distance with known value")
        void squaredDistance_knownValue() {
            // Points (0,0) and (3,4) -> squared distance = 9 + 16 = 25
            float[] a = {0.0f, 0.0f};
            float[] b = {3.0f, 4.0f};
            assertThat(distance.computeSquared(a, b)).isEqualTo(25.0f);
        }

        @Test
        @DisplayName("Squared distance null first vector throws NPE")
        void squaredDistance_nullFirstVector() {
            float[] b = {1.0f, 2.0f, 3.0f};
            assertThatThrownBy(() -> distance.computeSquared(null, b))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("First vector");
        }

        @Test
        @DisplayName("Squared distance null second vector throws NPE")
        void squaredDistance_nullSecondVector() {
            float[] a = {1.0f, 2.0f, 3.0f};
            assertThatThrownBy(() -> distance.computeSquared(a, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Second vector");
        }

        @Test
        @DisplayName("Squared distance mismatched dimensions throws IAE")
        void squaredDistance_mismatchedDimensions() {
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {1.0f, 2.0f};
            assertThatThrownBy(() -> distance.computeSquared(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions must match");
        }

        @Test
        @DisplayName("Squared distance empty vectors returns 0")
        void squaredDistance_emptyVectors() {
            float[] a = {};
            float[] b = {};
            assertThat(distance.computeSquared(a, b)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Squared distance with large vector uses vectorized path")
        void squaredDistance_largeVectorVectorized() {
            float[] a = new float[128];
            float[] b = new float[128];
            for (int i = 0; i < 128; i++) {
                a[i] = i;
                b[i] = i + 1;
            }

            float squared = distance.computeSquared(a, b);

            // Each element diff is 1, so squared = 128 * 1 = 128
            assertThat(squared).isEqualTo(128.0f);
        }
    }

    @Nested
    @DisplayName("Singleton Pattern")
    class SingletonPattern {

        @Test
        @DisplayName("getInstance returns same instance")
        void getInstance_returnsSameInstance() {
            L2Distance instance1 = L2Distance.getInstance();
            L2Distance instance2 = L2Distance.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }
    }

    @Nested
    @DisplayName("Name and ToString")
    class NameAndToString {

        @Test
        @DisplayName("name() returns 'l2'")
        void name_returnsL2() {
            assertThat(distance.name()).isEqualTo("l2");
        }

        @Test
        @DisplayName("toString contains 'L2Distance'")
        void toString_containsL2Distance() {
            // toString includes the acceleration mode (vectorized or scalar)
            assertThat(distance.toString()).startsWith("L2Distance");
        }
    }

    @Nested
    @DisplayName("Numerical Stability")
    class NumericalStability {

        @Test
        @DisplayName("Large values do not overflow")
        void largeValues_noOverflow() {
            float[] a = {1e10f, 1e10f, 1e10f, 1e10f};
            float[] b = {0.0f, 0.0f, 0.0f, 0.0f};

            float dist = distance.compute(a, b);
            assertThat(dist).isPositive();
            assertThat(Float.isFinite(dist)).isTrue();
        }

        @Test
        @DisplayName("Very small differences are computed accurately")
        void smallDifferences_accurate() {
            float[] a = {1.000001f, 2.000001f, 3.000001f};
            float[] b = {1.0f, 2.0f, 3.0f};

            float dist = distance.compute(a, b);
            // sqrt(3 * 1e-12) = sqrt(3e-12) ≈ 1.73e-6
            assertThat(dist).isPositive();
            assertThat(dist).isLessThan(1e-4f);
        }

        @Test
        @DisplayName("High-dimensional vector maintains precision")
        void highDimensional_maintainsPrecision() {
            // 1000-dimensional vector with small differences
            float[] a = new float[1000];
            float[] b = new float[1000];
            for (int i = 0; i < 1000; i++) {
                a[i] = i * 0.001f;
                b[i] = a[i] + 0.0001f;
            }

            float dist = distance.compute(a, b);
            // Expected: sqrt(1000 * 1e-8) = sqrt(1e-5) ≈ 0.00316
            float expected = (float) Math.sqrt(1000 * 1e-8);
            assertThat(dist).isCloseTo(expected, within(1e-5f));
            assertThat(dist).isPositive();
        }
    }

    @Nested
    @DisplayName("Interface Contract")
    class InterfaceContract {

        @Test
        @DisplayName("Implements DistanceFunction interface")
        void implementsInterface() {
            assertThat(distance).isInstanceOf(DistanceFunction.class);
        }

        @Test
        @DisplayName("compute method works through interface reference")
        void computeThroughInterface() {
            DistanceFunction df = L2Distance.getInstance();
            float[] a = {1.0f, 2.0f, 3.0f};
            float[] b = {4.0f, 5.0f, 6.0f};

            float result = df.compute(a, b);
            assertThat(result).isPositive();
        }
    }
}
