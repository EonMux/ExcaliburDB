package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogHeader;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.staging.EntityTextLog;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.LabelInterner;
import com.eonmux.excalibur.graph.staging.ReferencedLabels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the append-only logs a tenant's {@code graph-staging} directory holds.
 *
 * <h2>Every record is read, by the node's own code</h2>
 * <p>Each of these logs has a read-only inspection beside the code that writes it:
 * {@link ErasureLog#inspect} for {@code erasure.log} and {@code withdrawals.log}, which share a
 * format; {@link GraphStagingStore#inspectLabels} for the two label interners;
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
 * repair plan leaves it alone. Those bytes may be whole records that fail their checksums as well
 * as a partial one: a crash between an append and the force that would have made it durable can
 * leave any of the records that force was covering unreadable, and none of them was ever
 * acknowledged. And damage the node refuses - a record that fails its checksum with an intact
 * record after it, other than in a label interner as described below - is
 * {@link CheckStatus#CORRUPT}, and put to an operator rather than into the repair plan: every one
 * of these logs is the only copy of what it holds, and the node opens a missing one as empty, so
 * moving a damaged one aside would lose outstanding erasures, reassign entity ids or empty the next
 * graph build rather than repair anything.</p>
 *
 * <h2>Damaged labels</h2>
 * <p>An interner's damaged record is a tail the node cuts off, together with every record after
 * it, intact or not, only while no id at or past the damage is referred to: the node forces a
 * label before handing out its id, and new labels that arrive together share one force, so a
 * record that force never finished - whether the last one or one whose pages reached the device
 * after those of labels behind it - held an id nobody was given, and so did every record after
 * it. One that rotted after being forced may hold an id, or sit before ids, that the staged facts,
 * the entity text, the graph published beside the staging directory or an outstanding erasure
 * still names. The node refuses that rather than give those ids to new labels, so it is
 * {@link CheckStatus#CORRUPT} here, asked of the same holders the node's tenant names; the
 * {@link CheckStatus#UNSEALED} verdict says how many intact labels the open would cut off with the
 * damage. A published dictionary that fails its checksum, or is missing from its graph, cannot
 * say which ids it names, and the graph may be the only holder of some of them, so the node
 * refuses the cut and this reports it {@link CheckStatus#CORRUPT} as well - naming the dictionary
 * - while an interner with nothing to cut is judged without asking it.</p>
 *
 * <h2>Erasures naming an entity nothing was given</h2>
 * <p>An outstanding entity-scoped erasure records an id and nothing else. The node refuses one for
 * an id nothing in the namespace holds, and discharges any such request already in the log when it
 * next opens the namespace, since the only entity it could ever erase is whichever is given that
 * id next. The check names how many outstanding requests are of that kind, judged as the node
 * judges them: an id is held when the entity labels hold it, as the node would open them, or when
 * the graph published beside the staging directory, or one a publish set aside, names it. They do
 * not change the verdict - every record matched its checksum, and the node's open deals with
 * them - but a log that will lose requests when the node next opens it is worth being told
 * about.</p>
 *
 * <h2>A file shorter than its header</h2>
 * <p>Each log is created by writing its header, so a file shorter than one whose bytes begin the
 * header the node creates it with is that write cut short: {@link CheckStatus#UNSEALED}, since the
 * node starts the log afresh. Any other file that short is not one the node wrote, and it refuses
 * to write after it, so it is {@link CheckStatus#CORRUPT}.</p>
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

    /**
     * Name of the marker that withholds the graph until an interrupted erasure is applied.
     *
     * <p>Stated here for the reason {@link #ERASURE_LOG} is.</p>
     */
    public static final String ERASURE_INTERRUPTED_MARKER = "erasure.interrupted";

    /** Name the marker goes by in a report. */
    public static final String MARKER_FORMAT = "erasure interrupted marker";

    /** Name {@link GraphStagingStore#UNPUBLISHED_MARKER} goes by in a report. */
    public static final String UNPUBLISHED_MARKER_FORMAT = "unpublished changes marker";

    private GraphStagingCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether a file is a rewrite of a staging log that did not finish.
     *
     * <p>The node builds each rewrite beside the log it replaces and renames it over that log,
     * so one that is still here was never published and the log it was replacing is intact.
     * Being a copy of that log less what was being removed, it can hold what an erasure has
     * since removed; the store deletes it when it next opens, and nothing should move it
     * anywhere that would keep it.</p>
     *
     * @param relative The file's path relative to the data directory, using "/" separators
     * @return true for one of {@link GraphStagingStore#UNFINISHED_REWRITES} directly in a
     *         graph staging directory
     */
    public static boolean isUnfinishedRewrite(String relative) {
        int slash = relative.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String parent = relative.substring(0, slash);
        String directory = parent.substring(parent.lastIndexOf('/') + 1);
        return directory.endsWith(DIRECTORY_SUFFIX)
            && GraphStagingStore.UNFINISHED_REWRITES.contains(relative.substring(slash + 1));
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
     * Checks the marker an interrupted erasure leaves.
     *
     * <p>The node writes it empty and reads only whether it is there, so an empty one is exactly
     * what was written. One that holds bytes was not written by the node; they are never read,
     * and are reported unchecked rather than judged.</p>
     *
     * @param file     The marker
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck marker(Path file, String relative) {
        return emptyMarker(file, relative, MARKER_FORMAT,
            "the tenant's graph is withheld until the erasures it still owes have been applied "
                + "to it, which the node does on its next open or compaction before it serves "
                + "the graph, removing the marker once they are");
    }

    /**
     * Checks the marker that says staging holds changes the served graph does not have.
     *
     * <p>Written empty and read only for whether it is there, like the interrupted-erasure
     * marker, and judged the same way.</p>
     *
     * @param file     The marker
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck unpublishedMarker(Path file, String relative) {
        return emptyMarker(file, relative, UNPUBLISHED_MARKER_FORMAT,
            "the tenant's served graph is behind its staged facts, which the node's automatic "
                + "build or POST /v1/graph/build publishes, removing the marker once it has");
    }

    /**
     * Judges a marker whose presence is its whole content.
     *
     * @param file     The marker
     * @param relative Its path relative to the data directory
     * @param format   What the marker is called in a report
     * @param meaning  What its presence says
     * @return The verdict
     */
    private static FileCheck emptyMarker(Path file, String relative, String format,
                                         String meaning) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the marker could not be sized: " + e, -1);
        }
        if (size == 0) {
            return new FileCheck(file, relative, format, null, CheckStatus.INTACT,
                "empty, as written: its presence is its whole content, and says " + meaning,
                size);
        }
        return new FileCheck(file, relative, format, null, CheckStatus.UNCHECKED,
            "holds " + size + " bytes, which the node never writes and never reads; its presence "
                + "still says " + meaning, size);
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
        ReferencedLabels holders = labelHolders(file);
        return inspected(file, relative, new LogFormat("graph label interner",
            LabelInterner.MAGIC, LabelInterner.VERSION, LabelInterner.VERSION), "labels",
            interner -> GraphStagingStore.inspectLabels(interner, holders));
    }

    /**
     * What outside a staging directory's own logs names its label ids, as the node names it.
     *
     * <p>The node's tenant passes the graph published beside the staging directory and the two
     * logs of requests still owed inside it; the staged facts and entity text the inspection
     * reads itself. Named the same way here so that an interner whose damaged last record holds
     * an id one of them refers to is called corrupt, as the node refuses it, rather than
     * unsealed.</p>
     *
     * @param interner One of the interners in a staging directory
     * @return The holders
     */
    private static ReferencedLabels labelHolders(Path interner) {
        Path staging = interner.toAbsolutePath().getParent();
        ReferencedLabels owed = ReferencedLabels.all(
            ReferencedLabels.owedIn(staging.resolve(ERASURE_LOG)),
            ReferencedLabels.owedIn(staging.resolve(WITHDRAWAL_LOG)));
        Path graph = graphBeside(staging);
        return graph == null ? owed : ReferencedLabels.all(ReferencedLabels.publishedIn(graph), owed);
    }

    /**
     * The graph directory a staging directory's graph is published to.
     *
     * @param staging The staging directory
     * @return The graph directory, which need not exist, or null when the staging directory is
     *         not named after one
     */
    private static Path graphBeside(Path staging) {
        String name = staging.getFileName().toString();
        if (!name.endsWith(DIRECTORY_SUFFIX) || name.length() == DIRECTORY_SUFFIX.length()) {
            return null;
        }
        return staging.resolveSibling(
            name.substring(0, name.length() - DIRECTORY_SUFFIX.length()));
    }

    /**
     * Says how many outstanding entity-scoped requests in a log name an id nothing holds.
     *
     * <p>See the class comment. Judged against the entity labels as the node would open them and
     * the graph published beside the staging directory; when the labels cannot be judged, that is
     * said instead, since which requests the node would discharge then depends on what an
     * operator does about them.</p>
     *
     * @param log A log in the erasure log's format, already replayed without error
     * @return A clause to append to the verdict, or an empty string when there is nothing to say
     * @throws IOException if the log or a published dictionary cannot be read
     */
    private static String unassignedEntities(Path log) throws IOException {
        List<Long> owed = ErasureLog.entitiesOwed(log);
        if (owed.isEmpty()) {
            return "";
        }
        long unassigned;
        try {
            unassigned = unassignedAmong(owed, log.toAbsolutePath().getParent());
        } catch (IOException | RuntimeException e) {
            return "; whether the " + owed.size() + " entity id(s) they name were ever handed out "
                + "cannot be judged, because the entity labels or the published graph beside the "
                + "log cannot be read: " + e.getMessage();
        }
        if (unassigned == 0) {
            return "";
        }
        return "; " + unassigned + " of them name an entity id nothing in this namespace holds - "
            + "no staged label, and no published graph - and the node discharges those when it "
            + "next opens the namespace rather than let them erase whichever entity is given the "
            + "id next";
    }

    /**
     * Counts the ids that neither the entity labels nor a published graph hold.
     *
     * @param owed    The ids outstanding requests name
     * @param staging The staging directory the log is in
     * @return How many are held by nothing
     * @throws IOException if the labels or a published dictionary cannot be read, or the labels
     *                     hold damage the node refuses
     */
    private static long unassignedAmong(List<Long> owed, Path staging)
            throws IOException {
        Path interner = staging.resolve(GraphStagingStore.ENTITY_LABELS);
        long assigned = Files.exists(interner)
            ? GraphStagingStore.inspectLabels(interner, labelHolders(interner)).records()
            : 0;
        Path graph = graphBeside(staging);
        long unassigned = 0;
        for (long entityId : owed) {
            boolean held = entityId < assigned
                || (graph != null && ReferencedLabels.publishedEntity(graph, entityId));
            if (!held) {
                unassigned++;
            }
        }
        return unassigned;
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
            ErasureLog.Inspection inspection = ErasureLog.inspectLog(file);
            LogInspection log = inspection.log();
            String outstanding = inspection.pending().count() + " " + owed
                + " are still outstanding" + unassignedEntities(file);
            if (log.hasTornTail()) {
                return new FileCheck(file, relative, format, "v" + ErasureLog.VERSION,
                    CheckStatus.UNSEALED,
                    "the log ends in " + log.tornBytes() + " bytes that do not read as a "
                        + "record, with nothing readable after them: the shape a write cut "
                        + "short by a crash leaves. The node cuts them off when it next opens "
                        + "the log, keeping the " + log.records() + " records before them, all "
                        + "of which matched their checksums; " + outstanding, size);
            }
            return new FileCheck(file, relative, format, "v" + ErasureLog.VERSION,
                CheckStatus.INTACT,
                "every record matched its checksum; " + outstanding, size);

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
        if (found.hasTornTail() && found.intactBytes() == 0) {
            return new FileCheck(file, relative, format, version, CheckStatus.UNSEALED,
                "the file is " + found.fileBytes() + " bytes, the start of the header the node "
                    + "creates the log with and nothing more: its creation was cut short by a "
                    + "crash. It holds no " + records + ", and the node starts it afresh when it "
                    + "next opens it", size);
        }
        if (found.hasTornTail() && found.intactRecordsCut() > 0) {
            return new FileCheck(file, relative, format, version, CheckStatus.UNSEALED,
                "the record after the first " + found.records() + " " + records + " fails its "
                    + "checksum, and the " + found.tornBytes() + " bytes from it to the end hold "
                    + found.intactRecordsCut() + " intact " + records + " besides: the shape one "
                    + "force a crash interrupted leaves when its pages reach the device out of "
                    + "order. Nothing refers to an id at or past the damage, so none of them was "
                    + "handed out, and the node cuts off all " + found.tornBytes() + " bytes, "
                    + "including those " + found.intactRecordsCut() + " " + records + ", when it "
                    + "next opens the log, keeping the " + found.records() + " before them"
                    + rewritten, size);
        }
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
            if (LogHeader.isTornCreation(head, log.magic(), log.newest())) {
                // The start of the header the node creates the log with: judged by the log's
                // inspection, which reports it as a creation the node starts afresh.
                return new Header(null, log.newest(), size);
            }
            return new Header(new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the file is " + size + " bytes, too short to hold its " + HEADER_BYTES
                    + "-byte header, and they are not the start of the header the node creates "
                    + "it with. The node refuses to open it rather than write after bytes it "
                    + "cannot account for", size, true), 0, size);
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
