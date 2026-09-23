package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.report.FsckReport;
import com.eonmux.excalibur.fsck.report.JsonReportWriter;
import com.eonmux.excalibur.fsck.report.TextReportWriter;
import com.eonmux.excalibur.fsck.repair.Quarantine;
import com.eonmux.excalibur.fsck.repair.RepairExecutor;
import com.eonmux.excalibur.fsck.repair.RepairOutcome;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/**
 * Offline integrity check and repair for an ExcaliburDB data directory.
 *
 * <p>Runs and exits. Nothing is started, nothing is served, and in the default mode nothing
 * in the directory is written to - the question it answers is whether a node's data is
 * intact, and answering it by starting the node is the thing an operator cannot do when
 * the node will not start.</p>
 *
 * <h2>What the exit code means</h2>
 * <p>Zero when nothing damaged was found, non-zero otherwise, so a deploy can be gated on
 * it. See {@link FsckExit} for the distinctions between the non-zero codes, and
 * {@code --strict} for gating on unverifiable files as well as damaged ones.</p>
 *
 * <h2>Repair</h2>
 * <p>Off unless {@code --repair} is given. The plan is printed either way, in the same
 * words, so a dry run is a faithful preview of an applied one. No repair deletes anything:
 * a damaged file is moved into a quarantine directory, a catalog row is marked purged
 * rather than cut out, and a torn log tail is disclaimed by the same one-byte write the
 * log makes for itself. Anything that would need a guess is reported as needing an
 * operator instead.</p>
 */
public final class ExcaliburFsck {

    private ExcaliburFsck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Runs the command.
     *
     * <p>Separate from {@link #main} so it can be driven by a test: the exit code and the
     * output are the whole interface, and a method that called {@code System.exit} could
     * not be asserted on.</p>
     *
     * @param args Command line
     * @param out  Where the report is written
     * @param err  Where usage errors and operational failures are written
     * @return The process exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (isHelpRequest(args)) {
            out.print(FsckOptionsParser.usage());
            return FsckExit.CLEAN;
        }
        FsckOptions options;
        try {
            options = FsckOptionsParser.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println();
            err.print(FsckOptionsParser.usage());
            return FsckExit.USAGE;
        }
        if (!Files.isDirectory(options.dataDirectory())) {
            err.println("The data directory " + options.dataDirectory()
                + " does not exist, or is not a directory. Nothing was checked, which is not "
                + "the same as nothing being wrong.");
            return FsckExit.USAGE;
        }
        return refusesQuarantine(options, err).orElseGet(() -> execute(options, out, err));
    }

    /**
     * Refuses a quarantine that lies inside the data directory, once links are followed.
     *
     * <p>Refused for a check as well as a repair. A repair would move damaged files to a place
     * still inside the directory it is repairing, where the next check reports them again and
     * the next backup carries them along. A check writes nothing there, but it is how a repair
     * is rehearsed: accepting the option on a check would pass the very command line that goes
     * wrong once {@code --repair} is added. A usage error, since the command line is what has
     * to change.</p>
     *
     * @param options What was asked for
     * @param err     Where the refusal is written
     * @return The exit code if refused, or empty to go on
     */
    private static OptionalInt refusesQuarantine(FsckOptions options, PrintStream err) {
        Path quarantine = options.quarantineDirectory();
        try {
            if (!QuarantineLocation.isInside(options.dataDirectory(), quarantine)) {
                return OptionalInt.empty();
            }
            err.println("The quarantine directory " + quarantine + " resolves to "
                + QuarantineLocation.canonical(quarantine) + ", inside the data directory "
                + QuarantineLocation.canonical(options.dataDirectory()) + ". Files moved there "
                + "would still be in the directory being checked, so nothing was checked. Name "
                + "one outside it with --quarantine-dir=<path>.");
            return OptionalInt.of(FsckExit.USAGE);
        } catch (IOException e) {
            err.println("Could not tell whether the quarantine directory " + quarantine
                + " lies inside the data directory " + options.dataDirectory()
                + ", so nothing was checked: " + e);
            return OptionalInt.of(FsckExit.FAILED);
        }
    }

