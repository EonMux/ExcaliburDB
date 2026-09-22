package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;

import java.nio.file.Path;

/**
 * Translates a per-format integrity verdict into this tool's own.
 *
 * <p>The store, vector and filter formats all report through
 * {@link com.eonmux.excalibur.core.integrity.IntegrityReport}, so the translation is one switch.
 * It exists at all because a survey of a whole directory needs a status those enums do not
 * have: a file nothing in this build can verify. Mapping in one place means a value added
 * to {@link IntegrityStatus} fails to compile here rather than being silently reported as
 * something else.</p>
 */
public final class IntegrityReports {

    private IntegrityReports() {
        throw new AssertionError("Utility class");
    }

    /**
     * Converts a format's verdict.
     *
     * @param report   What the format's own verifier concluded
     * @param file     The file it was about
     * @param relative Its path relative to the data directory
     * @param version  The format version, or null when none was read
     * @param size     The file's size in bytes
     * @return The equivalent verdict in this tool's terms
     */
    public static FileCheck from(IntegrityReport report, Path file, String relative,
                                 String version, long size) {
        return new FileCheck(file, relative, report.format(), version,
            translate(report.status()), report.detail(), size);
    }

    /**
     * Maps one status.
     *
     * @param status The verifier's verdict
     * @return This tool's equivalent
     */
    private static CheckStatus translate(IntegrityStatus status) {
        return switch (status) {
            case INTACT -> CheckStatus.INTACT;
            case UNSEALED -> CheckStatus.UNSEALED;
            case LEGACY_UNPROTECTED -> CheckStatus.LEGACY_UNPROTECTED;
            case CORRUPT -> CheckStatus.CORRUPT;
            case MISSING -> CheckStatus.MISSING;
            case UNREADABLE -> CheckStatus.UNREADABLE;
        };
    }
}
