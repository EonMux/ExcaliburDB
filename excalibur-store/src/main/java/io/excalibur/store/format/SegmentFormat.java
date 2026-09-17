package io.excalibur.store.format;

/**
 * On-disk format of a {@code segment_N.dat} file's slot table and entry records.
 *
 * <p>Which version a file is written in is decided once, when the file is created, and
 * never changes while it exists: the entry header width is baked into every record, so a
 * file cannot hold records of both shapes, and a slot table that carries no checksums
 * cannot start carrying them without every slot being rewritten. A file written in an older
 * version is upgraded only by being rewritten - a split or a compaction produces a new file,
 * and new files are always {@link #CURRENT}.</p>
 *
 * <h2>V1 - the original format</h2>
 * <pre>
 * 0  key_hash  (8 bytes, little-endian)
 * 8  key_len   (4 bytes, little-endian)
 * 12 value_len (4 bytes, little-endian)
 * 16 distance  (1 byte, unsigned)
 * 17 key bytes, then value bytes, then a 1-byte tombstone
 * </pre>
 * <p>V1 carries no integrity information at all. A V1 file is still read, and still
 * written to, but its entries cannot be verified: a read from one returns whatever the
 * bytes say, and {@code verifyIntegrity} reports its entries as unverifiable rather than
 * as sound.</p>
 *
 * <h2>V2 - checksummed entries</h2>
 * <pre>
 * 0  key_hash  (8 bytes, little-endian)
 * 8  key_len   (4 bytes, little-endian)
 * 12 value_len (4 bytes, little-endian)
 * 16 distance  (1 byte, unsigned)
 * 17 crc32     (4 bytes, little-endian)
 * 21 key bytes, then value bytes, then a 1-byte tombstone
 * </pre>
 * <p>The CRC32 covers the first {@value SegmentLayout#ENTRY_CHECKSUMMED_HEADER_PREFIX}
 * bytes of the record - the key hash and the two lengths, which is what says how far the
 * record extends - followed by the key bytes and the value bytes. It deliberately excludes
 * the distance byte and the tombstone byte, both of which are rewritten in place after the
 * record is appended and neither of which carries payload.</p>
 *
 * <p>A V2 file is marked as such by {@link SegmentDataHeader}, written at offset 0 of the
 * data region. A file without that marker is V1.</p>
 *
 * <p>V2 protects the entry records and nothing else. A segment file is
 * {@code [slot table | data region]}, and a record is only ever reached through the slot
 * that points at it, so a V2 file whose slot table lost a bit hands back a wrong answer that
 * every entry checksum in the file agrees with: the entry a damaged slot no longer names
 * reads as simply absent.</p>
 *
 * <h2>V3 - checksummed slots</h2>
 * <p>Entry records are laid out exactly as in V2. What V3 adds is a CRC32 in each slot,
 * written into bytes 16-19 of the 24-byte slot, which earlier versions reserved for
 * alignment and never wrote:</p>
 * <pre>
 * 0  key_hash   (8 bytes, little-endian)
 * 8  distance   (1 byte, unsigned)
 * 9  flags      (1 byte)
 * 10 data_offset(6 bytes, little-endian)
 * 16 crc32      (4 bytes, little-endian) - over key_hash, flags and data_offset
 * 20 reserved   (4 bytes) - zero
 * </pre>
 * <p>The distance byte is outside the slot's checksum for the reason it is outside the
 * record's: Robin Hood displacement rewrites the distance of a slot whose entry is not
 * otherwise touched. See {@link SlotChecksum}.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public enum SegmentFormat {

    /** The original, unchecksummed format. Read and written, but never created. */
    V1(1, false, false),

    /** Checksummed entries, unprotected slot table. Read and written, but never created. */
    V2(2, true, false),

    /** The current format: every entry and every slot carries a CRC32. */
    V3(3, true, true);

    /** The format every newly created segment file is written in. */
    public static final SegmentFormat CURRENT = V3;

    private final int version;
    private final boolean checksummed;
    private final boolean slotChecksummed;

    /**
     * Defines a format version.
     *
     * @param version         The number stored in the data region header
     * @param checksummed     Whether each entry carries a CRC32
     * @param slotChecksummed Whether each slot carries a CRC32
     */
    SegmentFormat(int version, boolean checksummed, boolean slotChecksummed) {
        this.version = version;
        this.checksummed = checksummed;
        this.slotChecksummed = slotChecksummed;
    }

    /**
     * Returns the version number this format is recorded as on disk.
     *
     * @return The format version
     */
    public int version() {
        return version;
    }

    /**
     * Reports whether entries in this format carry a checksum.
     *
     * @return true if a read can be verified, false if the format predates checksums
     */
    public boolean hasChecksum() {
        return checksummed;
    }

    /**
     * Reports whether slots in this format carry a checksum.
     *
     * <p>Separate from {@link #hasChecksum()} because the two protect different things and
     * arrived in different versions: an entry checksum says the record's bytes are the bytes
     * that were written, and a slot checksum says the pointer that reached them is the
     * pointer that was written. A file with the first and not the second can still turn a
     * present entry into an absent one.</p>
     *
     * @return true if a slot can be verified, false if the format predates slot checksums
     */
    public boolean hasSlotChecksum() {
        return slotChecksummed;
    }

    /**
     * Returns the offset of an entry's CRC32 within its record.
     *
     * @return The offset in bytes
     * @throws UnsupportedOperationException if this format has no checksum
     */
    public int checksumOffset() {
        if (!checksummed) {
            throw new UnsupportedOperationException(this + " entries carry no checksum");
        }
        return SegmentLayout.ENTRY_DISTANCE_OFFSET + 1;
    }

    /**
     * Returns the number of bytes an entry record spends on its header.
     *
     * @return The header width in bytes
     */
    public int entryHeaderSize() {
        return checksummed ? SegmentLayout.ENTRY_DISTANCE_OFFSET + 5
            : SegmentLayout.ENTRY_DISTANCE_OFFSET + 1;
    }

    /**
     * Returns the offset at which an entry's key bytes begin within its record.
     *
     * @return The offset in bytes, which is the header width
     */
    public int keyBytesOffset() {
        return entryHeaderSize();
    }

    /**
     * Returns the data region offset at which the first entry record may be written.
     *
     * <p>V2 reserves the head of the data region for {@link SegmentDataHeader}; V1 starts
     * its first record at zero because it has no header to make room for.</p>
     *
     * @return The first writable data region offset
     */
    public long firstEntryOffset() {
        return checksummed ? SegmentDataHeader.SIZE : 0L;
    }

    /**
     * Returns the format recorded under a given version number.
     *
     * @param version The version number read from a data region header
     * @return The matching format, or null if this build does not know that version
     */
    public static SegmentFormat forVersion(int version) {
        for (SegmentFormat format : values()) {
            if (format.version == version) {
                return format;
            }
        }
        return null;
    }
}
