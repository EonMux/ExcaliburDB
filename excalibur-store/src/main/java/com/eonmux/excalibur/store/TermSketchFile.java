package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.filter.sketch.CountMinSketch;
import com.eonmux.excalibur.vector.durability.DurableFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads and writes a segment's term-frequency sketch as a file beside the segment.
 *
 * <p>The sketch is derived entirely from the segment's live entries, so losing the file loses
 * nothing that cannot be rebuilt. What the file saves is the rebuild: re-tokenising every chunk
 * of every segment on each start. The file therefore has one job beyond holding the counters -
 * saying which state of the segment they describe - so that a sketch written before a crash, or
 * by a segment that no longer owns the name, is recognised as stale and rebuilt rather than
 * trusted.</p>
 *
 * <h2>Name</h2>
 * <p>{@code segment_<N>.tfs} beside {@code segment_<N>.dat}, derived the way the write log's name
 * is: a trailing {@code .dat} is replaced, anything else has the suffix appended. A write is
 * staged as {@code segment_<N>.tfs.<unique>.tmp} in the same directory and renamed over the
 * final name. A process that dies between creating the staging file and moving or removing it
 * leaves the file behind, so the staging files of a segment are {@linkplain #sweepStaging(Path)
 * swept} when the segment is next opened, before anything can be writing its sidecar.</p>
 *
 * <h2>Layout (version 2)</h2>
 * <pre>
 * magic          (8B)  the ASCII bytes "EXTFSKCH"
 * version        (4B)  format version
 * payloadLength  (4B)  bytes of body that follow the header
 * payloadCrc     (8B)  CRC32C of the body
 * headerCrc      (8B)  CRC32C of the 24 header bytes before it
 * body:
 *   walNextLsn      (8B)  the segment's write log sequence number when the sketch was taken
 *   entryCount      (8B)  occupied slots, tombstones included
 *   liveEntryCount  (8B)  occupied slots that are not tombstoned
 *   sketch          (rest) the counters, in {@link CountMinSketch#toBytes()} layout
 * </pre>
 *
 * <p>All fields are big-endian, the order the sketch's own layout uses.</p>
 *
 * <h2>The watermark</h2>
 * <p>The three figures ahead of the sketch are compared against the reopened segment after its
 * write log has been replayed. The log's sequence number advances on every logged put and
 * delete and is recovered on open, so a sketch taken before writes that reached the log but not
 * the sidecar - which is what a crash leaves - disagrees with it. A compaction or split gives the
 * segment a new, empty log, so a sketch left behind by the file the name used to hold disagrees
 * too. The entry counts catch the remaining case of a log that happens to be at the same number
 * over different contents.</p>
 *
 * <p><strong>Thread safety:</strong> stateless. Writes are staged under a unique name and moved
 * into place, so concurrent writers of the same sidecar each publish a whole file and readers
 * never see a partial one.</p>
 */
public final class TermSketchFile {

    private static final Logger LOGGER = Logger.getLogger(TermSketchFile.class.getName());

    /** The suffix the sidecar takes in place of the segment's {@code .dat}. */
    public static final String SUFFIX = ".tfs";

    /** Name used when a damaged sidecar is reported. */
    private static final String FORMAT = "term frequency sketch";

    /** File identifier, the ASCII bytes "EXTFSKCH". */
    private static final long MAGIC = 0x45585446534B4348L;

    /**
     * Current format version.
     *
     * <p>The version covers what the counters mean as well as where the bytes sit: the layout
     * below has not changed since version 1, but the columns a {@link CountMinSketch} counts an
     * item in have, and a version 1 sidecar holds counts filed under the old columns. Refusing it
     * by version gets it rebuilt from the segment on open, with a message naming the version it
     * was written at rather than one saying its checksummed body holds no sketch.</p>
     */
    static final int VERSION = 2;

    /** Bytes of header the header checksum covers: magic, version, length and payload CRC. */
    private static final int HEADER_COVERED_BYTES = 24;

    /** Total header bytes, the header checksum included. */
    static final int HEADER_BYTES = HEADER_COVERED_BYTES + Long.BYTES;

    /** Bytes of watermark ahead of the sketch in the body. */
    private static final int WATERMARK_BYTES = 3 * Long.BYTES;

    /**
     * Largest body this build will read.
     *
     * <p>The length comes out of the file and sizes an allocation. The sketch a segment keeps is
     * a few tens of kilobytes; a declared length beyond this bound describes no sketch this build
     * wrote.</p>
     */
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private TermSketchFile() {
    }

    /**
     * The state of a segment a sketch was taken at.
     *
     * @param walNextLsn     The next sequence number the segment's write log would hand out
     * @param entryCount     Occupied slots, tombstones included
     * @param liveEntryCount Occupied slots that are not tombstoned
     */
    public record Watermark(long walNextLsn, long entryCount, long liveEntryCount) {
    }

    /**
     * A sketch read back from its sidecar, with the state it was taken at.
     *
     * @param watermark The state of the segment the counters describe
     * @param sketch    The counters
     */
    public record Stored(Watermark watermark, CountMinSketch sketch) {
    }

    /**
     * Returns the sidecar path for a segment file.
     *
     * @param segmentPath The segment's backing file
     * @return {@code segment_<N>.tfs} for {@code segment_<N>.dat}; the suffix appended otherwise
     */
    public static Path pathFor(Path segmentPath) {
        Objects.requireNonNull(segmentPath, "segmentPath cannot be null");
        String name = segmentPath.getFileName().toString();
        String base = name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
        return segmentPath.resolveSibling(base + SUFFIX);
    }

    /**
     * Writes a sketch and its watermark to {@code path}, replacing whatever is there.
     *
     * <p>The bytes are staged in a uniquely named file in the same directory, forced to storage,
     * moved into place and the directory entry forced, so a crash leaves either the previous
     * sidecar or this one.</p>
     *
     * @param path        The sidecar to write
     * @param watermark   The state of the segment the sketch was taken at
     * @param sketchBytes The sketch, as {@link CountMinSketch#toBytes()} returned it
     * @throws IOException if the file cannot be written
     */
    public static void write(Path path, Watermark watermark, byte[] sketchBytes) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(watermark, "watermark cannot be null");
        Objects.requireNonNull(sketchBytes, "sketchBytes cannot be null");

        byte[] body = ByteBuffer.allocate(WATERMARK_BYTES + sketchBytes.length)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(watermark.walNextLsn())
            .putLong(watermark.entryCount())
            .putLong(watermark.liveEntryCount())
            .put(sketchBytes)
            .array();
        byte[] header = header(body);

        Path staging = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer out = ByteBuffer.allocate(header.length + body.length)
                    .put(header).put(body).flip();
                while (out.hasRemaining()) {
                    channel.write(out);
                }
                channel.force(true);
            }
            moveIntoPlace(staging, path);
            DurableFiles.syncContainingDirectory(path);
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not remove the staging file " + staging
                    + " left by a term sketch write", e);
            }
        }
    }

    /**
     * Reads a sidecar, having checked everything that guards it.
     *
     * @param path The sidecar to read
     * @return The sketch and the watermark it was written with
     * @throws IOException if the file is missing, is not a sidecar this build writes, is
     *         truncated or overlong, or does not match its checksums
     */
    public static Stored read(Path path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");
        return decode(path, Files.readAllBytes(path));
    }

    /**
     * Checks and decodes the bytes of a sidecar that has already been read.
     *
     * <p>Separate from the read so that {@link #verifyIntegrity} can tell the two failures
     * apart: every exception from here is about what the bytes are, never about whether they
     * could be had.</p>
     *
     * @param path The sidecar the bytes came from, for the messages
     * @param file Its whole contents
     * @return The sketch and the watermark it was written with
     * @throws IOException if the bytes are not a sidecar this build writes, are truncated or
     *         overlong, or do not match their checksums
     */
    private static Stored decode(Path path, byte[] file) throws IOException {
        if (file.length < HEADER_BYTES) {
            throw new IOException("Term sketch file " + path + " is truncated: " + file.length
                + " bytes is shorter than its header");
        }
        ByteBuffer header = ByteBuffer.wrap(file, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        long magic = header.getLong();
        if (magic != MAGIC) {
            throw new IOException("File " + path + " is not a term frequency sketch (bad magic "
                + "number " + Long.toHexString(magic) + ")");
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Term sketch file " + path + " is at version " + version
                + ", which this build cannot read (expected " + VERSION + ")");
        }
        int payloadLength = header.getInt();
        long payloadCrc = header.getLong();
        long headerCrc = header.getLong();
        long actualHeaderCrc = IndexChecksum.of(file, 0, HEADER_COVERED_BYTES);
        if (headerCrc != actualHeaderCrc) {
            throw new IOException("Term sketch file " + path + " has a header checksum of 0x"
                + Long.toHexString(headerCrc) + " but its header digests to 0x"
                + Long.toHexString(actualHeaderCrc));
        }
        if (payloadLength < WATERMARK_BYTES || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IOException("Term sketch file " + path + " declares " + payloadLength
                + " bytes of body, outside the range any sketch occupies");
        }
        if (file.length != HEADER_BYTES + payloadLength) {
            throw new IOException("Term sketch file " + path + " holds " + (file.length
                - HEADER_BYTES) + " bytes of body but its header declares " + payloadLength);
        }
        long actualPayloadCrc = IndexChecksum.of(file, HEADER_BYTES, payloadLength);
        if (payloadCrc != actualPayloadCrc) {
            throw new IOException("Term sketch file " + path + " has a checksum of 0x"
                + Long.toHexString(payloadCrc) + " over its body but it digests to 0x"
                + Long.toHexString(actualPayloadCrc));
        }

        ByteBuffer body = ByteBuffer.wrap(file, HEADER_BYTES, payloadLength)
            .order(ByteOrder.BIG_ENDIAN);
        Watermark watermark = new Watermark(body.getLong(), body.getLong(), body.getLong());
        byte[] sketchBytes = new byte[body.remaining()];
        body.get(sketchBytes);
        try {
            return new Stored(watermark, CountMinSketch.fromBytes(sketchBytes));
        } catch (IllegalArgumentException e) {
            throw new IOException("Term sketch file " + path + " matches its checksum but does "
                + "not hold a sketch: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a sidecar without handing its sketch to anything.
     *
     * <p>Reports rather than throws, so a survey of a node's files can continue past a damaged
     * one. A damaged sidecar costs a rebuild on the next open, not data.</p>
     *
     * <p>A file whose bytes could not be had at all - an I/O error from the device, a
     * permission refused - is reported as unreadable, not corrupt. The two mean different
     * things to whoever reads the verdict: corrupt bytes are this one derived file's problem
     * and a rebuild answers them, while a read the operating system refused says something
     * about the device or the directory's ownership that reaches past this file, and nothing
     * about what the file holds.</p>
     *
     * @param path The sidecar to verify
     * @return What the check concluded
     */
    public static IntegrityReport verifyIntegrity(Path path) {
        Objects.requireNonNull(path, "path cannot be null");
        if (!Files.exists(path)) {
            return IntegrityReport.missing(path, FORMAT);
        }
        byte[] file;
        try {
            file = Files.readAllBytes(path);
        } catch (IOException e) {
            return IntegrityReport.unreadable(path, FORMAT, "the file could not be read: " + e);
        }
        try {
            decode(path, file);
            return IntegrityReport.intact(path, FORMAT, "the sketch matches its checksum");
        } catch (IOException e) {
            return IntegrityReport.corrupt(path, FORMAT, e.getMessage());
        }
    }

    /**
     * Removes the sidecar beside a segment file, if there is one.
     *
     * <p>Called before a new file takes a segment name, so the sketch describing the previous
     * file is never read as describing the new one. A failure is logged, not raised: the
     * watermark still refuses a stale sidecar on the next open, so the worst case is a sketch
     * rebuilt that did not need to be.</p>
     *
     * @param segmentPath The segment file whose sidecar is to go
     */
    static void deleteFor(Path segmentPath) {
        Path sidecar = pathFor(segmentPath);
        try {
            Files.deleteIfExists(sidecar);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not remove the stale term sketch " + sidecar, e);
        }
    }

    /**
     * Removes the staging files an interrupted {@link #write} left beside one sidecar.
     *
     * <p>Only files named {@code <sidecar>.<anything>.tmp} are touched - the names
     * {@link #write} stages under for this sidecar, and no other segment's. The caller must know
     * that no write of this sidecar is in progress, which is true of a segment being opened: its
     * sketch is persisted only once it has been. A failure is logged and the sweep carries on;
     * a file left behind costs disk space, never correctness, since nothing reads staging
     * files.</p>
     *
     * @param sidecar The sidecar whose staging files are to go
     * @return How many staging files were removed
     */
    static int sweepStaging(Path sidecar) {
        Objects.requireNonNull(sidecar, "sidecar cannot be null");
        Path directory = sidecar.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        String prefix = sidecar.getFileName() + ".";
        int removed = 0;
        try (java.nio.file.DirectoryStream<Path> staged =
                 Files.newDirectoryStream(directory, entry -> isStagingName(entry, prefix))) {
            for (Path file : staged) {
                try {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not remove the staging file " + file
                        + " left by an interrupted term sketch write", e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list " + directory + " for staging files left "
                + "by interrupted writes of " + sidecar, e);
        }
        if (removed > 0) {
            LOGGER.log(Level.INFO, "Removed {0} staging file(s) left beside {1} by interrupted "
                + "term sketch writes", new Object[] { removed, sidecar });
        }
        return removed;
    }

    /**
     * Whether a file carries the name {@link #write} stages a sidecar under.
     *
     * @param file   The candidate
     * @param prefix The sidecar's file name followed by a dot
     * @return true for {@code <prefix><non-empty>.tmp}
     */
    private static boolean isStagingName(Path file, String prefix) {
        String name = file.getFileName().toString();
        return name.startsWith(prefix) && name.endsWith(".tmp")
            && name.length() > prefix.length() + ".tmp".length();
    }

    /**
     * Builds the header that precedes a body, checksums included.
     */
    private static byte[] header(byte[] body) {
        byte[] covered = ByteBuffer.allocate(HEADER_COVERED_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(MAGIC)
            .putInt(VERSION)
            .putInt(body.length)
            .putLong(IndexChecksum.of(body, 0, body.length))
            .array();
        return ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(covered)
            .putLong(IndexChecksum.of(covered, 0, HEADER_COVERED_BYTES))
            .array();
    }

    /**
     * Moves the staged file over the destination, preferring an atomic rename.
     */
    private static void moveIntoPlace(Path staging, Path path) throws IOException {
        try {
            Files.move(staging, path,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.log(Level.WARNING, "Atomic rename is not available for " + path
                + "; the term sketch is moved without it and a reader could observe it "
                + "partially written");
            Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
