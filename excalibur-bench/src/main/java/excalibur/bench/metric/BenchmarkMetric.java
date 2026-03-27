package excalibur.bench.metric;

/**
 * Represents a single metric from a benchmark run.
 *
 * <p>Immutable data class holding metric name, value, and unit.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values: positive ops/sec, typically 1M-100M range for single-threaded</li>
 *   <li>Latency values: non-negative, typically in nanoseconds or milliseconds</li>
 *   <li>Percentile values: p50 should be less than p99</li>
 *   <li>Score values: cosine similarity [-1, 1], recall [0, 1], FP rate [0, 0.001]</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkMetric {

    private final String name;
    private final double value;
    private final String unit;
    private final double error;
    private final long sampleCount;

    /**
     * Creates a new benchmark metric.
     *
     * @param name the metric name (e.g., "throughput", "p50", "p99")
     * @param value the metric value
     * @param unit the unit (e.g., "ops/sec", "ms", "ns")
     * @param error the error margin (standard deviation or confidence interval)
     * @param sampleCount the number of samples collected
     */
    public BenchmarkMetric(String name, double value, String unit, double error, long sampleCount) {
        this.name = name;
        this.value = value;
        this.unit = unit;
        this.error = error;
        this.sampleCount = sampleCount;
    }

    /**
     * Creates a metric with no error information.
     */
    public BenchmarkMetric(String name, double value, String unit) {
        this(name, value, unit, 0.0, 0);
    }

    /**
     * Creates a throughput metric (ops/sec).
     *
     * @param opsPerSec operations per second - must be positive
     * @param error the error margin
     * @return a throughput metric
     * @throws IllegalArgumentException if opsPerSec is not positive
     */
    public static BenchmarkMetric throughput(double opsPerSec, double error) {
        if (opsPerSec <= 0) {
            throw new IllegalArgumentException(
                "Throughput must be a positive number (ops/sec), got: " + opsPerSec);
        }
        if (Double.isNaN(opsPerSec) || Double.isInfinite(opsPerSec)) {
            throw new IllegalArgumentException(
                "Throughput must be a finite positive number, got: " + opsPerSec);
        }
        return new BenchmarkMetric("throughput", opsPerSec, "ops/sec", error, 0);
    }

    /**
     * Creates a latency percentile metric (milliseconds).
     */
    public static BenchmarkMetric latencyPercentile(int percentile, double valueMs, double error) {
        return new BenchmarkMetric("p" + percentile, valueMs, "ms", error, 0);
    }

    /**
     * Creates a latency percentile metric (nanoseconds).
     */
    public static BenchmarkMetric latencyPercentileNs(int percentile, double valueNs, double error) {
        return new BenchmarkMetric("p" + percentile + "_ns", valueNs, "ns", error, 0);
    }

    /**
     * Creates a score metric (dimensionless, 0-1 range).
     */
    public static BenchmarkMetric score(String name, double value, double error) {
        return new BenchmarkMetric(name, value, "score", error, 0);
    }

    /**
     * Creates a count metric.
     */
    public static BenchmarkMetric count(String name, long count) {
        return new BenchmarkMetric(name, count, "count", 0, count);
    }

    public String name() {
        return name;
    }

    public double value() {
        return value;
    }

    public String unit() {
        return unit;
    }

    public double error() {
        return error;
    }

    public long sampleCount() {
        return sampleCount;
    }

    /**
     * Checks if this metric meets a target threshold.
     *
     * @param target the target value
     * @param higherIsBetter true if higher values are better
     * @return true if the metric meets or exceeds the target
     */
    public boolean meetsTarget(double target, boolean higherIsBetter) {
        if (higherIsBetter) {
            return value >= target;
        } else {
            return value <= target;
        }
    }

    /**
     * Formats the metric as a human-readable string.
     */
    public String format() {
        if (error > 0) {
            return String.format("%s: %.2f ± %.2f %s", name, value, error, unit);
        } else {
            return String.format("%s: %.2f %s", name, value, unit);
        }
    }

    /**
     * Converts the metric to a JSON representation.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"name\": \"").append(escapeJson(name)).append("\",\n");
        sb.append("      \"value\": ").append(value).append(",\n");
        sb.append("      \"unit\": \"").append(escapeJson(unit)).append("\",\n");
        sb.append("      \"error\": ").append(error).append(",\n");
        sb.append("      \"sampleCount\": ").append(sampleCount).append("\n");
        sb.append("    }");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return format();
    }
}
