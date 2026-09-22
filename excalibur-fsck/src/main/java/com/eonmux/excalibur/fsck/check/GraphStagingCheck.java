package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.staging.EntityTextLog;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.LabelInterner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the append-only logs a tenant's {@code graph-staging} directory holds.
 *
 * <p>Two different claims are made here, and the difference between them is the point.
 * {@code erasure.log} has a public, non-mutating inspection that replays every record
 * against its CRC and refuses damage with sound records behind it, so that file is
 * genuinely verified. The other four have no such entry point - their open paths write the
 * header, truncate partial records and migrate old versions in place - so all this can do
 * is confirm the sixteen-byte header, and the verdict says exactly that rather than
 * implying the records were read.</p>
 */
public final class GraphStagingCheck {

    /** Name of the directory these logs live in, under a tenant's namespace. */
    public static final String DIRECTORY_SUFFIX = "-staging";

    /** Bytes of header every one of these logs begins with. */
    private static final int HEADER_BYTES = 16;

    /**
     * Name of the graph erasure log inside a staging directory.
     *
     * <p>Stated here because the constant that decides it lives in the API module's tenant
     * layout, which this tool does not depend on: it reads directories, not deployments.</p>
     */
    public static final String ERASURE_LOG = "erasure.log";

    private GraphStagingCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether a file name is one of the staging logs.
     *
     * @param fileName The file's name
     * @return true if this class can say something about it
     */
    public static boolean matches(String fileName) {
        return GraphStagingStore.ENTITY_LABELS.equals(fileName)
            || GraphStagingStore.RELATION_LABELS.equals(fileName)
            || GraphStagingStore.TRIPLE_LOG.equals(fileName)
            || GraphStagingStore.ENTITY_TEXT_LOG.equals(fileName)
            || ERASURE_LOG.equals(fileName);
    }

    /**
     * Checks one staging log.
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck check(Path file, String relative) {
        String name = file.getFileName().toString();
        if (ERASURE_LOG.equals(name)) {
            return erasureLog(file, relative);
        }
        if (GraphStagingStore.ENTITY_TEXT_LOG.equals(name)) {
            return header(file, relative, "graph entity text log", EntityTextLog.MAGIC);
        }
        if (GraphStagingStore.TRIPLE_LOG.equals(name)) {
            return header(file, relative, "graph triple log", GraphStagingStore.MAGIC);
        }
        return header(file, relative, "graph label interner", LabelInterner.MAGIC);
    }

    /**
     * Replays the graph erasure log against its record checksums.
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    private static FileCheck erasureLog(Path file, String relative) {
        String format = "graph erasure log";
        long size = sizeOf(file);
        try {
            ErasureLog.Pending pending = ErasureLog.inspect(file);
            return new FileCheck(file, relative, format, "v" + ErasureLog.VERSION,
                CheckStatus.INTACT,
                "every record matched its checksum; " + pending.count()
                    + " erasure requests are still outstanding", size);
        } catch (IOException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the log could not be replayed: " + e.getMessage(), size);
        } catch (RuntimeException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the log could not be replayed: " + e, size);
        }
    }

    /**
     * Confirms a staging log's header and says that is all that was confirmed.
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @param format   Name of the format
     * @param magic    The magic number the file must begin with
     * @return The verdict
     */
    private static FileCheck header(Path file, String relative, String format, long magic) {
        byte[] head;
        long size;
        try {
            size = Files.size(file);
            head = readHead(file);
        } catch (IOException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the file could not be read: " + e, -1);
        }
        if (head.length < HEADER_BYTES) {
            return new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the file is " + size + " bytes, too short to hold its " + HEADER_BYTES
                    + "-byte header", size);
        }
        // Written by DataOutputStream, so the header is big-endian even where the records
        // that follow it are not.
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getLong(0) != magic) {
            return new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the file does not begin with this log's magic number", size);
        }
        int version = buffer.getInt(8);
        return new FileCheck(file, relative, format, "v" + version, CheckStatus.UNCHECKED,
            "the header is well-formed and " + (size - HEADER_BYTES) + " bytes of records "
                + "follow it. Each record carries a CRC32, but this build exposes no "
                + "read-only replay for this log - opening it writes - so the records "
                + "themselves were not read", size);
    }

    /**
     * Reads the first bytes of a file.
     *
     * @param file The file
     * @return Up to {@link #HEADER_BYTES} bytes
     * @throws IOException if the file cannot be read
     */
    private static byte[] readHead(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            return stream.readNBytes(HEADER_BYTES);
        }
    }

    /**
     * Reads a file's size, answering -1 rather than failing the survey.
     *
     * @param file The file
     * @return Its size in bytes, or -1
     */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
