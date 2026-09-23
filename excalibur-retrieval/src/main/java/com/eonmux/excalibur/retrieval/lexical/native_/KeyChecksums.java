package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The checksums over a segment's key table, kept beside it in {@code seg-N.keysum}.
 *
 * <h2>Why the key table needs them</h2>
 * <p>The names are the one part of a segment nothing else can reconstruct: a rebuild reads them
 * and asks for each one's text, and an erasure finds what to tombstone through them. A flipped
 * byte in {@code .keys} makes a hit answer under a name no chunk is stored under; a flipped byte
 * in {@code .keyidx} shifts where every later name begins; a flipped byte in {@code .keymap}
 * makes a lookup miss, so an erasure reports a document it holds as not found and leaves it
 * readable. None of the three looks damaged - each still parses - so each is digested when the
 * segment is written and checked when the store opens.</p>
 *
 * <h2>Why a file of its own</h2>
 * <p>The names are text, so an operator can read what a segment holds; the offsets and the table
 * take their counts from their lengths, so no header can disagree with them. Neither has room
 * for a checksum that would not cost it that property, and neither has a header an older file
 * could be told apart by. So the digests live here, written once with the segment and never
 * changed: the names and offsets are immutable, and the table is a pure function of them, so
 * rebuilding it reproduces the bytes this file records.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * 0   magic        int64  "EXLXKSUM"
 * 8   version      int32  1
 * 12  docCount     int32  names in the table
 * 16  keys         int64 length, int64 CRC32C    seg-N.keys
 * 32  keyidx       int64 length, int64 CRC32C    seg-N.keyidx
 * 48  keymap       int64 length, int64 CRC32C    seg-N.keymap
 * 64  checksum     int64  CRC32C of [0, 64)
 * </pre>
 * <p>Big-endian, as every other file of a lexical segment is, and laid out the way
 * {@code index.meta}'s column table is: a length and a checksum per file, then a checksum over
 * the table itself.</p>
 *
 * <h2>A segment without one</h2>
 * <p>An older build wrote no checksums, and none is written for its segments now: a digest taken
 * today would vouch for bytes nothing vouched for when they were written. Such a key table is
 * checked for what its structure can show and is otherwise unverified; the segment is counted as
 * outdated, so compaction rewrites it - with a checksum - in time.</p>
 */
final class KeyChecksums {

    /** Suffix of the checksum file beside a segment. */
    static final String SUFFIX = ".keysum";

    /** Identifies the file. */
    static final long MAGIC = 0x45584C584B53554DL;

    /** The version this build writes and reads. */
    static final int VERSION = 1;

    /** Name used when a damaged key table is refused or reported. */
    static final String FORMAT = "lexical key table";

    /** Bytes covered by the trailing checksum. */
    private static final int COVERED_BYTES = 64;

    /** The whole file. */
    static final int FILE_BYTES = COVERED_BYTES + Long.BYTES;

    /** The files recorded, in the order the table holds them. */
    static final List<String> SUFFIXES =
        List.of(SegmentKeys.KEYS_SUFFIX, SegmentKeys.INDEX_SUFFIX, SegmentKeys.MAP_SUFFIX);

    private KeyChecksums() {
        throw new AssertionError("Utility class");
    }

    /**
     * A checksum file written at a version this build does not read.
     */
    static final class UnsupportedVersionException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int version;

        UnsupportedVersionException(Path file, int version) {
            super(file + " is key table checksum format version " + version + ", which this "
                + "build does not read - it reads version " + VERSION + "; a newer build wrote it");
            this.version = version;
        }

