package com.eonmux.excalibur.fsck.check;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The verdict on one file in the data directory.
 *
 * @param path          Absolute path of the file
 * @param relative      Path relative to the data directory, which is what an operator reads
 * @param format        Human-readable name of the format, or what it looked like
 * @param version       The format version found in the file, or null when there is none
 * @param status        What the check concluded
 * @param detail        What was observed, phrased for an operator
 * @param sizeBytes     Size of the file on disk, or -1 when it is not there
 * @param needsOperator True when the damage is of a kind no repair can act on by itself,
 *                      so the file is put to a human instead of into the repair plan
 * @param unverifiedContent True when part of the file carried nothing to weigh against,
 *                      even though everything that could be weighed held up
 */
public record FileCheck(
    Path path,
    String relative,
    String format,
    String version,
    CheckStatus status,
    String detail,
    long sizeBytes,
    boolean needsOperator,
    boolean unverifiedContent) {

    /**
     * Validates the verdict's components.
     *
     * @throws NullPointerException if path, relative, format, status or detail is null
     */
    public FileCheck {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(relative, "relative");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * Records a verdict whose damage, if any, is of a kind the repair planner may act on.
     *
     * <p>Which is nearly all of them: only a check that has established something about
     * <em>why</em> a file is damaged can say that no safe repair follows from it, so the
     * common case is a verdict that does not have to mention the question at all.</p>
     *
     * @param path      Absolute path of the file
     * @param relative  Path relative to the data directory
     * @param format    Human-readable name of the format
     * @param version   The format version found in the file, or null
     * @param status    What the check concluded
     * @param detail    What was observed
     * @param sizeBytes Size of the file on disk, or -1
     */
    public FileCheck(Path path, String relative, String format, String version,
                     CheckStatus status, String detail, long sizeBytes) {
        this(path, relative, format, version, status, detail, sizeBytes, false, false);
    }

    /**
     * Records a verdict whose damage may be acted on, and which is fully proven or not.
     *
     * @param path              Absolute path of the file
     * @param relative          Path relative to the data directory
     * @param format            Human-readable name of the format
     * @param version           The format version found in the file, or null
     * @param status            What the check concluded
     * @param detail            What was observed
     * @param sizeBytes         Size of the file on disk, or -1
     * @param needsOperator     Whether no repair here can act on the damage
     */
    public FileCheck(Path path, String relative, String format, String version,
                     CheckStatus status, String detail, long sizeBytes,
                     boolean needsOperator) {
        this(path, relative, format, version, status, detail, sizeBytes, needsOperator, false);
    }

    /**
     * Returns this verdict with part of the file marked as never weighed against anything.
     *
     * <p>Separate from {@link #status()} on purpose. A file can be
     * {@link CheckStatus#INTACT} - every checksum it carries was found and matched - and
     * still have a structure in it that carries no checksum at all. Bending the status to
     * say so would make a false statement about what was checked; leaving it unsaid would
     * let {@code --strict}, the mode whose entire job is to surface unproven state, pass
     * over it in silence.</p>
     *
     * @return A copy marked as only partly proven
     */
    public FileCheck withUnverifiedContent() {
        return new FileCheck(path, relative, format, version, status, detail, sizeBytes,
            needsOperator, true);
    }

    /**
     * Reports whether this verdict is damage to the store, which is what fails a deploy gate.
     *
     * <p>Every corrupt, unreadable or missing file is, with one exception: a corrupt file of a
     * kind the node rebuilds by itself when it opens ({@link FileKind#rebuiltOnOpen()}). That
     * file is reported as corrupt, because its bytes do not match its checksum, and it costs
     * the node a rebuild at start-up and nothing else, so it does not make the directory
     * damaged. Only corruption is exempt. A file that could not be read at all says something
     * about the device or its permissions that reaches past the one file, and stays damage
     * whatever its kind.</p>
     *
     * @return true if this verdict should fail the run
     */
    public boolean isDamage() {
        if (!status.isDamage()) {
            return false;
        }
        return status != CheckStatus.CORRUPT
            || !FileClassifier.classify(relative).rebuiltOnOpen();
    }

    /**
     * Returns the version as text, naming its absence rather than printing nothing.
     *
     * @return The version, or "n/a"
     */
    public String versionText() {
        return version == null ? "n/a" : version;
    }
}
