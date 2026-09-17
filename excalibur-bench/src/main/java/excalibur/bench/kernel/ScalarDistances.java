package excalibur.bench.kernel;

import java.util.Objects;

/**
 * Scalar reference implementations of the vector distance metrics.
 *
 * <p>These exist so a benchmark can put a Vector API implementation beside a plain
 * loop and compare both the speed and the answer. That makes them the <em>reference</em>
 * for correctness, not merely the slow path: if these are wrong, a SIMD implementation
 * that disagrees with them looks like the broken one.</p>
 *
 * <p>Every accumulator is compensated (Kahan summation) and runs in {@code double}.
 * A 1536-dimensional embedding contributes 1536 products to a dot product, and the
 * error of a naive {@code float} accumulation over that many terms is large enough to
 * move a nearest-neighbour ranking - which would show up as a recall regression in a
 * component that never changed.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless and thread-safe.</p>
 */
public final class ScalarDistances {

    /**
     * Cosine similarity of two vectors, in {@code [-1, 1]}.
     *
     * <p>Returns {@code 0} when either vector has zero norm: the angle between a point
     * and the origin is undefined, and {@code NaN} propagating into a ranking turns an
     * ordering into an arbitrary one rather than an obviously wrong one.</p>
     *
     * @param v1 the first vector
     * @param v2 the second vector, of the same dimension
     * @return the cosine similarity, clamped to {@code [-1, 1]}
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static float cosineSimilarity(float[] v1, float[] v2) {
        requireSameDimension(v1, v2);
        if (v1.length == 0) {
            return 0.0f;
        }

        CompensatedSum dot = new CompensatedSum();
        CompensatedSum norm1 = new CompensatedSum();
        CompensatedSum norm2 = new CompensatedSum();
        for (int i = 0; i < v1.length; i++) {
            double a = v1[i];
            double b = v2[i];
            dot.add(a * b);
            norm1.add(a * a);
            norm2.add(b * b);
        }

        double normProduct = norm1.value() * norm2.value();
        if (normProduct == 0.0) {
            return 0.0f;
        }
        return clampToUnitRange(dot.value() / Math.sqrt(normProduct));
    }

    /**
     * Euclidean (L2) distance between two vectors.
     *
     * @param v1 the first vector
     * @param v2 the second vector, of the same dimension
     * @return the distance, never negative
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static float l2Distance(float[] v1, float[] v2) {
        return (float) Math.sqrt(squaredDifferenceSum(v1, v2));
    }

    /**
     * Squared Euclidean distance between two vectors.
     *
     * <p>Ranking by this is equivalent to ranking by {@link #l2Distance}, and it skips
     * the square root.</p>
     *
     * @param v1 the first vector
     * @param v2 the second vector, of the same dimension
     * @return the squared distance, never negative
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static float l2Squared(float[] v1, float[] v2) {
        return (float) squaredDifferenceSum(v1, v2);
    }

    /**
     * Inner (dot) product of two vectors.
     *
     * @param v1 the first vector
     * @param v2 the second vector, of the same dimension
     * @return the dot product
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static float innerProduct(float[] v1, float[] v2) {
        return (float) innerProductExact(v1, v2);
    }

    /**
     * Inner product without narrowing the accumulator to {@code float}.
     *
     * <p>The compensated sum is computed in {@code double}; narrowing it to
     * {@code float} discards the very precision the compensation recovered. Callers
     * checking the arithmetic itself want this value.</p>
     *
     * @param v1 the first vector
     * @param v2 the second vector, of the same dimension
     * @return the dot product in double precision
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static double innerProductExact(float[] v1, float[] v2) {
        requireSameDimension(v1, v2);
        CompensatedSum sum = new CompensatedSum();
        for (int i = 0; i < v1.length; i++) {
            sum.add((double) v1[i] * (double) v2[i]);
        }
        return sum.value();
    }

    private static double squaredDifferenceSum(float[] v1, float[] v2) {
        requireSameDimension(v1, v2);
        CompensatedSum sum = new CompensatedSum();
        for (int i = 0; i < v1.length; i++) {
            double diff = (double) v1[i] - (double) v2[i];
            sum.add(diff * diff);
        }
        // Compensation can leave a sum of squares fractionally below zero; a negative
        // distance is not a value any caller can act on.
        return Math.max(0.0, sum.value());
    }

    /**
     * Holds the documented [-1, 1] range of a cosine similarity.
     *
     * <p>Compensated accumulation keeps the quotient inside the range on its own, so
     * these branches are a guard rather than a correction: they are what makes the
     * range a property of the method instead of a property of the summation.</p>
     */
    private static float clampToUnitRange(double value) {
        if (value > 1.0) {
            return 1.0f;
        }
        if (value < -1.0) {
            return -1.0f;
        }
        return (float) value;
    }

    private static void requireSameDimension(float[] v1, float[] v2) {
        Objects.requireNonNull(v1, "v1");
        Objects.requireNonNull(v2, "v2");
        if (v1.length != v2.length) {
            throw new IllegalArgumentException(
                "Vector dimensions differ: " + v1.length + " and " + v2.length);
        }
    }

    /**
     * A Kahan-compensated running sum.
     *
     * <p>Mutable by necessity - it is an accumulator - and confined to one loop on one
     * thread, never shared.</p>
     */
    private static final class CompensatedSum {
        private double sum;
        private double compensation;

        void add(double term) {
            double y = term - compensation;
            double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        double value() {
            return sum;
        }
    }

    private ScalarDistances() {
        // Utility class
    }
}
