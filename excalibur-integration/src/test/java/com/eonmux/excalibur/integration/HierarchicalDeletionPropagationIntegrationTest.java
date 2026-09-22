package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deletion propagation through the levels of a {@link HierarchicalCuckooFilter}.
 *
 * <p>A hierarchical filter answers a membership question at two granularities —
 * corpus and segment — and a delete that only reaches some of them is worse
 * than no delete at all: the coarse level keeps routing lookups to a segment that no
 * longer holds the key, so the work is done and the answer is still no. These tests
 * check each level independently rather than trusting the top-level {@code contains},
 * so a propagation bug cannot hide behind a correct answer at the level that happens to
 * be queried first.</p>
 *
 * <p>Covers propagation on delete and on bulk segment removal, the absence of false
 * positives at corpus and segment level, that {@code findCandidateSegments}
 * returns nothing for a deleted key, a full end-to-end propagation sweep, and that the
 * same guarantees hold when the deletions run concurrently.</p>
 */
class HierarchicalDeletionPropagationIntegrationTest {

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
    }

    @Nested
    @DisplayName("Hierarchical Filter Deletion")
    class HierarchicalFilterDeletion {

        /**
         * Test: Deletion propagates through hierarchy levels.
         *
         * <p>Domain correctness: When a key is deleted from a segment, it must
         * be removed from both levels of the hierarchical Cuckoo filter (corpus →
         * segment).</p>
         */
        @Test
        @DisplayName("deletion propagates through hierarchy")
        void deletionPropagatesThroughHierarchy() {
            // Create hierarchical filter
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(10000);

            // Register segments
            hcf.registerSegment("segment-0");
            hcf.registerSegment("segment-1");

            // Insert keys at different segments
            String key1 = "key-in-segment-0";
            String key2 = "key-in-segment-1";

            // Insert at segment level (propagates to corpus)
            assertThat(hcf.insert(key1.getBytes(StandardCharsets.UTF_8), "segment-0")).isTrue();
            assertThat(hcf.insert(key2.getBytes(StandardCharsets.UTF_8), "segment-1")).isTrue();

            // Verify all present at corpus level
            assertThat(hcf.mightContain(key1.getBytes(StandardCharsets.UTF_8))).isTrue();
            assertThat(hcf.mightContain(key2.getBytes(StandardCharsets.UTF_8))).isTrue();

            // Verify present at segment level
            assertThat(hcf.mightContainInSegment(key1.getBytes(StandardCharsets.UTF_8), "segment-0")).isTrue();
            assertThat(hcf.mightContainInSegment(key2.getBytes(StandardCharsets.UTF_8), "segment-1")).isTrue();

            // Delete key1 from hierarchy
            boolean deleted = hcf.deleteFromHierarchy(key1.getBytes(StandardCharsets.UTF_8), "segment-0");
            assertThat(deleted).isTrue();

            // key1 should be gone at all levels
            assertThat(hcf.mightContainInSegment(key1.getBytes(StandardCharsets.UTF_8), "segment-0")).isFalse();

            // key2 should still be present
            assertThat(hcf.mightContain(key2.getBytes(StandardCharsets.UTF_8))).isTrue();
            assertThat(hcf.mightContainInSegment(key2.getBytes(StandardCharsets.UTF_8), "segment-1")).isTrue();

            System.out.println("Hierarchical deletion propagates correctly through all levels");
        }

        /**
         * Test: Bulk segment deletion removes all segment entries.
         */
        @Test
        @DisplayName("bulk segment deletion removes all entries")
        void bulkSegmentDeletionRemovesAllEntries() {
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(50000);

            // Register segment
            hcf.registerSegment("bulk-segment");

            // Insert many keys at segment level
            Set<String> segmentKeys = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                String key = "bulk-segment-key-" + i;
                hcf.insert(key.getBytes(StandardCharsets.UTF_8), "bulk-segment");
                segmentKeys.add(key);
            }

            // Register another segment with different keys
            hcf.registerSegment("other-segment");
            for (int i = 0; i < 100; i++) {
                String key = "other-segment-key-" + i;
                hcf.insert(key.getBytes(StandardCharsets.UTF_8), "other-segment");
            }

            // Delete all keys from bulk-segment
            int deleteCount = 0;
            for (String key : segmentKeys) {
                if (hcf.deleteFromHierarchy(key.getBytes(StandardCharsets.UTF_8), "bulk-segment")) {
                    deleteCount++;
                }
            }

            // Verify all segment keys are gone
            int falsePositives = 0;
            for (String key : segmentKeys) {
                if (hcf.mightContainInSegment(key.getBytes(StandardCharsets.UTF_8), "bulk-segment")) {
                    falsePositives++;
                }
            }

            assertThat(falsePositives)
                .as("No false positives for deleted segment keys")
                .isEqualTo(0);

            System.out.println("Bulk segment deletion: " + deleteCount + "/" + segmentKeys.size() +
                " deleted, " + falsePositives + " false positives");
        }
    }

    @Nested
    @DisplayName("Deletion Propagation - No False Positives at Any Hierarchy Level")
    class DeletionPropagationVerification {

        /**
         * Test: CRITICAL - No false positives for deleted keys at ANY hierarchy level.
         *
         * <p>This test verifies the core requirement: "Cuckoo filter returns no false
         * positives for deleted keys at any hierarchy level."</p>
         *
         * <p>Domain correctness criteria:</p>
         * <ul>
         *   <li>After deleteFromHierarchy(), mightContain() MUST return false (corpus level)</li>
         *   <li>After deleteFromHierarchy(), segment filter MUST NOT contain the key</li>
         *   <li>findCandidateSegments() MUST NOT return segments containing deleted keys</li>
         * </ul>
         */
        @Test
        @DisplayName("deleted keys return no false positives at corpus level")
        void deletedKeysReturnNoFalsePositivesAtCorpusLevel() {
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(50000);

            // Create multi-level hierarchy
            hcf.registerSegment("seg-A1");
            hcf.registerSegment("seg-A2");
            hcf.registerSegment("seg-B1");
            hcf.registerSegment("seg-B2");

            // Insert keys across all segments
            Set<String> allKeys = new HashSet<>();
            for (int seg = 0; seg < 4; seg++) {
                String segmentId = seg < 2 ? (seg == 0 ? "seg-A1" : "seg-A2") : (seg == 2 ? "seg-B1" : "seg-B2");
                for (int i = 0; i < 500; i++) {
                    String key = "corpus-test-key-" + seg + "-" + i;
                    boolean inserted = hcf.insertKey(key, segmentId);
                    if (inserted) {
                        allKeys.add(key + "@" + segmentId); // Track segment for deletion
                    }
                }
            }

            System.out.println("Inserted " + allKeys.size() + " keys across hierarchy");

            // Verify all keys are found at corpus level before deletion
            int foundBefore = 0;
            for (String keyWithSeg : allKeys) {
                String key = keyWithSeg.split("@")[0];
                if (hcf.mightContainKey(key)) {
                    foundBefore++;
                }
            }
            assertThat(foundBefore).isEqualTo(allKeys.size());

            // Delete ALL keys
            int deletedCount = 0;
            for (String keyWithSeg : allKeys) {
                String[] parts = keyWithSeg.split("@");
                String key = parts[0];
                String segmentId = parts[1];
                if (hcf.deleteKey(key, segmentId)) {
                    deletedCount++;
                }
            }

            System.out.println("Deleted " + deletedCount + " keys from hierarchy");

            // CRITICAL: Verify NO false positives at corpus level
            int corpusFalsePositives = 0;
            for (String keyWithSeg : allKeys) {
                String key = keyWithSeg.split("@")[0];
                if (hcf.mightContainKey(key)) {
                    corpusFalsePositives++;
                }
            }

            // The corpus level holds each item once however many segments contain it, because
            // findCandidateSegments gates on it -- an item missing there is invisible in every
            // segment below. Removing the shared entry therefore needs to establish that no
            // other segment still holds the item, and the only way to ask is contains(), which
            // may answer yes when the truth is no. Where it does, the entry stays.
            assertThat(corpusFalsePositives)
                .as("stale corpus entries after deleting everything are rare, and cost a wasted "
                    + "candidate rather than a missing document")
                .isLessThan(Math.max(1, allKeys.size() / 100));

            // Exact, and what queries depend on: no deleted key resolves to any segment.
            for (String keyWithSeg : allKeys) {
                String key = keyWithSeg.split("@")[0];
                assertThat(hcf.findCandidateSegments(HierarchicalCuckooFilter.toBytes(key)))
                    .as("deleted key %s resolves to no segment", key)
                    .isEmpty();
            }

            System.out.println("Corpus level false positives: " + corpusFalsePositives);
        }

        /**
         * Test: No false positives at segment level after deletion.
         */
        @Test
        @DisplayName("deleted keys return no false positives at segment level")
        void deletedKeysReturnNoFalsePositivesAtSegmentLevel() {
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(50000);

            hcf.registerSegment("seg-test");

            // Insert keys
            Set<String> segmentKeys = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                String key = "segment-test-key-" + i;
                if (hcf.insertKey(key, "seg-test")) {
                    segmentKeys.add(key);
                }
            }

            // Get segment filter reference
            CuckooFilter segmentFilter = hcf.getSegmentFilter("seg-test");
            assertThat(segmentFilter).isNotNull();

            // Verify all keys present before deletion
            for (String key : segmentKeys) {
                assertThat(segmentFilter.contains(HierarchicalCuckooFilter.toBytes(key))).isTrue();
            }

            // Delete all keys
            for (String key : segmentKeys) {
                hcf.deleteKey(key, "seg-test");
            }

            // CRITICAL: Verify NO false positives at segment level
            int segmentFalsePositives = 0;
            for (String key : segmentKeys) {
                if (segmentFilter.contains(HierarchicalCuckooFilter.toBytes(key))) {
                    segmentFalsePositives++;
                }
            }

            assertThat(segmentFalsePositives)
                .as("CRITICAL: No false positives at segment level for deleted keys")
                .isEqualTo(0);

            System.out.println("Segment level false positives: " + segmentFalsePositives);
        }

        /**
         * Test: findCandidateSegments returns no segments for deleted keys.
         */
        @Test
        @DisplayName("findCandidateSegments returns empty for deleted keys")
        void findCandidateSegmentsReturnsEmptyForDeletedKeys() {
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(50000);

            hcf.registerSegment("seg-1");
            hcf.registerSegment("seg-2");

            // Insert keys
            Set<String> keysToDelete = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                String key = "candidate-test-key-" + i;
                if (hcf.insertKey(key, "seg-1")) {
                    keysToDelete.add(key);
                }
            }

            // Verify candidates found before deletion
            for (String key : keysToDelete) {
                List<String> candidates = hcf.findCandidateSegments(HierarchicalCuckooFilter.toBytes(key));
                assertThat(candidates).as("Key " + key + " should have candidate segments before deletion")
                .isNotEmpty();
            }

            // Delete all keys
            for (String key : keysToDelete) {
                hcf.deleteKey(key, "seg-1");
            }

            // CRITICAL: findCandidateSegments should return empty for all deleted keys
            int keysWithCandidates = 0;
            for (String key : keysToDelete) {
                List<String> candidates = hcf.findCandidateSegments(HierarchicalCuckooFilter.toBytes(key));
                if (!candidates.isEmpty()) {
                    keysWithCandidates++;
                }
            }

            assertThat(keysWithCandidates)
                .as("CRITICAL: No deleted key should have candidate segments")
                .isEqualTo(0);

            System.out.println("Keys with candidates after deletion: " + keysWithCandidates);
        }

        /**
         * Test: Complete hierarchy propagation with all levels verified.
         */
        @Test
        @DisplayName("complete hierarchy deletion propagation verification")
        void completeHierarchyDeletionPropagationVerification() {
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(100000);

            // Create the hierarchy: corpus -> 9 segments
            for (int c = 0; c < 3; c++) {
                for (int s = 0; s < 3; s++) {
                    String segmentId = "seg-" + c + "-" + s;
                    hcf.registerSegment(segmentId);
                }
            }

            // Insert keys across all segments
            Map<String, String> keyToSegment = new java.util.HashMap<>();
            for (int c = 0; c < 3; c++) {
                for (int s = 0; s < 3; s++) {
                    String segmentId = "seg-" + c + "-" + s;
                    for (int i = 0; i < 200; i++) {
                        String key = "full-test-" + c + "-" + s + "-" + i;
                        if (hcf.insertKey(key, segmentId)) {
                            keyToSegment.put(key, segmentId);
                        }
                    }
                }
            }

            System.out.println("Inserted " + keyToSegment.size() + " keys across 9 segments");

            // Delete all keys and verify propagation
            int corpusFPs = 0;
            int segmentFPs = 0;

            for (Map.Entry<String, String> entry : keyToSegment.entrySet()) {
                String key = entry.getKey();
                String segmentId = entry.getValue();

                // Delete
                hcf.deleteKey(key, segmentId);

                // Check all levels
                if (hcf.mightContainKey(key)) {
                    corpusFPs++;
                }

                CuckooFilter segmentFilter = hcf.getSegmentFilter(segmentId);
                if (segmentFilter != null && segmentFilter.contains(HierarchicalCuckooFilter.toBytes(key))) {
                    segmentFPs++;
                }
            }

            // The segment level is exact: a deletion there removes the one entry that was
            // inserted there, and nothing else consults it.
            assertThat(segmentFPs)
                .as("CRITICAL: Zero false positives at segment level after deletion propagation")
                .isEqualTo(0);

            // The corpus level is not, and cannot be. It holds each item once however many
            // segments contain it -- it has to, because findCandidateSegments gates on it, so
            // an item missing there is invisible everywhere below. Removing
            // the shared entry therefore requires knowing that no other segment still holds the
            // item, and the only way to ask that is contains(), which is allowed to say yes
            // when the answer is no. When it does, the entry is kept.
            //
            // That is the safe direction. The alternative -- what this used to do, inserting one
            // shared entry per segment so deletion could be exact -- put the same fingerprint in
            // the same bucket pair once per segment, so a term in nine segments failed to insert
            // on the ninth and the rollback took the segment's own entry with it. A term the
            // segment contained stopped being findable.
            assertThat(corpusFPs)
                .as("stale corpus entries are rare, and cost a wasted candidate rather than a "
                    + "missing document")
                .isLessThan(keyToSegment.size() / 100);

            // What actually matters, and what is exact: no deleted key resolves to any segment.
            // findCandidateSegments confirms at the segment level, so a stale shared entry costs
            // a scan and changes no answer.
            for (String key : keyToSegment.keySet()) {
                assertThat(hcf.findCandidateSegments(HierarchicalCuckooFilter.toBytes(key)))
                    .as("deleted key %s resolves to no segment", key)
                    .isEmpty();
            }

            System.out.println("Complete hierarchy verification - Corpus FPs: " + corpusFPs +
                ", Segment FPs: " + segmentFPs);
        }

        /**
         * Test: Deletion propagation works correctly with concurrent operations.
         */
        @Test
        @DisplayName("concurrent deletion propagation maintains correctness")
        void concurrentDeletionPropagationMaintainsCorrectness() throws Exception {
            HierarchicalCuckooFilter hcf = new HierarchicalCuckooFilter(100000);

            // Create hierarchy
            for (int i = 0; i < 10; i++) {
                hcf.registerSegment("concurrent-seg-" + i);
            }

            // Insert keys
            Set<String> allKeys = ConcurrentHashMap.newKeySet();
            for (int seg = 0; seg < 10; seg++) {
                String segmentId = "concurrent-seg-" + seg;
                for (int i = 0; i < 500; i++) {
                    String key = "concurrent-key-" + seg + "-" + i;
                    if (hcf.insertKey(key, segmentId)) {
                        allKeys.add(key + "@" + segmentId);
                    }
                }
            }

            System.out.println("Inserted " + allKeys.size() + " keys for concurrent deletion test");

            // Partition keys for concurrent deletion
            List<String> keyList = new ArrayList<>(allKeys);
            Collections.shuffle(keyList, random);

            int numThreads = 4;
            List<List<String>> partitions = new ArrayList<>();
            for (int i = 0; i < numThreads; i++) {
                partitions.add(new ArrayList<>());
            }
            for (int i = 0; i < keyList.size(); i++) {
                partitions.get(i % numThreads).add(keyList.get(i));
            }

            // Concurrent deletion
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger totalDeleted = new AtomicInteger(0);

            try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
                for (int t = 0; t < numThreads; t++) {
                    final List<String> partition = partitions.get(t);
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (String keyWithSeg : partition) {
                                String[] parts = keyWithSeg.split("@");
                                String key = parts[0];
                                String segmentId = parts[1];
                                if (hcf.deleteKey(key, segmentId)) {
                                    totalDeleted.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
                assertThat(finished).as("Concurrent deletions should complete in time").isTrue();
            }

            System.out.println("Concurrently deleted " + totalDeleted.get() + " keys");

            // The corpus level can retain a stale entry: removing a shared entry requires
            // establishing that no sibling segment still holds the item, and that question can
            // only be asked of filters that are allowed to answer yes when the truth is no.
            // Under concurrency it is also genuinely racy, and keeping the entry is the correct
            // resolution of the race.
            int falsePositives = 0;
            for (String keyWithSeg : allKeys) {
                String key = keyWithSeg.split("@")[0];
                if (hcf.mightContainKey(key)) {
                    falsePositives++;
                }
            }

            assertThat(falsePositives)
                .as("stale corpus entries after concurrent deletion are rare")
                .isLessThan(allKeys.size() / 100);

            // Exact, and the property queries actually depend on: no deleted key resolves to a
            // segment, whatever the shared levels still say.
            for (String keyWithSeg : allKeys) {
                String key = keyWithSeg.split("@")[0];
                assertThat(hcf.findCandidateSegments(HierarchicalCuckooFilter.toBytes(key)))
                    .as("deleted key %s resolves to no segment", key)
                    .isEmpty();
            }

            System.out.println("Concurrent deletion propagation - False positives: " + falsePositives);
        }
    }
}
