package io.excalibur.vector.quantization;

import io.excalibur.core.integrity.IndexChecksum;
import io.excalibur.core.integrity.IntegrityReport;
import io.excalibur.vector.durability.DurableFiles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * <h2>Layout (version 2)</h2>
 * <pre>
 * magic         (8B)  identifies the file as a codebook
 * version       (4B)  format version
 * payloadLength (4B)  bytes of body that follow the header
 * payloadCrc    (8B)  CRC32C of the body
 * headerCrc     (8B)  CRC32C of the 24 header bytes before it
 * body:
 *   dimension    (4B)  the vector dimension the codebooks were trained for
 *   numSubspaces (4B)
 *   numCentroids (4B)
 *   centroids    (numSubspaces * numCentroids * (dimension / numSubspaces) * 4B)
 * </pre>
 *
 * <p>All fields are big-endian, matching the byte order the graph's own mapped file uses.</p>
 *
 * <p>A version 1 file has no checksum fields: its body starts immediately after the version.
 * Such a file is still read - the shape and end-of-file checks it was written with still
 * apply - and {@link #verifyIntegrity(Path)} names it as unprotected. It is not upgraded
 * where it lies, because a checksum written over it now would attest to bytes nothing has
 * attested to; it becomes a version 2 file the next time the quantiser is written.</p>
 *
 * <p>The shape is recorded because a codebook is otherwise just a block of floats: one trained
 * for another vector space parses perfectly against any index and quantises every vector
 * against centroids drawn from a different corpus, which no later check would catch. A file
 * whose recorded shape does not match the index it sits beside is refused.</p>
 *
 * <h2>Durability</h2>
 * <p>The checksum is what refuses a file of the right length whose centroids are not the ones
 * that were written - the state a crash produces when the rename that publishes the file
 * outruns the bytes it names. A codebook that decodes to the wrong vectors is worse than a
 * missing one, because a missing one is noticed. The staged contents are still forced to
 * storage before the rename, so the ordinary case is a file that is whole rather than a file
 * that is caught being broken.</p>
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

    /** Name used when a damaged codebook is reported. */
    private static final String FORMAT = "product quantizer codebook";

    /** File identifier, the ASCII bytes "PQCODEBK". */
    private static final long MAGIC = 0x5051434F4445424BL;

    /** Current format version. */
    private static final int VERSION = 2;

    /** The version written before the file carried checksums. */
    private static final int VERSION_WITHOUT_CHECKSUM = 1;

    /** Bytes of header the header checksum covers: magic, version, length and payload CRC. */
    private static final int HEADER_COVERED_BYTES = 24;

    /**
     * Largest body this build will read.
     *
     * <p>The length comes out of the file, so it sizes an allocation from data that may be
     * damaged. A codebook for the largest representable shape is a few hundred megabytes; a
     * declared length beyond this bound describes no codebook that was ever trained.</p>
     */
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;

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
        byte[] body = encodeBody(quantizer);
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream out = Channels.newOutputStream(channel);
            DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out));
            data.write(header(body));
            data.write(body);
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

        byte[] body = readVerifiedBody(path).body();
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(body))) {
            Header header = readShape(data, path, expectedDimension);
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
     * Verifies a codebook file without building a quantiser from it.
     *
     * <p>The checking entry point for this format. It performs every check {@link #read} does
     * short of interpreting the centroids, and reports rather than throws, so a survey of a
     * node's files can continue past a damaged one.</p>
     *
     * @param path The codebook file to verify
     * @return What the check concluded, including whether the file carries a checksum at all
     * @throws NullPointerException if path is null
     */
    public static IntegrityReport verifyIntegrity(Path path) {
        Objects.requireNonNull(path, "Path cannot be null");
        if (!Files.exists(path)) {
            return IntegrityReport.missing(path, FORMAT);
        }
        try {
            Verified verified = readVerifiedBody(path);
            return verified.version() == VERSION
                ? IntegrityReport.intact(path, FORMAT, "the codebook matches its checksum")
                : IntegrityReport.legacy(path, FORMAT, verified.version());
        } catch (IOException e) {
            return IntegrityReport.corrupt(path, FORMAT, e.getMessage());
        }
    }

    /**
     * A codebook file's body and the format version it was written at.
     *
     * @param version The version read from the header
     * @param body    The bytes after the header, already checked against their checksum
     */
    private record Verified(int version, byte[] body) {
    }

    /**
     * Reads a codebook file's body, having checked everything that guards it.
     *
     * <p>The body is returned rather than streamed on, so the checksum is taken over the very
     * bytes the caller will parse. Verifying a file and then re-reading it would leave a
     * window in which the two differ.</p>
     *
     * @param path The codebook file
     * @return The body bytes, without the header, and the version they were written at
     * @throws IOException if the file is not a codebook, is truncated, holds trailing bytes,
     *         or does not match the checksum it carries
     */
    private static Verified readVerifiedBody(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
            requireMagic(data, path);

            int version = data.readInt();
            if (version == VERSION_WITHOUT_CHECKSUM) {
                return new Verified(version, data.readAllBytes());
            }
            if (version != VERSION) {
                throw new IOException("Codebook file " + path + " is at version " + version
                    + ", which this build cannot read (expected " + VERSION + ")");
            }

            int payloadLength = data.readInt();
            long payloadCrc = data.readLong();
            requireHeaderChecksum(data.readLong(), payloadLength, payloadCrc, path);
            requireUsableLength(payloadLength, path);

            byte[] body = data.readNBytes(payloadLength);
            if (body.length != payloadLength) {
                throw new IOException("Codebook file " + path + " is truncated: its header "
                    + "declares " + payloadLength + " bytes of centroids but only "
                    + body.length + " are present");
            }
            requireBodyChecksum(body, payloadCrc, path);
            requireEndOfFile(data, path);
            return new Verified(version, body);
        } catch (EOFException e) {
            throw new IOException("Codebook file " + path + " is truncated", e);
        }
    }

    /**
     * Refuses a file that does not start with the codebook magic number.
     */
    private static void requireMagic(DataInputStream data, Path path) throws IOException {
        long magic = data.readLong();
        if (magic != MAGIC) {
            throw new IOException("File " + path + " is not a product quantizer codebook "
                + "(bad magic number " + Long.toHexString(magic) + ")");
        }
    }

    /**
     * Refuses a header whose own checksum no longer matches its fields.
     */
    private static void requireHeaderChecksum(long stored, int payloadLength, long payloadCrc,
                                              Path path) throws IOException {
        long actual = IndexChecksum.of(headerBytes(payloadLength, payloadCrc), 0,
            HEADER_COVERED_BYTES);
        if (stored != actual) {
            throw new IOException("Codebook file " + path + " has a header checksum of 0x"
                + Long.toHexString(stored) + " but its header digests to 0x"
                + Long.toHexString(actual) + ", so the length and checksum it declares for "
                + "the centroids cannot be trusted");
        }
    }

    /**
     * Refuses a declared body length no codebook could have.
     */
    private static void requireUsableLength(int payloadLength, Path path) throws IOException {
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IOException("Codebook file " + path + " declares " + payloadLength
                + " bytes of centroids, outside the range any codebook occupies");
        }
    }

    /**
     * Refuses centroids that no longer match the checksum written over them.
     */
    private static void requireBodyChecksum(byte[] body, long stored, Path path)
            throws IOException {
        long actual = IndexChecksum.of(body, 0, body.length);
        if (stored != actual) {
            throw new IOException("Codebook file " + path + " has a checksum of 0x"
                + Long.toHexString(stored) + " over its " + body.length
                + " bytes of centroids but they digest to 0x" + Long.toHexString(actual)
                + ", so at least one centroid has changed since it was trained");
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
    private static byte[] encodeBody(ProductQuantizer quantizer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
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
        return bytes.toByteArray();
    }

    /**
     * Builds the header that precedes a body, checksums included.
     *
     * @param body The encoded body the header describes
     * @return The header bytes, ready to write
     */
    private static byte[] header(byte[] body) {
        byte[] covered = headerBytes(body.length, IndexChecksum.of(body, 0, body.length));
        return ByteBuffer.allocate(HEADER_COVERED_BYTES + Long.BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(covered)
            .putLong(IndexChecksum.of(covered, 0, HEADER_COVERED_BYTES))
            .array();
    }

    /**
     * Lays out the header fields the header checksum covers.
     *
     * @param payloadLength Bytes of body that follow
     * @param payloadCrc    The checksum over those bytes
     * @return The covered header prefix
     */
    private static byte[] headerBytes(int payloadLength, long payloadCrc) {
        return ByteBuffer.allocate(HEADER_COVERED_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(MAGIC)
            .putInt(VERSION)
            .putInt(payloadLength)
            .putLong(payloadCrc)
            .array();
    }

    /**
     * Reads and validates the body's shape, refusing anything that does not describe a
     * codebook for an index of {@code expectedDimension}.
     *
     * @throws IOException if the file is not a codebook this build can read, or belongs to an
     *         index of another shape
     */
    private static Header readShape(DataInputStream data, Path path, int expectedDimension)
            throws IOException {
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
