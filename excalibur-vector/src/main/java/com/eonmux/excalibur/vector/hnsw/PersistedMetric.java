package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.DistanceFunctions;

/**
 * Translates between a distance metric's name and the small integer a graph header
 * stores in its place.
 *
 * <p>The header has to say which metric built a graph's edges, and it has to say so
 * in a form that survives a rename of any class: a number, not a string. The numbers
 * below are therefore part of the on-disk format. A code that has been written can
 * never be reassigned to a different metric, and a new metric takes the next unused
 * number.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class holds only immutable state and is safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 * @since 1.3
 */
final class PersistedMetric {

    /**
     * The metric a graph was built with when its header predates the metric field.
     *
     * <p>Before the field existed cosine was the only metric a graph could be built
     * under, so an older file needs no field to be read correctly.</p>
     */
    static final String LEGACY_METRIC = "cosine";

    private static final int CODE_COSINE = 1;
    private static final int CODE_L2 = 2;
    private static final int CODE_INNER_PRODUCT = 3;

    static {
        // A code whose name the registry cannot resolve would let a graph record a
        // metric that nothing is able to instantiate on the way back in.
        for (int code : new int[] {CODE_COSINE, CODE_L2, CODE_INNER_PRODUCT}) {
            DistanceFunctions.byName(nameFor(code));
        }
    }

    private PersistedMetric() {
        throw new AssertionError("PersistedMetric is a utility class and is not instantiable");
    }

    /**
     * Returns the header code that stands for the given metric.
     *
     * @param metricName A canonical metric name, as reported by
     *                   {@code DistanceFunction.name()}
     * @return The code to write into a graph header
     * @throws IllegalArgumentException if no code has been assigned to that metric
     */
    static int codeFor(String metricName) {
        return switch (metricName) {
            case "cosine" -> CODE_COSINE;
            case "l2" -> CODE_L2;
            case "inner_product" -> CODE_INNER_PRODUCT;
            default -> throw new IllegalArgumentException(
                "No graph header code is assigned to distance metric '" + metricName
                    + "'; graphs can record " + DistanceFunctions.names());
        };
    }

    /**
     * Returns the metric a header code stands for.
     *
     * @param code The code read from a graph header
     * @return The canonical metric name
     * @throws IllegalArgumentException if the code is not one this build knows
     */
    static String nameFor(int code) {
        return switch (code) {
            case CODE_COSINE -> "cosine";
            case CODE_L2 -> "l2";
            case CODE_INNER_PRODUCT -> "inner_product";
            default -> throw new IllegalArgumentException(
                "Unrecognised distance metric code " + code + " in an HNSW graph header");
        };
    }
}
