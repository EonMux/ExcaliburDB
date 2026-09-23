package com.eonmux.excalibur.fsck;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Reads the command line, refusing anything it does not understand.
 *
 * <p>An unknown flag is a failure rather than something to ignore. The one flag that
 * matters is {@code --repair}, and a tool that quietly ignored a misspelling of it would
 * report a directory as unrepaired when the operator believed they had asked for repairs -
 * or, worse, the reverse in some future version.</p>
 */
public final class FsckOptionsParser {

    /** Prefix of the directory quarantined files are moved into by default. */
    private static final String DEFAULT_QUARANTINE_PREFIX = "fsck-quarantine-";

    private FsckOptionsParser() {
        throw new AssertionError("Utility class");
    }

    /**
     * Parses the arguments.
     *
     * @param args The command line
     * @return What was asked for
     * @throws IllegalArgumentException if the line is not usable, with a message to print
     */
    public static FsckOptions parse(String[] args) {
        Path dataDirectory = null;
        Path quarantine = null;
        boolean repair = false;
        boolean json = false;
        boolean strict = false;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                dataDirectory = firstDirectory(dataDirectory, arg);
                continue;
            }
            switch (flagName(arg)) {
                case "--repair" -> repair = true;
                case "--json" -> json = true;
                case "--strict" -> strict = true;
                case "--quarantine-dir" -> quarantine = Path.of(flagValue(arg));
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (dataDirectory == null) {
            throw new IllegalArgumentException("No data directory was given");
        }
        return new FsckOptions(dataDirectory, repair,
            quarantine != null ? quarantine : defaultQuarantine(dataDirectory), json, strict);
    }

    /**
     * Accepts the first positional argument as the data directory and refuses a second.
     *
     * @param current What has been taken so far, or null
     * @param arg     The positional argument
     * @return The data directory
     * @throws IllegalArgumentException if a second directory is given
     */
    private static Path firstDirectory(Path current, String arg) {
        if (current != null) {
            throw new IllegalArgumentException(
                "Only one data directory may be checked at a time; got a second: " + arg);
        }
        return Path.of(arg);
    }

    /**
     * Returns the name part of a flag, with any {@code =value} removed.
     *
     * @param arg The argument
     * @return The flag name
     */
    private static String flagName(String arg) {
        int equals = arg.indexOf('=');
        return equals < 0 ? arg : arg.substring(0, equals);
    }

    /**
     * Returns the value part of a {@code --flag=value} argument.
     *
     * @param arg The argument
     * @return The value
     * @throws IllegalArgumentException if the argument carries none
     */
    private static String flagValue(String arg) {
        int equals = arg.indexOf('=');
        if (equals < 0 || equals == arg.length() - 1) {
            throw new IllegalArgumentException(arg + " needs a value, as --flag=value");
        }
        return arg.substring(equals + 1);
    }

    /**
     * Chooses where quarantined files go when the caller did not say.
     *
     * <p>Beside the data directory rather than inside it: a directory that grows a
     * subdirectory of damaged files is one whose next backup, next disk-usage alarm and
     * next recursive copy all include them. Beside the directory the data is really in,
     * when it is named through a link, so the quarantine is on the data's own filesystem
     * and a repair moves a file by renaming it rather than copying it across.</p>
     *
     * <p>A directory with no parent - a filesystem root - has nowhere beside it, and the
     * default is then inside it. That is not quietly corrected here: the run refuses a
     * quarantine inside the data directory, and says to name one.</p>
     *
     * @param dataDirectory The directory being checked
     * @return A timestamped sibling directory
     */
    private static Path defaultQuarantine(Path dataDirectory) {
        Path real;
        try {
            real = QuarantineLocation.canonical(dataDirectory);
        } catch (IOException e) {
            // Unresolvable - a link loop, an unreadable directory on the way. The run reports
            // that about the data directory itself; the path as spelled serves until then.
            real = dataDirectory.toAbsolutePath().normalize();
        }
        Path parent = real.getParent();
        String name = DEFAULT_QUARANTINE_PREFIX + Instant.now().toEpochMilli();
        return parent != null ? parent.resolve(name) : real.resolve(name);
    }

    /**
     * Returns the usage text.
     *
     * @return How to invoke the tool
     */
    public static String usage() {
        return """
            Usage: excalibur-fsck <data-directory> [options]

            Verifies an ExcaliburDB data directory offline. Nothing is started and nothing is
            served; the directory is read and reported on.

            Options:
              --repair                 Apply the repairs that can be shown to be safe. Off by
                                       default: without it the plan is printed and nothing is
                                       touched. Every repair is stated before it is made, and
                                       no repair ever deletes anything.
              --quarantine-dir=<path>  Where damaged files are moved to. Defaults to a
                                       timestamped directory beside the data directory,
                                       whose parent must then be writable. Must be outside
                                       the data directory once links are followed; one
                                       inside it is refused with exit code 2, as is the
                                       default for a data directory at a filesystem root.
                                       On another filesystem a file, or a lexical
                                       segment's whole directory, is copied, compared, and
                                       only then removed from the data directory.
              --json                   Emit the report as JSON instead of text.
              --strict                 Exit non-zero when any file could not be verified, not
                                       only when one is damaged.
              --help, -h               Print this and do nothing else.

            Exit codes:
              0  nothing damaged was found
              1  damage, or an inconsistency between files, was found
              2  the command line was wrong, or the directory is not there
              3  the check could not be performed

            A corrupt segment_N.tfs term sketch is reported and does not exit 1: the node
            rebuilds it from its segment's live entries the next time it opens the segment.
            """;
    }
}
