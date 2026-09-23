package com.eonmux.excalibur.graph.dictionary;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * The rules for checking a {@link LabelDictionary}'s two files against each other and against
 * the checksums its index carries, shared by the dictionary's open and by any tool that surveys
 * one.
 *
 * <h2>What is checked</h2>
 * <p>A version 2 index ends with a trailer, {@code [text length:8][text CRC32C:4][index
 * CRC32C:4]}, where the index checksum covers every byte of the index ahead of it. The index is
 * refused unless it is exactly the size its entry count implies and digests to that checksum; the
 * text is refused unless it is the length and digest the index records. A flipped byte in an
 * entry would slice a name out of the middle of somebody else's, and one in the text would
 * misspell it, and both would read as answers, so both files are verified in full on every open -
 * the policy the edge store's columns follow, for the reasons given on {@code
 * EdgeStoreIntegrity}. A dictionary is a few bytes per name, so the pass is short beside the
 * edge store's.</p>
 *
 * <p>Recording the text's digest in the index rather than in the text also checks the two as a
 * pair. They are published by two renames, which cannot be one atomic act; an index left beside
 * text from a different build no longer matches the digest it carries, even when the two texts
 * happen to be the same length.</p>
 *
 * <h2>Version 1</h2>
 * <p>A version 1 index has no trailer. It is held to the one check its layout allows - the last
 * entry ends exactly where the text does - and is reported as unprotected. It is derived from the
 * namespace's staging logs, and the next build replaces it with one at version 2.</p>
 *
 * <h2>Read-only</h2>
 * <p>Both files are streamed through channels rather than mapped, so a dictionary too damaged to
 * open can still be judged, and nothing here writes.</p>
 */
public final class LabelDictionaryIntegrity {

    private static final Logger LOGGER = LoggerFactory.getLogger(LabelDictionaryIntegrity.class);

    /** Name the index goes by in a refusal or a report. */
    public static final String INDEX_FORMAT = "label dictionary index";

    /** Name the text goes by in a refusal or a report. */
    public static final String TEXT_FORMAT = "label dictionary text";

    private LabelDictionaryIntegrity() {
        throw new AssertionError("Utility class");
    }

    /**
     * Verifies both files of a dictionary, refusing one that is damaged.
     *
     * @param directory The directory holding the dictionary
     * @param name      The base name, without extension
     * @return The format version of the index, every byte of which was checked as far as that
     *         version allows
     * @throws GraphCorruptionException if either file is missing, malformed, cut short or does
     *         not match its checksum
     * @throws IOException if either file cannot be read, or the index is at a version this build
     *         does not read
     */
    public static int requireIntact(Path directory, String name) throws IOException {
        List<IntegrityReport> reports = inspect(directory, name);
        for (IntegrityReport report : reports) {
            switch (report.status()) {
                case CORRUPT, MISSING -> throw new GraphCorruptionException(report.file(),
                    report.format() + " " + report.file() + " failed its integrity check: "
                        + report.detail());
                case UNREADABLE -> throw new IOException(report.format() + " " + report.file()
                    + " cannot be read: " + report.detail());
                default -> {
                    // Intact or unprotected: served either way.
                }
            }
        }
        if (reports.get(0).status() == IntegrityStatus.LEGACY_UNPROTECTED) {
            LOGGER.warn("label dictionary {} in {} is format version {}, written before "
                + "dictionaries carried checksums: its two files describe each other and nothing "
                + "more can be shown. It is derived from the namespace's staging logs, and the "
                + "next build replaces it with a checksummed one", name, directory,
                LabelDictionary.OLDEST_VERSION);
            return LabelDictionary.OLDEST_VERSION;
        }
        return LabelDictionary.VERSION;
    }

