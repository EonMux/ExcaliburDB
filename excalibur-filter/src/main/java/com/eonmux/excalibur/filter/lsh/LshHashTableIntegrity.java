package com.eonmux.excalibur.filter.lsh;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The integrity block of an LSH hash table file, and the rules for writing and checking it.
 *
 * <h2>Where it lives</h2>
 * <p>Version 3 widened the header from 56 bytes to 128 and put the block in the space that
 * opened up. The bucket directory therefore starts at 128 in a version 3 file and at 56 in a
 * version 2 one, which is why the offset is carried on the table rather than compiled in:</p>
 * <pre>
 *   offset  56  seal state    (4 bytes)
 *   offset  60  reserved      (4 bytes)
 *   offset  64  payload bytes (8 bytes)  bytes from the bucket directory the CRC covers
 *   offset  72  payload CRC   (8 bytes)  CRC32C of [bucket directory, + payload bytes)
 *   offset  80  format tag    (8 bytes)  {@link #FORMAT_TAG}, present only in version 3
 *   offset  88  reserved     (32 bytes)  covered by the header CRC, so a field added here
 *                                        is protected the day it is added
 *   offset 120  header CRC    (8 bytes)  CRC32C of [0, 120)
 * </pre>
 * <p>All fields are little-endian, matching the rest of the table.</p>
 *
 * <h2>Why the header CRC sits last</h2>
 * <p>A checksum cannot cover the field it is stored in, so wherever it sits it leaves a hole.
 * Putting it at the very end of the header makes that hole the end of the header, which means
 * every byte before it - including the whole reserved run - is digested. The alternative,
 * a CRC in the middle with reserved bytes behind it, is the shape that lets the next field
 * added to this format arrive with no protection at all and nobody noticing.</p>
 *
 * <h2>Seal state</h2>
 * <p>The file is memory mapped and mutated in place: an insert writes a bucket row, an entry
 * record and three header counters. Nothing can hold a checksum current against that, so the
 * table is sealed on close and marked open again the moment a writer attaches. Both CRCs are
 * checked only on a sealed file, because a file left open was not closed cleanly and refusing
 * it would turn every crash into a lost table. Verification reports such a file as unsealed
 * rather than intact.</p>
 *
 * <h2>The format tag, and what a version field alone cannot say</h2>
 * <p>The version field is four bytes at offset 4. Flipping one bit of a 3 makes a 2, and a 2
 * is a version that really existed - so a bare version check reads the damage as an honest
 * legacy file, places the bucket directory at 56 instead of 128, and hands back a table that
 * opens cleanly, reports its full entry count and answers every query empty. The header CRC
 * would catch the flip, but it is never consulted, because a legacy file has none to consult.
 *
 * <p>{@link #FORMAT_TAG} closes that: it is a 64-bit constant a version 3 file always carries
 * and a version 1 or 2 file cannot, because in those layouts offset 80 is a bucket directory
 * row. A header claiming version 1 or 2 while carrying the tag is a version 3 file whose
 * version field has been altered, and is reported corrupt. A genuine legacy file would have
 * to hold that exact 64-bit value in a bucket hash to be misread, which is a 2<sup>-64</sup>
 * coincidence rather than the single bit flip the version field alone costs.</p>
 *
 * <h2>Version 2 and version 1</h2>
 * <p>Read as before, at their own header size, and reported as unprotected. They are not
 * upgraded where they lie: the layout move from 56 to 128 relocates the bucket directory and
 * every absolute offset stored in it, and a checksum written over bytes nothing has attested
 * to would attest to nothing. Such a file becomes a version 3 file when the index is
 * rebuilt.</p>
 */
final class LshHashTableIntegrity {

    /** Name used when a damaged file is refused or reported. */
    static final String FORMAT = "LSH hash table";

    /** Seal state written by a clean close. */
    static final int STATE_SEALED = 0x5345414C;

    /** Seal state written while a writer holds the file. */
    static final int STATE_OPEN = 0x4F50454E;

    /** Offset of the seal state field. */
    static final int OFFSET_STATE = 56;

    /** Offset of the covered payload length. */
    static final int OFFSET_PAYLOAD_LENGTH = 64;

    /** Offset of the payload CRC32C. */
    static final int OFFSET_PAYLOAD_CRC = 72;

    /** Offset of the version 3 format tag. */
    static final int OFFSET_FORMAT_TAG = 80;

    /** Offset of the header CRC32C, the last field of the header. */
    static final int OFFSET_HEADER_CRC = 120;

    /** Number of leading header bytes the header CRC covers: everything but itself. */
    static final int HEADER_COVERED_BYTES = 120;

    /**
     * The value at {@link #OFFSET_FORMAT_TAG} in every version 3 file: ASCII "LSHTBLv3".
     *
     * <p>Chosen to be self-describing in a hex dump, and wide enough that a legacy file
     * cannot hold it at that offset by accident.</p>
     */
    static final long FORMAT_TAG = 0x4C534854424C7633L;

    /** The oldest version this format ever had. */
    private static final int OLDEST_VERSION = 1;

    private static final ValueLayout.OfInt INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private LshHashTableIntegrity() {
    }

    /**
     * Marks a table as live and stamps its header checksum.
     *
     * @param segment The table's mapping
     */
    static void markOpen(MemorySegment segment) {
        segment.set(INT, OFFSET_STATE, STATE_OPEN);
        segment.set(LONG, OFFSET_PAYLOAD_LENGTH, 0L);
        segment.set(LONG, OFFSET_PAYLOAD_CRC, 0L);
        segment.set(LONG, OFFSET_FORMAT_TAG, FORMAT_TAG);
        segment.set(LONG, OFFSET_HEADER_CRC,
            IndexChecksum.of(segment, 0, HEADER_COVERED_BYTES));
    }

    /**
     * Seals a table: covers its directory and entries with a checksum a later open can check.
     *
     * @param segment         The table's mapping
     * @param bucketDirOffset Where the bucket directory starts
     * @param liveEnd         The end of the live region, the bump allocator's position
     */
    static void seal(MemorySegment segment, long bucketDirOffset, long liveEnd) {
        long covered = Math.max(0, Math.min(liveEnd, segment.byteSize()) - bucketDirOffset);
        segment.set(LONG, OFFSET_PAYLOAD_LENGTH, covered);
        segment.set(LONG, OFFSET_PAYLOAD_CRC,
            IndexChecksum.of(segment, bucketDirOffset, covered));
        segment.set(INT, OFFSET_STATE, STATE_SEALED);
        segment.set(LONG, OFFSET_FORMAT_TAG, FORMAT_TAG);
        segment.set(LONG, OFFSET_HEADER_CRC,
            IndexChecksum.of(segment, 0, HEADER_COVERED_BYTES));
    }

    /**
     * Refuses a mapped table whose stored checksums do not match its bytes.
     *
     * @param segment         The table's mapping
     * @param path            The file the mapping belongs to, or null for an anonymous image
     * @param version         The format version read from the header
     * @param bucketDirOffset Where the bucket directory starts
     * @throws IOException if the table is sealed and damaged
     */
    static void requireIntact(MemorySegment segment, Path path, int version,
                              long bucketDirOffset) throws IOException {
        inspect(segment, path, version, bucketDirOffset).requireIntact();
    }

    /**
     * Reaches a verdict on a mapped table without refusing anything.
     *
     * @param segment         The table's mapping
     * @param path            The file the mapping belongs to, or null for an anonymous image
     * @param version         The format version read from the header
     * @param bucketDirOffset Where the bucket directory starts
     * @return What the check concluded
     */
    static IntegrityReport inspect(MemorySegment segment, Path path, int version,
                                   long bucketDirOffset) {
        IntegrityReport verdict = versionVerdict(path, version, carriesFormatTag(segment));
        if (verdict != null) {
            return verdict;
        }
        int state = segment.get(INT, OFFSET_STATE);
        if (state != STATE_SEALED) {
            return unsealed(path);
        }
        long storedHeaderCrc = segment.get(LONG, OFFSET_HEADER_CRC);
        long actualHeaderCrc = IndexChecksum.of(segment, 0, HEADER_COVERED_BYTES);
        if (storedHeaderCrc != actualHeaderCrc) {
            return headerMismatch(path, storedHeaderCrc, actualHeaderCrc);
        }
        long covered = segment.get(LONG, OFFSET_PAYLOAD_LENGTH);
        long available = segment.byteSize() - bucketDirOffset;
        if (covered < 0 || covered > available) {
            return shortPayload(path, covered, available);
        }
        long cursor = segment.get(LONG, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET);
        if (cursor != bucketDirOffset + covered) {
            return cursorDisagrees(path, cursor, bucketDirOffset + covered);
        }
        long storedPayloadCrc = segment.get(LONG, OFFSET_PAYLOAD_CRC);
        long actualPayloadCrc = IndexChecksum.of(segment, bucketDirOffset, covered);
        if (storedPayloadCrc != actualPayloadCrc) {
            return payloadMismatch(path, covered, storedPayloadCrc, actualPayloadCrc);
        }
        return IntegrityReport.intact(path, FORMAT,
            covered + " bytes of buckets and entries verified");
    }

    /**
     * Judges the version field, using the format tag to tell a real legacy file from a
     * version 3 file whose version field has been altered.
     *
     * @param path    The file being judged, or null
     * @param version The version read from the header
     * @param tagged  Whether the file carries {@link #FORMAT_TAG}
     * @return The verdict, or null when the file is a version 3 file and the checks go on
     */
    private static IntegrityReport versionVerdict(Path path, int version, boolean tagged) {
        if (version == LshHashTable.VERSION) {
            return tagged ? null : IntegrityReport.corrupt(path, FORMAT, "the header claims "
                + "format version " + LshHashTable.VERSION + " but carries no format tag at "
                + "offset " + OFFSET_FORMAT_TAG + ", so the integrity block this version is "
                + "supposed to have is not there");
        }
        if (version >= OLDEST_VERSION && version <= LshHashTable.VERSION_WITHOUT_CHECKSUM) {
            return tagged
                ? IntegrityReport.corrupt(path, FORMAT, "the header claims format version "
                    + version + ", which has no integrity block, but the file carries the "
                    + "version " + LshHashTable.VERSION + " format tag at offset "
                    + OFFSET_FORMAT_TAG + "; the version field has been altered, and honouring "
                    + "it would read the bucket directory at " + LshHashTable.HEADER_SIZE_V2
                    + " instead of " + LshHashTable.HEADER_SIZE + " and answer every query "
                    + "empty while still reporting the full entry count")
                : IntegrityReport.legacy(path, FORMAT, version);
        }
        return IntegrityReport.corrupt(path, FORMAT, "the header claims format version "
            + version + ", which this format has never had; the only versions ever written "
            + "are " + OLDEST_VERSION + " to " + LshHashTable.VERSION);
    }

    /** Reports whether a mapped table carries the version 3 format tag. */
    private static boolean carriesFormatTag(MemorySegment segment) {
        return segment.byteSize() >= OFFSET_FORMAT_TAG + Long.BYTES
            && segment.get(LONG, OFFSET_FORMAT_TAG) == FORMAT_TAG;
    }

    /** Reports whether a header read from disk carries the version 3 format tag. */
    private static boolean carriesFormatTag(ByteBuffer header, long size) {
        return size >= OFFSET_FORMAT_TAG + Long.BYTES
            && header.getLong(OFFSET_FORMAT_TAG) == FORMAT_TAG;
    }

    /**
     * Verifies a table file on disk without mapping it or attaching a table to it.
     *
     * @param path The table file to verify
     * @return What the check concluded
     */
    static IntegrityReport verify(Path path) {
        if (path == null) {
            throw new NullPointerException("path cannot be null");
        }
        if (!Files.exists(path)) {
            return IntegrityReport.missing(path, FORMAT);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return verifyChannel(path, channel);
        } catch (IOException e) {
            return IntegrityReport.unreadable(path, FORMAT, e.toString());
        }
    }

    /**
     * Verifies an open table file, reading and validating its header first.
     */
    private static IntegrityReport verifyChannel(Path path, FileChannel channel)
            throws IOException {
        long size = channel.size();
        if (size < LshHashTable.HEADER_SIZE_V2) {
            return IntegrityReport.corrupt(path, FORMAT,
                "the file is " + size + " bytes, too short to hold a header");
        }
        ByteBuffer header = ByteBuffer.allocate(LshHashTable.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        readHeader(channel, header, size);
        if (header.getInt(LshHashTable.OFFSET_MAGIC) != LshHashTable.MAGIC) {
            return IntegrityReport.corrupt(path, FORMAT,
                "the file does not start with the LSH table magic number");
        }
        int version = header.getInt(LshHashTable.OFFSET_VERSION);
        IntegrityReport verdict =
            versionVerdict(path, version, carriesFormatTag(header, size));
        if (verdict != null) {
            return verdict;
        }
        if (size < LshHashTable.HEADER_SIZE) {
            return IntegrityReport.corrupt(path, FORMAT, "the file is " + size
                + " bytes, shorter than its " + LshHashTable.HEADER_SIZE + "-byte header");
        }
        return inspectChannel(path, channel, header, size);
    }

    /**
     * Checks a version 3 file's two checksums against the bytes on disk.
     */
    private static IntegrityReport inspectChannel(Path path, FileChannel channel,
                                                  ByteBuffer header, long size)
            throws IOException {
        int state = header.getInt(OFFSET_STATE);
        if (state != STATE_SEALED) {
            return unsealed(path);
        }
        long storedHeaderCrc = header.getLong(OFFSET_HEADER_CRC);
        long actualHeaderCrc = IndexChecksum.of(header.array(), 0, HEADER_COVERED_BYTES);
        if (storedHeaderCrc != actualHeaderCrc) {
            return headerMismatch(path, storedHeaderCrc, actualHeaderCrc);
        }
        long covered = header.getLong(OFFSET_PAYLOAD_LENGTH);
        long available = size - LshHashTable.HEADER_SIZE;
        if (covered < 0 || covered > available) {
            return shortPayload(path, covered, available);
        }
        long cursor = header.getLong(LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET);
        if (cursor != LshHashTable.HEADER_SIZE + covered) {
            return cursorDisagrees(path, cursor, LshHashTable.HEADER_SIZE + covered);
        }
        long storedPayloadCrc = header.getLong(OFFSET_PAYLOAD_CRC);
        long actualPayloadCrc =
            IndexChecksum.of(channel, LshHashTable.HEADER_SIZE, covered);
        if (storedPayloadCrc != actualPayloadCrc) {
            return payloadMismatch(path, covered, storedPayloadCrc, actualPayloadCrc);
        }
        return IntegrityReport.intact(path, FORMAT,
            covered + " bytes of buckets and entries verified");
    }

    private static IntegrityReport unsealed(Path path) {
        return IntegrityReport.unsealed(path, FORMAT, "the table is not sealed (a writer "
            + "holds it open, or the last writer did not close it), so its buckets cannot "
            + "be verified");
    }

    private static IntegrityReport headerMismatch(Path path, long stored, long actual) {
        return IntegrityReport.corrupt(path, FORMAT, "the header checksum is 0x"
            + Long.toHexString(stored) + " but its " + HEADER_COVERED_BYTES
            + " bytes digest to 0x" + Long.toHexString(actual) + ", so the seed, "
            + "directory capacity and allocator position in it cannot be trusted");
    }

    private static IntegrityReport shortPayload(Path path, long covered, long available) {
        return IntegrityReport.corrupt(path, FORMAT, "the header claims " + covered
            + " bytes of buckets and entries but the file holds only " + available);
    }

    /**
     * Reports a header whose two statements about where entry storage ends disagree.
     *
     * <p>The allocator cursor and the sealed payload length are written from the same value
     * and must describe the same boundary. When they do not, one of them is wrong, and both
     * failure modes are silent: a cursor that is too low hands the next insert an offset on
     * top of a live record, and a payload length that is too low leaves the tail of the
     * entry region outside the checksum that is supposed to cover it.</p>
     */
    private static IntegrityReport cursorDisagrees(Path path, long cursor, long expected) {
        return IntegrityReport.corrupt(path, FORMAT, "the header puts the end of entry "
            + "storage at " + cursor + " but its sealed payload length puts it at " + expected
            + "; the file's own offsets disagree, so either the next insert would land on a "
            + "live record or the end of the entry region is outside the checksum");
    }

    private static IntegrityReport payloadMismatch(Path path, long covered, long stored,
                                                   long actual) {
        return IntegrityReport.corrupt(path, FORMAT, "the stored checksum over " + covered
            + " bytes of buckets and entries is 0x" + Long.toHexString(stored)
            + " but they digest to 0x" + Long.toHexString(actual)
            + ", so at least one bucket or entry link has changed since it was written");
    }

    /**
     * Fills the header buffer from the start of the file, tolerating a short legacy header.
     */
    private static void readHeader(FileChannel channel, ByteBuffer header, long size)
            throws IOException {
        header.limit((int) Math.min(header.capacity(), size));
        while (header.hasRemaining()) {
            if (channel.read(header, header.position()) < 0) {
                throw new IOException("Header ended after " + header.position() + " bytes");
            }
        }
        header.clear();
    }
}
