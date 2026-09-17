package io.excalibur.vector.hnsw;

import io.excalibur.core.integrity.IndexChecksum;
import io.excalibur.core.integrity.IntegrityReport;
import io.excalibur.core.mapped.MappedFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The integrity block of an HNSW graph file, and the rules for writing and checking it.
 *
 * <h2>Where it lives</h2>
 * <p>In the graph's own 4 KiB header, which had only 84 bytes in use. Four fields:</p>
 * <pre>
 *   offset  88  seal state    (4 bytes)  SEAL when the writer sealed the file, OPEN otherwise
 *   offset  96  payload bytes (8 bytes)  how much of the file after the header the CRC covers
 *   offset 104  payload CRC   (8 bytes)  CRC32C of [4096, 4096 + payload bytes)
 *   offset 112  header CRC    (8 bytes)  CRC32C of [0, 112)
 * </pre>
 *
 * <h2>Seal state, and why a live graph carries none</h2>
 * <p>The graph file is memory mapped and mutated in place on every insert, tombstone and
 * offset update. Nothing can hold a checksum current against that, so the file is sealed
 * instead: the CRCs are computed on {@code flush()} and on {@code close()}, and the state
 * field is set back to OPEN the moment a writer attaches to the file.</p>
 *
 * <p>Both CRCs are therefore checked only on a file that is sealed. A file left OPEN was not
 * closed cleanly, which is a statement about the writer, not about the bytes: refusing it
 * would turn every crash into a lost index, and the graph already recovers a torn tail by
 * walking back to the last node index entry that describes itself. Verification reports such
 * a file as unsealed rather than intact, so nothing downstream can mistake "not refused" for
 * "checked".</p>
 *
 * <h2>Version 3 and what came before</h2>
 * <p>A graph written before this block existed is at version 2 (or 1), and there is no way to
 * acquire a checksum for bytes that were never covered by one. Such a file is read as before
 * and left at its version - writing a CRC over it now would be a claim this build cannot
 * make. It gains protection the next time it is rebuilt or compacted, because that produces a
 * new file at version 3. Verification names it explicitly as unprotected.</p>
 */
final class HnswGraphIntegrity {

    /** Name used when a damaged file is refused or reported. */
    static final String FORMAT = "HNSW graph";

    /** Seal state written by a clean flush or close: the ASCII bytes "SEAL". */
    static final int STATE_SEALED = 0x5345414C;

    /** Seal state written while a writer holds the file: the ASCII bytes "OPEN". */
    static final int STATE_OPEN = 0x4F50454E;

    /** Offset of the seal state field. */
    static final long OFFSET_STATE = 88;

    /** Offset of the covered payload length. */
    static final long OFFSET_PAYLOAD_LENGTH = 96;

    /** Offset of the payload CRC32C. */
    static final long OFFSET_PAYLOAD_CRC = 104;

    /** Offset of the header CRC32C. */
    static final long OFFSET_HEADER_CRC = 112;

    /** Number of leading header bytes the header CRC covers. */
    static final int HEADER_COVERED_BYTES = 112;

    private HnswGraphIntegrity() {
    }

    /**
     * Writes the integrity block of a brand new graph file, in its open state.
     *
     * @param file The mapping of the file whose header has just been written
     */
    static void initialize(MappedFile file) {
        file.writeLong(OFFSET_PAYLOAD_LENGTH, 0L);
        file.writeLong(OFFSET_PAYLOAD_CRC, 0L);
        markOpen(file);
    }

    /**
     * Records that a writer has attached to the file, so its stored CRCs no longer apply.
     *
     * <p>Called once per attach rather than on every mutation. Clearing the seal lazily at the
     * first write would mean finding every write in a four-thousand-line class, and a single
     * missed one leaves a sound file claiming a checksum it no longer matches - which the next
     * open refuses. Failing to protect an unclean shutdown is a smaller cost than refusing
     * healthy graphs.</p>
     *
     * @param file The mapping of the file a writer has attached to
     */
    static void markOpen(MappedFile file) {
        file.writeInt(OFFSET_STATE, STATE_OPEN);
        file.writeLong(OFFSET_HEADER_CRC, headerChecksum(file));
    }

