package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.catalog.CatalogFile;

/**
 * Signals that a split was refused because the segment catalog has no row left for a child.
 *
 * <p>The catalog holds at most {@link CatalogFile#MAX_SEGMENTS} rows, one per live segment; a
 * purged segment's row is reused, so the limit counts the segments a store has now rather than
 * every one it has had. A split needs a new row for its right child, and publication moves the
 * children's files into place before it writes their rows, so the split is refused before
 * anything is moved rather than left with moved files and nowhere to record them. The refusal is
 * counted in {@link StorageEngine.Statistics#getRefusedSplits()} and logged once per segment,
 * and a write that needed the space gets this rather than waiting for a split that will not
 * come.</p>
 *
 * <p>A {@link SegmentAtCapacityException}, because to a writer that is what it is: the segment
 * its key routes to is full and cannot be relieved until a purge frees a row.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe, like any exception.</p>
 */
public class CatalogFullException extends SegmentAtCapacityException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new CatalogFullException.
     *
     * @param message Which segment could not split, and the limit it met
     */
    public CatalogFullException(String message) {
        super(message);
    }
}
