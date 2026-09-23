package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The integrity block of a node-to-document mapping file, and the rules for checking it.
 *
 * <h2>Layout</h2>
 * <p>Version 2 widened the header from 24 bytes to 48 and put the entry count where it
 * already was, so the only field that moved is the one that was reserved:</p>
 * <pre>
 *   offset  0  magic         (8 bytes)
 *   offset  8  version       (4 bytes)
 *   offset 12  seal state    (4 bytes)  was reserved
 *   offset 16  entry count   (8 bytes)
 *   offset 24  payload bytes (8 bytes)  how much of the file after the header the CRC covers
 *   offset 32  payload CRC   (8 bytes)  CRC32C of [48, 48 + payload bytes)
 *   offset 40  header CRC    (8 bytes)  CRC32C of [0, 40)
 * </pre>
 * <p>All fields are little-endian, matching the entries behind them.</p>
 *
 * <h2>Seal state</h2>
 * <p>{@code persist()} rewrites the whole file and seals it. {@code appendMapping()} adds one
 * entry to the end of a file it never reads in full, so it cannot recompute a checksum over
 * it and leaves the file open instead; the next {@code persist()} or {@code compact()} seals
 * it again. Verification says which of the two it found rather than calling an unsealed file
 * sound.</p>
 *
 * <h2>Version 1</h2>
 * <p>A version 1 file has a 24-byte header and no checksum. It is read as before and reported
 * as unprotected. It is not upgraded in place, because a checksum written over it now would
 * attest to bytes nothing has ever attested to; it becomes a version 2 file the first time
 * the mapping is persisted.</p>
 *
 * <h2>Versions 2 and 3</h2>
 * <p>Both carry this block, and differ only in what their entries name: documents at version 2,
 * chunks at version 3. The checksum is the same either way, so both are verified here alike; a
 * sound version 2 file is reported intact with a note that its entries still name documents,
 * because that is what an operator reading the report needs to know about it - the node re-keys
 * it from its segment the next time it opens the graph, and nothing about the file is
 * damaged.</p>
 */
final class NodeMappingIntegrity {

    /** Name used when a damaged file is refused or reported. */
    static final String FORMAT = "HNSW node-to-document mapping";

    /** Seal state written when the whole file has been rewritten and digested. */
    static final int STATE_SEALED = 0x5345414C;

    /** Seal state written when the file has been appended to since it was last digested. */
    static final int STATE_OPEN = 0x4F50454E;

    /** Offset of the seal state field. */
    static final int OFFSET_STATE = 12;

    /** Offset of the covered payload length. */
    static final int OFFSET_PAYLOAD_LENGTH = 24;

    /** Offset of the payload CRC32C. */
    static final int OFFSET_PAYLOAD_CRC = 32;

    /** Offset of the header CRC32C. */
    static final int OFFSET_HEADER_CRC = 40;

    /** Number of leading header bytes the header CRC covers. */
    static final int HEADER_COVERED_BYTES = 40;

    private NodeMappingIntegrity() {
    }

    /**
     * Stamps the header of a fully rewritten mapping file with its checksums.
     *
     * @param header        A header-sized little-endian buffer holding the other fields
     * @param payload       The entry bytes that follow the header
     * @param payloadLength How many of those bytes are entries
     */
    static void seal(ByteBuffer header, byte[] payload, int payloadLength) {
        header.putInt(OFFSET_STATE, STATE_SEALED);
        header.putLong(OFFSET_PAYLOAD_LENGTH, payloadLength);
        header.putLong(OFFSET_PAYLOAD_CRC, IndexChecksum.of(payload, 0, payloadLength));
        header.putLong(OFFSET_HEADER_CRC,
            IndexChecksum.of(header.array(), 0, HEADER_COVERED_BYTES));
    }

    /**
     * Rewrites the header of a file that has just been appended to, clearing its seal.
     *
     * @param channel The open mapping file
     * @param header  The file's current header, little-endian, already updated in memory
     * @throws IOException if the header cannot be written back
     */
    static void breakSeal(FileChannel channel, ByteBuffer header) throws IOException {
        header.putInt(OFFSET_STATE, STATE_OPEN);
        header.putLong(OFFSET_PAYLOAD_LENGTH, 0L);
        header.putLong(OFFSET_PAYLOAD_CRC, 0L);
        header.putLong(OFFSET_HEADER_CRC,
            IndexChecksum.of(header.array(), 0, HEADER_COVERED_BYTES));
        ByteBuffer copy = ByteBuffer.wrap(header.array()).order(ByteOrder.LITTLE_ENDIAN);
        long at = 0;
        while (copy.hasRemaining()) {
            at += channel.write(copy, at);
        }
    }

