package io.excalibur.vector.quantization;

import io.excalibur.vector.durability.DurableFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Reads and writes a trained {@link ProductQuantizer}'s codebooks as a file.
 *
 * <p>Training is the expensive half of product quantisation - k-means over a sample of the
 * segment - and it produces the only state a quantiser has. Keeping it on disk beside the index
 * it was trained for is what lets a reopened segment quantise immediately instead of retraining
 * or silently giving up on quantisation altogether.</p>
 *
 * <h2>Layout</h2>
 * <pre>
 * magic        (8B)  identifies the file as a codebook
 * version      (4B)  format version
 * dimension    (4B)  the vector dimension the codebooks were trained for
 * numSubspaces (4B)
 * numCentroids (4B)
 * centroids    (numSubspaces * numCentroids * (dimension / numSubspaces) * 4B)
 * </pre>
 *
 * <p>All fields are big-endian, matching the byte order the graph's own mapped file uses.</p>
 *
 * <p>The shape is recorded because a codebook is otherwise just a block of floats: one trained
 * for another vector space parses perfectly against any index and quantises every vector
 * against centroids drawn from a different corpus, which no later check would catch. A file
 * whose recorded shape does not match the index it sits beside is refused.</p>
 *
 * <h2>Durability</h2>
 * <p>The file carries a magic number, a version and the shape it was trained for, which is
 * enough to refuse a file that is not a codebook, is truncated, or belongs to another index.
 * It carries no checksum, so it cannot refuse a file of the right length whose centroids are
 * not the ones that were written. That is exactly what a crash produces when the rename that
 * publishes the file outruns the bytes it names, and it is why the staged contents are forced
 * to storage before the rename rather than left to the page cache: a codebook that decodes to
 * the wrong vectors is worse than a missing one, because a missing one is noticed.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class holds no state and its methods are safe to call concurrently. Writes are
 * staged in a temporary file and moved into place, so a reader never observes a partial
 * codebook.</p>
 */
