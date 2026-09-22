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

    private FsckExit() {
        throw new AssertionError("Utility class");
    }
}
