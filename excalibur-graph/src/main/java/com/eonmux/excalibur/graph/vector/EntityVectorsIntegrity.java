package com.eonmux.excalibur.graph.vector;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * The rules for checking an {@link EntityVectors} file against the checksums its header carries,
 * shared by the file's open and by any tool that surveys one.
 *
 * <h2>What is checked</h2>
 * <p>From version 2 the header records a CRC32C of the presence flags, a CRC32C of the vectors,
 * and, in its last four bytes, a CRC32C of every header byte ahead of them. The file is refused
 * unless its header digests to that checksum, it is exactly the size the header's dimension and
 * count imply, and both regions digest to what the header records. A flipped bit in a vector
 * moves an entity to a different point in the space and reranks every answer it appears in; one
 * in a presence flag makes a slot of zeros read as a vector, or a real vector read as none. Both
 * look like answers, so the whole file is verified on every open - the policy the edge store's
 * columns follow, for the reasons given on {@code EdgeStoreIntegrity}.</p>
 *
 * <h2>Version 1</h2>
 * <p>A version 1 file has a 24-byte header and no checksums. It is held to the size its header
 * implies and reported as unprotected. It is derived from the namespace's staging logs and the
 * embedder, and the next build replaces it with one at version 2.</p>
 *
 * <h2>Read-only</h2>
 * <p>The file is streamed through a channel rather than mapped, so a file too damaged to serve can
 * still be judged, and nothing here writes.</p>
 */
