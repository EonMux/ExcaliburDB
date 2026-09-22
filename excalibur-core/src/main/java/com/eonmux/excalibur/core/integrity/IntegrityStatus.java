package com.eonmux.excalibur.core.integrity;

/**
 * What a verification pass was able to conclude about one persisted index file.
 *
 * <p>The three non-failing values are deliberately distinct. "Not corrupt" and "checked and
 * intact" are different claims, and a tool that collapses them reports a file it never
 * verified as sound.</p>
 */
public enum IntegrityStatus {

    /** The file carries a checksum and the bytes match it. */
    INTACT,

    /**
     * The file carries checksum fields but was not sealed, so its payload is unverified.
     *
     * <p>A file is left unsealed for the whole time a writer holds it open, so this is the
     * normal state of a live index and the state a crash leaves behind. It is not evidence of
     * damage, only the absence of evidence of soundness.</p>
     */
    UNSEALED,

    /** The file predates checksums in this format and carries none. */
    LEGACY_UNPROTECTED,

    /** A checksum was present and did not match: the file is damaged. */
    CORRUPT,

    /** The file does not exist. */
    MISSING,

    /** The file exists but could not be read far enough to reach a verdict. */
    UNREADABLE
}
