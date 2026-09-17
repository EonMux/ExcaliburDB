package io.excalibur.retrieval.lexical.native_;

import io.excalibur.core.integrity.IndexChecksum;
import io.excalibur.core.integrity.IntegrityReport;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The checksums a lexical segment carries over its columns, and the rules for checking them.
 *
 * <h2>Why a length was not enough</h2>
 * <p>{@link SegmentGeometry} measures every column against the counts in {@code index.meta}, and
 * its own comment is explicit that this catches truncation and nothing else: bytes that were
 * rewritten rather than lost pass it. A flipped bit in {@code post.dat} leaves the file the
 * length the metadata describes, leaves every block header addressing a region inside it, and
 * changes only the numbers the packed gaps decode to. The segment opens, the query runs, and the
 * answer is wrong with nothing anywhere to say so - which for a search engine is the worst
 * failure there is, because it is indistinguishable from a correct one.</p>
 *
 * <p>So every column additionally carries a CRC32C of its whole contents, recorded when the
 * segment is committed and checked before it is mapped. A column that fails is named and
 * refused. It is never mapped, never partially read, and never treated as absent - a segment
 * that loaded as empty would turn one bad sector into a corpus that has apparently vanished.</p>
 *
 * <h2>Where the checksums live</h2>
 * <p>In {@code index.meta}, beside the counts they belong with, because the metadata is already
 * the segment's commit marker: it is published by rename after every column is on the device, so
 * a checksum recorded in it cannot become visible before the bytes it covers. The metadata then
 * carries a checksum of its own, over everything ahead of it, because a table of column
 * checksums is only worth as much as the bytes holding it. See {@link IndexMeta} for the
 * layout.</p>
 *
 * <h2>Why there is no seal state</h2>
 * <p>The HNSW graph and the node mapping are mutated in place and so have to record whether a
 * writer was holding them. A lexical segment is written once, committed, and never modified
 * again - erasure and compaction both produce a new segment rather than editing one - so every
 * segment at this format version is sealed by construction and there is no third state to
 * report.</p>
 *
 * <h2>What it costs</h2>
 * <p>One sequential pass over the segment, on the open that first puts it into service and on
 * the store open that surveys it. That is memory bandwidth rather than CPU time - CRC32C is a
 * hardware instruction on every CPU this runs on - and it is paid once per segment per process,
 * against a cost of serving wrong answers that has no upper bound.</p>
 */
final class LexicalSegmentIntegrity {

    /** Name used when a damaged segment is refused or reported. */
    static final String FORMAT = "lexical segment";

    private LexicalSegmentIntegrity() {
        throw new AssertionError("Utility class");
    }

    /**
     * One column's length and the digest of its contents, as recorded in the metadata.
     *
     * <p>The name is not stored in the file: the table is positional, in
     * {@link SegmentFiles#COLUMNS} order, and the name is attached when it is read back. A
     * segment whose table is a different length is therefore refused rather than matched up
     * column by column, because there is no way to know which column went missing.</p>
     *
     * @param column   The column's file name within the segment directory
     * @param length   How many bytes the column held when it was committed
     * @param checksum The CRC32C of those bytes, as an unsigned value in a long
     */
    record ColumnChecksum(String column, long length, long checksum) {

        /**
         * Validates the record's components.
         *
         * @throws NullPointerException     if the column name is null
         * @throws IllegalArgumentException if the length is negative
         */
        ColumnChecksum {
            Objects.requireNonNull(column, "column cannot be null");
            if (length < 0) {
                throw new IllegalArgumentException(
                    "Column " + column + " cannot have a negative length: " + length);
            }
        }
    }

    /**
     * Digests every column of a segment whose files are already on the device.
     *
     * <p>Called from {@link SegmentFiles#commit} after each column has been forced, so the
     * digest describes the bytes the device actually holds rather than the ones a writer
     * intended.</p>
     *
     * @param directory The segment directory, holding every column
     * @return One entry per column, in {@link SegmentFiles#COLUMNS} order
     * @throws IOException if a column cannot be read
     */
    static List<ColumnChecksum> digest(Path directory) throws IOException {
        List<ColumnChecksum> digests = new ArrayList<>(SegmentFiles.COLUMNS.size());
        for (String column : SegmentFiles.COLUMNS) {
            Path file = directory.resolve(column);
            long length = Files.size(file);
            digests.add(new ColumnChecksum(column, length, checksumOf(file, length)));
        }
        return List.copyOf(digests);
    }

