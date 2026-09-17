package excalibur.filter.lsh;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * The identifying fields at the front of an LSH table image, and the one place they are read.
 *
 * <p>Three entry points open a table - the file constructor, {@code LshHashTable.load} and
 * {@code LshHashTable.fromMemorySegment} - and each used to carry its own copy of the magic
 * check, the list of versions it would accept and the rule placing the bucket directory. The
 * copies had drifted apart, and every way they can drift is silent:</p>
 *
 * <ul>
 *   <li>a version one reader accepts and another refuses throws out of a constructor after
 *       the caller has already opened the mapping, which leaks it;</li>
 *   <li>a directory placed at the wrong offset reads bucket rows out of bytes that hold none,
 *       so the table opens, reports its full entry count and answers every query empty.</li>
 * </ul>
 *
 * <p>Neither shows up as an error, which is why the rule lives here rather than three times.</p>
 *
 * @param version         The format version the image is written at
 * @param numBits         Hash bits the table was built with
 * @param dimensions      Vector dimensionality the table was built for
 * @param seed            The seed its buckets were filled with; hashing with any other seed
 *                        sends every vector to a bucket that does not hold its entries
 * @param bucketDirOffset Where its bucket directory starts, fixed by the version
 */
record LshTableHeader(int version, int numBits, int dimensions, long seed,
                      long bucketDirOffset) {

    private static final ValueLayout.OfInt INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * Reports whether this build can read a file written at the given format version.
     *
     * @param version The version read from a header
     * @return true when the file can be read
     */
    static boolean isSupportedVersion(int version) {
        return version == LshHashTable.VERSION
            || version == LshHashTable.VERSION_WITHOUT_CHECKSUM;
    }

    /**
     * Reports where a file at the given version keeps its bucket directory.
     *
     * @param version A supported format version
     * @return The offset the directory starts at
     */
    static long bucketDirOffsetFor(int version) {
        return version == LshHashTable.VERSION
            ? LshHashTable.HEADER_SIZE : LshHashTable.HEADER_SIZE_V2;
    }

    /**
     * Reads and validates the front of a table image.
     *
     * @param segment The image, positioned at its own offset zero
     * @return What the header says about the table behind it
     * @throws IllegalArgumentException if there is no table header this build can read there
     */
    static LshTableHeader read(MemorySegment segment) {
        if (segment.byteSize() < LshHashTable.HEADER_SIZE_V2) {
            throw new IllegalArgumentException("Segment too small: " + segment.byteSize()
                + " < " + LshHashTable.HEADER_SIZE_V2);
        }
        int magic = segment.get(INT, LshHashTable.OFFSET_MAGIC);
        if (magic != LshHashTable.MAGIC) {
            throw new IllegalArgumentException(
                "Invalid magic number: 0x" + Integer.toHexString(magic));
        }
        int version = segment.get(INT, LshHashTable.OFFSET_VERSION);
        if (!isSupportedVersion(version)) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
        long bucketDirOffset = bucketDirOffsetFor(version);
        if (segment.byteSize() < bucketDirOffset) {
            throw new IllegalArgumentException("Segment too small for a version " + version
                + " header: " + segment.byteSize() + " < " + bucketDirOffset);
        }
        return new LshTableHeader(version,
            segment.get(INT, LshHashTable.OFFSET_NUM_BITS),
            segment.get(INT, LshHashTable.OFFSET_DIMENSIONS),
            segment.get(LONG, LshHashTable.OFFSET_SEED),
            bucketDirOffset);
    }
}
