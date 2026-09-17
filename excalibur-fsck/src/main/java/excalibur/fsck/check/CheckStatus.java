package excalibur.fsck.check;

/**
 * What the checker was able to conclude about one file.
 *
 * <p>Mirrors the {@code IntegrityStatus} the store, vector and filter modules each declare,
 * with one value they do not need and this tool cannot do without. Those enums describe a
 * file whose format is known and whose verifier exists; a survey of a whole data directory
 * also meets files nothing can verify at all, and calling those intact would be the exact
 * failure this tool exists to prevent.</p>
 */
public enum CheckStatus {

    /** A checksum was present and the bytes matched it. */
    INTACT,

    /**
     * The file carries checksum fields but was never sealed, so its payload is unverified.
     *
     * <p>The normal state of a file a live writer holds open, and the state a crash leaves
     * behind. Absence of evidence, not evidence of damage.</p>
     */
    UNSEALED,

    /** The file predates checksums in its format and carries none to check. */
    LEGACY_UNPROTECTED,

    /**
     * The format is known but nothing in this build can verify the file's contents.
     *
     * <p>Distinct from {@link #LEGACY_UNPROTECTED}, which is a file that could have carried
     * a checksum had it been written by a newer build. This is a format that has none at
     * all, or a file nothing here recognises.</p>
     */
    UNCHECKED,

    /** A checksum was present and did not match: the file is damaged. */
    CORRUPT,

    /** The file exists but could not be read far enough to reach a verdict. */
    UNREADABLE,

    /** Something names this file and it is not on disk. */
    MISSING;

    /**
     * Reports whether this verdict is damage that must fail a deploy gate.
     *
     * @return true for corrupt, unreadable and missing
     */
    public boolean isDamage() {
        return this == CORRUPT || this == UNREADABLE || this == MISSING;
    }

    /**
     * Reports whether the file's bytes were actually weighed against a stored checksum.
     *
     * @return true only for {@link #INTACT}
     */
    public boolean isProven() {
        return this == INTACT;
    }
}
