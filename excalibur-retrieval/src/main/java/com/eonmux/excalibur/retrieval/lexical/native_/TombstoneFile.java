package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Which documents of a segment have been erased or replaced, on disk: {@code seg-N.tomb}.
 *
 * <h2>Why this file needs a checksum more than most</h2>
 * <p>A tombstone is the only thing standing between an erased document and a query. A bit that
 * flips from set to clear does not look like damage - every bit pattern is a valid set of marks -
 * and it makes a document readable again that its owner was told had been erased, with nothing
 * anywhere to say so. So the marks are followed by a CRC32C of every byte before them, and a file
 * whose bytes do not match it is refused rather than believed: the segment is kept out of every
 * query until the file is restored, because serving it would answer with documents that are
 * meant to be gone.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * 0   magic     int64   "EXLXTOMB"
 * 8   version   int32   2
 * 12  docCount  int32   documents in the segment the marks describe
 * 16  marks     ceil(docCount / 8) bytes, document i at bit (i &amp; 7) of byte (i &gt;&gt;&gt; 3)
 * ..  crc32c    int32   over every byte before it
 * </pre>
 * <p>Big-endian, the order every other file of a lexical segment is written in. The document
 * count is recorded so that a file cannot be read against a segment it was not written for: a
 * set of marks one byte short of its segment would otherwise read as its last eight documents
 * never having been erased.</p>
 *
 * <h2>The version before it</h2>
 * <p>Version 1 was the marks alone, with no header, and is recognised by the absence of the magic
 * number and by its length, which was always exactly one bit per document. It is read as before,
 * and carries nothing that could show a mark was lost. The next write of the segment's marks replaces
 * it with the current version, and until then the store counts the segment as outdated, so
 * compaction rewrites it in time. A header naming a version above {@link #VERSION} was written by
 * a newer build and is refused.</p>
 *
 * <h2>What an absent file means</h2>
 * <p>No marks, which is the state of every segment nothing has been erased from or replaced in.
 * An erasure a crash kept from its marks is not lost with them: the erasure log holds every
 * promise the store has not confirmed as applied, and the store marks those again when it
 * opens.</p>
 */
final class TombstoneFile {

    /** Suffix of a segment's tombstone file beside its directory. */
    static final String SUFFIX = ".tomb";

    /** Distinguishes the current format from the headerless version before it. */
    static final long MAGIC = 0x45584C58544F4D42L;

    /** The version this build writes. */
    static final int VERSION = 2;

    /** The headerless version, still read and never written. */
    static final int HEADERLESS_VERSION = 1;

    /** Name used when a damaged file is refused or reported. */
    static final String FORMAT = "lexical tombstones";

    /** Magic, version and document count. */
    private static final int HEADER_BYTES = Long.BYTES + 2 * Integer.BYTES;

    /** The trailing CRC32C. */
    private static final int CHECKSUM_BYTES = Integer.BYTES;

    private TombstoneFile() {
        throw new AssertionError("Utility class");
    }

    /**
     * The marks a file holds, and the version it was written at.
     *
     * @param marks   One flag per document, true where the document is tombstoned
     * @param version The format version read, or {@link #VERSION} when there was no file
     */
    record Marks(boolean[] marks, int version) {

        /** Documents marked. */
        int count() {
            int total = 0;
            for (boolean mark : marks) {
                if (mark) {
                    total++;
                }
            }
            return total;
        }
    }

    /**
     * A tombstone file written at a version this build does not read.
     */
    static final class UnsupportedVersionException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int version;

        UnsupportedVersionException(Path file, int version) {
            super(file + " holds tombstones at format version " + version + ", which this build "
                + "does not read - it reads versions " + HEADERLESS_VERSION + " to " + VERSION
                + "; a newer build wrote it");
            this.version = version;
        }

        int version() {
            return version;
        }
    }

    /**
     * Returns the file a segment's marks are kept in.
     *
     * @param directory The store directory
     * @param segment   The segment's name
     * @return {@code <directory>/<segment>.tomb}
     */
    static Path of(Path directory, String segment) {
        return directory.resolve(segment + SUFFIX);
    }

    /**
     * Reads a segment's marks, refusing any that cannot be shown to be the ones written.
     *
     * @param file     The tombstone file
     * @param docCount Documents in the segment
     * @return The marks, all clear when there is no file
     * @throws UnsupportedVersionException if a newer build wrote the file
     * @throws IOException if the file is damaged, cut short or written for another segment; the
     *                     message says which
     */
    static Marks read(Path file, int docCount) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file)) {
            return new Marks(new boolean[docCount], VERSION);
        }
        return decode(file, Files.readAllBytes(file), docCount);
    }

    /**
     * Parses a file's bytes against the segment they describe.
     *
     * @param file     The file, for messages
     * @param bytes    Its contents
     * @param docCount Documents in the segment
     * @return The marks and the version they were read at
     * @throws IOException if the bytes are not a whole file written for this segment
     */
    static Marks decode(Path file, byte[] bytes, int docCount) throws IOException {
        if (!hasMagic(bytes)) {
            return headerless(file, bytes, docCount);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int version = buffer.getInt(Long.BYTES);
        if (version > VERSION) {
            throw new UnsupportedVersionException(file, version);
        }
        if (version != VERSION) {
            throw new IOException(file + " begins with the tombstone magic number and declares "
                + "format version " + version + ", which no build writes; its header was "
                + "overwritten");
        }
        int written = buffer.getInt(Long.BYTES + Integer.BYTES);
        if (written != docCount) {
            throw new IOException(file + " holds marks for " + written + " document(s) and the "
                + "segment has " + docCount + "; it was not written for this segment");
        }
        int bits = bitBytes(docCount);
        long expected = (long) HEADER_BYTES + bits + CHECKSUM_BYTES;
        if (bytes.length != expected) {
            throw new IOException(file + " is " + bytes.length + " bytes where marks for "
                + docCount + " document(s) take exactly " + expected + ", so it was "
                + (bytes.length < expected ? "cut short" : "written past its end"));
        }
        int covered = HEADER_BYTES + bits;
        int stored = buffer.getInt(covered);
        int actual = (int) IndexChecksum.of(bytes, 0, covered);
        if (stored != actual) {
            throw new IOException(String.format("%s failed its integrity check: the checksum did "
                + "not match. It records %08x and its %d bytes digest to %08x, so a mark may have "
                + "been cleared and an erased document made readable again", file, stored,
                covered, actual));
        }
        return new Marks(unpack(bytes, HEADER_BYTES, bits, docCount), VERSION);
    }

    /**
     * Encodes marks at the current version.
     *
     * @param marks One flag per document
     * @return The file's bytes
     */
    static byte[] encode(boolean[] marks) {
        int bits = bitBytes(marks.length);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + bits + CHECKSUM_BYTES)
            .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(MAGIC).putInt(VERSION).putInt(marks.length);
        byte[] packed = new byte[bits];
        for (int i = 0; i < marks.length; i++) {
            if (marks[i]) {
                packed[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        buffer.put(packed);
        buffer.putInt((int) IndexChecksum.of(buffer.array(), 0, buffer.position()));
        return buffer.array();
    }

    /**
     * Reaches a verdict on a segment's marks without changing anything.
     *
     * @param file     The tombstone file
     * @param docCount Documents in the segment
     * @return Intact at the current version, legacy for the headerless one, unreadable for a
     *         newer build's, and corrupt otherwise; null when there is no file
     */
    static IntegrityReport inspect(Path file, int docCount) {
        Marks marks;
        try {
            marks = decode(file, Files.readAllBytes(file), docCount);
        } catch (NoSuchFileException e) {
            return null;
        } catch (UnsupportedVersionException e) {
            return IntegrityReport.unsupportedVersion(file, FORMAT, e.version(),
                HEADERLESS_VERSION, VERSION);
        } catch (IOException e) {
            return IntegrityReport.corrupt(file, FORMAT, e.getMessage());
        }
        if (marks.version() == HEADERLESS_VERSION) {
            return new IntegrityReport(file, FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
                "written without a header or checksum, so which of the segment's documents its "
                    + marks.count() + " mark(s) name cannot be verified. The node rewrites it "
                    + "with a checksum the next time a document of this segment is erased or "
                    + "replaced, and compaction rewrites the segment in time");
        }
        return IntegrityReport.intact(file, FORMAT, "the checksum matched all " + docCount
            + " mark(s), " + marks.count() + " of them set");
    }

    /**
     * Reads a file without the magic number as the headerless version, if that is what it is.
     *
     * <p>That version was always written at exactly one bit per document, so a file of any other
     * length is not one: it is a current file cut short before the end of its magic number, or
     * marks written for another segment. A file of the right length that is nonetheless the
     * start of the magic number is a current one cut short there too; a headerless file whose
     * marks spell those bytes out would be refused with it, which no set of marks written for
     * a real segment has been seen to do.</p>
     */
    private static Marks headerless(Path file, byte[] bytes, int docCount) throws IOException {
        int bits = bitBytes(docCount);
        if (bytes.length != bits || (bytes.length > 0 && startsMagic(bytes))) {
            throw new IOException(file + " is " + bytes.length + " bytes and does not begin with "
                + "the tombstone magic number, so it is neither a current tombstone file, which "
                + "for " + docCount + " document(s) takes " + (HEADER_BYTES + bits
                + CHECKSUM_BYTES) + " bytes, nor a headerless one, which takes " + bits
                + "; it was cut short or not written for this segment");
        }
        return new Marks(unpack(bytes, 0, bytes.length, docCount), HEADERLESS_VERSION);
    }

    /** Whether a file shorter than a header is the beginning of the magic number. */
    private static boolean startsMagic(byte[] bytes) {
        if (bytes.length >= HEADER_BYTES) {
            return false;
        }
        byte[] magic = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN)
            .putLong(MAGIC).array();
        int compared = Math.min(bytes.length, magic.length);
        for (int i = 0; i < compared; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Whether a file begins with the current format's magic number. */
    private static boolean hasMagic(byte[] bytes) {
        return bytes.length >= HEADER_BYTES
            && ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong(0) == MAGIC;
    }

    /** Bytes a set of marks takes. */
    private static int bitBytes(int docCount) {
        return (docCount + 7) / 8;
    }

    /** Unpacks marks from a region, reading a bit past its end as clear. */
    private static boolean[] unpack(byte[] bytes, int offset, int length, int docCount) {
        boolean[] marks = new boolean[docCount];
        for (int i = 0; i < docCount; i++) {
            int index = i >>> 3;
            if (index < length && ((bytes[offset + index] >>> (i & 7)) & 1) != 0) {
                marks[i] = true;
            }
        }
        return marks;
    }
}
