package com.eonmux.excalibur.graph.format;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * The one file in an edge store that carries metadata, and the only place the format's decisions
 * are recorded.
 *
 * <p>Identifier width, offset width and byte order are all written down here rather than assumed,
 * so a store built by a different build is <em>detected</em> at open time instead of being read as
 * plausible nonsense. That is the whole reason this file exists: every other file in the store is a
 * bare array with nothing in it to disagree with.
 *
 * <p>Written with {@link DataOutputStream}, so this file alone is big-endian — Java's default for
 * that class, and irrelevant to performance for a few hundred bytes read once at open. The column
 * files it describes are little-endian, which is a hot-path decision recorded in
 * {@link #byteOrder()}.
 *
 * <h2>Layout</h2>
 * <pre>
 * [magic:8][version:4][byte order:1][entity id bytes:1][edge offset bytes:1][relation id bytes:1]
 * [entity count:8][edge count:8]                                  version 1 ends here, 32 bytes
 * [column count:4] then per column, in {@link EdgeColumn} order: [length:8][CRC32C:4]
 * [CRC32C of every byte before it:4]                              version 2
 * </pre>
 *
 * <h2>Why version 2 records a digest per column</h2>
 * <p>The size check at open catches a column that lost bytes and nothing else. A flipped bit in
 * {@code edges_fwd.csr} leaves the file the length the counts imply, and changes only which
 * entity an edge points at: the store opens, the traversal runs, and the answer names the wrong
 * neighbour with nothing anywhere to say so. So a build records each column's CRC32C here, as
 * it streams the column out, and the store refuses a column that no longer digests to it. The
 * table lives in this file rather than in the columns because a column is a bare array whose
 * every byte is an element, and because this file is already the store's commit record: it is
 * written last and moved into place atomically, so no digest can become visible ahead of the
 * bytes it describes. The table then carries a checksum of its own, because a record of
 * checksums is only worth as much as the bytes holding it.</p>
 *
 * <p>A version 1 descriptor, written before the table existed, is read as before and reported
 * as unprotected: no checksum is computed for it now, because one written over bytes nothing
 * vouched for would vouch for them. The store is derived entirely from the namespace's staging
 * logs, so the next build replaces it with one at version 2.</p>
 */
public record EdgeStoreDescriptor(
    int formatVersion,
    ByteOrder byteOrder,
    int entityIdBytes,
    int edgeOffsetBytes,
    int relationIdBytes,
    long entityCount,
    long edgeCount,
    Map<EdgeColumn, ColumnDigest> digests
) {

    /** File name of the descriptor within an edge store directory. */
    public static final String FILE_NAME = "edgestore.meta";

    /** Name this file goes by in a refusal or a report. */
    public static final String FORMAT = "edge store descriptor";

    /** Identifies the file as an ExcaliburDB edge store: ASCII {@code EXCLBEDG}. */
    public static final long MAGIC = 0x4558434C42454447L;

    /** Format version written by this build: the first to record a digest per column. */
    public static final int CURRENT_VERSION = 2;

    /** Oldest format version this build reads: the one written before columns carried digests. */
    public static final int OLDEST_VERSION = 1;

    /** Entity ids are 32-bit. See the width rationale on {@link #of}. */
    public static final int ENTITY_ID_BYTES = Integer.BYTES;

    /** Edge offsets are 64-bit. */
    public static final int EDGE_OFFSET_BYTES = Long.BYTES;

    /** Relation ids are 32-bit. */
    public static final int RELATION_ID_BYTES = Integer.BYTES;

    /**
     * Byte order of every column file.
     *
     * <p>Little-endian, and stated rather than inherited: this repo has no single convention to
     * follow ({@code core}'s {@code MappedFile} is big-endian, {@code store}'s {@code SegmentEntry}
     * is little-endian). A columnar store exists to be scanned without allocating, and on every
     * machine this runs on little-endian is a plain load where big-endian costs a byte swap per
     * element and blocks bulk copies. Recording it is what keeps that a portable choice rather than
     * a third silent convention.
     */
    public static final ByteOrder COLUMN_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** Bytes of the fields every version carries, which is the whole of version 1. */
    private static final int FIXED_BYTES = 32;

    /** Bytes of one column's entry in the version 2 table: length and CRC32C. */
    private static final int DIGEST_BYTES = Long.BYTES + Integer.BYTES;

    /**
     * Largest descriptor this will read into memory.
     *
     * <p>Far above the few hundred bytes a real one holds, so a file that claims to be one and
     * is gigabytes long is refused rather than loaded.</p>
     */
    private static final int MAX_BYTES = 64 * 1024;

    private static final byte ORDER_LITTLE = 0;
    private static final byte ORDER_BIG = 1;

    /**
     * Validates the descriptor.
     *
     * @throws IllegalArgumentException if a count is negative, the entity count exceeds what the
     *         id width addresses, or the digests do not describe exactly the columns this
     *         version records at the sizes the counts imply
     */
    public EdgeStoreDescriptor {
        if (byteOrder == null) {
            throw new IllegalArgumentException("byteOrder must not be null");
        }
        if (entityCount < 0) {
            throw new IllegalArgumentException("entityCount must not be negative: " + entityCount);
        }
        if (edgeCount < 0) {
            throw new IllegalArgumentException("edgeCount must not be negative: " + edgeCount);
        }
        if (entityIdBytes == Integer.BYTES && entityCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "entityCount " + entityCount + " exceeds what a " + entityIdBytes
                    + "-byte entity id can address");
        }
        digests = digests == null || digests.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new EnumMap<>(digests));
        if (formatVersion < 2 && !digests.isEmpty()) {
            throw new IllegalArgumentException(
                "a version " + formatVersion + " descriptor records no column digests");
        }
        if (formatVersion >= 2) {
            requireDigests(digests, entityCount, edgeCount);
        }
    }

    /**
     * Refuses a digest table that does not describe every column at the size the counts imply.
     *
     * @param digests     The table
     * @param entityCount The store's entity count
     * @param edgeCount   The store's edge count
     * @throws IllegalArgumentException if a column is missing or recorded at the wrong length
     */
    private static void requireDigests(Map<EdgeColumn, ColumnDigest> digests, long entityCount,
                                       long edgeCount) {
        for (EdgeColumn column : EdgeColumn.values()) {
            ColumnDigest digest = digests.get(column);
            if (digest == null) {
                throw new IllegalArgumentException("no digest recorded for " + column);
            }
            long expected = column.byteSize(entityCount, edgeCount);
            if (digest.length() != expected) {
                throw new IllegalArgumentException(column.fileName() + " is recorded at "
                    + digest.length() + " bytes where " + entityCount + " entities and "
                    + edgeCount + " edges make it " + expected);
            }
        }
    }

    /**
     * A descriptor for a store of the given size, at this build's widths, recording the digest of
     * every column the build wrote.
     *
     * <p>The widths are the format's central decision and are fixed here:
     * <ul>
     *   <li><b>entity id, 32-bit.</b> This element repeats once per edge and is the largest file in
     *       the store, so its width sets the memory budget — at a billion edges, 4 GB against 8 GB.
     *       2.1 billion entities is far beyond the ~115M in Wikidata and further still beyond any
     *       single tenant's conversation graph.</li>
     *   <li><b>edge offset, 64-bit.</b> Indexed by entity, so the array is short and the width is
     *       nearly free, but the values are edge positions and total edge count crosses 2^31 long
     *       before entity count does. 32 bits here is an overflow that only appears at scale.</li>
     *   <li><b>relation id, 32-bit.</b> One per edge, so 16 bits would halve a 4 GB file; rejected
     *       because Wikidata already has ~12k properties against a 32k ceiling, and exhausting
     *       relation ids means re-ingesting everything.</li>
     * </ul>
     *
     * @param entityCount How many entity ids the store addresses
     * @param edgeCount   How many edges it holds
     * @param digests     Every column's length and CRC32C, as written
     * @return The descriptor
     * @throws IllegalArgumentException if the digests do not describe every column at its size
     */
    public static EdgeStoreDescriptor of(long entityCount, long edgeCount,
                                         Map<EdgeColumn, ColumnDigest> digests) {
        if (digests == null) {
            throw new IllegalArgumentException("digests must not be null");
        }
        return new EdgeStoreDescriptor(
            CURRENT_VERSION, COLUMN_BYTE_ORDER,
            ENTITY_ID_BYTES, EDGE_OFFSET_BYTES, RELATION_ID_BYTES,
            entityCount, edgeCount, digests);
    }

    /** Expected size in bytes of one of this store's column files. */
    public long byteSizeOf(EdgeColumn column) {
        return column.byteSize(entityCount, edgeCount);
    }

    /**
     * Whether this descriptor records a digest for every column.
     *
     * @return false only for a version 1 descriptor, written before columns carried digests
     */
    public boolean checksummed() {
        return !digests.isEmpty();
    }

    /**
     * The digest recorded for one column.
     *
     * @param column The column
     * @return Its length and CRC32C
     * @throws IllegalStateException if this descriptor predates column digests
     */
    public ColumnDigest digestOf(EdgeColumn column) {
        ColumnDigest digest = digests.get(column);
        if (digest == null) {
            throw new IllegalStateException("a version " + formatVersion
                + " descriptor records no column digests");
        }
        return digest;
    }

    /**
     * Writes the descriptor into {@code directory}, atomically and durably.
     *
     * @param directory The store directory
     * @throws IOException if the descriptor cannot be written, forced or moved into place
     */
    public void write(Path directory) throws IOException {
        write(directory, Durability.DEVICE);
    }

    /**
     * As above, with how the descriptor is forced to the device under the caller's control.
     *
     * <p>Written to a sibling and moved into place so that a store is never observed with a
     * descriptor that describes a size its columns have not reached.
     *
     * <p>Forced before the move, because this file is what makes a directory a store at all and
     * the ordering the format depends on is worth nothing if it only holds against the page
     * cache. A rename that reached the platter ahead of its own contents names a descriptor of
     * zeros; a durable descriptor whose directory entry is not durable can come back with no
     * name pointing at it. Both are a store that will not open, and the caller of a build has
     * already deleted the graph it replaced.
     *
     * <p>Written in the layout of its own {@link #formatVersion()}, so a version 1 descriptor
     * read back is written back as one.
     *
     * @param directory  The store directory
     * @param durability How the descriptor is forced before and after it takes its name
     * @throws IOException if the descriptor cannot be written, forced or moved into place
     * @throws IllegalArgumentException if {@code durability} is null
     */
    public void write(Path directory, Durability durability) throws IOException {
        if (durability == null) {
            throw new IllegalArgumentException("durability must not be null");
        }
        Path target = directory.resolve(FILE_NAME);
        Path temp = directory.resolve(FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(encode());
        }
        try {
            durability.replaceDurably(temp, target);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * The bytes of this descriptor, in the layout of its version.
     *
     * @return The encoded descriptor
     */
    private byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(MAGIC);
            out.writeInt(formatVersion);
            out.writeByte(byteOrder == ByteOrder.BIG_ENDIAN ? ORDER_BIG : ORDER_LITTLE);
            out.writeByte(entityIdBytes);
            out.writeByte(edgeOffsetBytes);
            out.writeByte(relationIdBytes);
            out.writeLong(entityCount);
            out.writeLong(edgeCount);
            if (formatVersion >= 2) {
                out.writeInt(EdgeColumn.values().length);
                for (EdgeColumn column : EdgeColumn.values()) {
                    ColumnDigest digest = digests.get(column);
                    out.writeLong(digest.length());
                    out.writeInt((int) digest.checksum());
                }
                out.flush();
                out.writeInt((int) crc32c(bytes.toByteArray(), bytes.size()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing to memory cannot fail", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Reads the descriptor from {@code directory} and checks it against this build.
     *
     * @param directory The store directory
     * @return The descriptor
     * @throws GraphCorruptionException   if the file is missing, is not an edge store descriptor,
     *                                    is cut short, or does not match the checksum it carries
     * @throws UnsupportedVersionException if it names a format version this build does not read
     * @throws IncompatibleStoreException if it was written with widths this build cannot read
     * @throws IOException                if the file cannot be read
     */
    public static EdgeStoreDescriptor read(Path directory) throws IOException {
        Path path = directory.resolve(FILE_NAME);
        byte[] bytes = readBounded(path);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (bytes.length < Long.BYTES + Integer.BYTES) {
            throw truncated(path, bytes.length);
        }
        long magic = in.getLong();
        if (magic != MAGIC) {
            throw new GraphCorruptionException(path,
                "not an edge store descriptor: " + path + " starts with 0x"
                    + Long.toHexString(magic) + ", expected 0x" + Long.toHexString(MAGIC));
        }
        int version = in.getInt();
        if (version < OLDEST_VERSION || version > CURRENT_VERSION) {
            throw new UnsupportedVersionException(directory, version);
        }
        if (bytes.length < FIXED_BYTES) {
            throw truncated(path, bytes.length);
        }
        if (version >= 2) {
            requireSealed(path, bytes);
        }
        byte order = in.get();
        ByteOrder byteOrder = order == ORDER_BIG ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        int entityIdBytes = in.get();
        int edgeOffsetBytes = in.get();
        int relationIdBytes = in.get();
        long entityCount = in.getLong();
        long edgeCount = in.getLong();

        checkWidth("entity id", entityIdBytes, ENTITY_ID_BYTES, directory);
        checkWidth("edge offset", edgeOffsetBytes, EDGE_OFFSET_BYTES, directory);
        checkWidth("relation id", relationIdBytes, RELATION_ID_BYTES, directory);

        Map<EdgeColumn, ColumnDigest> digests = version >= 2
            ? readDigests(path, in)
            : Map.of();
        try {
            return new EdgeStoreDescriptor(version, byteOrder, entityIdBytes, edgeOffsetBytes,
                relationIdBytes, entityCount, edgeCount, digests);
        } catch (IllegalArgumentException e) {
            // Every byte matched the checksum the build wrote, so this is a descriptor that was
            // wrong when it was written; it describes no store that can be served either way.
            throw new GraphCorruptionException(path,
                "edge store descriptor " + path + " does not describe a store: "
                    + e.getMessage());
        }
    }

    /**
     * Reads a descriptor's bytes, refusing one far larger than any descriptor is.
     *
     * @param path The descriptor
     * @return Its bytes
     * @throws GraphCorruptionException if it is missing or implausibly large
     * @throws IOException if it cannot be read
     */
    private static byte[] readBounded(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > MAX_BYTES) {
                throw new GraphCorruptionException(path, "edge store descriptor " + path
                    + " is " + size + " bytes, far larger than any descriptor; it is not one");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read until full or the end of the file.
            }
            return java.util.Arrays.copyOf(buffer.array(), buffer.position());
        } catch (NoSuchFileException e) {
            throw new GraphCorruptionException(path, "edge store descriptor " + path
                + " is missing, so nothing says what the directory's columns hold");
        }
    }

    /**
     * Refuses a version 2 descriptor that is not exactly as long as its table, or whose bytes do
     * not match the checksum at its end.
     *
     * @param path  The descriptor, named in the refusal
     * @param bytes Its contents
     * @throws GraphCorruptionException if either check fails
     */
    private static void requireSealed(Path path, byte[] bytes) throws GraphCorruptionException {
        if (bytes.length < FIXED_BYTES + Integer.BYTES + Integer.BYTES) {
            throw truncated(path, bytes.length);
        }
        int columns = ByteBuffer.wrap(bytes, FIXED_BYTES, Integer.BYTES).getInt();
        long expected = columns < 0 || columns > MAX_BYTES
            ? -1
            : FIXED_BYTES + Integer.BYTES + (long) columns * DIGEST_BYTES + Integer.BYTES;
        if (expected != bytes.length) {
            throw new GraphCorruptionException(path, "edge store descriptor " + path + " is "
                + bytes.length + " bytes, but the column table it declares ("
                + columns + " columns) makes it " + expected
                + "; it was cut short or altered");
        }
        long stored = Integer.toUnsignedLong(
            ByteBuffer.wrap(bytes, bytes.length - Integer.BYTES, Integer.BYTES).getInt());
        long actual = crc32c(bytes, bytes.length - Integer.BYTES);
        if (stored != actual) {
            throw new GraphCorruptionException(path, "edge store descriptor " + path
                + " does not match the checksum it was written with (stored 0x"
                + Long.toHexString(stored) + ", computed 0x" + Long.toHexString(actual)
                + "), so neither its counts nor its column digests can be believed");
        }
    }

    /**
     * Reads the column table of a descriptor whose checksum has already matched.
     *
     * @param path The descriptor, named in a refusal
     * @param in   Positioned at the column count
     * @return The digests, keyed by column
     * @throws GraphCorruptionException if the table does not describe this format's columns
     */
    private static Map<EdgeColumn, ColumnDigest> readDigests(Path path, ByteBuffer in)
            throws GraphCorruptionException {
        int columns = in.getInt();
        if (columns != EdgeColumn.values().length) {
            // Positional, so a table of another length cannot be matched up column by column:
            // there is no way to know which column went missing or was added.
            throw new GraphCorruptionException(path, "edge store descriptor " + path
                + " records digests for " + columns + " column(s) where this format has "
                + EdgeColumn.values().length);
        }
        Map<EdgeColumn, ColumnDigest> digests = new EnumMap<>(EdgeColumn.class);
        for (EdgeColumn column : EdgeColumn.values()) {
            long length = in.getLong();
            long checksum = Integer.toUnsignedLong(in.getInt());
            if (length < 0) {
                throw new GraphCorruptionException(path, "edge store descriptor " + path
                    + " records a negative length for " + column.fileName());
            }
            digests.put(column, new ColumnDigest(length, checksum));
        }
        return digests;
    }

    /**
     * The refusal for a descriptor that ends before its fields do.
     *
     * @param path  The descriptor
     * @param bytes How long it is
     * @return The exception
     */
    private static GraphCorruptionException truncated(Path path, int bytes) {
        return new GraphCorruptionException(path,
            "edge store descriptor truncated: " + path + " is " + bytes + " bytes");
    }

    /**
     * CRC32C of the first {@code length} bytes of {@code bytes}.
     *
     * @param bytes  The bytes
     * @param length How many to digest
     * @return The checksum, as an unsigned value in a long
     */
    private static long crc32c(byte[] bytes, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, length);
        return crc.getValue();
    }

    private static void checkWidth(String what, int found, int expected, Path directory) {
        if (found != expected) {
            throw new IncompatibleStoreException(
                "edge store at " + directory + " uses a " + found + "-byte " + what
                    + "; this build uses " + expected
                    + ". Widths are fixed at ingest and cannot be reinterpreted: rebuild the store.");
        }
    }

    /**
     * Thrown when a store on disk cannot be read by this build.
     *
     * <p>Not damage: nothing shows the bytes are wrong, only that they were written by a build
     * that made different decisions. So it is unchecked and distinct from
     * {@link GraphCorruptionException}, and a node that meets it refuses the namespace rather
     * than treating its graph as something to replace.</p>
     */
    public static class IncompatibleStoreException extends UncheckedIOException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message What this build could not read, and why
         */
        public IncompatibleStoreException(String message) {
            super(new IOException(message));
        }
    }

    /** Thrown when a descriptor names a format version this build does not read. */
    public static final class UnsupportedVersionException extends IncompatibleStoreException {

        private static final long serialVersionUID = 1L;

        /** The version the descriptor names. */
        private final int version;

        /**
         * @param directory The store directory
         * @param version   The version its descriptor names
         */
        public UnsupportedVersionException(Path directory, int version) {
            super("edge store at " + directory + " is format version " + version
                + "; this build reads versions " + OLDEST_VERSION + " to " + CURRENT_VERSION);
            this.version = version;
        }

        /**
         * The version the descriptor names.
         *
         * @return The version
         */
        public int version() {
            return version;
        }
    }
}
