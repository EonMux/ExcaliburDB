package com.eonmux.excalibur.core.datadir;

import java.util.Objects;

/**
 * The entries of a data directory that coordinate the processes using it rather than hold
 * any of its data.
 *
 * <p>The lock file, and a restore's journal and scratch directories. None of them is engine
 * state: a checker has nothing to verify in them, a snapshot must not carry them - an image
 * holding a lock file would hand its record to whatever restores it, and one holding a restore
 * journal would restore a node that refuses to start - and a restore target that holds only
 * them is empty. One predicate, so the tools that walk a data directory agree on which entries
 * those are.</p>
 *
 * <p>Only at the top of the directory. The same names deeper down are not this build's, and
 * are left to be classified like any other file.</p>
 *
 * @author ExcaliburDB
 */
public final class ReservedNames {

    private ReservedNames() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether a path inside a data directory is coordination rather than data.
     *
     * @param relativePath The path relative to the data directory, "/"-separated
     * @return true for the lock file, a restore journal, and anything under a restore's scratch
     *         directories
     */
    public static boolean isReserved(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        int slash = relativePath.indexOf('/');
        if (slash < 0) {
            return relativePath.equals(DataDirectoryLock.FILE_NAME)
                || InterruptedRestore.isRestoreEntry(relativePath);
        }
        String top = relativePath.substring(0, slash);
        return top.startsWith(InterruptedRestore.STAGING_PREFIX)
            || top.startsWith(InterruptedRestore.DISPLACED_PREFIX);
    }
}
