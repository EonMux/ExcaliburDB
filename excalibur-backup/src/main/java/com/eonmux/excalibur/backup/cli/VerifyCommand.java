package com.eonmux.excalibur.backup.cli;

import com.eonmux.excalibur.backup.verify.ContentCheck;
import com.eonmux.excalibur.backup.verify.Problem;
import com.eonmux.excalibur.backup.verify.SnapshotVerifier;
import com.eonmux.excalibur.backup.verify.VerificationReport;
import com.eonmux.excalibur.backup.verify.VerifyDepth;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/**
 * The {@code verify} subcommand.
 *
 * @author ExcaliburDB
 */
public final class VerifyCommand {

    /** Options this subcommand takes a value for. */
    public static final Set<String> OPTIONS = Set.of("snapshot");

    /** Options this subcommand takes without a value. */
    public static final Set<String> FLAGS = Set.of("deep");

    private VerifyCommand() {
    }

    /**
     * Verifies an image and reports what was found.
     *
     * @param arguments The parsed command line
     * @param out       Where to report a clean image
     * @param err       Where to report a damaged one
     * @return {@link ExitCode#OK} or {@link ExitCode#VERIFICATION_FAILED}
     * @throws UsageException if a required option is missing
     * @throws IOException    if the image cannot be read or its manifest believed
     */
    public static ExitCode execute(Arguments arguments, PrintStream out, PrintStream err)
        throws UsageException, IOException {
        VerifyDepth depth = arguments.flag("deep") ? VerifyDepth.DEEP : VerifyDepth.CHECKSUM;
        VerificationReport report =
            new SnapshotVerifier().verify(arguments.requiredPath("snapshot"), depth);
        if (report.isValid()) {
            printClean(report, depth, out);
            return ExitCode.OK;
        }
        err.printf("NOT VERIFIED  %s%n", report.snapshotDir());
        err.printf("  %d of %d file(s) are not what the manifest says they are:%n",
            report.problems().size(), report.filesChecked());
        for (Problem problem : report.problems()) {
            err.printf("  %s: %s%n", problem.path(), problem.detail());
        }
        return ExitCode.VERIFICATION_FAILED;
    }

    /**
     * Reports an image that checked out.
     *
     * @param report What was found
     * @param depth  How hard it looked
     * @param out    Where to report
     */
    private static void printClean(VerificationReport report, VerifyDepth depth,
                                   PrintStream out) {
        out.printf("verified   %s%n", report.snapshotDir());
        out.printf("  epoch    %d (%s)%n", report.manifest().epoch(),
            report.manifest().epochSource());
        out.printf("  state    %s%n", report.manifest().consistency());
        out.printf("  checked  %d file(s), %d bytes, %s%n", report.filesChecked(),
            report.bytesChecked(), depth);
        long healthy = report.contentChecks().stream().filter(ContentCheck::healthy).count();
        if (!report.contentChecks().isEmpty()) {
            out.printf("  formats  %d of %d structure(s) opened and walked cleanly%n",
                healthy, report.contentChecks().size());
        }
    }
}
