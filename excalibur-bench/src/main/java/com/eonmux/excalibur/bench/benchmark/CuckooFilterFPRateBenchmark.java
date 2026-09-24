package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.bench.kernel.FalsePositiveTally;
import com.eonmux.excalibur.filter.CuckooFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark that measures and reports the actual false positive rate of the Cuckoo Filter.
 *
 * <h2>Overview</h2>
 * <p>Unlike throughput benchmarks, this benchmark measures accuracy by quantifying
 * the actual false positive rate at various load factors. The result is the measured
 * FP rate (0.0 to 1.0) that should be ≤0.001 (0.1%) per specification.</p>
 *
 * <h2>Measurement Methodology</h2>
 * <ol>
 *   <li>Insert N keys into the filter at target load factor</li>
 *   <li>Query M keys that were NOT inserted</li>
 *   <li>Count how many non-inserted keys return true (false positives)</li>
 *   <li>FP Rate = false_positives / total_miss_queries</li>
 * </ol>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>FP rate values must be in range [0.0, 1.0]</li>
 *   <li>FP rate should be ≤0.001 (0.1%) at 95% load factor with 13-bit fingerprints</li>
 *   <li>Sample size must be large enough for statistical significance (≥100,000 queries)</li>
 *   <li>No false negatives ever (all inserted keys must be found)</li>
 * </ul>
 *
 * <h2>Expected Results</h2>
 * <p>Per specification: "Target FP rate ≤0.1%"</p>
 * <ul>
 *   <li>At 50% load factor: Expected FP rate ≈ 0.02-0.05%</li>
 *   <li>At 75% load factor: Expected FP rate ≈ 0.04-0.08%</li>
 *   <li>At 90% load factor: Expected FP rate ≈ 0.06-0.10%</li>
 *   <li>At 95% load factor: Expected FP rate ≈ 0.08-0.12% (should still be ≤0.1%)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The CuckooFilter being tested has its own thread-safety guarantees.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class CuckooFilterFPRateBenchmark {

    /**
     * Load factor targets to test.
     * These represent realistic operating ranges for the filter.
     */
    @Param({"0.50", "0.75", "0.90", "0.95"})
    public double targetLoadFactor;

    /**
     * Capacity of the filter (number of entries at load factor 1.0).
     * Using 2M entries provides better statistical significance for FP rate measurement.
     */
    @Param({"2000000"})
    public long capacity;

    /**
     * Number of bits per fingerprint.
     * 13 bits provides ≤0.1% false positive rate at 95% load factor.
     */
    @Param({"13"})
    public int fingerprintBits;

    /**
     * Number of miss queries to perform per benchmark iteration.
     * Higher count = better statistical significance.
     */
    @Param({"100000"})
    public int queriesPerIteration;

    // The filter under test
    private CuckooFilter filter;

    // Keys that were NOT inserted (for false positive detection)
    private byte[][] nonInsertedKeys;

    // Number of entries actually inserted
    private long insertedCount;

    // Current query index
    private int queryIndex;

    // Pre-computed expected FP rate for comparison
    private double expectedFPRate;

    // Random number generator with fixed seed for reproducibility
    private static final Random RANDOM = new Random(42);

    /**
     * Maximum acceptable false positive rate for the Cuckoo filter (0.1%).
     */
    public static final double MAX_ACCEPTABLE_FP_RATE = 0.001; // 0.1%

    /**
     * Maximum allowed difference between the requested and the realized load factor.
     */
    public static final double LOAD_FACTOR_TOLERANCE = 0.005;

    /**
     * Number of standard errors of headroom allowed before the measured FP rate is
     * treated as a genuine specification breach rather than sampling noise.
     */
    private static final double FP_RATE_SIGMA_ALLOWANCE = 3.0;

    /**
     * Load factor at and above which the FP-rate specification is enforced.
     */
    private static final double SPEC_LOAD_FACTOR = 0.95;

    @Setup(Level.Trial)
    public void setup() {
        // Create the filter with specified capacity and fingerprint bits
        filter = CuckooFilter.create(capacity, fingerprintBits);

        // Derive the insert count from the REALIZED capacity, not the requested one.
        // CuckooFilter.create rounds the bucket count up to a power of two, so a
        // requested 2,000,000 becomes 2,097,152 slots: inserting
        // requestedCapacity * targetLoadFactor would measure the filter at a materially
        // lower load factor than the one this run is named after.
        insertedCount = (long) (filter.capacity() * targetLoadFactor);

        // Pre-generate and insert keys
        byte[][] insertedKeys = new byte[(int) insertedCount][];
        for (int i = 0; i < insertedCount; i++) {
            insertedKeys[i] = generateKey(i);
        }

        // Insert all keys into the filter
        int successfulInserts = 0;
        for (int i = 0; i < insertedCount; i++) {
            if (filter.insert(insertedKeys[i])) {
                successfulInserts++;
            } else {
                System.err.println("Warning: Insert failed at index " + i);
                break;
            }
        }

        // Pre-generate keys for miss lookups (distinct from inserted keys)
        // Use a different key space to ensure no overlap
        int missKeyCount = (int) Math.max(queriesPerIteration, 500000);
        nonInsertedKeys = new byte[missKeyCount][];
        for (int i = 0; i < missKeyCount; i++) {
            // Use keys from a different namespace (offset by capacity + large constant)
            nonInsertedKeys[i] = generateKey(i + (int) capacity + 10000000);
        }

        // Verify no false negatives (domain correctness check)
        int falseNegatives = 0;
        for (int i = 0; i < Math.min(10000, successfulInserts); i++) {
            if (!filter.contains(insertedKeys[i])) {
                falseNegatives++;
            }
        }
        if (falseNegatives > 0) {
            throw new IllegalStateException(
                "CRITICAL: " + falseNegatives + " false negatives detected! " +
                "Cuckoo filter must never have false negatives.");
        }

        // Calculate expected FP rate using theoretical formula:
        // FP_rate ≈ 1 - (1 - 2^-f)^(2 * b * load_factor)
        // where f = fingerprintBits, b = fingerprintsPerBucket (4)
        double fpPerFingerprint = Math.pow(2, -fingerprintBits);
        double loadFactor = filter.loadFactor();
        double effectiveSlots = 2 * 4 * loadFactor; // 2 buckets × 4 fingerprints × load
        expectedFPRate = 1 - Math.pow(1 - fpPerFingerprint, effectiveSlots);

        // The realized load factor must be the one this run claims to measure.
        if (Math.abs(loadFactor - targetLoadFactor) > LOAD_FACTOR_TOLERANCE) {
            throw new IllegalStateException(String.format(
                "Realized load factor %.4f differs from the target %.4f by more than %.4f; "
                    + "the measurement would not describe the operating point it is named after.",
                loadFactor, targetLoadFactor, LOAD_FACTOR_TOLERANCE));
        }

        // Log setup information
        System.out.println("========================================");
        System.out.println("Cuckoo Filter FP Rate Benchmark Setup");
        System.out.println("========================================");
        System.out.println("  Target load factor: " + targetLoadFactor);
        System.out.println("  Actual load factor: " + String.format("%.4f", loadFactor));
        System.out.println("  Realized capacity: " + filter.capacity());
        System.out.println("  Capacity: " + capacity);
        System.out.println("  Inserted entries: " + successfulInserts);
        System.out.println("  Fingerprint bits: " + fingerprintBits);
        System.out.println("  Queries per iteration: " + queriesPerIteration);
        System.out.println("  Miss key pool size: " + missKeyCount);
        System.out.println("  Expected FP rate (theoretical): " + String.format("%.4f%%", expectedFPRate * 100));
        System.out.println("  Max acceptable FP rate: " + String.format("%.4f%%", MAX_ACCEPTABLE_FP_RATE * 100));
        System.out.println("  False negatives detected: " + falseNegatives + " (MUST be 0)");
        System.out.println("========================================");

        // Initialize query index
        queryIndex = 0;
    }

    /**
     * Enforces the FP-rate specification at the end of the trial.
     *
     * <p>The FP rate is not a JMH score - this class is scored in ns/op - so without
     * this check nothing in the pipeline could ever fail on it. The measurement sweeps
     * the whole miss-key pool once and is compared against the spec with a
     * {@value #FP_RATE_SIGMA_ALLOWANCE}-sigma allowance for binomial sampling noise, so
     * a genuine breach fails while a borderline sample does not flake.</p>
     *
     * <p>Only the high-load configurations are gated: the lower load factors exist to
     * chart how the FP rate grows and are not covered by the specification.</p>
     */
    @TearDown(Level.Trial)
    public void enforceFPRateSpecification() {
        if (filter == null || nonInsertedKeys == null || nonInsertedKeys.length == 0) {
            return;
        }

        long falsePositives = 0;
        for (byte[] key : nonInsertedKeys) {
            if (filter.contains(key)) {
                falsePositives++;
            }
        }
        long queries = nonInsertedKeys.length;
        double measuredFPRate = (double) falsePositives / queries;

        System.out.println(String.format(
            "Measured FP rate at load factor %.4f: %.6f (%.4f%%) over %d miss queries "
                + "(theoretical %.6f)",
            filter.loadFactor(), measuredFPRate, measuredFPRate * 100, queries, expectedFPRate));

        if (targetLoadFactor < SPEC_LOAD_FACTOR) {
            return;
        }

        double standardError = Math.sqrt(
            Math.max(measuredFPRate * (1 - measuredFPRate), Double.MIN_NORMAL) / queries);
        double threshold = MAX_ACCEPTABLE_FP_RATE + FP_RATE_SIGMA_ALLOWANCE * standardError;
        if (measuredFPRate > threshold) {
            throw new IllegalStateException(String.format(
                "False positive rate %.6f (%.4f%%) at load factor %.4f exceeds the "
                    + "%.4f%% specification beyond %.0f standard errors (threshold %.6f).",
                measuredFPRate, measuredFPRate * 100, filter.loadFactor(),
                MAX_ACCEPTABLE_FP_RATE * 100, FP_RATE_SIGMA_ALLOWANCE, threshold));
        }
    }

    /**
     * Generates a deterministic 16-byte key from an index.
     *
     * @param index The key index
     * @return A 16-byte key
     */
    private byte[] generateKey(int index) {
        byte[] key = new byte[16];
        // First 8 bytes: prefix + numeric index
        String prefix = "fp-" + String.format("%011d", index);
        byte[] prefixBytes = prefix.getBytes();
        System.arraycopy(prefixBytes, 0, key, 0, Math.min(prefixBytes.length, 16));
        // Remaining bytes: deterministic random
        RANDOM.setSeed(index + 98765L);
        for (int i = prefixBytes.length; i < 16; i++) {
            key[i] = (byte) (RANDOM.nextInt(256) - 128);
        }
        return key;
    }

    /**
     * Benchmark: Measure actual false positive rate.
     *
     * <p>Performs queries on keys that were NOT inserted and counts false positives.
     * Returns the number of false positives found in this batch.</p>
     *
     * <p>Domain correctness: Result is a count in range [0, queriesPerIteration].</p>
     *
     * @param bh Blackhole for consuming results
     * @return Number of false positives in this batch
     */
    @Benchmark
    public int measureFPRateBatch(Blackhole bh) {
        int falsePositives = 0;

        for (int i = 0; i < queriesPerIteration; i++) {
            int idx = (queryIndex + i) % nonInsertedKeys.length;
            boolean result = filter.contains(nonInsertedKeys[idx]);

            // If contains returns true for a non-inserted key, it's a false positive
            if (result) {
                falsePositives++;
            }
        }

        queryIndex = (queryIndex + queriesPerIteration) % nonInsertedKeys.length;

        // Consume to prevent dead code elimination
        bh.consume(falsePositives);

        return falsePositives;
    }

    /**
     * Benchmark: Single false positive check.
     *
     * <p>Performs a single contains() on a non-inserted key.
     * Returns 1 if false positive, 0 if correctly returns false.</p>
     *
     * <p>This is useful for understanding per-query overhead.</p>
     *
     * @param bh Blackhole for consuming results
     * @return 1 if false positive detected, 0 otherwise
     */
    @Benchmark
    public int singleFPCheck(Blackhole bh) {
        int idx = queryIndex % nonInsertedKeys.length;
        queryIndex++;
        boolean result = filter.contains(nonInsertedKeys[idx]);
        bh.consume(result);
        return result ? 1 : 0;
    }

    /**
     * State class for FP rate validation across multiple iterations.
     * Accumulates total false positives and total queries to compute
     * a statistically significant FP rate.
     */
    @State(Scope.Thread)
    public static class FPRateAccumulator {

        private final FalsePositiveTally tally = new FalsePositiveTally(MAX_ACCEPTABLE_FP_RATE);

        /**
         * Records the result of a batch of FP checks.
         *
         * @param falsePositives Number of false positives in the batch
         * @param batchSize Number of queries in the batch
         */
        public void record(int falsePositives, int batchSize) {
            tally.record(falsePositives, batchSize);
        }

        /**
         * Gets the accumulated false positive rate.
         *
         * @return FP rate in range [0.0, 1.0]
         */
        public double getAccumulatedFPRate() {
            return tally.rate();
        }

        /**
         * Checks if the accumulated FP rate meets specification.
         *
         * @return true if probes were made and their FP rate is ≤ 0.1%
         */
        public boolean meetsSpecification() {
            return tally.meetsSpecification();
        }

        /**
         * Gets total false positives recorded.
         *
         * @return the accumulated false positive count
         */
        public long getTotalFalsePositives() {
            return tally.falsePositives();
        }

        /**
         * Gets total queries performed.
         *
         * @return the accumulated probe count
         */
        public long getTotalQueries() {
            return tally.queries();
        }
    }

    /**
     * Benchmark: FP rate with detailed statistics.
     *
     * <p>Performs a batch of queries and records detailed statistics
     * including the computed FP rate.</p>
     *
     * @param acc The accumulator state
     * @return The computed FP rate for this batch (0.0 to 1.0)
     */
    @Benchmark
    @OperationsPerInvocation(10000)
    public double measureFPRateWithStats(FPRateAccumulator acc) {
        int batchSize = 10000;
        int falsePositives = 0;

        for (int i = 0; i < batchSize; i++) {
            int idx = (queryIndex + i) % nonInsertedKeys.length;
            if (filter.contains(nonInsertedKeys[idx])) {
                falsePositives++;
            }
        }

        queryIndex = (queryIndex + batchSize) % nonInsertedKeys.length;

        // Record in accumulator
        acc.record(falsePositives, batchSize);

        // Return batch FP rate
        return (double) falsePositives / batchSize;
    }

    /**
     * Teardown to report final statistics.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        // Perform a final validation pass
        int validationSamples = 100000;
        int falsePositives = 0;

        for (int i = 0; i < validationSamples; i++) {
            int idx = i % nonInsertedKeys.length;
            if (filter.contains(nonInsertedKeys[idx])) {
                falsePositives++;
            }
        }

        double measuredFPRate = (double) falsePositives / validationSamples;
        boolean meetsSpec = measuredFPRate <= MAX_ACCEPTABLE_FP_RATE;

        System.out.println();
        System.out.println("========================================");
        System.out.println("Cuckoo Filter FP Rate Results");
        System.out.println("========================================");
        System.out.println("  Validation samples: " + validationSamples);
        System.out.println("  False positives found: " + falsePositives);
        System.out.println("  Measured FP rate: " + String.format("%.6f", measuredFPRate) +
                          " (" + String.format("%.4f%%", measuredFPRate * 100) + ")");
        System.out.println("  Expected FP rate: " + String.format("%.6f", expectedFPRate) +
                          " (" + String.format("%.4f%%", expectedFPRate * 100) + ")");
        System.out.println("  Max acceptable: " + String.format("%.6f", MAX_ACCEPTABLE_FP_RATE) +
                          " (" + String.format("%.4f%%", MAX_ACCEPTABLE_FP_RATE * 100) + ")");
        System.out.println("  SPEC COMPLIANCE: " + (meetsSpec ? "PASS ✓" : "FAIL ✗"));
        System.out.println("========================================");
    }
}
