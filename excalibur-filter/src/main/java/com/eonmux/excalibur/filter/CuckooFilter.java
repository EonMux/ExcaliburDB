package com.eonmux.excalibur.filter;

import com.eonmux.excalibur.core.hash.XxHash64;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.util.SegmentVarHandle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

/**
 * A Cuckoo Filter implementation supporting O(1) insert, lookup, and delete operations.
 *
 * <h2>Overview</h2>
 * <p>A Cuckoo Filter is a probabilistic data structure that provides set membership testing
 * with configurable false positive rates. Unlike Bloom filters, Cuckoo filters support
 * deletion without false negatives and have lower space overhead for the same FP rate.</p>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li>{@code fingerprintBits}: Number of bits per fingerprint (8-16, default 13)</li>
 *   <li>{@code fingerprintsPerBucket}: Number of fingerprints per bucket (4, cache-line aligned)</li>
 *   <li>{@code numBuckets}: Number of buckets (power of 2)</li>
 *   <li>{@code maxDisplacements}: Maximum displacement attempts (500)</li>
 * </ul>
 *
 * <h2>False Positive Rate Analysis</h2>
 *
 * <h3>Fingerprint Size Selection</h3>
 * <p>The false positive rate depends on fingerprint size (f bits) and load factor:</p>
 * <pre>
 *   FP_rate = 1 - (1 - 2^-f)^(2 * b * load_factor)
 * </pre>
 * <p>where b = fingerprints per bucket (4).</p>
 *
 * <h3>12-bit vs 13-bit Analysis</h3>
 * <p>At 95% load factor with 4 fingerprints per bucket:</p>
 * <ul>
 *   <li><strong>12 bits:</strong> FP ≈ 1 - (1 - 1/4096)^7.6 ≈ <b>0.185%</b> (exceeds 0.1% target)</li>
 *   <li><strong>13 bits:</strong> FP ≈ 1 - (1 - 1/8192)^7.6 ≈ <b>0.093%</b> (meets ≤0.1% target)</li>
 * </ul>
 *
 * <h3>Why 13-bit Default</h3>
 * <p>The spec requires "Target FP rate ≤0.1%". While 12 bits provides ~0.19% FP rate,
 * 13 bits is needed to meet the 0.1% requirement. The formula derivation:</p>
 * <pre>
 *   Per-bucket theoretical FP: 2^-f
 *   - 12 bits: 2^-12 = 1/4096 ≈ 0.024% per fingerprint match
 *   - 13 bits: 2^-13 = 1/8192 ≈ 0.012% per fingerprint match
 *
 *   With 4 fingerprints × 2 buckets = 8 potential matches:
 *   - 12 bits at 95% load: 1 - (1 - 1/4096)^(8 × 0.95) ≈ 0.185%
 *   - 13 bits at 95% load: 1 - (1 - 1/8192)^(8 × 0.95) ≈ 0.093%
 * </pre>
 * <p><b>Conclusion:</b> 13-bit fingerprints are required to achieve ≤0.1% FP rate at load factor 0.95.</p>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * [header (64B)][buckets (numBuckets * 8B)]
 * </pre>
 * <p>Header offsets, all big-endian:</p>
 * <ul>
 *   <li>offset 0: magic (4 bytes) - 0x4355434B ("CUCK")</li>
 *   <li>offset 4: version (4 bytes) - format version</li>
 *   <li>offset 8: fingerprintBits (4 bytes) - bits per fingerprint (8-16)</li>
 *   <li>offset 12: padding (4 bytes), aligning the 8-byte fields that follow</li>
 *   <li>offset 16: numBuckets (8 bytes) - number of buckets</li>
 *   <li>offset 24: seed (8 bytes) - hash seed</li>
 *   <li>offset 32: the integrity block, see {@link CuckooFilterIntegrity}</li>
 *   <li>offset 64: bucket data begins</li>
 * </ul>
 * <p>Bucket storage: every bucket is a fixed 8 bytes, so eight of them fill a cache line and
 * none crosses one, and it holds 4 fingerprints packed at {@code fingerprintBits} each. That is
 * 16 bits of memory per slot whatever the fingerprint width: a narrower fingerprint leaves the
 * rest of the bucket unused rather than making the filter smaller. The width decides the false
 * positive rate, never the memory.</p>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Reads: optimistic and lock-free while no displacement is running. A bucket is read
 *       byte by byte, so a read is not atomic on its own; correctness comes from the
 *       displacement barrier below, not from the width of the read.</li>
 *   <li>Writes: the insert and delete fast paths hold the shared side of the displacement
 *       barrier and one striped lock (bucket index modulo 64) at a time.</li>
 *   <li>Displacement barrier: a cuckoo displacement chain moves a victim out of one bucket
 *       before re-homing it in the next iteration, so mid-chain the victim is in neither of
 *       its two buckets. {@link #displace} therefore runs under the exclusive side of a
 *       {@link StampedLock}; readers validate an optimistic stamp and retry under the shared
 *       side if a displacement overlapped them. This is what makes the no-false-negatives
 *       guarantee hold for concurrent readers. The exclusive side also excludes every other
 *       writer, so the chain itself takes no stripe locks.</li>
 * </ul>
 *
 * <h2>Insertion Graceful Failure Guarantee (CRITICAL)</h2>
 * <p>The {@link #insert(byte[])} method guarantees graceful failure:
 * <ul>
 *   <li><strong>Never Throws Exception</strong>: When the filter is full (maxDisplacements
 *       exceeded), insert returns {@code false} instead of throwing any exception.</li>
 *   <li><strong>Return Value</strong>: Returns {@code true} on success, {@code false} when
 *       the filter cannot accommodate the item due to displacement limit.</li>
 *   <li><strong>Filter State</strong>: After a failed insertion, the filter remains in a
 *       consistent state - all previously inserted items remain accessible.</li>
 *   <li><strong>Recovery</strong>: After deletions free up space, new insertions will
 *       succeed again.</li>
 * </ul>
 * <p><strong>The only exception thrown by insert() is NullPointerException for null input.</strong>
 * All other failure modes return {@code false}.
 *
 * @author ExcaliburDB
 */
public class CuckooFilter {

