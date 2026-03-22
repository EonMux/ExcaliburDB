package excalibur.query.pipeline;

import java.util.Objects;

/**
 * Represents the result of comparing lastIntegratedOffset vs writeLogHead for a segment.
 *
 * <p>This class is used by the query pipeline to determine if a tail scan is needed
 * for a candidate segment. The tail scan finds entries that exist in the write log
 * but have not yet been integrated into the HNSW graph.</p>
 *
 * <h2>Backpressure Enforcement</h2>
 * <p>The backpressure limit (default 100K entries) bounds the maximum tail scan size
 * to ensure query latency remains acceptable. Use {@link #exceedsEntryLimit(int)}
 * to check if a segment's tail exceeds a given entry limit.</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>{@code segmentId} must be a positive segment identifier</li>
 *   <li>{@code lastIntegratedOffset} must be non-negative (≥ 0)</li>
 *   <li>{@code writeLogHead} must be non-negative (≥ 0)</li>
 *   <li>{@code writeLogHead} ≥ {@code lastIntegratedOffset} (always)</li>
 *   <li>{@code tailSize} = {@code writeLogHead} - {@code lastIntegratedOffset} (always ≥ 0)</li>
 *   <li>{@code estimatedEntryCount} is an estimate based on average entry size</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and therefore thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class TailScanRequirement {

    /**
     * Default average entry size in bytes for entry count estimation.
     */
    public static final int DEFAULT_AVG_ENTRY_SIZE = 100;

    /**
     * Default backpressure entry limit (100K entries).
     */
    public static final int DEFAULT_ENTRY_LIMIT = 100_000;

    /**
     * Empty tail scan requirement indicating no tail scan is needed.
     */
    private static final TailScanRequirement EMPTY = new TailScanRequirement(-1, 0, 0);

    private final long segmentId;
    private final long lastIntegratedOffset;
    private final long writeLogHead;
    private final boolean tailScanNeeded;
    private final long tailSize;
    private final long estimatedEntryCount;

    /**
     * Creates a new TailScanRequirement.
     *
     * @param segmentId The segment identifier
     * @param lastIntegratedOffset The last integrated offset (≥ 0)
     * @param writeLogHead The write log head offset (≥ 0)
     */
    private TailScanRequirement(long segmentId, long lastIntegratedOffset, long writeLogHead) {
        this(segmentId, lastIntegratedOffset, writeLogHead, DEFAULT_AVG_ENTRY_SIZE);
    }

    /**
     * Creates a new TailScanRequirement with custom average entry size.
     *
     * @param segmentId The segment identifier
     * @param lastIntegratedOffset The last integrated offset (≥ 0)
     * @param writeLogHead The write log head offset (≥ 0)
     * @param avgEntrySize Average entry size for estimation (must be > 0)
     */
    private TailScanRequirement(long segmentId, long lastIntegratedOffset, long writeLogHead, int avgEntrySize) {
        this.segmentId = segmentId;
        this.lastIntegratedOffset = Math.max(0, lastIntegratedOffset);
        this.writeLogHead = Math.max(0, writeLogHead);
        this.tailSize = Math.max(0, this.writeLogHead - this.lastIntegratedOffset);
        this.tailScanNeeded = this.tailSize > 0;
        // Estimate entry count based on tail size and average entry size
        int size = avgEntrySize > 0 ? avgEntrySize : DEFAULT_AVG_ENTRY_SIZE;
        this.estimatedEntryCount = this.tailSize / size;
    }

    /**
     * Creates a TailScanRequirement by comparing offsets for a segment.
     *
     * <p>This is the primary factory method for creating TailScanRequirement instances.
     * It performs the comparison between lastIntegratedOffset and writeLogHead and
     * determines if a tail scan is needed.</p>
     *
     * <h3>Comparison Logic</h3>
     * <ul>
     *   <li>If writeLogHead > lastIntegratedOffset: tail scan needed</li>
     *   <li>If writeLogHead <= lastIntegratedOffset: no tail scan needed</li>
     *   <li>Negative offsets are treated as 0</li>
     * </ul>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Result always has tailSize >= 0</li>
     *   <li>tailSize = writeLogHead - lastIntegratedOffset (clamped to 0)</li>
     *   <li>tailScanNeeded = tailSize > 0</li>
     * </ul>
     *
     * @param segmentId The segment identifier
     * @param lastIntegratedOffset The last offset integrated into HNSW graph
     * @param writeLogHead The current write log head offset
     * @return A TailScanRequirement representing the comparison result
     */
    public static TailScanRequirement compare(long segmentId, long lastIntegratedOffset, long writeLogHead) {
        // Normalize offsets to non-negative values
        long normalizedIntegratedOffset = Math.max(0, lastIntegratedOffset);
        long normalizedWriteLogHead = Math.max(0, writeLogHead);

        // Empty segment or no write log
        if (segmentId < 0) {
            return EMPTY;
        }

        return new TailScanRequirement(segmentId, normalizedIntegratedOffset, normalizedWriteLogHead);
    }

    /**
     * Creates a TailScanRequirement indicating no tail scan is needed.
     *
     * @param segmentId The segment identifier
     * @return A TailScanRequirement with zero tail size
     */
    public static TailScanRequirement noTailScan(long segmentId) {
        return new TailScanRequirement(segmentId, 0, 0);
    }

    /**
     * Returns an empty TailScanRequirement (no segment, no tail scan).
     *
     * @return An empty TailScanRequirement
     */
    public static TailScanRequirement empty() {
        return EMPTY;
    }

    /**
     * Gets the segment identifier.
     *
     * @return The segment ID
     */
    public long segmentId() {
        return segmentId;
    }

    /**
     * Gets the last integrated offset.
     *
     * <p>This is the write log offset up to which all entries have been
     * linked into the HNSW graph.</p>
     *
     * @return The last integrated offset (≥ 0)
     */
    public long lastIntegratedOffset() {
        return lastIntegratedOffset;
    }

    /**
     * Gets the write log head offset.
     *
     * <p>This is the offset of the next byte to be written to the write log.</p>
     *
     * @return The write log head offset (≥ 0)
     */
    public long writeLogHead() {
        return writeLogHead;
    }

    /**
     * Checks if a tail scan is needed for this segment.
     *
     * <p>A tail scan is needed when there are entries in the write log
     * that have not yet been integrated into the HNSW graph.</p>
     *
     * @return true if tail scan is needed, false otherwise
     */
    public boolean isTailScanNeeded() {
        return tailScanNeeded;
    }

    /**
     * Gets the size of the tail that needs to be scanned.
     *
     * <p>This is the difference between writeLogHead and lastIntegratedOffset,
     * representing the number of bytes in the write log that need to be
     * scanned for un-integrated entries.</p>
     *
     * @return The tail size in bytes (≥ 0)
     */
    public long tailSize() {
        return tailSize;
    }

    /**
     * Gets the start offset for the tail scan.
     *
     * <p>This is the same as lastIntegratedOffset. Entries at offsets
     * >= this value and < writeLogHead need to be scanned.</p>
     *
     * @return The start offset for tail scan
     */
    public long tailScanStartOffset() {
        return lastIntegratedOffset;
    }

    /**
     * Gets the end offset for the tail scan (exclusive).
     *
     * <p>This is the same as writeLogHead. Entries at offsets
     * >= lastIntegratedOffset and < this value need to be scanned.</p>
     *
     * @return The end offset for tail scan (exclusive)
     */
    public long tailScanEndOffset() {
        return writeLogHead;
    }

    /**
     * Checks if the tail size exceeds the backpressure limit.
     *
     * <p>The backpressure limit (default 100K entries) bounds the maximum
     * tail scan size to ensure query latency remains acceptable.</p>
     *
     * @param backpressureLimitBytes The maximum allowed tail size in bytes
     * @return true if tail size exceeds the limit
     * @deprecated Use {@link #exceedsEntryLimit(int)} for entry-based limit checking
     */
    @Deprecated
    public boolean exceedsBackpressureLimit(long backpressureLimitBytes) {
        return tailSize > backpressureLimitBytes;
    }

    /**
     * Gets the estimated entry count for this tail scan.
     *
     * <p>This is calculated as tailSize / DEFAULT_AVG_ENTRY_SIZE (100 bytes).
     * Used for backpressure limit checking.</p>
     *
     * @return Estimated entry count (≥ 0)
     */
    public long estimatedEntryCount() {
        return estimatedEntryCount;
    }

    /**
     * Checks if the estimated entry count exceeds the specified limit.
     *
     * <p>This is the primary method for checking if a segment's tail scan
     * may impact query latency due to excessive entries.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>If entryLimit ≤ 0, returns false (no limit enforced)</li>
     *   <li>Comparison uses estimated entry count, not actual count</li>
     * </ul>
     *
     * @param entryLimit The maximum allowed entry count (typically 100K)
     * @return true if estimated entry count exceeds the limit
     */
    public boolean exceedsEntryLimit(int entryLimit) {
        if (entryLimit <= 0) {
            return false;
        }
        return estimatedEntryCount > entryLimit;
    }

    /**
     * Checks if the estimated entry count exceeds the default limit (100K entries).
     *
     * @return true if estimated entry count > 100,000
     */
    public boolean exceedsDefaultEntryLimit() {
        return exceedsEntryLimit(DEFAULT_ENTRY_LIMIT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TailScanRequirement that = (TailScanRequirement) o;
        return segmentId == that.segmentId &&
               lastIntegratedOffset == that.lastIntegratedOffset &&
               writeLogHead == that.writeLogHead;
    }

    @Override
    public int hashCode() {
        return Objects.hash(segmentId, lastIntegratedOffset, writeLogHead);
    }

    @Override
    public String toString() {
        return String.format("TailScanRequirement{segmentId=%d, lastIntegratedOffset=%d, " +
                            "writeLogHead=%d, tailSize=%d, estimatedEntries=%d, tailScanNeeded=%s}",
                            segmentId, lastIntegratedOffset, writeLogHead, tailSize,
                            estimatedEntryCount, tailScanNeeded);
    }
}
