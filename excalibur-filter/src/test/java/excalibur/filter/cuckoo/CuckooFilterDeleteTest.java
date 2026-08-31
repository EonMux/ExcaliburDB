package excalibur.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive tests for Cuckoo Filter deletion correctness.
 *
 * <h2>Deletion Correctness Guarantee</h2>
 * <p>The delete operation must satisfy these invariants:
 * <ol>
 *   <li><strong>Exact Match Only</strong>: delete(x) removes ONLY the fingerprint of x,
 *       never a different item's fingerprint</li>
 *   <li><strong>Return False If Not Present</strong>: delete(x) returns false if x was never
 *       inserted or was already deleted</li>
 *   <li><strong>No False Negatives After Delete</strong>: After deleting x, only x becomes
 *       absent; all other items remain present</li>
 *   <li><strong>Idempotent</strong>: Multiple deletes of the same item return false after
 *       the first successful deletion</li>
 * </ol>
 *
 * @author ExcaliburDB
 */
class CuckooFilterDeleteTest {

    /**
     * Fixed hash seed for every filter in this class.
     *
     * <p>A filter created through {@link CuckooFilter#create(long, int)} draws a random seed,
     * and the seed decides each item's fingerprint and bucket pair - so which items end up
     * colliding, and therefore how many deleted items still read as present, changes from
     * run to run. Seeding the filter (which also seeds victim-slot selection) makes these
     * tests a fixed computation rather than a sample: a failure here reproduces on a re-run
     * instead of vanishing.</p>
     */
    private static final long FILTER_SEED = 0x5CA1AB1E5EEDL;

    /**
     * Probability budget for the false-positive bounds below: a correct filter must have
     * less than this chance of exceeding the bound. It is a documentation of the statistical
     * claim - with a fixed seed the outcome is deterministic.
     */
    private static final double FP_BOUND_ALPHA = 1e-9;

    private CuckooFilter filter;

    @BeforeEach
    void setUp() {
        // Create a filter with small capacity to stress test collisions
        filter = CuckooFilter.create(1000, 12, FILTER_SEED);
    }

    /**
     * Largest residual count a correct filter stays within, given an expected count.
     *
     * <p>The residual is a sum of many nearly independent, individually tiny collision
     * indicators, i.e. Poisson with mean {@code mean}. The usual {@code mean + k*sigma}
     * normal bound is invalid here: at a mean well below 1 the distribution is not remotely
     * symmetric, and {@code mean + 4*sqrt(mean)} sits at a tail probability of order 1e-4 -
     * three orders of magnitude looser in failure rate than the 4-sigma it looks like. This
     * returns the smallest k with {@code P(X > k) < alpha} instead, which is correct for any
     * mean, small or large.</p>
     *
     * @param mean  Expected residual count
     * @param alpha Allowed probability of exceeding the returned bound
     * @return Smallest count k such that P(X &gt; k) is below alpha
     */
    private static int poissonUpperBound(double mean, double alpha) {
        if (mean <= 0.0) {
            return 0;
        }
        // P(X = k), starting at k = 0.
        double pk = Math.exp(-mean);
        for (int k = 0; k < 1000; k++) {
            double pNext = pk * mean / (k + 1);
            // For k + 2 > mean the terms decay at least geometrically with ratio
            // mean / (k + 2), so the whole tail is bounded by the first term over
            // (1 - ratio). Computing the tail this way avoids cancelling 1 - CDF.
            if (k + 2 > mean) {
                double ratio = mean / (k + 2);
                if (pNext / (1.0 - ratio) < alpha) {
                    return k;
                }
            }
            pk = pNext;
        }
        throw new IllegalStateException("No Poisson bound converged for mean " + mean);
    }

    // ==================== Basic Delete Operations ====================

    @Test
    @DisplayName("delete() returns true for inserted item")
    void deleteReturnsTrueForInsertedItem() {
        byte[] item = "test-item".getBytes(StandardCharsets.UTF_8);

        assertThat(filter.insert(item)).isTrue();
        assertThat(filter.contains(item)).isTrue();
        assertThat(filter.delete(item)).isTrue();
        assertThat(filter.contains(item)).isFalse();
    }

