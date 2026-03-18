package excalibur.filter;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Standalone verification program for Cuckoo Filter FP Rate.
 *
 * <p>Validates that the measured false positive rate is ≤ 0.1% at load factor 0.95.</p>
 *
 * <h2>Fingerprint Size Analysis</h2>
 * <p>The spec states: "Fingerprint Size: 12 bits yields theoretical FP rate of 2^(-12) ≈ 0.024%
 * per bucket; with 4 fingerprints per bucket, actual FP ≈ 0.1%"</p>
 *
 * <p>Mathematical analysis reveals this needs correction:</p>
 * <ul>
 *   <li>Per-fingerprint match probability: 2^-f (where f = fingerprint bits)</li>
 *   <li>With 2 buckets × 4 fingerprints/bucket = 8 potential matches</li>
 *   <li>At 95% load factor: effective matches ≈ 8 × 0.95 = 7.6</li>
 * </ul>
 *
 * <p>Calculated FP rates at 95% load factor:</p>
 * <pre>
 *   12 bits: 1 - (1 - 1/4096)^7.6 ≈ 0.185% (EXCEEDS 0.1% target)
 *   13 bits: 1 - (1 - 1/8192)^7.6 ≈ 0.093% (MEETS ≤0.1% target)
 * </pre>
 *
 * <p><b>Conclusion:</b> 13-bit fingerprints are required to achieve the spec's ≤0.1% FP rate.</p>
 *
 * <p>Run with: java --enable-preview --source 21 excalibur.filter.FPRateVerification</p>
 */
public class FPRateVerification {

    private static final int TEST_ITEMS = 1_000_000;
    private static final double TARGET_LOAD_FACTOR = 0.95;
    private static final double MAX_FP_RATE = 0.001; // 0.1%

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Cuckoo Filter FP Rate Verification");
        System.out.println("========================================");
        System.out.println();

        // Create filter with default 13-bit fingerprints
        // Analysis shows 13 bits is required for ≤0.1% FP rate at 95% load:
        // - 12 bits: FP ≈ 0.185% (exceeds 0.1% target)
        // - 13 bits: FP ≈ 0.093% (meets ≤0.1% target)
        CuckooFilter filter = CuckooFilter.create(TEST_ITEMS);

        System.out.println("Filter Configuration:");
        System.out.println("  Capacity: " + filter.capacity());
        System.out.println("  Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("  Number of buckets: " + filter.numBuckets());
        System.out.println("  Target load factor: " + TARGET_LOAD_FACTOR);
        System.out.println();

        // Calculate target insertions to reach 95% load factor
        long targetInsertions = (long) (filter.capacity() * TARGET_LOAD_FACTOR);

        // Insert items to reach load factor ~0.95
        Set<String> insertedItems = new HashSet<>();
        Random random = new Random(42); // Deterministic seed for reproducibility

        int inserted = 0;

        System.out.println("Inserting " + targetInsertions + " items to reach load factor " + TARGET_LOAD_FACTOR + "...");
        long insertStart = System.nanoTime();

        for (int i = 0; i < targetInsertions; i++) {
            String item = "fp-test-item-" + random.nextInt(Integer.MAX_VALUE);
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);

            if (filter.insert(itemBytes)) {
                insertedItems.add(item);
                inserted++;
            }
        }

        long insertEnd = System.nanoTime();
        double insertTimeMs = (insertEnd - insertStart) / 1_000_000.0;

        double actualLoadFactor = filter.loadFactor();

        System.out.println("  Items inserted: " + inserted);
        System.out.println("  Actual load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  Insert time: " + String.format("%.2f", insertTimeMs) + " ms");
        System.out.println();

        // Verify no false negatives (all inserted items MUST be found)
        System.out.println("Verifying no false negatives...");
        int falseNegatives = 0;
        long verifyStart = System.nanoTime();

        for (String item : insertedItems) {
            if (!filter.contains(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
            }
        }

        long verifyEnd = System.nanoTime();
        double verifyTimeMs = (verifyEnd - verifyStart) / 1_000_000.0;

        System.out.println("  False negatives: " + falseNegatives + " (expected 0)");
        System.out.println("  Verification time: " + String.format("%.2f", verifyTimeMs) + " ms");

        if (falseNegatives > 0) {
            System.out.println("  ✗ FAILED: False negatives are NOT allowed in Cuckoo Filter!");
            System.exit(1);
        } else {
            System.out.println("  ✓ PASSED: No false negatives");
        }
        System.out.println();

        // Query non-inserted items to measure FP rate
        System.out.println("Measuring false positive rate...");
        int fpCount = 0;
        int nonItemQueries = inserted; // Query same number as inserted

        long fpStart = System.nanoTime();

        for (int i = 0; i < nonItemQueries; i++) {
            String nonItem = "non-existent-item-" + random.nextInt(Integer.MAX_VALUE);
            byte[] nonItemBytes = nonItem.getBytes(StandardCharsets.UTF_8);

            // Skip if accidentally inserted
            if (insertedItems.contains(nonItem)) {
                continue;
            }

            if (filter.contains(nonItemBytes)) {
                fpCount++;
            }
        }

        long fpEnd = System.nanoTime();
        double fpTimeMs = (fpEnd - fpStart) / 1_000_000.0;

        double fpRate = (double) fpCount / nonItemQueries;
        double fpRatePercent = fpRate * 100;

        System.out.println("  Non-item queries: " + nonItemQueries);
        System.out.println("  False positives: " + fpCount);
        System.out.println("  Query time: " + String.format("%.2f", fpTimeMs) + " ms");
        System.out.println();
        System.out.println("  Measured FP rate: " + String.format("%.6f", fpRate) +
            " (" + String.format("%.4f", fpRatePercent) + "%)");
        System.out.println("  Maximum allowed: " + String.format("%.6f", MAX_FP_RATE) +
            " (0.1%)");
        System.out.println();

        // CRITICAL ASSERTION: FP rate MUST be ≤ 0.1%
        boolean fpRatePassed = fpRate <= MAX_FP_RATE;

        System.out.println("========================================");
        System.out.println("RESULT: FP Rate Verification");
        System.out.println("========================================");
        System.out.println();
        System.out.println("  Load factor: " + String.format("%.4f", actualLoadFactor));
        System.out.println("  FP rate: " + String.format("%.6f", fpRate) +
            " (" + String.format("%.4f", fpRatePercent) + "%)");
        System.out.println("  Maximum: " + String.format("%.6f", MAX_FP_RATE) + " (0.1%)");
        System.out.println();

        if (fpRatePassed) {
            System.out.println("  ✓ PASSED: FP rate " + String.format("%.4f", fpRatePercent) +
                "% ≤ 0.1%");
            System.out.println();
            System.out.println("SUCCESS: Cuckoo Filter meets specification!");
            System.exit(0);
        } else {
            System.out.println("  ✗ FAILED: FP rate " + String.format("%.4f", fpRatePercent) +
                "% > 0.1%");
            System.out.println();
            System.out.println("FAILURE: Cuckoo Filter does NOT meet specification!");
            System.exit(1);
        }
    }
}
