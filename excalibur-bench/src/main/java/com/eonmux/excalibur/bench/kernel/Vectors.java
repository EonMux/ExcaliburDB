package com.eonmux.excalibur.bench.kernel;

import java.util.Objects;
import java.util.Random;

/**
 * Vector helpers shared by the benchmark datasets.
 *
 * <p>Several benchmarks build a synthetic dataset of unit-length vectors and each had
 * its own copy of the normalisation loop. One copy silently dividing by a zero norm
 * would seed a dataset containing {@code NaN}, and every distance computed against it
 * would be {@code NaN} - a benchmark that measures nothing while reporting a number.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless and thread-safe. The {@link Random} a caller passes in is not.</p>
 */
public final class Vectors {

    /**
     * Returns a unit-length copy of a vector.
     *
     * <p>A zero vector has no direction to preserve, so it is returned as zeros rather
     * than as {@code NaN}.</p>
     *
     * @param vector the vector to normalise; not modified
     * @return a new array of the same length scaled to unit norm
     */
    public static float[] normalized(float[] vector) {
        Objects.requireNonNull(vector, "vector");
        double sumOfSquares = 0.0;
        for (float v : vector) {
            sumOfSquares += (double) v * v;
        }

        float[] result = vector.clone();
        double norm = Math.sqrt(sumOfSquares);
        if (norm == 0.0) {
            return result;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) (result[i] / norm);
        }
        return result;
    }

    /**
     * Builds a dataset of random unit-length vectors.
     *
     * <p>Reproducible: the same seeded {@link Random} always yields the same dataset,
     * so a recall figure measured on one run describes the same data as the next.</p>
     *
     * @param random the source of randomness
     * @param count the number of vectors
     * @param dimension the dimension of each vector
     * @return a {@code count x dimension} dataset of unit vectors
     * @throws IllegalArgumentException if {@code count} or {@code dimension} is not positive
     */
    public static float[][] randomUnitVectors(Random random, int count, int dimension) {
        Objects.requireNonNull(random, "random");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got " + count);
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }

        float[][] dataset = new float[count][];
        for (int i = 0; i < count; i++) {
            float[] vector = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                vector[d] = random.nextFloat();
            }
            dataset[i] = normalized(vector);
        }
        return dataset;
    }

    private Vectors() {
        // Utility class
    }
}
