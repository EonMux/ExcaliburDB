package com.eonmux.excalibur.backup.restore;

import com.eonmux.excalibur.backup.io.AtomicPublish;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable record of where a restore into a directory has got to.
 *
 * <p>A restore that builds its tree inside the target cannot publish it with one rename, so it
 * publishes it in steps, and between two steps the target is a mixture. The journal is what
 * makes every mixture recoverable: it is written - atomically, by rename - before each step
 * begins, it names the two scratch directories, and a restore that finds one completes the step
 * it records in the direction it records, forward to the image or back to what was there.</p>
 *
 * <p>Each phase is chosen so that recovery never has to guess which entries are old and which
 * are new:</p>
 * <ul>
 *   <li>{@link Phase#STAGING} - the image is being copied into the staging directory. The
 *       target's own entries are untouched. Recovery discards the staging directory.</li>
 *   <li>{@link Phase#DISPLACING} - the staging directory is complete and forced, and the
 *       target's entries are being moved into the displaced directory. Everything still in the
 *       target is old. Recovery moves the rest aside and installs.</li>
 *   <li>{@link Phase#INSTALLING} - every old entry is displaced, and the staged entries are
 *       being moved into the target. Everything in the target is new. Recovery moves the rest of
 *       the staged entries in, then deletes both scratch directories.</li>
 *   <li>{@link Phase#UNINSTALLING} - an install failed, and the entries already installed are
 *       going back to the staging directory. Everything in the target is new.</li>
 *   <li>{@link Phase#UNDISPLACING} - the target holds nothing of the image, and the displaced
 *       entries are going back. Recovery finishes putting them back and discards the image.</li>
 * </ul>
 *
 * <p>The format is the backup tool's own and is read by nothing else; the node needs only to
 * know that the file exists ({@link InterruptedRestore}).</p>
 *
 * @param phase       How far the restore got
 * @param snapshotId  The image being restored
 * @param staging     Name of the staging directory inside the target
 * @param displaced   Name of the directory the target's previous entries are moved into
 * @author ExcaliburDB
 */
record RestoreJournal(Phase phase, String snapshotId, String staging, String displaced) {

    /** The first line of every journal, naming the format and its version. */
    static final String HEADER = "excalibur-restore-journal 1";

    /** How far a restore got, in the order it gets there. */
    enum Phase {

        /** Copying the image into the staging directory. */
        STAGING,

        /** Moving the target's previous entries into the displaced directory. */
        DISPLACING,

        /** Moving the staged entries into the target. */
        INSTALLING,

        /** Moving installed entries back into the staging directory, after a failure. */
        UNINSTALLING,

        /** Moving the displaced entries back into the target, after a failure. */
        UNDISPLACING
    }

    /**
     * Validates the components, including that the scratch names cannot point outside the
     * target.
     *
     * @throws IllegalArgumentException if a scratch name is not a restore's
     */
    RestoreJournal {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(snapshotId, "snapshotId");
        requireScratchName(staging, InterruptedRestore.STAGING_PREFIX);
        requireScratchName(displaced, InterruptedRestore.DISPLACED_PREFIX);
        if (snapshotId.isBlank() || snapshotId.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A snapshot id is one non-blank line");
        }
    }

    /**
     * Starts the journal of a new restore, with fresh scratch names.
     *
     * @param snapshotId The image being restored
     * @return The journal, in {@link Phase#STAGING}
     */
    static RestoreJournal begin(String snapshotId) {
        String suffix = UUID.randomUUID().toString();
        return new RestoreJournal(Phase.STAGING, snapshotId,
            InterruptedRestore.STAGING_PREFIX + suffix,
            InterruptedRestore.DISPLACED_PREFIX + suffix);
    }

    /**
     * Returns this journal moved on to another phase.
     *
     * @param next The phase
     * @return The journal in that phase
     */
    RestoreJournal at(Phase next) {
        return new RestoreJournal(next, snapshotId, staging, displaced);
    }

    /**
     * Returns the staging directory inside a target.
     *
     * @param target The target
     * @return Where the image is staged
     */
    Path stagingIn(Path target) {
        return target.resolve(staging);
    }

    /**
     * Returns the displaced directory inside a target.
     *
     * @param target The target
     * @return Where the target's previous entries are kept until the image is installed
     */
    Path displacedIn(Path target) {
        return target.resolve(displaced);
    }

    /**
     * Makes this the target's journal: written beside it, forced, renamed on, and the rename
     * forced, so a crash leaves either the previous phase or this one and never a torn file.
     *
     * @param target The target
     * @throws IOException if it cannot be written
     */
    void write(Path target) throws IOException {
        Path staged = target.resolve(InterruptedRestore.JOURNAL_STAGED_NAME);
        Files.writeString(staged, render(), StandardCharsets.UTF_8);
        AtomicPublish.publish(staged, target.resolve(InterruptedRestore.JOURNAL_FILE_NAME));
    }

    /**
     * Reads a target's journal, if it has one.
     *
     * @param target The target
     * @return The journal, or empty when no restore was interrupted there
     * @throws IOException if there is a journal and it cannot be read or does not parse; nothing
     *                     is done to a directory whose journal cannot be believed
     */
    static Optional<RestoreJournal> read(Path target) throws IOException {
        Path file = target.resolve(InterruptedRestore.JOURNAL_FILE_NAME);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(lines));
        } catch (IllegalArgumentException e) {
            throw new IOException("The restore journal " + file + " cannot be read ("
                + e.getMessage() + "), so the interrupted restore it records cannot be completed "
                + "automatically. The directory's previous contents, if any are not back in "
                + "place, are in its " + InterruptedRestore.DISPLACED_PREFIX + "* directory and "
                + "the image in its " + InterruptedRestore.STAGING_PREFIX + "* directory; put "
                + "back the one that is wanted, delete the other and the journal, and restore "
                + "again.", e);
        }
    }

    /**
     * Removes the target's journal, and forces the removal.
     *
     * @param target The target
     * @throws IOException if it cannot be removed
     */
    static void remove(Path target) throws IOException {
        Files.deleteIfExists(target.resolve(InterruptedRestore.JOURNAL_FILE_NAME));
        Files.deleteIfExists(target.resolve(InterruptedRestore.JOURNAL_STAGED_NAME));
        AtomicPublish.syncDirectory(target);
    }

    /**
     * Writes the journal in the form {@link #parse} reads.
     *
     * @return The text
     */
    String render() {
        return HEADER + "\n"
            + "phase=" + phase.name() + "\n"
            + "snapshot=" + snapshotId + "\n"
            + "staging=" + staging + "\n"
            + "displaced=" + displaced + "\n";
    }

    /**
     * Parses a journal.
     *
     * @param lines Its lines
     * @return The journal
     * @throws IllegalArgumentException if it is not a journal this build wrote
     */
    static RestoreJournal parse(List<String> lines) {
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IllegalArgumentException("it does not begin with '" + HEADER + "'");
        }
        Map<String, String> values = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("'" + line + "' is not key=value");
            }
            values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return new RestoreJournal(Phase.valueOf(required(values, "phase")),
            required(values, "snapshot"), required(values, "staging"),
            required(values, "displaced"));
    }

    /**
     * Returns a value the journal must carry.
     *
     * @param values Every value it carries
     * @param key    The key
     * @return The value
     * @throws IllegalArgumentException if it is missing
     */
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("it has no " + key);
        }
        return value;
    }

    /**
     * Requires a scratch name to be one this tool gives, and nothing that leaves the target.
     *
     * @param name   The name
     * @param prefix What it must begin with
     * @throws IllegalArgumentException if it is anything else
     */
    private static void requireScratchName(String name, String prefix) {
        Objects.requireNonNull(name, "name");
        if (!name.startsWith(prefix) || name.length() == prefix.length()
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("'" + name + "' is not a restore's scratch name");
        }
    }
}
