package com.eonmux.excalibur.store.format;

import java.lang.foreign.MemorySegment;
import java.util.zip.CRC32;

/**
 * Computes the CRC32 that a {@link SegmentFormat#V2} entry record carries.
 *
 * <p>There is one implementation, used both when a record is written and when it is read
 * back, and it reads the bytes out of the mapping in both cases. Writing the record first
 * and then checksumming what actually landed means the stored checksum describes the bytes
 * on disk rather than the arguments the caller passed, so the two can never drift apart
 * through a difference in how they were encoded.</p>
 *
 * <h2>Coverage</h2>
 * <p>The checksum covers the record's key hash, key length and value length - the fields
 * that say how far the record extends - and then its key and value bytes. It does not cover
 * the distance byte, which Robin Hood displacement rewrites on records it does not
 * otherwise touch, nor the tombstone trailer, which a delete rewrites. Both are metadata
 * held redundantly in the slot table; neither is payload.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public final class EntryChecksum {

    /** Bytes copied out of the mapping at a time while checksumming. */
    private static final int CHUNK_SIZE = 8192;

    /** Scratch space for {@link #update}, reused so that a read allocates nothing. */
    private static final ThreadLocal<byte[]> SCRATCH =
        ThreadLocal.withInitial(() -> new byte[CHUNK_SIZE]);

    /** Not instantiable: this class is a single computation. */
    private EntryChecksum() {
    }

    /**
     * Computes the checksum of the entry record at an offset in a mapped segment file.
     *
     * @param mapped      The mapped segment file, or a read-only view of one
     * @param entryOffset Absolute offset of the entry record
     * @param format      The format the record is written in
     * @return The CRC32 of the record's identifying header, key and value
     * @throws IndexOutOfBoundsException if the record does not lie inside the mapping
     * @throws IllegalArgumentException  if the record declares a negative length
     */
    public static int compute(MemorySegment mapped, long entryOffset, SegmentFormat format) {
        int keyLen = mapped.get(SegmentLayout.INT_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_KEY_LEN_OFFSET);
        int valueLen = mapped.get(SegmentLayout.INT_LAYOUT,
            entryOffset + SegmentLayout.ENTRY_VALUE_LEN_OFFSET);
        return compute(mapped, entryOffset, format, keyLen, valueLen);
    }

    /**
     * Computes the checksum of an entry record whose lengths the caller has already read.
     *
     * @param mapped      The mapped segment file, or a read-only view of one
     * @param entryOffset Absolute offset of the entry record
     * @param format      The format the record is written in
     * @param keyLen      The record's key length
     * @param valueLen    The record's value length
     * @return The CRC32 of the record's identifying header, key and value
     * @throws IndexOutOfBoundsException if the record does not lie inside the mapping
     * @throws IllegalArgumentException  if either length is negative
     */
    public static int compute(MemorySegment mapped, long entryOffset, SegmentFormat format,
                              int keyLen, int valueLen) {
        if (keyLen < 0 || valueLen < 0) {
            throw new IllegalArgumentException(
                "Entry at " + entryOffset + " declares negative lengths: key=" + keyLen
                    + ", value=" + valueLen);
        }
        CRC32 crc = new CRC32();
        update(crc, mapped, entryOffset, SegmentLayout.ENTRY_CHECKSUMMED_HEADER_PREFIX);
        update(crc, mapped, entryOffset + format.keyBytesOffset(), (long) keyLen + valueLen);
        return (int) crc.getValue();
    }

    /**
     * Feeds a run of mapped bytes into a checksum.
     *
     * <p>Copied through a reusable per-thread buffer rather than handed to
     * {@code CRC32.update(ByteBuffer)} directly: a buffer derived from a closeable arena -
     * which is what every segment mapping uses - cannot be passed to the intrinsic, and a
     * fresh array per read would put an allocation the size of the value on every lookup.
     * The buffer is scratch space only; nothing outlives the call.</p>
     *
     * @param crc    The checksum to update
     * @param mapped The mapping to read from
     * @param offset Absolute offset of the first byte
     * @param length Number of bytes, possibly zero
     * @throws IndexOutOfBoundsException if the run does not lie inside the mapping
     */
    private static void update(CRC32 crc, MemorySegment mapped, long offset, long length) {
        byte[] scratch = SCRATCH.get();
        long remaining = length;
        long at = offset;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, scratch.length);
            MemorySegment.copy(mapped, SegmentLayout.BYTE_LAYOUT, at, scratch, 0, chunk);
            crc.update(scratch, 0, chunk);
            at += chunk;
            remaining -= chunk;
        }
    }

    /**
     * Reads the checksum stored in an entry record.
     *
     * @param mapped      The mapped segment file, or a read-only view of one
     * @param entryOffset Absolute offset of the entry record
     * @param format      The format the record is written in
     * @return The stored CRC32
     * @throws UnsupportedOperationException if the format stores no checksum
     */
    public static int stored(MemorySegment mapped, long entryOffset, SegmentFormat format) {
        return mapped.get(SegmentLayout.INT_LAYOUT, entryOffset + format.checksumOffset());
    }

    /**
     * Writes the checksum of an already-written entry record into that record.
     *
     * @param mapped      The mapped segment file
     * @param entryOffset Absolute offset of the entry record
     * @param format      The format the record is written in
     * @throws UnsupportedOperationException if the format stores no checksum
     */
    public static void store(MemorySegment mapped, long entryOffset, SegmentFormat format) {
        mapped.set(SegmentLayout.INT_LAYOUT, entryOffset + format.checksumOffset(),
            compute(mapped, entryOffset, format));
    }
}
