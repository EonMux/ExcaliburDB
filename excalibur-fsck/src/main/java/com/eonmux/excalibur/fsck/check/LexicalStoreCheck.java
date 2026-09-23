package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.retrieval.lexical.native_.DocumentErasureLog;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalSidecars;
import com.eonmux.excalibur.retrieval.lexical.native_.PostingsIndex;
import com.eonmux.excalibur.retrieval.lexical.native_.SegmentManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the native lexical store directory, usually {@code chunk-index}.
 *
 * <p>The manifest is the store's commit record: a reader takes the set of live segments from
 * it, so a segment it names and the directory does not hold is an index that cannot be
 * opened. {@link SegmentManifest#read} is public and writes nothing, and it is the one
 * judgement here: it refuses a manifest whose declared record count disagrees with its lines,
 * or, at the current version, whose bytes do not match the checksum on its end line, and the
 * store refuses exactly the same file. What it accepted is then reported by what its version
 * could prove - intact at the current version, whose checksum covers every line; unprotected
 * at version 2, whose count proves that no line was lost and nothing about the lines that are
 * there; unchecked at version 1, which carries neither. A manifest a newer build wrote is
 * unreadable rather than corrupt, since nothing shows its bytes are damaged.</p>
 *
 * <p>Each segment the manifest names is then verified by
 * {@link PostingsIndex#verifyIntegrity}, which streams every column past a digest without
 * mapping one or putting the segment into service. A segment written before that format
 * carried checksums comes back legacy-unprotected rather than sound.</p>
 *
 * <p>The files beside each named segment - its key table and its tombstones - are verified by
 * {@link LexicalSidecars#inspect} against the checksums they were written with, and the
 * erasure log by {@link DocumentErasureLog#inspect} line by line, each read exactly as the
 * store reads it. Damage to the names, their offsets, their checksums, the tombstones or the
 * erasure log is put to an operator rather than moved aside: the store already keeps a segment
 * whose names or marks it cannot trust out of every query, and a tombstone file or an erasure
 * log moved out of the way reads as nothing erased at all. The lookup table is derived from the
 * names, and the store rebuilds one that fails its checksum, so its damage is not.</p>
 */
public final class LexicalStoreCheck {

    /** Name this format goes by in the report. */
    public static final String FORMAT = "lexical store manifest";

    /** The manifest's file name inside the store directory. */
    public static final String MANIFEST = "manifest";

    private LexicalStoreCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the manifest and confirms that every segment it names is present and complete.
     *
     * @param directory The store directory
     * @param relative  Its path relative to the data directory
     * @return The verdict on the manifest and what is missing behind it
     */
    public static LexicalInspection check(Path directory, String relative) {
        Path manifest = directory.resolve(MANIFEST);
        String manifestRelative = relative + "/" + MANIFEST;
        if (!Files.exists(manifest)) {
            return new LexicalInspection(new FileCheck(manifest, manifestRelative, FORMAT, null,
                CheckStatus.MISSING,
                "the store directory holds files but no manifest naming its segments, so "
                    + "nothing in it can be opened: something was written here and the record "
                    + "of which segments are live is gone", -1),
                List.of(), List.of(), erasureLog(directory, relative));
        }
        SegmentManifest parsed;
        try {
            parsed = SegmentManifest.read(directory);
        } catch (SegmentManifest.UnsupportedVersionException e) {
            IntegrityReport unreadable = IntegrityReport.unsupportedVersion(manifest, FORMAT,
                e.version(), SegmentManifest.OLDEST_VERSION, SegmentManifest.VERSION);
            return new LexicalInspection(IntegrityReports.from(unreadable, manifest,
                manifestRelative, "v" + e.version(), sizeOf(manifest)), List.of(), List.of(),
                erasureLog(directory, relative));
        } catch (IOException e) {
            return new LexicalInspection(new FileCheck(manifest, manifestRelative, FORMAT, null,
                CheckStatus.CORRUPT, e.getMessage(), sizeOf(manifest)),
                List.of(), List.of(), erasureLog(directory, relative));
        }
        List<FileCheck> sidecars = new ArrayList<>(sidecarChecks(directory, relative, parsed));
        sidecars.addAll(erasureLog(directory, relative));
        return new LexicalInspection(
            manifestVerdict(manifest, manifestRelative, parsed),
            List.of(),
            segmentChecks(directory, relative, parsed),
            sidecars);
    }

    /**
     * Verifies the key table and tombstones beside every segment the manifest names.
     *
     * @param directory The store directory
     * @param relative  Its path relative to the data directory
     * @param parsed    The manifest as read
     * @return One verdict per file judged
     */
    private static List<FileCheck> sidecarChecks(Path directory, String relative,
                                                 SegmentManifest parsed) {
        List<FileCheck> checks = new ArrayList<>();
        for (SegmentManifest.Entry entry : parsed.entries()) {
            for (IntegrityReport report : LexicalSidecars.inspect(directory, entry)) {
                Path file = report.file();
                String name = file.getFileName().toString();
                boolean derived = name.endsWith(LexicalSidecars.LOOKUP_TABLE);
                checks.add(verdict(report, relative + "/" + name, !derived));
            }
        }
        return checks;
    }

    /**
     * Verifies the store's erasure log, if it has one.
     *
     * @param directory The store directory
     * @param relative  Its path relative to the data directory
     * @return The verdict, or nothing when there is no log
     */
    private static List<FileCheck> erasureLog(Path directory, String relative) {
        Path log = directory.resolve(DocumentErasureLog.FILE);
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        return List.of(verdict(DocumentErasureLog.inspect(log),
            relative + "/" + DocumentErasureLog.FILE, true));
    }

    /**
     * Converts a sidecar's verdict, marking damage that must be put to an operator.
     *
     * @param report        The verifier's verdict
     * @param relative      The file's path relative to the data directory
     * @param needsOperator Whether moving the file aside would do harm
     * @return The verdict in this tool's terms
     */
    private static FileCheck verdict(IntegrityReport report, String relative,
                                     boolean needsOperator) {
        FileCheck check = IntegrityReports.from(report, report.file(), relative, null,
            sizeOf(report.file()));
        return new FileCheck(check.path(), check.relative(), check.format(), check.version(),
            check.status(), check.detail(), check.sizeBytes(), needsOperator);
    }

    /**
     * Phrases what reading the manifest proved.
     *
     * @param manifest The manifest file
     * @param relative Its path relative to the data directory
     * @param parsed   The manifest as read
     * @return The verdict
     */
    private static FileCheck manifestVerdict(Path manifest, String relative,
                                             SegmentManifest parsed) {
        long size = sizeOf(manifest);
        String version = "v" + parsed.version();
        if (!parsed.verified()) {
            return new FileCheck(manifest, relative, FORMAT, version, CheckStatus.UNCHECKED,
                "this manifest predates the record count, so it cannot be shown to be all of "
                    + "itself: a segment it does not name and a segment named by a line that "
                    + "is no longer there are indistinguishable. The node rewrites it with a "
                    + "count and a checksum at its next write", size);
        }
        if (parsed.version() < SegmentManifest.VERSION) {
            return new FileCheck(manifest, relative, FORMAT, version,
                CheckStatus.LEGACY_UNPROTECTED,
                "the record count agrees with the " + parsed.entries().size() + " segments "
                    + "named, so no line was lost; this version carries no checksum, so the "
                    + "lines themselves are not verified. The node rewrites it with one at its "
                    + "next write", size);
        }
        return new FileCheck(manifest, relative, FORMAT, version, CheckStatus.INTACT,
            "the checksum on the end line matched every byte before it, and the record count "
                + "agrees with the " + parsed.entries().size() + " segments named", size);
    }

    /**
     * Verifies every segment the manifest names.
     *
     * @param directory The store directory
     * @param relative  Its path relative to the data directory
     * @param parsed    The manifest as read
     * @return One verdict per named segment
     */
    private static List<FileCheck> segmentChecks(Path directory, String relative,
                                                 SegmentManifest parsed) {
        List<FileCheck> checks = new ArrayList<>();
        for (SegmentManifest.Entry entry : parsed.entries()) {
            Path segment = directory.resolve(entry.name());
            String segmentRelative = relative + "/" + entry.name();
            IntegrityReport report = PostingsIndex.verifyIntegrity(segment);
            checks.add(IntegrityReports.from(namingTheFile(report, segment), segment,
                segmentRelative, null, -1));
        }
        return checks;
    }

    /**
     * Puts the name of the file a verdict is about into its detail.
     *
     * <p>A segment is reported as one directory, because it is written, verified and repaired
     * as one; the verifier's own verdict is about the column or metadata file that failed, and
     * without its name the report would say a segment is damaged without saying where.</p>
     *
     * @param report  The verifier's verdict
     * @param segment The segment directory the verdict will be reported under
     * @return The verdict, its detail prefixed with the file's name inside the segment
     */
    private static IntegrityReport namingTheFile(IntegrityReport report, Path segment) {
        Path file = report.file();
        if (file == null || file.equals(segment) || !file.startsWith(segment)) {
            return report;
        }
        return new IntegrityReport(file, report.format(), report.status(),
            segment.relativize(file) + ": " + report.detail());
    }

    /**
     * Reads a file's size, answering -1 rather than failing the survey.
     *
     * @param file The file
     * @return Its size in bytes, or -1
     */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * What checking a lexical store directory found.
     *
     * @param manifest What the manifest itself came to
     * @param findings What is inconsistent between the manifest and the directory
     * @param segments One verdict per segment the manifest names
     * @param sidecars One verdict per file beside the segments that was judged: each named
     *                 segment's key table and tombstones, and the erasure log
     */
    public record LexicalInspection(FileCheck manifest, List<ConsistencyFinding> findings,
                                    List<FileCheck> segments, List<FileCheck> sidecars) {

        /**
         * Canonical constructor, defensively copying every list.
         *
         * @throws NullPointerException if any component is null
         */
        public LexicalInspection {
            java.util.Objects.requireNonNull(manifest, "manifest");
            findings = List.copyOf(java.util.Objects.requireNonNull(findings, "findings"));
            segments = List.copyOf(java.util.Objects.requireNonNull(segments, "segments"));
            sidecars = List.copyOf(java.util.Objects.requireNonNull(sidecars, "sidecars"));
        }

        /**
         * Returns every verdict this inspection reached: the manifest's, each segment's, then
         * each sidecar's.
         *
         * @return The verdicts, in that order
         */
        public List<FileCheck> verdicts() {
            List<FileCheck> verdicts = new ArrayList<>(segments.size() + sidecars.size() + 1);
            verdicts.add(manifest);
            verdicts.addAll(segments);
            verdicts.addAll(sidecars);
            return List.copyOf(verdicts);
        }

        /**
         * Reports whether a file is already accounted for by one of these verdicts.
         *
         * <p>The manifest and each judged sidecar are their own verdicts, and a segment's
         * verdict covers every column inside that segment's directory, because the segment is
         * verified by streaming all of them. A file here that nothing judged - one belonging to
         * no segment the manifest names, a sidecar whose checksums could not be read, anything
         * at all when the manifest itself could not be - keeps its own verdict.</p>
         *
         * @param relative A file's path relative to the data directory, "/"-separated
         * @return true if a verdict here speaks for it
         */
        public boolean covers(String relative) {
            if (relative.equals(manifest.relative())) {
                return true;
            }
            if (sidecars.stream().anyMatch(sidecar -> relative.equals(sidecar.relative()))) {
                return true;
            }
            return segments.stream()
                .anyMatch(segment -> relative.startsWith(segment.relative() + "/"));
        }
    }
}