    /**
     * Reaches a verdict on an open mapping file.
     *
     * @param path    The file, for the report
     * @param channel The open file
     * @param header  Its header, already read, little-endian
     * @param version The format version found in the header
     * @return What the check concluded
     * @throws IOException if the payload cannot be read
     */
    static IntegrityReport inspect(Path path, FileChannel channel, ByteBuffer header, int version)
            throws IOException {
        if (version < NodeDocumentMapping.FIRST_SEALED_VERSION) {
            return IntegrityReport.legacy(path, FORMAT, version);
        }
        int state = header.getInt(OFFSET_STATE);
        if (state != STATE_SEALED) {
            return IntegrityReport.unsealed(path, FORMAT, "the mapping has been appended to "
                + "since it was last written in full, so its payload cannot be verified; "
                + "compacting the mapping seals it again");
        }
        long storedHeaderCrc = header.getLong(OFFSET_HEADER_CRC);
        long actualHeaderCrc = IndexChecksum.of(header.array(), 0, HEADER_COVERED_BYTES);
        if (storedHeaderCrc != actualHeaderCrc) {
            return IntegrityReport.corrupt(path, FORMAT, "the header checksum is 0x"
                + Long.toHexString(storedHeaderCrc) + " but its " + HEADER_COVERED_BYTES
                + " bytes digest to 0x" + Long.toHexString(actualHeaderCrc)
                + ", so the entry count in it cannot be trusted");
        }
        long covered = header.getLong(OFFSET_PAYLOAD_LENGTH);
        long available = channel.size() - NodeDocumentMapping.HEADER_SIZE;
        if (covered < 0 || covered > available) {
            return IntegrityReport.corrupt(path, FORMAT, "the header claims " + covered
                + " bytes of entries but the file holds only " + available);
        }
        long storedPayloadCrc = header.getLong(OFFSET_PAYLOAD_CRC);
        long actualPayloadCrc =
            IndexChecksum.of(channel, NodeDocumentMapping.HEADER_SIZE, covered);
        if (storedPayloadCrc != actualPayloadCrc) {
            return IntegrityReport.corrupt(path, FORMAT, "the stored checksum over " + covered
                + " bytes of entries is 0x" + Long.toHexString(storedPayloadCrc)
                + " but they digest to 0x" + Long.toHexString(actualPayloadCrc)
                + ", so at least one node is now attributed to a different document");
        }
        return IntegrityReport.intact(path, FORMAT, covered + " bytes of entries verified"
            + keyingNote(version));
    }

    /**
     * Says what a sound file's entries name, when that is not what this build writes.
     *
     * @param version The file's version
     * @return A clause to append to the verdict, empty for a chunk-keyed file
     */
    private static String keyingNote(int version) {
        if (NodeDocumentMapping.keyingOf(version) == NodeDocumentMapping.Keying.CHUNK) {
            return "";
        }
        return "; written at version " + version + ", whose entries name documents rather than "
            + "chunks - the node re-keys it to chunks from its segment's stored embeddings once "
            + "it has the graph open, before integrating anything more into it";
    }

    /**
     * Verifies a mapping file on disk without loading the mapping.
     *
     * @param path The mapping file to verify
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
            return verifyChannel(path, channel);
        } catch (IOException e) {
            return IntegrityReport.unreadable(path, FORMAT, e.toString());
        }
    }

    /**
     * Verifies an open mapping file, reading and validating its header first.
     */
    private static IntegrityReport verifyChannel(Path path, FileChannel channel)
            throws IOException {
        long size = channel.size();
        if (size < NodeDocumentMapping.HEADER_SIZE_V1) {
            return IntegrityReport.corrupt(path, FORMAT,
                "the file is " + size + " bytes, too short to hold a header");
        }
        ByteBuffer probe = ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        NodeDocumentMapping.readFully(channel, probe, 0, "mapping header");
        if (probe.getLong(0) != NodeDocumentMapping.MAGIC) {
            return IntegrityReport.corrupt(path, FORMAT,
                "the file does not start with the mapping magic number");
        }
        int version = probe.getInt(Long.BYTES);
        if (version < NodeDocumentMapping.FIRST_SEALED_VERSION) {
            return IntegrityReport.legacy(path, FORMAT, version);
        }
        if (size < NodeDocumentMapping.HEADER_SIZE) {
            return IntegrityReport.corrupt(path, FORMAT, "the file is " + size
                + " bytes, shorter than its " + NodeDocumentMapping.HEADER_SIZE
                + "-byte header");
        }
        ByteBuffer header = ByteBuffer.allocate(NodeDocumentMapping.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        NodeDocumentMapping.readFully(channel, header, 0, "mapping header");
        return inspect(path, channel, header, version);
    }
}
