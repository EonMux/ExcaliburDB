package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;

import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mapped column files of one edge store, held in a single shared {@link Arena}.
 *
 * <p>One arena for the whole store, not one per file: the columns are useless individually and are
 * released together, and a single arena means one lifetime to reason about instead of thirteen.
 * {@link Arena#ofShared()} rather than {@code ofConfined()} because expansion fans a hop out across
 * virtual threads, and a confined arena would restrict every access to the thread that opened it.
 *
 * <p>Closing a shared arena while another thread is mid-read is safe: the JVM performs a handshake
 * and the racing access fails with {@link IllegalStateException} rather than reading freed memory.
 * That is the property that makes {@link #close()} callable without stopping the world first.
 *
 * <h2>Access</h2>
 * Accessors take an element index, not a byte offset, and are the only place the two are converted.
 * Every column is a bare array, so element {@code i} is at {@code i * elementBytes}, and the base
 * of each mapping is page-aligned — so element addresses are naturally aligned and the layouts
 * below say so rather than defensively claiming otherwise.
 *
 * <p>All accessors are bounds-checked against the descriptor's element count. The cost is a
 * comparison against a field; the alternative is that a corrupt offset array reads arbitrary
 * process memory as entity ids.
 */
public final class MappedColumns implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MappedColumns.class);

    private static final ValueLayout.OfInt INT =
        ValueLayout.JAVA_INT.withOrder(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG.withOrder(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
    private static final ValueLayout.OfShort SHORT =
        ValueLayout.JAVA_SHORT.withOrder(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private final Arena arena;
    private final EdgeStoreDescriptor descriptor;
    private final Map<EdgeColumn, MemorySegment> columns;
    private final Map<EdgeColumn, Long> elementCounts;

    private MappedColumns(Arena arena, EdgeStoreDescriptor descriptor,
                          Map<EdgeColumn, MemorySegment> columns,
                          Map<EdgeColumn, Long> elementCounts) {
        this.arena = arena;
        this.descriptor = descriptor;
        this.columns = columns;
        this.elementCounts = elementCounts;
    }

    /**
     * Maps every column of the store in {@code directory} read-only.
     *
     * <p>Each file's length is checked against the size the descriptor implies. A column that is
     * short is a truncated write, and reading it would return zeros that look like entity 0 — so it
     * fails here, where the message can say which file and by how much.
     *
     * @throws IOException if a column is missing, unreadable or the wrong size
     */
    public static MappedColumns open(Path directory, EdgeStoreDescriptor descriptor)
            throws IOException {
        Arena arena = Arena.ofShared();
        Map<EdgeColumn, MemorySegment> mapped = new EnumMap<>(EdgeColumn.class);
        Map<EdgeColumn, Long> counts = new EnumMap<>(EdgeColumn.class);
        try {
            for (EdgeColumn column : EdgeColumn.values()) {
                Path path = directory.resolve(column.fileName());
                long expected = descriptor.byteSizeOf(column);
                if (!Files.exists(path)) {
                    throw new IOException("edge store column missing: " + path);
                }
                long actual = Files.size(path);
                if (actual != expected) {
                    throw new IOException("edge store column " + path + " is " + actual
                        + " bytes, expected " + expected + " for " + descriptor.entityCount()
                        + " entities and " + descriptor.edgeCount() + " edges");
                }
                mapped.put(column, map(arena, path, expected));
                counts.put(column, column.elementCount(
                    descriptor.entityCount(), descriptor.edgeCount()));
            }
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }

        MappedColumns result = new MappedColumns(arena, descriptor, mapped, counts);
        result.applyAccessHints();
        return result;
    }

    private static MemorySegment map(Arena arena, Path path, long size) throws IOException {
        if (size == 0) {
            // FileChannel.map rejects a zero length. An empty column is legitimate for an empty
            // store, and a zero-length segment answers every in-bounds query there are none of.
            return MemorySegment.NULL.reinterpret(0);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
        }
    }

    /**
     * Tells the kernel how each column is actually read.
     *
     * <p>The offset arrays and the reverse permutation are hit at unrelated indices as a traversal
     * jumps between entities, so readahead around each hit is wasted bandwidth. The adjacency and
     * relation columns are read as contiguous runs once an entity's slot is located, which is
     * exactly what readahead is for.
     */
    private void applyAccessHints() {
        if (!MemoryAdvice.isAvailable()) {
            LOGGER.debug("madvise unavailable; using default readahead for all columns");
            return;
        }
        for (Map.Entry<EdgeColumn, MemorySegment> entry : columns.entrySet()) {
            MemorySegment segment = entry.getValue();
            if (segment.byteSize() == 0) {
                continue;
            }
            switch (entry.getKey()) {
                case OFFSETS_FWD, OFFSETS_REV, PERM_REV -> MemoryAdvice.adviseRandom(segment);
                default -> MemoryAdvice.adviseSequential(segment);
            }
        }
    }

    /** The descriptor these columns were opened against. */
    public EdgeStoreDescriptor descriptor() {
        return descriptor;
    }

    /** Number of elements in {@code column}. */
    public long elementCount(EdgeColumn column) {
        return elementCounts.get(column);
    }

    /** The raw mapping of {@code column}, for bulk operations. */
    public MemorySegment segment(EdgeColumn column) {
        return columns.get(column);
    }

    /** Element {@code index} of a 4-byte column. */
    public int getInt(EdgeColumn column, long index) {
        checkIndex(column, index, Integer.BYTES);
        return columns.get(column).get(INT, index * Integer.BYTES);
    }

    /** Element {@code index} of an 8-byte column. */
    public long getLong(EdgeColumn column, long index) {
        checkIndex(column, index, Long.BYTES);
        return columns.get(column).get(LONG, index * Long.BYTES);
    }

    /** Element {@code index} of a 2-byte column. */
    public short getShort(EdgeColumn column, long index) {
        checkIndex(column, index, Short.BYTES);
        return columns.get(column).get(SHORT, index * Short.BYTES);
    }

    /** Element {@code index} of a 1-byte column. */
    public byte getByte(EdgeColumn column, long index) {
        checkIndex(column, index, Byte.BYTES);
        return columns.get(column).get(BYTE, index);
    }

    private void checkIndex(EdgeColumn column, long index, int expectedWidth) {
        if (column.elementBytes() != expectedWidth) {
            throw new IllegalArgumentException(column + " holds " + column.elementBytes()
                + "-byte elements; read as " + expectedWidth + "-byte");
        }
        long count = elementCounts.get(column);
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                "index " + index + " out of bounds for " + column + " of " + count + " elements");
        }
    }

    /**
     * Unmaps every column.
     *
     * <p>Idempotent. A concurrent reader racing this call sees {@link IllegalStateException} from
     * its access rather than reading unmapped memory.
     */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }

    /** Whether the mappings are still valid. */
    public boolean isOpen() {
        return arena.scope().isAlive();
    }
}
