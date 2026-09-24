package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Validation tests for CuckooFilterFPRateBenchmark.
 *
 * <p>These tests verify that:
 * <ol>
 *   <li>The benchmark correctly measures false positive rate</li>
 *   <li>The measured FP rate is within expected bounds</li>
 *   <li>The FP rate meets the ≤0.1% specification at 95% load factor</li>
 * </ol>
 *
 * @author ExcaliburDB
 */
class CuckooFilterFPRateBenchmarkTest {

    private static final Random RANDOM = new Random(42);

    /** Cuckoo filter target: false positive rate at most 0.1%. */
    private static final double MAX_ACCEPTABLE_FP_RATE = 0.001;

    /** Requested capacity; the filter rounds this up to a power-of-two bucket count. */
    private static final long REQUESTED_CAPACITY = 250_000;

    /** Number of miss queries per measurement. */
    private static final int SAMPLE_SIZE = 200_000;

    /** Fingerprint width the specification is stated for. */
    private static final int FINGERPRINT_BITS = 13;

    /**
     * How far the realized load factor may drift from the requested one.
     *
     * <p>Tight on purpose. The measurement must describe the operating point named in
     * the test, and a loose band let the "95% load factor" cases actually run at 90.6%
     * because the insert count was derived from the requested rather than the realized
     * capacity.</p>
     */
    private static final double LOAD_FACTOR_TOLERANCE = 0.005;

    /**
     * Standard-error allowance on the one-sided spec check.
     *
     * <p>Derived from the sample rather than being a flat percentage: the FP count is
     * binomial, so the threshold is {@code spec + SIGMA_ALLOWANCE * sqrt(p(1-p)/n)}.
     * At n=200,000 this is roughly 0.121%, against a theoretical 0.093% at 95% load -
     * tight enough to fail a genuine breach, wide enough not to flake.</p>
     */
    private static final double SIGMA_ALLOWANCE = 3.0;

    /**
     * Generates a deterministic 16-byte key from an index.
     */
    private byte[] generateKey(int index) {
        byte[] key = new byte[16];
        String prefix = "fp-" + String.format("%011d", index);
        byte[] prefixBytes = prefix.getBytes();
        System.arraycopy(prefixBytes, 0, key, 0, Math.min(prefixBytes.length, 16));
        RANDOM.setSeed(index + 98765L);
        for (int i = prefixBytes.length; i < 16; i++) {
            key[i] = (byte) (RANDOM.nextInt(256) - 128);
        }
        return key;
    }

    @Test
    @DisplayName("FP rate at 50% load factor should be well below 0.1%")
    void fpRateAt50PercentLoadFactor() {
        measureFPRate(0.50, FINGERPRINT_BITS, SAMPLE_SIZE);
    }

    @Test
    @DisplayName("FP rate at 75% load factor should be below 0.1%")
    void fpRateAt75PercentLoadFactor() {
        measureFPRate(0.75, FINGERPRINT_BITS, SAMPLE_SIZE);
    }

    @Test
    @DisplayName("FP rate at 90% load factor should be at or below 0.1%")
    void fpRateAt90PercentLoadFactor() {
        measureFPRate(0.90, FINGERPRINT_BITS, SAMPLE_SIZE);
    }

    @Test
    @DisplayName("FP rate at 95% load factor must meet spec (at most 0.1%)")
    void fpRateAt95PercentLoadFactor() {
        measureFPRate(0.95, FINGERPRINT_BITS, SAMPLE_SIZE);
    }

    @Test
    @DisplayName("Requested capacity is rounded up, so insert counts must come from the realized capacity")
    void realizedCapacityDiffersFromRequestedCapacity() {
        CuckooFilter filter = CuckooFilter.create(REQUESTED_CAPACITY, FINGERPRINT_BITS);

        // Documents why every measurement below derives its insert count from
        // filter.capacity(). Using the requested capacity would put the "95% load"
        // measurement at 90.6% of the filter's real capacity.
        assertThat(filter.capacity())
            .as("CuckooFilter.create rounds the bucket count up to a power of two")
            .isGreaterThan(REQUESTED_CAPACITY);

        double loadFromRequestedCapacity = (REQUESTED_CAPACITY * 0.95) / filter.capacity();
        assertThat(loadFromRequestedCapacity)
            .as("deriving the insert count from the requested capacity understates the load")
            .isLessThan(0.95 - LOAD_FACTOR_TOLERANCE);
    }