public final class PQCodebookFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(PQCodebookFile.class);

    /** The suffix a codebook takes beside the index file it belongs to. */
    public static final String SUFFIX = ".pqcb";

    /** File identifier, the ASCII bytes "PQCODEBK". */
    private static final long MAGIC = 0x5051434F4445424BL;

    /** Current format version. */
    private static final int VERSION = 1;

    /** Suffix of the file a write is staged in before it is moved into place. */
    private static final String STAGING_SUFFIX = ".tmp";

    /**
     * The two points at which a codebook write has to reach storage.
     *
     * <p>Named rather than inlined because an fsync leaves nothing behind that can be checked
     * afterwards: a codebook written without one is byte-for-byte identical to a codebook
     * written with one, right up until the host dies. The order of these calls against the
     * rename is the entire guarantee, and this is what makes that order assertable.</p>
     */
    interface Sync {

        /**
         * Forces the staged file's contents to storage, before the rename publishes them.
         *
         * @param staged The channel the staged codebook was written through
         * @throws IOException if the contents cannot be forced
         */
        void forceStagedContents(FileChannel staged) throws IOException;

        /**
         * Forces the directory entry the rename created, so the published name survives too.
         *
         * @param file The file whose new name must be durable
         */
        void forceDirectoryEntry(Path file);
    }

    /**
     * The durability the real filesystem provides.
     */
    private static final Sync PLATFORM_SYNC = new Sync() {

        @Override
        public void forceStagedContents(FileChannel staged) throws IOException {
            staged.force(true);
        }

        @Override
        public void forceDirectoryEntry(Path file) {
            DurableFiles.syncContainingDirectory(file);
        }
    };

    private PQCodebookFile() {
    }

    /**
     * Writes a trained quantiser's codebooks to {@code path}, replacing whatever is there.
     *
     * <p>The bytes are staged in a temporary file in the same directory, forced to storage,
     * and only then moved into place, so a concurrent reader - or a crash - sees either the
     * previous codebook or this one, and never a half-written file under a name that claims
     * to be complete.</p>
     *
     * @param path The file to write
     * @param quantizer A trained quantiser
     * @throws IOException if the file cannot be written
     * @throws IllegalStateException if the quantiser has not been trained
     */
    public static void write(Path path, ProductQuantizer quantizer) throws IOException {
        write(path, quantizer, PLATFORM_SYNC);
    }

    /**
     * Writes a codebook with the durability steps supplied, so their order can be observed.
     *
     * @param path The file to write
     * @param quantizer A trained quantiser
     * @param sync How the write reaches storage
     * @throws IOException if the file cannot be written
     * @throws IllegalStateException if the quantiser has not been trained
     */
    static void write(Path path, ProductQuantizer quantizer, Sync sync) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(quantizer, "Quantizer cannot be null");
        Objects.requireNonNull(sync, "Sync cannot be null");
        if (!quantizer.isTrained()) {
            throw new IllegalStateException(
                "ProductQuantizer must be trained before its codebooks can be written: " + path);
        }

        Path staging = path.resolveSibling(path.getFileName() + STAGING_SUFFIX);
        try {
            writeStaged(staging, quantizer, sync);
            moveIntoPlace(staging, path);
            sync.forceDirectoryEntry(path);
        } finally {
            deleteStagingRemains(staging);
        }
    }

    /**
     * Writes the codebook to the staging file and leaves it on storage.
     *
     * <p>The buffered stream is flushed onto the channel before the channel is forced, and
     * the channel outlives the stream so that the force happens while it is still open.
     * Forcing first would push whatever had happened to reach the channel and leave the rest
     * sitting in the buffer, which is the bug this exists to avoid rather than a fix for
     * it.</p>
     *
     * @param staging The staging file to create
     * @param quantizer A trained quantiser
     * @param sync How the write reaches storage
     * @throws IOException if the staged file cannot be written or forced
     */
    private static void writeStaged(Path staging, ProductQuantizer quantizer, Sync sync)
            throws IOException {
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream out = Channels.newOutputStream(channel);
            DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out));
            writeTo(data, quantizer);
            data.flush();
            sync.forceStagedContents(channel);
        }
    }

    /**
     * Reads a codebook written by {@link #write} and returns a quantiser holding it.
     *
     * @param path The file to read
     * @param expectedDimension The vector dimension of the index this codebook belongs to
     * @return A trained quantiser carrying the stored codebooks
     * @throws IOException if the file is missing, unreadable, or does not describe a codebook
     *         for an index of {@code expectedDimension}
     */
    public static ProductQuantizer read(Path path, int expectedDimension) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");

        try (InputStream in = Files.newInputStream(path);
             DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
            Header header = readHeader(data, path, expectedDimension);
            float[][][] codebooks = readCodebooks(data, path, header);
            requireEndOfFile(data, path);

            ProductQuantizer quantizer = new ProductQuantizer(
                header.dimension(), header.numSubspaces(), header.numCentroids());
            quantizer.setCodebooks(codebooks);
            return quantizer;
        } catch (EOFException e) {
            throw new IOException("Codebook file " + path + " is truncated", e);
        }
    }

    /**
     * The shape a codebook file declares for itself.
     */
    private record Header(int dimension, int numSubspaces, int numCentroids) {

        int subvectorDim() {
            return dimension / numSubspaces;
        }
    }

    /**
     * Writes the header and every centroid.
     */
    private static void writeTo(DataOutputStream data, ProductQuantizer quantizer)
            throws IOException {
        data.writeLong(MAGIC);
        data.writeInt(VERSION);
        data.writeInt(quantizer.originalDim());
        data.writeInt(quantizer.numSubspaces());
        data.writeInt(quantizer.numCentroids());

        float[][][] codebooks = quantizer.getCodebooks();
        for (float[][] subspace : codebooks) {
            for (float[] centroid : subspace) {
                for (float value : centroid) {
                    data.writeFloat(value);
                }
            }
        }
    }

    /**
     * Reads and validates the header, refusing anything that does not describe a codebook for
     * an index of {@code expectedDimension}.
     *
     * @throws IOException if the file is not a codebook this build can read, or belongs to an
     *         index of another shape
     */
    private static Header readHeader(DataInputStream data, Path path, int expectedDimension)
            throws IOException {
        long magic = data.readLong();
        if (magic != MAGIC) {
            throw new IOException("File " + path + " is not a product quantizer codebook "
                + "(bad magic number " + Long.toHexString(magic) + ")");
        }

        int version = data.readInt();
        if (version != VERSION) {
            throw new IOException("Codebook file " + path + " is at version " + version
                + ", which this build cannot read (expected " + VERSION + ")");
        }

        int dimension = data.readInt();
        if (dimension != expectedDimension) {
            throw new IOException("Codebook file " + path + " was trained for vectors of "
                + "dimension " + dimension + " but the index beside it holds dimension "
                + expectedDimension + ". Quantising against centroids from another vector "
                + "space returns plausible distances that rank by nothing.");
        }

        int numSubspaces = data.readInt();
        int numCentroids = data.readInt();
        requireUsableShape(path, dimension, numSubspaces, numCentroids);

        return new Header(dimension, numSubspaces, numCentroids);
    }

    /**
     * Refuses a shape no quantiser could have produced, before it is used to size an array.
     */
    private static void requireUsableShape(Path path, int dimension, int numSubspaces,
                                           int numCentroids) throws IOException {
        if (numSubspaces < 1 || dimension % numSubspaces != 0) {
            throw new IOException("Codebook file " + path + " declares " + numSubspaces
                + " subspaces, which does not divide dimension " + dimension);
        }
        if (numCentroids < 2 || numCentroids > ProductQuantizer.MAX_NUM_CENTROIDS) {
            throw new IOException("Codebook file " + path + " declares " + numCentroids
                + " centroids per subspace, outside the representable range [2, "
                + ProductQuantizer.MAX_NUM_CENTROIDS + "]");
        }
    }

    /**
     * Reads every centroid the header accounts for.
     */
    private static float[][][] readCodebooks(DataInputStream data, Path path, Header header)
            throws IOException {
        float[][][] codebooks =
            new float[header.numSubspaces()][header.numCentroids()][header.subvectorDim()];
        for (int s = 0; s < header.numSubspaces(); s++) {
            for (int c = 0; c < header.numCentroids(); c++) {
                for (int d = 0; d < header.subvectorDim(); d++) {
                    float value = data.readFloat();
                    if (!Float.isFinite(value)) {
                        throw new IOException("Codebook file " + path + " holds a non-finite "
                            + "centroid value at subspace " + s + ", centroid " + c);
                    }
                    codebooks[s][c][d] = value;
                }
            }
        }
        return codebooks;
    }

    /**
     * Refuses a file that holds more than the codebook it declared.
     *
     * <p>Bytes past the last centroid mean the file is not the thing its header says it is -
     * an append, an overlay of a longer file, a different format sharing the suffix - and the
     * codebook read out of the front of it cannot be trusted to be the whole one.</p>
     */
    private static void requireEndOfFile(DataInputStream data, Path path) throws IOException {
        if (data.read() != -1) {
            throw new IOException("Codebook file " + path + " holds trailing bytes past the "
                + "centroids its header declares");
        }
    }

    /**
     * Moves the staged file over the destination, preferring an atomic rename.
     *
     * <p>Not every filesystem supports one; where it is refused the copy is still made, and the
     * exposure is the window in which a reader could see a partial file, which is the behaviour
     * such a filesystem offers for any write.</p>
     *
     * @param staging The staged codebook, already forced to storage
     * @param path The name to publish it under
     * @throws IOException if the staged file cannot be moved
     */
    private static void moveIntoPlace(Path staging, Path path) throws IOException {
        try {
            Files.move(staging, path,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            LOGGER.warn("Atomic rename is not available for {}; the codebook is written in "
                + "place instead and a reader could observe it partially written: {}",
                path, e.toString());
            Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes the staging file if the write did not consume it.
     *
     * <p>A failure here leaves a stray temporary rather than costing the codebook, so it is
     * reported and not raised over whatever failure brought us here.</p>
     */
    private static void deleteStagingRemains(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            LOGGER.warn("Could not remove the staging file {} left by a codebook write: {}",
                staging, e.toString());
        }
    }
}
