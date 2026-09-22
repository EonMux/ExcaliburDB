package com.eonmux.excalibur.backup.verify;

/**
 * How hard to look.
 *
 * @author ExcaliburDB
 */
public enum VerifyDepth {

    /**
     * Every file's length and checksum against the manifest.
     *
     * <p>Answers "is this the image that was taken". Catches a flipped bit, a truncated
     * write, a lost file, a file nobody expected. Runs at the speed of the storage, so it
     * is the one worth running on a schedule.</p>
     */
    CHECKSUM,

    /**
     * The above, and then each format asked about itself.
     *
     * <p>Answers the different question "would a node be able to open this". A file can
     * match its checksum perfectly and still be a segment whose entries were damaged before
     * the backup was taken, and no amount of checksumming an image finds a fault that was
     * copied faithfully. Each file is handed to the engine's own integrity checker for its
     * format, so this reports what a node would find rather than what this tool guesses.</p>
     */
    DEEP
}