    @Test
    @DisplayName("delete() returns false for non-existent item")
    void deleteReturnsFalseForNonExistentItem() {
        byte[] item = "never-inserted".getBytes(StandardCharsets.UTF_8);

        // Item was never inserted
        assertThat(filter.delete(item)).isFalse();
    }

    @Test
    @DisplayName("delete() returns false for already deleted item")
    void deleteReturnsFalseForAlreadyDeletedItem() {
        byte[] item = "delete-twice".getBytes(StandardCharsets.UTF_8);

        assertThat(filter.insert(item)).isTrue();
        assertThat(filter.delete(item)).isTrue();
        assertThat(filter.delete(item)).isFalse(); // Second delete should fail
    }

    @Test
    @DisplayName("delete() is idempotent - multiple deletes after insert")
    void deleteIsIdempotent() {
        byte[] item = "idempotent-test".getBytes(StandardCharsets.UTF_8);

        filter.insert(item);

        // First delete succeeds
        assertThat(filter.delete(item)).isTrue();

        // Subsequent deletes all fail
        for (int i = 0; i < 10; i++) {
            assertThat(filter.delete(item))
                .as("Delete attempt " + (i + 2) + " should return false")
                .isFalse();
        }
    }

    // ==================== Deletion Correctness - Exact Fingerprint Matching ====================

    @Test
    @DisplayName("CRITICAL: delete(x) removes only x's fingerprint, not other items in same bucket")
    void deleteOnlyRemovesExactFingerprint() {
        // Create items that may hash to similar buckets
        byte[] itemA = "item-A-unique-content".getBytes(StandardCharsets.UTF_8);
        byte[] itemB = "item-B-different-content".getBytes(StandardCharsets.UTF_8);
        byte[] itemC = "item-C-another-content".getBytes(StandardCharsets.UTF_8);

        // Insert all three items
        assertThat(filter.insert(itemA)).isTrue();
        assertThat(filter.insert(itemB)).isTrue();
        assertThat(filter.insert(itemC)).isTrue();

        // Verify all are present
        assertThat(filter.contains(itemA)).isTrue();
        assertThat(filter.contains(itemB)).isTrue();
        assertThat(filter.contains(itemC)).isTrue();

        // Delete itemB
        assertThat(filter.delete(itemB)).isTrue();

        // CRITICAL ASSERTION: itemA and itemC must still be present
        // This verifies we didn't accidentally remove their fingerprints
        assertThat(filter.contains(itemA))
            .as("itemA should still be present after deleting itemB")
            .isTrue();
        assertThat(filter.contains(itemB))
            .as("itemB should be absent after deletion")
            .isFalse();
        assertThat(filter.contains(itemC))
            .as("itemC should still be present after deleting itemB")
            .isTrue();
    }

    @Test
    @DisplayName("CRITICAL: Sequential deletions maintain correctness")
    void sequentialDeletionsMaintainCorrectness() {
        int numItems = 100;
        List<byte[]> items = new ArrayList<>();

        // Insert all items
        for (int i = 0; i < numItems; i++) {
            byte[] item = ("sequential-item-" + i).getBytes(StandardCharsets.UTF_8);
            items.add(item);
            assertThat(filter.insert(item))
                .as("Insert of item " + i + " should succeed")
                .isTrue();
        }

        // Verify all present (no false negatives allowed)
        for (int i = 0; i < numItems; i++) {
            assertThat(filter.contains(items.get(i)))
                .as("Item " + i + " should be present before deletion")
                .isTrue();
        }

        // Delete every other item
        for (int i = 0; i < numItems; i += 2) {
            assertThat(filter.delete(items.get(i)))
                .as("Delete of item " + i + " should succeed")
                .isTrue();
        }

        // CRITICAL: Verify non-deleted items remain present (no false negatives)
        // This is the key guarantee - deleting one item should never make another item disappear
        for (int i = 1; i < numItems; i += 2) {
            assertThat(filter.contains(items.get(i)))
                .as("Non-deleted item " + i + " MUST still be present (no false negatives)")
                .isTrue();
        }

        // Verify idempotent deletion - deleting again should return false
        for (int i = 0; i < numItems; i += 2) {
            assertThat(filter.delete(items.get(i)))
                .as("Re-delete of item " + i + " should return false")
                .isFalse();
        }

        // Delete remaining items
        for (int i = 1; i < numItems; i += 2) {
            assertThat(filter.delete(items.get(i)))
                .as("Delete of remaining item " + i + " should succeed")
                .isTrue();
        }

        // Verify count is zero after all deletions
        assertThat(filter.count()).isZero();

        // Note: We don't assert that contains() returns false for all items because
        // Cuckoo filters can have false positives. The key guarantees are:
        // 1. delete() returns true/false correctly
        // 2. Non-deleted items remain present (no false negatives)
        // 3. Count is accurate
    }

