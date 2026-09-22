package com.eonmux.excalibur.backup.manifest;

/**
 * Where the epoch recorded in a manifest came from.
 *
 * <p>This engine keeps no durable epoch counter. The number a node is at lives in an
 * {@code AtomicLong} that dies with the process, and on the next open it is recomputed as
 * one past the highest epoch stamped on anything in the data directory. A snapshot reads
 * the same stamps from the same files, so the epoch it records is the epoch a node opening
 * that image would resume from. Which files were available to read it from changes how much
 * the number is worth, so it is recorded alongside it.</p>
 *
 * @author ExcaliburDB
 */
public enum EpochSource {

    /** Read from the segment catalog's creation and tombstone stamps alone. */
    CATALOG_STAMPS,

    /** Read from the catalog's stamps and the lexical index manifest's latest epoch. */
    CATALOG_AND_LEXICAL_STAMPS,

    /**
     * Nothing in the directory carries an epoch stamp yet.
     *
     * <p>A node has written nothing that outlives an epoch: no segment has been tombstoned
     * and no lexical batch has been published. The recorded epoch is zero, which is also
     * where a node opening this image would start.</p>
     */
    NOTHING_STAMPED,

    /**
     * The stamps could not be read.
     *
     * <p>A catalog that will not open is a fact worth recording rather than rounding to
     * zero: an image whose epoch is unknown is one a deep verification should be run over
     * before it is relied on.</p>
     */
    UNREADABLE
}
