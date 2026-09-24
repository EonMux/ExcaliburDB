package com.eonmux.excalibur.core.datadir;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A second process for the lock tests: takes a directory's lock, says so, and holds it until
 * its standard input is closed.
 *
 * <p>The lock is an operating-system lock, and what it has to stop is another process. Within
 * one JVM the class refuses a second claim from its own bookkeeping before the operating system
 * is asked, so only a real second process shows the exclusion the node relies on.</p>
 */
public final class LockHoldingProcess {

    /** Printed once the lock is held. */
    static final String READY = "LOCKED";

    /** What the holder calls itself in the lock file. */
    static final String HOLDER = "lock test holder";

    private LockHoldingProcess() {
    }

    /**
     * Holds the lock on the directory named by the first argument.
     *
     * @param args The directory
     * @throws IOException if the lock cannot be taken
     */
    public static void main(String[] args) throws IOException {
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(Path.of(args[0]), HOLDER)) {
            System.out.println(READY);
            System.out.flush();
            while (System.in.read() >= 0) {
                // Held until the parent closes the pipe.
            }
        }
    }
}
