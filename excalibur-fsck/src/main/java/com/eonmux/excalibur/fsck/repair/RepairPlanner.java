package com.eonmux.excalibur.fsck.repair;

import com.eonmux.excalibur.fsck.check.CatalogCheck;
import com.eonmux.excalibur.fsck.check.CatalogRecord;
import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.ConsistencyFinding;
import com.eonmux.excalibur.fsck.check.CrossFileChecks;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.LexicalStoreCheck;
import com.eonmux.excalibur.store.RoutingDirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a survey into the repairs that follow from it, and the questions that do not.
 *
 * <p>Nothing here writes; planning and applying are separate so the plan can be printed,
 * refused, and printed again identically by a run that does apply it.</p>
 */
public final class RepairPlanner {

    /** The lexical store's manifest, relative to the data directory. */
    private static final String LEXICAL_MANIFEST =
        FileClassifier.LEXICAL_STORE_DIRECTORY + "/" + LexicalStoreCheck.MANIFEST;

    /** The files a repair never moves aside on its own judgement. */
    private static final List<String> IRREPLACEABLE =
        List.of(CatalogCheck.FILE_NAME, RoutingDirectory.FILE_NAME, LEXICAL_MANIFEST);

    private RepairPlanner() {
        throw new AssertionError("Utility class");
    }

    /**
     * Works out what can safely be done about what the survey found.
     *
     * @param scan What the survey found
     * @return The plan
     */
    public static RepairPlan plan(DataDirectoryScan scan) {
        List<RepairAction> actions = new ArrayList<>();
        List<String> operator = new ArrayList<>();
        // Findings first, so that a file which is both damaged in itself and orphaned from
        // the segment it belongs to is moved aside rather than patched up in place. Trimming
        // the torn tail of a log whose segment is gone would leave a tidy log of records
        // that can never be replayed into anything.
        for (ConsistencyFinding finding : scan.findings()) {
            addFindingAction(scan, finding, actions, operator);
        }
        for (FileCheck file : scan.files()) {
            addFileAction(scan, file, actions, operator);
        }
        return new RepairPlan(actions, operator);
    }

    /**
     * Decides what to do about one file's verdict.
     *
     * @param scan     The survey
     * @param file     The verdict
     * @param actions  Where safe repairs are collected
     * @param operator Where questions for a human are collected
     */
    private static void addFileAction(DataDirectoryScan scan, FileCheck file,
                                      List<RepairAction> actions, List<String> operator) {
        if (file.status() == CheckStatus.UNSEALED
                && FileClassifier.classify(file.relative()) == FileKind.WRITE_AHEAD_LOG) {
            // Not if the log is already being moved aside: see plan() for why the findings
            // are considered first.
            if (!alreadyPlanned(actions, file.relative())) {
                actions.add(trimLog(file));
            }
            return;
        }
        if (file.status() == CheckStatus.CORRUPT
                && alreadyPlanned(actions, file.relative())) {
            return;
        }
        if (file.status() == CheckStatus.UNREADABLE) {
            // Two causes share this status: a read the operating system refused, and a format
            // version this build does not know. Neither says the bytes are damaged, so neither
            // is moved aside; the detail says which it was.
            operator.add(file.relative() + " could not be read far enough to judge, so nothing "
                + "here knows whether anything is wrong with it: " + file.detail() + ". Settle "
                + "what stopped the read - the device, the file's permissions, or a newer "
                + "build's format - before deciding anything about its contents");
            return;
        }
        if (file.status() != CheckStatus.CORRUPT) {
            return;
        }
        if (IRREPLACEABLE.contains(file.relative())) {
            operator.add(irreplaceableAdvice(file));
            return;
        }
        if (file.needsOperator()) {
            operator.add(unrepairableAdvice(file));
            return;
        }
        actions.add(quarantineCorrupt(scan, file));
    }

    /**
     * Reports whether some action already acts on a file.
     *
     * @param actions  What is planned so far
     * @param relative The file's path relative to the data directory
     * @return true if the file is already spoken for
     */
    private static boolean alreadyPlanned(List<RepairAction> actions, String relative) {
        return actions.stream().anyMatch(action -> action.relative().equals(relative));
    }

    /**
     * Phrases the decision a damaged catalog or routing directory forces on a human.
     *
     * @param file The verdict
     * @return What to weigh
     */
    private static String irreplaceableAdvice(FileCheck file) {
        if (LEXICAL_MANIFEST.equals(file.relative())) {
            // Moving it aside is worse than leaving it: a store with no manifest opens as a new,
            // empty one, and an empty manifest is a complete one, so the node would then delete
            // every segment it does not name - which is all of them.
            return LEXICAL_MANIFEST + " is damaged: " + file.detail() + ". This is not moved "
                + "aside automatically: the node refuses to open a manifest it cannot show to "
                + "be whole, but a store without one opens as a new, empty index and deletes "
                + "every segment the empty manifest does not name. Every segment is still on "
                + "disk; restore the manifest from a backup, or remove the whole "
                + FileClassifier.LEXICAL_STORE_DIRECTORY + " directory and rebuild the keyword "
                + "index by reindexing";
        }
        if (CatalogCheck.FILE_NAME.equals(file.relative())) {
            return "catalog.dat is damaged: " + file.detail() + ". This is not moved aside "
                + "automatically. The segment files are rediscovered from the directory on "
                + "start-up, but every segment's lifecycle state, integrated offset, source "
                + "and epochs live only here, and a node that starts without them comes up "
                + "looking like a first start. Restore it from a backup if you have one";
        }
        return "routing.dir is damaged: " + file.detail() + ". This is not moved aside "
            + "automatically and cannot be rebuilt: which hash slot belongs to which segment "
            + "after a split is not recoverable from the file names, which is the reason the "
            + "directory is written down at all. Restore it from a backup";
    }