    /**
     * Stresses deletion under bucket collisions.
     *
     * <p>Two different invariants are checked, and only one of them is absolute. Surviving
     * items MUST all still be reported present - a Cuckoo filter admits no false negatives,
     * and displacement must never lose an item. A deleted item, on the other hand, can still
     * be reported present when a live item happens to carry the same fingerprint in one of
     * its two buckets; that is a false positive, which the structure explicitly allows. The
     * residual is therefore bounded by the filter's own estimated FP rate (a Poisson tail
     * that a correct implementation clears virtually always) rather than asserted to be
     * zero, which the placement of fingerprints makes a coin flip.</p>
     */
    /**
     * Guards the reproducibility the stress tests above depend on.
     *
     * <p>Before the filter took an explicit seed, two runs of the same operations produced
     * different fingerprint layouts and therefore a different number of deleted-but-still-
     * present items, so the stress test's statistical assertion passed or failed by chance.
     * This fails if any per-run randomness (hash seed, victim-slot choice) creeps back in.</p>
     */
    @Test
    @DisplayName("A fixed seed replays insert/delete byte for byte")
    void fixedSeedReplaysTheSameOutcome() {
        int numItems = 500;
        List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < numItems; i++) {
            items.add(("replay-" + String.format("%04d", i)).getBytes(StandardCharsets.UTF_8));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < numItems; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(7));
        Set<Integer> deletedIndices = new HashSet<>(order.subList(0, numItems / 2));

        CuckooFilter first = CuckooFilter.create(1000, 12, FILTER_SEED);
        CuckooFilter second = CuckooFilter.create(1000, 12, FILTER_SEED);

        assertThat(first.seed())
            .as("create() must honour the requested seed")
            .isEqualTo(FILTER_SEED);

        boolean[] firstOutcome = replay(first, items, deletedIndices);
        boolean[] secondOutcome = replay(second, items, deletedIndices);

