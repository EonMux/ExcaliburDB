package io.excalibur.graph.staging;

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
 * <p>Fixed-width records, so a torn tail is a file whose length is not a whole number of records
 * and a half-written record is caught by its checksum. Either ends the replay at the last good
 * record, which is the same tolerance the interners apply and for the same reason: a crash during
 * ingestion must cost the facts that were in flight, not the graph.</p>
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

    /**
     * Longest snippet recorded for one mention.
     *
     * <p>An entity's document is what BM25 ranks it by, and a few sentences of the text it
     * appeared in is what makes it rankable. Recording whole documents per mention would store
     * the corpus once per entity in it.
     */
    public static final int MAX_SNIPPET_BYTES = 2048;

    private final Path directory;
    private final Path logPath;
    private final Path textPath;
    private final LabelInterner entities;
    private final LabelInterner relations;
    private final Object appendLock = new Object();
    private final Object textLock = new Object();

    private DataOutputStream out;
    private DataOutputStream textOut;
    private long staged;
    private volatile boolean closed;

    private GraphStagingStore(Path directory, Path logPath, Path textPath, LabelInterner entities,
                              LabelInterner relations, DataOutputStream out,
                              DataOutputStream textOut, long staged) {
        this.directory = directory;
        this.logPath = logPath;
        this.textPath = textPath;
        this.entities = entities;
        this.relations = relations;
        this.out = out;
        this.textOut = textOut;
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
        DataOutputStream textOut = null;
        try {
            relations = LabelInterner.open(directory.resolve(RELATION_LABELS));

            // Truncated to a whole number of records before anything is appended. A process
            // killed mid-append leaves a partial record, and appending after it would put every
            // later fact behind a record replay stops at - present on disk and invisible to every
            // build from then on.
            Path logPath = directory.resolve(TRIPLE_LOG);
            long existing = truncateToWholeRecords(logPath);
            out = openLog(logPath);

            Path textPath = directory.resolve(ENTITY_TEXT_LOG);
            textOut = openLog(textPath);

            LOGGER.info("graph staging at {}: {} facts, {} entities, {} relations",
                directory, existing, entities.size(), relations.size());
            return new GraphStagingStore(directory, logPath, textPath, entities, relations,
                out, textOut, existing);
        } catch (IOException | RuntimeException e) {
            closeQuietly(entities);
            closeQuietly(relations);
            closeQuietly(out);
            closeQuietly(textOut);
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
     * @param visitor Receives each fact, with ids resolved
     * @return How many facts were replayed
     * @throws IOException if the log cannot be read
     */
    public long forEach(TripleVisitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        flush();

        if (!Files.exists(logPath) || Files.size(logPath) < HEADER_BYTES) {
            return 0;
        }

        byte[] all = Files.readAllBytes(logPath);
        // The header is written by DataOutputStream, which is big-endian; the fixed-width records
        // after it are little-endian like every other column this system stores. Reading the
        // header through the record view turns the magic into its own byte reversal.
        ByteBuffer buffer = ByteBuffer.wrap(all);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not a triple log (magic 0x" + Long.toHexString(magic)
                + "): " + logPath);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported triple log version " + version + ": " + logPath);
        }
        buffer.getInt();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        long replayed = 0;
        while (buffer.remaining() >= RECORD_BYTES) {
            int start = buffer.position();
            int subject = buffer.getInt();
            int relation = buffer.getInt();
            int object = buffer.getInt();
            int validFrom = buffer.getInt();
            int validTo = buffer.getInt();
            byte sourceType = buffer.get();
            byte confidence = buffer.get();
            short extractorVersion = buffer.getShort();
            long sourceId = buffer.getLong();
            int stored = buffer.getInt();

            CRC32 crc = new CRC32();
            crc.update(all, start, RECORD_PAYLOAD_BYTES);
            if (stored != (int) crc.getValue()) {
                LOGGER.warn("triple log {} has a corrupt record at fact {}; "
                    + "building from the {} facts before it", logPath, replayed, replayed);
                break;
            }

            visitor.visit(subject, relation, object, validFrom, validTo,
                new Provenance(SourceType.fromByte(sourceType), sourceId,
                    Byte.toUnsignedInt(confidence), extractorVersion));
            replayed++;
        }
        return replayed;
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
     * Records a piece of text an entity was mentioned in.
     *
     * <p>What BM25 ranks the entity by. An entity with no recorded text is still traversable - it
     * is in the edge store - but contributes nothing to the hybrid signal, so a graph built
     * without this ranks every entity identically and the serialization budget is spent in
     * arbitrary order.
     *
     * @param entityId The entity
     * @param snippet  Text it appeared in; truncated to {@link #MAX_SNIPPET_BYTES}
     * @throws IOException if the record cannot be written
     */
    public void appendEntityText(int entityId, String snippet) throws IOException {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must not be negative: " + entityId);
        }
        if (snippet == null || snippet.isBlank()) {
            return;
        }

        byte[] bytes = snippet.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_SNIPPET_BYTES) {
            bytes = truncateUtf8(bytes, MAX_SNIPPET_BYTES);
        }

        CRC32 crc = new CRC32();
        crc.update(bytes);

        synchronized (textLock) {
            requireOpen();
            textOut.writeInt(entityId);
            textOut.writeInt(bytes.length);
            textOut.write(bytes);
            textOut.writeInt((int) crc.getValue());
        }
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

        if (!Files.exists(textPath) || Files.size(textPath) < HEADER_BYTES) {
            return 0;
        }

        byte[] all = Files.readAllBytes(textPath);
        ByteBuffer buffer = ByteBuffer.wrap(all);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not an entity text log (magic 0x" + Long.toHexString(magic)
                + "): " + textPath);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported entity text log version " + version
                + ": " + textPath);
        }
        buffer.getInt();

        long replayed = 0;
        while (buffer.remaining() >= Integer.BYTES * 2) {
            int entityId = buffer.getInt();
            int length = buffer.getInt();
            if (entityId < 0 || length <= 0 || length > MAX_SNIPPET_BYTES
                || buffer.remaining() < length + Integer.BYTES) {
                break;
            }
            int start = buffer.position();
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            int stored = buffer.getInt();
            CRC32 crc = new CRC32();
            crc.update(all, start, length);
            if (stored != (int) crc.getValue()) {
                LOGGER.warn("entity text log {} has a corrupt record after {} snippets; "
                    + "building from what came before it", textPath, replayed);
                break;
            }
            visitor.visit(entityId, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            replayed++;
        }
        return replayed;
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
    public java.util.List<String> entityLabels() {
        return entities.labels();
    }

    /**
     * The relation labels, in id order.
     *
     * @return A snapshot
     */
    public java.util.List<String> relationLabels() {
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
        synchronized (textLock) {
            if (!closed) {
                textOut.flush();
            }
        }
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
        synchronized (textLock) {
            textOut.flush();
            textOut.close();
        }
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
