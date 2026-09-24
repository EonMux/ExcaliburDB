package com.eonmux.excalibur.backup.cli;

import com.eonmux.excalibur.backup.restore.RestoreOptions;
import com.eonmux.excalibur.backup.restore.RestoreResult;
import com.eonmux.excalibur.backup.restore.SnapshotRestorer;
import com.eonmux.excalibur.backup.verify.VerifyDepth;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/**
 * The {@code restore} subcommand.
 *
 * @author ExcaliburDB
 */
public final class RestoreCommand {

    /** Options this subcommand takes a value for. */
    public static final Set<String> OPTIONS = Set.of("snapshot", "target");

    /** Options this subcommand takes without a value. */
    public static final Set<String> FLAGS = Set.of("force", "deep");

    private RestoreCommand() {
    }

    /**
     * Restores an image and reports what now exists.
     *
     * @param arguments The parsed command line
     * @param out       Where to report
     * @return Always {@link ExitCode#OK}; every refusal is thrown
     * @throws UsageException if a required option is missing, or --target is inside
     *                        --snapshot
     * @throws IOException    if the image does not verify, the target is occupied or held
     *                        by another process, or a file cannot be read or written
     */
    public static ExitCode execute(Arguments arguments, PrintStream out)
        throws UsageException, IOException {
        RestoreOptions options;
        try {
            options = new RestoreOptions(arguments.requiredPath("snapshot"),
                arguments.requiredPath("target"), arguments.flag("force"),
                arguments.flag("deep") ? VerifyDepth.DEEP : VerifyDepth.CHECKSUM);
        } catch (IllegalArgumentException e) {
            // The two paths contradict each other: a line to correct, not a failure.
            throw new UsageException(e.getMessage());
        }
        // Printed as it happens, so a restore that then fails still says what it already did.
        RestoreResult result = new SnapshotRestorer().restore(options,
            recovery -> out.printf("recovered  %s%n", recovery));
        out.printf("restored   %s%n", result.target());
        out.printf("  from     %s (%s)%n", options.snapshotDir(),
            result.manifest().snapshotId());
        out.printf("  epoch    %d (%s)%n", result.manifest().epoch(),
            result.manifest().epochSource());
        out.printf("  state    %s%n", result.manifest().consistency());
        out.printf("  files    %d, %d bytes%n", result.filesRestored(),
            result.bytesRestored());
        for (String rebuild : result.rebuildRequired()) {
            out.printf("  rebuild  %s%n", rebuild);
        }
        return ExitCode.OK;
    }
}
