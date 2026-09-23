package com.eonmux.excalibur.ingest.pipeline;

/**
 * Where one chunk write landed: its write log offset, and the segment that took it.
 *
 * <p>The segment is reported by the write rather than worked out by the caller because only the
 * storage engine knows it. The engine routes a key by its hash and may split the target part-way
 * through the write, so a caller that recomputed the route afterwards, or assumed one, would file
 * the chunk's terms in the membership filter under a segment that does not hold it - and a
 * trusted level missing a term rules its segment out of every query for that term.</p>
 *
 * @param writeLogOffset The write log offset the chunk was written at, or negative if the write
 *                       failed
 * @param segmentId      The catalog index of the segment that took the write, or
 *                       {@link #UNKNOWN_SEGMENT} when the writer cannot say
 */
public record ChunkPlacement(long writeLogOffset, long segmentId) {

    /** The segment of a write whose writer does not route by segment, or that failed. */
    public static final long UNKNOWN_SEGMENT = -1L;

    /** A write that did not happen. */
    public static final ChunkPlacement FAILED = new ChunkPlacement(-1L, UNKNOWN_SEGMENT);

    /**
     * A write reported by offset alone, from a writer with no segments to report.
     *
     * @param writeLogOffset The offset, negative for a failed write
     * @return The placement, or {@link #FAILED} for a negative offset
     */
    public static ChunkPlacement unrouted(long writeLogOffset) {
        return writeLogOffset < 0 ? FAILED : new ChunkPlacement(writeLogOffset, UNKNOWN_SEGMENT);
    }

    /**
     * @return true if the chunk was written
     */
    public boolean written() {
        return writeLogOffset >= 0;
    }

    /**
     * @return true if the write reported the segment that took it
     */
    public boolean segmentKnown() {
        return segmentId >= 0;
    }
}
