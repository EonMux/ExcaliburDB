package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.build.BuildCancellation;
import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.file.TailIntegrity;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * The text each entity was mentioned in, with the source each piece of text came out of.
 *
 * <h2>What it is for</h2>
 * <p>An entity's document is what BM25 ranks it by and what its vector is computed from. Without
 * it the lexical signal scores every entity identically, and the budget deciding which facts a
 * model is shown is spent in arbitrary order.
 *
 * <h2>Why the source is recorded alongside</h2>
 * <p>Because these snippets are prose copied verbatim out of ingested documents, up to
 * {@value #MAX_SNIPPET_BYTES} bytes of it per mention, and an erasure has to be able to reach
 * them. While a snippet recorded only the entity and the text, withdrawing a document removed its
 * triples and left its prose behind, to be re-indexed into the lexical document index and
 * re-embedded on the next build — with the obligation reported as discharged. Provenance is what
 * makes a source-scoped or extractor-scoped erasure answerable here rather than merely claimed.
 *
 * <h2>Format</h2>
 * <pre>
 * [magic : int64][version : int32][reserved : int32]
 * repeated, big-endian:
 *   [entityId : int32][sourceType : int8][extractorVersion : int16][reserved : int8]
 *   [sourceId : int64][length : int32][text : length bytes][crc32 : int32]
 * </pre>
 *
 * <p>The checksum covers the whole record rather than only the text, so that rot in a source id
 * is caught: a source id read wrongly is an erasure aimed at the wrong document.
 *
 * <p>Version {@value #VERSION_WITHOUT_PROVENANCE} is the same framing without the four provenance
 * bytes and the source id, and with the checksum over the text alone. A file in that version is
 * rewritten to the current one when it is opened; see {@link #open}.
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent appends. Replay and erasure read the file, so they see what has been
 * flushed; both flush first.
 *
 * @author ExcaliburDB
 */
public final class EntityTextLog implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityTextLog.class);

    /** Identifies the file as one of the staging logs: ASCII {@code EXCLBTRP}. */
    public static final long MAGIC = 0x4558434C42545250L;

    /** The version written before snippets recorded where they came from. */
    public static final int VERSION_WITHOUT_PROVENANCE = 1;

    /** Format version written by this build. */
    public static final int VERSION = 2;

    /** Bytes before the first record. */
    public static final int HEADER_BYTES = 16;

    /**
     * Longest snippet recorded for one mention.
     *
     * <p>A few sentences of the text an entity appeared in is what makes it rankable. Recording
     * whole documents per mention would store the corpus once per entity in it.
     */
    public static final int MAX_SNIPPET_BYTES = 2048;

    /** Bytes of a current-version record before the text: ids, provenance and the length. */
    private static final int RECORD_HEADER_BYTES = 20;

    /** Bytes of a pre-provenance record before the text: the entity id and the length. */
    private static final int LEGACY_RECORD_HEADER_BYTES = 8;

    /** Suffix of the replacement a rewrite builds beside the log before swapping it in. */
    private static final String REWRITE_SUFFIX = ".rewriting";

    private final Path path;
    private final Object lock = new Object();

    private DataOutputStream out;
    private boolean closed;

    private EntityTextLog(Path path, DataOutputStream out) {
        this.path = path;
        this.out = out;
    }

    /** What one snippet is, as read back. */
    @FunctionalInterface
    public interface Visitor {

        /**
         * @param entityId   The entity the text mentions
         * @param snippet    The text
         * @param provenance Where the text came from, or {@link Provenance#UNKNOWN}
         */
        void visit(int entityId, String snippet, Provenance provenance);
    }

    /** Decides whether an erasure covers one snippet. */
    @FunctionalInterface
    public interface SnippetFilter {

        /**
         * @param entityId   The entity the snippet belongs to
         * @param provenance Where the snippet came from, {@link Provenance#UNKNOWN} when it was
         *                   recorded before origins were kept
         * @return true to remove the snippet
         */
        boolean erases(int entityId, Provenance provenance);
    }

    /**
     * What an erasure pass did, and what it could not decide.
     *
     * @param removed        How many snippets were removed
     * @param ofUnknownOrigin How many surviving snippets carry no recorded origin, and so cannot
     *                        be matched against a source-scoped or extractor-scoped request. A
     *                        caller discharging such a request has to treat a non-zero count as
     *                        an obligation it has not met rather than as a detail.
     */
    public record Erased(long removed, long ofUnknownOrigin) {

        /** @throws IllegalArgumentException if either count is negative */
        public Erased {
            if (removed < 0 || ofUnknownOrigin < 0) {
                throw new IllegalArgumentException("counts must not be negative");
            }
        }
    }

    /**
     * Opens the log at {@code path}, upgrading a pre-provenance file to the current format.
     *
     * <h3>What an existing deployment sees</h3>
     * <p>A file written by an earlier build is rewritten once, here, on the first open after the
     * upgrade. Every snippet in it is preserved, so nothing an entity is ranked by is lost, and
     * each is given {@link Provenance#UNKNOWN}, because the origin genuinely was not recorded and
     * inventing one would aim a later erasure at the wrong document. Those snippets are therefore
     * unreachable by a source-scoped erasure for as long as they remain, which is why
     * {@link Erased#ofUnknownOrigin} exists: the gap is reported rather than papered over.
     *
     * <h3>What a crash mid-append leaves behind</h3>
     * <p>A partial record at the end is cut off here, before anything is appended after it; see
     * {@link #trimTornTail}. Leaving it would wedge the log permanently, because the very next
     * append puts an intact record behind the damage and that is indistinguishable from
     * corruption in the middle of a log - which {@link #forEach} and {@link #erase} both refuse,
     * correctly and for ever.
     *
     * @param path The file to read and append to; created, with its parent, if absent
     * @return The log
     * @throws IOException if the file is not one of these logs, is damaged other than at the tail,
     *                     or cannot be upgraded, trimmed or opened
     * @throws NullPointerException if {@code path} is null
     */
    public static EntityTextLog open(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(path) && Files.size(path) >= HEADER_BYTES
            && versionOf(path) == VERSION_WITHOUT_PROVENANCE) {
            upgrade(path);
        }
        trimTornTail(path);
        return new EntityTextLog(path, openForAppend(path));
    }

    /**
     * Cuts a write that was interrupted off the end, before anything is appended after it.
     *
     * <h3>Why the next append is what does the damage</h3>
     * <p>A process killed mid-append leaves a partial record, and the records before it are
     * intact and are what every artifact built from this log refers to. Left in place, though,
     * the tear is not stable: the next run opens the log in append mode and writes after the
     * partial bytes, so the damage now has a perfectly good record behind it. That is the exact
     * shape of corruption in the middle of a log, which {@link TailIntegrity} refuses - so from
     * that append onwards every replay and every erasure throws, the graph can never be built
     * again and the erasure obligations aimed at this log can never be discharged. Trimming here
     * is what keeps a crash costing the one snippet that was in flight.
     *
     * <p>Refused, rather than trimmed, when a readable record already follows the break. Those
     * records were written and acknowledged after a write that failed for a reason the file
     * outlived, and truncating to the damage would destroy prose nobody asked to erase.
     *
     * @param path The log
     * @throws IOException if the file cannot be read, holds damage that is not a torn tail, or
     *                     cannot be truncated
     */
    private static void trimTornTail(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) < HEADER_BYTES) {
            return;
        }
        byte[] all = Files.readAllBytes(path);
        LogInspection walked = walk(path, all);
        if (!walked.hasTornTail()) {
            return;
        }

        LOGGER.warn("truncating {} from {} to {} bytes after {} snippet(s): a write was cut "
            + "short, and appending after it would leave every later snippet behind damage that "
            + "replay stops at", path, all.length, walked.intactBytes(), walked.records());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(walked.intactBytes());
        }
    }

    /**
     * Reads what a log holds without opening it.
     *
     * <h3>Why this is not {@link #open}</h3>
     * <p>Opening creates the file and its directory when they are missing, rewrites a
     * pre-provenance log in the current version, cuts a torn tail off the end and takes an append
     * handle - the right things for a node taking the log over, and writes an offline checker
     * must not make. This reads the file once and walks it with the framing the open walks it
     * with: the current version's through the same cursor {@link #forEach} and {@link #erase}
     * read, and a pre-provenance log's through the same walk its upgrade copies records with.
     * It writes nothing, and a pre-provenance log is reported at the version it is, since that is
     * what is on disk.</p>
     *
     * <h3>What it refuses</h3>
     * <p>Everything {@link #open} refuses, on the same terms: a file that is not one of these
     * logs, a version this build does not read, and a record that fails its checksum with an
     * intact record somewhere after it - the last as a {@link LogCorruptionException}, so that
     * damage can be told apart from a file that merely could not be read. A record that fails its
     * checksum with nothing readable after it is reported rather than refused, because the open
     * treats it as a write cut short and trims it; see {@link LogInspection#hasTornTail()}.</p>
     *
     * @param path The log's file
     * @return How many snippets matched their checksums, and where the file stops reading cleanly
     * @throws IOException if the file is absent, shorter than its header, not a log of a version
     *                     this build reads, or cannot be read
     * @throws LogCorruptionException if damage has an intact snippet behind it
     * @throws NullPointerException if {@code path} is null
     */
    public static LogInspection inspect(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        byte[] all = Files.readAllBytes(path);
        if (versionOf(all, path) == VERSION_WITHOUT_PROVENANCE) {
            return walkLegacy(path, all, (entityId, text) -> {
                // Counted only; nothing is carried anywhere.
            });
        }
        return walk(path, all);
    }

    /**
     * Walks every current-version record, refusing damage that is not a tail.
     *
     * <p>The judgement the open's trim and {@link #inspect} share. Nothing here decodes a
     * snippet: all either needs is where the readable records end, and whether what follows
     * them is a write cut short or damage with acknowledged records behind it.</p>
     *
     * @param path The log, named in any failure
     * @param all  The whole log as read
     * @return What the walk found
     * @throws LogCorruptionException if damage has an intact record behind it
     */
    private static LogInspection walk(Path path, byte[] all) throws LogCorruptionException {
        Cursor cursor = new Cursor(all);
        while (cursor.next()) {
            // Walked only for where the readable records end.
        }
        cursor.requireTornTail(path);
        return new LogInspection(VERSION, cursor.read, cursor.position, all.length);
    }

    /** Receives each record of a pre-provenance log, in order. */
    @FunctionalInterface
    private interface LegacyVisitor {

        /**
         * @param entityId The entity the text mentions
         * @param text     The snippet's bytes, a copy the visitor may keep
         * @throws IOException if the visitor's own write fails
         */
        void visit(int entityId, byte[] text) throws IOException;
    }

    /**
     * Walks every record of a pre-provenance log, refusing damage that is not a tail.
     *
     * <p>Shared by the upgrade, which copies each record across as it is reached, and by
     * {@link #inspect}, which counts them - so the two cannot disagree about which bytes of an
     * old log are a snippet and which a torn tail the upgrade leaves behind.</p>
     *
     * @param path    The log, named in any failure
     * @param all     The whole log as read
     * @param visitor Receives each record
     * @return What the walk found
     * @throws IOException if the visitor fails
     * @throws LogCorruptionException if damage has an intact record behind it
     */
    private static LogInspection walkLegacy(Path path, byte[] all, LegacyVisitor visitor)
            throws IOException {
        int position = HEADER_BYTES;
        long read = 0;
        while (legacyRecordStartsAt(all, position)) {
            ByteBuffer buffer = ByteBuffer.wrap(all, position, all.length - position);
            int entityId = buffer.getInt();
            int length = buffer.getInt();
            int text = position + LEGACY_RECORD_HEADER_BYTES;
            visitor.visit(entityId, Arrays.copyOfRange(all, text, text + length));
            position = text + length + Integer.BYTES;
            read++;
        }
        TailIntegrity.requireTornTail(path, "entity text log", all, position,
            EntityTextLog::legacyRecordStartsAt);
        return new LogInspection(VERSION_WITHOUT_PROVENANCE, read, position, all.length);
    }

    /**
     * Records a piece of text an entity was mentioned in.
     *
     * @param entityId   The entity
     * @param snippet    Text it appeared in; truncated to {@link #MAX_SNIPPET_BYTES}
     * @param provenance Where the text came from; {@link Provenance#UNKNOWN} makes the snippet
     *                   unreachable by any source-scoped erasure, so pass the real origin
     * @throws IOException if the record cannot be written
     * @throws IllegalArgumentException if {@code entityId} is negative
     * @throws NullPointerException if {@code provenance} is null
     */
    public void append(int entityId, String snippet, Provenance provenance) throws IOException {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must not be negative: " + entityId);
        }
        Objects.requireNonNull(provenance, "provenance must not be null");
        if (snippet == null || snippet.isBlank()) {
            return;
        }

        byte[] text = snippet.getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_SNIPPET_BYTES) {
            text = truncateUtf8(text, MAX_SNIPPET_BYTES);
        }
        byte[] record = encode(entityId, text, provenance);

        synchronized (lock) {
            requireOpen();
            out.write(record);
        }
    }

    /**
     * Replays every snippet recorded so far.
     *
     * <p>Stops at the first record that does not read cleanly, which is a write cut short by a
     * crash. Damage with readable records after it is reported rather than skipped past, because
     * silently dropping the rest would make an erasure that then reports success a lie.
     *
     * @param visitor Receives each snippet
     * @return How many snippets were replayed
     * @throws IOException if the log cannot be read, or holds damage that is not a torn tail
     */
    public long forEach(Visitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        flush();

        byte[] all = contents();
        if (all.length < HEADER_BYTES) {
            return 0;
        }
        Cursor cursor = new Cursor(all);
        // The replay both Lucene indexes and every entity vector are built from, so this is
        // where a build spends its time and where it is asked to stop. Nothing is published
        // until every phase has finished, so stopping here costs the build and no data.
        BuildCancellation.checkpoint();
        while (cursor.next()) {
            BuildCancellation.checkpoint(cursor.read);
            visitor.visit(cursor.entityId, cursor.snippet(), cursor.provenance);
        }
        cursor.requireTornTail(path);
        cursor.warnAboutTornTail(path);
        return cursor.read;
    }

    /**
     * Removes every snippet an erasure covers.
     *
     * <p>The log is rewritten only when something is actually removed. An erasure that matches
     * nothing must not churn the file, because a request that can never be satisfied would
     * otherwise rewrite the whole log on every compaction for as long as it stays outstanding.
     *
     * <p>A damaged record with readable snippets after it aborts the rewrite with the log exactly
     * as it was. Truncating there would destroy prose nobody asked to erase, and carrying on would
     * report an erasure that had not reached the records past the damage.
     *
     * @param filter Decides which snippets the erasure covers
     * @return What was removed, and how much could not be judged for want of a recorded origin
     * @throws IOException if the log cannot be read, is damaged other than at its tail, or the
     *                     replacement cannot be written
     * @throws NullPointerException if {@code filter} is null
     */
    public Erased erase(SnippetFilter filter) throws IOException {
        Objects.requireNonNull(filter, "filter must not be null");

        synchronized (lock) {
            requireOpen();
            out.flush();

            byte[] all = contents();
            if (all.length < HEADER_BYTES) {
                return new Erased(0, 0);
            }

            Cursor cursor = new Cursor(all);
            List<int[]> kept = new ArrayList<>();
            long removed = 0;
            long unknown = 0;
            while (cursor.next()) {
                if (filter.erases(cursor.entityId, cursor.provenance)) {
                    removed++;
                    continue;
                }
                if (cursor.provenance.sourceType() == SourceType.UNKNOWN) {
                    unknown++;
                }
                kept.add(new int[] {cursor.start, cursor.end});
            }
            // Checked before anything is written, so damage leaves the log alone entirely.
            cursor.requireTornTail(path);
            cursor.warnAboutTornTail(path);

            if (removed > 0) {
                rewrite(all, kept);
                LOGGER.info("erased {} snippet(s) from {}; {} remain", removed, path, kept.size());
            }
            return new Erased(removed, unknown);
        }
    }

    /**
     * Forces everything appended so far to the file.
     *
     * @throws IOException if the write fails
     */
    public void flush() throws IOException {
        synchronized (lock) {
            if (!closed) {
                out.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            out.flush();
            out.close();
        }
    }

    /** The file this log lives in. */
    public Path path() {
        return path;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("entity text log is closed: " + path);
        }
    }

    private byte[] contents() throws IOException {
        return Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
    }

    /**
     * Writes the surviving records over the log, durably.
     *
     * <p>Forced before the swap because the point of the rewrite is that the erased prose is gone.
     * A rename that reached the disk ahead of its own contents would leave the erased text
     * readable on the platter after the erasure was reported discharged.
     *
     * @param all  The log as read
     * @param kept Start and end offsets of the records to carry across, in order
     * @throws IOException if the replacement cannot be written or swapped in
     */
    private void rewrite(byte[] all, List<int[]> kept) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + REWRITE_SUFFIX);
        Files.deleteIfExists(temp);
        out.close();
        try {
            try (DataOutputStream rewritten = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)))) {
                writeHeader(rewritten);
                for (int[] record : kept) {
                    rewritten.write(all, record[0], record[1] - record[0]);
                }
            }
            Durability.DEVICE.replaceDurably(temp, path);
        } finally {
            Files.deleteIfExists(temp);
            out = openForAppend(path);
        }
    }

    /**
     * Rewrites a pre-provenance log in the current format, once.
     *
     * @param path The log
     * @throws IOException if it cannot be read or rewritten
     */
    private static void upgrade(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        Path temp = path.resolveSibling(path.getFileName() + REWRITE_SUFFIX);
        Files.deleteIfExists(temp);
        long upgraded;

        try (DataOutputStream rewritten = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)))) {
            writeHeader(rewritten);
            // Damage that is not a tail is refused by the walk after the copy, before anything
            // is swapped in, so the old log is left exactly as it was.
            upgraded = walkLegacy(path, all, (entityId, text) ->
                rewritten.write(encode(entityId, text, Provenance.UNKNOWN))).records();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        Durability.DEVICE.replaceDurably(temp, path);
        LOGGER.warn("upgraded {} to entity text log version {}: {} snippet(s) carried across with "
            + "no recorded origin, so a source-scoped erasure cannot reach them and will report "
            + "them as an unmet obligation", path, VERSION, upgraded);
    }

    private static void writeHeader(DataOutputStream out) throws IOException {
        out.writeLong(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(0);
    }

    private static byte[] encode(int entityId, byte[] text, Provenance provenance) {
        ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_BYTES + text.length + Integer.BYTES);
        record.putInt(entityId);
        record.put(provenance.sourceType().toByte());
        record.putShort(provenance.extractorVersionShort());
        record.put((byte) 0);
        record.putLong(provenance.sourceId());
        record.putInt(text.length);
        record.put(text);

        CRC32 crc = new CRC32();
        crc.update(record.array(), 0, RECORD_HEADER_BYTES + text.length);
        record.putInt((int) crc.getValue());
        return record.array();
    }

    private static int versionOf(Path path) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        int read;
        try (var in = Files.newInputStream(path)) {
            read = in.readNBytes(header, 0, HEADER_BYTES);
        }
        return versionOf(Arrays.copyOf(header, read), path);
    }

    /**
     * Reads the magic and the version from the front of a log.
     *
     * @param head At least the log's first {@value #HEADER_BYTES} bytes
     * @param path The log, named in any failure
     * @return The version, one this build reads
     * @throws IOException if the bytes are too few for a header, are not one of these logs, or
     *                     name a version this build does not read
     */
    private static int versionOf(byte[] head, Path path) throws IOException {
        if (head.length < HEADER_BYTES) {
            throw new IOException("entity text log " + path + " is shorter than its header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(head);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not an entity text log (magic 0x" + Long.toHexString(magic)
                + "): " + path);
        }
        int version = buffer.getInt();
        if (version != VERSION && version != VERSION_WITHOUT_PROVENANCE) {
            throw new IOException("unsupported entity text log version " + version + ": " + path);
        }
        return version;
    }

    private static DataOutputStream openForAppend(Path path) throws IOException {
        boolean fresh = !Files.exists(path) || Files.size(path) < HEADER_BYTES;
        OutputStream sink = Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sink));
        if (fresh) {
            writeHeader(out);
            out.flush();
        }
        return out;
    }

    /**
     * Whether a complete, checksum-clean current-version record begins at {@code offset}.
     *
     * @param contents The whole file
     * @param offset   Where to look
     * @return true when a record starts exactly there
     */
    private static boolean recordStartsAt(byte[] contents, int offset) {
        if (offset < 0 || offset + RECORD_HEADER_BYTES + Integer.BYTES > contents.length) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(contents, offset, contents.length - offset);
        if (buffer.getInt() < 0) {
            return false;
        }
        buffer.position(buffer.position() + 12);
        int length = buffer.getInt();
        if (length <= 0 || length > MAX_SNIPPET_BYTES
            || buffer.remaining() < length + Integer.BYTES) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(contents, offset, RECORD_HEADER_BYTES + length);
        buffer.position(buffer.position() + length);
        return buffer.getInt() == (int) crc.getValue();
    }

    /** As {@link #recordStartsAt}, for the pre-provenance framing. */
    private static boolean legacyRecordStartsAt(byte[] contents, int offset) {
        if (offset < 0 || offset + LEGACY_RECORD_HEADER_BYTES + Integer.BYTES > contents.length) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(contents, offset, contents.length - offset);
        if (buffer.getInt() < 0) {
            return false;
        }
        int length = buffer.getInt();
        if (length <= 0 || length > MAX_SNIPPET_BYTES
            || buffer.remaining() < length + Integer.BYTES) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(contents, offset + LEGACY_RECORD_HEADER_BYTES, length);
        buffer.position(buffer.position() + length);
        return buffer.getInt() == (int) crc.getValue();
    }

    /**
     * Truncates UTF-8 bytes without splitting a character.
     *
     * @param bytes    The encoded text
     * @param maxBytes The limit
     * @return A prefix that is still valid UTF-8
     */
    private static byte[] truncateUtf8(byte[] bytes, int maxBytes) {
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        byte[] truncated = new byte[end];
        System.arraycopy(bytes, 0, truncated, 0, end);
        return truncated;
    }

    /**
     * Walks the records of a log already in memory.
     *
     * <p>One place that knows the framing, shared by the replay and the rewrite so the two cannot
     * disagree about where a record ends — and a disagreement between them is an erasure that
     * misses a snippet the replay goes on to publish.
     */
    private static final class Cursor {

        private final byte[] all;
        private int position = HEADER_BYTES;
        private long read;
        private int start;
        private int end;
        private int entityId;
        private Provenance provenance = Provenance.UNKNOWN;

        Cursor(byte[] all) {
            this.all = all;
        }

        boolean next() {
            if (!recordStartsAt(all, position)) {
                return false;
            }
            start = position;
            ByteBuffer buffer = ByteBuffer.wrap(all, position, all.length - position);
            entityId = buffer.getInt();
            SourceType sourceType = SourceType.fromByte(buffer.get());
            short extractorVersion = buffer.getShort();
            buffer.get();
            long sourceId = buffer.getLong();
            int length = buffer.getInt();
            provenance = new Provenance(sourceType, sourceId, 0, extractorVersion);
            position = start + RECORD_HEADER_BYTES + length + Integer.BYTES;
            end = position;
            read++;
            return true;
        }

        String snippet() {
            return new String(all, start + RECORD_HEADER_BYTES,
                end - start - RECORD_HEADER_BYTES - Integer.BYTES, StandardCharsets.UTF_8);
        }

        void requireTornTail(Path path) throws LogCorruptionException {
            TailIntegrity.requireTornTail(path, "entity text log", all, position,
                EntityTextLog::recordStartsAt);
        }

        /**
         * Says so when bytes were left unread, rather than passing over them.
         *
         * <p>Reached only once {@link #requireTornTail} has established that they are a write cut
         * short. Nothing can be recovered from them, but an operator seeing this repeatedly is
         * seeing a process that keeps dying mid-append, and the only way to notice is to be told.
         *
         * @param path The log, for the message
         */
        void warnAboutTornTail(Path path) {
            if (position < all.length) {
                LOGGER.warn("entity text log {} ends in {} unreadable byte(s) after {} snippet(s); "
                    + "a write was cut short, and the next rewrite of this log will drop them",
                    path, all.length - position, read);
            }
        }
    }

    @Override
    public String toString() {
        return "EntityTextLog[" + path + "]";
    }
}
