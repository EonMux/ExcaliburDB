package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
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
 * opened. {@link SegmentManifest#read} is public and writes nothing, and it refuses a
 * manifest whose declared record count disagrees with its lines - which is the only
 * completeness evidence this format has. None of it is checksummed, and the verdict says
 * so.</p>
 *
 * <p>Each segment the manifest names is then verified by
 * {@link PostingsIndex#verifyIntegrity}, which streams every column past a digest without
 * mapping one or putting the segment into service. A segment written before that format
 * carried checksums comes back legacy-unprotected rather than sound.</p>
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
                List.of(), List.of());
        }
        SegmentManifest parsed;
        try {
            parsed = SegmentManifest.read(directory);
        } catch (IOException e) {
            return new LexicalInspection(new FileCheck(manifest, manifestRelative, FORMAT, null,
                CheckStatus.CORRUPT, e.getMessage(), sizeOf(manifest)),
                List.of(), List.of());
        }
        return new LexicalInspection(
            manifestVerdict(manifest, manifestRelative, parsed),
            List.of(),
            segmentChecks(directory, relative, parsed));
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
        if (!parsed.verified()) {
            return new FileCheck(manifest, relative, FORMAT, "v1", CheckStatus.UNCHECKED,
                "this manifest predates the record count, so it cannot be shown to be all of "
                    + "itself: a segment it does not name and a segment named by a line that "
                    + "is no longer there are indistinguishable", size);
        }
        return new FileCheck(manifest, relative, FORMAT, "v2", CheckStatus.LEGACY_UNPROTECTED,
            "the record count agrees with the " + parsed.entries().size() + " segments named, "
                + "so no line was lost; the format carries no checksum, so the lines "
                + "themselves are not verified", size);
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
     */
    public record LexicalInspection(FileCheck manifest, List<ConsistencyFinding> findings,
                                    List<FileCheck> segments) {

        /**
         * Canonical constructor, defensively copying both lists.
         *
         * @throws NullPointerException if any component is null
         */
        public LexicalInspection {
            java.util.Objects.requireNonNull(manifest, "manifest");
            findings = List.copyOf(java.util.Objects.requireNonNull(findings, "findings"));
            segments = List.copyOf(java.util.Objects.requireNonNull(segments, "segments"));
        }

        /**
         * Returns every verdict this inspection reached: the manifest's, then each segment's.
         *
         * @return The verdicts, in that order
         */
        public List<FileCheck> verdicts() {
            List<FileCheck> verdicts = new ArrayList<>(segments.size() + 1);
            verdicts.add(manifest);
            verdicts.addAll(segments);
            return List.copyOf(verdicts);
        }

        /**
         * Reports whether a file is already accounted for by one of these verdicts.
         *
         * <p>The manifest is its own verdict, and a segment's verdict covers every column
         * inside that segment's directory, because the segment is verified by streaming all
         * of them. A sidecar beside the segments - document keys, offsets, tombstones, the
         * erasure log - is covered by nothing here and keeps its own verdict.</p>
         *
         * @param relative A file's path relative to the data directory, "/"-separated
         * @return true if a verdict here speaks for it
         */
        public boolean covers(String relative) {
            if (relative.equals(manifest.relative())) {
                return true;
            }
            return segments.stream()
                .anyMatch(segment -> relative.startsWith(segment.relative() + "/"));
        }
    }
}
