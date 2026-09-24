package com.eonmux.excalibur.backup.snapshot;

/**
 * Where things are inside a snapshot directory.
 *
 * <pre>
 * &lt;snapshot&gt;/
 *   MANIFEST        every file by name, length and checksum, plus the epoch
 *   data/           the payload, under the same relative names the node used
 * </pre>
 *
 * <p>A directory, not an archive. Three reasons: the files being copied are mostly hole,
 * and no common archive format preserves that; a verification has to be able to read one
 * file without unpacking fifty gigabytes to reach it; and an operator can restore by hand
 * from a directory if this tool is the thing that is broken.</p>
 *
 * <p>The payload holds files. A directory appears in it because a file in the image lives
 * there, so a directory that was empty in the source is not recreated - which is right for
 * every directory here, since an absent {@code chunk-index} and an empty one mean the same
 * thing to the store that reads it, and the node creates what it needs on open.</p>
 *
 * <p>The manifest goes down last, by rename. A snapshot directory with no manifest is an
 * interrupted snapshot, and every entry point here says so rather than treating the
 * payload as if it were complete.</p>
 *
 * @author ExcaliburDB
 */
public final class SnapshotLayout {

    /** The subdirectory holding the copied data directory. */
    public static final String PAYLOAD_DIRECTORY = "data";

    /** The version of this layout, recorded in every manifest. */
    public static final String LAYOUT_VERSION = "1";

    private SnapshotLayout() {
    }
}
