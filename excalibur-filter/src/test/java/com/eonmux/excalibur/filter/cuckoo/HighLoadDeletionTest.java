package com.eonmux.excalibur.filter.cuckoo;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.FalsePositiveModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deletion and displacement at the load factors the benchmarks run at.
 *
 * <p>A cuckoo filter answers membership through fingerprints, so two of its answers about
 * deletion are false positives by construction rather than defects: deleting a key that was
 * never inserted succeeds when a resident entry in one of its two buckets carries the same
 * fingerprint, and a deleted key still reads as present when some other resident entry in one
 * of its buckets does. What separates that from a defect is the rate. A delete that removed the
 * wrong copy, or an alternate index that was not an involution, would lose resident keys
 * outright and push both rates orders of magnitude above the fingerprint collision rate. These
 * tests pin the rates to that collision rate at 95% load, where displacement chains are long
 * and any such defect would surface, and pin the no-false-negative guarantee across the chains
 * that produced them.</p>
 *
 * <p>Every filter is seeded, so each test is a fixed computation: a failure reproduces.</p>
 *
 * @author ExcaliburDB
 */
class HighLoadDeletionTest {

    private static final long SEED = 0x0DDBA11C0FFEEL;

    private static final int FINGERPRINT_BITS = 13;

    private static final double LOAD_FACTOR = 0.95;

    /** Chance a correct filter exceeds a bound below; the seed makes the outcome fixed. */
    private static final double ALPHA = 1e-9;

    @Test
    @DisplayName("Every key inserted to 95% load deletes, and the filter ends empty")
    void everyKeyInsertedAtHighLoadDeletes() {
        CuckooFilter filter = CuckooFilter.create(50_000, FINGERPRINT_BITS, SEED);
        int resident = fill(filter, LOAD_FACTOR);

        for (int i = 0; i < resident; i++) {
            assertThat(filter.delete(key(i)))
                .as("resident key %d must delete: a displaced entry that left its bucket pair "
                    + "would be unreachable here", i)
                .isTrue();
        }

        assertThat(filter.count()).isZero();
        MemorySegment image = snapshot(filter, Arena.ofAuto());
        CuckooFilter reopened = CuckooFilter.readFrom(image, 0);
        assertThat(reopened.count())
            .as("no fingerprint may be left behind in the buckets")
            .isZero();
    }

    @Test
    @DisplayName("Deleting an absent key succeeds exactly when a lookup would be a false positive")
    void absentDeleteSucceedsOnlyOnFingerprintCollision() {
        CuckooFilter filter = CuckooFilter.create(50_000, FINGERPRINT_BITS, SEED);
        int resident = fill(filter, LOAD_FACTOR);
        double fpRate = FalsePositiveModel.expectedRate(filter);

        int probes = 200_000;
        int collisions = 0;
        for (int i = 0; i < probes; i++) {
            byte[] absent = key(1_000_000_000L + i);
            boolean lookupHit = filter.contains(absent);
            boolean deleted = filter.delete(absent);
            assertThat(deleted)
                .as("delete and lookup examine the same two buckets for the same fingerprint")
                .isEqualTo(lookupHit);
            if (deleted) {
                collisions++;
                // The removed entry had this key's fingerprint and sat in one of its buckets,
                // so it shares this key's bucket pair; inserting the key puts back an entry
                // indistinguishable from it.
                assertThat(filter.insert(absent)).isTrue();
            }
        }

        assertThat(collisions)
            .as("absent deletes that succeeded (%d of %d) must stay at the %.4f%% false "
                + "positive rate", collisions, probes, fpRate * 100)
            .isLessThanOrEqualTo(poissonUpperBound(probes * fpRate, ALPHA));
        for (int i = 0; i < resident; i++) {
            assertThat(filter.contains(key(i)))
                .as("restoring a colliding delete must leave resident key %d present", i)
                .isTrue();
        }
    }

    @Test
    @DisplayName("A deleted key reads as present only at the fingerprint collision rate")
    void deletedKeyStaysVisibleOnlyOnCollision() {
        CuckooFilter filter = CuckooFilter.create(50_000, FINGERPRINT_BITS, SEED);
        int resident = fill(filter, LOAD_FACTOR);

        // Another entry with the key's fingerprint in one of its buckets shares its bucket pair
        // exactly, so every such resident key is confined to those two buckets and keeps the
        // deleted key visible: the rate is the lookup false positive rate at this load, not the
        // collision chance of the seven slots left beside the deleted entry, which a correct
        // filter exceeds at this load.
        double rate = FalsePositiveModel.expectedRate(filter);

        int stillVisible = 0;
        for (int i = 0; i < resident; i++) {
            byte[] k = key(i);
            assertThat(filter.delete(k)).isTrue();
            if (filter.contains(k)) {
                stillVisible++;
            }
            assertThat(filter.insert(k)).isTrue();
        }

        assertThat(stillVisible)
            .as("deleted keys still reported (%d of %d)", stillVisible, resident)
            .isLessThanOrEqualTo(poissonUpperBound(resident * rate, ALPHA));
        assertThat(filter.count()).isEqualTo(resident);
    }

    @Test
    @DisplayName("A refused insert rolls every displaced entry back to its exact slot")
    void refusedInsertLeavesTheImageByteIdentical() {
        CuckooFilter filter = CuckooFilter.create(2_000, FINGERPRINT_BITS, SEED);
        long next = 0;
        while (filter.insert(key(next))) {
            next++;
        }
        Arena arena = Arena.ofAuto();

        int refusals = 0;
        for (long candidate = next + 1; refusals < 20 && candidate < next + 10_000; candidate++) {
            MemorySegment before = snapshot(filter, arena);
            long countBefore = filter.count();
            if (filter.insert(key(candidate))) {
                continue;
            }
            refusals++;
            MemorySegment after = snapshot(filter, arena);
            assertThat(after.mismatch(before))
                .as("refused insert %d changed the image", refusals)
                .isEqualTo(-1L);
            assertThat(filter.count()).isEqualTo(countBefore);
        }
        assertThat(refusals).as("the full filter must refuse inserts").isEqualTo(20);
        for (long i = 0; i < next; i++) {
            assertThat(filter.contains(key(i))).isTrue();
        }
    }

    private static int fill(CuckooFilter filter, double loadFactor) {
        int target = (int) (filter.capacity() * loadFactor);
        for (int i = 0; i < target; i++) {
            assertThat(filter.insert(key(i)))
                .as("insert %d of %d toward %.2f load", i, target, loadFactor)
                .isTrue();
        }
        return target;
    }

    private static MemorySegment snapshot(CuckooFilter filter, Arena arena) {
        MemorySegment image = arena.allocate(filter.byteSize());
        filter.writeTo(image, 0);
        return image;
    }

    private static byte[] key(long index) {
        byte[] key = new byte[16];
        key[0] = 'H';
        key[1] = 'L';
        key[2] = 'D';
        for (int i = 0; i < 8; i++) {
            key[8 + i] = (byte) (index >>> (56 - 8 * i));
        }
        return key;
    }

    /**
     * Smallest k with {@code P(X > k) < alpha} for X Poisson with the given mean.
     *
     * @param mean  Expected count
     * @param alpha Allowed tail probability
     * @return The bound
     */
    private static int poissonUpperBound(double mean, double alpha) {
        if (mean <= 0.0) {
            return 0;
        }
        double pk = Math.exp(-mean);
        for (int k = 0; k < 100_000; k++) {
            double pNext = pk * mean / (k + 1);
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
}