    // Magic number for serialization validation
    private static final int MAGIC = 0x4355434B; // "CUCK" in ASCII

    // Current version for serialization. Version 2 added the integrity block described on
    // CuckooFilterIntegrity and widened the header from 32 bytes to 64.
    static final int VERSION = 2;

    // The version written before the header carried checksums.
    private static final int VERSION_WITHOUT_CHECKSUM = 1;

    // Header layout (with alignment padding for memory-mapped I/O):
    // offset 0: magic (4 bytes)
    // offset 4: version (4 bytes)
    // offset 8: fingerprintBits (4 bytes)
    // offset 12: padding (4 bytes) - for 8-byte alignment of long fields
    // offset 16: numBuckets (8 bytes)
    // offset 24: seed (8 bytes)
    // offset 32: the integrity block, see CuckooFilterIntegrity
    // Total: 64 bytes
    private static final int HEADER_SIZE = 64;

    // Header size of a version 1 image, which ended where the integrity block now starts.
    private static final int HEADER_SIZE_V1 = 32;

    /** Fewest buckets any filter has, however small its capacity. */
    private static final int MIN_BUCKETS = 4;

    // Header field offsets
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_FINGERPRINT_BITS = 8;
    // offset 12 is padding for alignment
    private static final int OFFSET_NUM_BUCKETS = 16;
    private static final int OFFSET_SEED = 24;

    // Default configuration
    private static final int DEFAULT_FINGERPRINT_BITS = 13;
    private static final int FINGERPRINTS_PER_BUCKET = 4;
    private static final int DEFAULT_MAX_DISPLACEMENTS = 500;

    // 8-byte buckets: eight fill a 64-byte cache line and none crosses one
    private static final int ALIGNED_BYTES_PER_BUCKET = 8;

    // A bucket read as one word. Unaligned because the segment's base address is whatever the
    // caller mapped; the bucket stride keeps each word inside its own bucket regardless.
    private static final ValueLayout.OfLong BUCKET_WORD =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    // Packing of one displacement-path entry: fingerprints are at most 16 bits wide.
    private static final int PATH_SLOT_SHIFT = 16;
    private static final int PATH_FINGERPRINT_MASK = (1 << PATH_SLOT_SHIFT) - 1;

    // Configuration
    private final int fingerprintBits;
    private final int fingerprintMask;
    private final long numBuckets;
    private final long bucketMask;
    private final long seed;
    private final int maxDisplacements;

    // Memory-mapped storage
    private final MemorySegment segment;
    private final long bucketDataOffset;

    // Bytes per bucket: always ALIGNED_BYTES_PER_BUCKET, whatever the fingerprint width
    private final int bytesPerBucket;

    // Entry counter (thread-safe atomic operations)
    private final AtomicLong count;

    // Striped locks for write operations (64 stripes)
    private final ReentrantLock[] locks;

    // Guards the window in which a displaced victim is in neither of its buckets.
    // Held exclusively for a whole displacement chain; readers use optimistic stamps.
    private final StampedLock displacementBarrier = new StampedLock();

    // Victim-slot selection state, seeded from the filter seed. Cuckoo hashing needs an
    // unpredictable kick to break displacement cycles, but it does not need a per-run
    // random one: deriving it from the seed keeps chains reproducible for a given seed.
    // Only ever touched from displaceExclusive, which runs under the exclusive side of
    // the displacement barrier, so no further synchronization is needed.
    private long victimSelectionState;

    // The slot and evicted fingerprint of each step of the running displacement chain, packed
    // as (slot << PATH_SLOT_SHIFT) | fingerprint. Allocated on the first chain and reused:
    // like victimSelectionState it is only touched under the exclusive displacement barrier.
    private int[] displacementPath;

    // Arena owning the off-heap bucket storage, or null when the segment was supplied
    // by the caller. Automatic arenas release their memory once the filter becomes
    // unreachable, which is the only ownership model a class without close() can honour.
    private final Arena arena;

    /**
     * Creates a new CuckooFilter with the specified capacity.
     *
     * @param capacity Expected number of entries
     * @return A new CuckooFilter instance
     */
    public static CuckooFilter create(long capacity) {
        return create(capacity, DEFAULT_FINGERPRINT_BITS);
    }

    /**
     * Creates a new CuckooFilter with the specified capacity and fingerprint bits,
     * using a randomly generated hash seed.
     *
     * @param capacity        Expected number of entries
     * @param fingerprintBits Number of bits per fingerprint (8-16)
     * @return A new CuckooFilter instance
     * @throws IllegalArgumentException if fingerprintBits is not in range [8, 16]
     */
    public static CuckooFilter create(long capacity, int fingerprintBits) {
        return create(capacity, fingerprintBits, ThreadLocalRandom.current().nextLong());
    }

    /**
     * How many buckets a filter created for {@code capacity} entries has.
     *
     * <p>Always a power of two, and never smaller for a larger capacity.</p>
     *
     * @param capacity The entries the filter is sized for
     * @return The bucket count {@link #create} would give it
     */
    public static long bucketsFor(long capacity) {
        long minBuckets = (capacity + FINGERPRINTS_PER_BUCKET - 1) / FINGERPRINTS_PER_BUCKET;
        return nextPowerOfTwo(Math.max(minBuckets * 2, MIN_BUCKETS));
    }

    /**
     * Rounds up to a power of two, which is what the bucket index depends on.
     *
     * <p>Rounding down would silently cancel the doubling above: twice the minimum for a
     * capacity of 1000 is 500 buckets, and the largest power of two at or below 500 is 256 -
     * fewer slots than the doubling is there to provide.</p>
     *
     * @param value The least acceptable number of buckets
     * @return The smallest power of two at or above it, and never below {@link #MIN_BUCKETS}
     */
    private static long nextPowerOfTwo(long value) {
        if (value <= MIN_BUCKETS) {
            return MIN_BUCKETS;
        }
        long highest = Long.highestOneBit(value);
        return highest == value ? value : highest << 1;
    }

