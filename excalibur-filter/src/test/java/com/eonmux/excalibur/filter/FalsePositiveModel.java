package com.eonmux.excalibur.filter;

/**
 * The analytic false positive rate of a {@link CuckooFilter} at its current load, which the
 * deletion tests use as the budget a correct filter stays within.
 *
 * <h2>Formula</h2>
 * <pre>
 * FP_rate = 1 - (1 - 2^-f)^(2 * b * load_factor)
 * </pre>
 * <p>where {@code f} is the fingerprint width, {@code b} the fingerprints per bucket and a
 * lookup examines two buckets. An empty filter has no false positives.</p>
 */
public final class FalsePositiveModel {

    /** Slots per bucket, fixed by the filter's on-disk layout. */
    private static final int FINGERPRINTS_PER_BUCKET = 4;

    private FalsePositiveModel() {
    }

    /**
     * @param filter The filter whose current load and fingerprint width set the rate
     * @return The expected false positive rate, 0.0 to 1.0
     */
    public static double expectedRate(CuckooFilter filter) {
        double loadFactor = filter.loadFactor();
        if (loadFactor == 0.0) {
            return 0.0;
        }
        double perFingerprint = 1.0 / (1L << filter.fingerprintBits());
        double comparisons = 2.0 * FINGERPRINTS_PER_BUCKET * loadFactor;
        return 1.0 - Math.pow(1.0 - perFingerprint, comparisons);
    }
}