    /**
     * Measures the actual FP rate at a given load factor and asserts it meets spec.
     *
     * @param targetLoadFactor Target load factor (0.0 to 1.0)
     * @param fingerprintBits Number of fingerprint bits
     * @param sampleSize Number of miss queries to perform
     */
    private void measureFPRate(double targetLoadFactor, int fingerprintBits, int sampleSize) {
        CuckooFilter filter = CuckooFilter.create(REQUESTED_CAPACITY, fingerprintBits);

        // The insert count comes from the REALIZED capacity. CuckooFilter.create rounds
        // the bucket count up to a power of two, so deriving it from the requested
        // capacity would measure a filter at 90.6% load and label the result "95%".
        long realizedCapacity = filter.capacity();
        long insertCount = (long) (realizedCapacity * targetLoadFactor);

        for (int i = 0; i < insertCount; i++) {
            assertThat(filter.insert(generateKey(i)))
                .as("Insert should succeed at load factor " + targetLoadFactor)
                .isTrue();
        }

        // The realized load factor must be the one this measurement is named after.
        double actualLoadFactor = filter.loadFactor();
        assertThat(actualLoadFactor)
            .as("measurement must be taken at the load factor it claims (target %.2f)",
                targetLoadFactor)
            .isCloseTo(targetLoadFactor, within(LOAD_FACTOR_TOLERANCE));

        // Measure false positive rate using keys from a namespace that cannot collide
        // with the inserted range.
        int missKeyBase = (int) realizedCapacity + 10_000_000;
        int falsePositives = 0;
        for (int i = 0; i < sampleSize; i++) {
            if (filter.contains(generateKey(i + missKeyBase))) {
                falsePositives++;
            }
        }

        double measuredFPRate = (double) falsePositives / sampleSize;
        double expectedFPRate = theoreticalFPRate(fingerprintBits, actualLoadFactor);

        System.out.println(String.format(
            "Load factor %.4f (target %.2f, capacity %d): FP count = %d / %d, "
                + "FP rate = %.6f (%.4f%%), theoretical %.6f (%.4f%%)",
            actualLoadFactor, targetLoadFactor, realizedCapacity, falsePositives, sampleSize,
            measuredFPRate, measuredFPRate * 100, expectedFPRate, expectedFPRate * 100));

        // Verify no false negatives
        int falseNegatives = 0;
        for (int i = 0; i < Math.min(10000, (int) insertCount); i++) {
            if (!filter.contains(generateKey(i))) {
                falseNegatives++;
            }
        }
        assertThat(falseNegatives)
            .as("Cuckoo filter must have zero false negatives")
            .isZero();

        // A filter that never reports a positive on a miss would satisfy any upper
        // bound; require the measurement to be a real one.
        assertThat(falsePositives)
            .as("expected roughly %.0f false positives from theory - zero would mean "
                + "contains() is not consulting the fingerprints at all",
                expectedFPRate * sampleSize)
            .isPositive();

        // The specification is a one-sided claim: the FP rate must not exceed 0.1%.
        // The threshold is the spec plus a sampling allowance derived from the binomial
        // standard error at this sample size, not a flat multiple of the spec.
        double threshold = specThreshold(sampleSize);
        assertThat(measuredFPRate)
            .as("FP rate at load factor %.4f must be at most %.4f%% (0.1%% spec + %.0f "
                + "standard errors at n=%d), got %.4f%%",
                actualLoadFactor, threshold * 100, SIGMA_ALLOWANCE, sampleSize,
                measuredFPRate * 100)
            .isLessThanOrEqualTo(threshold);
    }

    /**
     * Theoretical Cuckoo filter FP rate: 1 - (1 - 2^-f)^(2 * b * loadFactor).
     */
    private static double theoreticalFPRate(int fingerprintBits, double loadFactor) {
        double fpPerFingerprint = Math.pow(2, -fingerprintBits);
        double effectiveSlots = 2 * 4 * loadFactor;
        return 1 - Math.pow(1 - fpPerFingerprint, effectiveSlots);
    }