    /**
     * Reaches a verdict on both files of a dictionary, refusing nothing.
     *
     * <p>The index's report comes first. The text's follows when the index could be believed far
     * enough to say what the text should hold, and is left out otherwise, since a verdict on it
     * would then be invented.</p>
     *
     * @param directory The directory the dictionary was published in
     * @param name      The base name, without extension
     * @return The index's report, then the text's when it could be judged
     * @throws NullPointerException if either argument is null
     */
    public static List<IntegrityReport> inspect(Path directory, String name) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Path indexPath = directory.resolve(name + ".idx");
        Path textPath = directory.resolve(name + ".dat");
        if (!Files.isRegularFile(indexPath)) {
            return List.of(IntegrityReport.missing(indexPath, INDEX_FORMAT));
        }
        try (FileChannel index = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            return inspect(indexPath, index, textPath);
        } catch (IOException e) {
            return List.of(IntegrityReport.unreadable(indexPath, INDEX_FORMAT, e.toString()));
        }
    }

    /**
     * Judges an open index, then the text it describes.
     *
     * @param indexPath The index
     * @param index     A channel over it
     * @param textPath  The text
     * @return The index's report, then the text's when it could be judged
     * @throws IOException if the index cannot be read
     */
    private static List<IntegrityReport> inspect(Path indexPath, FileChannel index,
                                                 Path textPath) throws IOException {
        long indexBytes = index.size();
        ByteBuffer header = read(index, 0, LabelDictionary.HEADER_BYTES);
        if (indexBytes < LabelDictionary.HEADER_BYTES || header.hasRemaining()) {
            return List.of(IntegrityReport.corrupt(indexPath, INDEX_FORMAT, "the index is "
                + indexBytes + " bytes, shorter than its " + LabelDictionary.HEADER_BYTES
                + "-byte header"));
        }
        long magic = header.getLong(0);
        if (magic != LabelDictionary.MAGIC) {
            return List.of(IntegrityReport.corrupt(indexPath, INDEX_FORMAT, "not a label "
                + "dictionary: magic 0x" + Long.toHexString(magic) + ", expected 0x"
                + Long.toHexString(LabelDictionary.MAGIC)));
        }
        int version = header.getInt(8);
        if (version < LabelDictionary.OLDEST_VERSION || version > LabelDictionary.VERSION) {
            return List.of(IntegrityReport.unsupportedVersion(indexPath, INDEX_FORMAT, version,
                LabelDictionary.OLDEST_VERSION, LabelDictionary.VERSION));
        }
        int count = header.getInt(12);
        long entriesEnd = LabelDictionary.HEADER_BYTES + (long) count * LabelDictionary.ENTRY_BYTES;
        if (count < 0 || entriesEnd > indexBytes) {
            return List.of(IntegrityReport.corrupt(indexPath, INDEX_FORMAT, "the header declares "
                + count + " entries, which do not fit in " + indexBytes + " bytes"));
        }
        return version == LabelDictionary.OLDEST_VERSION
            ? inspectLegacy(indexPath, index, count, textPath)
            : inspectSealed(indexPath, index, indexBytes, count, textPath);
    }

    /**
     * Judges a version 1 dictionary by the one check its layout allows: that the index's last
     * entry ends exactly where the text does.
     *
     * <p>The text is written as one concatenation in id order, so a mismatch means the two files
     * are from different builds - what a crash between their two renames leaves behind - and
     * reading them together would return one entity's name for another's.</p>
     *
     * @param indexPath The index
     * @param index     A channel over it
     * @param count     How many entries it declares
     * @param textPath  The text
     * @return The two reports
     * @throws IOException if the index cannot be read
     */
    private static List<IntegrityReport> inspectLegacy(Path indexPath, FileChannel index,
                                                       int count, Path textPath)
            throws IOException {
        IntegrityReport indexReport = new IntegrityReport(indexPath, INDEX_FORMAT,
            IntegrityStatus.LEGACY_UNPROTECTED, legacyDetail(count));
        if (!Files.isRegularFile(textPath)) {
            return List.of(indexReport, IntegrityReport.missing(textPath, TEXT_FORMAT));
        }
        long textBytes = Files.size(textPath);
        long implied = impliedTextBytes(index, count);
        if (implied != textBytes) {
            return List.of(indexReport, IntegrityReport.corrupt(textPath, TEXT_FORMAT,
                "the index beside it describes " + implied + " bytes of text but it holds "
                    + textBytes + ": the two files are from different builds, which is what a "
                    + "crash between their two renames leaves behind, and reading them together "
                    + "would return one entity's name for another's"));
        }
        return List.of(indexReport, new IntegrityReport(textPath, TEXT_FORMAT,
            IntegrityStatus.LEGACY_UNPROTECTED, legacyDetail(count)));
    }

    /**
     * Judges a version 2 dictionary against the checksums in its trailer.
     *
     * @param indexPath  The index
     * @param index      A channel over it
     * @param indexBytes How long the index is
     * @param count      How many entries it declares
     * @param textPath   The text
     * @return The index's report, then the text's when the index could be believed
     * @throws IOException if the index cannot be read
     */
    private static List<IntegrityReport> inspectSealed(Path indexPath, FileChannel index,
                                                       long indexBytes, int count,
                                                       Path textPath) throws IOException {
        long expected = LabelDictionary.HEADER_BYTES + (long) count * LabelDictionary.ENTRY_BYTES
            + LabelDictionary.TRAILER_BYTES;
        if (indexBytes != expected) {
            return List.of(IntegrityReport.corrupt(indexPath, INDEX_FORMAT, "the index is "
                + indexBytes + " bytes where " + count + " entries and the trailer make it "
                + expected + ", so it has "
                + (indexBytes < expected ? "lost its tail" : "been written past its end")));
        }
        ByteBuffer trailer = read(index, indexBytes - LabelDictionary.TRAILER_BYTES,
            LabelDictionary.TRAILER_BYTES);
        long recordedTextBytes = trailer.getLong(0);
        long recordedTextCrc = Integer.toUnsignedLong(trailer.getInt(8));
        long recordedIndexCrc = Integer.toUnsignedLong(trailer.getInt(12));
        long actualIndexCrc = IndexChecksum.of(index, 0, indexBytes - Integer.BYTES);
        if (actualIndexCrc != recordedIndexCrc) {
            return List.of(IntegrityReport.corrupt(indexPath, INDEX_FORMAT, "its trailer "
                + "records 0x" + Long.toHexString(recordedIndexCrc) + " over the index but it "
                + "digests to 0x" + Long.toHexString(actualIndexCrc) + ", so an id, offset or "
                + "length in it is no longer the one written, and the name it points at may be "
                + "a slice of somebody else's"));
        }
        IntegrityReport indexReport = IntegrityReport.intact(indexPath, INDEX_FORMAT,
            "format version " + LabelDictionary.VERSION + ": the CRC32C in its trailer matched "
                + "the header and all " + count + " entries");
        return List.of(indexReport,
            inspectText(textPath, recordedTextBytes, recordedTextCrc));
    }

    /**
     * Judges the text against the length and digest a verified index records for it.
     *
     * @param textPath The text
     * @param length   The length the index records
     * @param crc      The CRC32C the index records
     * @return The text's report
     */
    private static IntegrityReport inspectText(Path textPath, long length, long crc) {
        if (!Files.isRegularFile(textPath)) {
            return IntegrityReport.missing(textPath, TEXT_FORMAT);
        }
        long actualLength;
        long actualCrc;
        try (FileChannel text = FileChannel.open(textPath, StandardOpenOption.READ)) {
            actualLength = text.size();
            if (actualLength != length) {
                return IntegrityReport.corrupt(textPath, TEXT_FORMAT, "the text is "
                    + actualLength + " bytes where the index beside it records " + length
                    + ": it was cut short or extended, or it is from a different build than the "
                    + "index, which is what a crash between their two renames leaves behind");
            }
            actualCrc = IndexChecksum.of(text, 0, actualLength);
        } catch (IOException e) {
            return IntegrityReport.unreadable(textPath, TEXT_FORMAT, e.toString());
        }
        if (actualCrc != crc) {
            return IntegrityReport.corrupt(textPath, TEXT_FORMAT, "the index records 0x"
                + Long.toHexString(crc) + " over its " + length + " bytes but they digest to 0x"
                + Long.toHexString(actualCrc) + ": a name in it was altered, or it is from a "
                + "different build than the index beside it");
        }
        return IntegrityReport.intact(textPath, TEXT_FORMAT, "the CRC32C over all " + length
            + " bytes matched the one its index records");
    }

    /**
     * What a version 1 dictionary can be shown to be.
     *
     * @param count How many entries it declares
     * @return The detail
     */
    private static String legacyDetail(int count) {
        return "format version " + LabelDictionary.OLDEST_VERSION + ", written before "
            + "dictionaries carried checksums: its " + count + " entries fit the index and end "
            + "where the text does, and nothing shows they are the ones written. It is derived "
            + "from the namespace's staging logs, and the next build replaces it with a "
            + "checksummed one";
    }

    /**
     * How long the text an index describes is: where its last entry ends.
     *
     * @param index A channel over the index
     * @param count How many entries it declares
     * @return The implied length of the text
     * @throws IOException if the index cannot be read
     */
    private static long impliedTextBytes(FileChannel index, int count) throws IOException {
        if (count == 0) {
            return 0;
        }
        ByteBuffer last = read(index,
            LabelDictionary.HEADER_BYTES + (long) (count - 1) * LabelDictionary.ENTRY_BYTES,
            LabelDictionary.ENTRY_BYTES);
        return Integer.toUnsignedLong(last.getInt(8)) + Integer.toUnsignedLong(last.getInt(12));
    }

    /**
     * Reads up to {@code length} bytes from an offset, stopping early only at the end of the file.
     *
     * @param channel The channel
     * @param offset  Where to start
     * @param length  How many bytes to read
     * @return The buffer, left with remaining space when the file ended first
     * @throws IOException if the read fails
     */
    private static ByteBuffer read(FileChannel channel, long offset, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length)
            .order(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        return buffer;
    }
}
