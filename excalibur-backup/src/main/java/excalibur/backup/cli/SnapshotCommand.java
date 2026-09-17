package excalibur.backup.cli;

import excalibur.backup.manifest.SnapshotManifest;
import excalibur.backup.snapshot.ClassifiedFile;
import excalibur.backup.snapshot.Inclusion;
import excalibur.backup.snapshot.SnapshotOptions;
import excalibur.backup.snapshot.SnapshotResult;
import excalibur.backup.snapshot.SnapshotWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/**
 * The {@code snapshot} subcommand.
 *
 * @author ExcaliburDB
 */
public final class SnapshotCommand {

    /** Options this subcommand takes a value for. */
    public static final Set<String> OPTIONS = Set.of("data-dir", "out");

    /** Options this subcommand takes without a value. */
    public static final Set<String> FLAGS = Set.of("exclude-derived", "allow-live");

    private SnapshotCommand() {
    }

    /**
     * Takes a snapshot and reports what it holds.
     *
     * @param arguments The parsed command line
     * @param out       Where to report
     * @return Always {@link ExitCode#OK}; every failure is thrown
     * @throws UsageException if a required option is missing
     * @throws IOException    if the snapshot cannot be taken
     */
    public static ExitCode execute(Arguments arguments, PrintStream out)
        throws UsageException, IOException {
        SnapshotOptions options = new SnapshotOptions(arguments.requiredPath("data-dir"),
            arguments.requiredPath("out"), !arguments.flag("exclude-derived"),
            arguments.flag("allow-live"));
        SnapshotResult result = new SnapshotWriter().take(options);
        report(result, out);
        return ExitCode.OK;
    }

    /**
     * Prints what was written, and what was deliberately not.
     *
     * @param result What the snapshot produced
     * @param out    Where to report
     */
    private static void report(SnapshotResult result, PrintStream out) {
        SnapshotManifest manifest = result.manifest();
        out.printf("snapshot   %s%n", result.snapshotDir());
        out.printf("  id       %s%n", manifest.snapshotId());
        out.printf("  taken    %s%n", manifest.createdAt());
        out.printf("  source   %s%n", manifest.sourceDataDir());
        out.printf("  epoch    %d (%s)%n", manifest.epoch(), manifest.epochSource());
        out.printf("  state    %s%n", manifest.consistency());
        out.printf("  files    %d, %d bytes%n", manifest.files().size(), manifest.totalBytes());
        for (Inclusion inclusion : Inclusion.values()) {
            List<ClassifiedFile> skipped = result.skipped().stream()
                .filter(file -> file.inclusion() == inclusion).toList();
            if (!skipped.isEmpty()) {
                out.printf("  omitted  %d file(s) classed %s: %s%n", skipped.size(), inclusion,
                    skipped.get(0).reason());
            }
        }
    }
}
