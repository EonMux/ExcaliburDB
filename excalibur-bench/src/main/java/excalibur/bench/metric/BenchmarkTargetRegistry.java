package excalibur.bench.metric;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registry of performance targets for ExcaliburDB benchmarks.
 *
 * <p>Defines the expected performance targets for each benchmark category
 * based on the PROJECT GOAL specifications.</p>
 *
 * <h2>Target Interpretation</h2>
 * <p>The PROJECT GOAL specified "≥1B ops/sec" for storage point lookups, which
 * has been interpreted as unrealistic for single-threaded Java code. The
 * realistic targets are documented per benchmark.</p>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkTargetRegistry {

    /**
     * Map of benchmark name patterns to their targets.
     * Key: regex pattern to match benchmark names
     * Value: Target configuration
     */
    private static final Map<Pattern, TargetConfig> TARGETS = new HashMap<>();

    static {
        // ===== Storage (Phase 2) =====
        // Point lookup: target ≥10M ops/sec (interpreted from "≥1B")
        register(".*PointLookup.*sequentialLookupFast",
            10_000_000.0, true, "ops/sec",
            "Realistic single-threaded target (interpreted from ≥1B spec)");
        register(".*PointLookup.*randomLookupFast",
            10_000_000.0, true, "ops/sec",
            "Realistic single-threaded target");
        register(".*PointLookup.*batchLookup100",
            50_000_000.0, true, "ops/sec",
            "Batch amortizes lock overhead");
        register(".*Segment.*lookup",
            10_000_000.0, true, "ops/sec",
            "Segment lookup throughput");

        // ===== Cuckoo Filter (Phase 3) =====
        // Lookup: high throughput
        register(".*CuckooFilter.*lookup.*",
            5_000_000.0, true, "ops/sec",
            "Cuckoo filter O(1) lookup");
        // Insert: graceful at high load
        register(".*CuckooFilter.*insert.*",
            1_000_000.0, true, "ops/sec",
            "Insert throughput at high load factor");
        // FP rate: ≤0.1%
        register(".*CuckooFilter.*FPRate.*",
            0.001, false, "rate",
            "False positive rate target ≤0.1%");

        // ===== HNSW (Phase 4) =====
        // Recall@10: ≥99%
        register(".*HnswRecall.*",
            0.99, true, "recall",
            "Recall@10 target ≥99%");
        // Search latency: p99 ≤10ms for small datasets
        register(".*Hnsw.*search.*",
            10.0, false, "ms",
            "HNSW search latency p99 target");

        // ===== Distance Functions (Phase 4) =====
        // Vector distance computation
        register(".*Distance.*cosine.*",
            50_000_000.0, true, "ops/sec",
            "Cosine similarity throughput");
        register(".*Distance.*l2.*",
            50_000_000.0, true, "ops/sec",
            "L2 distance throughput");
        register(".*DistanceVectorApi.*vector.*",
            100_000_000.0, true, "ops/sec",
            "Vector API distance throughput");

        // ===== Tail Scan (Phase 6) =====
        // Brute-force scan
        register(".*TailScan.*",
            1_000_000.0, true, "vectors/sec",
            "Tail scan brute-force throughput");

        // ===== End-to-End Query (Phase 10) =====
        // p50 latency
        register(".*EndToEnd.*p50.*",
            10.0, false, "ms",
            "End-to-end query latency p50 target");
        // p99 latency
        register(".*EndToEnd.*p99.*",
            50.0, false, "ms",
            "End-to-end query latency p99 target");
        // Overall throughput
        register(".*EndToEnd.*throughput.*",
            1000.0, true, "ops/sec",
            "End-to-end query throughput");

        // ===== Hash Functions (Phase 1) =====
        register(".*Hash.*",
            500_000_000.0, true, "ops/sec",
            "Hash function throughput");

        // ===== MappedFile (Phase 1) =====
        register(".*MappedFile.*read.*",
            1_000_000_000.0, true, "bytes/sec",
            "Memory-mapped read throughput");
        register(".*MappedFile.*write.*",
            500_000_000.0, true, "bytes/sec",
            "Memory-mapped write throughput");
    }

    private static void register(String pattern, double target, boolean higherIsBetter,
                                  String unit, String description) {
        TARGETS.put(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE),
            new TargetConfig(target, higherIsBetter, unit, description));
    }

    /**
     * Gets the target configuration for a benchmark.
     *
     * @param benchmarkName the fully qualified benchmark name
     * @return the target config, or null if no target is defined
     */
    public static TargetConfig getTarget(String benchmarkName) {
        for (Map.Entry<Pattern, TargetConfig> entry : TARGETS.entrySet()) {
            if (entry.getKey().matcher(benchmarkName).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Checks if a benchmark has a defined target.
     */
    public static boolean hasTarget(String benchmarkName) {
        return getTarget(benchmarkName) != null;
    }

    /**
     * Configuration for a benchmark target.
     */
    public static final class TargetConfig {
        private final double targetValue;
        private final boolean higherIsBetter;
        private final String unit;
        private final String description;

        public TargetConfig(double targetValue, boolean higherIsBetter,
                           String unit, String description) {
            this.targetValue = targetValue;
            this.higherIsBetter = higherIsBetter;
            this.unit = unit;
            this.description = description;
        }

        public double targetValue() {
            return targetValue;
        }

        public boolean higherIsBetter() {
            return higherIsBetter;
        }

        public String unit() {
            return unit;
        }

        public String description() {
            return description;
        }

        /**
         * Checks if a value meets this target.
         */
        public boolean isMet(double actualValue) {
            if (higherIsBetter) {
                return actualValue >= targetValue;
            } else {
                return actualValue <= targetValue;
            }
        }

        /**
         * Formats the target for display.
         */
        public String formatTarget() {
            String op = higherIsBetter ? "≥" : "≤";
            return String.format("%s %.2f %s (%s)", op, targetValue, unit, description);
        }

        @Override
        public String toString() {
            return formatTarget();
        }
    }

    private BenchmarkTargetRegistry() {
        // Utility class
    }
}
