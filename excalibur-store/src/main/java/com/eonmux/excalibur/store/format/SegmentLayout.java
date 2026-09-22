package com.eonmux.excalibur.store.format;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Byte offsets and widths of the structures inside a {@code segment_N.dat} file.
 *
 * <p>The segment file is {@code [slot table | data region]}. The slot table is a fixed
 * number of {@link #SLOT_SIZE}-byte slots; the data region holds variable-length entry
 * records that the slots point at. This class is the single definition of where each
 * field sits, so that the segment itself, the integrity checker and any future fsck tool
 * read the same bytes rather than each carrying its own copy of the numbers.</p>
 *
 * <p>Everything here is fixed for every format version. What varies by version - the width
 * of the entry header, whether it carries a checksum, and whether the slot table carries
 * one - lives in {@link SegmentFormat}. The slot's checksum field occupies bytes a format
 * without slot checksums simply never reads or writes, so the slot's size and every offset
 * in it are the same in all three versions.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class SegmentLayout {

    /** Size of each slot in the hash table, in bytes. */
    public static final int SLOT_SIZE = 24;

    /** Offset of the key hash within a slot: 8 bytes, little-endian. */
    public static final int SLOT_KEY_HASH_OFFSET = 0;

    /** Offset of the probe distance within a slot: 1 unsigned byte. */
    public static final int SLOT_DISTANCE_OFFSET = 8;

    /** Offset of the flag byte within a slot. */
    public static final int SLOT_FLAGS_OFFSET = 9;

    /** Offset of the 6-byte little-endian data region offset within a slot. */
    public static final int SLOT_DATA_OFFSET_OFFSET = 10;

    /** Offset of the slot's CRC32 within a slot: 4 bytes, little-endian. */
    public static final int SLOT_CHECKSUM_OFFSET = 16;

    /** Offset of the 4 reserved alignment bytes within a slot. */
    public static final int SLOT_RESERVED_OFFSET = 20;

    /**
     * Offset of the first run of slot bytes a slot checksum covers: the key hash.
     *
     * <p>The checksum covers two runs rather than one because the distance byte sits
     * between them and is deliberately left out. Robin Hood displacement rewrites the
     * distance of a slot whose entry is otherwise untouched, so a checksum over it would go
     * stale on a write to an unrelated key - the same reasoning
     * {@link #ENTRY_CHECKSUMMED_HEADER_PREFIX} records for entry records.</p>
     */
    public static final int SLOT_CHECKSUMMED_HEAD_OFFSET = SLOT_KEY_HASH_OFFSET;

    /** Width of the first checksummed run: the 8-byte key hash. */
    public static final int SLOT_CHECKSUMMED_HEAD_LENGTH = 8;

    /** Offset of the second run a slot checksum covers: the flag byte. */
    public static final int SLOT_CHECKSUMMED_TAIL_OFFSET = SLOT_FLAGS_OFFSET;

    /** Width of the second checksummed run: the flag byte and the 6-byte data offset. */
    public static final int SLOT_CHECKSUMMED_TAIL_LENGTH = 7;

    /** Total number of slot bytes a slot checksum covers. */
    public static final int SLOT_CHECKSUMMED_LENGTH =
        SLOT_CHECKSUMMED_HEAD_LENGTH + SLOT_CHECKSUMMED_TAIL_LENGTH;

    /** Width of the 6-byte little-endian data region offset within a slot. */
    public static final int SLOT_DATA_OFFSET_LENGTH = 6;

    /** Flag bit marking a slot as occupied. */
    public static final byte FLAG_OCCUPIED = 0x01;

    /** Flag bit marking a slot's entry as deleted. */
    public static final byte FLAG_TOMBSTONE = 0x02;

    /** Offset of the key hash within an entry record: 8 bytes, little-endian. */
    public static final int ENTRY_KEY_HASH_OFFSET = 0;

    /** Offset of the key length within an entry record: 4 bytes, little-endian. */
    public static final int ENTRY_KEY_LEN_OFFSET = 8;

    /** Offset of the value length within an entry record: 4 bytes, little-endian. */
    public static final int ENTRY_VALUE_LEN_OFFSET = 12;

    /** Offset of the probe distance within an entry record: 1 unsigned byte. */
    public static final int ENTRY_DISTANCE_OFFSET = 16;

    /**
     * Width of the entry fields a checksum covers: key hash, key length, value length.
     *
     * <p>The distance byte that follows them is deliberately outside it: Robin Hood
     * displacement rewrites the distance of a record that is not otherwise touched, so a
     * checksum over it would go stale on a write to a different key.</p>
     */
    public static final int ENTRY_CHECKSUMMED_HEADER_PREFIX = 16;

    /** Size of the entry trailer: the one-byte tombstone marker. */
    public static final int ENTRY_TRAILER_SIZE = 1;

    /** Little-endian 64-bit accessor for unaligned mapped memory. */
    public static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Little-endian 32-bit accessor for unaligned mapped memory. */
    public static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED
        .withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Single-byte accessor for mapped memory. */
    public static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    /** Not instantiable: this class is a set of constants. */
    private SegmentLayout() {
    }
}
