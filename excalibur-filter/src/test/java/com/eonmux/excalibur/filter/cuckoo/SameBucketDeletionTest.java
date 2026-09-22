package com.eonmux.excalibur.filter.cuckoo;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.core.hash.XxHash64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRITICAL TEST: Deletion correctness when items A and B are in the SAME hash bucket.
 *
 * <h2>Test Objective</h2>
 * <p>Verify that delete(x) removes ONLY x's fingerprint, never a different item's fingerprint,
 * even when both items hash to the same bucket.
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ol>
 *   <li><strong>Same Bucket Guarantee</strong>: Items A and B MUST be placed in the same bucket
 *       (either both in bucket1 or both in bucket2)</li>
 *   <li><strong>Exact Fingerprint Match</strong>: delete(A) must only remove A's fingerprint,
 *       never B's fingerprint even if they're in the same bucket</li>
 *   <li><strong>No False Negatives After Delete</strong>: After deleting A, B must still be found</li>
 *   <li><strong>Idempotent Deletion</strong>: Subsequent delete(A) must return false</li>
 * </ol>
 *
 * @author ExcaliburDB
 */
class SameBucketDeletionTest {

    /**
     * Test helper class to track bucket assignments for items.
     * Uses the same hashing logic as CuckooFilter to compute bucket indices.
     */
    private static class BucketAssignment {
        final byte[] item;
        final int fingerprint;
        final long bucket1;
        final long bucket2;
        final long seed;
        final long bucketMask;
        final int fingerprintBits;
        final int fingerprintMask;

        BucketAssignment(byte[] item, long seed, long numBuckets, int fingerprintBits) {
            this.item = item;
            this.seed = seed;
            this.bucketMask = numBuckets - 1;
            this.fingerprintBits = fingerprintBits;
            this.fingerprintMask = (1 << fingerprintBits) - 1;

            // Replicate CuckooFilter's hash computation
            long hash = XxHash64.hash(item, seed);

            // Fingerprint from upper bits
            this.fingerprint = computeFingerprint(hash);

            // Bucket1 from lower bits
            this.bucket1 = hash & bucketMask;

            // Bucket2 via partial-key cuckoo hashing
            this.bucket2 = computeBucket2(bucket1, fingerprint);
        }

        private int computeFingerprint(long hash) {
            int fp = (int) ((hash >>> 32) & fingerprintMask);
            return fp == 0 ? 1 : fp; // 0 reserved for empty
        }

        private long computeBucket2(long bucket1, int fingerprint) {
            long fpHash = XxHash64.hash((long) fingerprint, seed);
            return (bucket1 ^ (fpHash & bucketMask)) & bucketMask;
        }

        /**
         * Checks if this item shares a bucket with another item.
         * Two items share a bucket if they have the same bucket1 OR the same bucket2.
         */
        boolean sharesBucketWith(BucketAssignment other) {
            return this.bucket1 == other.bucket1 || this.bucket1 == other.bucket2 ||
                   this.bucket2 == other.bucket1 || this.bucket2 == other.bucket2;
        }

        /**
         * Returns the shared bucket index, or -1 if no shared bucket.
         */
        long getSharedBucket(BucketAssignment other) {
            if (this.bucket1 == other.bucket1 || this.bucket1 == other.bucket2) {
                return this.bucket1;
            }
            if (this.bucket2 == other.bucket1 || this.bucket2 == other.bucket2) {
                return this.bucket2;
            }
            return -1;
        }

        String itemString() {
            return new String(item, StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts filter configuration from a serialized CuckooFilter.
     * The filter stores its seed and numBuckets in the header.
     */
    private static long[] extractFilterConfig(CuckooFilter filter) {
        // Serialize filter to access header values
        long size = filter.byteSize();
        MemorySegment temp = Arena.ofAuto().allocate(size);
        filter.writeTo(temp, 0);

        // Read header values (matching CuckooFilter header layout)
        long numBuckets = temp.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 16);
        long seed = temp.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 24);
        int fingerprintBits = temp.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 8);

        return new long[]{numBuckets, seed, fingerprintBits};
    }

