package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A node's exclusive claim on its data directory, taken before anything in it is read.
 *
 * <p>Two nodes on one directory each map the same segments and publish the same catalog, and
 * the directory ends up describing neither; so do a node and an {@code excalibur-fsck
 * --repair} or an {@code excalibur-backup restore}. All of them take the same lock, and the
 * second to arrive is refused with the directory and the holder's pid in the message.</p>
 *
 * <p>A directory holding the journal of a restore that did not finish is refused as well,
 * after the lock is taken so a restore still running is told apart from one that stopped: it
 * would hold the lock. Its files may be any mixture of the old contents and the image, and
 * running the restore again is what completes it.</p>
 */
final class DataDirectoryClaim {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataDirectoryClaim.class);

    private DataDirectoryClaim() {
        throw new AssertionError("Utility class");
    }

    /**
     * Takes the claim.
     *
     * @param dataDir The data directory, which exists
     * @param holder  What the lock file names as holding it
     * @return The claim; the caller decides how long it is held
     * @throws com.eonmux.excalibur.core.datadir.DataDirectoryLockedException if another holder
     *                                                                        has the directory
     * @throws IOException if the lock cannot be taken, or a restore was interrupted there; the
     *                     directory is left unclaimed either way
     */
    static DataDirectoryLock claim(Path dataDir, String holder) throws IOException {
        DataDirectoryLock lock = DataDirectoryLock.acquire(dataDir, holder);
        try {
            InterruptedRestore.requireNoneIn(dataDir);
        } catch (IOException e) {
            release(lock, e);
            throw e;
        }
        LOGGER.info("Data directory {} claimed ({})", dataDir, DataDirectoryLock.FILE_NAME);
        return lock;
    }

    /**
     * Releases a claim on the way out of a failure, keeping the failure the one reported.
     *
     * @param lock    The claim
     * @param failure What is being reported
     */
    static void release(DataDirectoryLock lock, Exception failure) {
        try {
            lock.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }
}
