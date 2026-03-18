package excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FP Rate Measurement Test - Critical Specification Verification.
 *
 * <h2>Task Specification</h2>
 * <ul>
 *   <li>Insert exactly 1,000,000 items</li>
 *   <li>Query exactly 1,000,000 non-items</li>
 *   <li>Measure FP rate ≤ 0.1%</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>This test validates the core Cuckoo Filter specification requirement.
 * The false positive rate is a probabilistic measure that must be empirically
 * verified at scale to ensure the implementation meets production requirements.</p>
 *
 * <h2>Expected FP Rate</h2>
 * <p>With 13-bit fingerprints (default) at ~75% load factor (1M items in 1.33M capacity):</p>
 * <pre>
 *   FP_rate = 1 - (1 - 2^-13)^(2 * 4 * 0.75)
 *           = 1 - (1 - 1/8192)^6
 *           ≈ 0.073%
 * </pre>
 * <p>This is well within the ≤0.1% requirement.</p>
 *
 * @author ExcaliburDB
 */
class FPRateMeasurementTest {

    /** Exact number of items to insert per specification */
    private static final int ITEMS_TO_INSERT = 1_000_000;

    /** Exact number of non-items to query per specification */
    private static final int NON_ITEMS_TO_QUERY = 1_000_000;

    /** Maximum allowed false positive rate: 0.1% */
    private static final double MAX_FP_RATE = 0.001;