    /**
     * Seals the file: covers its payload and header with checksums a later open can check.
     *
     * @param file          The mapping to seal
     * @param payloadLength How many bytes after the header hold live graph data
     */
    static void seal(MappedFile file, long payloadLength) {
        long covered = Math.max(0, Math.min(payloadLength, file.size() - HnswGraphHeader.HEADER_SIZE));
        file.writeLong(OFFSET_PAYLOAD_LENGTH, covered);
        file.writeLong(OFFSET_PAYLOAD_CRC,
            IndexChecksum.of(file.segment(), HnswGraphHeader.HEADER_SIZE, covered));
        file.writeInt(OFFSET_STATE, STATE_SEALED);
        file.writeLong(OFFSET_HEADER_CRC, headerChecksum(file));
    }

    /**
     * Refuses a mapped graph file whose stored checksums do not match its bytes.
     *
     * @param file    The mapping to check
     * @param path    The file, for the refusal message
     * @param version The format version read from the header
     * @throws IOException if the file is sealed and damaged
     */
    static void requireIntact(MappedFile file, Path path, int version) throws IOException {
        inspect(file, path, version).requireIntact();
    }

    /**
     * Reaches a verdict on a mapped graph file without refusing anything.
     *
     * @param file    The mapping to check
     * @param path    The file the mapping belongs to
     * @param version The format version read from the header
     * @return What the check concluded
     */
    static IntegrityReport inspect(MappedFile file, Path path, int version) {
        if (version < HnswGraphHeader.VERSION) {
            return IntegrityReport.legacy(path, FORMAT, version);
        }
        int state = file.readInt(OFFSET_STATE);
        if (state != STATE_SEALED) {
            return unsealedReport(path, state);
        }
        long storedHeaderCrc = file.readLong(OFFSET_HEADER_CRC);
        long actualHeaderCrc = headerChecksum(file);
        if (storedHeaderCrc != actualHeaderCrc) {
            return headerMismatch(path, storedHeaderCrc, actualHeaderCrc);
        }
        long covered = file.readLong(OFFSET_PAYLOAD_LENGTH);
        long available = file.size() - HnswGraphHeader.HEADER_SIZE;
        if (covered < 0 || covered > available) {
            return IntegrityReport.corrupt(path, FORMAT, "the header claims " + covered
                + " bytes of graph data but the file holds only " + available);
        }
        long storedPayloadCrc = file.readLong(OFFSET_PAYLOAD_CRC);
        long actualPayloadCrc =
            IndexChecksum.of(file.segment(), HnswGraphHeader.HEADER_SIZE, covered);
        if (storedPayloadCrc != actualPayloadCrc) {
            return payloadMismatch(path, covered, storedPayloadCrc, actualPayloadCrc);
        }
        return IntegrityReport.intact(path, FORMAT, covered + " bytes of graph data verified");
    }