    /**
     * Reports whether the line is asking how to use the tool rather than asking it to run.
     *
     * <p>Recognised anywhere on the line, not only first: {@code excalibur-fsck /var/lib/db
     * --help} is a half-typed command, and checking the directory instead of answering
     * would be the one thing the operator did not ask for.</p>
     *
     * @param args The command line
     * @return true if help was asked for
     */
    private static boolean isHelpRequest(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h") || arg.equals("help")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Surveys the directory, reports, and repairs if asked.
     *
     * @param options What was asked for
     * @param out     Where the report is written
     * @param err     Where operational failures are written
     * @return The process exit code
     */
    private static int execute(FsckOptions options, PrintStream out, PrintStream err) {
        DataDirectoryScan scan;
        try {
            scan = new DataDirectoryScanner(options.dataDirectory()).scan();
        } catch (IOException | RuntimeException e) {
            err.println("The data directory " + options.dataDirectory()
                + " could not be surveyed, so nothing is known about it: " + e);
            return FsckExit.FAILED;
        }
        RepairPlan plan = RepairPlanner.plan(scan);
        Buffered report = new Buffered(options.json(), out);
        TextReportWriter.writeScan(scan, report.text());
        TextReportWriter.writePlan(plan, report.text());
        List<RepairOutcome> outcomes = options.repair()
            ? applyRepairs(options, plan, report.text())
            : dryRun(plan, report.text());
        int exitCode = exitCode(scan, options.strict());
        report.finish(new FsckReport(scan, plan, outcomes, options.repair(), exitCode));
        return exitCode;
    }

    /**
     * States that nothing was done and how to ask for it to be.
     *
     * @param plan What could be done
     * @param out  Where to write
     * @return An empty outcome list
     */
    private static List<RepairOutcome> dryRun(RepairPlan plan, PrintStream out) {
        TextReportWriter.writeDryRunNotice(plan, out);
        return List.of();
    }

    /**
     * Carries out the plan, after saying so.
     *
     * @param options What was asked for
     * @param plan    What to do
     * @param out     Where to write
     * @return What happened
     */
    private static List<RepairOutcome> applyRepairs(FsckOptions options, RepairPlan plan,
                                                    PrintStream out) {
        out.println();
        out.println("Applying repairs into quarantine directory "
            + options.quarantineDirectory());
        List<RepairOutcome> outcomes = new RepairExecutor(
            new Quarantine(options.quarantineDirectory()), out).apply(plan);
        TextReportWriter.writeOutcomes(outcomes, out);
        return outcomes;
    }

    /**
     * Decides the exit code from what was found.
     *
     * @param scan   What the survey found
     * @param strict Whether unverifiable files should fail the run too
     * @return The exit code
     */
    private static int exitCode(DataDirectoryScan scan, boolean strict) {
        if (scan.hasDamage()) {
            return FsckExit.DAMAGE;
        }
        return strict && scan.hasUnverified() ? FsckExit.DAMAGE : FsckExit.CLEAN;
    }

    /**
     * Holds the text report until it is known whether JSON was asked for instead.
     *
     * <p>The two modes describe the same run, and the repair executor announces each action
     * as it takes it, which has to go somewhere even when the caller wanted JSON. Buffering
     * the text keeps the JSON document the only thing on stdout in that mode, rather than
     * JSON with progress lines interleaved through it.</p>
     */
    private static final class Buffered {

        private final boolean json;
        private final PrintStream destination;
        private final ByteArrayOutputStream buffer;
        private final PrintStream text;

        /**
         * Prepares the output for one run.
         *
         * @param json        Whether the caller asked for JSON
         * @param destination Where output ultimately goes
         */
        private Buffered(boolean json, PrintStream destination) {
            this.json = json;
            this.destination = destination;
            this.buffer = new ByteArrayOutputStream();
            this.text = json
                ? new PrintStream(buffer, true, StandardCharsets.UTF_8) : destination;
        }

        /**
         * Returns the stream the text report is written to.
         *
         * @return The destination, or a buffer that will be discarded in JSON mode
         */
        private PrintStream text() {
            return text;
        }

        /**
         * Emits whichever form the caller asked for.
         *
         * @param report What the run established and did
         */
        private void finish(FsckReport report) {
            if (!json) {
                return;
            }
            text.flush();
            JsonReportWriter.write(report, destination);
        }
    }

    /**
     * Process entry point.
     *
     * @param args Command line
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
