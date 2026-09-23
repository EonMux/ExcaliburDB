package com.eonmux.excalibur.vector.distance;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Distance kernels compiled against the JDK Vector API.
 *
 * <p>This is the only class in the module that links against {@code jdk.incubator.vector}.
 * {@link VectorApiSupport} loads it only after confirming the module is resolved, so a JVM
 * started without {@code --add-modules=jdk.incubator.vector} never links it and keeps the
 * scalar paths.</p>
 *
 * <h2>Why direct calls</h2>
 * <p>C2 turns {@code FloatVector} operations into SIMD instructions only when it can see them
 * as calls on a constant species. Reached through {@code Method.invoke}, every lane-wide step
 * materialises a vector object and boxes its partial sum, and the "vectorized" path ran several
 * times slower than a plain scalar loop. The species is therefore a {@code static final}
 * constant and every operation is an ordinary call.</p>
 *
 * <h2>Accumulation</h2>
 * <p>Each kernel keeps two lane-wide float accumulators and reduces them to a double once at
 * the end; two accumulators halve the length of the loop-carried dependency chain. Each lane
 * sums about {@code length / (2 * lanes)} products, so for embedding dimensions the float
 * rounding stays far below the float precision of the returned distances. Elements past the last whole vector are summed in double. Products are
 * accumulated with a separate multiply and add rather than {@code fma}: where the hardware has
 * no fused instruction, {@code FloatVector.fma} falls back to {@code Math.fma} per lane, which
 * is slower than the scalar loop this class replaces.</p>
 *
 * <h2>Overflow</h2>
 * <p>A float lane overflows long before a double does: sixty-four components of 1e19 have
 * squares that each fit a float and a sum that does not. A kernel whose float total comes back
 * non-finite therefore sums the same range again in double and returns that. For finite inputs
 * the result is then the double-precision value the scalar paths compute; for a NaN or infinite
 * component it is still non-finite, which is what the distance functions' deferred input
 * check waits for. Either way the recheck costs one comparison on the common path.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
final class SimdVectorOperations implements VectorApiSupport.VectorOperations {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    @Override
    public double dotProduct(float[] v1, float[] v2, int from, int to) {
        final int lanes = SPECIES.length();
        FloatVector acc0 = FloatVector.zero(SPECIES);
        FloatVector acc1 = FloatVector.zero(SPECIES);
        int i = from;
        for (; i + 2 * lanes <= to; i += 2 * lanes) {
            acc0 = acc0.add(FloatVector.fromArray(SPECIES, v1, i)
                .mul(FloatVector.fromArray(SPECIES, v2, i)));
            acc1 = acc1.add(FloatVector.fromArray(SPECIES, v1, i + lanes)
                .mul(FloatVector.fromArray(SPECIES, v2, i + lanes)));
        }
        for (; i + lanes <= to; i += lanes) {
            acc0 = acc0.add(FloatVector.fromArray(SPECIES, v1, i)
                .mul(FloatVector.fromArray(SPECIES, v2, i)));
        }
        double sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
        for (; i < to; i++) {
            sum += (double) v1[i] * (double) v2[i];
        }
        return Double.isFinite(sum) ? sum : scalarDotProduct(v1, v2, from, to);
    }

    @Override
    public double squaredL2(float[] v1, float[] v2, int from, int to) {
        final int lanes = SPECIES.length();
        FloatVector acc0 = FloatVector.zero(SPECIES);
        FloatVector acc1 = FloatVector.zero(SPECIES);
        int i = from;
        for (; i + 2 * lanes <= to; i += 2 * lanes) {
            FloatVector d0 = FloatVector.fromArray(SPECIES, v1, i)
                .sub(FloatVector.fromArray(SPECIES, v2, i));
            FloatVector d1 = FloatVector.fromArray(SPECIES, v1, i + lanes)
                .sub(FloatVector.fromArray(SPECIES, v2, i + lanes));
            acc0 = acc0.add(d0.mul(d0));
            acc1 = acc1.add(d1.mul(d1));
        }
        for (; i + lanes <= to; i += lanes) {
            FloatVector d = FloatVector.fromArray(SPECIES, v1, i)
                .sub(FloatVector.fromArray(SPECIES, v2, i));
            acc0 = acc0.add(d.mul(d));
        }
        double sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
        for (; i < to; i++) {
            final double d = (double) v1[i] - (double) v2[i];
            sum += d * d;
        }
        return Double.isFinite(sum) ? sum : scalarSquaredL2(v1, v2, from, to);
    }

    @Override
    public double squaredNorm(float[] v, int from, int to) {
        final int lanes = SPECIES.length();
        FloatVector acc0 = FloatVector.zero(SPECIES);
        FloatVector acc1 = FloatVector.zero(SPECIES);
        int i = from;
        for (; i + 2 * lanes <= to; i += 2 * lanes) {
            FloatVector x0 = FloatVector.fromArray(SPECIES, v, i);
            FloatVector x1 = FloatVector.fromArray(SPECIES, v, i + lanes);
            acc0 = acc0.add(x0.mul(x0));
            acc1 = acc1.add(x1.mul(x1));
        }
        for (; i + lanes <= to; i += lanes) {
            FloatVector x = FloatVector.fromArray(SPECIES, v, i);
            acc0 = acc0.add(x.mul(x));
        }
        double sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
        for (; i < to; i++) {
            sum += (double) v[i] * (double) v[i];
        }
        return Double.isFinite(sum) ? sum : scalarDotProduct(v, v, from, to);
    }

    @Override
    public int speciesLength() {
        return SPECIES.length();
    }

    /**
     * The dot product in double, for a range whose float-lane sum did not come back finite.
     */
    private static double scalarDotProduct(float[] v1, float[] v2, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += (double) v1[i] * (double) v2[i];
        }
        return sum;
    }

    /**
     * The squared distance in double, for a range whose float-lane sum did not come back finite.
     */
    private static double scalarSquaredL2(float[] v1, float[] v2, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            final double d = (double) v1[i] - (double) v2[i];
            sum += d * d;
        }
        return sum;
    }
}
