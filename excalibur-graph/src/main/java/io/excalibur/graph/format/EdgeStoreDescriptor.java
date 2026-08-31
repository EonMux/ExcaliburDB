package io.excalibur.graph.format;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
 * that class, and irrelevant to performance for a hundred bytes read once at open. The column files
 * it describes are little-endian, which is a hot-path decision recorded in {@link #byteOrder()}.
 */
public record EdgeStoreDescriptor(
    int formatVersion,
    ByteOrder byteOrder,
    int entityIdBytes,
    int edgeOffsetBytes,
    int relationIdBytes,
    long entityCount,
    long edgeCount
) {

    /** File name of the descriptor within an edge store directory. */
    public static final String FILE_NAME = "edgestore.meta";

    /** Identifies the file as an ExcaliburDB edge store: ASCII {@code EXCLBEDG}. */
    public static final long MAGIC = 0x4558434C42454447L;

    /** Format version written by this build. */
    public static final int CURRENT_VERSION = 1;

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

    private static final byte ORDER_LITTLE = 0;
    private static final byte ORDER_BIG = 1;

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
    }

    /**
     * A descriptor for a store of the given size, at this build's widths.
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
     */
    public static EdgeStoreDescriptor of(long entityCount, long edgeCount) {
        return new EdgeStoreDescriptor(
            CURRENT_VERSION, COLUMN_BYTE_ORDER,
            ENTITY_ID_BYTES, EDGE_OFFSET_BYTES, RELATION_ID_BYTES,
            entityCount, edgeCount);
    }

    /** Expected size in bytes of one of this store's column files. */
    public long byteSizeOf(EdgeColumn column) {
        return column.byteSize(entityCount, edgeCount);
    }

    /**
     * Writes the descriptor into {@code directory}, atomically.
     *
     * <p>Written to a sibling and moved into place so that a store is never observed with a
     * descriptor that describes a size its columns have not reached.
     */
    public void write(Path directory) throws IOException {
        Path target = directory.resolve(FILE_NAME);
        Path temp = directory.resolve(FILE_NAME + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(temp))) {
            out.writeLong(MAGIC);
            out.writeInt(formatVersion);
            out.writeByte(byteOrder == ByteOrder.BIG_ENDIAN ? ORDER_BIG : ORDER_LITTLE);
            out.writeByte(entityIdBytes);
            out.writeByte(edgeOffsetBytes);
            out.writeByte(relationIdBytes);
            out.writeLong(entityCount);
            out.writeLong(edgeCount);
        }
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Reads the descriptor from {@code directory} and checks it against this build.
     *
     * @throws IOException             if the file is missing or truncated
     * @throws IncompatibleStoreException if the file is not an edge store, or was written with
     *                                 widths or a version this build cannot read
     */
    public static EdgeStoreDescriptor read(Path directory) throws IOException {
        Path path = directory.resolve(FILE_NAME);
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            long magic = in.readLong();
            if (magic != MAGIC) {
                throw new IncompatibleStoreException(
                    "not an edge store: " + path + " starts with 0x" + Long.toHexString(magic)
                        + ", expected 0x" + Long.toHexString(MAGIC));
            }
            int version = in.readInt();
            if (version != CURRENT_VERSION) {
                throw new IncompatibleStoreException(
                    "edge store at " + directory + " is format version " + version
                        + "; this build reads version " + CURRENT_VERSION);
            }
            byte order = in.readByte();
            ByteOrder byteOrder = order == ORDER_BIG ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            int entityIdBytes = in.readByte();
            int edgeOffsetBytes = in.readByte();
            int relationIdBytes = in.readByte();
            long entityCount = in.readLong();
            long edgeCount = in.readLong();

            checkWidth("entity id", entityIdBytes, ENTITY_ID_BYTES, directory);
            checkWidth("edge offset", edgeOffsetBytes, EDGE_OFFSET_BYTES, directory);
            checkWidth("relation id", relationIdBytes, RELATION_ID_BYTES, directory);

            return new EdgeStoreDescriptor(version, byteOrder,
                entityIdBytes, edgeOffsetBytes, relationIdBytes, entityCount, edgeCount);
        } catch (EOFException e) {
            throw new IOException("edge store descriptor truncated: " + path, e);
        }
    }

    private static void checkWidth(String what, int found, int expected, Path directory) {
        if (found != expected) {
            throw new IncompatibleStoreException(
                "edge store at " + directory + " uses a " + found + "-byte " + what
                    + "; this build uses " + expected
                    + ". Widths are fixed at ingest and cannot be reinterpreted: rebuild the store.");
        }
    }

    /** Thrown when a store on disk cannot be read by this build. */
    public static final class IncompatibleStoreException extends UncheckedIOException {
        public IncompatibleStoreException(String message) {
            super(new IOException(message));
        }
    }
}
