package com.eonmux.excalibur.fsck.check;

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
                "the store directory exists but names no segments, so nothing in it can be "
                    + "opened; a store that was never written looks the same", -1),
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
            checks.add(IntegrityReports.from(PostingsIndex.verifyIntegrity(segment),
                segment, segmentRelative, null, -1));
        }
        return checks;
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
    }
}
