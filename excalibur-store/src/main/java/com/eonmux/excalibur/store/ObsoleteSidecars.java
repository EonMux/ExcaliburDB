package com.eonmux.excalibur.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Removes the files earlier builds wrote beside a segment that this build neither reads nor
 * writes.
 *
 * <p>Earlier builds kept a term-frequency sketch of every segment in {@code segment_N.tfs}, staged
 * as {@code segment_N.tfs.<unique>.tmp} while it was written. The query pipeline no longer orders
 * segments by term statistics, so nothing reads those files, and a node upgraded in place would
 * otherwise carry them for as long as the segment exists. They are derived data: removing one
 * loses nothing.</p>
 *
 * <p>The engine removes them once, as it starts, from every directory it discovers segments in.
 * A file that will not go is logged and left; it costs disk space and nothing else, and the next
 * start tries again. {@code excalibur-fsck} and {@code excalibur-backup} recognise the same names
 * through {@link #isObsolete(String)}, so a leftover one is reported as obsolete rather than as a
 * file nothing recognises, and is never copied into an image.</p>
 *
 * <p><strong>Thread safety:</strong> stateless.</p>
 */
public final class ObsoleteSidecars {

    private static final Logger LOGGER = Logger.getLogger(ObsoleteSidecars.class.getName());

    /**
     * {@code segment_N.tfs}, the term sketch an earlier build kept beside each segment, and
     * {@code segment_N.tfs.<unique>.tmp}, the name it staged one under.
     */
    private static final Pattern TERM_SKETCH = Pattern.compile("^segment_\\d+\\.tfs(\\..+\\.tmp)?$");

    private ObsoleteSidecars() {
    }

    /**
     * Reports whether a file name is one an earlier build wrote and this build has no use for.
     *
     * @param fileName The file's name, without any directory
     * @return true for a term sketch or its staging file
     */
    public static boolean isObsolete(String fileName) {
        return TERM_SKETCH.matcher(fileName).matches();
    }

    /**
     * Deletes every obsolete file directly inside the given directories.
     *
     * <p>Logs one line at INFO when anything was removed, naming how many files, so an upgrade
     * says what it tidied without a line per segment.</p>
     *
     * @param directories The directories segments are discovered in
     * @return How many files were removed
     */
    static int removeFrom(List<Path> directories) {
        int removed = 0;
        for (Path directory : directories) {
            removed += removeFrom(directory);
        }
        if (removed > 0) {
            LOGGER.log(Level.INFO, "Removed {0} obsolete term sketch file(s) (segment_N.tfs) an "
                + "earlier build wrote; nothing in this build reads them", removed);
        }
        return removed;
    }

    /**
     * Deletes the obsolete files directly inside one directory.
     *
     * @param directory The directory
     * @return How many files were removed
     */
    private static int removeFrom(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
                entry -> isObsolete(entry.getFileName().toString()))) {
            for (Path file : files) {
                try {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not remove the obsolete file " + file
                        + "; it is not read, and removal is tried again on the next start", e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list " + directory + " for obsolete term "
                + "sketch files", e);
        }
        return removed;
    }
}
