package com.eonmux.excalibur.store.format;

import com.eonmux.excalibur.core.memory.Segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * The {@value #SIZE}-byte header at the start of a versioned write-ahead log.
 *
 * <pre>
 * 0  magic    (4 bytes) = "EXWL"
 * 4  version  (4 bytes, big-endian) - a {@link WriteLogFormat} version
 * 8  reserved (4 bytes) - zero
 * 12 crc32    (4 bytes, big-endian) - CRC-32 of the preceding 12 bytes
 * </pre>
 *
 * <p>Big-endian, like every field of the records that follow it: both are written through the
 * same {@link Segment} accessors.</p>
 *
 * <h2>Why a headerless log cannot be mistaken for one with a header</h2>
 * <p>In a headerless log the first byte is the first record's opcode: {@code 0x01} for a put,
 * {@code 0x02} for a delete, or zero where nothing was ever written. The magic's first byte is
 * {@code 'E'}, which is none of those, so no readable headerless log begins with it - and a
 * damaged one that happened to would still need the next three bytes to spell the rest of the
 * magic before it was taken for a header at all, and its CRC to match before it was believed.</p>
 *
 * <h2>Why an older build refuses a log with a header rather than misreading it</h2>
 * <p>For the same reason. An older build reads the magic as an opcode that is neither a put nor
 * a delete, and its scan then looks for sound records past it. A log with records behind its
 * header has them, so the older build refuses the log as damaged in the middle and does not
 * start - it neither replays nor overwrites anything. A log with no record behind its header
 * has none, so the older build reads it as a log whose only record was torn, ends it at offset
 * 0, and its first append writes over the header: nothing is lost either way.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class WriteLogHeader {

    /** Size of the header in bytes. */
    public static final int SIZE = 16;

    /** Magic number identifying a versioned write-ahead log: "EXWL" in ASCII. */
    public static final int MAGIC = 0x4558574C;

    private static final int MAGIC_OFFSET = 0;
    private static final int VERSION_OFFSET = 4;
    private static final int RESERVED_OFFSET = 8;
    private static final int CRC_OFFSET = 12;

    /** Not instantiable: this class is a reader and writer of one fixed structure. */
    private WriteLogHeader() {
    }

    /**
     * Encodes the header naming a version.
     *
     * <p>Any version, not only one this build writes, so that what a newer build's header
     * looks like can be stated exactly where it is needed - by a test, or by a tool describing
     * what it refused.</p>
     *
     * @param version The version the header names
     * @return The {@value #SIZE} header bytes
     */
    public static byte[] encode(int version) {
        ByteBuffer header = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC_OFFSET, MAGIC);
        header.putInt(VERSION_OFFSET, version);
        header.putInt(RESERVED_OFFSET, 0);
        header.putInt(CRC_OFFSET, checksumOf(header.array()));
        return header.array();
    }

    /**
     * Reports whether a log begins with the magic number, whatever the rest of the header says.
     *
     * <p>A headerless log never does (see the class description), so a log that does was written
     * with a header, and a header that is not {@linkplain #isSound sound} is a damaged one.</p>
     *
     * @param mapped The mapped log
     * @return true if the first four bytes are the magic number
     */
    public static boolean hasMagic(Segment mapped) {
        return mapped.byteSize() >= SIZE && mapped.getInt(MAGIC_OFFSET) == MAGIC;
    }

    /**
     * Reports whether a log begins with a header that can be believed.
     *
     * <p>Its magic, its zero reserved field and its CRC must all agree. The CRC is what separates
     * a newer build's header from a damaged one: both name a version this build does not know,
     * but only the newer build's also carries a checksum over it.</p>
     *
     * @param mapped The mapped log
     * @return true if the header's magic, reserved field and CRC all agree
     */
    public static boolean isSound(Segment mapped) {
        if (!hasMagic(mapped) || mapped.getInt(RESERVED_OFFSET) != 0) {
            return false;
        }
        byte[] header = mapped.getBytes(0, SIZE);
        return mapped.getInt(CRC_OFFSET) == checksumOf(header);
    }

    /**
     * Returns the version a header names, without judging whether the header is sound.
     *
     * @param mapped The mapped log
     * @return The version field
     */
    public static int version(Segment mapped) {
        return mapped.getInt(VERSION_OFFSET);
    }

    /**
     * Computes the CRC over a header's first twelve bytes.
     *
     * @param header The header bytes; only the first {@code CRC_OFFSET} are read
     * @return The CRC-32 as stored
     */
    private static int checksumOf(byte[] header) {
        CRC32 crc = new CRC32();
        crc.update(header, 0, CRC_OFFSET);
        return (int) crc.getValue();
    }
}
