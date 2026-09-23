package com.eonmux.excalibur.fsck.report;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.ConsistencyFinding;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FindingSeverity;
import com.eonmux.excalibur.fsck.repair.RepairAction;
import com.eonmux.excalibur.fsck.repair.RepairOutcome;
import com.eonmux.excalibur.fsck.repair.RepairPlan;

import java.io.PrintStream;
import java.util.Map;

/**
 * Writes the report a person reads.
 *
 * <p>Every file is listed, including the ones nothing could be concluded about. A survey
 * that printed only the damage would let an operator read silence as health, and the
 * difference between "checked and sound" and "not checked" is the one thing this tool
 * exists to keep visible - so the summary counts them separately and names the distinction
 * rather than implying it.</p>
 */
public final class TextReportWriter {

    /** Width the status column is padded to, so the detail column lines up. */
    private static final int STATUS_WIDTH = 19;

    private TextReportWriter() {
        throw new AssertionError("Utility class");
    }

    /**
     * Writes the file-by-file survey and the summary.
     *
     * @param scan What the survey found
     * @param out  Where to write
     */
    public static void writeScan(DataDirectoryScan scan, PrintStream out) {
        out.println("excalibur-fsck: " + scan.dataDirectory());
        out.println();
        out.println("Files");
        for (FileCheck file : scan.files()) {
            out.printf("  %-" + STATUS_WIDTH + "s %s  [%s %s]%n",
                file.status(), file.relative(), file.format(), file.versionText());
            out.println("        " + file.detail());
        }
        if (scan.files().isEmpty()) {
            out.println("  (the directory holds no files)");
        }
        writeFindings(scan, out);
        writeSummary(scan, out);
    }

    /**
     * Writes what is wrong between files.
     *
     * @param scan What the survey found
     * @param out  Where to write
     */
    private static void writeFindings(DataDirectoryScan scan, PrintStream out) {
        out.println();
        out.println("Cross-file consistency");
        if (scan.findings().isEmpty()) {
            out.println("  every file the catalog, the routing directory and the indexes "
                + "refer to is present");
            return;
        }
        for (ConsistencyFinding finding : scan.findings()) {
            String label = finding.severity() == FindingSeverity.DAMAGE ? "DAMAGE " : "WARNING";
            out.printf("  %s [%s] %s%n", label, finding.kind(), finding.subject());
            out.println("        " + finding.detail());
        }
    }

    /**
     * Writes the counts and the one-line verdict.
     *
     * @param scan What the survey found
     * @param out  Where to write
     */
    private static void writeSummary(DataDirectoryScan scan, PrintStream out) {
        Map<CheckStatus, Long> counts = scan.statusCounts();
        out.println();
        out.println("Summary");
        out.printf("  %d files examined%n", scan.files().size());
        out.printf("  %d verified against a checksum%n", counts.get(CheckStatus.INTACT));
        out.printf("  %d corrupt, %d unreadable, %d missing%n",
            counts.get(CheckStatus.CORRUPT), counts.get(CheckStatus.UNREADABLE),
            counts.get(CheckStatus.MISSING));
        long rebuiltOnOpen = scan.rebuiltOnOpenCount();
        if (rebuiltOnOpen > 0) {
            out.printf("  %d of the corrupt files the node rebuilds or sets aside by itself "
                + "when it next opens them - reported, and not counted as damage%n",
                rebuiltOnOpen);
        }
        out.printf("  %d unsealed, %d legacy-unprotected, %d unchecked, %d only partly "
                + "verified - these were NOT shown to be sound, only not shown to be "
                + "damaged%n",
            counts.get(CheckStatus.UNSEALED), counts.get(CheckStatus.LEGACY_UNPROTECTED),
            counts.get(CheckStatus.UNCHECKED), scan.partlyVerifiedCount());
        long notData = counts.get(CheckStatus.NOT_DATA);
        if (notData > 0) {
            out.printf("  %d not engine data - JVM heap dumps, reported so the disk they take "
                + "is seen, never read, and not counted as unverified%n", notData);
        }
        out.println(scan.hasDamage()
            ? "  VERDICT: damage found"
            : "  VERDICT: no damage found");
    }

    /**
     * Writes the repair plan, in the form it takes when nothing will be applied.
     *
     * @param plan What could be done
     * @param out  Where to write
     */
    public static void writePlan(RepairPlan plan, PrintStream out) {
        out.println();
        out.println("Repair plan");
        if (plan.actions().isEmpty()) {
            out.println("  nothing here can be repaired safely and automatically");
        }
        for (RepairAction action : plan.actions()) {
            out.println("  - " + action.description());
            out.println("        why this is safe: " + action.rationale());
        }
        if (!plan.needsOperator().isEmpty()) {
            out.println();
            out.println("Needs an operator - not guessed at");
            for (String item : plan.needsOperator()) {
                out.println("  - " + item);
            }
        }
    }

    /**
     * Says that the plan was only a plan, and how to ask for it to be carried out.
     *
     * @param plan What could be done
     * @param out  Where to write
     */
    public static void writeDryRunNotice(RepairPlan plan, PrintStream out) {
        if (plan.actions().isEmpty()) {
            return;
        }
        out.println();
        out.printf("  Nothing above was done. Repair is off by default; %d action(s) would be "
            + "taken if this were re-run with --repair, and no file would be deleted by any "
            + "of them.%n", plan.actions().size());
    }

    /**
     * Writes what the repairs actually did.
     *
     * @param outcomes What happened
     * @param out      Where to write
     */
    public static void writeOutcomes(java.util.List<RepairOutcome> outcomes, PrintStream out) {
        out.println();
        out.println("Repairs applied");
        if (outcomes.isEmpty()) {
            out.println("  nothing needed doing");
            return;
        }
        for (RepairOutcome outcome : outcomes) {
            out.printf("  %s %s: %s%n", outcome.applied() ? "done   " : "FAILED ",
                outcome.action().relative(), outcome.detail());
        }
    }
}