    /**
     * Verifies a graph file on disk without mapping it or opening the graph.
     *
     * <p>This is what a checking tool calls. It reads the header and streams the payload
     * through the digest, so a file too large or too damaged to serve can still be judged.</p>
     *
     * @param path The graph file to verify
     * @return What the check concluded
     */
    static IntegrityReport verify(Path path) {
        if (path == null) {
            throw new NullPointerException("path cannot be null");
        }
        if (!Files.exists(path)) {
            return IntegrityReport.missing(path, FORMAT);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < HnswGraphHeader.HEADER_SIZE) {
                return IntegrityReport.corrupt(path, FORMAT, "the file is " + size
                    + " bytes, shorter than the " + HnswGraphHeader.HEADER_SIZE + "-byte header");
            }
            return verifyChannel(path, channel, size);
        } catch (IOException e) {
            return IntegrityReport.unreadable(path, FORMAT, e.toString());
        }
    }

    /**
     * Verifies an open channel whose length is already known.
     */
    private static IntegrityReport verifyChannel(Path path, FileChannel channel, long size)
            throws IOException {
        byte[] header = readHeader(channel);
        ByteBuffer view = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (view.getLong(0) != HnswGraphHeader.MAGIC) {
            return IntegrityReport.corrupt(path, FORMAT,
                "the file does not start with the HNSW magic number");
        }
        int version = view.getInt(8);
        if (version < HnswGraphHeader.VERSION) {
            return IntegrityReport.legacy(path, FORMAT, version);
        }
        int state = view.getInt((int) OFFSET_STATE);
        if (state != STATE_SEALED) {
            return unsealedReport(path, state);
        }
        long storedHeaderCrc = view.getLong((int) OFFSET_HEADER_CRC);
        long actualHeaderCrc = IndexChecksum.of(header, 0, HEADER_COVERED_BYTES);
        if (storedHeaderCrc != actualHeaderCrc) {
            return headerMismatch(path, storedHeaderCrc, actualHeaderCrc);
        }
        long covered = view.getLong((int) OFFSET_PAYLOAD_LENGTH);
        long available = size - HnswGraphHeader.HEADER_SIZE;
        if (covered < 0 || covered > available) {
            return IntegrityReport.corrupt(path, FORMAT, "the header claims " + covered
                + " bytes of graph data but the file holds only " + available);
        }
        long storedPayloadCrc = view.getLong((int) OFFSET_PAYLOAD_CRC);
        long actualPayloadCrc = IndexChecksum.of(channel, HnswGraphHeader.HEADER_SIZE, covered);
        if (storedPayloadCrc != actualPayloadCrc) {
            return payloadMismatch(path, covered, storedPayloadCrc, actualPayloadCrc);
        }
        return IntegrityReport.intact(path, FORMAT, covered + " bytes of graph data verified");
    }

    /**
     * Reads the fixed header region of a graph file into memory.
     */
    private static byte[] readHeader(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HnswGraphHeader.HEADER_SIZE);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("Header ended after " + buffer.position() + " bytes");
            }
        }
        return buffer.array();
    }

    /**
     * The CRC32C of the leading header bytes the header checksum covers.
     */
    private static long headerChecksum(MappedFile file) {
        return IndexChecksum.of(file.segment(), 0, HEADER_COVERED_BYTES);
    }

    /**
     * Describes a file that no writer sealed.
     */
    private static IntegrityReport unsealedReport(Path path, int state) {
        String reason = state == STATE_OPEN
            ? "a writer holds it open, or the last writer did not close it"
            : "the seal state field reads 0x" + Integer.toHexString(state);
        return IntegrityReport.unsealed(path, FORMAT,
            "the graph is not sealed (" + reason + "), so its payload cannot be verified");
    }

    /**
     * Describes a header whose checksum no longer matches its fields.
     */
    private static IntegrityReport headerMismatch(Path path, long stored, long actual) {
        return IntegrityReport.corrupt(path, FORMAT, "the header checksum is 0x"
            + Long.toHexString(stored) + " but its " + HEADER_COVERED_BYTES
            + " bytes digest to 0x" + Long.toHexString(actual)
            + ", so the node count, entry point or layout in it cannot be trusted");
    }

    /**
     * Describes a payload whose checksum no longer matches its bytes.
     */
    private static IntegrityReport payloadMismatch(Path path, long covered, long stored,
                                                   long actual) {
        return IntegrityReport.corrupt(path, FORMAT, "the stored checksum over " + covered
            + " bytes of graph data is 0x" + Long.toHexString(stored) + " but they digest to 0x"
            + Long.toHexString(actual) + ", so at least one vector, neighbour list or "
            + "tombstone flag has changed since the graph was written");
    }
}
