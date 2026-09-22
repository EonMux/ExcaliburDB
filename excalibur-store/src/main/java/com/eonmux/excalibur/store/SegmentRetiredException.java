package com.eonmux.excalibur.store;

/**
 * Signals that a segment has been replaced by the children of its split.
 *
 * <p>A retired parent is out of the routing directory and its file has been taken over by the
 * left child, so anything written to it afterwards is unreachable and lost at process exit. A
 * caller that resolved the parent just before the split published gets this instead, which says
 * exactly one thing: route again and repeat the operation against whichever child now owns the
 * key.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe, like any exception.</p>
 *
 * @author ExcaliburDB
 */
public class SegmentRetiredException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SegmentRetiredException.
     *
     * @param message What was attempted against the retired segment
     */
    public SegmentRetiredException(String message) {
        super(message);
    }
}
