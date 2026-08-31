package io.excalibur.graph.dictionary;

import io.excalibur.graph.format.EdgeStoreDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.LongFunction;

/**
 * The names behind the integer ids, read straight off disk.
 *
 * <p>Entity linking turns text into integer ids and everything downstream of it works in integers,
 * which is what makes the traversal allocation-free. The one place the names are needed again is
 * the end: a model reasons far better over {@code Ada_Lovelace -[collaboratedWith]-> Charles_Babbage}
 * than over {@code E4711 -[R12]-> E8102}, and the ids mean nothing to it. This is the mapping back.
 *
 * <h2>Layout</h2>
 * <p>Two files, in the same little-endian columnar style as the edge store:
 * <pre>
 * &lt;name&gt;.idx : [magic:8][version:4][count:4] then count * ([id:8][offset:4][length:4])
 *              sorted ascending by id
 * &lt;name&gt;.dat : the UTF-8 bytes, concatenated
 * </pre>
 *
 * <p>Sorted ids and a fixed-width index mean a lookup is a binary search over a mapped array with
 * no allocation until the one string it returns. A hash map of the same data would be faster per
 * lookup and would also have to be built, in full, at startup - for a dictionary covering every
 * entity in the graph, that is the kind of cost that only shows up in production. Serialization
 * happens once per answered question, over a few dozen edges, so the log factor is not the thing
 * worth optimising.
 *
 * <h2>Missing ids</h2>
 * <p>An id with no entry yields {@code E<id>} rather than null. A subgraph is not worth discarding
 * because one entity has no name recorded, and a caller that has to null-check every label will
 * eventually forget to.
 *
 * <h2>Thread safety</h2>
 * <p>Immutable once opened and safe for concurrent lookup. {@link #close()} releases the mappings;
 * a lookup racing with it fails with {@link IllegalStateException} rather than reading freed
 * memory, which is the shared-arena handshake rather than a check of ours.
 */
public final class LabelDictionary implements AutoCloseable, LongFunction<String> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LabelDictionary.class);

    /** Distinguishes this file from any other mapped file that happens to be in the directory. */
    public static final long MAGIC = 0x4558434C42444354L;

    /** Format version. */
    public static final int VERSION = 1;

    /** Bytes before the first index entry: magic, version, count. */
    public static final int HEADER_BYTES = 16;

    /** Bytes per index entry: id, offset, length. */
    public static final int ENTRY_BYTES = 16;

    private static final ValueLayout.OfInt INT =
        ValueLayout.JAVA_INT.withOrder(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG.withOrder(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);

    private final Arena arena;
    private final MemorySegment index;
    private final MemorySegment data;
    private final int count;
    private final String prefix;

    private LabelDictionary(Arena arena, MemorySegment index, MemorySegment data,
                            int count, String prefix) {
        this.arena = arena;
        this.index = index;
        this.data = data;
        this.count = count;
        this.prefix = prefix;
    }

    /**
     * Opens the dictionary named {@code name} in {@code directory}.
     *
     * @param directory The directory holding the dictionary files
     * @param name      The base name, without extension
     * @param prefix    The prefix for a synthesised name when an id has no entry
     * @return The dictionary
     * @throws IOException if either file is missing or malformed
     */
    public static LabelDictionary open(Path directory, String name, String prefix)
            throws IOException {
        Path indexPath = directory.resolve(name + ".idx");
        Path dataPath = directory.resolve(name + ".dat");
        if (!Files.isReadable(indexPath) || !Files.isReadable(dataPath)) {
            throw new IOException("dictionary " + name + " is not readable in " + directory);
        }

        long indexBytes = Files.size(indexPath);
        if (indexBytes < HEADER_BYTES) {
            throw new IOException(indexPath + " is shorter than its header");
        }

        Arena arena = Arena.ofShared();
        try (FileChannel indexChannel = FileChannel.open(indexPath, StandardOpenOption.READ);
             FileChannel dataChannel = FileChannel.open(dataPath, StandardOpenOption.READ)) {

            MemorySegment index =
                indexChannel.map(FileChannel.MapMode.READ_ONLY, 0, indexBytes, arena);
            long magic = index.get(LONG, 0);
            if (magic != MAGIC) {
                throw new IOException(indexPath + " is not a label dictionary: magic 0x"
                    + Long.toHexString(magic) + ", expected 0x" + Long.toHexString(MAGIC));
            }
            int version = index.get(INT, 8);
            if (version != VERSION) {
                throw new IOException(indexPath + " is version " + version
                    + "; this build reads version " + VERSION);
            }
            int count = index.get(INT, 12);
            if (count < 0 || HEADER_BYTES + (long) count * ENTRY_BYTES > indexBytes) {
                throw new IOException(indexPath + " declares " + count
                    + " entries, which does not fit in " + indexBytes + " bytes");
            }

            long dataBytes = Files.size(dataPath);
            MemorySegment data = dataBytes == 0
                ? MemorySegment.ofArray(new byte[0])
                : dataChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataBytes, arena);

            LOGGER.info("Opened label dictionary {} with {} entries ({} bytes of text)",
                name, count, dataBytes);
            return new LabelDictionary(arena, index, data, count, prefix);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Opens the dictionary if it is present, or returns null.
     *
     * @param directory The directory to look in
     * @param name      The base name, without extension
     * @param prefix    The prefix for a synthesised name
     * @return The dictionary, or null when it is not there
     * @throws IOException if the files are present but malformed - a dictionary that exists and
     *                     cannot be read is a corrupt store, not an absent one
     */
    public static LabelDictionary openIfPresent(Path directory, String name, String prefix)
            throws IOException {
        if (!Files.isReadable(directory.resolve(name + ".idx"))) {
            return null;
        }
        return open(directory, name, prefix);
    }

    /** The number of names held. */
    public int size() {
        return count;
    }

    /**
     * The name for an id, or {@code <prefix><id>} when none is recorded.
     *
     * @param id The entity or relation id
     * @return A name, never null
     */
    @Override
    public String apply(long id) {
        int slot = search(id);
        if (slot < 0) {
            return prefix + id;
        }
        long entry = HEADER_BYTES + (long) slot * ENTRY_BYTES;
        int offset = index.get(INT, entry + 8);
        int length = index.get(INT, entry + 12);
        if (offset < 0 || length < 0 || offset + (long) length > data.byteSize()) {
            // A corrupt index entry names bytes outside the blob. Falling back keeps one bad
            // entry from failing a whole answer.
            LOGGER.warn("Label entry for id {} points outside the data file; using a synthetic name",
                id);
            return prefix + id;
        }
        byte[] bytes = new byte[length];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Binary search over the id column; the insertion point is not needed, so misses return -1. */
    private int search(long id) {
        int low = 0;
        int high = count - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long candidate = index.get(LONG, HEADER_BYTES + (long) mid * ENTRY_BYTES);
            if (candidate < id) {
                low = mid + 1;
            } else if (candidate > id) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        arena.close();
    }

    @Override
    public String toString() {
        return "LabelDictionary{entries=" + count + ", text=" + data.byteSize() + " bytes}";
    }
}
