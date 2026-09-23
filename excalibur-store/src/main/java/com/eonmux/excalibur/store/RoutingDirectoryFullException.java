package com.eonmux.excalibur.store;

/**
 * Signals that a split was refused because it would widen the routing directory past its limit.
 *
 * <p>The directory doubles only when a segment as deep as the directory itself splits, so with
 * keys that hash evenly its width stays a small multiple of the segment count and this is never
 * reached. Reaching it means one lineage of segments keeps splitting without its keys
 * separating - keys whose hashes agree in far more low bits than chance allows - and every
 * further split would double the heap the directory takes while relieving nothing. The split is
 * refused, the refusal is counted in {@link StorageEngine.Statistics#getRefusedSplits()} and
 * logged once per segment, and a write that needed the space gets this rather than waiting for
 * a split that will not come.</p>
 *
 * <p>A {@link SegmentAtCapacityException}, because to a writer that is what it is: the segment
 * its key routes to is full and cannot be relieved. The limit is
 * {@link StorageEngine#withMaxRoutingWidth(int)}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe, like any exception.</p>
 */
public class RoutingDirectoryFullException extends SegmentAtCapacityException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new RoutingDirectoryFullException.
     *
     * @param message Which segment could not split, and the limit it met
     */
    public RoutingDirectoryFullException(String message) {
        super(message);
    }
}