        int version() {
            return version;
        }
    }

    /**
     * One file's length and digest, as recorded.
     *
     * @param suffix   Which of the three files
     * @param length   Its size when the segment was written
     * @param checksum The CRC32C of its contents, as an unsigned value in a long
     */
    record FileChecksum(String suffix, long length, long checksum) {
    }

    /**
     * What a checksum file records.
     *
     * @param docCount How many names the table holds
     * @param files    One entry per file, in {@link #SUFFIXES} order
     */
    record Recorded(int docCount, List<FileChecksum> files) {

        /** The entry for one file. */
        FileChecksum of(String suffix) {
            return files.get(SUFFIXES.indexOf(suffix));
        }
    }

    /**
     * Returns where a segment's checksums are kept.
     *
     * @param directory The store directory
     * @param segment   The segment's name
     * @return {@code <directory>/<segment>.keysum}
     */
    static Path of(Path directory, String segment) {
        return directory.resolve(segment + SUFFIX);
    }

    /**
     * Digests a segment's key table, all three files already on the device, and records it.
     *
     * @param directory The store directory
     * @param segment   The segment's name
     * @param docCount  How many names the table holds
     * @throws IOException if a file cannot be digested or the record cannot be made durable
     */
    static void write(Path directory, String segment, int docCount) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(FILE_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(MAGIC).putInt(VERSION).putInt(docCount);
        for (String suffix : SUFFIXES) {
            Path file = directory.resolve(segment + suffix);
            long length = Files.size(file);
            buffer.putLong(length).putLong(digest(file, length));
        }
        buffer.putLong(IndexChecksum.of(buffer.array(), 0, COVERED_BYTES));
        Durability.FILE_SYSTEM.replace(of(directory, segment), buffer.array());
    }

    /**
     * Reads a segment's checksums.
     *
     * @param directory The store directory
     * @param segment   The segment's name
     * @return What was recorded, or null when the segment has no checksum file
     * @throws UnsupportedVersionException if a newer build wrote the file
     * @throws IOException if the file is there and is not a whole one this build reads
     */
    static Recorded read(Path directory, String segment) throws IOException {
        Path file = of(directory, segment);
        if (!Files.exists(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < Long.BYTES + Integer.BYTES) {
            throw new IOException(file + " is " + bytes.length + " bytes, too short to be a "
                + "key table checksum file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getLong(0) != MAGIC) {
            throw new IOException(file + " does not begin with the key table checksum file's "
                + "magic number, so it is not one, or its first bytes were overwritten");
        }
        int version = buffer.getInt(Long.BYTES);
        if (version > VERSION) {
            throw new UnsupportedVersionException(file, version);
        }
        if (version != VERSION) {
            throw new IOException(file + " declares format version " + version + ", which no "
                + "build writes; its header was overwritten");
        }
        if (bytes.length != FILE_BYTES) {
            throw new IOException(file + " is " + bytes.length + " bytes where the format takes "
                + "exactly " + FILE_BYTES);
        }
        long stored = buffer.getLong(COVERED_BYTES);
        long actual = IndexChecksum.of(bytes, 0, COVERED_BYTES);
        if (stored != actual) {
            throw new IOException(file + " failed its integrity check: the checksum did not "
                + "match. It records " + Long.toHexString(stored) + " over its own " + COVERED_BYTES
                + " bytes and they digest to " + Long.toHexString(actual) + ", so the checksums "
                + "it holds for the key table cannot be relied on");
        }
        List<FileChecksum> files = new ArrayList<>(SUFFIXES.size());
        int at = Long.BYTES + 2 * Integer.BYTES;
        for (String suffix : SUFFIXES) {
            files.add(new FileChecksum(suffix, buffer.getLong(at), buffer.getLong(at + Long.BYTES)));
            at += 2 * Long.BYTES;
        }
        return new Recorded(buffer.getInt(Long.BYTES + Integer.BYTES), List.copyOf(files));
    }

    /**
     * Checks one of the three files against what was recorded for it.
     *
     * @param directory The store directory
     * @param segment   The segment's name
     * @param recorded  What the checksum file records
     * @param suffix    Which file
     * @return Null when the file matches, otherwise what is wrong with it
     * @throws IOException if the file cannot be read
     */
    static String mismatch(Path directory, String segment, Recorded recorded, String suffix)
            throws IOException {
        Path file = directory.resolve(segment + suffix);
        FileChecksum expected = recorded.of(suffix);
        if (!Files.isRegularFile(file)) {
            return file.getFileName() + " is not there";
        }
        long length = Files.size(file);
        if (length != expected.length()) {
            return file.getFileName() + " is " + length + " bytes where it was "
                + expected.length() + " when the segment was written";
        }
        long actual = digest(file, length);
        if (actual != expected.checksum()) {
            return file.getFileName() + " failed its integrity check: the checksum did not "
                + "match. " + SUFFIX + " records " + Long.toHexString(expected.checksum())
                + " over its " + length + " bytes and they digest to " + Long.toHexString(actual);
        }
        return null;
    }

    /**
     * Reaches a verdict on each file of a segment's key table without changing anything.
     *
     * <p>One verdict for each of the names, the offsets, the lookup table and the checksum file
     * itself. With no checksum file the names and offsets are held to what their structure can
     * show - the number of names the manifest records, and offsets that end where the names do -
     * and reported unverified. With a checksum file that cannot be read there is a verdict on it
     * alone: nothing was checked against it, so nothing is said about the other three.</p>
     *
     * @param directory The store directory
     * @param segment   The segment's name
     * @param docCount  How many documents the manifest records for the segment
     * @return The verdicts, in the order names, offsets, table, checksum file; only the last when
     *         the checksum file cannot be read, and all but the last when there is none
     */
    static List<IntegrityReport> inspect(Path directory, String segment, int docCount) {
        Path sums = of(directory, segment);
        Recorded recorded;
        try {
            recorded = read(directory, segment);
        } catch (UnsupportedVersionException e) {
            return List.of(IntegrityReport.unsupportedVersion(sums, FORMAT, e.version(),
                VERSION, VERSION));
        } catch (IOException e) {
            return List.of(IntegrityReport.corrupt(sums, FORMAT, e.getMessage()));
        }
        if (recorded == null) {
            return unprotected(directory, segment, docCount);
        }
        List<IntegrityReport> reports = new ArrayList<>(SUFFIXES.size() + 1);
        for (String suffix : SUFFIXES) {
            reports.add(verdict(directory, segment, recorded, suffix, docCount));
        }
        reports.add(recorded.docCount() == docCount
            ? IntegrityReport.intact(sums, FORMAT, "its own checksum matched, and it records "
                + docCount + " name(s), as the manifest does")
            : IntegrityReport.corrupt(sums, FORMAT, "it records " + recorded.docCount()
                + " name(s) where the manifest records " + docCount));
        return reports;
    }

    /** The verdict on one checksummed file. */
    private static IntegrityReport verdict(Path directory, String segment, Recorded recorded,
                                           String suffix, int docCount) {
        Path file = directory.resolve(segment + suffix);
        try {
            String wrong = mismatch(directory, segment, recorded, suffix);
            if (wrong != null) {
                return Files.isRegularFile(file)
                    ? IntegrityReport.corrupt(file, FORMAT, wrong)
                    : IntegrityReport.missing(file, FORMAT);
            }
        } catch (IOException e) {
            return IntegrityReport.unreadable(file, FORMAT, e.toString());
        }
        return IntegrityReport.intact(file, FORMAT, "the checksum matched all "
            + recorded.of(suffix).length() + " bytes");
    }

    /** The verdicts on a key table written before checksums. */
    private static List<IntegrityReport> unprotected(Path directory, String segment,
                                                     int docCount) {
        Path names = directory.resolve(segment + SegmentKeys.KEYS_SUFFIX);
        Path offsets = directory.resolve(segment + SegmentKeys.INDEX_SUFFIX);
        Path table = directory.resolve(segment + SegmentKeys.MAP_SUFFIX);
        List<IntegrityReport> reports = new ArrayList<>(SUFFIXES.size());
        String structure;
        try {
            structure = structuralDamage(names, offsets, docCount);
        } catch (IOException e) {
            structure = "it could not be read: " + e;
        }
        String legacy = "written before key tables carried checksums, so its bytes cannot be "
            + "verified; compaction rewrites the segment with them";
        if (structure == null) {
            reports.add(new IntegrityReport(names, FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
                "the offsets delimit " + docCount + " name(s), as the manifest records; "
                    + legacy));
            reports.add(new IntegrityReport(offsets, FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
                "one offset per name and a terminating one, ending where the names do; "
                    + legacy));
        } else {
            reports.add(IntegrityReport.corrupt(names, FORMAT, structure));
            reports.add(IntegrityReport.corrupt(offsets, FORMAT, structure));
        }
        reports.add(new IntegrityReport(table, FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
            "derived from the names and rebuilt whenever it is missing or the wrong size; "
                + legacy));
        return reports;
    }

    /**
     * Says what, if anything, the structure of a key table written without checksums shows is
     * wrong with it: the number of names, and where the first and last of them begin and end.
     *
     * @return A description of the damage, or null when the structure holds
     */
    private static String structuralDamage(Path names, Path offsets, int docCount)
            throws IOException {
        if (!Files.isRegularFile(names) || !Files.isRegularFile(offsets)) {
            return "the names or their offsets are not there";
        }
        long offsetBytes = Files.size(offsets);
        long expected = ((long) docCount + 1) * Integer.BYTES;
        if (offsetBytes != expected) {
            return "the offsets file is " + offsetBytes + " bytes, which is not one offset per "
                + "each of the " + docCount + " name(s) the manifest records and a terminating one";
        }
        try (FileChannel channel = FileChannel.open(offsets, StandardOpenOption.READ)) {
            ByteBuffer first = ByteBuffer.allocate(Integer.BYTES);
            ByteBuffer last = ByteBuffer.allocate(Integer.BYTES);
            channel.read(first, 0);
            channel.read(last, offsetBytes - Integer.BYTES);
            int start = first.flip().getInt();
            int end = last.flip().getInt();
            long length = Files.size(names);
            if (start != 0 || end != length) {
                return "the offsets run from byte " + start + " to byte " + end + " and the names "
                    + "file is " + length + " bytes";
            }
        }
        return null;
    }

    /** The CRC32C of a whole file, streamed rather than mapped. */
    private static long digest(Path file, long length) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return IndexChecksum.of(channel, 0, length);
        }
    }
}