    /**
     * Phrases damage that a check has established no safe repair follows from.
     *
     * <p>The check that found it said so; nothing here re-derives why. What this adds is
     * the one thing the planner knows and the check does not: that the alternative on offer
     * was to move the whole file aside, and that doing so would set aside everything sound
     * in it as well.</p>
     *
     * @param file The verdict
     * @return What to weigh
     */
    private static String unrepairableAdvice(FileCheck file) {
        if (FileClassifier.classify(file.relative()) == FileKind.HNSW_STAGED_DOCUMENT_MAP) {
            return file.relative() + " is damaged: " + file.detail() + ". It is not moved "
                + "aside automatically, and it must not be: the graph beside it already holds "
                + "the compacted numbering, and without this file the node would serve that "
                + "graph through the pre-compaction mapping, attributing results to the wrong "
                + "documents rather than failing. Restore the segment's graph and both of its "
                + "mappings together from a backup, or rebuild the segment's vector index by "
                + "reindexing it";
        }
        if (FileClassifier.classify(file.relative()) == FileKind.WRITE_AHEAD_LOG) {
            return file.relative() + " is damaged in a way no repair here can act on: "
                + file.detail() + ". It is not moved aside automatically, and it must not be: "
                + "the sound records behind the damage were fsynced and acknowledged before "
                + "their writers were told the write had happened, and nothing rebuilds a log "
                + "the way a reindex rebuilds an index. Setting it aside loses those writes, "
                + "and trimming past the damage loses them too. Restore the log from a backup "
                + "or a replica, or accept the loss deliberately by moving it aside by hand";
        }
        return file.relative() + " is damaged in a way no repair here can act on: "
            + file.detail() + ". It is not moved aside automatically, because the only "
            + "repair that would apply sets the whole file aside and most of what is in it "
            + "is still sound. Verify the file against a replica or a backup, and rebuild "
            + "or restore it rather than letting a compaction rewrite it - a rewrite copies "
            + "what the slot table reaches, so anything it no longer reaches is dropped";
    }

    /**
     * Builds the action that moves a damaged file aside.
     *
     * @param scan The survey
     * @param file The verdict
     * @return The action
     */
    private static RepairAction quarantineCorrupt(DataDirectoryScan scan, FileCheck file) {
        FileKind kind = FileClassifier.classify(file.relative());
        boolean primary = kind == FileKind.SEGMENT_DATA;
        String rationale = isLexicalSegment(kind, file.relative())
            ? "Every byte is preserved under the quarantine directory. The node keeps a "
                + "lexical segment it cannot open out of every query whether or not it is "
                + "moved, and names it in /v1/stats; while the segment's key table beside it "
                + "is intact, the next compaction rebuilds it from the chunk text in storage, "
                + "and /v1/stats says whether it can"
            : kind.rebuiltOnOpen()
            ? "Every byte is preserved under the quarantine directory. The node would rebuild "
                + "this file from the segment's live entries on its next open whether or not "
                + "it is moved; moving it aside only means the damaged copy is no longer read "
                + "first and rejected"
            : kind.discardedOnOpen()
            ? "Every byte is preserved under the quarantine directory. The node would read "
                + "this history as empty on its next start whether or not it is moved, and "
                + "report each source from its next run; what is lost either way is how the "
                + "last crawls ended, never indexed data"
            : primary
            ? "Every byte is preserved under the quarantine directory, so this is reversible "
                + "by hand. It is still a removal of primary data from the store: a damaged "
                + "segment cannot be served, because a read of the damaged record fails "
                + "rather than returning wrong bytes, and the rest of the file goes with it. "
                + "Restore from a backup or a replica if one exists"
            : "Every byte is preserved under the quarantine directory. This file is derived "
                + "from the segment data and can be rebuilt by reindexing";
        return RepairAction.onFile(RepairKind.QUARANTINE_CORRUPT_FILE, file.path(),
            file.relative(),
            "move " + file.relative() + " out of " + scan.dataDirectory()
                + " into the quarantine directory, deleting nothing",
            rationale);
    }

    /**
     * Reports whether a verdict is about one of the lexical store's segment directories.
     *
     * <p>The scan reports each segment as its directory, and the files beside it - the key
     * table, tombstones, the manifest - under their own names, which all carry a suffix or are
     * the manifest.</p>
     *
     * @param kind     What the file was classified as
     * @param relative Its path relative to the data directory
     * @return true for a segment directory
     */
    private static boolean isLexicalSegment(FileKind kind, String relative) {
        if (kind != FileKind.LEXICAL_STORE_PART || LEXICAL_MANIFEST.equals(relative)) {
            return false;
        }
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        return name.indexOf('.') < 0;
    }

