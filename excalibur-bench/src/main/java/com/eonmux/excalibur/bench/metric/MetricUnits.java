package com.eonmux.excalibur.bench.metric;

import java.util.Map;
import java.util.Set;

/**
 * Normalisation and conversion of benchmark measurement units.
 *
 * <p>JMH reports a score together with a unit string that depends on the benchmark
 * mode and the {@code @OutputTimeUnit} annotation ({@code ops/s}, {@code ms/op},
 * {@code ns/op}, ...). A performance target is expressed in the unit its author had
 * in mind. Comparing the two without checking that they describe the same physical
 * dimension silently produces nonsense - for example testing a recall target of 0.99
 * against a score of 12 ms/op.</p>
 *
 * <p>This class makes that comparison explicit: {@link #isCompatible(String, String)}
 * answers whether two units measure the same thing, and {@link #convert(double,
 * String, String)} performs the conversion, refusing rather than guessing when the
 * dimensions differ.</p>
 *
 * <h2>Dimensions and canonical units</h2>
 * <ul>
 *   <li>{@link Dimension#THROUGHPUT} - canonical {@code ops/sec}</li>
 *   <li>{@link Dimension#TIME_PER_OP} - canonical {@code ms/op}</li>
 *   <li>{@link Dimension#BYTES_PER_SEC} - canonical {@code bytes/sec}</li>
 *   <li>{@link Dimension#DIMENSIONLESS} - canonical {@code ratio} (recall, FP rate, ...)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and stateless; all methods are thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class MetricUnits {

    /**
     * The physical dimension a unit measures.
     */
    public enum Dimension {
        /** Operations completed per unit of time (higher is better). */
        THROUGHPUT,
        /** Time consumed by a single operation (lower is better). */
        TIME_PER_OP,
        /** Bytes transferred per unit of time (higher is better). */
        BYTES_PER_SEC,
        /** A pure ratio such as recall or a false positive rate. */
        DIMENSIONLESS,
        /** The unit string was not recognised. */
        UNKNOWN
    }

    /** Canonical throughput unit. */
    public static final String OPS_PER_SEC = "ops/sec";

    /**
     * Vector components processed per second: a distance over {@code D}-dimensional vectors
     * processes {@code D} of them. A throughput, so it converts one-for-one with ops/sec.
     */
    public static final String COMPONENTS_PER_SEC = "components/sec";

    /** Log entries processed per second. A throughput, so it converts one-for-one with ops/sec. */
    public static final String ENTRIES_PER_SEC = "entries/sec";

    /** Canonical latency unit. */
    public static final String MS_PER_OP = "ms/op";

    /** Canonical transfer-rate unit. */
    public static final String BYTES_PER_SEC = "bytes/sec";

    /** Canonical dimensionless unit. */
    public static final String RATIO = "ratio";

    /** Factor converting a throughput unit into {@code ops/sec}. */
    private static final Map<String, Double> THROUGHPUT_FACTORS = Map.ofEntries(
        Map.entry("ops/s", 1.0),
        Map.entry("ops/sec", 1.0),
        Map.entry("op/s", 1.0),
        Map.entry("op/sec", 1.0),
        Map.entry("ops/second", 1.0),
        Map.entry("qps", 1.0),
        Map.entry("vectors/sec", 1.0),
        Map.entry("vectors/s", 1.0),
        Map.entry("entries/sec", 1.0),
        Map.entry("entries/s", 1.0),
        Map.entry("components/sec", 1.0),
        Map.entry("components/s", 1.0),
        Map.entry("ops/ms", 1_000.0),
        Map.entry("ops/us", 1_000_000.0),
        Map.entry("ops/ns", 1_000_000_000.0),
        Map.entry("ops/min", 1.0 / 60.0)
    );

    /** Factor converting a latency unit into {@code ms/op}. */
    private static final Map<String, Double> TIME_PER_OP_FACTORS = Map.ofEntries(
        Map.entry("s/op", 1_000.0),
        Map.entry("ms/op", 1.0),
        Map.entry("us/op", 1.0e-3),
        Map.entry("ns/op", 1.0e-6),
        Map.entry("s", 1_000.0),
        Map.entry("ms", 1.0),
        Map.entry("us", 1.0e-3),
        Map.entry("ns", 1.0e-6)
    );

    /** Factor converting a transfer-rate unit into {@code bytes/sec}. */
    private static final Map<String, Double> BYTES_PER_SEC_FACTORS = Map.of(
        "bytes/sec", 1.0,
        "bytes/s", 1.0,
        "b/s", 1.0,
        "kb/sec", 1_000.0,
        "mb/sec", 1_000_000.0,
        "gb/sec", 1_000_000_000.0
    );

    /** Unit strings that denote a pure ratio. */
    private static final Set<String> DIMENSIONLESS_UNITS =
        Set.of("ratio", "rate", "recall", "score", "fraction", "probability");

    /**
     * Returns the dimension the given unit measures.
     *
     * @param unit a unit string, may be null
     * @return the dimension, or {@link Dimension#UNKNOWN} if unrecognised
     */
    public static Dimension dimensionOf(String unit) {
        String u = normalize(unit);
        if (u.isEmpty()) {
            return Dimension.UNKNOWN;
        }
        if (THROUGHPUT_FACTORS.containsKey(u)) {
            return Dimension.THROUGHPUT;
        }
        if (TIME_PER_OP_FACTORS.containsKey(u)) {
            return Dimension.TIME_PER_OP;
        }
        if (BYTES_PER_SEC_FACTORS.containsKey(u)) {
            return Dimension.BYTES_PER_SEC;
        }
        if (DIMENSIONLESS_UNITS.contains(u)) {
            return Dimension.DIMENSIONLESS;
        }
        return Dimension.UNKNOWN;
    }

    /**
     * Checks whether two units describe the same physical dimension.
     *
     * <p>An unrecognised unit is never compatible with anything, including itself:
     * an unknown unit carries no guarantee that a comparison is meaningful.</p>
     *
     * @param unitA the first unit
     * @param unitB the second unit
     * @return true if a value in {@code unitA} can be converted to {@code unitB}
     */
    public static boolean isCompatible(String unitA, String unitB) {
        Dimension a = dimensionOf(unitA);
        return a != Dimension.UNKNOWN && a == dimensionOf(unitB);
    }

    /**
     * Converts a measurement from one unit to another.
     *
     * @param value the measured value
     * @param fromUnit the unit {@code value} is expressed in
     * @param toUnit the unit to convert to
     * @return the converted value
     * @throws IllegalArgumentException if the units describe different dimensions
     *         or either is unrecognised
     */
    public static double convert(double value, String fromUnit, String toUnit) {
        Dimension from = dimensionOf(fromUnit);
        Dimension to = dimensionOf(toUnit);
        if (from == Dimension.UNKNOWN || to == Dimension.UNKNOWN || from != to) {
            throw new IllegalArgumentException(
                "Cannot convert '" + fromUnit + "' (" + from + ") to '" + toUnit + "' (" + to + ")");
        }
        return value * factor(fromUnit, from) / factor(toUnit, to);
    }

    /**
     * Returns whether higher values are better for the given unit.
     *
     * @param unit the unit string
     * @return true for throughput and transfer rates, false for latency
     * @throws IllegalArgumentException if the direction cannot be derived from the unit
     */
    public static boolean higherIsBetter(String unit) {
        return switch (dimensionOf(unit)) {
            case THROUGHPUT, BYTES_PER_SEC -> true;
            case TIME_PER_OP -> false;
            default -> throw new IllegalArgumentException(
                "Cannot derive optimisation direction from unit '" + unit + "'");
        };
    }

    /**
     * Returns the canonical unit for a dimension.
     *
     * @param dimension the dimension
     * @return the canonical unit string
     */
    public static String canonicalUnit(Dimension dimension) {
        return switch (dimension) {
            case THROUGHPUT -> OPS_PER_SEC;
            case TIME_PER_OP -> MS_PER_OP;
            case BYTES_PER_SEC -> BYTES_PER_SEC;
            case DIMENSIONLESS -> RATIO;
            case UNKNOWN -> "unknown";
        };
    }

    private static double factor(String unit, Dimension dimension) {
        String u = normalize(unit);
        return switch (dimension) {
            case THROUGHPUT -> THROUGHPUT_FACTORS.get(u);
            case TIME_PER_OP -> TIME_PER_OP_FACTORS.get(u);
            case BYTES_PER_SEC -> BYTES_PER_SEC_FACTORS.get(u);
            case DIMENSIONLESS -> 1.0;
            case UNKNOWN -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }

    private static String normalize(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private MetricUnits() {
        // Utility class
    }
}