    /**
     * One-sided acceptance threshold for the 0.1% specification at a given sample size.
     */
    private static double specThreshold(int sampleSize) {
        double standardError = Math.sqrt(
            MAX_ACCEPTABLE_FP_RATE * (1 - MAX_ACCEPTABLE_FP_RATE) / sampleSize);
        return MAX_ACCEPTABLE_FP_RATE + SIGMA_ALLOWANCE * standardError;
    }

    @Test
    @DisplayName("Benchmark class should have correct MAX_ACCEPTABLE_FP_RATE constant")
    void benchmarkConstantMatchesSpec() {
        assertThat(CuckooFilterFPRateBenchmark.MAX_ACCEPTABLE_FP_RATE)
            .as("MAX_ACCEPTABLE_FP_RATE should be 0.001 (0.1%)")
            .isEqualTo(0.001);
    }

    @Test
    @DisplayName("Repeated measurements at true 95% load agree with theory and meet spec")
    void multipleMeasurementsShouldBeConsistent() {
        double targetLoadFactor = 0.95;
        int sampleSize = 50_000;
        int runs = 5;

        CuckooFilter filter = CuckooFilter.create(125_000, FINGERPRINT_BITS);
        long realizedCapacity = filter.capacity();
        long insertCount = (long) (realizedCapacity * targetLoadFactor);
        for (int i = 0; i < insertCount; i++) {
            assertThat(filter.insert(generateKey(i)))
                .as("Insert should succeed at load factor %.2f", targetLoadFactor)
                .isTrue();
        }

        assertThat(filter.loadFactor())
            .as("measurement must be taken at a true %.0f%% load", targetLoadFactor * 100)
            .isCloseTo(targetLoadFactor, within(LOAD_FACTOR_TOLERANCE));

        int missKeyBase = (int) realizedCapacity + 10_000_000;
        double[] measurements = new double[runs];
        long totalFalsePositives = 0;
        for (int run = 0; run < runs; run++) {
            int falsePositives = 0;
            for (int i = 0; i < sampleSize; i++) {
                if (filter.contains(generateKey(i + missKeyBase + run * sampleSize))) {
                    falsePositives++;
                }
            }
            totalFalsePositives += falsePositives;
            measurements[run] = (double) falsePositives / sampleSize;
            System.out.println(String.format(
                "Run %d: FP rate = %.6f (%.4f%%)",
                run + 1, measurements[run], measurements[run] * 100));
        }

        int pooledSamples = runs * sampleSize;
        double pooledRate = (double) totalFalsePositives / pooledSamples;
        double expectedFPRate = theoreticalFPRate(FINGERPRINT_BITS, filter.loadFactor());

        System.out.println(String.format(
            "Pooled FP rate over %d samples: %.6f (%.4f%%), theoretical %.6f (%.4f%%)",
            pooledSamples, pooledRate, pooledRate * 100, expectedFPRate, expectedFPRate * 100));

        // The pooled estimate is the statistically meaningful one; compare it against
        // the specification with an allowance derived from its own sample size.
        assertThat(pooledRate)
            .as("pooled FP rate at true %.0f%% load must meet the 0.1%% spec, got %.4f%%",
                targetLoadFactor * 100, pooledRate * 100)
            .isLessThanOrEqualTo(specThreshold(pooledSamples));

        // Each individual run must also be consistent with the theoretical rate: a run
        // that is far off indicates the miss key namespace is leaking into the inserted
        // one rather than statistical noise.
        double runStandardError = Math.sqrt(expectedFPRate * (1 - expectedFPRate) / sampleSize);
        double runUpperBound = expectedFPRate + 4.0 * runStandardError;
        for (double measurement : measurements) {
            assertThat(measurement)
                .as("each run must agree with the theoretical %.4f%% within 4 standard "
                    + "errors (upper bound %.4f%%), got %.4f%%",
                    expectedFPRate * 100, runUpperBound * 100, measurement * 100)
                .isLessThanOrEqualTo(runUpperBound);
        }
    }
}