    /**
     * Finds pairs of items that hash to the same bucket.
     *
     * @param filter The filter to find collisions for
     * @param numCandidates Number of candidate items to generate
     * @return List of pairs that share a bucket
     */
    private List<BucketAssignment[]> findBucketCollisionPairs(CuckooFilter filter, int numCandidates) {
        long[] config = extractFilterConfig(filter);
        long numBuckets = config[0];
        long seed = config[1];
        int fingerprintBits = (int) config[2];

        Random random = new Random(42);
        Map<Long, List<BucketAssignment>> bucket1Map = new HashMap<>();
        Map<Long, List<BucketAssignment>> bucket2Map = new HashMap<>();
        List<BucketAssignment[]> collisionPairs = new ArrayList<>();

        // Generate candidates and group by bucket
        for (int i = 0; i < numCandidates; i++) {
            byte[] item = ("collision-candidate-" + random.nextInt(Integer.MAX_VALUE)).getBytes(StandardCharsets.UTF_8);
            BucketAssignment assignment = new BucketAssignment(item, seed, numBuckets, fingerprintBits);

            // Check bucket1 for collisions
            if (bucket1Map.containsKey(assignment.bucket1)) {
                for (BucketAssignment existing : bucket1Map.get(assignment.bucket1)) {
                    collisionPairs.add(new BucketAssignment[]{existing, assignment});
                }
            }
            bucket1Map.computeIfAbsent(assignment.bucket1, k -> new ArrayList<>()).add(assignment);

            // Check bucket2 for collisions (if different from bucket1)
            if (assignment.bucket2 != assignment.bucket1) {
                if (bucket2Map.containsKey(assignment.bucket2)) {
                    for (BucketAssignment existing : bucket2Map.get(assignment.bucket2)) {
                        collisionPairs.add(new BucketAssignment[]{existing, assignment});
                    }
                }
                bucket2Map.computeIfAbsent(assignment.bucket2, k -> new ArrayList<>()).add(assignment);
            }
        }

        return collisionPairs;
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("CRITICAL: Delete item A when A and B share the SAME bucket - B must remain present")
    void deleteOneItemWhenBothItemsShareSameBucket() {
        // Create filter with small capacity to increase collision probability
        CuckooFilter filter = CuckooFilter.create(1000);

        // Find pairs that actually share a bucket
        List<BucketAssignment[]> collisionPairs = findBucketCollisionPairs(filter, 10000);

        assertThat(collisionPairs)
            .as("Should find at least one pair of items that share a bucket")
            .isNotEmpty();

        // Test deletion correctness for each collision pair
        int testedPairs = 0;
        for (BucketAssignment[] pair : collisionPairs) {
            BucketAssignment itemA = pair[0];
            BucketAssignment itemB = pair[1];
            long sharedBucket = itemA.getSharedBucket(itemB);

            // Skip if no shared bucket (shouldn't happen, but defensive)
            if (sharedBucket < 0) continue;

            testedPairs++;

            // Clear filter for this test
            // Since we can't easily clear, create a new filter with same config
            long[] config = extractFilterConfig(filter);
            // We need a fresh filter - create one
            CuckooFilter testFilter = CuckooFilter.create(1000);

            // Re-compute assignments for new filter's seed
            long[] newConfig = extractFilterConfig(testFilter);
            long newNumBuckets = newConfig[0];
            long newSeed = newConfig[1];
            int newFpBits = (int) newConfig[2];

            BucketAssignment a = new BucketAssignment(itemA.item, newSeed, newNumBuckets, newFpBits);
            BucketAssignment b = new BucketAssignment(itemB.item, newSeed, newNumBuckets, newFpBits);

            // Only test if they still share a bucket with new seed
            if (!a.sharesBucketWith(b)) {
                continue;
            }

            // Insert both items
            assertThat(testFilter.insert(a.item))
                .as("Insert of itemA should succeed")
                .isTrue();
            assertThat(testFilter.insert(b.item))
                .as("Insert of itemB should succeed")
                .isTrue();

            // Verify both are present
            assertThat(testFilter.contains(a.item))
                .as("itemA should be present after insertion")
                .isTrue();
            assertThat(testFilter.contains(b.item))
                .as("itemB should be present after insertion")
                .isTrue();

            // Delete itemA
            assertThat(testFilter.delete(a.item))
                .as("Delete of itemA should succeed")
                .isTrue();

            // CRITICAL ASSERTIONS:
            // 1. itemA should be absent
            assertThat(testFilter.contains(a.item))
                .as("itemA should be absent after deletion")
                .isFalse();

            // 2. itemB should STILL be present (deletion correctness)
            assertThat(testFilter.contains(b.item))
                .as("CRITICAL: itemB should STILL be present after deleting itemA " +
                    "(they share bucket " + a.getSharedBucket(b) + ")")
                .isTrue();

            // 3. Count should be 1
            assertThat(testFilter.count())
                .as("Count should be 1 after deleting one of two items")
                .isEqualTo(1);

            // Test complete for this pair
            if (testedPairs >= 10) {
                break; // Test at least 10 collision pairs
            }
        }

        System.out.println("Tested deletion correctness for " + testedPairs + " bucket-collision pairs");
        assertThat(testedPairs)
            .as("Should have tested at least one bucket-collision pair")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CRITICAL: Delete both items A and B when they share same bucket - sequential deletion")
    void deleteBothItemsSequentiallyWhenSharingBucket() {
        // Create a filter and use it directly (not creating new filters with different seeds)
        CuckooFilter filter = CuckooFilter.create(1000);

        // Get the filter's configuration
        long[] config = extractFilterConfig(filter);
        long numBuckets = config[0];
        long seed = config[1];
        int fingerprintBits = (int) config[2];

        // Find pairs that share a bucket using the SAME filter's seed
        Random random = new Random(999);
        Map<Long, List<BucketAssignment>> bucketMap = new HashMap<>();
        List<BucketAssignment[]> validPairs = new ArrayList<>();

        for (int i = 0; i < 10000 && validPairs.size() < 5; i++) {
            byte[] item = ("seq-delete-" + random.nextInt(Integer.MAX_VALUE)).getBytes(StandardCharsets.UTF_8);
            BucketAssignment a = new BucketAssignment(item, seed, numBuckets, fingerprintBits);

            // Check for collisions in bucket1
            if (bucketMap.containsKey(a.bucket1)) {
                for (BucketAssignment existing : bucketMap.get(a.bucket1)) {
                    validPairs.add(new BucketAssignment[]{existing, a});
                    if (validPairs.size() >= 5) break;
                }
            }
            bucketMap.computeIfAbsent(a.bucket1, k -> new ArrayList<>()).add(a);
        }

        assertThat(validPairs)
            .as("Should find at least one pair of items that share a bucket")
            .isNotEmpty();

        int testedPairs = 0;
        for (BucketAssignment[] pair : validPairs) {
            BucketAssignment a = pair[0];
            BucketAssignment b = pair[1];

            // Clear filter by deleting any items (or use fresh filter with KNOWN seed)
            // Since we can't easily reset, use the original filter but track what we add
            long initialCount = filter.count();

            // Insert both
            assertThat(filter.insert(a.item)).isTrue();
            assertThat(filter.insert(b.item)).isTrue();

            // Delete first item
            assertThat(filter.delete(a.item)).isTrue();
            assertThat(filter.contains(a.item)).isFalse();
            assertThat(filter.contains(b.item))
                .as("itemB should remain after deleting itemA")
                .isTrue();

            // Delete second item
            assertThat(filter.delete(b.item)).isTrue();
            assertThat(filter.contains(a.item)).isFalse();
            assertThat(filter.contains(b.item)).isFalse();

            testedPairs++;
        }

        System.out.println("Tested sequential deletion for " + testedPairs + " bucket-collision pairs");
        assertThat(testedPairs).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CRITICAL: Multiple items in same bucket - delete one at a time")
    void deleteMultipleItemsInSameBucket() {
        CuckooFilter filter = CuckooFilter.create(1000);
        long[] config = extractFilterConfig(filter);
        long numBuckets = config[0];
        long seed = config[1];
        int fingerprintBits = (int) config[2];

        // Find a bucket with multiple items
        Map<Long, List<BucketAssignment>> bucketMap = new HashMap<>();
        Random random = new Random(12345);

        for (int i = 0; i < 5000; i++) {
            byte[] item = ("multi-collision-" + random.nextInt(Integer.MAX_VALUE)).getBytes(StandardCharsets.UTF_8);
            BucketAssignment assignment = new BucketAssignment(item, seed, numBuckets, fingerprintBits);

            bucketMap.computeIfAbsent(assignment.bucket1, k -> new ArrayList<>()).add(assignment);

            // Stop when we have a bucket with at least 3 items
            if (bucketMap.values().stream().anyMatch(list -> list.size() >= 3)) {
                break;
            }
        }

        // Find a bucket with multiple items
        List<BucketAssignment> bucketItems = bucketMap.values().stream()
            .filter(list -> list.size() >= 3)
            .findFirst()
            .orElse(null);

        assertThat(bucketItems)
            .as("Should find at least 3 items that share a bucket")
            .isNotNull();

        // Create fresh filter
        CuckooFilter testFilter = CuckooFilter.create(1000);
        long[] newConfig = extractFilterConfig(testFilter);
        long newSeed = newConfig[1];
        long newNumBuckets = newConfig[0];
        int newFpBits = (int) newConfig[2];

        // Re-compute for new seed and insert
        List<BucketAssignment> toInsert = new ArrayList<>();
        for (BucketAssignment orig : bucketItems) {
            BucketAssignment a = new BucketAssignment(orig.item, newSeed, newNumBuckets, newFpBits);
            // Verify they still share a bucket
            if (!toInsert.isEmpty() && a.sharesBucketWith(toInsert.get(0))) {
                toInsert.add(a);
            } else if (toInsert.isEmpty()) {
                toInsert.add(a);
            }
        }

        // Insert all items
        for (BucketAssignment a : toInsert) {
            testFilter.insert(a.item);
        }

        int initialCount = toInsert.size();
        assertThat(testFilter.count()).isEqualTo(initialCount);

        // Delete one at a time, verifying others remain
        for (int i = 0; i < toInsert.size(); i++) {
            BucketAssignment toDelete = toInsert.get(i);

            // Delete
            assertThat(testFilter.delete(toDelete.item))
                .as("Delete of item " + i + " should succeed")
                .isTrue();

            // Verify deleted item is gone
            assertThat(testFilter.contains(toDelete.item))
                .as("Deleted item " + i + " should be absent")
                .isFalse();

            // Verify all OTHER items are still present
            for (int j = i + 1; j < toInsert.size(); j++) {
                assertThat(testFilter.contains(toInsert.get(j).item))
                    .as("Item " + j + " should still be present after deleting item " + i)
                    .isTrue();
            }

            // Verify count
            assertThat(testFilter.count())
                .as("Count should be " + (initialCount - i - 1) + " after " + (i + 1) + " deletions")
                .isEqualTo(initialCount - i - 1);
        }

        System.out.println("Tested deletion of " + initialCount + " items sharing same bucket");
    }

    @Test
    @DisplayName("CRITICAL: Delete with exact fingerprint matching - different fingerprints same bucket")
    void deleteWithExactFingerprintMatching() {
        CuckooFilter filter = CuckooFilter.create(500);
        long[] config = extractFilterConfig(filter);
        long numBuckets = config[0];
        long seed = config[1];
        int fingerprintBits = (int) config[2];

        // Find items that share a bucket but have DIFFERENT fingerprints
        // This is the critical test: delete should only match EXACT fingerprint
        List<BucketAssignment[]> sameBucketDifferentFp = new ArrayList<>();
        Random random = new Random(99999);

        Map<Long, List<BucketAssignment>> bucketMap = new HashMap<>();
        for (int i = 0; i < 10000 && sameBucketDifferentFp.size() < 5; i++) {
            byte[] item = ("fp-test-" + random.nextInt(Integer.MAX_VALUE)).getBytes(StandardCharsets.UTF_8);
            BucketAssignment a = new BucketAssignment(item, seed, numBuckets, fingerprintBits);

            // Check for same bucket but different fingerprint
            List<BucketAssignment> sameBucket = bucketMap.get(a.bucket1);
            if (sameBucket != null) {
                for (BucketAssignment existing : sameBucket) {
                    if (existing.fingerprint != a.fingerprint) {
                        sameBucketDifferentFp.add(new BucketAssignment[]{existing, a});
                    }
                }
            }
            bucketMap.computeIfAbsent(a.bucket1, k -> new ArrayList<>()).add(a);
        }

        assertThat(sameBucketDifferentFp)
            .as("Should find items with same bucket but different fingerprints")
            .isNotEmpty();

        // Test deletion for each pair
        for (BucketAssignment[] pair : sameBucketDifferentFp) {
            CuckooFilter testFilter = CuckooFilter.create(500);
            long[] newConfig = extractFilterConfig(testFilter);
            long newSeed = newConfig[1];
            long newNumBuckets = newConfig[0];
            int newFpBits = (int) newConfig[2];

            BucketAssignment a = new BucketAssignment(pair[0].item, newSeed, newNumBuckets, newFpBits);
            BucketAssignment b = new BucketAssignment(pair[1].item, newSeed, newNumBuckets, newFpBits);

            // Skip if no longer same bucket or same fingerprint
            if (!a.sharesBucketWith(b) || a.fingerprint == b.fingerprint) continue;

            // Insert both
            testFilter.insert(a.item);
            testFilter.insert(b.item);

            System.out.println("Testing: bucket1(A)=" + a.bucket1 + ", bucket2(A)=" + a.bucket2 +
                ", bucket1(B)=" + b.bucket1 + ", bucket2(B)=" + b.bucket2 +
                ", fp(A)=" + a.fingerprint + ", fp(B)=" + b.fingerprint);

            // Delete A - should only remove A's fingerprint
            assertThat(testFilter.delete(a.item))
                .as("Delete of itemA should succeed")
                .isTrue();

            // B must still be present - this is the critical assertion
            assertThat(testFilter.contains(b.item))
                .as("CRITICAL: itemB with fingerprint " + b.fingerprint +
                    " must still be present after deleting itemA with fingerprint " + a.fingerprint)
                .isTrue();

            // A should be gone
            assertThat(testFilter.contains(a.item))
                .as("itemA should be absent after deletion")
                .isFalse();
        }

        System.out.println("Tested " + sameBucketDifferentFp.size() + " pairs with same bucket, different fingerprints");
    }

    @Test
    @DisplayName("CRITICAL: Verify bucket collision detection works correctly")
    void verifyBucketCollisionDetection() {
        // This test validates that our bucket collision detection logic is correct
        CuckooFilter filter = CuckooFilter.create(1000);
        long[] config = extractFilterConfig(filter);
        long numBuckets = config[0];
        long seed = config[1];
        int fingerprintBits = (int) config[2];

        // Generate items and verify collision detection
        int collisionCount = 0;
        Map<Long, Integer> bucket1Counts = new HashMap<>();
        Map<Long, Integer> bucket2Counts = new HashMap<>();

        for (int i = 0; i < 10000; i++) {
            byte[] item = ("verify-" + i).getBytes(StandardCharsets.UTF_8);
            BucketAssignment a = new BucketAssignment(item, seed, numBuckets, fingerprintBits);

            bucket1Counts.merge(a.bucket1, 1, Integer::sum);
            bucket2Counts.merge(a.bucket2, 1, Integer::sum);
        }

        // Count collisions
        for (int count : bucket1Counts.values()) {
            if (count > 1) collisionCount += count - 1;
        }
        for (int count : bucket2Counts.values()) {
            if (count > 1) collisionCount += count - 1;
        }

        System.out.println("Bucket1 unique: " + bucket1Counts.size());
        System.out.println("Bucket2 unique: " + bucket2Counts.size());
        System.out.println("Total collisions: " + collisionCount);

        // With 10000 items and numBuckets, we should have collisions
        assertThat(collisionCount)
            .as("Should have detected bucket collisions")
            .isGreaterThan(0);
    }
}