    /**
     * Refuses a segment whose columns no longer digest to what its metadata records.
     *
     * @param directory The segment directory
     * @param meta      That segment's metadata, already read
     * @throws IOException if any column is damaged, naming the column
     */
    static void requireIntact(Path directory, IndexMeta meta) throws IOException {
        inspect(directory, meta).requireIntact();
    }

    /**
     * Reaches a verdict on a segment whose metadata has already been read, refusing nothing.
     *
     * @param directory The segment directory
     * @param meta      That segment's metadata
     * @return What the check concluded
     */
    static IntegrityReport inspect(Path directory, IndexMeta meta) {
        Path metaFile = directory.resolve(IndexMeta.FILE_NAME);
        if (!meta.checksummed()) {
            return IntegrityReport.legacy(metaFile, FORMAT, meta.formatVersion());
        }
        List<ColumnChecksum> recorded = meta.columns();
        if (recorded.size() != SegmentFiles.COLUMNS.size()) {
            return IntegrityReport.corrupt(metaFile, FORMAT, "it records checksums for "
                + recorded.size() + " column(s) where a segment of this format has "
                + SegmentFiles.COLUMNS.size() + ", so which column is missing cannot be known");
        }
        try {
            return inspectColumns(directory, recorded);
        } catch (IOException e) {
            return IntegrityReport.unreadable(directory, FORMAT, e.toString());
        }
    }

    /**
     * Verifies a segment on disk without mapping a column or opening the index.
     *
     * <p>This is what a checking tool calls. Each column is streamed past the digest through a
     * channel, so a segment too large to map, or too damaged to serve, can still be judged - and
     * nothing about the call puts the segment into service.</p>
     *
     * @param directory The segment directory to verify
     * @return What the check concluded
     * @throws NullPointerException if the directory is null
     */
    static IntegrityReport verify(Path directory) {
        Objects.requireNonNull(directory, "directory cannot be null");
        Path metaFile = directory.resolve(IndexMeta.FILE_NAME);
        if (!Files.isRegularFile(metaFile)) {
            return IntegrityReport.missing(metaFile, FORMAT);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(metaFile);
        } catch (IOException e) {
            return IntegrityReport.unreadable(metaFile, FORMAT, e.toString());
        }
        IndexMeta meta;
        try {
            meta = IndexMeta.parse(metaFile, bytes);
        } catch (IOException e) {
            return IntegrityReport.corrupt(metaFile, FORMAT, e.getMessage());
        }
        return inspect(directory, meta);
    }

    /**
     * Measures and digests each recorded column in turn, stopping at the first that fails.
     */
    private static IntegrityReport inspectColumns(Path directory, List<ColumnChecksum> recorded)
            throws IOException {
        long covered = 0;
        for (ColumnChecksum column : recorded) {
            Path file = directory.resolve(column.column());
            if (!Files.isRegularFile(file)) {
                return IntegrityReport.corrupt(file, FORMAT, "the metadata describes this "
                    + "column but the segment does not hold it");
            }
            long length = Files.size(file);
            if (length != column.length()) {
                return lengthMismatch(file, column.length(), length);
            }
            long actual = checksumOf(file, length);
            if (actual != column.checksum()) {
                return checksumMismatch(file, column, actual);
            }
            covered += length;
        }
        return IntegrityReport.intact(directory, FORMAT,
            covered + " bytes across " + recorded.size() + " column(s) verified");
    }

    /** The CRC32C of a whole column, streamed rather than mapped. */
    private static long checksumOf(Path file, long length) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return IndexChecksum.of(channel, 0, length);
        }
    }

    /** Describes a column that is no longer the size it was committed at. */
    private static IntegrityReport lengthMismatch(Path file, long recorded, long actual) {
        return IntegrityReport.corrupt(file, FORMAT, "the column is " + actual
            + " bytes where it was " + recorded + " when the segment was committed, so it has "
            + (actual < recorded ? "lost its tail" : "been written past its end"));
    }

    /** Describes a column of the right size whose bytes are no longer the ones committed. */
    private static IntegrityReport checksumMismatch(Path file, ColumnChecksum column,
                                                    long actual) {
        return IntegrityReport.corrupt(file, FORMAT, "the stored checksum over its "
            + column.length() + " bytes is 0x" + Long.toHexString(column.checksum())
            + " but they digest to 0x" + Long.toHexString(actual) + ", so the column kept its "
            + "length and lost its contents; every posting, term or length it decodes to is a "
            + "different number from the one that was indexed");
    }
}
