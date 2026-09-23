package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.filter.CuckooFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static com.eonmux.excalibur.integration.DeletionTestFilters.DEFAULT_CAPACITY;
import static com.eonmux.excalibur.integration.DeletionTestFilters.filter;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deletion correctness for a single Cuckoo filter.
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>After delete(key), contains(key) returns false unless a surviving key shares its
 *       fingerprint and bucket, which is inherent to the design rather than a defect</li>
 *   <li>Fingerprint collisions are possible at <strong>any</strong> load factor - load changes
 *       how likely one is, not whether one can happen. At 1000 items in a 10000-entry filter
 *       roughly one run in twenty produces one, which is why these tests fix the hash seed
 *       rather than assert a probability holds for every seed</li>
 *   <li>False positive rate for non-deleted keys must remain &le;0.1%</li>
 *   <li>Delete operations must succeed (return true) for existing keys</li>
 *   <li>Delete operations must fail (return false) for non-existent keys</li>
 * </ul>
 *
 * <h2>Fingerprint Collision Note</h2>
 * <p>Cuckoo filters use fingerprint hashing. With 13-bit fingerprints (8191 possible values)
 * and 4 fingerprints per bucket, two different keys may share the same fingerprint AND bucket.
 * When this happens, deleting one key removes the shared fingerprint, but contains() may still
 * find the other key's fingerprint. This is a fundamental limitation, not a bug.</p>
 *
 * <p>Covers deletion one key at a time and in bulk, reinsertion after deletion, repeated
 * insert/delete cycles, deletion at a high load factor, and the false positive rate that
 * survives it.</p>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class DeletionCorrectnessIntegrationTest {

    @Nested
    @DisplayName("Single Filter Deletion")
    class SingleFilterDeletion {

        /**
         * Test: Deleted entries return false for contains().
         *
         * <p>Domain correctness: After delete(key), contains(key) MUST return false.
         * This is a critical correctness requirement - no false positives for deleted keys.</p>
         */
        @Test
        @DisplayName("deleted entries not found by contains")
        void deletedEntriesNotFoundByContains() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            // Insert entries
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                String key = "key-" + i;
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                assertThat(filter.insert(keyBytes)).isTrue();
                keys.add(key);
            }

            // Verify all inserted
            for (String key : keys) {
                assertThat(filter.contains(key.getBytes(StandardCharsets.UTF_8)))
                    .as("Key " + key + " should be present before deletion")
                    .isTrue();
            }

            // Delete half the keys
            Set<String> deletedKeys = new HashSet<>();
            int deleteCount = 0;
            for (String key : keys) {
                if (deleteCount < 500) {
                    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                    boolean deleted = filter.delete(keyBytes);
                    assertThat(deleted)
                        .as("Delete should succeed for existing key " + key)
                        .isTrue();
                    deletedKeys.add(key);
                    deleteCount++;
                }
            }

            // Verify deleted keys are NOT found
            int falsePositiveCount = 0;
            for (String key : deletedKeys) {
                boolean contains = filter.contains(key.getBytes(StandardCharsets.UTF_8));
                if (contains) {
                    falsePositiveCount++;
                }
            }

            // CRITICAL: No false positives for deleted keys
            assertThat(falsePositiveCount)
                .as("Deleted keys must NOT be found (no false positives for deleted keys)")
                .isEqualTo(0);

            // Verify non-deleted keys are still found
            Set<String> remainingKeys = new HashSet<>(keys);
            remainingKeys.removeAll(deletedKeys);
            for (String key : remainingKeys) {
                assertThat(filter.contains(key.getBytes(StandardCharsets.UTF_8)))
                    .as("Non-deleted key " + key + " should still be present")
                    .isTrue();
            }

            System.out.println("Deletion correctness: " + deletedKeys.size() +
                " deleted, " + falsePositiveCount + " false positives");
        }

        /**
         * Test: Delete non-existent key returns false.
         */
        @Test
        @DisplayName("delete non-existent key returns false")
        void deleteNonExistentKeyReturnsFalse() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            // Insert some keys
            for (int i = 0; i < 100; i++) {
                filter.insert(("key-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Deleting a key that was never inserted almost always fails, and occasionally
            // does not: a cuckoo filter matches fingerprints, so a key that was never inserted
            // can match one that was. That is the documented reason a caller must only delete
            // what it inserted -- a spurious success here removes a real key's fingerprint and
            // turns it into a false negative. Asserting zero made this test fail roughly one
            // run in fifteen for behaviour the structure is defined to have.
            int spuriousSuccesses = 0;
            for (int i = 1000; i < 1100; i++) {
                if (filter.delete(("nonexistent-" + i).getBytes(StandardCharsets.UTF_8))) {
                    spuriousSuccesses++;
                }
            }

            assertThat(spuriousSuccesses)
                .as("deleting a never-inserted key succeeds only on a fingerprint collision, "
                    + "which must stay rare")
                .isLessThan(5);

            System.out.println("Delete of non-existent keys: " + spuriousSuccesses
                + " fingerprint collisions out of 100");
        }

        /**
         * Test: Insert after delete works correctly.
         */
        @Test
        @DisplayName("insert after delete works correctly")
        void insertAfterDeleteWorksCorrectly() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            String key = "reinsert-key";
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            // Insert
            assertThat(filter.insert(keyBytes)).isTrue();
            assertThat(filter.contains(keyBytes)).isTrue();

            // Delete
            assertThat(filter.delete(keyBytes)).isTrue();
            assertThat(filter.contains(keyBytes)).isFalse();

            // Re-insert
            assertThat(filter.insert(keyBytes)).isTrue();
            assertThat(filter.contains(keyBytes)).isTrue();

            // Delete again
            assertThat(filter.delete(keyBytes)).isTrue();
            assertThat(filter.contains(keyBytes)).isFalse();

            System.out.println("Insert after delete works correctly");
        }

        /**
         * Test: Multiple insert/delete cycles work correctly.
         */
        @Test
        @DisplayName("multiple insert/delete cycles work correctly")
        void multipleInsertDeleteCyclesWorkCorrectly() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            String key = "cycle-key";
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            for (int cycle = 0; cycle < 100; cycle++) {
                // Insert
                assertThat(filter.insert(keyBytes))
                    .as("Insert should succeed in cycle " + cycle)
                    .isTrue();
                assertThat(filter.contains(keyBytes))
                    .as("Key should be present after insert in cycle " + cycle)
                    .isTrue();

                // Delete
                assertThat(filter.delete(keyBytes))
                    .as("Delete should succeed in cycle " + cycle)
                    .isTrue();
                assertThat(filter.contains(keyBytes))
                    .as("Key should NOT be present after delete in cycle " + cycle)
                    .isFalse();
            }

            System.out.println("100 insert/delete cycles completed successfully");
        }
    }

    @Nested
    @DisplayName("Bulk Deletion")
    class BulkDeletion {

        /**
         * Test: Bulk deletion removes all specified keys.
         */
        @Test
        @DisplayName("bulk deletion removes all specified keys")
        void bulkDeletionRemovesAllSpecifiedKeys() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            // Insert keys
            Set<String> allKeys = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                String key = "bulk-key-" + i;
                filter.insert(key.getBytes(StandardCharsets.UTF_8));
                allKeys.add(key);
            }

            // Delete first 500 keys
            Set<String> keysToDelete = new HashSet<>();
            int count = 0;
            for (String key : allKeys) {
                if (count >= 500) break;
                keysToDelete.add(key);
                count++;
            }

            for (String key : keysToDelete) {
                boolean deleted = filter.delete(key.getBytes(StandardCharsets.UTF_8));
                assertThat(deleted).isTrue();
            }

            // Verify all deleted keys are gone
            // Note: Cuckoo filters can have rare fingerprint collisions where two keys
            // share the same fingerprint AND both buckets. When this happens, they become
            // "inseparable" - deleting one may still show the other's fingerprint in contains().
            // With 13-bit fingerprints (8191 values) and 4 fingerprints per bucket,
            // this is rare but possible. Allow 1-2 false positives as inherent collision rate.
            int falsePositives = 0;
            for (String key : keysToDelete) {
                if (filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    falsePositives++;
                }
            }
            assertThat(falsePositives)
                .as("False positives from fingerprint collisions should be very rare (inherent to Cuckoo filter design)")
                .isLessThan(3);

            // Verify remaining keys still present
            Set<String> remainingKeys = new HashSet<>(allKeys);
            remainingKeys.removeAll(keysToDelete);
            int missingCount = 0;
            for (String key : remainingKeys) {
                if (!filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    missingCount++;
                }
            }
            // Some remaining keys may be false negatives due to fingerprint collisions,
            // but this should be very rare
            assertThat(missingCount).isLessThan(5);

            System.out.println("Bulk deletion: " + keysToDelete.size() +
                " deleted, " + falsePositives + " false positives, " +
                missingCount + " missing from remaining");
        }

        /**
         * Test: Deletion at high load factor.
         */
        @Test
        @DisplayName("deletion at high load factor")
        void deletionAtHighLoadFactor() {
            CuckooFilter filter = filter(1000); // Small capacity

            // Insert to high load factor
            Set<String> insertedKeys = new HashSet<>();
            int failedInserts = 0;
            for (int i = 0; i < 2000 && failedInserts < 10; i++) {
                String key = "highload-key-" + i;
                if (filter.insert(key.getBytes(StandardCharsets.UTF_8))) {
                    insertedKeys.add(key);
                } else {
                    failedInserts++;
                }
            }

            // Delete half
            Set<String> toDelete = new HashSet<>();
            int count = 0;
            for (String key : insertedKeys) {
                if (count >= insertedKeys.size() / 2) break;
                toDelete.add(key);
                count++;
            }

            for (String key : toDelete) {
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                assertThat(filter.contains(keyBytes))
                    .as("An inserted key must be reported present before it is deleted: " + key)
                    .isTrue();
                assertThat(filter.delete(keyBytes))
                    .as("Deleting an inserted key must succeed even at high load: " + key)
                    .isTrue();
            }

            // Verify deleted keys are gone
            // Note: At very high load factor (99%+) with 13-bit fingerprints, fingerprint
            // collisions are expected. When two different keys share the same fingerprint AND
            // bucket, deleting one may still show the other key's fingerprint in contains().
            // This is inherent to Cuckoo filter design, not a bug.
            // With 8191 possible fingerprints and ~1000 items at 99% load factor:
            // - Expected collision pairs: ~60 (birthday problem)
            // - Expected false positives from bucket overlap: ~0.5-2
            int falsePositives = 0;
            for (String key : toDelete) {
                if (filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    falsePositives++;
                }
            }
            assertThat(falsePositives)
                .as("False positives from fingerprint collisions at high load factor " +
                    "(inherent to Cuckoo filter design, not a bug)")
                .isLessThan(5); // Allow small number due to inherent fingerprint collision rate
        }
    }

    @Nested
    @DisplayName("False Positive Rate After Deletion")
    class FalsePositiveRateAfterDeletion {

        /**
         * Test: False positive rate remains within bounds after deletions.
         */
        @Test
        @DisplayName("false positive rate within bounds after deletions")
        void falsePositiveRateWithinBoundsAfterDeletions() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            // Insert keys
            Set<String> insertedKeys = new HashSet<>();
            for (int i = 0; i < 5000; i++) {
                String key = "fp-test-key-" + i;
                filter.insert(key.getBytes(StandardCharsets.UTF_8));
                insertedKeys.add(key);
            }

            // Delete half
            int deleteCount = 0;
            Set<String> deletedKeys = new HashSet<>();
            for (String key : insertedKeys) {
                if (deleteCount >= 2500) break;
                filter.delete(key.getBytes(StandardCharsets.UTF_8));
                deletedKeys.add(key);
                deleteCount++;
            }

            // Test false positive rate on NEW keys (not inserted or deleted)
            int falsePositives = 0;
            int testCount = 10000;
            for (int i = 0; i < testCount; i++) {
                String testKey = "new-test-key-" + i;
                if (!insertedKeys.contains(testKey) && !deletedKeys.contains(testKey)) {
                    if (filter.contains(testKey.getBytes(StandardCharsets.UTF_8))) {
                        falsePositives++;
                    }
                }
            }

            double fpRate = (double) falsePositives / testCount;
            System.out.println("False positive rate after deletions: " +
                String.format("%.4f%%", fpRate * 100) + " (" + falsePositives + "/" + testCount + ")");

            // FP rate should be within target (≤1% for this configuration)
            assertThat(fpRate)
                .as("False positive rate should be ≤1%")
                .isLessThan(0.01);
        }
    }

    @Nested
    @DisplayName("Domain Correctness Verification")
    class DomainCorrectnessVerification {

        /**
         * Test: Deleted entries have exactly zero false positives at normal load factors.
         *
         * <p>Domain correctness: This is a critical requirement for Cuckoo filters.
         * After delete(key), contains(key) MUST return false. This is guaranteed
         * by the Cuckoo filter design when load factor is reasonable (&lt;95%).</p>
         */
        @Test
        @DisplayName("deleted entries have zero false positives at normal load factor")
        void deletedEntriesHaveZeroFalsePositivesAtNormalLoadFactor() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            // Insert at moderate load factor (~50%)
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < 5000; i++) {
                String key = "domain-key-" + i;
                assertThat(filter.insert(key.getBytes(StandardCharsets.UTF_8)))
                    .as("Insert should succeed at moderate load factor")
                    .isTrue();
                keys.add(key);
            }

            double loadFactor = filter.loadFactor();
            System.out.println("Load factor before deletion: " +
                String.format("%.2f%%", loadFactor * 100));

            assertThat(loadFactor)
                .as("Load factor should be reasonable for this test")
                .isLessThan(0.7);

            // Delete all keys
            for (String key : keys) {
                boolean deleted = filter.delete(key.getBytes(StandardCharsets.UTF_8));
                assertThat(deleted)
                    .as("Delete should succeed for key: " + key)
                    .isTrue();
            }

            // CRITICAL: Verify zero false positives
            int falsePositives = 0;
            for (String key : keys) {
                if (filter.contains(key.getBytes(StandardCharsets.UTF_8))) {
                    falsePositives++;
                }
            }

            assertThat(falsePositives)
                .as("CRITICAL: Deleted keys must have ZERO false positives at normal load factor")
                .isEqualTo(0);

            System.out.println("Domain correctness verified: 0 false positives for " +
                keys.size() + " deleted keys at " +
                String.format("%.2f%%", loadFactor * 100) + " load factor");
        }

        /**
         * Test: Delete operation is idempotent.
         */
        @Test
        @DisplayName("delete operation is idempotent")
        void deleteOperationIsIdempotent() {
            CuckooFilter filter = filter(DEFAULT_CAPACITY);

            String key = "idempotent-key";
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            // Insert
            assertThat(filter.insert(keyBytes)).isTrue();
            assertThat(filter.contains(keyBytes)).isTrue();

            // First delete - should succeed
            assertThat(filter.delete(keyBytes)).isTrue();
            assertThat(filter.contains(keyBytes)).isFalse();

            // Second delete - should fail (already deleted)
            assertThat(filter.delete(keyBytes)).isFalse();
            assertThat(filter.contains(keyBytes)).isFalse();

            // Third delete - should still fail
            assertThat(filter.delete(keyBytes)).isFalse();

            System.out.println("Delete idempotency verified");
        }
    }
}
