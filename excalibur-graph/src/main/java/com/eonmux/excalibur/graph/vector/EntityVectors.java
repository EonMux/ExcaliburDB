package com.eonmux.excalibur.graph.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import com.eonmux.excalibur.core.memory.MappedFootprint;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * One full-precision vector per entity, memory-mapped and addressed by entity id.
 *
 * <h2>What it is for</h2>
 * <p>Reranking a lexical ranking against real vectors. BM25 decides which few dozen entities are
 * worth considering - it ranks by term overlap, which is exactly what makes it a good first pass
 * and a poor last one - and the ordering it produces within its own top-k is close to arbitrary
 * with respect to meaning. Reading the real vector for each of those candidates and re-scoring by
 * distance costs a few dozen sequential reads and fixes precisely that.</p>
 *
 * <h2>Why a flat file rather than an index</h2>
 * <p>Nothing searches this. It is looked up by entity id, and entity ids are dense - the staging
 * store hands them out in order from zero - so the vector for id <em>n</em> is at a computed
 * offset and there is nothing to search. An ANN index over the same data would answer a question
 * this never asks and cost a structure to build and keep correct across every rebuild.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * [magic : int64][version : int32][dimension : int32][count : int64]
 * [present : count * int8]           1 when the entity has a vector
 * [vectors : count * dimension * float32]
 * </pre>
 *
 * <p>Little-endian throughout, header included. The graph's column files are little-endian for the
 * hot path and its descriptor is big-endian because {@code DataOutputStream} is; a file that is one
 * or the other depending on which part you are reading is a byte-order bug waiting to be written,
 * so this one picks a single rule.</p>
 *
 * <p>Presence is stored rather than inferred. An entity whose text could not be embedded has no
 * vector, and the all-zero vector is a legitimate value that must not be mistaken for one -
 * scoring against it would place that entity at a fixed, meaningless distance from every query.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent reads. Reads after {@link #close()} throw, because the mapping is gone;
 * that is the same contract the edge store's mappings have.</p>
 *
 * @author ExcaliburDB
 */
public final class EntityVectors implements AutoCloseable, MappedFootprint {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityVectors.class);

    /** File name within a graph directory. */
    public static final String FILE_NAME = "entity_vectors.vec";

    /** Identifies the file: ASCII {@code EXCLBVEC}. */
    public static final long MAGIC = 0x4558434C42564543L;

    /** Format version written by this build. */
    public static final int VERSION = 1;

    /** Bytes before the presence flags. */
    public static final int HEADER_BYTES = 24;

    /**
     * Largest dimension this will believe from a header.
     *
     * <p>Well above any embedding model in use and far below the point where the offset arithmetic
     * below could overflow, so a corrupt header cannot turn into a wild read.
     */
    public static final int MAX_DIMENSION = 65_536;

    private final Path path;
    private final Arena arena;
    private final MemorySegment mapping;
    private final int dimension;
    private final long count;
    private final long vectorsOffset;

    private EntityVectors(Path path, Arena arena, MemorySegment mapping, int dimension,
                          long count) {
        this.path = path;
        this.arena = arena;
        this.mapping = mapping;
        this.dimension = dimension;
        this.count = count;
        this.vectorsOffset = HEADER_BYTES + count;
    }

    /**
     * Opens the vectors beside a graph, if there are any.
     *
     * <p>Absent is a configuration, not a failure: a graph built without an embedder has no
     * vectors and is reranked lexically. Present but unreadable is a failure, because it means the
     * file is there and says something this build cannot act on.</p>
     *
     * @param directory The graph directory
     * @return The vectors, or null when the file is not there
     * @throws IOException if the file is present and cannot be read
     */
    public static EntityVectors openIfPresent(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Path path = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return open(path);
    }

    /**
     * Opens a vector file.
     *
     * @param path The file
     * @return The vectors
     * @throws IOException if the file is not one of these, or does not hold what it claims
     */
    public static EntityVectors open(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        long size = Files.size(path);
        if (size < HEADER_BYTES) {
            throw new IOException("not an entity vector file, too short at " + size
                + " bytes: " + path);
        }

        Arena arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            MemorySegment mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);

            long magic = mapping.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(
                java.nio.ByteOrder.LITTLE_ENDIAN), 0);
            if (magic != MAGIC) {
                throw new IOException("not an entity vector file (magic 0x"
                    + Long.toHexString(magic) + "): " + path);
            }
            int version = readInt(mapping, 8);
            if (version != VERSION) {
                throw new IOException("unsupported entity vector version " + version + ": " + path);
            }
            int dimension = readInt(mapping, 12);
            if (dimension <= 0 || dimension > MAX_DIMENSION) {
                throw new IOException("entity vector file claims dimension " + dimension
                    + ", which is not usable: " + path);
            }
            long count = readLong(mapping, 16);
            if (count < 0) {
                throw new IOException("entity vector file claims " + count + " entities: " + path);
            }

            long expected = HEADER_BYTES + count + count * dimension * (long) Float.BYTES;
            if (size != expected) {
                throw new IOException("entity vector file " + path + " is " + size
                    + " bytes but its header describes " + expected);
            }

            LOGGER.debug("opened {} holding {} vectors of dimension {}", path, count, dimension);
            return new EntityVectors(path, arena, mapping, dimension, count);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * The vector for an entity, or null when it has none.
     *
     * <p>Null is an ordinary answer: an entity whose text could not be embedded is still in the
     * graph, still traversable, and simply keeps whatever score the ranking already gave it.</p>
     *
     * @param entityId The entity
     * @return Its vector, or null
     */
    public float[] vectorOf(long entityId) {
        if (entityId < 0 || entityId >= count) {
            return null;
        }
        if (mapping.get(ValueLayout.JAVA_BYTE, HEADER_BYTES + entityId) == 0) {
            return null;
        }

        float[] vector = new float[dimension];
        long offset = vectorsOffset + entityId * dimension * (long) Float.BYTES;
        for (int i = 0; i < dimension; i++) {
            vector[i] = mapping.get(
                ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN),
                offset + i * (long) Float.BYTES);
        }
        return vector;
    }

    /**
     * The dimension every stored vector has.
     *
     * @return The dimension
     */
    public int dimension() {
        return dimension;
    }

    /**
     * How many entities the file addresses.
     *
     * @return The count; ids run {@code 0..count()-1}
     */
    public long count() {
        return count;
    }

    /**
     * How many of those entities actually have a vector.
     *
     * @return The count
     */
    public long presentCount() {
        long present = 0;
        for (long id = 0; id < count; id++) {
            if (mapping.get(ValueLayout.JAVA_BYTE, HEADER_BYTES + id) != 0) {
                present++;
            }
        }
        return present;
    }

    /**
     * Where the file is.
     *
     * @return The path
     */
    public Path path() {
        return path;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One: the whole file is a single mapping, however many vectors it holds.
     */
    @Override
    public long mappings() {
        return 1L;
    }

    /**
     * {@inheritDoc}
     *
     * <p>None. The channel is closed inside {@link #openIfPresent} and the mapping outlives it.
     */
    @Override
    public long descriptors() {
        return 0L;
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }

    private static int readInt(MemorySegment mapping, long offset) {
        return mapping.get(
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset);
    }

    private static long readLong(MemorySegment mapping, long offset) {
        return mapping.get(
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset);
    }

    @Override
    public String toString() {
        return "EntityVectors[" + path + ", " + count + " entities, dimension " + dimension + "]";
    }
}