    /**
     * Builds the action that disclaims a torn log tail.
     *
     * @param file The verdict
     * @return The action
     */
    private static RepairAction trimLog(FileCheck file) {
        return RepairAction.onFile(RepairKind.TRIM_TORN_LOG_TAIL, file.path(),
            file.relative(),
            "clear the opcode byte of the torn record at the end of " + file.relative()
                + ", so the scan reads that offset as never written",
            "This is the same one-byte write the log itself makes when an append fails its "
                + "fsync, and the scan has already established that no readable record "
                + "follows the tear - so the only records it disclaims are ones no caller "
                + "was ever told had succeeded. The torn record's remaining bytes are left "
                + "in place until another append writes over them");
    }

    /**
     * Decides what to do about one cross-file finding.
     *
     * @param scan     The survey
     * @param finding  The finding
     * @param actions  Where safe repairs are collected
     * @param operator Where questions for a human are collected
     */
    private static void addFindingAction(DataDirectoryScan scan, ConsistencyFinding finding,
                                         List<RepairAction> actions, List<String> operator) {
        switch (finding.kind()) {
            case "catalog-names-missing-segment" ->
                dropRecord(scan, finding, actions);
            case "index-without-segment" ->
                quarantineByRelative(scan, finding.subject(), actions,
                    RepairKind.QUARANTINE_ORPHANED_INDEX,
                    "It belongs to a segment that is not in this directory, so nothing will "
                        + "open it and nothing will rebuild it in place. Every byte is "
                        + "preserved under the quarantine directory - which matters most for "
                        + "a write-ahead log, whose unintegrated records are the only copy "
                        + "of themselves left");
            case "staging-leftover" ->
                quarantineByRelative(scan, finding.subject(), actions,
                    RepairKind.QUARANTINE_STAGING_LEFTOVER,
                    "Nothing reads a staged file: it becomes state only by being renamed over "
                        + "its destination, and it was not. Every byte is preserved under the "
                        + "quarantine directory");
            case "routing-names-missing-segment", "lexical-segment-missing",
                 "lexical-column-missing" ->
                operator.add(finding.subject() + ": " + finding.detail());
            case CrossFileChecks.PENDING_MAPPING -> {
                // Named rather than left to the default, because this is the one finding
                // about a file that must never become a repair: the file is the state the
                // node's own recovery completes a compaction from.
            }
            default -> {
                // A warning with no action: reported by the survey, nothing to decide.
            }
        }
    }

    /**
     * Builds the action that marks a catalog row purged.
     *
     * @param scan    The survey
     * @param finding The finding naming the missing segment
     * @param actions Where the action is collected
     */
    private static void dropRecord(DataDirectoryScan scan, ConsistencyFinding finding,
                                   List<RepairAction> actions) {
        CatalogRecord row = rowFor(scan, finding.subject());
        if (row == null) {
            return;
        }
        Path catalog = scan.dataDirectory().resolve(CatalogCheck.FILE_NAME);
        actions.add(new RepairAction(RepairKind.DROP_CATALOG_RECORD, catalog,
            CatalogCheck.FILE_NAME, row.segmentId(),
            "mark catalog row " + row.index() + ", for segment " + row.segmentId()
                + " (" + finding.subject() + "), as purged",
            "The segment file the row names is not in the directory, so the row is a claim "
                + "the store cannot honour whatever happens next. Marking it purged is what "
                + "the catalog does for a segment that has been removed: the row stays in the "
                + "file and is reused by the next registration, so nothing is cut out and no "
                + "other row moves"));
    }

    /**
     * Finds the catalog row that names a file.
     *
     * @param scan     The survey
     * @param fileName The segment file's name
     * @return The row, or null if none names it
     */
    private static CatalogRecord rowFor(DataDirectoryScan scan, String fileName) {
        for (CatalogRecord row : scan.catalogRows()) {
            if (!row.expectsFileOnDisk()) {
                continue;
            }
            String named = row.fileName() != null
                ? row.fileName() : "segment_" + row.segmentId() + ".dat";
            if (named.equals(fileName)) {
                return row;
            }
        }
        return null;
    }

    /**
     * Builds a quarantine action for a file named by its relative path.
     *
     * @param scan      The survey
     * @param relative  The file's path relative to the data directory
     * @param actions   Where the action is collected
     * @param kind      What sort of quarantine this is
     * @param rationale Why it is safe
     */
    private static void quarantineByRelative(DataDirectoryScan scan, String relative,
                                             List<RepairAction> actions, RepairKind kind,
                                             String rationale) {
        Path file = scan.dataDirectory().resolve(relative);
        if (alreadyPlanned(actions, relative)) {
            return;
        }
        actions.add(RepairAction.onFile(kind, file, relative,
            "move " + relative + " out of " + scan.dataDirectory()
                + " into the quarantine directory, deleting nothing",
            rationale));
    }
}