        assertThat(secondOutcome)
            .as("identical operations on identically seeded filters must give identical lookups")
            .isEqualTo(firstOutcome);
        assertThat(second.count())
            .as("identically seeded filters must hold the same number of fingerprints")
            .isEqualTo(first.count());
    }

    /**
     * Inserts every item, deletes the selected ones, and reports the resulting lookups.
     *
     * @param target          Filter to operate on
     * @param items           Items to insert, in order
     * @param deletedIndices  Indices of the items to delete afterwards
     * @return contains() for every item, in index order
     */
    private static boolean[] replay(CuckooFilter target, List<byte[]> items, Set<Integer> deletedIndices) {
        for (byte[] item : items) {
            assertThat(target.insert(item))
                .as("insert must succeed below the load limit")
                .isTrue();
        }
        for (int idx : deletedIndices) {
            assertThat(target.delete(items.get(idx)))
                .as("delete of item " + idx + " must succeed")
                .isTrue();
        }
        boolean[] outcome = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            outcome[i] = target.contains(items.get(i));
        }
        return outcome;
    }

    @Test
    @DisplayName("CRITICAL: Delete with collision stress test")
    void deleteWithCollisionStressTest() {
        // Create many items to increase bucket collision probability
        int numItems = 500;
        List<byte[]> items = new ArrayList<>();

        for (int i = 0; i < numItems; i++) {
            byte[] item = ("collision-stress-" + String.format("%04d", i)).getBytes(StandardCharsets.UTF_8);
            items.add(item);
            filter.insert(item);
        }

        // Randomly delete half the items
        Random random = new Random(42); // Deterministic seed for reproducibility
        List<Integer> toDelete = new ArrayList<>();
        for (int i = 0; i < numItems; i++) {
            toDelete.add(i);
        }
        Collections.shuffle(toDelete, random);

        int deletedCount = numItems / 2;
        Set<Integer> deletedIndices = new HashSet<>(toDelete.subList(0, deletedCount));

        for (int idx : deletedIndices) {
            assertThat(filter.delete(items.get(idx)))
                .as("Delete of item " + idx + " should succeed")
                .isTrue();
        }

        // Absolute invariant: nothing that was not deleted may go missing.
        for (int i = 0; i < numItems; i++) {
            if (!deletedIndices.contains(i)) {
                assertThat(filter.contains(items.get(i)))
                    .as("Item " + i + " was not deleted and must still be present")
                    .isTrue();
            }
        }

        assertThat(filter.count())
            .as("count must drop by exactly the number of deletions")
            .isEqualTo(numItems - deletedCount);

        // Probabilistic invariant: deleted items are absent except for fingerprint collisions.
        int stillReported = 0;
        for (int idx : deletedIndices) {
            if (filter.contains(items.get(idx))) {
                stillReported++;
            }
        }

        double expected = deletedCount * filter.estimateFpRate();
        int allowed = poissonUpperBound(expected, FP_BOUND_ALPHA);

        assertThat(stillReported)
            .as("deleted items still reported present (%d) must stay within the %.4f%% FP budget "
                    + "(expected %.3f, bound %d at seed 0x%X)",
                stillReported, filter.estimateFpRate() * 100, expected, allowed, filter.seed())
            .isLessThanOrEqualTo(allowed);
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("delete() handles re-insertion after deletion")
    void deleteHandlesReinsertion() {
        byte[] item = "reinsert-test".getBytes(StandardCharsets.UTF_8);

        // Insert, delete, re-insert, delete cycle
        for (int cycle = 0; cycle < 5; cycle++) {
            assertThat(filter.insert(item))
                .as("Insert in cycle " + cycle + " should succeed")
                .isTrue();
            assertThat(filter.contains(item))
                .as("Contains in cycle " + cycle + " should be true after insert")
                .isTrue();
            assertThat(filter.delete(item))
                .as("Delete in cycle " + cycle + " should succeed")
                .isTrue();
            assertThat(filter.contains(item))
                .as("Contains in cycle " + cycle + " should be false after delete")
                .isFalse();
        }
    }

    @Test
    @DisplayName("delete() with single byte items")
    void deleteSingleByteItems() {
        // Single byte items have minimal entropy - good stress test
        for (int b = 0; b < 256; b++) {
            byte[] item = new byte[]{(byte) b};
            filter.insert(item);
        }

        // Delete every other byte
        for (int b = 0; b < 256; b += 2) {
            byte[] item = new byte[]{(byte) b};
            assertThat(filter.delete(item))
                .as("Delete of byte " + b + " should succeed")
                .isTrue();
        }

        // Absolute invariant: the odd bytes were never deleted and must all survive.
        for (int b = 1; b < 256; b += 2) {
            assertThat(filter.contains(new byte[]{(byte) b}))
                .as("Byte " + b + " was not deleted and must still be present")
                .isTrue();
        }

        assertThat(filter.count())
            .as("count must drop by exactly the number of deletions")
            .isEqualTo(128);

        // Probabilistic invariant: a deleted byte can still read as present when a surviving
        // byte carries the same fingerprint in one of its two buckets. That is an ordinary
        // false positive, so bound the residual by the filter's FP budget instead of zero.
        int stillReported = 0;
        for (int b = 0; b < 256; b += 2) {
            if (filter.contains(new byte[]{(byte) b})) {
                stillReported++;
            }
        }

        double expected = 128 * filter.estimateFpRate();
        int allowed = poissonUpperBound(expected, FP_BOUND_ALPHA);

        assertThat(stillReported)
            .as("deleted bytes still reported present (%d) must stay within the %.4f%% FP budget "
                    + "(expected %.3f, bound %d at seed 0x%X)",
                stillReported, filter.estimateFpRate() * 100, expected, allowed, filter.seed())
            .isLessThanOrEqualTo(allowed);
    }

    // ==================== Thread Safety ====================

    @Test
    @DisplayName("Concurrent deletions are thread-safe")
    void concurrentDeletionsAreThreadSafe() throws InterruptedException {
        // Use a separate filter with larger capacity to ensure all insertions succeed
        // The shared filter from setUp() has capacity 1000 which is too small for 1000 items
        // at the target load factor of 0.95
        CuckooFilter concurrentFilter = CuckooFilter.create(2000, 12, FILTER_SEED);

        int numItems = 1000;
        int numThreads = 8;
        List<byte[]> items = new ArrayList<>();

        // Insert all items, tracking successful insertions
        int insertedCount = 0;
        for (int i = 0; i < numItems; i++) {
            byte[] item = ("concurrent-delete-" + i).getBytes(StandardCharsets.UTF_8);
            items.add(item);
            if (concurrentFilter.insert(item)) {
                insertedCount++;
            }
        }

        // Verify all inserted items are present
        for (byte[] item : items) {
            assertThat(concurrentFilter.contains(item)).isTrue();
        }

        // Verify count matches inserted count
        assertThat(concurrentFilter.count()).isEqualTo(insertedCount);

        // Concurrent deletions
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successfulDeletes = new AtomicInteger(0);

        int itemsPerThread = numItems / numThreads;

        for (int t = 0; t < numThreads; t++) {
            final int threadStart = t * itemsPerThread;
            final int threadEnd = (t == numThreads - 1) ? numItems : threadStart + itemsPerThread;

            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = threadStart; i < threadEnd; i++) {
                        if (concurrentFilter.delete(items.get(i))) {
                            successfulDeletes.incrementAndGet();
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
        doneLatch.await();
        executor.shutdown();

        // All items should be deleted
        assertThat(successfulDeletes.get())
            .as("All " + insertedCount + " items should have been deleted")
            .isEqualTo(insertedCount);

        // Verify all are absent
        for (byte[] item : items) {
            assertThat(concurrentFilter.contains(item))
                .as("Item should be absent after concurrent deletion")
                .isFalse();
        }
    }

    // ==================== Memory-Mapped Serialization ====================

    @Test
    @DisplayName("Delete correctness preserved after serialization round-trip")
    void deleteCorrectnessAfterSerialization() {
        // Insert items
        byte[] itemA = "serialize-A".getBytes(StandardCharsets.UTF_8);
        byte[] itemB = "serialize-B".getBytes(StandardCharsets.UTF_8);
        byte[] itemC = "serialize-C".getBytes(StandardCharsets.UTF_8);

        filter.insert(itemA);
        filter.insert(itemB);
        filter.insert(itemC);

        // Serialize to memory segment
        long memorySize = filter.byteSize();
        MemorySegment segment = Arena.ofAuto().allocate(memorySize);
        filter.writeTo(segment, 0);

        // Read back
        CuckooFilter deserialized = CuckooFilter.readFrom(segment, 0);

        // Verify all present
        assertThat(deserialized.contains(itemA)).isTrue();
        assertThat(deserialized.contains(itemB)).isTrue();
        assertThat(deserialized.contains(itemC)).isTrue();

        // Delete from deserialized filter
        assertThat(deserialized.delete(itemB)).isTrue();

        // CRITICAL: A and C must still be present
        assertThat(deserialized.contains(itemA))
            .as("itemA should be present after deleting itemB from deserialized filter")
            .isTrue();
        assertThat(deserialized.contains(itemB)).isFalse();
        assertThat(deserialized.contains(itemC))
            .as("itemC should be present after deleting itemB from deserialized filter")
            .isTrue();
    }

    // ==================== Null Handling ====================

    @Test
    @DisplayName("delete() throws NullPointerException for null item")
    void deleteThrowsOnNull() {
        assertThatThrownBy(() -> filter.delete(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("null");
    }

    // ==================== Statistics ====================

    @Test
    @DisplayName("count decreases after deletion")
    void countDecreasesAfterDeletion() {
        int numItems = 50;

        for (int i = 0; i < numItems; i++) {
            filter.insert(("count-test-" + i).getBytes(StandardCharsets.UTF_8));
        }

        assertThat(filter.count()).isEqualTo(numItems);

        for (int i = 0; i < numItems; i++) {
            filter.delete(("count-test-" + i).getBytes(StandardCharsets.UTF_8));
            assertThat(filter.count())
                .as("Item count after deleting " + (i + 1) + " items")
                .isEqualTo(numItems - i - 1);
        }

        assertThat(filter.count()).isZero();
    }
}
