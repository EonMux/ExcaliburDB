package com.eonmux.excalibur.core.datadir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The names a restore writes inside the data directory it is restoring into, and the one
 * question everything else asks about them: did a restore stop part-way here.
 *
 * <p>{@code excalibur-backup restore} builds the new tree inside the target itself, because the
 * target may be a mount point - the container image's volume is one - that can be neither
 * renamed nor replaced, and a directory beside it would sit on another filesystem. It stages the
 * image under {@value #STAGING_PREFIX}{@code <id>}, moves what the target held aside into
 * {@value #DISPLACED_PREFIX}{@code <id>}, and then moves the staged entries into place one by
 * one. For the whole of that it keeps a journal, {@value #JOURNAL_FILE_NAME}, written before the
 * first byte is staged and removed only after the last scratch directory is gone.</p>
 *
 * <p>So a journal in a data directory means exactly one thing: a restore did not finish, and
 * the directory may be any blend of the old contents and the new. A node must not start on it
 * and a snapshot must not copy it; the restore itself, run again, completes or unwinds what the
 * journal records. The format of the journal belongs to the backup tool - everything else needs
 * only its name.</p>
 *
 * @author ExcaliburDB
 */
public final class InterruptedRestore {

    /** The restore journal, present only while a restore is in progress or was interrupted. */
    public static final String JOURNAL_FILE_NAME = ".excalibur-restore";

    /** The name the journal is written under before it is renamed into place. */
    public static final String JOURNAL_STAGED_NAME = JOURNAL_FILE_NAME + ".tmp";

    /** Begins the name of the directory a restore stages the image into. */
    public static final String STAGING_PREFIX = ".excalibur-restore-staging-";

    /** Begins the name of the directory a restore moves the target's old contents into. */
    public static final String DISPLACED_PREFIX = ".excalibur-restore-replaced-";

    private InterruptedRestore() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether a top-level name in a data directory belongs to a restore.
     *
     * @param name A name directly inside the data directory
     * @return true for the journal, its staged name, and either scratch directory
     */
    public static boolean isRestoreEntry(String name) {
        Objects.requireNonNull(name, "name");
        return name.equals(JOURNAL_FILE_NAME) || name.equals(JOURNAL_STAGED_NAME)
            || name.startsWith(STAGING_PREFIX) || name.startsWith(DISPLACED_PREFIX);
    }

    /**
     * Returns the journal of an interrupted restore, if the directory holds one.
     *
     * @param dataDirectory The data directory
     * @return The journal's path, or empty
     */
    public static Optional<Path> journalIn(Path dataDirectory) {
        Path journal = Objects.requireNonNull(dataDirectory, "dataDirectory")
            .resolve(JOURNAL_FILE_NAME);
        return Files.exists(journal, LinkOption.NOFOLLOW_LINKS)
            ? Optional.of(journal) : Optional.empty();
    }

    /**
     * Refuses a data directory a restore stopped part-way through.
     *
     * <p>Called after the directory's lock is held, so a restore still running cannot be
     * mistaken for one that was interrupted: it would hold the lock.</p>
     *
     * @param dataDirectory The data directory
     * @throws IOException if it holds a restore journal
     */
    public static void requireNoneIn(Path dataDirectory) throws IOException {
        Optional<Path> journal = journalIn(dataDirectory);
        if (journal.isPresent()) {
            throw new IOException("The data directory " + dataDirectory + " holds "
                + JOURNAL_FILE_NAME + ", the journal of an excalibur-backup restore that did not "
                + "finish, so its files may be a mixture of what was there before and the image "
                + "being restored. Run excalibur-backup restore into it again: it completes the "
                + "interrupted restore from the journal before doing anything else.");
        }
    }
}
