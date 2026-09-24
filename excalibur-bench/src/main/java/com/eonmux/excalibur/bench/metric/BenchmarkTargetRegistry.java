package com.eonmux.excalibur.bench.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registry of performance targets for ExcaliburDB benchmarks.
 *
 * <p>Each entry binds a benchmark-name pattern to a target on <em>one named metric</em>
 * in <em>one explicit unit</em>. Both parts matter:</p>
 * <ul>
 *   <li>The metric name selects what is compared. {@link #PRIMARY_METRIC} means JMH's
 *       primary score; any other value names a secondary metric attached to the result
 *       (for example {@code p99}). Without it a latency benchmark's ms/op score was
 *       compared against a recall target.</li>
 *   <li>The unit lets {@link BenchmarkResult} verify that the measurement and the
 *       target describe the same physical dimension before comparing them, and convert
 *       between compatible units. A target whose unit is incompatible with the measured
 *       unit is reported as unevaluated, never as met.</li>
 * </ul>
 *
 * <p>Entries are held in declaration order, so lookup is deterministic. A benchmark may
 * carry several targets, but at most one per metric: {@link #getTargets(String)} throws
 * if two entries claim the same metric for the same benchmark, which turns a silently
 * arbitrary choice into a hard failure.</p>
 *
 * <p>Each entry documents the reasoning behind its own figure.</p>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkTargetRegistry {

    /**
     * Metric name meaning "JMH's primary score for this benchmark".
     */
    public static final String PRIMARY_METRIC = "primary";

    /**
     * Floor on vector components processed per second by one distance computation.
     */
    static final double DISTANCE_COMPONENTS_PER_SEC = 1_500_000_000.0;

    /**
     * Registered targets, in declaration order.
     */
    private static final List<TargetConfig> TARGETS;

    static {
        List<TargetConfig> targets = new ArrayList<>();

        // ===== Storage - Mode.Throughput, JMH unit ops/s =====
        targets.add(throughputTarget(".*PointLookupBenchmark\\.sequentialLookupFast",
            10_000_000.0, "Realistic single-threaded target"));
        targets.add(throughputTarget(".*PointLookupBenchmark\\.randomLookupFast",
            10_000_000.0, "Realistic single-threaded target"));
        // Per lookup (@OperationsPerInvocation(100)). A batch is the lock-free lookup of
        // randomLookupFast repeated under one read lock, so that lookup is its ceiling: batching
        // can only remove the lock, and an uncontended read lock shared by a hundred lookups
        // costs nothing measurable. The target is therefore the single-lookup target - a batch
        // must not cost anything per lookup - not a multiple of it.
        targets.add(throughputTarget(".*PointLookupBenchmark\\.batchLookup100",
            10_000_000.0, "Per lookup: a batch shares one read lock, so it must hold the "
                + "single-lookup target"));
        targets.add(throughputTarget(".*SegmentBenchmark\\.(sequentialLookup|randomLookup)",
            10_000_000.0, "Segment lookup throughput"));

        // ===== Cuckoo Filter =====
        // Both targets are per lookup and per insert. The methods that make several calls per
        // invocation (lookupMixed, batchLookupHit, batchInsert100, batchInsert1000,
        // insertVariedKeySizes) declare @OperationsPerInvocation, so JMH already reports them
        // per call; the batch count is not a JMH parameter, so scaling here could not do it.
        targets.add(throughputTarget(".*CuckooFilterBenchmark\\..*[Ll]ookup.*",
            5_000_000.0, "Cuckoo filter O(1) lookup"));
        targets.add(throughputTarget(".*CuckooFilterInsertBenchmark\\..*[Ii]nsert.*",
            1_000_000.0, "Insert throughput at every load factor up to 0.95"));

        // ===== HNSW =====
        // Recall is not a JMH score: HnswRecallBenchmark runs in AverageTime mode, so its
        // primary score is ms/op. The 99% recall@10 requirement is enforced where it can
        // actually fail - HnswRecallBenchmark.setup() aborts the trial below target - and
        // asserted in HnswRecallBenchmarkValidationTest. Only latency is targeted here.
        targets.add(latencyTarget(".*HnswRecallBenchmark\\.searchLatency",
            10.0, "HNSW search latency target"));

        // ===== Distance Functions =====
        // One op is one distance between two vectors of `dimension` components, and the
        // benchmarks sweep dimension from 64 to 1536: ops/sec falls in proportion to the
        // dimension, so a flat ops/sec threshold was a different demand at every parameter
        // value. The dimension-independent rate is components/sec. The SIMD kernels sustain
        // 2.4-6.4G components/sec across the sweep on an AVX2 core - the low end at 64, where
        // the fixed per-call cost dominates - while a scalar loop manages 0.5G (the benchmark's
        // own *_scalar reference kernels), so the floor fails any path that falls off the
        // Vector API with room to spare for a noisy host. DistanceBenchmark's *_single and
        // DistanceVectorApiBenchmark's *_vectorApi call the same production method and share it.
        targets.add(componentRateTarget(".*DistanceBenchmark\\.cosineSimilarity_single",
            "Cosine similarity throughput per vector component"));
        targets.add(componentRateTarget(".*DistanceBenchmark\\.l2Distance_single",
            "L2 distance throughput per vector component"));
        targets.add(componentRateTarget(".*DistanceVectorApiBenchmark\\.(cosine|l2|innerProduct)_vectorApi",
            "Vector API distance throughput per vector component"));

        // ===== Tail Scan =====
        // One op scans the whole tail, and the benchmark sweeps tailSize from 100 to 50,000
        // entries, so the size-independent rate is entries/sec.
        targets.add(new TargetConfig(".*TailScanBenchmark\\.tailScan_(l2|cosine|largeK)",
            PRIMARY_METRIC, 1_000_000.0, true, MetricUnits.ENTRIES_PER_SEC, "tailSize",
            "Tail scan brute-force throughput per entry"));

        // ===== End-to-End Query - Mode.SampleTime, percentiles are secondary metrics =====
        targets.add(new TargetConfig(".*EndToEndQueryBenchmark\\.fullQueryPipeline",
            "p50", 10.0, false, "ms", null,
            "End-to-end query latency p50 target"));
        targets.add(new TargetConfig(".*EndToEndQueryBenchmark\\.fullQueryPipeline",
            "p99", 50.0, false, "ms", null,
            "End-to-end query latency p99 target"));

        // ===== Hash Functions =====
        // hashLong hashes a fixed 8-byte input, so ops/sec is well defined for it and
        // carries the documented 500M ops/sec target directly.
        targets.add(throughputTarget(".*HashBenchmark\\.hashLong",
            500_000_000.0, "Hash function throughput on a fixed 8-byte input"));
        // hashByteArray sweeps keySize from 8 B to 4 KB, so an ops/sec threshold would
        // mean something different at every parameter value. The size-independent
        // invariant is bytes hashed per second.
        targets.add(new TargetConfig(".*HashBenchmark\\.hashByteArray",
            PRIMARY_METRIC, 1_000_000_000.0, true, MetricUnits.BYTES_PER_SEC, "keySize",
            "Hash function bulk throughput"));

        // ===== MappedFile =====
        // One op walks the whole file - the benchmark loops until the offset passes fileSize -
        // so fileSize is what a single operation transfers. Scaling by bufferSize instead
        // understated the rate by fileSize/bufferSize, which is up to 262144x across the swept
        // parameters: a run sustaining gigabytes per second was reported in kilobytes and
        // failed its target, and any real regression was invisible under the same distortion.
        targets.add(new TargetConfig(".*MappedFileBenchmark\\.sequentialRead_bytes",
            PRIMARY_METRIC, 1_000_000_000.0, true, MetricUnits.BYTES_PER_SEC, "fileSize",
            "Memory-mapped read throughput"));
        targets.add(new TargetConfig(".*MappedFileBenchmark\\.sequentialWrite_bytes",
            PRIMARY_METRIC, 500_000_000.0, true, MetricUnits.BYTES_PER_SEC, "fileSize",
            "Memory-mapped write throughput"));

        TARGETS = Collections.unmodifiableList(targets);
        verifyNoDuplicatePatternMetricPairs(TARGETS);
    }

    private static TargetConfig throughputTarget(String pattern, double opsPerSec, String description) {
        return new TargetConfig(pattern, PRIMARY_METRIC, opsPerSec, true,
            MetricUnits.OPS_PER_SEC, null, description);
    }

    private static TargetConfig componentRateTarget(String pattern, String description) {
        return new TargetConfig(pattern, PRIMARY_METRIC, DISTANCE_COMPONENTS_PER_SEC, true,
            MetricUnits.COMPONENTS_PER_SEC, "dimension", description);
    }

    private static TargetConfig latencyTarget(String pattern, double ms, String description) {
        return new TargetConfig(pattern, PRIMARY_METRIC, ms, false, "ms", null, description);
    }

    private static void verifyNoDuplicatePatternMetricPairs(List<TargetConfig> targets) {
        Map<String, TargetConfig> seen = new LinkedHashMap<>();
        for (TargetConfig target : targets) {
            String key = target.pattern() + " " + target.metric();
            TargetConfig previous = seen.put(key, target);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate benchmark target registration for pattern '" + target.pattern()
                        + "' and metric '" + target.metric() + "'");
            }
        }
    }

    /**
     * Gets every target that applies to a benchmark, in declaration order.
     *
     * @param benchmarkName the fully qualified benchmark name
     * @return the matching targets, possibly empty, never null
     * @throws IllegalStateException if two registered targets claim the same metric
     *         for this benchmark, which would make the choice between them arbitrary
     */
    public static List<TargetConfig> getTargets(String benchmarkName) {
        List<TargetConfig> matches = new ArrayList<>();
        Map<String, TargetConfig> byMetric = new LinkedHashMap<>();
        for (TargetConfig target : TARGETS) {
            if (!target.matches(benchmarkName)) {
                continue;
            }
            TargetConfig previous = byMetric.put(target.metric(), target);
            if (previous != null) {
                throw new IllegalStateException(
                    "Ambiguous targets for benchmark '" + benchmarkName + "' on metric '"
                        + target.metric() + "': patterns '" + previous.pattern()
                        + "' and '" + target.pattern() + "' both match");
            }
            matches.add(target);
        }
        return Collections.unmodifiableList(matches);
    }

    /**
     * Gets the target on the primary JMH score for a benchmark.
     *
     * @param benchmarkName the fully qualified benchmark name
     * @return the target config, or null if the benchmark has no primary-score target
     */
    public static TargetConfig getTarget(String benchmarkName) {
        for (TargetConfig target : getTargets(benchmarkName)) {
            if (PRIMARY_METRIC.equals(target.metric())) {
                return target;
            }
        }
        return null;
    }

    /**
     * Checks if a benchmark has any defined target.
     *
     * @param benchmarkName the fully qualified benchmark name
     * @return true if at least one target applies
     */
    public static boolean hasTarget(String benchmarkName) {
        return !getTargets(benchmarkName).isEmpty();
    }

    /**
     * Gets every registered target.
     *
     * @return an immutable list of all targets in declaration order
     */
    public static List<TargetConfig> allTargets() {
        return TARGETS;
    }

    /**
     * Configuration for a benchmark target.
     */
    public static final class TargetConfig {
        private final String pattern;
        private final Pattern compiledPattern;
        private final String metric;
        private final double targetValue;
        private final boolean higherIsBetter;
        private final String unit;
        private final String scaleByParameter;
        private final String description;

        /**
         * Creates a target configuration.
         *
         * @param pattern regex matched against the fully qualified benchmark name
         * @param metric the metric this target applies to, or {@link #PRIMARY_METRIC}
         * @param targetValue the threshold, expressed in {@code unit}
         * @param higherIsBetter true if the measurement must be at or above the threshold
         * @param unit the unit {@code targetValue} is expressed in
         * @param scaleByParameter JMH parameter whose numeric value is how many units of
         *        {@code unit}'s work one operation performs - bytes, entries, vector
         *        components - so that ops/sec times it is a rate in {@code unit}; or null for
         *        no scaling
         * @param description human-readable rationale
         */
        public TargetConfig(String pattern, String metric, double targetValue,
                            boolean higherIsBetter, String unit, String scaleByParameter,
                            String description) {
            this.pattern = pattern;
            this.compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            this.metric = metric;
            this.targetValue = targetValue;
            this.higherIsBetter = higherIsBetter;
            this.unit = unit;
            this.scaleByParameter = scaleByParameter;
            this.description = description;
        }

        /**
         * Checks whether this target applies to the given benchmark name.
         *
         * @param benchmarkName the fully qualified benchmark name
         * @return true if the registered pattern matches the whole name
         */
        public boolean matches(String benchmarkName) {
            return compiledPattern.matcher(benchmarkName).matches();
        }

        public String pattern() {
            return pattern;
        }

        public String metric() {
            return metric;
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

        /**
         * Gets the JMH parameter whose value scales the measurement before comparison.
         *
         * @return the parameter name, or null if no scaling applies
         */
        public String scaleByParameter() {
            return scaleByParameter;
        }

        public String description() {
            return description;
        }

        /**
         * Checks if a value expressed in {@link #unit()} meets this target.
         *
         * @param actualValue the measured value, already converted to {@link #unit()}
         * @return true if the value satisfies the threshold
         */
        public boolean isMet(double actualValue) {
            if (higherIsBetter) {
                return actualValue >= targetValue;
            }
            return actualValue <= targetValue;
        }

        /**
         * Formats the target for display.
         *
         * @return a human-readable description of the threshold
         */
        public String formatTarget() {
            String op = higherIsBetter ? ">=" : "<=";
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
