package com.eonmux.excalibur.store.format;

import java.lang.foreign.MemorySegment;
import java.util.zip.CRC32;

/**
 * The {@value #SIZE}-byte marker that says a segment file's entries are checksummed.
 *
 * <pre>
 * 0  magic    (4 bytes, little-endian) = {@value #MAGIC}
 * 4  version  (4 bytes, little-endian) - a {@link SegmentFormat} version
 * 8  reserved (4 bytes) - zero
 * 12 crc32    (4 bytes, little-endian) - over the preceding 12 bytes
 * </pre>
 *
 * <h2>Why it sits at the head of the data region</h2>
 * <p>A segment file is {@code [slot table | data region]} with no file header, and its slot
 * count is derived from its size. Prefixing the file would shift the slot table and change
 * that derivation, and would still leave the reader guessing whether the bytes at offset 0
 * were a header or the first slot. Placing the marker at data region offset 0 instead
 * leaves the file's size arithmetic and every slot offset exactly as they were.</p>
 *
 * <h2>Why an old file cannot be mistaken for a new one</h2>
 * <p>In a version 1 file the bytes this header occupies are the first entry record:
 * {@code key_hash} over bytes 0-7, {@code key_len} over 8-11 and {@code value_len} over
 * 12-15. For {@link #detect} to be fooled, that record would have to have
 * {@code key_len == 0}, because this header's reserved field is required to be zero - and a
 * segment refuses to store an empty key, precisely so that nothing downstream can read past
 * such a record. A version 1 file with any entry in it therefore cannot present a valid
 * header, and one with no entry has nothing in its data region to mis-parse.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class SegmentDataHeader {

    /** Size of the header in bytes. */
    public static final int SIZE = 16;

    /** Magic number identifying a checksummed segment data region: "EXSD" in ASCII. */
    public static final int MAGIC = 0x45585344;

    private static final int MAGIC_OFFSET = 0;
    private static final int VERSION_OFFSET = 4;
    private static final int RESERVED_OFFSET = 8;
    private static final int CRC_OFFSET = 12;

    /** Not instantiable: this class is a reader and writer of one fixed structure. */
    private SegmentDataHeader() {
    }

    /**
     * Writes the header for {@link SegmentFormat#CURRENT} at the head of the data region.
     *
     * <p>The CRC is written last, so a write torn part-way through leaves a header that
     * {@link #detect} rejects rather than one it half-believes.</p>
     *
     * @param mapped        The mapped segment file
     * @param dataRegionBase Absolute offset at which the data region begins
     * @throws IllegalArgumentException if the mapping cannot hold the header
     */
    public static void write(MemorySegment mapped, long dataRegionBase) {
        if (dataRegionBase < 0 || dataRegionBase + SIZE > mapped.byteSize()) {
            throw new IllegalArgumentException(
                "Data region at " + dataRegionBase + " cannot hold a " + SIZE + "-byte header");
        }
        int version = SegmentFormat.CURRENT.version();
        mapped.set(SegmentLayout.INT_LAYOUT, dataRegionBase + MAGIC_OFFSET, MAGIC);
        mapped.set(SegmentLayout.INT_LAYOUT, dataRegionBase + VERSION_OFFSET, version);
        mapped.set(SegmentLayout.INT_LAYOUT, dataRegionBase + RESERVED_OFFSET, 0);
        mapped.set(SegmentLayout.INT_LAYOUT, dataRegionBase + CRC_OFFSET, checksumOf(version));
    }

    /**
     * Reads the format of a mapped segment file from its data region header.
     *
     * <p>A header is believed only if its magic number, zero reserved field and CRC all
     * agree. Anything else - including a file too small to hold a header - is a file written
     * before the header existed, and is reported as {@link SegmentFormat#V1}.</p>
     *
     * <p>A believed header naming a version this build does not know is refused, not read as
     * the legacy format. A version 1 file cannot present a sound header (see the class
     * description), so such a header proves a newer build wrote the file, and reading its
     * records at this build's widths would misread every one of them.</p>
     *
     * @param mapped         The mapped segment file
     * @param dataRegionBase Absolute offset at which the data region begins
     * @return The format the file is written in, never null
     * @throws UnsupportedSegmentFormatException if a sound header names a version this build
     *                                           does not read
     */
    public static SegmentFormat detect(MemorySegment mapped, long dataRegionBase)
            throws UnsupportedSegmentFormatException {
        if (dataRegionBase < 0 || dataRegionBase + SIZE > mapped.byteSize()) {
            return SegmentFormat.V1;
        }
        if (mapped.get(SegmentLayout.INT_LAYOUT, dataRegionBase + MAGIC_OFFSET) != MAGIC) {
            return SegmentFormat.V1;
        }
        if (mapped.get(SegmentLayout.INT_LAYOUT, dataRegionBase + RESERVED_OFFSET) != 0) {
            return SegmentFormat.V1;
        }
        int version = mapped.get(SegmentLayout.INT_LAYOUT, dataRegionBase + VERSION_OFFSET);
        int storedCrc = mapped.get(SegmentLayout.INT_LAYOUT, dataRegionBase + CRC_OFFSET);
        if (storedCrc != checksumOf(version)) {
            return SegmentFormat.V1;
        }
        SegmentFormat format = SegmentFormat.forVersion(version);
        if (format == null) {
            throw new UnsupportedSegmentFormatException(version);
        }
        return format.hasChecksum() ? format : SegmentFormat.V1;
    }

    /**
     * Computes the header CRC for a given version.
     *
     * @param version The version field's value
     * @return The CRC32 of magic, version and the zero reserved field
     */
    private static int checksumOf(int version) {
        CRC32 crc = new CRC32();
        crc.update(littleEndian(MAGIC));
        crc.update(littleEndian(version));
        crc.update(littleEndian(0));
        return (int) crc.getValue();
    }

    /**
     * Renders an int as four little-endian bytes.
     *
     * @param value The value
     * @return Its little-endian encoding
     */
    private static byte[] littleEndian(int value) {
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }
}
