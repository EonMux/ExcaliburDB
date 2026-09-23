package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IntegrityReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verdicts on the files a lexical segment keeps beside its directory, for a checker that must
 * not open the store.
 *
 * <p>Each segment the manifest names has a key table - its names, their offsets, a lookup table
 * from name to document, and the checksums of all three - and, once anything in it has been
 * erased or replaced, a tombstone file. The store verifies both when it opens; this reaches the
 * same verdicts by reading the same bytes the same way, and writes nothing: a lookup table that
 * does not match its checksum is reported corrupt here, where the store would rebuild it.</p>
 *
 * <p>The suffixes are public so a checker can say what kind of file a verdict is about without
 * restating this format's names.</p>
 */
public final class LexicalSidecars {

    /** Suffix of a segment's names. */
    public static final String NAMES = SegmentKeys.KEYS_SUFFIX;

    /** Suffix of the offsets into a segment's names. */
    public static final String OFFSETS = SegmentKeys.INDEX_SUFFIX;

    /** Suffix of a segment's lookup table from name to document, derived from its names. */
    public static final String LOOKUP_TABLE = SegmentKeys.MAP_SUFFIX;

    /** Suffix of the checksums a segment's key table was written with. */
    public static final String KEY_CHECKSUMS = KeyChecksums.SUFFIX;

    /** Suffix of a segment's tombstones. */
    public static final String TOMBSTONES = TombstoneFile.SUFFIX;

    private LexicalSidecars() {
        throw new AssertionError("Utility class");
    }

    /**
     * Verifies the files beside one segment without changing anything.
     *
     * <p>The key table's verdicts come first - names, offsets, lookup table, then its checksum
     * file - followed by the tombstones' when the segment has any. A key table written before
     * checksums has no checksum file and is held to its structure alone; one whose checksum file
     * cannot be read has a verdict on that file only, since nothing was checked against it.</p>
     *
     * @param directory The store directory
     * @param entry     The segment, as the manifest records it
     * @return The verdicts, one per file judged
     * @throws NullPointerException if either argument is null
     */
    public static List<IntegrityReport> inspect(Path directory, SegmentManifest.Entry entry) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(entry, "entry");
        List<IntegrityReport> reports = new ArrayList<>(
            KeyChecksums.inspect(directory, entry.name(), entry.docCount()));
        IntegrityReport marks = TombstoneFile.inspect(
            TombstoneFile.of(directory, entry.name()), entry.docCount());
        if (marks != null) {
            reports.add(marks);
        }
        return List.copyOf(reports);
    }
}