    /**
     * Creates a new CuckooFilter with an explicit hash seed.
     *
     * <p>The seed selects the fingerprint and bucket assignment of every item, and also seeds
     * victim-slot selection, so it fixes the displacement chains too. Two filters created with
     * the same seed and given the same sequence of single-threaded operations therefore end
     * up byte-identical, which is what makes a statistical assertion about false positives
     * reproducible instead of a coin flip. Production code should use
     * {@link #create(long, int)} so that each filter gets an independent hash family.</p>
     *
     * @param capacity        Expected number of entries
     * @param fingerprintBits Number of bits per fingerprint (8-16)
     * @param seed            Hash seed
     * @return A new CuckooFilter instance
     * @throws IllegalArgumentException if fingerprintBits is not in range [8, 16]
     */
    public static CuckooFilter create(long capacity, int fingerprintBits, long seed) {
        if (fingerprintBits < 8 || fingerprintBits > 16) {
            throw new IllegalArgumentException(
                "fingerprintBits must be in range [8, 16], got: " + fingerprintBits);
        }

        // Buckets are a power of two, and there are twice as many as the capacity strictly
        // needs. The headroom is not slack: a cuckoo filter refuses inserts well before its
        // slots are full, because an insert that cannot find a free slot displaces an occupant
        // and gives up after a bounded chain of displacements. Sized to exactly the capacity,
        // a filter asked to hold its documented capacity sits near 98% full and starts refusing
        // items - and a membership filter that has refused an item answers "absent" for
        // something it was given.
        long numBuckets = bucketsFor(capacity);

        // Cache-line aligned bucket storage: 8 bytes per bucket
        // This ensures buckets never cross 64-byte cache line boundaries
        // 8 buckets per cache line = optimal cache utilization
        int bytesPerBucket = ALIGNED_BYTES_PER_BUCKET;

        // Total size: header + buckets (cache-line aligned storage)
        long totalSize = HEADER_SIZE + numBuckets * bytesPerBucket;

        // Allocate memory segment.
        // An automatic arena is used deliberately: a CuckooFilter has no owner that could
        // close an explicit arena (a filter is simply dropped by whatever holds it), and
        // Arena.ofShared() memory is never reclaimed by the collector, so every discarded
        // filter would leak its whole bucket region.
        Arena arena = Arena.ofAuto();
        MemorySegment segment = arena.allocate(totalSize);

        // Initialize header (EXACT SPECIFICATION - no padding, no count)
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC, MAGIC);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION, VERSION);
        segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_FINGERPRINT_BITS, fingerprintBits);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_BUCKETS, numBuckets);
        segment.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED, seed);
        CuckooFilterIntegrity.markOpen(segment);

        return new CuckooFilter(segment, arena);
    }

    /**
     * Creates a CuckooFilter from an existing MemorySegment.
     *
     * @param segment The memory segment containing the filter data
     * @throws IllegalArgumentException if the segment is invalid or corrupted
     */
    public CuckooFilter(MemorySegment segment) {
        this(segment, null);
    }

    /**
     * Creates a CuckooFilter over a segment, recording the arena that backs it.
     *
     * @param segment The memory segment containing the filter data
     * @param arena   The arena the segment was allocated from, or null when caller-supplied
     * @throws IllegalArgumentException if the segment is invalid or corrupted
     */
    private CuckooFilter(MemorySegment segment, Arena arena) {
        this.arena = arena;
        if (segment == null) {
            throw new IllegalArgumentException("MemorySegment cannot be null");
        }

        // Validate minimum size
        if (segment.byteSize() < HEADER_SIZE_V1) {
            throw new IllegalArgumentException(
                "Segment too small: " + segment.byteSize() + " < " + HEADER_SIZE_V1);
        }

        // Read and validate header (EXACT SPECIFICATION)
        int magic = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic) +
                ", expected 0x" + Integer.toHexString(MAGIC));
        }

        int version = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_VERSION);
        if (version != VERSION && version != VERSION_WITHOUT_CHECKSUM) {
            throw new IllegalArgumentException(
                "Unsupported version: " + version + ", expected " + VERSION);
        }
        int headerSize = version == VERSION ? HEADER_SIZE : HEADER_SIZE_V1;
        if (segment.byteSize() < headerSize) {
            throw new IllegalArgumentException("Segment too small for a version " + version
                + " header: " + segment.byteSize() + " < " + headerSize);
        }

        this.fingerprintBits = segment.get(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_FINGERPRINT_BITS);
        if (fingerprintBits < 8 || fingerprintBits > 16) {
            throw new IllegalArgumentException(
                "Invalid fingerprintBits in segment: " + fingerprintBits);
        }

        // Read from aligned offsets (with padding for 8-byte alignment)
        this.numBuckets = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_BUCKETS);
        this.seed = segment.get(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_SEED);
        this.victimSelectionState = this.seed;

        // Calculate derived values
        this.fingerprintMask = (1 << fingerprintBits) - 1;
        this.bucketMask = numBuckets - 1;
        this.maxDisplacements = DEFAULT_MAX_DISPLACEMENTS;
        this.bucketDataOffset = headerSize;

        // Use the same aligned stride that create() uses for allocation.
        // The packed size would be (4 * fingerprintBits + 7) / 8 but the allocator
        // uses ALIGNED_BYTES_PER_BUCKET for cache-line alignment. Using the packed
        // size here would address the wrong bucket offsets as bucket index grows.
        this.bytesPerBucket = ALIGNED_BYTES_PER_BUCKET;

        // Validate segment size
        long expectedSize = bucketDataOffset + numBuckets * bytesPerBucket;
        if (segment.byteSize() < expectedSize) {
            throw new IllegalArgumentException(
                "Segment size " + segment.byteSize() + " < expected " + expectedSize);
        }

        // Before a single fingerprint is read out of it, and before the filter starts
        // answering from it.
        long payloadLength = numBuckets * bytesPerBucket;
        CuckooFilterIntegrity.requireIntact(segment, version, bucketDataOffset, payloadLength);

        // The image is about to become a live filter, so whatever checksum it carried no
        // longer describes it. Saying so is what keeps the next read from refusing an image
        // this process legitimately changed.
        if (version == VERSION && !segment.isReadOnly()) {
            CuckooFilterIntegrity.markOpen(segment);
        }

        this.segment = segment;

        // Count is tracked in memory only (not in header per spec)
        // Recount from bucket data and wrap in AtomicLong for thread safety
        this.count = new AtomicLong(recountFromBuckets());

        // Initialize striped locks
        this.locks = new ReentrantLock[64];
        for (int i = 0; i < 64; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Recounts the number of entries by scanning all buckets.
     * Used when loading from a MemorySegment since count is not stored in header.
     *
     * @return The number of non-empty fingerprints
     */
    private long recountFromBuckets() {
        long count = 0;
        for (long bucket = 0; bucket < numBuckets; bucket++) {
            for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
                if (readFingerprintPacked(bucket, slot) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Inserts an item into the filter.
     *
     * <h3>Graceful Failure Guarantee (CRITICAL)</h3>
     * <p>This method NEVER throws an exception when the filter is full. Instead, it returns
     * {@code false} to indicate the insertion failed due to the displacement limit being exceeded.
     *
     * <h4>Failure Modes:</h4>
     * <ul>
     *   <li>{@code false}: Filter is full (maxDisplacements exceeded) - item not inserted</li>
     *   <li>{@link NullPointerException}: Item is null (the ONLY exception thrown)</li>
     * </ul>
     *
     * <h4>Post-Failure Behavior:</h4>
     * <ul>
     *   <li>Filter state remains consistent after failed insertion</li>
     *   <li>All previously inserted items remain accessible</li>
     *   <li>After deletions free space, new insertions will succeed</li>
     * </ul>
     *
     * @param item The item to insert
     * @return {@code true} if insertion succeeded, {@code false} if filter is full
     *         (maxDisplacements exceeded - graceful failure)
     * @throws NullPointerException if item is null (the only exception case)
     */
    public boolean insert(byte[] item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }

        // Compute fingerprint and bucket indices
        long hash = XxHash64.hash(item, seed);
        int fingerprint = computeFingerprint(hash);
        long bucket1 = computeBucket1(hash);
        long bucket2 = computeBucket2(bucket1, fingerprint);

        // Fast path: an empty slot in either bucket. Held under the shared side of the
        // displacement barrier so a concurrent rollback cannot overwrite the slot this
        // insert just claimed. The barrier is released before displacing, because the
        // exclusive side cannot be acquired while this thread holds the shared side.
        long barrierStamp = displacementBarrier.readLock();
        try {
            acquireLock(bucket1);
            try {
                if (tryInsert(bucket1, fingerprint)) {
                    incrementCount();
                    return true;
                }
            } finally {
                releaseLock(bucket1);
            }

            acquireLock(bucket2);
            try {
                if (tryInsert(bucket2, fingerprint)) {
                    incrementCount();
                    return true;
                }
            } finally {
                releaseLock(bucket2);
            }
        } finally {
            displacementBarrier.unlockRead(barrierStamp);
        }

        // Need to displace - use cuckoo hashing
        return displace(bucket1, fingerprint);
    }

    /**
     * Inserts an item only if the filter does not already report it.
     *
     * <p>A Cuckoo filter is a multiset: inserting the same item twice occupies two slots.
     * That is the wrong shape for membership, where the question is only whether a key is
     * present. It is also self-limiting - an item's fingerprint can only live in its two
     * candidate buckets, so the same item can be inserted at most {@code 2 * bucketSize}
     * times before insertion fails outright - and it breaks deletion, which removes one
     * slot per call and so cannot undo a variable number of insertions.
     *
     * <p>The presence check is the filter's own, so a false positive can skip an insertion
     * the filter would otherwise have recorded. Lookups still answer "present" for that
     * item, so the skip is invisible until the colliding item is deleted; callers that
     * delete individual items rather than discarding the whole filter should weigh that
     * against the capacity a multiset costs them.
     *
     * <p>Not atomic: two threads inserting the same absent item can both observe it absent
     * and both insert, which costs a duplicate slot but leaves the filter correct.
     *
     * @param item The item to insert
     * @return {@code true} if the filter holds the item afterwards, {@code false} if it was
     *         absent and could not be inserted because the filter is full
     * @throws NullPointerException if item is null
     */
    public boolean insertIfAbsent(byte[] item) {
        return contains(item) || insert(item);
    }

    /**
     * Checks if an item might be in the filter.
     *
     * @param item The item to check
     * @return true if the item might be present (may be false positive),
     *         false if definitely not present
     * @throws NullPointerException if item is null
     */
    public boolean contains(byte[] item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }

        // Compute fingerprint and bucket indices
        long hash = XxHash64.hash(item, seed);
        int fingerprint = computeFingerprint(hash);
        long bucket1 = computeBucket1(hash);
        long bucket2 = computeBucket2(bucket1, fingerprint);

        // Optimistic read: mid-chain a displaced victim is in neither of its buckets, so a
        // read that overlapped a displacement can miss an item that is definitely present.
        // The stamp detects exactly that overlap; the retry runs under the shared side of the
        // barrier, which no displacement can be inside of.
        long stamp = displacementBarrier.tryOptimisticRead();
        boolean found = containsFingerprint(bucket1, fingerprint) ||
                        containsFingerprint(bucket2, fingerprint);
        if (displacementBarrier.validate(stamp)) {
            return found;
        }

        stamp = displacementBarrier.readLock();
        try {
            return containsFingerprint(bucket1, fingerprint) ||
                   containsFingerprint(bucket2, fingerprint);
        } finally {
            displacementBarrier.unlockRead(stamp);
        }
    }

    /**
     * Deletes an item from the filter.
     *
     * <p>Domain constraint: delete(x) removes the EXACT fingerprint for x,
     * never a different item's fingerprint. Returns false if item not present.</p>
     *
     * @param item The item to delete
     * @return true if the item was found and deleted, false if not found
     * @throws NullPointerException if item is null
     */
    public boolean delete(byte[] item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }

        // Compute fingerprint and bucket indices
        long hash = XxHash64.hash(item, seed);
        int fingerprint = computeFingerprint(hash);
        long bucket1 = computeBucket1(hash);
        long bucket2 = computeBucket2(bucket1, fingerprint);

        // Shared side of the displacement barrier: an item being displaced is momentarily in
        // neither bucket, and a delete that landed in that window would silently leave the
        // entry in the filter.
        long barrierStamp = displacementBarrier.readLock();
        try {
            acquireLock(bucket1);
            try {
                if (tryDelete(bucket1, fingerprint)) {
                    decrementCount();
                    return true;
                }
            } finally {
                releaseLock(bucket1);
            }

            acquireLock(bucket2);
            try {
                if (tryDelete(bucket2, fingerprint)) {
                    decrementCount();
                    return true;
                }
            } finally {
                releaseLock(bucket2);
            }
        } finally {
            displacementBarrier.unlockRead(barrierStamp);
        }

        return false;
    }

    /**
     * Gets the current number of entries in the filter.
     *
     * @return The entry count
     */
    public long count() {
        return count.get();
    }

    /**
     * Gets the capacity (maximum entries at load factor 1.0).
     *
     * @return The capacity
     */
    public long capacity() {
        return numBuckets * FINGERPRINTS_PER_BUCKET;
    }

    /**
     * Gets the current load factor.
     *
     * @return The load factor (0.0 to 1.0)
     */
    public double loadFactor() {
        return (double) count.get() / capacity();
    }

    /**
     * Gets the fingerprint bits configuration.
     *
     * @return The number of fingerprint bits
     */
    public int fingerprintBits() {
        return fingerprintBits;
    }

    /**
     * Gets the hash seed this filter was built with.
     *
     * <p>Passing it back to {@link #create(long, int, long)} reproduces the same fingerprint
     * and bucket assignment, which is how a failure observed on a randomly seeded filter is
     * replayed.</p>
     *
     * @return The hash seed
     */
    public long seed() {
        return seed;
    }

    /**
     * Gets the number of buckets.
     *
     * @return The number of buckets
     */
    public long numBuckets() {
        return numBuckets;
    }

    /**
     * Writes the filter to a MemorySegment at the specified offset.
     *
     * @param target The target memory segment
     * @param offset The offset within the target
     */
    public void writeTo(MemorySegment target, long offset) {
        MemorySegment.copy(segment, 0, target, offset, segment.byteSize());

        // The copy is sealed, not this filter: this filter goes on being mutated, and a
        // checksum over it would be stale before the next insert returns. The image that
        // leaves here is the thing that has to be checkable when it comes back.
        //
        // A filter attached to a version 1 image is copied out as one. Its header ends where
        // the integrity block would start, so stamping one would overwrite the first buckets,
        // and widening the header would mean relaying out every bucket behind it rather than
        // copying. Such an image gains a checksum when the filter is rebuilt.
        if (bucketDataOffset != HEADER_SIZE) {
            return;
        }
        MemorySegment image = target.asSlice(offset, segment.byteSize());
        CuckooFilterIntegrity.seal(image, bucketDataOffset, numBuckets * bytesPerBucket);
    }

    /**
     * Reads a CuckooFilter from a MemorySegment at the specified offset.
     *
     * @param source The source memory segment
     * @param offset The offset within the source
     * @return A new CuckooFilter instance
     */
    public static CuckooFilter readFrom(MemorySegment source, long offset) {
        MemorySegment slice = source.asSlice(offset);
        return new CuckooFilter(slice);
    }

    /**
     * Verifies a serialized filter image without building a filter over it.
     *
     * <p>The checking entry point for this format. Nothing is mutated, so an image that is
     * about to be refused is left exactly as it was found for whatever inspects it next.</p>
     *
     * @param source The memory holding the image
     * @param offset Where the image starts within {@code source}
     * @return What the check concluded, including whether the image carries a checksum at all
     * @throws IllegalArgumentException if there is no readable filter header at that offset
     * @throws NullPointerException if source is null
     */
    public static IntegrityReport verifyIntegrity(MemorySegment source, long offset) {
        if (source == null) {
            throw new NullPointerException("MemorySegment cannot be null");
        }
        MemorySegment image = source.asSlice(offset);
        ValueLayout.OfInt intLayout = ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN);
        if (image.byteSize() < HEADER_SIZE_V1) {
            throw new IllegalArgumentException("Segment too small to hold a filter header: "
                + image.byteSize() + " < " + HEADER_SIZE_V1);
        }
        if (image.get(intLayout, OFFSET_MAGIC) != MAGIC) {
            throw new IllegalArgumentException(
                "No Cuckoo filter image at offset " + offset + " (bad magic number)");
        }
        int version = image.get(intLayout, OFFSET_VERSION);
        int headerSize = version == VERSION ? HEADER_SIZE : HEADER_SIZE_V1;
        long buckets = image.get(
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), OFFSET_NUM_BUCKETS);
        long payload = buckets * ALIGNED_BYTES_PER_BUCKET;
        if (buckets < 0 || payload > image.byteSize() - headerSize) {
            return IntegrityReport.corrupt(null, CuckooFilterIntegrity.FORMAT,
                "the header claims " + buckets + " buckets, which need " + payload
                    + " bytes, but the image holds only " + (image.byteSize() - headerSize));
        }
        return CuckooFilterIntegrity.inspect(image, version, headerSize, payload);
    }

    /**
     * Gets the size in bytes of this filter.
     *
     * @return The size in bytes
     */
    public long byteSize() {
        return segment.byteSize();
    }

    // ==================== Internal Implementation ====================

    /**
     * Computes the fingerprint from a hash value.
     * Uses the upper bits of the hash (different from bucket bits).
     *
     * @param hash The hash value
     * @return The fingerprint (non-zero)
     */
    private int computeFingerprint(long hash) {
        // Use upper bits for fingerprint (different from bucket which uses lower bits)
        int fp = (int) ((hash >>> 32) & fingerprintMask);
        return fp == 0 ? 1 : fp; // 0 is reserved for empty slot
    }

    /**
     * Computes the first bucket index from a hash value.
     * Uses the lower bits of the hash (different from fingerprint bits).
     *
     * @param hash The hash value
     * @return The bucket index
     */
    private long computeBucket1(long hash) {
        // Use lower bits for bucket (different from fingerprint which uses upper bits)
        return hash & bucketMask;
    }

    /**
     * Computes the second bucket index using partial-key cuckoo hashing.
     * bucket2 = bucket1 XOR hash(fingerprint)
     *
     * @param bucket1     The first bucket index
     * @param fingerprint The fingerprint
     * @return The second bucket index
     */
    private long computeBucket2(long bucket1, int fingerprint) {
        // XOR with hash of fingerprint to get alternate bucket
        long fpHash = XxHash64.hash((long) fingerprint, seed);
        return (bucket1 ^ (fpHash & bucketMask)) & bucketMask;
    }

    /**
     * Gets the byte offset for a bucket.
     *
     * @param bucketIndex The bucket index
     * @return The byte offset
     */
    private long getBucketOffset(long bucketIndex) {
        return bucketDataOffset + bucketIndex * bytesPerBucket;
    }

    /**
     * Reads a packed fingerprint from a bucket at the specified slot.
     * Uses bit-level packing: 4 fingerprints of fingerprintBits bits each per bucket.
     *
     * <p>Bit layout within bucket (big-endian):
     * <pre>
     * For 12-bit fingerprints (6 bytes per bucket):
     * FP0: bits 0-11, FP1: bits 12-23, FP2: bits 24-35, FP3: bits 36-47
     * </pre>
     *
     * @param bucketIndex The bucket index
     * @param slot        The slot within the bucket (0-3)
     * @return The fingerprint, or 0 if empty
     */
    private int readFingerprintPacked(long bucketIndex, int slot) {
        long bucketOffset = getBucketOffset(bucketIndex);
        int bitOffset = slot * fingerprintBits;
        int byteOffset = bitOffset / 8;
        int bitPos = bitOffset % 8;

        // Read enough bytes to cover the fingerprint (up to 16 bits for 13-bit fingerprint)
        // Need to read at most 3 bytes to get any fingerprint
        long offset = bucketOffset + byteOffset;

        // Read bytes as big-endian
        int value = 0;
        int bytesToRead = windowBytes(byteOffset, offset);
        for (int i = 0; i < bytesToRead; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset + i);
            value = (value << 8) | (b & 0xFF);
        }
        // If we read fewer bytes, shift to align
        if (bytesToRead < 3) {
            value <<= (3 - bytesToRead) * 8;
        }

        // Extract fingerprint: shift right to align, then mask
        int shift = 24 - bitPos - fingerprintBits; // 24 = 3 bytes * 8 bits
        int fingerprint = (value >>> shift) & fingerprintMask;
        return fingerprint;
    }

    /**
     * Writes a packed fingerprint to a bucket at the specified slot.
     * Uses bit-level packing: 4 fingerprints of fingerprintBits bits each per bucket.
     *
     * <p>This method reads-modify-writes the affected bytes to preserve other fingerprints.
     *
     * @param bucketIndex  The bucket index
     * @param slot         The slot within the bucket (0-3)
     * @param fingerprint The fingerprint to write
     */
    private void writeFingerprintPacked(long bucketIndex, int slot, int fingerprint) {
        long bucketOffset = getBucketOffset(bucketIndex);
        int bitOffset = slot * fingerprintBits;
        int byteOffset = bitOffset / 8;
        int bitPos = bitOffset % 8;

        long offset = bucketOffset + byteOffset;

        // Read the affected bytes (up to 3)
        int value = 0;
        int bytesToRead = windowBytes(byteOffset, offset);
        for (int i = 0; i < bytesToRead; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset + i);
            value = (value << 8) | (b & 0xFF);
        }
        if (bytesToRead < 3) {
            value <<= (3 - bytesToRead) * 8;
        }

        // Clear the fingerprint bits
        int shift = 24 - bitPos - fingerprintBits;
        int clearMask = ~(fingerprintMask << shift);
        value &= clearMask;

        // Set the new fingerprint
        value |= (fingerprint << shift);

        // Write back the bytes
        for (int i = 0; i < bytesToRead; i++) {
            byte b = (byte) (value >>> (16 - i * 8));
            segment.set(ValueLayout.JAVA_BYTE, offset + i, b);
        }
    }

    /**
     * Size of the read-modify-write window for a slot, in bytes.
     *
     * <p>The window is clamped to the bucket. With 16-bit fingerprints slot 3 starts at byte 6
     * of the 8-byte bucket, so an unclamped 3-byte window would reach byte 0 of the NEXT
     * bucket - and the write-back rewrites every byte it read, clobbering that neighbour's
     * slot 0. Neighbouring buckets sit on different lock stripes, so nothing serialises the
     * two writers.</p>
     *
     * @param byteOffset Offset of the window within the bucket
     * @param offset     Absolute offset of the window within the segment
     * @return Number of bytes the window may span (1 to 3)
     */
    private int windowBytes(int byteOffset, long offset) {
        long available = Math.min(bytesPerBucket - byteOffset, segment.byteSize() - offset);
        return (int) Math.min(3L, available);
    }

    /**
     * Reads a fingerprint from a bucket at the specified slot.
     * Delegates to packed implementation.
     *
     * @param bucketIndex The bucket index
     * @param slot        The slot within the bucket (0-3)
     * @return The fingerprint, or 0 if empty
     */
    private int readFingerprint(long bucketIndex, int slot) {
        return readFingerprintPacked(bucketIndex, slot);
    }

    /**
     * Writes a fingerprint to a bucket at the specified slot.
     * Delegates to packed implementation.
     *
     * @param bucketIndex  The bucket index
     * @param slot         The slot within the bucket (0-3)
     * @param fingerprint The fingerprint to write
     */
    private void writeFingerprint(long bucketIndex, int slot, int fingerprint) {
        writeFingerprintPacked(bucketIndex, slot, fingerprint);
    }

    /**
     * Tries to insert a fingerprint into a bucket.
     *
     * @param bucketIndex  The bucket index
     * @param fingerprint The fingerprint to insert
     * @return true if inserted, false if bucket is full
     */
    private boolean tryInsert(long bucketIndex, int fingerprint) {
        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            if (readFingerprint(bucketIndex, slot) == 0) {
                writeFingerprint(bucketIndex, slot, fingerprint);
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a fingerprint exists in a bucket, without taking a lock.
     *
     * <h3>Lock-Free Implementation</h3>
     * <p>The bucket is read through {@link SegmentVarHandle}, which assembles the value from
     * byte-wide volatile reads. That gives visibility (the reader sees writes published by
     * another thread) but NOT single-copy atomicity: a bucket read can observe a mix of bytes
     * from before and after a concurrent write.</p>
     *
     * <h3>Why a mixed read is safe here</h3>
     * <p>Writers only ever change one slot's bits at a time under the bucket's stripe lock, so
     * a mixed read can misreport the slot being written - never one of the other three. The
     * only way an item can vanish from both of its buckets is a displacement chain, and that
     * is excluded by the barrier {@link #contains(byte[])} validates against.</p>
     *
     * @param bucketIndex  The bucket index
     * @param fingerprint The fingerprint to find
     * @return true if found, false otherwise
     */
    private boolean containsFingerprint(long bucketIndex, int fingerprint) {
        // Reads the bucket's bytes. NOT atomic: buckets are packed at fingerprintBits each and
        // sit at arbitrary byte offsets, and SegmentVarHandle.readLongUnaligned guarantees only
        // per-byte atomicity there. Correctness comes from the displacement barrier in contains(),
        // which is what detects a read that overlapped a displacement. Do not remove that barrier
        // on the strength of the word "volatile" below.
        long bucketOffset = getBucketOffset(bucketIndex);

        // Calculate how many bytes we can safely read
        long remainingBytes = segment.byteSize() - bucketOffset;
        int bytesToRead = (int) Math.min(8, remainingBytes);

        long bucketValue;
        if (bytesToRead >= 8) {
            bucketValue = SegmentVarHandle.readLongUnaligned(segment, bucketOffset, 8);
        } else if (bytesToRead > 0) {
            // Last bucket near the end of the segment: read only what is there.
            bucketValue = SegmentVarHandle.readLongUnaligned(segment, bucketOffset, bytesToRead);
        } else {
            // Edge case: no bytes to read
            return false;
        }

        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            int slotFp = extractFingerprintFromLong(bucketValue, slot);
            if (slotFp == fingerprint) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts a fingerprint from a bucket value read by {@link #containsFingerprint}.
     *
     * <p>Bit layout within the 8-byte value (for packed fingerprints, big-endian):</p>
     * <pre>
     * When reading 8 bytes from a packed bucket:
     * - Bucket data occupies the LOW bits of the bucket (not the 8-byte read)
     * - For 12-bit fingerprints (48 bits = 6 bytes per bucket):
     *   Bits 63-16: high 48 bits may contain next bucket data
     *   FP0: bits 15-4 (12 bits), FP1: bits 3-0 + bits 63-56 (crosses boundary)
     *   The 8-byte window is treated as containing:
     *   FP0: bits 63-52, FP1: bits 51-40, FP2: bits 39-28, FP3: bits 27-16
     *   (high 16 bits are padding/next bucket)
     *
     * - For 13-bit fingerprints (52 bits = 7 bytes per bucket):
     *   FP0: bits 63-51, FP1: bits 50-38, FP2: bits 37-25, FP3: bits 24-12
     *   (high 12 bits are padding/next bucket)
     *
     * General formula: shift = 64 - (slot + 1) * fingerprintBits
     * </pre>
     *
     * @param bucketValue The 8-byte bucket window (big-endian)
     * @param slot        The slot within the bucket (0-3)
     * @return The fingerprint value (0 if empty)
     */
    private int extractFingerprintFromLong(long bucketValue, int slot) {
        // Fingerprints are packed from high to low bits within the used portion
        // For slot s, the fingerprint occupies bits [(4-s-1)*fpBits + (64-totalBits) ... (4-s)*fpBits + (64-totalBits) - 1]
        // Simplified: shift = 64 - (slot + 1) * fingerprintBits
        int shift = 64 - (slot + 1) * fingerprintBits;

        // Extract fingerprint
        int fingerprint = (int) ((bucketValue >>> shift) & fingerprintMask);
        return fingerprint;
    }

    /**
     * Tries to delete a fingerprint from a bucket.
     * Only deletes the EXACT fingerprint - never deletes a different fingerprint.
     *
     * @param bucketIndex  The bucket index
     * @param fingerprint The fingerprint to delete
     * @return true if deleted, false if not found
     */
    private boolean tryDelete(long bucketIndex, int fingerprint) {
        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            int existing = readFingerprint(bucketIndex, slot);
            if (existing == fingerprint) {
                // Exact match - safe to delete
                writeFingerprint(bucketIndex, slot, 0);
                return true;
            }
        }
        return false;
    }

    /**
     * Performs cuckoo displacement to make room for a new fingerprint.
     *
     * <p>In Cuckoo hashing, each item can go in one of two buckets. When we displace
     * a victim, we must compute the victim's alternate bucket based on its fingerprint,
     * not just alternate between the original two buckets.</p>
     *
     * <h3>Graceful Failure with Rollback (CRITICAL for No False Negatives)</h3>
     * <p>This method NEVER throws an exception. When maxDisplacements is exceeded,
     * it performs a complete rollback of all displacement operations to restore
     * the filter to its original state, then returns {@code false}.
     *
     * <h3>No False Negatives Guarantee</h3>
     * <p>The rollback mechanism ensures that if displacement fails, all victims
     * are restored to their original positions. This guarantees that no existing
     * item is lost due to a failed insertion attempt.
     *
     * <h3>Reader Visibility</h3>
     * <p>Between two iterations the victim is held only in a local variable, so it is in
     * neither of its buckets. The whole chain therefore runs under the exclusive side of the
     * displacement barrier, and readers either validate an optimistic stamp taken outside the
     * chain or retry under the shared side. When the barrier is released the filter is either
     * fully updated or fully rolled back - never mid-chain.
     *
     * @param bucket1     First bucket index for the item; the chain starts here
     * @param fingerprint Fingerprint to insert
     * @return {@code true} if successful, {@code false} if filter is full
     *         (maxDisplacements exceeded - graceful failure with full rollback)
     */
    private boolean displace(long bucket1, int fingerprint) {
        long barrierStamp = displacementBarrier.writeLock();
        try {
            return displaceExclusive(bucket1, fingerprint);
        } finally {
            displacementBarrier.unlockWrite(barrierStamp);
        }
    }

    /**
     * Runs the displacement chain. The caller must hold the exclusive displacement barrier.
     *
     * <h3>No stripe locks</h3>
     * <p>Every other writer - the insert and delete fast paths - holds one side or the other of the displacement barrier while it touches a bucket, so the
     * exclusive side already keeps them all out of every bucket for the whole chain. Taking
     * the stripe locks as well bought nothing and cost two lock round trips per step.</p>
     *
     * <h3>One-step look-ahead</h3>
     * <p>Before kicking a random victim out of a full bucket, the chain checks whether any
     * occupant's alternate bucket has a free slot and, if one has, moves that occupant there
     * and finishes. A random walk only discovers such a slot one kick later - and, just as
     * often, kicks a different occupant into another full bucket instead. At 95% load this
     * shortens the average chain several-fold, and it is the kick count, not the per-kick
     * cost, that dominates an insert near capacity.</p>
     *
     * <h3>Rollback from the victims alone</h3>
     * <p>A step records only the slot it overwrote and the fingerprint it evicted. The
     * alternate index is an involution - {@code alt(alt(b, fp), fp) == b} - so walking the
     * record backwards from the bucket the last victim was headed for recovers every bucket
     * of the chain. The record is kept between chains and reused, since the exclusive barrier
     * means only one chain ever runs at a time; a filter that never fills never allocates it.</p>
     *
     * @param bucket1     Bucket the chain starts from
     * @param fingerprint Fingerprint to insert
     * @return {@code true} if successful, {@code false} after a full rollback
     */
    private boolean displaceExclusive(long bucket1, int fingerprint) {
        int[] path = displacementPath;
        if (path == null) {
            path = new int[maxDisplacements];
            displacementPath = path;
        }
        int pathLength = 0;

        long currentBucket = bucket1;
        int currentFingerprint = fingerprint;

        for (int i = 0; i < maxDisplacements; i++) {
            long word = readBucket(currentBucket);
            int free = emptySlot(word);
            if (free >= 0) {
                writeFingerprint(currentBucket, free, currentFingerprint);
                incrementCount();
                return true;
            }

            for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
                int occupant = extractFingerprintFromLong(word, slot);
                long alternate = computeBucket2(currentBucket, occupant);
                int alternateFree = emptySlot(readBucket(alternate));
                if (alternateFree >= 0) {
                    writeFingerprint(alternate, alternateFree, occupant);
                    writeFingerprint(currentBucket, slot, currentFingerprint);
                    incrementCount();
                    return true;
                }
            }

            int victimSlot = nextVictimSlot();
            int victimFingerprint = extractFingerprintFromLong(word, victimSlot);
            path[pathLength++] = (victimSlot << PATH_SLOT_SHIFT) | victimFingerprint;
            writeFingerprint(currentBucket, victimSlot, currentFingerprint);
            currentBucket = computeBucket2(currentBucket, victimFingerprint);
            currentFingerprint = victimFingerprint;
        }

        // Max displacements exceeded: put every victim back where it was, newest first, so a
        // slot the chain overwrote more than once ends holding its original occupant.
        long bucket = currentBucket;
        for (int i = pathLength - 1; i >= 0; i--) {
            int victim = path[i] & PATH_FINGERPRINT_MASK;
            bucket = computeBucket2(bucket, victim);
            writeFingerprint(bucket, path[i] >>> PATH_SLOT_SHIFT, victim);
        }
        return false;
    }

    /**
     * Reads a whole bucket as one big-endian word, for {@link #extractFingerprintFromLong}.
     *
     * <p>A plain read: only the displacement chain uses it, and the exclusive barrier it holds
     * keeps every writer out.</p>
     *
     * @param bucketIndex The bucket index
     * @return The bucket's eight bytes, first byte most significant
     */
    private long readBucket(long bucketIndex) {
        return segment.get(BUCKET_WORD, getBucketOffset(bucketIndex));
    }

    /**
     * Finds the first empty slot of a bucket word read by {@link #readBucket}.
     *
     * @param word The bucket word
     * @return The slot index, or -1 when all slots are occupied
     */
    private int emptySlot(long word) {
        for (int slot = 0; slot < FINGERPRINTS_PER_BUCKET; slot++) {
            if (extractFingerprintFromLong(word, slot) == 0) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Picks the slot whose fingerprint is kicked out next.
     *
     * <p>Advances a SplitMix64 state seeded from the filter seed. The caller must hold the
     * exclusive displacement barrier, which serialises every chain and publishes the state
     * update to the next chain.</p>
     *
     * @return A slot index in [0, FINGERPRINTS_PER_BUCKET)
     */
    private int nextVictimSlot() {
        long z = (victimSelectionState += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) ((z >>> 1) % FINGERPRINTS_PER_BUCKET);
    }

    /**
     * Maps a bucket to its lock stripe.
     *
     * @param bucketIndex The bucket index
     * @return The stripe index in [0, 64)
     */
    private static int stripeOf(long bucketIndex) {
        return (int) (bucketIndex % 64);
    }

    /**
     * Acquires the striped lock for a bucket.
     *
     * @param bucketIndex The bucket index
     */
    private void acquireLock(long bucketIndex) {
        locks[stripeOf(bucketIndex)].lock();
    }

    /**
     * Releases the striped lock for a bucket.
     *
     * @param bucketIndex The bucket index
     */
    private void releaseLock(long bucketIndex) {
        locks[stripeOf(bucketIndex)].unlock();
    }

    /**
     * Atomically increments the entry count.
     * Count is tracked in memory only (not in header per memory layout spec).
     */
    private void incrementCount() {
        count.incrementAndGet();
    }

    /**
     * Atomically decrements the entry count.
     * Count is tracked in memory only (not in header per memory layout spec).
     */
    private void decrementCount() {
        count.decrementAndGet();
    }

    @Override
    public String toString() {
        return String.format("CuckooFilter[count=%d, capacity=%d, loadFactor=%.4f, fingerprintBits=%d]",
            count.get(), capacity(), loadFactor(), fingerprintBits);
    }
}
