package io.excalibur.graph.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes the entity vector file a graph is reranked against.
 *
 * <h2>Streaming, in id order</h2>
 * <p>The reader addresses a vector by computing its offset from the entity id, so the file has to
 * be written in id order with no gaps - and it is written a vector at a time rather than assembled
 * in memory, because a corpus of a million entities at 1536 dimensions is six gigabytes and holding
 * that to write it would be the largest allocation in the process.</p>
 *
 * <p>The presence flags come before the vectors and are therefore written first, which means the
 * caller must say up front how many entities there are. That is known: it is the entity count the
 * edge store was built with.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. One writer per file, used by the build that owns it.</p>
 *
 * @author ExcaliburDB
 */
public final class EntityVectorWriter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityVectorWriter.class);

    private final Path path;
    private final int dimension;
    private final long count;
    private final byte[] present;
    private final ByteBuffer scratch;

    private OutputStream out;
    private long written;
    private long withVector;
    private boolean finished;

    /**
     * Creates a writer for {@code count} entities of {@code dimension} floats each.
     *
     * @param path      Where the file goes
     * @param dimension Length of every vector
     * @param count     How many entities the file will address
     * @throws IOException if the file cannot be created
     * @throws IllegalArgumentException if the dimension or count is not usable
     */
    public EntityVectorWriter(Path path, int dimension, long count) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (dimension <= 0 || dimension > EntityVectors.MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "dimension must be 1.." + EntityVectors.MAX_DIMENSION + ", got " + dimension);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative, got " + count);
        }
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "count " + count + " exceeds what one presence array can address");
        }

        this.path = path;
        this.dimension = dimension;
        this.count = count;
        this.present = new byte[(int) count];
        this.scratch = ByteBuffer.allocate(dimension * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.out = new BufferedOutputStream(Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING));
    }

    /**
     * Records the vector for the next entity, in id order.
     *
     * <p>A null vector records the entity as having none - which is what happens when its text
     * could not be embedded. It still occupies its slot, because the reader finds a vector by
     * computing an offset and a missing slot would shift every entity after it.</p>
     *
     * @param vector The vector, or null when the entity has none
     * @throws IOException if the write fails
     * @throws IllegalStateException if more vectors are written than the declared count
     * @throws IllegalArgumentException if the vector is not of the declared dimension
     */
    public void put(float[] vector) throws IOException {
        if (finished) {
            throw new IllegalStateException("writer is finished: " + path);
        }
        if (written >= count) {
            throw new IllegalStateException(
                "more vectors than the declared count of " + count + ": " + path);
        }
        if (vector != null && vector.length != dimension) {
            throw new IllegalArgumentException("vector for entity " + written + " has "
                + vector.length + " dimensions, expected " + dimension);
        }

        if (vector == null) {
            // The slot is still written, all zeros, and the flag says it means nothing. The reader
            // must not be able to mistake it for a real vector: scoring against an all-zero vector
            // would place the entity at a fixed, meaningless distance from every query.
            scratch.clear();
            java.util.Arrays.fill(scratch.array(), (byte) 0);
            out.write(scratch.array());
        } else {
            scratch.clear();
            for (float value : vector) {
                scratch.putFloat(value);
            }
            out.write(scratch.array());
            present[(int) written] = 1;
            withVector++;
        }
        written++;
    }

    /**
     * Finishes the file: writes the header and the presence flags in front of the vectors.
     *
     * <p>The vectors were streamed to a temporary body because the header has to precede them and
     * the presence flags are only known once every entity has been offered. Assembling the final
     * file here is one sequential copy.</p>
     *
     * @throws IOException if the file cannot be completed
     * @throws IllegalStateException if fewer vectors were written than declared
     */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        if (written != count) {
            throw new IllegalStateException("wrote " + written + " vectors of a declared "
                + count + ": " + path);
        }
        finished = true;
        out.flush();
        out.close();
        out = null;

        Path body = path;
        Path finalPath = path.resolveSibling(path.getFileName() + ".assembled");

        ByteBuffer header = ByteBuffer.allocate(EntityVectors.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(EntityVectors.MAGIC);
        header.putInt(EntityVectors.VERSION);
        header.putInt(dimension);
        header.putLong(count);

        try (OutputStream assembled = new BufferedOutputStream(Files.newOutputStream(finalPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            assembled.write(header.array());
            assembled.write(present);
            Files.copy(body, assembled);
        }
        Files.move(finalPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("wrote {}: {} of {} entities have a vector of dimension {}",
            path, withVector, count, dimension);
    }

    /**
     * How many entities were given a vector.
     *
     * @return The count
     */
    public long withVector() {
        return withVector;
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }
}
