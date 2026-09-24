package com.eonmux.excalibur.fsck;

/**
 * The process exit codes, which are the tool's contract with a deploy gate.
 *
 * <p>Distinct codes for damage and for not having run matter more here than usual. A
 * pipeline that treats every non-zero the same will block a deploy either way, which is
 * correct; one that distinguishes them can tell "this node's data is damaged" from "the
 * check could not be performed", and those call for different people.</p>
 */
public final class FsckExit {

    /** Nothing damaged was found. */
    public static final int CLEAN = 0;

    /** Something is corrupt, unreadable, missing, or inconsistent between files. */
    public static final int DAMAGE = 1;

    /** The command line was wrong, or the directory is not there. */
    public static final int USAGE = 2;

    /** The check itself failed, so nothing is known about the directory. */
    public static final int FAILED = 3;

    /**
     * A repair was refused because another process - a running node, a restore - holds the
     * data directory, so nothing was checked or changed.
     *
     * <p>Distinct from {@link #FAILED}: nothing is wrong with the tool or the disk, and the
     * response is to stop the holder, not to investigate. A check without {@code --repair}
     * never exits with it; it runs on a held directory and warns.</p>
     */
    public static final int IN_USE = 4;

    private FsckExit() {
        throw new AssertionError("Utility class");
    }
}
