package excalibur.bench.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the scalar reference distance kernels.
 *
 * <p>These kernels are the reference the Vector API implementations are benchmarked
 * against, so their job is to be <em>right</em>, not fast. Every test here asserts a
 * mathematical property of the metric rather than the shape of the loop that computes
 * it.</p>
 */
class ScalarDistancesTest {

    private static final float TOLERANCE = 1e-6f;

    @Test
    @DisplayName("cosine similarity of a vector with itself is 1")
    void cosineOfIdenticalVectorsIsOne() {
        float[] v = {3.0f, -4.0f, 12.0f};

        assertThat(ScalarDistances.cosineSimilarity(v, v)).isCloseTo(1.0f, within());
    }

    @Test
    @DisplayName("cosine similarity of orthogonal vectors is 0")
    void cosineOfOrthogonalVectorsIsZero() {
        assertThat(ScalarDistances.cosineSimilarity(new float[]{1, 0}, new float[]{0, 1}))
            .isCloseTo(0.0f, within());
    }

    @Test
    @DisplayName("cosine similarity of opposed vectors is -1")
    void cosineOfOpposedVectorsIsMinusOne() {
        assertThat(ScalarDistances.cosineSimilarity(new float[]{2, 2}, new float[]{-2, -2}))
            .isCloseTo(-1.0f, within());
    }

    @Test
    @DisplayName("cosine similarity stays inside [-1, 1] at magnitudes that stress the quotient")
    void cosineStaysInUnitRange() {
        // Parallel vectors put the quotient exactly at the boundary, which is where a
        // less careful accumulation would round past it.
        float[] a = {1e18f, 1e18f, 1e18f};
        float[] b = {1e18f, 1e18f, 1e18f};

        assertThat(ScalarDistances.cosineSimilarity(a, b)).isBetween(-1.0f, 1.0f);
    }

    @Test
    @DisplayName("cosine similarity against a zero vector is 0 rather than NaN")
    void cosineAgainstZeroVectorIsZero() {
        assertThat(ScalarDistances.cosineSimilarity(new float[]{0, 0}, new float[]{1, 1}))
            .isEqualTo(0.0f);
    }

    @Test
    @DisplayName("empty vectors yield zero for every metric")
    void emptyVectorsYieldZero() {
        float[] empty = new float[0];

        assertThat(ScalarDistances.cosineSimilarity(empty, empty)).isEqualTo(0.0f);
        assertThat(ScalarDistances.l2Distance(empty, empty)).isEqualTo(0.0f);
        assertThat(ScalarDistances.l2Squared(empty, empty)).isEqualTo(0.0f);
        assertThat(ScalarDistances.innerProduct(empty, empty)).isEqualTo(0.0f);
    }

    @Test
    @DisplayName("L2 distance is the Euclidean norm of the difference")
    void l2DistanceIsEuclidean() {
        assertThat(ScalarDistances.l2Distance(new float[]{0, 0}, new float[]{3, 4}))
            .isCloseTo(5.0f, within());
    }

    @Test
    @DisplayName("L2 distance is symmetric")
    void l2DistanceIsSymmetric() {
        float[] a = {1.5f, -2.5f, 7.25f};
        float[] b = {-3.0f, 0.5f, 1.0f};

        assertThat(ScalarDistances.l2Distance(a, b))
            .isEqualTo(ScalarDistances.l2Distance(b, a));
    }

    @Test
    @DisplayName("L2 squared is the square of L2 distance and is never negative")
    void l2SquaredAgreesWithL2Distance() {
        float[] a = {1, 2, 3};
        float[] b = {4, 6, 3};

        assertThat(ScalarDistances.l2Squared(a, b)).isCloseTo(25.0f, within());
        assertThat(ScalarDistances.l2Squared(a, a)).isGreaterThanOrEqualTo(0.0f);
    }

    @Test
    @DisplayName("inner product sums the elementwise products")
    void innerProductSumsProducts() {
        assertThat(ScalarDistances.innerProduct(new float[]{1, 2, 3}, new float[]{4, 5, 6}))
            .isCloseTo(32.0f, within());
    }

    @Test
    @DisplayName("compensated summation keeps a large term from swallowing many small ones")
    void innerProductCompensatesForCatastrophicCancellation() {
        // One huge term followed by 4096 terms of 1.0. Accumulating naively in float
        // loses every small term; the compensated accumulator must keep all of them.
        int small = 4096;
        float[] a = new float[small + 1];
        float[] b = new float[small + 1];
        a[0] = 1e9f;
        b[0] = 1e9f;
        for (int i = 1; i <= small; i++) {
            a[i] = 1.0f;
            b[i] = 1.0f;
        }

        double expected = 1e9 * 1e9 + small;

        assertThat(ScalarDistances.innerProduct(a, b)).isNotNaN();
        assertThat(ScalarDistances.innerProductExact(a, b)).isEqualTo(expected);
    }

    @Test
    @DisplayName("mismatched dimensions are rejected rather than read out of bounds")
    void mismatchedDimensionsAreRejected() {
        float[] two = {1, 2};
        float[] three = {1, 2, 3};

        assertThatThrownBy(() -> ScalarDistances.cosineSimilarity(two, three))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2")
            .hasMessageContaining("3");
        assertThatThrownBy(() -> ScalarDistances.l2Distance(two, three))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScalarDistances.l2Squared(two, three))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScalarDistances.innerProduct(two, three))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null vectors are rejected")
    void nullVectorsAreRejected() {
        assertThatThrownBy(() -> ScalarDistances.l2Distance(null, new float[]{1}))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ScalarDistances.l2Distance(new float[]{1}, null))
            .isInstanceOf(NullPointerException.class);
    }

    private static org.assertj.core.data.Offset<Float> within() {
        return org.assertj.core.data.Offset.offset(TOLERANCE);
    }
}
