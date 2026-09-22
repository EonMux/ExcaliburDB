package com.eonmux.excalibur.core.integrity;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32C;

/**
 * CRC32C over a region of a persisted index file.
 *
 * <p>CRC32C rather than CRC32: the same detection strength, and the hardware instruction for
 * it exists on every CPU this runs on, so checksumming a multi-gigabyte graph on open costs
 * memory bandwidth rather than CPU time.</p>
 *
 * <p>Regions are read in bounded chunks. An index file is routinely larger than a byte array
 * may be, and larger than the heap, so neither the mapped nor the channel form may materialize
 * the region it covers.</p>
 */
public final class IndexChecksum {

    /** How much of a region is digested per pass. */
    private static final int CHUNK_BYTES = 64 * 1024;

    private IndexChecksum() {
    }

    /**
     * Digests a region of a memory segment.
     *
     * @param segment The segment to read
     * @param offset  Start of the region, in bytes from the segment's base
     * @param length  Length of the region in bytes
     * @return The CRC32C of the region, as an unsigned value in a long
     * @throws IllegalArgumentException if the region is negative or runs past the segment
     */
    public static long of(MemorySegment segment, long offset, long length) {
        requireRegion(offset, length, segment.byteSize(), "segment");
        CRC32C digest = new CRC32C();
        byte[] buffer = new byte[(int) Math.min(CHUNK_BYTES, Math.max(length, 1))];
        long done = 0;
        while (done < length) {
            int step = (int) Math.min(buffer.length, length - done);
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset + done, buffer, 0, step);
            digest.update(buffer, 0, step);
            done += step;
        }
        return digest.getValue();
    }

    /**
     * Digests a region of a byte array.
     *
     * @param bytes  The array to read
     * @param offset Start of the region
     * @param length Length of the region
     * @return The CRC32C of the region, as an unsigned value in a long
     * @throws IllegalArgumentException if the region runs past the array
     */
    public static long of(byte[] bytes, int offset, int length) {
        requireRegion(offset, length, bytes.length, "array");
        CRC32C digest = new CRC32C();
        digest.update(bytes, offset, length);
        return digest.getValue();
    }

    /**
     * Digests a region of a file without mapping it.
     *
     * <p>The verification entry points use this form: a checking tool must be able to read a
     * file it is not prepared to serve, and mapping one is both an allocation of address space
     * and, for a file that turns out to be short, a fault rather than an exception.</p>
     *
     * @param channel The channel to read, positioned anywhere
     * @param offset  Start of the region, in bytes from the start of the file
     * @param length  Length of the region in bytes
     * @return The CRC32C of the region, as an unsigned value in a long
     * @throws IOException if the region cannot be read in full
     */
    public static long of(FileChannel channel, long offset, long length) throws IOException {
        requireRegion(offset, length, Long.MAX_VALUE, "file");
        CRC32C digest = new CRC32C();
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(CHUNK_BYTES, Math.max(length, 1)));
        long done = 0;
        while (done < length) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), length - done));
            int read = channel.read(buffer, offset + done);
            if (read <= 0) {
                throw new IOException("Region of " + length + " bytes at offset " + offset
                    + " is truncated: only " + done + " bytes could be read");
            }
            buffer.flip();
            digest.update(buffer);
            done += read;
        }
        return digest.getValue();
    }

    /**
     * Rejects a region that cannot exist before any of it is read.
     */
    private static void requireRegion(long offset, long length, long available, String what) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException(
                "Checksum region cannot be negative: offset=" + offset + ", length=" + length);
        }
        if (length > available - offset) {
            throw new IllegalArgumentException("Checksum region [" + offset + ", "
                + (offset + length) + ") runs past the end of the " + what
                + " (" + available + " bytes)");
        }
    }
}
