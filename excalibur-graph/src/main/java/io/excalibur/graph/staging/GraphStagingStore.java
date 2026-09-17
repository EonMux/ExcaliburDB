package io.excalibur.graph.staging;

import io.excalibur.graph.build.BuildCancellation;
import io.excalibur.graph.file.Durability;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.RelationSelector;
import io.excalibur.graph.temporal.TemporalFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Where extracted facts accumulate between graph builds.
 *
 * <h2>Why staging exists at all</h2>
 * <p>The edge store is a CSR: sorted, columnar and immutable, which is what makes traversal a
 * bounded walk rather than a search. Nothing can be appended to it. Ingestion, meanwhile, produces
 * facts one document at a time, forever. This store is the join between the two - a durable,
 * append-only record of every fact ingestion has extracted, from which a whole edge store is built
 * whenever one is wanted.</p>
 *
 * <p>It owns the label-to-id assignment as well, because ids have to outlive any single build: see
 * {@link LabelInterner}.</p>
 *
 * <h2>Format</h2>
 * <p>Two interners beside a fixed-width log:</p>
 * <pre>
 * entities.labels    LabelInterner
 * relations.labels   LabelInterner
 * triples.log        [magic : int64][version : int32][reserved : int32]
 *                    repeated, little-endian, {@value #RECORD_BYTES} bytes each:
 *                      [subject : int32][relation : int32][object : int32]
 *                      [validFrom : int32][validTo : int32]
 *                      [sourceType : int8][confidence : int8][extractorVersion : int16]
 *                      [sourceId : int64][crc32 : int32]
 * </pre>
 *
 * <p>Fixed-width records, so a crash mid-append leaves a file whose length is not a whole number
 * of records. That partial record is cut off when the log is opened, which is the same tolerance
 * the interners apply and for the same reason: a crash during ingestion must cost the facts that
 * were in flight, not the graph.</p>
 *
 * <p>A checksum that fails over a whole record is therefore not a torn tail - every record the
 * scan reaches was written whole - and both the replay and the erasure rewrite refuse the log
 * rather than stopping at the damage. See {@link #forEach}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent appends, which is what ingestion does - one virtual thread per document.
 * Replay is not concurrent with appending: {@link #forEach} reads the file, so a caller that wants
 * a complete view calls {@link #flush()} first, and a build takes whatever was flushed.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphStagingStore implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphStagingStore.class);

    /** Identifies the file as an ExcaliburDB triple log: ASCII {@code EXCLBTRP}. */
    public static final long MAGIC = 0x4558434C42545250L;

    /** Format version written by this build. */
    public static final int VERSION = 1;

    /** Bytes before the first record. */
    public static final int HEADER_BYTES = 16;

    /**
     * Bytes per staged triple.
     *
     * <p>Three ids, two interval bounds, four provenance fields and a checksum: 4+4+4 + 4+4 +
     * 1+1+2+8 + 4. Fixed width, so a torn tail is a file whose length is not a whole number of
     * records.
     */
    public static final int RECORD_BYTES = 36;

    /** Bytes of a record the checksum covers - everything but the checksum itself. */
    private static final int RECORD_PAYLOAD_BYTES = RECORD_BYTES - Integer.BYTES;

    /** File holding the entity label assignments. */
    public static final String ENTITY_LABELS = "entities.labels";

    /** File holding the relation label assignments. */
    public static final String RELATION_LABELS = "relations.labels";

    /** File holding the staged facts. */
    public static final String TRIPLE_LOG = "triples.log";

    /** File holding the text each entity was mentioned in. */
    public static final String ENTITY_TEXT_LOG = "entity_text.log";

    /** Longest snippet recorded for one mention; see {@link EntityTextLog#MAX_SNIPPET_BYTES}. */
    public static final int MAX_SNIPPET_BYTES = EntityTextLog.MAX_SNIPPET_BYTES;

    /** Suffix of the partially rewritten log an erasure builds before swapping it in. */
    private static final String ERASING_SUFFIX = ".erasing";

    private final Path directory;
    private final Path logPath;
    private final LabelInterner entities;
    private final LabelInterner relations;
    private final EntityTextLog text;
    private final Object appendLock = new Object();

    private DataOutputStream out;
    private long staged;
    private volatile boolean closed;

    private GraphStagingStore(Path directory, Path logPath, LabelInterner entities,
                              LabelInterner relations, EntityTextLog text, DataOutputStream out,
                              long staged) {
        this.directory = directory;
        this.logPath = logPath;
        this.entities = entities;
        this.relations = relations;
        this.text = text;
        this.out = out;
        this.staged = staged;
    }

    /**
     * Opens the staging store in {@code directory}, creating it if it is not there.
     *
     * @param directory Where the interners and the log live
     * @return The store
     * @throws IOException if the directory or its files cannot be opened
     */
    public static GraphStagingStore open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Files.createDirectories(directory);

        LabelInterner entities = LabelInterner.open(directory.resolve(ENTITY_LABELS));
        LabelInterner relations = null;
        DataOutputStream out = null;
        EntityTextLog text = null;
        try {
            relations = LabelInterner.open(directory.resolve(RELATION_LABELS));

            // Truncated to a whole number of records before anything is appended. A process
            // killed mid-append leaves a partial record, and appending after it would put every
            // later fact behind a record replay stops at - present on disk and invisible to every
            // build from then on.
            Path logPath = directory.resolve(TRIPLE_LOG);
            long existing = truncateToWholeRecords(logPath);
            out = openLog(logPath);

            text = EntityTextLog.open(directory.resolve(ENTITY_TEXT_LOG));

            LOGGER.info("graph staging at {}: {} facts, {} entities, {} relations",
                directory, existing, entities.size(), relations.size());
            return new GraphStagingStore(directory, logPath, entities, relations, text,
                out, existing);
        } catch (IOException | RuntimeException e) {
            closeQuietly(entities);
            closeQuietly(relations);
            closeQuietly(out);
            closeQuietly(text);
            throw e;
        }
    }

    /**
     * Records one extracted fact.
     *
     * @param triple The fact
     * @throws IOException if the record cannot be written
     * @throws IllegalArgumentException if the triple names a relation id outside the storable range
     */
    public void append(ExtractedTriple triple) throws IOException {
        Objects.requireNonNull(triple, "triple must not be null");

        int subject = entities.intern(triple.subject());
        int object = entities.intern(triple.object());
        int relation = relations.intern(triple.relation());
        RelationSelector.checkRelation(relation);

        Provenance provenance = triple.provenance();
        ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(subject);
        record.putInt(relation);
        record.putInt(object);
        record.putInt(triple.validFrom());
        record.putInt(triple.validTo());
        record.put(provenance.sourceType().toByte());
        record.put(provenance.confidenceByte());
        record.putShort(provenance.extractorVersionShort());
        record.putLong(provenance.sourceId());

        CRC32 crc = new CRC32();
        crc.update(record.array(), 0, RECORD_PAYLOAD_BYTES);
        record.putInt((int) crc.getValue());

        synchronized (appendLock) {
            requireOpen();
            out.write(record.array());
            staged++;
        }
    }

    /**
     * Replays every fact recorded so far, in the order it was staged.
     *
     * <p>Reads the file rather than any in-memory copy, so only what has been flushed is seen.
     *
     * <p>A damaged record ends the replay by failing it, not by stopping at it. The replay is
     * what a build reads, and a build that stopped at the damage would publish a graph missing
     * every fact after it and report success - the same condition {@link #erase} refuses, decided
     * the same way. See {@link #corrupt}.
     *
     * @param visitor Receives each fact, with ids resolved
     * @return How many facts were replayed
     * @throws IOException if the log cannot be read, or holds a record whose checksum fails
     */
    public long forEach(TripleVisitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        flush();

        if (!Files.exists(logPath) || Files.size(logPath) < HEADER_BYTES) {
            return 0;
        }

        Cursor cursor = new Cursor(Files.readAllBytes(logPath), logPath);
        // A build asked to stop before it reached the log does no work at all.
        BuildCancellation.checkpoint();
        while (cursor.next()) {
            // Between two facts, which is a safe place for a build to stop: what it has
            // assembled so far is in a scratch directory nothing is served from.
            BuildCancellation.checkpoint(cursor.read);
            visitor.visit(cursor.subject, cursor.relation, cursor.object,
                cursor.validFrom, cursor.validTo, cursor.provenance);
        }
        return cursor.read;
    }

    /**
     * The one refusal both the replay and the erasure rewrite raise for a failed checksum.
     *
     * <h3>Why a failed checksum here is never a torn tail</h3>
     * <p>The records are fixed width and a partial trailing one is cut off when the log is
     * opened, so every record the scan reaches was written whole. A checksum that fails over a
     * whole record is therefore rot or a device error, and the facts after it were written and
     * acknowledged to whoever staged them.
     *
     * <h3>Why both paths refuse rather than stop</h3>
     * <p>Neither of the two alternatives is honest. Building from the facts before the damage
     * publishes a graph missing the rest while reporting success, which nobody can see. Erasing
     * around it reports an obligation discharged that never reached the records past it. A log
     * that will not open is the only outcome an operator can act on, and they still have the log.
     *
     * @param logPath      The log, named in the failure
     * @param factsBefore  How many records read cleanly before the damage
     * @return The failure to throw
     */
    private static IOException corrupt(Path logPath, long factsBefore) {
        return new IOException("triple log " + logPath + " has a corrupt record at fact "
            + factsBefore + ". The records are fixed width and a partial trailing one is trimmed "
            + "when the log is opened, so this is rot rather than a write cut short, and the "
            + "facts after it were written and acknowledged. Building from the facts before it "
            + "would publish a partial graph and report success, and erasing around it would "
            + "report an obligation discharged that had not reached them; this build refuses the "
            + "log instead. Recover or quarantine the file before restarting.");
    }

    /**
     * Walks the fixed-width records of a triple log already in memory.
     *
     * <p>One place that knows the framing, shared by the replay and the erasure rewrite so the
     * two cannot disagree about where a record ends or about what a failed checksum means. They
     * did disagree: the replay stopped at damage and the rewrite refused it, so a build quietly
     * published a prefix of the facts an erasure had just refused to touch.
     */
    private static final class Cursor {

        private final byte[] all;
        private final ByteBuffer buffer;
        private final Path path;

        private long read;
        private int start;
        private int subject;
        private int relation;
        private int object;
        private int validFrom;
        private int validTo;
        private Provenance provenance = Provenance.UNKNOWN;

        /**
         * @param all  The whole log as read
         * @param path The log, for any failure raised while walking it
         * @throws IOException if the file is not a triple log of a version this build reads
         */
        Cursor(byte[] all, Path path) throws IOException {
            this.all = all;
            this.path = path;
            // The header is written by DataOutputStream, which is big-endian; the fixed-width
            // records after it are little-endian like every other column this system stores.
            // Reading the header through the record view turns the magic into its own reversal.
            this.buffer = readHeader(all, path, "triple log").order(ByteOrder.LITTLE_ENDIAN);
        }

        /**
         * Decodes the next record.
         *
         * @return false once the log is exhausted
         * @throws IOException if a record's checksum does not match
         */
        boolean next() throws IOException {
            if (buffer.remaining() < RECORD_BYTES) {
                return false;
            }
            start = buffer.position();
            subject = buffer.getInt();
            relation = buffer.getInt();
            object = buffer.getInt();
            validFrom = buffer.getInt();
            validTo = buffer.getInt();
            byte sourceType = buffer.get();
            byte confidence = buffer.get();
            short extractorVersion = buffer.getShort();
            long sourceId = buffer.getLong();
            int stored = buffer.getInt();

            CRC32 crc = new CRC32();
            crc.update(all, start, RECORD_PAYLOAD_BYTES);
            if (stored != (int) crc.getValue()) {
                throw corrupt(path, read);
            }
            provenance = new Provenance(SourceType.fromByte(sourceType), sourceId,
                Byte.toUnsignedInt(confidence), extractorVersion);
            read++;
            return true;
        }
    }

    /**
     * Removes every staged fact, snippet and label that an erasure covers.
     *
     * <h3>Why staging has to be erased too</h3>
     * <p>Rewriting the edge store removes the erased edges from the graph being served. It does
     * not touch the log those edges were built from, so the next {@code POST /v1/graph/build}
     * replays the staged facts and puts back exactly what the erasure removed. The facts also
     * simply remain on disk in the meantime, which for an erasure obligation is the whole
     * problem: a tombstone is a filter, and what was promised was that the data stops
     * existing.</p>
     *
     * <h3>Labels are part of it</h3>
     * <p>An entity's label is a person's name, kept apart from the facts in {@code entities
     * .labels}. Erasing the facts and the snippets and leaving the label behind left the name to
     * be republished by the next build into the searchable label index, where the person who
     * asked to be forgotten was findable by searching for themselves. The label is therefore
     * tombstoned in place, keeping its ordinal, because no id may move.</p>
     *
     * <p>A corrupt record aborts the rewrite with the logs untouched, rather than truncating at
     * it. Erasing everything after a bad record would destroy facts nobody asked to remove, and
     * silently keeping them would discharge a promise that had not been kept; leaving the
     * request outstanding and failing loudly is the only option that does neither.</p>
     *
     * @param erasure What the requests cover
     * @return What was removed, and what could not be judged
     * @throws IOException if a log cannot be read or rewritten
     * @throws NullPointerException if {@code erasure} is null
     */
    public EraseResult erase(StagedErasure erasure) throws IOException {
        Objects.requireNonNull(erasure, "erasure must not be null");

        synchronized (appendLock) {
            requireOpen();
            flush();
            long facts = rewriteTriples(erasure);
            EntityTextLog.Erased snippets = text.erase(erasure::erasesSnippet);
            int labels = entities.tombstone(erasure::erasesLabel);
            return new EraseResult(facts, snippets.removed(), labels,
                snippets.ofUnknownOrigin());
        }
    }

    /**
     * What one erasure pass removed from staging, and what it could not reach.
     *
     * @param factsRemoved            Staged facts removed from the triple log
     * @param snippetsRemoved         Recorded snippets removed from the entity text log
     * @param labelsTombstoned        Entity labels replaced by a placeholder
     * @param snippetsOfUnknownOrigin Surviving snippets with no recorded origin. A source-scoped
     *                                or extractor-scoped request cannot be matched against these,
     *                                so a non-zero count means such a request has not been
     *                                satisfied however many facts were removed, and must not be
     *                                reported as discharged.
     */
    public record EraseResult(long factsRemoved, long snippetsRemoved, long labelsTombstoned,
                              long snippetsOfUnknownOrigin) {

        /** @throws IllegalArgumentException if any count is negative */
        public EraseResult {
            if (factsRemoved < 0 || snippetsRemoved < 0 || labelsTombstoned < 0
                || snippetsOfUnknownOrigin < 0) {
                throw new IllegalArgumentException("counts must not be negative");
            }
        }

        /** Everything removed, across all three logs. */
        public long removed() {
            return factsRemoved + snippetsRemoved + labelsTombstoned;
        }
    }

    private long rewriteTriples(StagedErasure erasure) throws IOException {
        if (!Files.exists(logPath) || Files.size(logPath) < HEADER_BYTES) {
            return 0;
        }
        byte[] all = Files.readAllBytes(logPath);
        Cursor cursor = new Cursor(all, logPath);

        // Scanned before anything is written, so a log that loses nothing is not churned. A
        // request that can never be satisfied stays outstanding, and rewriting both logs on every
        // compaction from then on would be a steady cost for no change.
        List<Integer> kept = new ArrayList<>();
        long removed = 0;
        while (cursor.next()) {
            if (erasure.erasesFact(cursor.subject, cursor.relation, cursor.object,
                    cursor.validFrom, cursor.validTo, cursor.provenance)) {
                removed++;
            } else {
                kept.add(cursor.start);
            }
        }
        if (removed == 0) {
            return 0;
        }

        Path temp = logPath.resolveSibling(logPath.getFileName() + ERASING_SUFFIX);
        Files.deleteIfExists(temp);
        out.close();
        // Reopened on every path below, successful or not: the swap replaces the file this
        // appends to, and a store left holding a closed stream would accept nothing and say
        // nothing about it.
        try {
            try (DataOutputStream rewritten = openLog(temp)) {
                for (int start : kept) {
                    rewritten.write(all, start, RECORD_BYTES);
                }
            }
            // Forced before the swap, because the point of the rewrite is that the erased facts
            // are gone: a rename that outran its own contents would leave them on the platter
            // after the erasure was reported discharged.
            Durability.DEVICE.replaceDurably(temp, logPath);
            staged = kept.size();
        } finally {
            Files.deleteIfExists(temp);
            out = openLog(logPath);
        }
        LOGGER.info("erased {} staged fact(s) from {}; {} remain", removed, logPath, kept.size());
        return removed;
    }

    private static ByteBuffer readHeader(byte[] all, Path path, String what) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(all);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not a " + what + " (magic 0x" + Long.toHexString(magic)
                + "): " + path);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported " + what + " version " + version + ": " + path);
        }
        buffer.getInt();
        return buffer;
    }

    /**
     * The id an entity label was given, assigning one if it is new.
     *
     * @param label The entity label
     * @return Its id
     * @throws IOException if a new assignment cannot be recorded
     */
    public int entityIdOf(String label) throws IOException {
        return entities.intern(label);
    }

    /**
     * The id a relation label was given, assigning one if it is new.
     *
     * @param label The relation label
     * @return Its id
     * @throws IOException if a new assignment cannot be recorded
     */
    public int relationIdOf(String label) throws IOException {
        return relations.intern(label);
    }

    /**
     * Records a piece of text an entity was mentioned in, with no origin.
     *
     * <p>A snippet recorded this way cannot be reached by a source-scoped or extractor-scoped
     * erasure, because there is nothing in it to match: withdrawing the document it was copied
     * out of will report the snippet as an obligation it could not meet. Prefer
     * {@link #appendEntityText(int, String, Provenance)} wherever the origin is known, which at
     * an ingestion site it always is.
     *
     * @param entityId The entity
     * @param snippet  Text it appeared in; truncated to {@link #MAX_SNIPPET_BYTES}
     * @throws IOException if the record cannot be written
     */
    public void appendEntityText(int entityId, String snippet) throws IOException {
        appendEntityText(entityId, snippet, Provenance.UNKNOWN);
    }

    /**
     * Records a piece of text an entity was mentioned in, and where it came from.
     *
     * <p>What BM25 ranks the entity by. An entity with no recorded text is still traversable - it
     * is in the edge store - but contributes nothing to the hybrid signal, so a graph built
     * without this ranks every entity identically and the serialization budget is spent in
     * arbitrary order.
     *
     * @param entityId   The entity
     * @param snippet    Text it appeared in; truncated to {@link #MAX_SNIPPET_BYTES}
     * @param provenance The document, conversation or statement the text was copied out of
     * @throws IOException if the record cannot be written
     */
    public void appendEntityText(int entityId, String snippet, Provenance provenance)
            throws IOException {
        requireOpen();
        text.append(entityId, snippet, provenance);
    }

    /**
     * Replays every snippet recorded so far.
     *
     * @param visitor Receives each snippet with the entity it belongs to
     * @return How many snippets were replayed
     * @throws IOException if the log cannot be read
     */
    public long forEachEntityText(EntityTextVisitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        flush();
        return text.forEach((entityId, snippet, provenance) ->
            visitor.visit(entityId, snippet));
    }

    /**
     * Replays every snippet recorded so far, with the source each came out of.
     *
     * @param visitor Receives each snippet
     * @return How many snippets were replayed
     * @throws IOException if the log cannot be read
     */
    public long forEachEntityText(EntityTextLog.Visitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        flush();
        return text.forEach(visitor);
    }

    /** Receives each snippet of a {@link #forEachEntityText} replay. */
    @FunctionalInterface
    public interface EntityTextVisitor {

        /**
         * @param entityId The entity the text mentions
         * @param snippet  The text
         */
        void visit(int entityId, String snippet);
    }

    /**
     * How many entity ids have been assigned.
     *
     * <p>This is the {@code entityCount} an edge store built from this staging must be given: ids
     * run {@code 0..entityCount()-1}, and the store's offset array is sized from it.
     *
     * @return The count
     */
    public int entityCount() {
        return entities.size();
    }

    /**
     * How many relation ids have been assigned.
     *
     * @return The count
     */
    public int relationCount() {
        return relations.size();
    }

    /**
     * How many facts have been staged, including any not yet flushed.
     *
     * @return The count
     */
    public long stagedCount() {
        synchronized (appendLock) {
            return staged;
        }
    }

    /**
     * The entity labels, in id order.
     *
     * @return A snapshot
     */
    public List<String> entityLabels() {
        return entities.labels();
    }

    /**
     * The relation labels, in id order.
     *
     * @return A snapshot
     */
    public List<String> relationLabels() {
        return relations.labels();
    }

    /**
     * The directory this store lives in.
     *
     * @return The directory
     */
    public Path directory() {
        return directory;
    }

    /**
     * Forces everything staged so far to disk.
     *
     * @throws IOException if the write fails
     */
    public void flush() throws IOException {
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            entities.flush();
            relations.flush();
            out.flush();
        }
        text.flush();
    }

    @Override
    public void close() throws IOException {
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            closed = true;
            out.flush();
            out.close();
        }
        text.close();
        entities.close();
        relations.close();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("graph staging store is closed: " + directory);
        }
    }

    /**
     * Opens one append-only log, writing the header when the file is new.
     *
     * @param path The log
     * @return A buffered stream positioned at the end
     * @throws IOException if the file cannot be opened
     */
    private static DataOutputStream openLog(Path path) throws IOException {
        boolean fresh = !Files.exists(path) || Files.size(path) < HEADER_BYTES;
        OutputStream sink = Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sink));
        if (fresh) {
            out.writeLong(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(0);
            out.flush();
        }
        return out;
    }

    /**
     * Trims a partial trailing record and reports how many whole ones remain.
     *
     * <p>The records are fixed width, so a file whose length is not a whole number of them ends in
     * a record that was being written when the process died. Cutting it here is what keeps the
     * next append contiguous with the ones replay can read.
     *
     * @param logPath The triple log
     * @return How many whole records the file holds
     * @throws IOException if the file cannot be trimmed
     */
    private static long truncateToWholeRecords(Path logPath) throws IOException {
        if (!Files.exists(logPath)) {
            return 0;
        }
        long size = Files.size(logPath);
        if (size < HEADER_BYTES) {
            return 0;
        }
        long records = (size - HEADER_BYTES) / RECORD_BYTES;
        long whole = HEADER_BYTES + records * RECORD_BYTES;
        if (size != whole) {
            LOGGER.warn("trimming {} from {} to {} bytes: the last record was incomplete",
                logPath, size, whole);
            try (java.nio.channels.FileChannel channel =
                     java.nio.channels.FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                channel.truncate(whole);
            }
        }
        return records;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.debug("ignoring failure closing {} during a failed open", closeable, e);
        }
    }

    /** Receives each fact of a {@link #forEach} replay, with its ids resolved. */
    @FunctionalInterface
    public interface TripleVisitor {

        /**
         * @param subject    subject entity id
         * @param relation   relation id
         * @param object     object entity id
         * @param validFrom  first day the fact held
         * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN}
         * @param provenance where the fact came from
         */
        void visit(int subject, int relation, int object, int validFrom, int validTo,
                   Provenance provenance);
    }

    @Override
    public String toString() {
        return "GraphStagingStore[" + directory + ", " + stagedCount() + " facts, "
            + entityCount() + " entities, " + relationCount() + " relations]";
    }
}
