package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.filter.CuckooFilter;

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

import static com.eonmux.excalibur.integration.DeletionTestFilters.filter;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The insert, query, delete and verify lifecycle of the Cuckoo filter at stress volume.
 *
 * <p>The other deletion suites each isolate one property. This one runs the sequence a real
 * deployment runs - populate, query, delete a subset, query again - many times over with the
 * operations interleaved. Its value is in the combination: a delete path that is correct in
 * isolation can still be wrong once insertions have relocated fingerprints around it.</p>
 */
class DeletionLifecycleIntegrationTest {

    @Nested
    @DisplayName("End-to-End System Integration")
    class EndToEndSystemIntegration {

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
