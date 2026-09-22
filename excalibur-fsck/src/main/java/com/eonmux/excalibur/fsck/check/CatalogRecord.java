package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.store.LifecycleState;

/**
 * One row of {@code catalog.dat}, as read back without opening the catalog.
 *
 * @param index     Position of the row in the file
 * @param segmentId The segment the row is about
 * @param fileName  The segment file the row names, or null when the path field was empty
 * @param state     The lifecycle state the row records
 * @param crcValid  Whether the row matched its own CRC
 */
public record CatalogRecord(
    int index,
    long segmentId,
    String fileName,
    LifecycleState state,
    boolean crcValid) {

    /**
     * Reports whether this row still claims a segment file must exist.
     *
     * <p>A purged row names nothing: the catalog marks a removed segment rather than cutting
     * its row out, and the next registration writes over it. Holding a purged row against a
     * missing file would report every properly deleted segment as damage.</p>
     *
     * @return true if the row is live and its file is expected on disk
     */
    public boolean expectsFileOnDisk() {
        return crcValid && state != null && state != LifecycleState.PURGED;
    }
}
