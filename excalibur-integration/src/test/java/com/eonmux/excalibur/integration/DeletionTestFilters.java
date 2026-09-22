package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.filter.CuckooFilter;

/**
 * Seeded Cuckoo filter fixtures shared by the deletion correctness suites.
 *
 * <h2>Why these tests fix the hash seed</h2>
 * <p>{@link CuckooFilter#create(long)} draws its seed from {@code ThreadLocalRandom}, so each
 * filter gets an independent hash family and a different set of collisions. That is right for
 * production and wrong for a test that asserts how many false positives there are: the assertion
 * then holds for most seeds and fails for the rest, which is a coin flip rather than a check.
 * {@link CuckooFilter#create(long, int, long)} exists for this, and every filter in these suites
 * is built through {@link #filter} so that a failure means the behaviour changed and not that the
 * dice came up differently.</p>
 */
final class DeletionTestFilters {

    static final int DEFAULT_CAPACITY = 10000;

    /** Fixed so a false-positive count is a fact about the filter, not about the seed. */
    static final long HASH_SEED = 0x5EEDC0FFEEL;

    static final int FINGERPRINT_BITS = 13;

    private DeletionTestFilters() {
    }

    /** A filter whose collisions are the same on every run. */
    static CuckooFilter filter(long capacity) {
        return CuckooFilter.create(capacity, FINGERPRINT_BITS, HASH_SEED);
    }
}
