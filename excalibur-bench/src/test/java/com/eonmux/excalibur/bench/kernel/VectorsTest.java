package com.eonmux.excalibur.bench.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the vector helpers used to build benchmark datasets.
 */
class VectorsTest {

    @Test
    @DisplayName("normalising produces a unit-length vector")
    void normalizedVectorHasUnitLength() {
        float[] unit = Vectors.normalized(new float[]{3.0f, 4.0f});

        assertThat(norm(unit)).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(unit).containsExactly(new float[]{0.6f, 0.8f}, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    @DisplayName("normalising leaves the input untouched")
    void normalizingDoesNotMutateTheInput() {
        float[] original = {3.0f, 4.0f};

        Vectors.normalized(original);

        assertThat(original).containsExactly(3.0f, 4.0f);
    }

    @Test
    @DisplayName("a zero vector normalises to a zero vector rather than to NaN")
    void zeroVectorNormalizesToZero() {
        assertThat(Vectors.normalized(new float[]{0.0f, 0.0f})).containsExactly(0.0f, 0.0f);
    }

    @Test
    @DisplayName("normalising preserves direction")
    void normalizingPreservesDirection() {
        float[] normalized = Vectors.normalized(new float[]{-2.0f, 0.0f, 0.0f});

        assertThat(normalized[0]).isNegative();
        assertThat(normalized[1]).isZero();
    }

    @Test
    @DisplayName("a random dataset is reproducible from its seed and is unit length")
    void randomUnitDatasetIsReproducibleAndNormalized() {
        float[][] first = Vectors.randomUnitVectors(new Random(42), 5, 8);
        float[][] second = Vectors.randomUnitVectors(new Random(42), 5, 8);

        assertThat(first).hasDimensions(5, 8);
        assertThat(first).isDeepEqualTo(second);
        for (float[] vector : first) {
            assertThat(norm(vector)).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    @DisplayName("a different seed gives a different dataset")
    void differentSeedsGiveDifferentDatasets() {
        float[][] a = Vectors.randomUnitVectors(new Random(1), 3, 4);
        float[][] b = Vectors.randomUnitVectors(new Random(2), 3, 4);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("dataset dimensions must be positive")
    void invalidDatasetShapeIsRejected() {
        assertThatThrownBy(() -> Vectors.randomUnitVectors(new Random(1), 0, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vectors.randomUnitVectors(new Random(1), 4, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static float norm(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum);
    }
}
