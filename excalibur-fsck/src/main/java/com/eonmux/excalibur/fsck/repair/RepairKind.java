package com.eonmux.excalibur.fsck.repair;

/**
 * The repairs this tool is willing to make.
 *
 * <p>The list is short on purpose. Each of these can be shown to be safe from what the
 * check already established, and none of them destroys anything: a file is moved, not
 * deleted; a catalog row is marked, not cut out; a torn log tail is disclaimed by the same
 * one-byte write the write-ahead log itself uses when an append fails its fsync. Anything
 * that would need a guess about what the data used to be is not here, and is reported as
 * needing an operator instead.</p>
 */
public enum RepairKind {

    /** Move a damaged file aside, keeping every byte of it. */
    QUARANTINE_CORRUPT_FILE,

    /** Move aside an index whose segment is no longer in the directory. */
    QUARANTINE_ORPHANED_INDEX,

    /** Move aside a staged file whose operation never finished. */
    QUARANTINE_STAGING_LEFTOVER,

    /** Mark a catalog row purged because the segment file it names is gone. */
    DROP_CATALOG_RECORD,

    /** Disclaim a write-ahead log's torn tail, the way recovery does. */
    TRIM_TORN_LOG_TAIL
}
