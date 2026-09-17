package excalibur.integration;

import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static excalibur.integration.DeletionTestFilters.filter;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full insert, query, delete and verify lifecycle across the filter hierarchy.
 *
 * <p>The other deletion suites each isolate one property. These two exercise the
 * sequence a real deployment runs — populate, query, delete a subset, query again —
 * against both a flat and a hierarchical filter, and then repeat it at stress volume
 * with many interleaved operations. Their value is in the combination: a delete path
 * that is correct in isolation can still be wrong once insertions have relocated
 * fingerprints around it.</p>
 */
class DeletionLifecycleIntegrationTest {

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
    }

    @Nested
    @DisplayName("End-to-End System Integration")
    class EndToEndSystemIntegration {

        /**
         * Test: Full lifecycle - insert, query, delete, verify.
         *
         * <p>Domain correctness: Simulates real user workflow with a complete
         * lifecycle of entries in the system.</p>
         */
        @Test
        @DisplayName("full lifecycle insert query delete verify")
        void fullLifecycleInsertQueryDeleteVerify() {
            // Create a hierarchical filter simulating a multi-segment index
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(50000);

            // Register segments for different document types
            for (int seg = 0; seg < 10; seg++) {
                String cluster = (seg < 5) ? "cluster-alpha" : "cluster-beta";
                hcf.registerSegment("documents-" + seg, cluster);
            }

            // Insert documents (simulating ingestion)
            Set<String> allKeys = new HashSet<>();
            for (int seg = 0; seg < 10; seg++) {
                String segmentId = "documents-" + seg;
                for (int doc = 0; doc < 100; doc++) {
                    String key = "doc-" + seg + "-" + doc + "-" + System.nanoTime();
                    boolean inserted = hcf.insertKey(key, segmentId);
                    if (inserted) {
                        allKeys.add(key);
                    }
                }
            }

            System.out.println("Inserted " + allKeys.size() + " documents across 10 segments");

            // Query phase - verify all inserted documents are findable
            int foundCount = 0;
            for (String key : allKeys) {
                if (hcf.mightContainKey(key)) {
                    foundCount++;
                }
            }
            assertThat(foundCount).isEqualTo(allKeys.size());

            // Simulate document deletion (e.g., user deleted documents)
            Set<String> keysToDelete = new HashSet<>();
            int deleteCount = 0;
            for (String key : allKeys) {
                if (deleteCount >= allKeys.size() / 2) break;
                keysToDelete.add(key);
                deleteCount++;
            }

            // Delete documents - must determine segment for each key
            // For this test, we search segments to find the right one
            int successfullyDeleted = 0;
            for (String key : keysToDelete) {
                byte[] keyBytes = HierarchicalCuckooFilter.toBytes(key);
                List<String> candidates = hcf.findCandidateSegments(keyBytes);
                for (String segId : candidates) {
                    if (hcf.deleteFromHierarchy(keyBytes, segId)) {
                        successfullyDeleted++;
                        break;
                    }
                }
            }

            System.out.println("Deleted " + successfullyDeleted + " documents");

            // Verify deleted documents are NOT found (no false positives)
            int falsePositives = 0;
            for (String key : keysToDelete) {
                if (hcf.mightContainKey(key)) {
                    falsePositives++;
                }
            }
            assertThat(falsePositives)
                .as("CRITICAL: Deleted documents must not appear in queries")
                .isEqualTo(0);

            // Verify remaining documents are still findable
            Set<String> remainingKeys = new HashSet<>(allKeys);
            remainingKeys.removeAll(keysToDelete);
            int remainingFound = 0;
            for (String key : remainingKeys) {
                if (hcf.mightContainKey(key)) {
                    remainingFound++;
                }
            }
            assertThat(remainingFound).isEqualTo(remainingKeys.size());

            System.out.println("End-to-end verification: " + falsePositives +
                " false positives, " + remainingFound + "/" + remainingKeys.size() +
                " remaining documents found");
        }

        /**
         * Test: Stress test with many operations.
         */
        @Test
        @DisplayName("stress test with many operations")
        void stressTestWithManyOperations() {
            CuckooFilter filter = filter(50000);

            Set<String> activeKeys = new HashSet<>();
            Random rng = new Random(12345);

            // Perform many insert/delete cycles
            int totalInserts = 0;
            int totalDeletes = 0;
            int failedInserts = 0;

            for (int round = 0; round < 100; round++) {
                // Insert batch
                for (int i = 0; i < 100; i++) {
                    String key = "stress-" + round + "-" + i;
                    if (filter.insert(key.getBytes(StandardCharsets.UTF_8))) {
                        activeKeys.add(key);
                        totalInserts++;
                    } else {
                        failedInserts++;
                    }
                }

                // Delete random subset
                List<String> keyList = new ArrayList<>(activeKeys);
                Collections.shuffle(keyList, rng);
                int toDelete = Math.min(50, keyList.size());
                for (int i = 0; i < toDelete; i++) {
                    String key = keyList.get(i);
                    if (filter.delete(key.getBytes(StandardCharsets.UTF_8))) {
                        activeKeys.remove(key);
                        totalDeletes++;
                    }
                }
            }

            System.out.println("Stress test: " + totalInserts + " inserts, " +
                totalDeletes + " deletes, " + failedInserts + " failed inserts, " +
                activeKeys.size() + " active keys");

            // Verify all active keys are present
            int missingCount = 0;
            for (String key : activeKeys) {
                if (!filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    missingCount++;
                }
            }
            assertThat(missingCount).as("All active keys should be present").isEqualTo(0);

            // Delete all remaining and verify zero false positives
            Set<String> toVerify = new HashSet<>(activeKeys);
            for (String key : toVerify) {
                filter.delete(key.getBytes(StandardCharsets.UTF_8));
                activeKeys.remove(key);
            }

            int falsePositives = 0;
            for (String key : toVerify) {
                if (filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    falsePositives++;
                }
            }
            assertThat(falsePositives)
                .as("No false positives after stress test deletion")
                .isEqualTo(0);

            System.out.println("Stress test passed: " + falsePositives + " false positives");
        }
    }
}
