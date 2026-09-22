package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The few numbers a reader needs before it can make sense of any other file in an index.
 *
 * <pre>
 * 0   termCount        int32
 * 4   docCount         int32
 * 8   hashSlots        int32
 * 12  formatVersion    int32
 * 16  averageDocLength float64
 * 24  bm25 k1          float64   version 1 and above
 * 32  bm25 b           float64   version 1 and above
 * 40  columnCount      int32     version 3 and above
 * 44  reserved         int32     version 3 and above; zero, and keeps the table 8-byte aligned
 * 48  column table     16 bytes x columnCount, in SegmentFiles.COLUMNS order:
 *       +0  length     int64     the column's size when the segment was committed
 *       +8  checksum   int64     CRC32C of the whole column
 * 48+16n metadata CRC  int64     CRC32C of [0, 48 + 16n)
 * </pre>
 *
 * <p>Big-endian throughout, which is the order every other file in a segment is written and
 * mapped in.</p>
 *
 * <h2>Why the BM25 pair lives here</h2>
 * <p>It is not a scoring preference an index is indifferent to. Changing {@code k1} or {@code b}
 * changes every score the corpus can produce, and with it every ranking and every threshold a
 * deployment has calibrated against those rankings. Recording the pair beside the bytes it was
 * written for is what lets an index refuse to open under a pair it was not built for, rather than
 * quietly answering differently.</p>
 *
 * <h2>Why the column checksums live here</h2>
 * <p>The metadata is already the segment's commit marker - it is published by rename once every
 * column is on the device - so a checksum recorded in it cannot become visible before the bytes
 * it vouches for. The table then carries a checksum of its own over everything ahead of it,
 * because a table of column checksums is worth exactly as much as the bytes holding it: the
 * counts at offsets 0 to 12 are what every column's geometry is derived from, and a flipped bit
 * there is as damaging as a flipped bit in a posting. See {@link LexicalSegmentIntegrity}.</p>
 *
 * <h2>What the version says</h2>
 * <p>The field occupies four bytes that were always written as zero, so an index written before
 * the pair was recorded reads as version 0 without any length guessing. Such an index was built by
 * a writer with 1.2 and 0.75 compiled into it, so that is exactly what it is taken to hold - which
 * is what lets an existing deployment keep opening its index.</p>
 *
 * <p>Versions 0 and 1 also carry a postings block header whose impact field is a bound computed
 * against the segment's own mean document length - which is not the mean a query over several
 * segments scores with, and is therefore not a bound at all once the segment is one of several.
 * Version 2 replaces that field with the largest frequency and the shortest document of the block,
 * from which a sound bound is computed at query time. A reader has to know which it is holding,
 * so the distinction is a version rather than a guess at the bytes.</p>
 *
 * <p>Version 3 adds the column table. An index at any earlier version carries no checksum and
 * cannot acquire one: writing a CRC over it now would vouch for bytes nothing has ever vouched
 * for, which is a claim this build is not in a position to make. It is therefore read at its own
 * version, left exactly as it is, and named as unprotected by
 * {@link PostingsIndex#verifyIntegrity(Path)}. It becomes a version 3 index the first time it is
 * rebuilt or compacted, because that writes a new segment rather than editing this one - so
 * there is no migration step and nothing to undo.</p>
 *
 * <p>One definition, read by {@link PostingsIndex} and written by both writers. It was three
 * copies of the same offsets, and a header that has to grow is the wrong thing to have three
 * copies of.</p>
 *
 * @param termCount        Distinct terms in the segment
 * @param docCount         Documents in the segment
 * @param hashSlots        Slots in the term hash table; a positive power of two
 * @param averageDocLength Mean document length, for BM25 normalisation
 * @param bm25             The pair this segment was built with and will only open under
 * @param formatVersion    The version the bytes on disk are at
 * @param columns          One entry per column for a version 3 segment, empty for anything older
 */
record IndexMeta(int termCount, int docCount, int hashSlots, double averageDocLength,
                 Bm25Parameters bm25, int formatVersion,
                 List<LexicalSegmentIntegrity.ColumnChecksum> columns) {

    /** The metadata file's name within an index directory. */
    static final String FILE_NAME = "index.meta";

    /** Before the BM25 pair was recorded. */
    private static final int VERSION_WITHOUT_BM25 = 0;

    /** With the BM25 pair appended, and a block impact bound baked in at build time. */
    private static final int VERSION_WITH_BM25 = 1;

    /** With block headers carrying the largest frequency and the shortest document instead. */
    static final int VERSION_WITH_STATISTIC_FREE_BLOCKS = 2;

    /** With a CRC32C per column and one over this file, which is the current format. */
    static final int VERSION_WITH_COLUMN_CHECKSUMS = 3;

    /** Bytes up to and including the average document length, present in every version. */
    private static final int HEADER_BYTES = 24;

    /** Bytes of a version 1 or 2 file: the header and the BM25 pair. */
    static final int BYTES_WITH_BM25 = 40;

    /** Offset of the column count. */
    private static final int COLUMN_COUNT_OFFSET = 40;

    /** Offset of the first column table entry. */
    private static final int COLUMN_TABLE_OFFSET = 48;

    /** Bytes per column table entry: a length and a checksum. */
    private static final int COLUMN_ENTRY_BYTES = 16;

    /**
     * An upper bound on the column table, so a corrupt count cannot ask for a huge allocation.
     *
     * <p>Generous rather than exact: the point is to reject a length field that was never
     * written by anything, not to pin the format to today's six columns.</p>
     */
    private static final int MAX_COLUMNS = 64;

    /**
     * Bytes of a version 3 file holding the columns this build writes.
     *
     * <p>A bound rather than a constant of the format: the table is sized by the count in the
     * file, so a segment written with a different set of columns is measured against its own
     * count and refused for the difference rather than misread.</p>
     */
    static final int BYTES_WITH_COLUMN_CHECKSUMS = checksummedBytes(SegmentFiles.COLUMNS.size());

    /**
     * Validates the record's components and takes an unshareable copy of the column table.
     *
     * @throws NullPointerException if the BM25 pair or the column list is null
     */
    IndexMeta {
        Objects.requireNonNull(bm25, "bm25 cannot be null");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns cannot be null"));
    }

    /**
     * Reads an index's metadata.
     *
     * @param directory The index directory
     * @return What the metadata says the index is
     * @throws IOException if the file is absent, truncated, at an unreadable version, or no
     *                     longer matches the checksum it carries over itself
     */
    static IndexMeta read(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        return parse(file, Files.readAllBytes(file));
    }

    /**
     * Interprets metadata bytes that have already been read.
     *
     * <p>Separate from {@link #read} so a verification pass can distinguish a file it could not
     * read from one it read and did not believe: the first is a fault in the device, the second
     * is damage in the index, and a checking tool has to report them differently.</p>
     *
     * @param file  The file the bytes came from, for the messages
     * @param bytes The whole file
     * @return What the metadata says the index is
     * @throws IOException if the bytes are not metadata this build can believe
     */
    static IndexMeta parse(Path file, byte[] bytes) throws IOException {
        if (bytes.length < HEADER_BYTES) {
            throw new IOException(file + " is " + bytes.length + " bytes, too short to be "
                + "index metadata; the index is truncated and has to be rebuilt");
        }
        ByteBuffer view = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int version = view.getInt(12);
        return new IndexMeta(view.getInt(0), view.getInt(4), view.getInt(8), view.getDouble(16),
            readBm25(view, bytes.length, version, file),
            version, readColumns(view, bytes.length, version, file));
    }

    /** The BM25 pair, defaulted for a version that predates it and validated for one that does. */
    private static Bm25Parameters readBm25(ByteBuffer view, int size, int version, Path file)
            throws IOException {
        if (version == VERSION_WITHOUT_BM25) {
            return Bm25Parameters.DEFAULT;
        }
        if (version < VERSION_WITH_BM25 || version > VERSION_WITH_COLUMN_CHECKSUMS) {
            throw new IOException(file + " is metadata format version " + version + ", which this "
                + "build cannot read; it was written by a newer version of the engine");
        }
        if (size < BYTES_WITH_BM25) {
            throw new IOException(file + " records BM25 parameters but is only " + size
                + " bytes; the index is truncated and has to be rebuilt");
        }
        double k1 = view.getDouble(HEADER_BYTES);
        double b = view.getDouble(HEADER_BYTES + Double.BYTES);
        try {
            return new Bm25Parameters(k1, b);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + " records BM25 k1=" + k1 + ", b=" + b
                + ", which is not a usable pair; the index is corrupt and has to be rebuilt", e);
        }
    }

    /**
     * The column table, after checking that this file still digests to the value it records.
     *
     * <p>Checked before any entry is believed, because the entries are what the columns will be
     * measured against; and the same digest covers the counts ahead of them, which is what every
     * column's geometry is derived from.</p>
     */
    private static List<LexicalSegmentIntegrity.ColumnChecksum> readColumns(
            ByteBuffer view, int size, int version, Path file) throws IOException {
        if (version < VERSION_WITH_COLUMN_CHECKSUMS) {
            return List.of();
        }
        if (size < COLUMN_TABLE_OFFSET) {
            throw truncated(file, size, COLUMN_TABLE_OFFSET);
        }
        int count = view.getInt(COLUMN_COUNT_OFFSET);
        if (count < 0 || count > MAX_COLUMNS) {
            throw new IOException(file + " declares " + count + " checksummed column(s), which "
                + "is not a count any writer of this format produces; the metadata is corrupt "
                + "and the index has to be restored or rebuilt");
        }
        int covered = checksummedBytes(count) - Long.BYTES;
        if (size < covered + Long.BYTES) {
            throw truncated(file, size, covered + Long.BYTES);
        }
        requireOwnChecksum(view, file, covered);
        return columnTable(view, count);
    }

    /** Reads the table itself, once the bytes holding it have been vouched for. */
    private static List<LexicalSegmentIntegrity.ColumnChecksum> columnTable(ByteBuffer view,
                                                                           int count) {
        List<LexicalSegmentIntegrity.ColumnChecksum> table = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int at = COLUMN_TABLE_OFFSET + i * COLUMN_ENTRY_BYTES;
            String name = i < SegmentFiles.COLUMNS.size()
                ? SegmentFiles.COLUMNS.get(i) : "column " + i;
            table.add(new LexicalSegmentIntegrity.ColumnChecksum(
                name, view.getLong(at), view.getLong(at + Long.BYTES)));
        }
        return List.copyOf(table);
    }

    /** Refuses metadata whose own bytes no longer digest to the value written over them. */
    private static void requireOwnChecksum(ByteBuffer view, Path file, int covered)
            throws IOException {
        long stored = view.getLong(covered);
        long actual = IndexChecksum.of(view.array(), 0, covered);
        if (stored != actual) {
            throw new IOException(file + " records a checksum of 0x" + Long.toHexString(stored)
                + " over its first " + covered + " bytes but they digest to 0x"
                + Long.toHexString(actual) + ", so the counts and column checksums in it cannot "
                + "be trusted; restore the segment from a backup or rebuild it");
        }
    }

    /** Describes metadata that ends before the field being read. */
    private static IOException truncated(Path file, int size, int needed) {
        return new IOException(file + " is " + size + " bytes where its own format needs "
            + needed + "; the metadata is truncated and the index has to be restored or rebuilt");
    }

    /** How long a version 3 file holding this many columns is. */
    private static int checksummedBytes(int columns) {
        return COLUMN_TABLE_OFFSET + columns * COLUMN_ENTRY_BYTES + Long.BYTES;
    }

    /**
     * Metadata for an index this build has just written, which is always the current format.
     *
     * <p>A writer has no business choosing a version: the bytes it emits decide which one it is,
     * and letting the two be set separately is how they come to disagree. The column table is
     * attached separately by {@link SegmentFiles#commit}, which is the only point at which every
     * column exists and is on the device.</p>
     *
     * @param termCount        Distinct terms written
     * @param docCount         Documents written
     * @param hashSlots        Slots in the term hash table
     * @param averageDocLength Mean document length
     * @param bm25             The pair the segment was scored for
     * @return Metadata at the current version, with no column table yet
     */
    static IndexMeta current(int termCount, int docCount, int hashSlots, double averageDocLength,
                             Bm25Parameters bm25) {
        return new IndexMeta(termCount, docCount, hashSlots, averageDocLength, bm25,
            VERSION_WITH_COLUMN_CHECKSUMS, List.of());
    }

    /**
     * The same metadata, carrying the digests of the columns it describes.
     *
     * @param digests One entry per column, in {@link SegmentFiles#COLUMNS} order
     * @return A copy with the column table attached
     */
    IndexMeta withColumns(List<LexicalSegmentIntegrity.ColumnChecksum> digests) {
        return new IndexMeta(termCount, docCount, hashSlots, averageDocLength, bm25,
            formatVersion, digests);
    }

    /**
     * Whether this segment's columns are covered by checksums at all.
     *
     * @return true for a version 3 segment, false for one written before they existed
     */
    boolean checksummed() {
        return formatVersion >= VERSION_WITH_COLUMN_CHECKSUMS;
    }

    /**
     * Whether the postings blocks carry a bound baked in against this segment's own statistics.
     *
     * <p>Such a bound cannot be trusted by a scorer using collection-wide statistics, so a reader
     * of one of these has to fall back to a bound that depends on no statistics at all.</p>
     *
     * @return true for a segment written before version 2
     */
    boolean hasBakedInBlockBounds() {
        return formatVersion < VERSION_WITH_STATISTIC_FREE_BLOCKS;
    }

    /**
     * Publishes this metadata, which is what makes the index beside it readable.
     *
     * <h2>Why it is renamed into place rather than written over</h2>
     * <p>A reader takes every other file's shape from these bytes, so this file is the index's
     * commit marker whether or not it is called one. Opening the live file for writing
     * truncates it, and between that truncation and the last byte reaching the device there is a
     * window in which the index on disk has no metadata at all - a window a rewrite of an
     * existing index spends with a perfectly good index behind it. Assembling the replacement
     * beside it and renaming closes that window: the index is described by the old bytes or by
     * the new ones, never by none.</p>
     *
     * @param directory The index directory
     * @throws IOException           if the metadata cannot be made durable under its name
     * @throws IllegalStateException if a current-format segment is published without the column
     *                               table, which would leave its columns unprotected while
     *                               claiming a version that protects them
     */
    void write(Path directory) throws IOException {
        if (checksummed() && columns.size() != SegmentFiles.COLUMNS.size()) {
            throw new IllegalStateException("A version " + formatVersion + " segment must record "
                + SegmentFiles.COLUMNS.size() + " column checksum(s) and this one has "
                + columns.size() + "; SegmentFiles.commit attaches them and is the only caller "
                + "that may publish metadata");
        }
        Durability.FILE_SYSTEM.replace(directory.resolve(FILE_NAME), encode());
    }

    /** The file's bytes, big-endian, with the checksum over them written last. */
    private byte[] encode() {
        int size = checksummed() ? checksummedBytes(columns.size()) : BYTES_WITH_BM25;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        out.putInt(0, termCount).putInt(4, docCount).putInt(8, hashSlots);
        out.putInt(12, formatVersion).putDouble(16, averageDocLength);
        out.putDouble(HEADER_BYTES, bm25.k1()).putDouble(HEADER_BYTES + Double.BYTES, bm25.b());
        if (!checksummed()) {
            return out.array();
        }
        out.putInt(COLUMN_COUNT_OFFSET, columns.size());
        for (int i = 0; i < columns.size(); i++) {
            int at = COLUMN_TABLE_OFFSET + i * COLUMN_ENTRY_BYTES;
            out.putLong(at, columns.get(i).length());
            out.putLong(at + Long.BYTES, columns.get(i).checksum());
        }
        int covered = size - Long.BYTES;
        out.putLong(covered, IndexChecksum.of(out.array(), 0, covered));
        return out.array();
    }
}
