package com.eonmux.excalibur.graph.vector;

import com.eonmux.excalibur.graph.build.BuildCancellation;
import com.eonmux.excalibur.graph.build.BuildCancelledException;
import com.eonmux.excalibur.graph.file.Durability;

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
import java.util.zip.CRC32C;

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
 * <h2>Nothing half-written ever wears the real name</h2>
 * <p>The vectors are streamed to a temporary body and the finished file is assembled beside it,
 * because the header and the presence flags belong in front of bytes that are only complete at
 * the end. Neither temporary is the file a reader opens: the assembled copy is forced and moved
 * onto the real name in one atomic act. The alternative, streaming into the real name and giving
 * it a header afterwards, means any failure in between leaves {@code entity_vectors.vec} as
 * headerless raw floats - which {@link EntityVectors#open} refuses, so the whole graph beside it
 * stops opening for want of a file whose <em>absence</em> is a supported configuration.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. One writer per file, used by the build that owns it.</p>
 *
 * @author ExcaliburDB
 */
public final class EntityVectorWriter implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityVectorWriter.class);

    /** Suffix of the streamed body, which holds the vectors and nothing else. */
    private static final String BODY_SUFFIX = ".body";

    /** Suffix of the complete file, assembled beside the real name before it is moved onto it. */
    private static final String ASSEMBLED_SUFFIX = ".assembled";

    private final Path path;
    private final Path body;
    private final Path assembled;
    private final Durability durability;
    private final int dimension;
    private final long count;
    private final byte[] present;
    private final ByteBuffer scratch;
    private final CRC32C vectorsChecksum = new CRC32C();

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
        this(path, dimension, count, Durability.DEVICE);
    }

    /**
     * As above, with how the finished file is forced to the device under the caller's control.
     *
     * @param path       Where the file goes
     * @param dimension  Length of every vector
     * @param count      How many entities the file will address
     * @param durability How the assembled file is forced before it takes the real name
     * @throws IOException if the file cannot be created
     * @throws IllegalArgumentException if the dimension, count or durability is not usable
     */
    public EntityVectorWriter(Path path, int dimension, long count, Durability durability)
            throws IOException {
        if (durability == null) {
            throw new IllegalArgumentException("durability must not be null");
        }
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
        this.body = path.resolveSibling(path.getFileName() + BODY_SUFFIX);
        this.assembled = path.resolveSibling(path.getFileName() + ASSEMBLED_SUFFIX);
        this.durability = durability;
        this.dimension = dimension;
        this.count = count;
        this.present = new byte[(int) count];
        this.scratch = ByteBuffer.allocate(dimension * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.out = new BufferedOutputStream(Files.newOutputStream(body,
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
     * @throws BuildCancelledException if the graph build on this thread was asked to stop
     */
    public void put(float[] vector) throws IOException {
        // The embedding phase is the long half of a build, and this is its only seam: the
        // caller embeds a batch and then offers the vectors one at a time, so a build asked
        // to stop ends one batch after the request rather than at the end of the corpus.
        // Checked on every entity rather than every thousandth, because the work between two
        // calls here is an embedding round trip amortised over a batch, not a field decode.
        BuildCancellation.checkpoint();
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
            vectorsChecksum.update(scratch.array());
        } else {
            scratch.clear();
            for (float value : vector) {
                scratch.putFloat(value);
            }
            out.write(scratch.array());
            vectorsChecksum.update(scratch.array());
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
     * <p>The assembled copy is forced and then moved onto the real name atomically, so that name
     * only ever refers to a complete file. Both temporaries are removed whatever happens: a
     * failure here leaves no vectors, which is a graph reranked lexically, rather than a vector
     * file that will not open and takes the graph down with it.</p>
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

        try {
            assemble();
            durability.replaceDurably(assembled, path);
        } finally {
            Files.deleteIfExists(assembled);
            Files.deleteIfExists(body);
        }

        LOGGER.info("wrote {}: {} of {} entities have a vector of dimension {}",
            path, withVector, count, dimension);
    }

    /**
     * Writes the header, the presence flags and the streamed body into the assembled copy.
     *
     * <p>The vectors' checksum was taken as each one was streamed to the body, so assembling costs
     * no second pass over them; the presence flags are digested here, where they are complete.
     * The header then carries a checksum of itself, so the digests it records are only believed
     * when they are the ones written.</p>
     *
     * @throws IOException if the copy cannot be written
     */
    private void assemble() throws IOException {
        CRC32C presenceChecksum = new CRC32C();
        presenceChecksum.update(present);
        ByteBuffer header = ByteBuffer.allocate(EntityVectors.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(EntityVectors.MAGIC);
        header.putInt(EntityVectors.VERSION);
        header.putInt(dimension);
        header.putLong(count);
        header.putInt((int) presenceChecksum.getValue());
        header.putInt((int) vectorsChecksum.getValue());
        header.putInt(0);
        CRC32C headerChecksum = new CRC32C();
        headerChecksum.update(header.array(), 0, header.position());
        header.putInt((int) headerChecksum.getValue());

        try (OutputStream complete = new BufferedOutputStream(Files.newOutputStream(assembled,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            complete.write(header.array());
            complete.write(present);
            Files.copy(body, complete);
        }
    }

    /**
     * How many entities were given a vector.
     *
     * @return The count
     */
    public long withVector() {
        return withVector;
    }

    /**
     * Releases the stream and removes any temporary an unfinished write left behind.
     *
     * <p>Closing without finishing is an abandoned write. The real file is untouched by such a
     * write - it was never the thing being written to - so all that is owed here is not leaving
     * the scratch behind for the next build to trip over.
     *
     * @throws IOException if the stream or the temporaries cannot be released
     */
    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
        if (!finished) {
            Files.deleteIfExists(body);
            Files.deleteIfExists(assembled);
        }
    }
}
