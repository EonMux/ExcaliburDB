package excalibur.fsck.report;

import excalibur.fsck.check.CheckStatus;
import excalibur.fsck.check.ConsistencyFinding;
import excalibur.fsck.check.FileCheck;
import excalibur.fsck.repair.RepairAction;
import excalibur.fsck.repair.RepairOutcome;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes the report a program reads.
 *
 * <p>The same information as the text form, in the same order, with the exit code carried
 * in the document. A caller that pipes this into a gate should still read the process's
 * exit status; the field is there so a stored report answers on its own later.</p>
 */
public final class JsonReportWriter {

    private JsonReportWriter() {
        throw new AssertionError("Utility class");
    }

    /**
     * Writes the whole report as one JSON object.
     *
     * @param report What the run established and did
     * @param out    Where to write
     */
    public static void write(FsckReport report, PrintStream out) {
        out.println("{");
        out.println("  \"dataDirectory\": "
            + Json.string(report.scan().dataDirectory().toString()) + ",");
        out.println("  \"exitCode\": " + report.exitCode() + ",");
        out.println("  \"repairApplied\": " + report.repaired() + ",");
        out.println("  \"damageFound\": " + report.scan().hasDamage() + ",");
        out.println("  \"summary\": " + summary(report) + ",");
        writeArray(out, "files", report.scan().files().stream()
            .map(JsonReportWriter::file).toList(), true);
        writeArray(out, "findings", report.scan().findings().stream()
            .map(JsonReportWriter::finding).toList(), true);
        writeArray(out, "plannedRepairs", report.plan().actions().stream()
            .map(JsonReportWriter::action).toList(), true);
        writeArray(out, "needsOperator", report.plan().needsOperator().stream()
            .map(Json::string).toList(), true);
        writeArray(out, "appliedRepairs", report.outcomes().stream()
            .map(JsonReportWriter::outcome).toList(), false);
        out.println("}");
    }

    /**
     * Writes one named array member.
     *
     * @param out      Where to write
     * @param name     The member's name
     * @param elements Its already-rendered elements
     * @param comma    Whether a trailing comma is needed
     */
    private static void writeArray(PrintStream out, String name, List<String> elements,
                                   boolean comma) {
        if (elements.isEmpty()) {
            out.println("  \"" + name + "\": []" + (comma ? "," : ""));
            return;
        }
        out.println("  \"" + name + "\": [");
        out.println(elements.stream().map(e -> "    " + e)
            .collect(Collectors.joining(",\n")));
        out.println("  ]" + (comma ? "," : ""));
    }

    /**
     * Renders the status counts.
     *
     * @param report The report
     * @return A JSON object of counts
     */
    private static String summary(FsckReport report) {
        Map<CheckStatus, Long> counts = report.scan().statusCounts();
        return "{"
            + "\"examined\": " + report.scan().files().size()
            + ", \"intact\": " + counts.get(CheckStatus.INTACT)
            + ", \"unsealed\": " + counts.get(CheckStatus.UNSEALED)
            + ", \"legacyUnprotected\": " + counts.get(CheckStatus.LEGACY_UNPROTECTED)
            + ", \"unchecked\": " + counts.get(CheckStatus.UNCHECKED)
            + ", \"partlyVerified\": " + report.scan().partlyVerifiedCount()
            + ", \"corrupt\": " + counts.get(CheckStatus.CORRUPT)
            + ", \"unreadable\": " + counts.get(CheckStatus.UNREADABLE)
            + ", \"missing\": " + counts.get(CheckStatus.MISSING)
            + "}";
    }

    /**
     * Renders one file's verdict.
     *
     * @param file The verdict
     * @return A JSON object
     */
    private static String file(FileCheck file) {
        return "{\"path\": " + Json.string(file.relative())
            + ", \"format\": " + Json.string(file.format())
            + ", \"version\": " + Json.string(file.version())
            + ", \"status\": " + Json.string(file.status().name())
            + ", \"unverifiedContent\": " + file.unverifiedContent()
            + ", \"sizeBytes\": " + file.sizeBytes()
            + ", \"detail\": " + Json.string(file.detail()) + "}";
    }

    /**
     * Renders one cross-file finding.
     *
     * @param finding The finding
     * @return A JSON object
     */
    private static String finding(ConsistencyFinding finding) {
        return "{\"kind\": " + Json.string(finding.kind())
            + ", \"subject\": " + Json.string(finding.subject())
            + ", \"severity\": " + Json.string(finding.severity().name())
            + ", \"detail\": " + Json.string(finding.detail()) + "}";
    }

    /**
     * Renders one planned repair.
     *
     * @param action The repair
     * @return A JSON object
     */
    private static String action(RepairAction action) {
        return "{\"kind\": " + Json.string(action.kind().name())
            + ", \"path\": " + Json.string(action.relative())
            + ", \"segmentId\": " + action.segmentId()
            + ", \"description\": " + Json.string(action.description())
            + ", \"rationale\": " + Json.string(action.rationale()) + "}";
    }

    /**
     * Renders one applied repair.
     *
     * @param outcome What happened
     * @return A JSON object
     */
    private static String outcome(RepairOutcome outcome) {
        return "{\"kind\": " + Json.string(outcome.action().kind().name())
            + ", \"path\": " + Json.string(outcome.action().relative())
            + ", \"applied\": " + outcome.applied()
            + ", \"detail\": " + Json.string(outcome.detail()) + "}";
    }
}