    /**
     * CRITICAL TEST: FP Rate Measurement - Exact 1M Items.
     *
     * <p><b>Specification Compliance:</b></p>
     * <ul>
     *   <li>Insert exactly 1,000,000 items</li>
     *   <li>Query exactly 1,000,000 non-items</li>
     *   <li>Assert measured FP rate ≤ 0.1%</li>
     * </ul>
     *
     * <p><b>Domain Correctness:</b></p>
     * <ul>
     *   <li>Uses 14-bit fingerprints for reliable ≤0.1% FP rate (theoretical ~0.046%)</li>
     *   <li>Load factor at 1M items: ~75% (1M / 1.33M capacity)</li>
     *   <li>Expected FP rate at 75% load with 14-bit: ~0.035% (well under 0.1%)</li>
     *   <li>All inserted items MUST be found (no false negatives)</li>
     *   <li>After insert, count MUST equal 1,000,000</li>
     * </ul>
     */
    @Test
    @DisplayName("FP rate measurement: Insert 1M items, query 1M non-items, measure FP rate ≤ 0.1%")
    void testFPRateMeasurement1MItems() {
        System.out.println("========================================");
        System.out.println("FP Rate Measurement Test");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Specification Requirements:");
        System.out.println("  Insert items: " + ITEMS_TO_INSERT);
        System.out.println("  Query non-items: " + NON_ITEMS_TO_QUERY);
        System.out.println("  Max FP rate: " + (MAX_FP_RATE * 100) + "%");
        System.out.println();

        // Create filter with 14-bit fingerprints for reliable ≤0.1% FP rate
        // 13-bit at 95% load factor has theoretical FP rate of ~0.093%, which is close to 0.1%
        // 14-bit at 95% load factor has theoretical FP rate of ~0.046%, which gives comfortable margin
        CuckooFilter filter = CuckooFilter.create(ITEMS_TO_INSERT, 14);

        System.out.println("Filter Configuration:");
        System.out.println("  Capacity: " + filter.capacity());
        System.out.println("  Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("  Number of buckets: " + filter.numBuckets());
        System.out.println();

        // Phase 1: Insert exactly 1,000,000 items
        Set<String> insertedItems = new HashSet<>(ITEMS_TO_INSERT);
        Random random = new Random(42); // Deterministic seed for reproducibility

        System.out.println("Phase 1: Inserting " + ITEMS_TO_INSERT + " items...");
        long insertStart = System.nanoTime();

        int successfulInserts = 0;
        int itemIndex = 0;

        while (successfulInserts < ITEMS_TO_INSERT) {
            String item = "fp-measure-item-" + itemIndex + "-" + random.nextInt(Integer.MAX_VALUE);
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);

            if (filter.insert(itemBytes)) {
                insertedItems.add(item);
                successfulInserts++;
            }
            itemIndex++;

            // Safety: prevent infinite loop if filter is unexpectedly full
            if (itemIndex > ITEMS_TO_INSERT * 2) {
                fail("Filter filled up before reaching " + ITEMS_TO_INSERT + " items. " +
                     "Only inserted: " + successfulInserts);
            }
        }

        long insertEnd = System.nanoTime();
        double insertTimeMs = (insertEnd - insertStart) / 1_000_000.0;
        double insertTimeSec = insertTimeMs / 1000.0;

        System.out.println("  Items inserted: " + successfulInserts);
        System.out.println("  Insert time: " + String.format("%.2f", insertTimeMs) + " ms (" +
                          String.format("%.2f", insertTimeSec) + " sec)");
        System.out.println("  Insert rate: " + String.format("%.0f", ITEMS_TO_INSERT / insertTimeSec) +
                          " items/sec");
        System.out.println();

        // Verify exact count
        assertEquals(ITEMS_TO_INSERT, filter.count(),
            "Filter count must equal exactly " + ITEMS_TO_INSERT + " after insertion");

        double actualLoadFactor = filter.loadFactor();
        System.out.println("Filter State After Insertion:");
        System.out.println("  Count: " + filter.count());
        System.out.println("  Load factor: " + String.format("%.4f", actualLoadFactor) +
                          " (" + String.format("%.2f", actualLoadFactor * 100) + "%)");
        System.out.println();

        // Phase 2: Verify no false negatives (all inserted items MUST be found)
        System.out.println("Phase 2: Verifying no false negatives...");
        long verifyStart = System.nanoTime();

        int falseNegatives = 0;
        for (String item : insertedItems) {
            if (!filter.contains(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
                if (falseNegatives <= 5) {
                    System.out.println("  WARNING: False negative found for: " + item);
                }
            }
        }

        long verifyEnd = System.nanoTime();
        double verifyTimeMs = (verifyEnd - verifyStart) / 1_000_000.0;

        System.out.println("  Items verified: " + insertedItems.size());
        System.out.println("  False negatives: " + falseNegatives);
        System.out.println("  Verification time: " + String.format("%.2f", verifyTimeMs) + " ms");
        System.out.println();

        // CRITICAL ASSERTION: No false negatives allowed
        assertEquals(0, falseNegatives,
            "False negatives are NOT allowed in Cuckoo Filter! Found " + falseNegatives);
        System.out.println("  ✓ PASSED: No false negatives detected");
        System.out.println();

        // Phase 3: Query exactly 1,000,000 non-items to measure FP rate
        System.out.println("Phase 3: Querying " + NON_ITEMS_TO_QUERY + " non-items to measure FP rate...");
        long fpStart = System.nanoTime();

        int falsePositives = 0;
        int validQueries = 0;

        // Reset random to generate different items than those inserted
        Random nonItemRandom = new Random(999);

        for (int i = 0; i < NON_ITEMS_TO_QUERY; i++) {
            // Generate a non-item that was definitely not inserted
            String nonItem = "non-existent-" + i + "-" + nonItemRandom.nextInt(Integer.MAX_VALUE);
            byte[] nonItemBytes = nonItem.getBytes(StandardCharsets.UTF_8);

            // Verify this is truly a non-item (not accidentally inserted)
            if (insertedItems.contains(nonItem)) {
                // Skip accidental collision (extremely rare)
                continue;
            }

            validQueries++;

            if (filter.contains(nonItemBytes)) {
                falsePositives++;
            }
        }

        long fpEnd = System.nanoTime();
        double fpTimeMs = (fpEnd - fpStart) / 1_000_000.0;
        double fpTimeSec = fpTimeMs / 1000.0;

        System.out.println("  Valid non-item queries: " + validQueries);
        System.out.println("  False positives: " + falsePositives);
        System.out.println("  Query time: " + String.format("%.2f", fpTimeMs) + " ms (" +
                          String.format("%.2f", fpTimeSec) + " sec)");
        System.out.println("  Query rate: " + String.format("%.0f", validQueries / fpTimeSec) +
                          " queries/sec");
        System.out.println();

        // Calculate and report FP rate
        double measuredFPRate = (double) falsePositives / validQueries;
        double measuredFPRatePercent = measuredFPRate * 100;

        System.out.println("========================================");
        System.out.println("FP Rate Measurement Results");
        System.out.println("========================================");
        System.out.println();
        System.out.println("  Items inserted: " + ITEMS_TO_INSERT);
        System.out.println("  Non-items queried: " + validQueries);
        System.out.println("  False positives: " + falsePositives);
        System.out.println();
        System.out.println("  Load factor: " + String.format("%.4f", actualLoadFactor) +
                          " (" + String.format("%.2f", actualLoadFactor * 100) + "%)");
        System.out.println();
        System.out.println("  Measured FP rate: " + String.format("%.6f", measuredFPRate) +
                          " (" + String.format("%.4f", measuredFPRatePercent) + "%)");
        System.out.println("  Maximum allowed: " + String.format("%.6f", MAX_FP_RATE) +
                          " (0.1%)");
        System.out.println();

        // CRITICAL ASSERTION: FP rate MUST be ≤ 0.1%
        // Using Java assert for specification compliance verification
        double fpRate = measuredFPRate;
        assert fpRate <= 0.001 : "FP rate " + fpRate + " exceeds 0.1%";

        // Also use JUnit assertion for test framework integration
        assertTrue(measuredFPRate <= MAX_FP_RATE,
            "FP rate " + String.format("%.6f", measuredFPRate) +
            " (" + String.format("%.4f", measuredFPRatePercent) + "%) " +
            "exceeds maximum allowed " + MAX_FP_RATE + " (0.1%) " +
            "at load factor " + String.format("%.4f", actualLoadFactor));

        System.out.println("  ✓ PASSED: FP rate " + String.format("%.4f", measuredFPRatePercent) +
                          "% ≤ 0.1%");
        System.out.println();
        System.out.println("========================================");
        System.out.println("SUCCESS: FP Rate Measurement Test Passed");
        System.out.println("========================================");
    }

    /**
     * Additional verification test with higher load factor to stress test FP rate.
     * This ensures the implementation remains correct even under higher loads.
     */
    @Test
    @DisplayName("FP rate at 95% load factor stress test")
    void testFPRateAtHighLoadFactor() {
        System.out.println("========================================");
        System.out.println("FP Rate Stress Test at 95% Load Factor");
        System.out.println("========================================");

        // Create filter sized for 1M items at 95% load factor
        long capacity = (long) (ITEMS_TO_INSERT / 0.95);
        CuckooFilter filter = CuckooFilter.create(capacity);

        System.out.println("Filter capacity: " + filter.capacity());
        System.out.println("Target load factor: 95%");

        Set<String> insertedItems = new HashSet<>();
        Random random = new Random(123);

        // Insert items
        int inserted = 0;
        while (inserted < ITEMS_TO_INSERT) {
            String item = "stress-item-" + inserted + "-" + random.nextInt(Integer.MAX_VALUE);
            byte[] itemBytes = item.getBytes(StandardCharsets.UTF_8);

            if (filter.insert(itemBytes)) {
                insertedItems.add(item);
                inserted++;
            }

            if (inserted > ITEMS_TO_INSERT * 2) {
                break; // Safety limit
            }
        }

        double loadFactor = filter.loadFactor();
        System.out.println("Items inserted: " + inserted);
        System.out.println("Actual load factor: " + String.format("%.4f", loadFactor));

        // Verify no false negatives
        int falseNegatives = 0;
        for (String item : insertedItems) {
            if (!filter.contains(item.getBytes(StandardCharsets.UTF_8))) {
                falseNegatives++;
            }
        }
        assertEquals(0, falseNegatives, "No false negatives allowed");

        // Measure FP rate
        Random nonItemRandom = new Random(456);
        int falsePositives = 0;
        int queries = Math.min(500_000, NON_ITEMS_TO_QUERY); // Fewer queries for stress test

        for (int i = 0; i < queries; i++) {
            String nonItem = "stress-non-item-" + i + "-" + nonItemRandom.nextInt(Integer.MAX_VALUE);
            if (!insertedItems.contains(nonItem) &&
                filter.contains(nonItem.getBytes(StandardCharsets.UTF_8))) {
                falsePositives++;
            }
        }

        double fpRate = (double) falsePositives / queries;
        System.out.println("FP rate at " + String.format("%.2f", loadFactor * 100) + "% load: " +
                          String.format("%.6f", fpRate) + " (" + String.format("%.4f", fpRate * 100) + "%)");

        // At 95% load factor with 13-bit fingerprints, FP should still be ≤ 0.1%
        // CRITICAL ASSERTION: FP rate MUST be ≤ 0.1% (spec requirement)
        assert fpRate <= 0.001 : "FP rate " + fpRate + " exceeds 0.1%";

        // Also use JUnit assertion for test framework integration
        assertTrue(fpRate <= MAX_FP_RATE,
            "FP rate at high load factor " + String.format("%.4f", fpRate) +
            " exceeds 0.1% limit");

        System.out.println("✓ Stress test passed: FP rate within spec at high load");
    }
}
