package com.eonmux.excalibur.store;

/**
 * Signals that a segment is in the middle of a split and cannot take a mutation now.
 *
 * <p>A split copies the segment's entries into two children from a frozen snapshot, so a write
 * applied to the segment while that runs would be missing from both. The segment refuses it
 * instead, and the refusal is transient: the split either publishes - after which the key
 * belongs to one of the children and the routing layer resolves it again - or is abandoned,
 * after which the segment takes writes as before. {@link StorageEngine}'s write and delete
 * paths wait for one or the other and repeat the operation; only a caller holding a
 * {@link Segment} directly sees this.</p>
 *
 * <p>An {@link IllegalStateException}, as the refusal always was, so a caller that handled it
 * as one still does.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe, like any exception.</p>
 */
public class SegmentSplittingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SegmentSplittingException.
     *
     * @param message What was attempted against the splitting segment
     */
    public SegmentSplittingException(String message) {
        super(message);
    }
}
