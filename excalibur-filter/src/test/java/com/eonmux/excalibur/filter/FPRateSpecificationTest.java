package com.eonmux.excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enforces the Cuckoo filter false-positive-rate specification on every build.
 *
 * <h2>Why this class exists</h2>
 * <p>{@link FPRateMeasurementTest} implements the written specification at its stated scale
 * (1,000,000 items), but it is tagged {@code slow} and the root build excludes that tag from
 * every {@code Test} task, so nothing enforced the bound on a normal build. This class runs a
 * scaled-down measurement, untagged, at a genuine 0.95 load factor with the default 13-bit
 * fingerprints - the exact configuration the ≤0.1% target is stated for.</p>
 *
 * <h2>Statistical bound</h2>
 * <p>The theoretical rate for 13 bits at 0.95 load is ~0.093%, only 7% under the 0.1% target,
 * so at any affordable query count the raw bound would fail on sampling noise alone. The
 * assertion therefore allows three standard errors of the binomial sampling distribution at
 * the query count actually used, which is a one-sided bound the correct implementation clears
 * by more than four sigma while a materially worse implementation (for example the ~0.185%
 * of 12-bit fingerprints) misses it by a wide margin.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("Cuckoo FP Rate Specification")
class FPRateSpecificationTest {

    /** Nominal filter capacity for the scaled-down measurement. */
    private static final int NOMINAL_CAPACITY = 200_000;

    /** Load factor the specification states the FP target for. */
    private static final double TARGET_LOAD_FACTOR = 0.95;

    /** Number of non-items queried to measure the rate. */
    private static final int NON_ITEM_QUERIES = 300_000;

    /** Maximum allowed false positive rate: 0.1%. */
    private static final double MAX_FP_RATE = 0.001;

    /** Standard errors of headroom allowed for binomial sampling noise. */
    private static final double SIGMA_ALLOWANCE = 3.0;

    @Test
    @DisplayName("FP rate stays within 0.1% at a genuine 0.95 load factor with default fingerprints")
    @Timeout(300)
    void fpRateMeetsSpecificationAtTargetLoadFactor() {
        CuckooFilter filter = CuckooFilter.create(NOMINAL_CAPACITY);
        long targetInsertions = (long) (filter.capacity() * TARGET_LOAD_FACTOR);

        Set<String> inserted = new HashSet<>();
        Random random = new Random(20240815L);

        for (long attempt = 0; attempt < targetInsertions * 2 && inserted.size() < targetInsertions; attempt++) {
            String item = "fp-spec-item-" + attempt + "-" + random.nextInt(Integer.MAX_VALUE);
            if (filter.insert(item.getBytes(StandardCharsets.UTF_8))) {
                inserted.add(item);
            }
        }

        double loadFactor = filter.loadFactor();

        // The bound is only meaningful at the load factor the specification names.
        assertTrue(loadFactor >= TARGET_LOAD_FACTOR - 0.01,
            "filter must reach the specified load factor, reached " + loadFactor);
        assertEquals(13, filter.fingerprintBits(),
            "the specification bound is stated for the default 13-bit fingerprints");

        // No false negatives at this load factor, otherwise the FP measurement is meaningless.
        int falseNegatives = 0;
        for (String item : inserted) {
            if (!filter.contains(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
            }
        }
        assertEquals(0, falseNegatives, "inserted items must never go missing");

        Random nonItemRandom = new Random(7748291L);
        int falsePositives = 0;
        int queries = 0;

        for (int i = 0; i < NON_ITEM_QUERIES; i++) {
            String nonItem = "fp-spec-absent-" + i + "-" + nonItemRandom.nextInt(Integer.MAX_VALUE);
            if (inserted.contains(nonItem)) {
                continue;
            }
            queries++;
            if (filter.contains(nonItem.getBytes(StandardCharsets.UTF_8))) {
                falsePositives++;
            }
        }

        double measuredFPRate = (double) falsePositives / queries;
        double standardError = Math.sqrt(MAX_FP_RATE * (1 - MAX_FP_RATE) / queries);
        double upperBound = MAX_FP_RATE + SIGMA_ALLOWANCE * standardError;

        System.out.printf("FP spec: load=%.4f, queries=%d, falsePositives=%d, rate=%.6f, bound=%.6f%n",
            loadFactor, queries, falsePositives, measuredFPRate, upperBound);

        assertTrue(measuredFPRate <= upperBound,
            String.format("FP rate %.6f exceeds the %.6f bound (0.1%% plus %.0f sigma at %d queries) "
                + "at load factor %.4f", measuredFPRate, upperBound, SIGMA_ALLOWANCE, queries, loadFactor));
    }
}
