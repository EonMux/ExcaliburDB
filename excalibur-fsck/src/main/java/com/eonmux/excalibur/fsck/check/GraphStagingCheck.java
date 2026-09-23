package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogInspection;
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
 * <h2>Every record is read, by the node's own code</h2>
 * <p>Each of these logs has a read-only inspection beside the code that writes it:
 * {@link ErasureLog#inspect} for {@code erasure.log} and {@code withdrawals.log}, which share a
 * format; {@link LabelInterner#inspect} for the two label interners;
 * {@link GraphStagingStore#inspectTripleLog} for the staged facts; and
 * {@link EntityTextLog#inspect} for the text each entity was mentioned in. Every one of them walks
 * the records with the replay the node opens, builds from or upgrades the log with, so what is
 * called sound here is what the node reads as sound, and nothing here restates a record layout
 * that could drift from the one written. None of them writes: opening any of these logs creates
 * it, trims its tail or rewrites an earlier version in place, which is why the checker asks
 * rather than opens.</p>
 *
 * <h2>Three verdicts, each the node's</h2>
 * <p>A log whose every record matched its checksum is {@link CheckStatus#INTACT}, with the count
 * that was proven. One that ends in bytes that do not read as a record, with nothing readable
 * after them, is {@link CheckStatus#UNSEALED}: the node takes that for a write a crash cut short
 * and cuts it off when it next opens the log, keeping every record before it, so it is neither
 * damage nor proof - the same verdict a write-ahead log's torn tail gets, and like the node, the
 * repair plan leaves it alone. And damage the node refuses - a record that fails its checksum
 * with an intact record after it, or, in the fixed-width triple log, any whole record that fails
 * its checksum - is {@link CheckStatus#CORRUPT}, and put to an operator rather than into the
 * repair plan: every one of these logs is the only copy of what it holds, and the node opens a
 * missing one as empty, so moving a damaged one aside would lose outstanding erasures, reassign
 * entity ids or empty the next graph build rather than repair anything.</p>
 *
 * <h2>The header first</h2>
 * <p>The version in the header is judged before the records are, against the range each log's
 * reader opens. A version outside it is a file the node refuses, whatever the records behind it
 * look like, so it is reported unreadable rather than corrupt, because a newer build may have
 * written it. An earlier version the node rewrites on open is read in its own framing and
 * reported at the version it is.</p>
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

    /**
     * Name of the log of withdrawals of staged facts still owed, inside a staging directory.
     *
     * <p>Stated here for the reason {@link #ERASURE_LOG} is: the constant that decides it
     * belongs to the ingest module, which this tool does not depend on.</p>
     */
    public static final String WITHDRAWAL_LOG = "withdrawals.log";

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
            || ERASURE_LOG.equals(fileName)
            || WITHDRAWAL_LOG.equals(fileName);
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
            return replayed(file, relative, "graph erasure log", "erasure requests");
        }
        if (WITHDRAWAL_LOG.equals(name)) {
            return replayed(file, relative, "graph withdrawal log",
                "withdrawals of staged facts");
        }
        if (GraphStagingStore.ENTITY_TEXT_LOG.equals(name)) {
            return inspected(file, relative, new LogFormat("graph entity text log",
                EntityTextLog.MAGIC, EntityTextLog.VERSION_WITHOUT_PROVENANCE,
                EntityTextLog.VERSION), "entity text snippets", EntityTextLog::inspect);
        }
        if (GraphStagingStore.TRIPLE_LOG.equals(name)) {
            return inspected(file, relative, new LogFormat("graph triple log",
                GraphStagingStore.MAGIC, GraphStagingStore.VERSION_WITHOUT_STATED_ON,
                GraphStagingStore.VERSION), "staged facts", GraphStagingStore::inspectTripleLog);
        }
        return inspected(file, relative, new LogFormat("graph label interner",
            LabelInterner.MAGIC, LabelInterner.VERSION, LabelInterner.VERSION), "labels",
            LabelInterner::inspect);
    }

    /** One log's read-only inspection, as its owning class declares it. */
    @FunctionalInterface
    private interface Inspector {

        /**
         * @param file The log
         * @return What the walk found
         * @throws IOException if the log cannot be read, or holds damage the node refuses
         */
        LogInspection inspect(Path file) throws IOException;
    }

    /**
     * What one staging log's reader accepts at the front of the file.
     *
     * @param name   Name of the format, for the report
     * @param magic  The magic number the file must begin with
     * @param oldest The oldest version the reader opens
     * @param newest The newest version the reader opens, the one it writes
     */
    private record LogFormat(String name, long magic, int oldest, int newest) {

        /** Reports whether the reader opens a log at this version. */
        boolean reads(int version) {
            return version >= oldest && version <= newest;
        }

        /** Phrases a header whose version the reader refuses. */
        FileCheck unsupported(Path file, String relative, int version, long size) {
            String readable = oldest == newest
                ? "version " + newest : "versions " + oldest + " to " + newest;
            return new FileCheck(file, relative, name, "v" + version, CheckStatus.UNREADABLE,
                "the header names format version " + version + ", and this build opens "
                    + readable + ": a newer build wrote it, or its version field was altered. "
                    + "The node refuses to open it, and nothing behind the header was read",
                size);
        }
    }

    /**
     * Replays a log in the erasure log's format against its record checksums.
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @param format   Name of the log
     * @param owed     What its outstanding records are, for the verdict
     * @return The verdict
     */
    private static FileCheck replayed(Path file, String relative, String format, String owed) {
        LogFormat accepted = new LogFormat(format, ErasureLog.MAGIC, ErasureLog.VERSION,
            ErasureLog.VERSION);
        long size = sizeOf(file);
        FileCheck refused = unsupportedVersion(file, relative, accepted, size);
        if (refused != null) {
            return refused;
        }
        try {
            ErasureLog.Pending pending = ErasureLog.inspect(file);
            return new FileCheck(file, relative, format, "v" + ErasureLog.VERSION,
                CheckStatus.INTACT,
                "every record matched its checksum; " + pending.count() + " " + owed
                    + " are still outstanding", size);

        } catch (IOException e) {
            // Put to an operator for the reason the class comment gives: moved aside, the log
            // is opened as empty, and every erasure still outstanding in it is forgotten.
            return new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the log could not be replayed: " + e.getMessage(), size, true);
        } catch (RuntimeException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the log could not be replayed: " + e, size);
        }
    }

    /**
     * Judges the version in a log's header before its replay is trusted to.
     *
     * <p>The replay refuses a version it does not know as it refuses damage, so without this a
     * log a newer build wrote would be reported corrupt and moved aside by a repair.</p>
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @param format   What its reader accepts
     * @param size     The file's size
     * @return The verdict on an unsupported version, or null when the replay should judge it
     */
    private static FileCheck unsupportedVersion(Path file, String relative, LogFormat format,
                                                long size) {
        byte[] head;
        try {
            head = readHead(file);
        } catch (IOException e) {
            return null;
        }
        if (head.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        int version = buffer.getInt(8);
        if (buffer.getLong(0) != format.magic() || format.reads(version)) {
            return null;
        }
        return format.unsupported(file, relative, version, size);
    }

    /**
     * Replays a staging log through its owner's read-only inspection and phrases the verdict.
     *
     * @param file      The log file
     * @param relative  Its path relative to the data directory
     * @param log       What its reader accepts at the front of the file
     * @param records   What its records are, plural, for the verdict
     * @param inspector The owning class's inspection
     * @return The verdict
     */
    private static FileCheck inspected(Path file, String relative, LogFormat log, String records,
                                       Inspector inspector) {
        Header header = header(file, relative, log);
        if (header.refused() != null) {
            return header.refused();
        }
        String format = log.name();
        String version = "v" + header.version();
        long size = header.size();
        LogInspection found;
        try {
            found = inspector.inspect(file);
        } catch (LogCorruptionException e) {
            // Refused by the node on the same terms, and nothing rebuilds the log: see the
            // class comment for why this goes to an operator rather than into the repair plan.
            return new FileCheck(file, relative, format, version, CheckStatus.CORRUPT,
                e.getMessage(), size, true);
        } catch (IOException e) {
            return new FileCheck(file, relative, format, version, CheckStatus.UNREADABLE,
                "the file could not be read: " + e, size);
        } catch (RuntimeException e) {
            return new FileCheck(file, relative, format, version, CheckStatus.UNREADABLE,
                "the log could not be replayed: " + e, size);
        }

        String rewritten = found.version() < log.newest()
            ? ". It was written at version " + found.version() + ", and the node rewrites it to "
                + "version " + log.newest() + " when it next opens it"
            : "";
        if (found.hasTornTail()) {
            return new FileCheck(file, relative, format, version, CheckStatus.UNSEALED,
                "the log ends in " + found.tornBytes() + " bytes that do not read as a record, "
                    + "with nothing readable after them: the shape a write cut short by a crash "
                    + "leaves. The node cuts them off when it next opens the log, keeping the "
                    + found.records() + " " + records + " before them, all of which matched "
                    + "their checksums" + rewritten, size);
        }
        String proven = found.records() == 0
            ? "the header is well-formed and no " + records + " follow it"
            : "all " + found.records() + " " + records + " matched their checksums, and the "
                + "log ends cleanly after the last of them";
        return new FileCheck(file, relative, format, version, CheckStatus.INTACT,
            proven + rewritten, size);
    }

    /**
     * What the front of a staging log says, or why it cannot be read past.
     *
     * @param refused The verdict when the header is the whole answer, or null to read on
     * @param version The version the header names, when it is one the reader opens
     * @param size    The file's size
     */
    private record Header(FileCheck refused, int version, long size) {
    }

    /**
     * Judges a staging log's header before its records are replayed.
     *
     * <p>Kept ahead of the replay, which refuses these cases too, because the verdict differs
     * between them and the replay's refusal does not say which it was: a version this build
     * does not read is a newer build's file, not a damaged one, and must not be moved aside.</p>
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @param log      What its reader accepts
     * @return The header's verdict, or its version when the records should be read
     */
    private static Header header(Path file, String relative, LogFormat log) {
        String format = log.name();
        byte[] head;
        long size;
        try {
            size = Files.size(file);
            head = readHead(file);
        } catch (IOException e) {
            return new Header(new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the file could not be read: " + e, -1), 0, -1);
        }
        if (head.length < HEADER_BYTES) {
            return new Header(new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the file is " + size + " bytes, too short to hold its " + HEADER_BYTES
                    + "-byte header", size, true), 0, size);
        }
        // Written by DataOutputStream, so the header is big-endian even where the records
        // that follow it are not.
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getLong(0) != log.magic()) {
            return new Header(new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the file does not begin with this log's magic number", size, true), 0, size);
        }
        int version = buffer.getInt(8);
        if (!log.reads(version)) {
            return new Header(log.unsupported(file, relative, version, size), version, size);
        }
        return new Header(null, version, size);
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