public final class EntityVectorsIntegrity {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityVectorsIntegrity.class);

    /** Name the file goes by in a refusal or a report. */
    public static final String FORMAT = "entity vectors";

    private EntityVectorsIntegrity() {
        throw new AssertionError("Utility class");
    }

    /**
     * What a header that could be believed says about the file.
     *
     * @param version     The format version
     * @param dimension   The length of every vector
     * @param count       How many entities the file addresses
     * @param headerBytes Where the presence flags start
     */
    record Header(int version, int dimension, long count, int headerBytes) {
    }

    /**
     * Verifies a vector file, refusing one that is damaged.
     *
     * @param path The file
     * @return The header, every region of which was checked as far as its version allows
     * @throws GraphCorruptionException if the file is missing, malformed, cut short or does not
     *         match its checksums
     * @throws IOException if the file cannot be read, or is at a version this build does not read
     */
    static Header requireIntact(Path path) throws IOException {
        IntegrityReport report = inspect(path);
        switch (report.status()) {
            case CORRUPT, MISSING -> throw new GraphCorruptionException(path, FORMAT + " " + path
                + " failed its integrity check: " + report.detail());
            case UNREADABLE -> throw new IOException(FORMAT + " " + path + " cannot be read: "
                + report.detail());
            default -> {
                // Intact or unprotected: served either way.
            }
        }
        Header header;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            header = headerOf(read(channel, 0, EntityVectors.V1_HEADER_BYTES));
        }
        if (report.status() == IntegrityStatus.LEGACY_UNPROTECTED) {
            LOGGER.warn("{} is format version {}, written before entity vectors carried "
                + "checksums: its size matches its header and nothing more can be shown. It is "
                + "derived from the namespace's staging logs, and the next build replaces it with "
                + "a checksummed one", path, header.version());
        }
        return header;
    }

    /**
     * Reaches a verdict on a vector file, refusing nothing.
     *
     * @param path The file
     * @return The report
     * @throws NullPointerException if {@code path} is null
     */
    public static IntegrityReport inspect(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.isRegularFile(path)) {
            return IntegrityReport.missing(path, FORMAT);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return inspect(path, channel);
        } catch (IOException e) {
            return IntegrityReport.unreadable(path, FORMAT, e.toString());
        }
    }

    /**
     * Judges an open vector file.
     *
     * @param path    The file
     * @param channel A channel over it
     * @return The report
     * @throws IOException if the file cannot be read
     */
    private static IntegrityReport inspect(Path path, FileChannel channel) throws IOException {
        long size = channel.size();
        ByteBuffer fixed = read(channel, 0, EntityVectors.V1_HEADER_BYTES);
        if (fixed.hasRemaining()) {
            return IntegrityReport.corrupt(path, FORMAT, "the file is " + size + " bytes, shorter "
                + "than the " + EntityVectors.V1_HEADER_BYTES + "-byte header every version has");
        }
        if (fixed.getLong(0) != EntityVectors.MAGIC) {
            return IntegrityReport.corrupt(path, FORMAT, "not an entity vector file: magic 0x"
                + Long.toHexString(fixed.getLong(0)) + ", expected 0x"
                + Long.toHexString(EntityVectors.MAGIC));
        }
        int version = fixed.getInt(8);
        if (version < EntityVectors.OLDEST_VERSION || version > EntityVectors.VERSION) {
            return IntegrityReport.unsupportedVersion(path, FORMAT, version,
                EntityVectors.OLDEST_VERSION, EntityVectors.VERSION);
        }
        if (version == EntityVectors.OLDEST_VERSION) {
            return inspectLegacy(path, size, headerOf(fixed));
        }
        return inspectSealed(path, channel, size);
    }

    /**
     * Judges a version 1 file by the one check its layout allows: its size.
     *
     * @param path   The file
     * @param size   Its size
     * @param header Its header
     * @return The report
     */
    private static IntegrityReport inspectLegacy(Path path, long size, Header header) {
        String shape = shapeProblem(header, size);
        if (shape != null) {
            return IntegrityReport.corrupt(path, FORMAT, shape);
        }
        return new IntegrityReport(path, FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
            "format version " + EntityVectors.OLDEST_VERSION + ", written before entity vectors "
                + "carried checksums: its " + size + " bytes are the size " + header.count()
                + " entities of dimension " + header.dimension() + " imply, and nothing shows "
                + "they are the ones written. It is derived from the namespace's staging logs, "
                + "and the next build replaces it with a checksummed one");
    }

    /**
     * Judges a version 2 file against the checksums in its header.
     *
     * @param path    The file
     * @param channel A channel over it
     * @param size    Its size
     * @return The report
     * @throws IOException if the file cannot be read
     */
    private static IntegrityReport inspectSealed(Path path, FileChannel channel, long size)
            throws IOException {
        ByteBuffer full = read(channel, 0, EntityVectors.HEADER_BYTES);
        if (full.hasRemaining()) {
            return IntegrityReport.corrupt(path, FORMAT, "the file is " + size + " bytes, "
                + "shorter than its " + EntityVectors.HEADER_BYTES + "-byte header");
        }
        long recordedHeaderCrc = Integer.toUnsignedLong(full.getInt(36));
        long actualHeaderCrc = IndexChecksum.of(full.array(), 0, 36);
        if (actualHeaderCrc != recordedHeaderCrc) {
            return IntegrityReport.corrupt(path, FORMAT, "its header records 0x"
                + Long.toHexString(recordedHeaderCrc) + " over itself but digests to 0x"
                + Long.toHexString(actualHeaderCrc) + ", so its dimension, count or the "
                + "checksums it carries are no longer the ones written");
        }
        Header header = headerOf(full);
        String shape = shapeProblem(header, size);
        if (shape != null) {
            return IntegrityReport.corrupt(path, FORMAT, shape);
        }
        long presenceStart = EntityVectors.HEADER_BYTES;
        long vectorsStart = presenceStart + header.count();
        String presence = regionProblem(channel, "presence flags", presenceStart,
            header.count(), Integer.toUnsignedLong(full.getInt(24)));
        if (presence != null) {
            return IntegrityReport.corrupt(path, FORMAT, presence + "; an entity with a vector "
                + "may read as having none, or a slot of zeros as a real vector");
        }
        String vectors = regionProblem(channel, "vectors", vectorsStart, size - vectorsStart,
            Integer.toUnsignedLong(full.getInt(28)));
        if (vectors != null) {
            return IntegrityReport.corrupt(path, FORMAT, vectors + "; an entity's vector is no "
                + "longer the one embedded, and every rerank it takes part in is moved");
        }
        return IntegrityReport.intact(path, FORMAT, "format version " + EntityVectors.VERSION
            + ": the header, the " + header.count() + " presence flags and the "
            + (size - vectorsStart) + " bytes of vectors all matched their CRC32C");
    }

    /**
     * Describes a region that does not digest to what the header records, or returns null.
     *
     * @param channel  A channel over the file
     * @param name     What the region holds
     * @param offset   Where it starts
     * @param length   How long it is
     * @param recorded The CRC32C the header records for it
     * @return The problem, or null when it matches
     * @throws IOException if the region cannot be read
     */
    private static String regionProblem(FileChannel channel, String name, long offset,
                                        long length, long recorded) throws IOException {
        long actual = IndexChecksum.of(channel, offset, length);
        if (actual == recorded) {
            return null;
        }
        return "the header records 0x" + Long.toHexString(recorded) + " over the " + length
            + " bytes of " + name + " but they digest to 0x" + Long.toHexString(actual);
    }

    /**
     * Describes a header whose dimension or count is unusable, or that does not match the file's
     * size, or returns null.
     *
     * @param header The header
     * @param size   The file's size
     * @return The problem, or null when the shape holds
     */
    private static String shapeProblem(Header header, long size) {
        if (header.dimension() <= 0 || header.dimension() > EntityVectors.MAX_DIMENSION) {
            return "its header claims dimension " + header.dimension() + ", which is not usable";
        }
        if (header.count() < 0) {
            return "its header claims " + header.count() + " entities";
        }
        long expected = expectedSize(header);
        if (expected < 0 || size != expected) {
            return "the file is " + size + " bytes where its header describes " + expected
                + ", so it has " + (size < expected ? "lost its tail" : "been written past its end");
        }
        return null;
    }

    /**
     * The size a well-formed file with this header has.
     *
     * @param header The header
     * @return The size, or -1 when the header's count is too large to be one
     */
    static long expectedSize(Header header) {
        try {
            long vectors = Math.multiplyExact(Math.multiplyExact(header.count(),
                (long) header.dimension()), (long) Float.BYTES);
            return Math.addExact(header.headerBytes() + header.count(), vectors);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    /**
     * Decodes the fields every version shares.
     *
     * @param fixed At least the first {@link EntityVectors#V1_HEADER_BYTES} bytes
     * @return The header
     */
    private static Header headerOf(ByteBuffer fixed) {
        int version = fixed.getInt(8);
        int headerBytes = version == EntityVectors.OLDEST_VERSION
            ? EntityVectors.V1_HEADER_BYTES
            : EntityVectors.HEADER_BYTES;
        return new Header(version, fixed.getInt(12), fixed.getLong(16), headerBytes);
    }

    /**
     * Reads up to {@code length} bytes from an offset, stopping early only at the end of the file.
     *
     * @param channel The channel
     * @param offset  Where to start
     * @param length  How many bytes
     * @return The buffer, little-endian, left with remaining space when the file ended first
     * @throws IOException if the read fails
     */
    private static ByteBuffer read(FileChannel channel, long offset, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        return buffer;
    }
}
