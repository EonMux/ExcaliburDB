package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.build.BuildCancellation;
import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogHeader;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.file.TailIntegrity;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.RelationSelector;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 *                      [validFrom : int32][validTo : int32][statedOn : int32]
 *                      [sourceType : int8][confidence : int8][extractorVersion : int16]
 *                      [sourceId : int64][crc32 : int32]
 * </pre>
 *
 * <p>{@code statedOn} is the day the source made the statement, which is what orders two
 * statements of a single-valued relation when neither says when its value began; see
 * {@link ExtractedTriple}. Every record has one: a fact whose source gave no date is recorded
 * with the day it was staged, because the order facts arrive in is the only evidence left about
 * which statement is the later one.</p>
 *
 * <p>Version {@value #VERSION_WITHOUT_STATED_ON} is the same framing without {@code statedOn}, in
 * {@value #LEGACY_RECORD_BYTES}-byte records. A log in that version is rewritten to the current
 * one when it is opened, its facts carried across with no stated day: nothing recorded when they
 * were made, and the day of the upgrade would be an invention. The order they were staged in
 * survives the rewrite, and it is what orders them.</p>
 *
 * <h2>What a crash leaves at the end</h2>
 * <p>A crash during ingestion must cost the facts that were in flight, not the graph, so the open
 * cuts off the end of the log a crash can leave unreadable, as the interners and the entity text
 * log do. That is more than a partial last record. Appends are forced only by {@link #sync()},
 * and a force that never finished leaves every record written since the last one that did at the
 * mercy of the order the operating system wrote the pages back in: a record can reach the device
 * whole in length and as zeros or garbage in content. Nothing in that stretch was acknowledged -
 * the documents its facts came from are recorded as handled only after a sync returns, so they
 * are extracted again - and so the open cuts off every record from the end back to the last one
 * that matches its checksum, a partial one included. See {@link #trimTornTail}.</p>
 *
 * <p>What it does not cut off is damage with an intact record after it. That record may have been
 * forced and acknowledged, the damage before it may be rot rather than a write that never landed,
 * and nothing in the log says which, so both the replay and the erasure rewrite refuse the log
 * rather than stopping at the damage. See {@link #forEach}.</p>
 *
 * <h2>Buffered, flushed and forced</h2>
 * <p>Appends go into buffers this process holds, because one write per fact would make ingestion
 * pay a system call for every triple it stages. So three states matter, and they are not the same
 * promise. Buffered is lost with the process. {@linkplain #flush() Flushed} reaches the operating
 * system and survives the process dying, but not the machine. {@linkplain #sync() Forced} reaches
 * the device and survives both. A build needs only flushed, because it reads the files; anything
 * that goes on to record the facts' documents as handled - the document registry, which is forced
 * to the device when it is written, and is what stops those documents being extracted again -
 * needs forced first, or a power loss leaves the registry saying the facts were taken while the
 * facts are gone. {@link #close()} forces for the same reason: a store nobody holds any more is
 * one nobody will force later.</p>
 *
 * <p>The interners are the exception, and are forced as they go: an id is written into the logs
 * here and into artifacts beyond them, and none of those may reach the device ahead of the label
 * that says who the id is. See {@link LabelInterner}.</p>
 *
 * <h2>Ids nothing may lose</h2>
 * <p>An interner with a damaged record is opened by cutting that record off, with everything
 * after it, which is only safe while nothing holds an id it loses. So when one has, the open
 * reads the highest id staged facts and entity text refer to, together with whatever
 * {@link ReferencedLabels} the caller names - a published graph, an erasure log - and refuses
 * rather than give an id that is held to a new label. {@link #inspectLabels} asks the same
 * question without opening anything.</p>
 *
 * <h2>A log shorter than its header</h2>
 * <p>Each log is created by writing its header, so a file shorter than one is either that write
 * cut short, which the open starts afresh, or bytes some other writer left, which the open
 * refuses and leaves alone. See {@link LogHeader}.</p>
 *
 * <h2>Changes the published graph does not have</h2>
 * <p>The served graph is rebuilt from this store, so it is current exactly when nothing here has
 * changed since the build that published it read it. The store therefore counts its own changes
 * - a staged fact, a recorded snippet, a withdrawal that removed something - and keeps
 * {@value #UNPUBLISHED_MARKER}, an empty file, for as long as any of them is unpublished. A build
 * reads {@link #changeCount()} before it reads anything else and hands it back to
 * {@link #markPublished(long)} once it has published; a change made in between leaves the count
 * moved and the marker in place, so the graph is never taken to be current by a build that did
 * not see everything. The marker is what survives a restart, which is what lets a node tell which
 * of its namespaces are behind without rebuilding them all or opening any of them.</p>
 *
 * <p>The marker's name is made durable with the facts it describes: {@link #sync()} forces the
 * directory, and nothing acknowledges a staged fact before a sync. A withdrawal is durable the
 * moment its rewrite is, so it forces the marker before it rewrites anything. An erasure that
 * removes the same records from the published graph in the same operation is not a change the
 * graph lacks, and {@link #eraseAlongsidePublished} leaves the marker alone.</p>
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

    /** The version written before each record carried the day its statement was made. */
    public static final int VERSION_WITHOUT_STATED_ON = 1;

    /** Format version written by this build. */
    public static final int VERSION = 2;

    /** Bytes before the first record. */
    public static final int HEADER_BYTES = 16;

    /**
     * Bytes per staged triple.
     *
     * <p>Three ids, two interval bounds, the stated-on day, four provenance fields and a
     * checksum: 4+4+4 + 4+4 + 4 + 1+1+2+8 + 4. Fixed width, so the records a crash leaves
     * unreadable at the end can be found by reading back from it.
     */
    public static final int RECORD_BYTES = 40;

    /** Bytes per staged triple in a {@value #VERSION_WITHOUT_STATED_ON} log. */
    public static final int LEGACY_RECORD_BYTES = 36;

    /** Bytes of a record before the stated-on day, laid out identically in both versions. */
    private static final int INTERVAL_END = 20;

    /** Bytes of provenance after the interval (and, in the current version, the stated-on day). */
    private static final int PROVENANCE_BYTES = 12;

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
     * Empty file whose presence says staging holds changes no published graph has.
     *
     * <p>Created by the first change after a publish and removed by the publish that saw every
     * change; see "Changes the published graph does not have".</p>
     */
    public static final String UNPUBLISHED_MARKER = "unpublished.changes";

    /** Longest snippet recorded for one mention; see {@link EntityTextLog#MAX_SNIPPET_BYTES}. */
    public static final int MAX_SNIPPET_BYTES = EntityTextLog.MAX_SNIPPET_BYTES;

    /**
     * The extractor version recorded on every asserted fact: no extractor made it.
     *
     * <p>Below the version an extractor is given when none is configured, which is 1, so an
     * extractor-scoped erasure aimed at an extractor does not reach assertions - unless the
     * deployment numbered its extractor 0, when that erasure covers both.</p>
     */
    public static final int ASSERTED_EXTRACTOR_VERSION = 0;

    /** Suffix of the partially rewritten log an erasure builds before swapping it in. */
    private static final String ERASING_SUFFIX = ".erasing";

    /** Suffix of the replacement an upgrade from an earlier version builds before swapping it. */
    private static final String REWRITE_SUFFIX = ".rewriting";

    /**
     * The name of every replacement a rewrite of one of this store's logs builds beside it: an
     * erasure's, a tombstoning's, an upgrade's.
     *
     * <p>A rewrite renames its replacement over the log once it is forced, and removes it when it
     * fails, so one on disk is a rewrite a crash cut short - the log itself is whole either way.
     * What it holds is the log's surviving records, which an erasure accepted since may cover, so
     * {@link #open} removes them: no rewrite can be under way before the store that runs it has
     * opened.</p>
     */
    public static final List<String> UNFINISHED_REWRITES = List.of(
        TRIPLE_LOG + ERASING_SUFFIX,
        TRIPLE_LOG + REWRITE_SUFFIX,
        ENTITY_TEXT_LOG + EntityTextLog.REWRITE_SUFFIX,
        ENTITY_LABELS + LabelInterner.REWRITE_SUFFIX,
        RELATION_LABELS + LabelInterner.REWRITE_SUFFIX,
        FactIdFloor.REWRITE_NAME);

    private final Path directory;
    private final Path logPath;
    private final LabelInterner entities;
    private final LabelInterner relations;
    private final EntityTextLog text;
    private final Clock clock;
    private final Object appendLock = new Object();

    /** Orders fact ids; see {@link #assertFacts}. */
    private final Object assertionLock = new Object();

    /** The last fact id handed out, or -1 until the log has been read for it. */
    private long lastAssertionId = -1;

    private DataOutputStream out;
    private long staged;
    private volatile boolean closed;

    /**
     * Whether anything has been appended or interned since the last {@link #sync()}.
     *
     * <p>Lets a caller force every resident namespace at the end of each ingestion run without
     * paying a device flush per file for the namespaces that run never wrote to. Cleared before
     * the flush it guards rather than after, so an append racing the sync marks the store again
     * and the next sync covers it.</p>
     */
    private volatile boolean unsynced;

    /**
     * Guards the record of unpublished changes below.
     *
     * <p>Apart from {@link #appendLock} so that asking whether a namespace is behind never waits
     * for a sync or an erasure rewrite. Where both are held, {@link #appendLock} is taken
     * first.</p>
     */
    private final Object changeLock = new Object();

    /** Changes since this store opened; see {@link #changeCount()}. */
    private long changes;

    /** Whether staging holds a change no published graph has. */
    private boolean pending;

    /** Whether {@value #UNPUBLISHED_MARKER} is on disk, as far as this store knows. */
    private boolean markerOnDisk;

    /** When the oldest unpublished change was made, or null when nothing is pending. */
    private Instant pendingSince;

    /** When the newest unpublished change was made, or null when nothing is pending. */
    private Instant lastChangedAt;

    private GraphStagingStore(Path directory, Path logPath, LabelInterner entities,
                              LabelInterner relations, EntityTextLog text, Clock clock,
                              DataOutputStream out, long staged, Unpublished unpublished) {
        this.directory = directory;
        this.logPath = logPath;
        this.entities = entities;
        this.relations = relations;
        this.text = text;
        this.clock = clock;
        this.out = out;
        this.staged = staged;
        if (unpublished != null) {
            this.pending = true;
            this.markerOnDisk = true;
            this.pendingSince = unpublished.since();
            this.lastChangedAt = unpublished.lastChanged();
        }
    }

    /**
     * The unpublished changes a store found on disk when it opened.
     *
     * @param since       When the marker was written
     * @param lastChanged When staging was last written
     */
    private record Unpublished(Instant since, Instant lastChanged) {
    }

    /**
     * Opens the staging store in {@code directory}, creating it if it is not there.
     *
     * @param directory Where the interners and the log live
     * @return The store
     * @throws IOException if the directory or its files cannot be opened
     */
    public static GraphStagingStore open(Path directory) throws IOException {
        return open(directory, Clock.systemUTC(), ReferencedLabels.NONE);
    }

    /**
     * Opens the staging store in {@code directory}, naming what else holds its label ids.
     *
     * @param directory Where the interners and the log live
     * @param holders   What outside the directory refers to label ids; see {@link #open(Path,
     *                  Clock, ReferencedLabels)}
     * @return The store
     * @throws IOException if the directory or its files cannot be opened
     */
    public static GraphStagingStore open(Path directory, ReferencedLabels holders)
            throws IOException {
        return open(directory, Clock.systemUTC(), holders);
    }

    /**
     * Opens the staging store in {@code directory}, dating undated facts by {@code clock}.
     *
     * @param directory Where the interners and the log live
     * @param clock     What the day a fact is staged on is read from
     * @return The store
     * @throws IOException if the directory or its files cannot be opened, or a log in an earlier
     *                     version cannot be upgraded
     */
    public static GraphStagingStore open(Path directory, Clock clock) throws IOException {
        return open(directory, clock, ReferencedLabels.NONE);
    }

    /**
     * Opens the staging store in {@code directory}.
     *
     * <h3>What {@code holders} is for</h3>
     * <p>An interner with a damaged record may be cut back to it only when no id at or past it is
     * held anywhere; see "Ids nothing may lose". The staged facts and entity text in this
     * directory are always asked. {@code holders} names what lives elsewhere and refers to the
     * same ids - the graph published from this staging, and the erasure logs whose requests name
     * entities by id - and is read only when an interner would otherwise give an id up.</p>
     *
     * @param directory Where the interners and the log live
     * @param clock     What the day a fact is staged on is read from
     * @param holders   What outside the directory refers to label ids
     * @return The store
     * @throws IOException if the directory or its files cannot be opened, a log is too short to
     *                     hold a header and is not a torn creation of one, or a log in an earlier
     *                     version cannot be upgraded
     * @throws LogCorruptionException if a log is damaged other than at its tail, or an interner
     *                                would lose an id something refers to
     */
    public static GraphStagingStore open(Path directory, Clock clock, ReferencedLabels holders)
            throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(holders, "holders must not be null");
        Files.createDirectories(directory);

        // Judged before anything in the directory is written, so a log that has to be refused
        // leaves the others as they were too.
        Path logPath = directory.resolve(TRIPLE_LOG);
        boolean freshLog = LogHeader.requireCreatable(logPath, "triple log", MAGIC, VERSION);
        LogHeader.requireCreatable(directory.resolve(ENTITY_TEXT_LOG), "entity text log",
            EntityTextLog.MAGIC, EntityTextLog.VERSION);

        // Read before the open writes a header or trims a tail, which would date the last
        // change to this open rather than to the write it describes.
        Unpublished unpublished = unpublishedOnDisk(directory)
            .map(since -> new Unpublished(since, lastChangedOnDisk(directory).orElse(since)))
            .orElse(null);

        ReferencedLabels held = everythingHolding(directory, holders);
        LabelInterner entities = LabelInterner.open(directory.resolve(ENTITY_LABELS),
            () -> held.reach().entities());
        LabelInterner relations = null;
        DataOutputStream out = null;
        EntityTextLog text = null;
        try {
            relations = LabelInterner.open(directory.resolve(RELATION_LABELS),
                () -> held.reach().relations());

            if (freshLog && Files.exists(logPath)) {
                // A header write cut short: truncated so the header is written at the start.
                LOGGER.warn("starting {} afresh: its {} bytes are a header write a crash cut "
                    + "short", logPath, Files.size(logPath));
                try (FileChannel channel =
                         FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                    channel.truncate(0);
                }
            }

            // Upgraded first, because appending current-width records to a log of the earlier
            // width would misframe every record after the first append.
            upgradeIfEarlierVersion(logPath);

            // Cut back to the last intact record before anything is appended. Appending after a
            // record a crash left unreadable would put every later fact behind damage the replay
            // refuses - present on disk, and failing every build from then on.
            long existing = trimTornTail(logPath);
            out = openLog(logPath);

            text = EntityTextLog.open(directory.resolve(ENTITY_TEXT_LOG));

            removeUnfinishedRewrites(directory);

            LOGGER.info("graph staging at {}: {} facts, {} entities, {} relations",
                directory, existing, entities.size(), relations.size());
            return new GraphStagingStore(directory, logPath, entities, relations, text, clock,
                out, existing, unpublished);
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
     * <p>A fact whose source gave no date is recorded as stated on the day it is staged, and one
     * dated later than that is recorded as stated today. The first is the fallback that keeps a
     * later statement ordered after an earlier one when neither document carried a date; the
     * second is a clock that disagrees with ours - a file modified "tomorrow" - and a statement
     * cannot have been made after the node read it. Left alone it would place a change of value
     * in the future, where no question about the present could see it.</p>
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

        int today = Math.toIntExact(LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay());
        int statedOn = triple.isDated() ? Math.min(triple.statedOn(), today) : today;

        Provenance provenance = triple.provenance();
        ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(subject);
        record.putInt(relation);
        record.putInt(object);
        record.putInt(triple.validFrom());
        record.putInt(triple.validTo());
        record.putInt(statedOn);
        record.put(provenance.sourceType().toByte());
        record.put(provenance.confidenceByte());
        record.putShort(provenance.extractorVersionShort());
        record.putLong(provenance.sourceId());
        sealRecord(record.array());

        synchronized (appendLock) {
            requireOpen();
            out.write(record.array());
            staged++;
            unsynced = true;
            recordChange();
        }
    }

    /**
     * Stages facts a person asserted, each under a fact id of its own, and forces them to the
     * device before handing the ids back.
     *
     * <h3>The fact id is the source</h3>
     * <p>Each fact is recorded with {@link SourceType#USER_ASSERTED} provenance whose source id
     * is its fact id, so it is a source of its own: a source-scoped erasure naming the id removes
     * exactly that fact, from staging and from any graph built out of it, and nothing else. That
     * is how an assertion is retracted, and why an id must never name a second fact - a
     * retraction still owed would remove the newcomer. Ids are therefore the later of one past
     * the last id this store handed out and the current time in microseconds since the epoch, and
     * stay exact as JSON numbers until the year 2255. The last id is recorded in
     * {@link FactIdFloor#FILE_NAME} before any of a batch's ids is used, so they rise across
     * restarts even after the newest facts have been erased and the clock set back.</p>
     *
     * <h3>Forced before returning</h3>
     * <p>An id handed back is a promise that the fact is kept, and a caller that goes on to
     * retract it by that id has to find it. So the facts are forced along with the labels they
     * named, as {@link #sync()} forces them.</p>
     *
     * <p>A batch is not atomic against a failure part way: the facts appended before the failure
     * stay staged. Each is complete and correct on its own, and the failure is reported.</p>
     *
     * @param facts The facts, in the order to stage them
     * @return The fact id of each, in the same order
     * @throws IOException if a record cannot be written or forced
     * @throws NullPointerException if {@code facts} or any fact in it is null
     */
    public List<Long> assertFacts(List<AssertedFact> facts) throws IOException {
        Objects.requireNonNull(facts, "facts must not be null");
        List<AssertedFact> batch = List.copyOf(facts);
        List<Long> ids = assertionIds(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            AssertedFact fact = batch.get(i);
            append(new ExtractedTriple(fact.subject(), fact.relation(), fact.object(),
                fact.validFrom(), fact.validTo(),
                new Provenance(SourceType.USER_ASSERTED, ids.get(i), fact.confidence(),
                    ASSERTED_EXTRACTOR_VERSION)));
        }
        sync();
        return ids;
    }

    /**
     * Every asserted fact still staged, in the order it was staged.
     *
     * <p>Read from the log, so a fact a retraction has removed from staging is not among them.</p>
     *
     * @return The assertions
     * @throws IOException if the log cannot be read, or holds a record whose checksum fails
     */
    public List<StagedAssertion> assertions() throws IOException {
        flush();
        List<StagedAssertion> found = new ArrayList<>();
        if (!Files.exists(logPath) || Files.size(logPath) < HEADER_BYTES) {
            return found;
        }
        Cursor cursor = new Cursor(Files.readAllBytes(logPath), logPath);
        while (cursor.next()) {
            Provenance provenance = cursor.provenance;
            if (provenance.sourceType() == SourceType.USER_ASSERTED) {
                found.add(new StagedAssertion(provenance.sourceId(), cursor.subject,
                    cursor.relation, cursor.object, cursor.validFrom, cursor.validTo,
                    provenance.confidence()));
            }
        }
        return found;
    }

    /**
     * The next fact ids, recorded as handed out before they are returned; see
     * {@link #assertFacts}.
     *
     * <p>An id recorded and then not used - the append after this failed - is simply never
     * seen; it is never handed out again either.</p>
     *
     * @param count How many
     * @return Ids no fact in this store has had, rising
     * @throws IOException if the last id cannot be read or the new one cannot be recorded
     */
    private List<Long> assertionIds(int count) throws IOException {
        synchronized (assertionLock) {
            Path floor = directory.resolve(FactIdFloor.FILE_NAME);
            if (lastAssertionId < 0) {
                long highest = FactIdFloor.read(floor);
                for (StagedAssertion staged : assertions()) {
                    highest = Math.max(highest, staged.factId());
                }
                lastAssertionId = highest;
            }
            java.time.Instant now = clock.instant();
            long micros = Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000L),
                now.getNano() / 1_000L);
            long first = Math.max(lastAssertionId + 1, micros);
            List<Long> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ids.add(first + i);
            }
            if (count > 0) {
                FactIdFloor.write(floor, first + count - 1);
                lastAssertionId = first + count - 1;
            }
            return List.copyOf(ids);
        }
    }

    /**
     * The id an entity label has, without giving it one.
     *
     * @param label The label, exactly as it was staged
     * @return Its id, or -1 when no fact or snippet has named it
     */
    public int findEntity(String label) {
        return entities.find(label);
    }

    /**
     * The id a relation label has, without giving it one.
     *
     * @param label The label, exactly as it was staged
     * @return Its id, or -1 when no fact has used it
     */
    public int findRelation(String label) {
        return relations.find(label);
    }

    /**
     * Replays every fact recorded so far, in the order it was staged.
     *
     * <p>Reads the file rather than any in-memory copy, so only what has been flushed is seen.
     *
     * <p>A damaged record with an intact one after it ends the replay by failing it, not by
     * stopping at it. The replay is what a build reads, and a build that stopped at the damage
     * would publish a graph missing every fact after it and report success - the same condition
     * {@link #erase} refuses, decided the same way. See {@link #corrupt}. Damage with nothing
     * intact after it is the end of a write that never finished, which the open cuts off; met
     * here, the replay ends at it.
     *
     * @param visitor Receives each fact, with ids resolved
     * @return How many facts were replayed
     * @throws IOException if the log cannot be read, or holds a record whose checksum fails
     */
    public long forEach(TripleVisitor visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor must not be null");
        return forEachStaged((subject, relation, object, validFrom, validTo, statedOn, provenance)
            -> visitor.visit(subject, relation, object, validFrom, validTo, provenance));
    }

    /**
     * Replays every fact recorded so far, in the order it was staged, with the day each was
     * stated.
     *
     * <p>What a build reads: supersession orders the statements of a single-valued relation by
     * when their values began, and where a statement does not say, by the day it was made and
     * then by the order it was staged in. The order of this replay is that last key, so a caller
     * that needs it keeps it.
     *
     * @param visitor Receives each fact, with ids resolved
     * @return How many facts were replayed
     * @throws IOException if the log cannot be read, or holds a record whose checksum fails
     * @see #forEach
     */
    public long forEachStaged(StagedFactVisitor visitor) throws IOException {
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
                cursor.validFrom, cursor.validTo, cursor.statedOn, cursor.provenance);
        }
        if (cursor.tornBytes() > 0) {
            LOGGER.warn("triple log {} ends in {} unreadable byte(s) after {} fact(s); a write "
                + "was cut short, and the next open of this log cuts them off", logPath,
                cursor.tornBytes(), cursor.read);
        }
        return cursor.read;
    }

    /**
     * The one refusal both the replay and the erasure rewrite raise for damage with an intact
     * record after it.
     *
     * <h3>Why this is not a torn tail</h3>
     * <p>A write that never finished leaves damage with nothing intact after it, which the open
     * cuts off. An intact record after the damage may be one that was forced and acknowledged,
     * and the damage before it rot or a device error in a record that was too; nothing in the log
     * can say it was not.
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
    private static LogCorruptionException corrupt(Path logPath, long factsBefore) {
        return new LogCorruptionException("triple log " + logPath + " has a corrupt record at fact "
            + factsBefore + ", and an intact record after it, so this is damage rather than the "
            + "end of a write a crash cut short, and the facts after it may have been written "
            + "and acknowledged. Building from the facts before it would publish a partial graph "
            + "and report success, and erasing around it would report an obligation discharged "
            + "that had not reached them; this build refuses the log instead. Recover or "
            + "quarantine the file before restarting.");
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
        /** Where the last record that read cleanly ends; the header's end before any has. */
        private int end;
        private int subject;
        private int relation;
        private int object;
        private int validFrom;
        private int validTo;
        private int statedOn;
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
            this.end = buffer.position();
        }

        /**
         * How many bytes at the end were not read as records: a partial record, or damage with
         * nothing intact after it.
         *
         * @return The count, meaningful once {@link #next} has returned false
         */
        long tornBytes() {
            return all.length - end;
        }

        /**
         * Decodes the next record.
         *
         * @return false once the log is exhausted, or ends in damage with nothing intact after it
         * @throws LogCorruptionException if a record's checksum does not match and an intact
         *                                record follows it
         */
        boolean next() throws IOException {
            if (buffer.remaining() < RECORD_BYTES) {
                return false;
            }
            start = buffer.position();
            if (!recordStartsAt(all, start)) {
                if (TailIntegrity.firstIntactRecordFrom(all, start + 1,
                        GraphStagingStore::recordStartsAt) >= 0) {
                    throw corrupt(path, read);
                }
                return false;
            }
            subject = buffer.getInt();
            relation = buffer.getInt();
            object = buffer.getInt();
            validFrom = buffer.getInt();
            validTo = buffer.getInt();
            statedOn = buffer.getInt();
            byte sourceType = buffer.get();
            byte confidence = buffer.get();
            short extractorVersion = buffer.getShort();
            long sourceId = buffer.getLong();
            buffer.getInt();
            provenance = new Provenance(SourceType.fromByte(sourceType), sourceId,
                Byte.toUnsignedInt(confidence), extractorVersion);
            read++;
            end = buffer.position();
            return true;
        }
    }

    /**
     * Whether a whole, checksum-clean current-version record begins at {@code offset}.
     *
     * @param contents The whole log
     * @param offset   Where to look
     * @return true when a record starts exactly there
     */
    private static boolean recordStartsAt(byte[] contents, int offset) {
        return offset >= HEADER_BYTES && wholeRecordAt(contents, offset, RECORD_BYTES);
    }

    /** As {@link #recordStartsAt}, for the version 1 framing. */
    private static boolean legacyRecordStartsAt(byte[] contents, int offset) {
        return offset >= HEADER_BYTES && wholeRecordAt(contents, offset, LEGACY_RECORD_BYTES);
    }

    /**
     * Whether {@code width} bytes lie at {@code offset} and end in the checksum of those before.
     *
     * @param bytes  Any stretch of a log
     * @param offset Where the record would begin in it
     * @param width  Bytes per record, checksum included
     * @return true when the record is whole and its checksum matches
     */
    private static boolean wholeRecordAt(byte[] bytes, int offset, int width) {
        if (offset < 0 || offset + width > bytes.length) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, width - Integer.BYTES);
        int stored = ByteBuffer.wrap(bytes, offset + width - Integer.BYTES, Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN).getInt();
        return stored == (int) crc.getValue();
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
     * <h3>What the published graph is told</h3>
     * <p>Anything this removes is a change the graph published from staging does not have yet,
     * and is recorded as one: see "Changes the published graph does not have". The marker is
     * forced before the logs are rewritten, because the rewrite is durable the moment it is
     * renamed into place and a crash after it must not find a graph that looks current. A pass
     * that removes nothing takes back a marker it created.</p>
     *
     * @param erasure What the requests cover
     * @return What was removed, and what could not be judged
     * @throws IOException if a log cannot be read or rewritten, or the marker cannot be written
     * @throws NullPointerException if {@code erasure} is null
     */
    public EraseResult erase(StagedErasure erasure) throws IOException {
        Objects.requireNonNull(erasure, "erasure must not be null");

        synchronized (appendLock) {
            requireOpen();
            boolean created = ensureMarkerDurably();
            EraseResult result = eraseLocked(erasure);
            if (result.removed() > 0) {
                recordChange();
            } else if (created) {
                removeMarker();
            }
            return result;
        }
    }

    /**
     * As {@link #erase}, for an erasure that removes the same records from the published graph
     * in the same operation.
     *
     * <p>An erasure compaction rewrites the served store and rebuilds the indexes over it from
     * the staging this has just erased, so what it removes is not something the published graph
     * lacks, and recording it as a change would rebuild a graph the compaction has already
     * brought into line. A change recorded before it stays recorded: this neither adds nor
     * clears one.</p>
     *
     * @param erasure What the requests cover
     * @return What was removed, and what could not be judged
     * @throws IOException if a log cannot be read or rewritten
     * @throws NullPointerException if {@code erasure} is null
     */
    public EraseResult eraseAlongsidePublished(StagedErasure erasure) throws IOException {
        Objects.requireNonNull(erasure, "erasure must not be null");

        synchronized (appendLock) {
            requireOpen();
            return eraseLocked(erasure);
        }
    }

    /**
     * Rewrites the three logs without what an erasure covers, holding {@link #appendLock}.
     *
     * @param erasure What the requests cover
     * @return What was removed
     * @throws IOException if a log cannot be read or rewritten
     */
    private EraseResult eraseLocked(StagedErasure erasure) throws IOException {
        flush();
        long facts = rewriteTriples(erasure);
        EntityTextLog.Erased snippets = text.erase(erasure::erasesSnippet);
        int labels = entities.tombstone(erasure::erasesLabel);
        return new EraseResult(facts, snippets.removed(), labels, snippets.ofUnknownOrigin());
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
        int version = versionOf(buffer, path, what);
        if (version != VERSION) {
            throw new IOException("unsupported " + what + " version " + version + ": " + path);
        }
        buffer.getInt();
        return buffer;
    }

    /**
     * Reads the magic and the version from the start of a log, leaving the buffer after them.
     *
     * @param header The log's first bytes, at least {@value #HEADER_BYTES} of them
     * @param path   The log, named in any failure
     * @param what   What the log is, for the failure
     * @return The version
     * @throws IOException if the file is not a triple log, or is one of a version this build
     *                     does not know
     */
    private static int versionOf(ByteBuffer header, Path path, String what) throws IOException {
        long magic = header.getLong();
        if (magic != MAGIC) {
            throw new IOException("not a " + what + " (magic 0x" + Long.toHexString(magic)
                + "): " + path);
        }
        int version = header.getInt();
        if (version != VERSION && version != VERSION_WITHOUT_STATED_ON) {
            throw new IOException("unsupported " + what + " version " + version + ": " + path);
        }
        return version;
    }

    /**
     * The width of the records in a log of {@code version}.
     *
     * @param version A version {@link #versionOf} accepted
     * @return Bytes per record
     */
    private static int recordBytes(int version) {
        return version == VERSION ? RECORD_BYTES : LEGACY_RECORD_BYTES;
    }

    /**
     * How many whole facts a triple log on disk holds, in whichever version it was written.
     *
     * <p>For a caller that has to answer without opening the store - opening one upgrades an
     * earlier version in place, and a status read must not write. Reads the sixteen-byte header
     * for the version, since the record width depends on it, and measures the file. A trailing
     * partial record is not counted: it is a process killed mid-append, and the next open trims
     * it. Whole records are counted without being read, so a crash that left some of them
     * unreadable is counted high until the next open cuts them off.</p>
     *
     * @param log The triple log
     * @return The whole records it holds, or zero if it does not exist or has no complete header
     * @throws IOException if the file cannot be read, is not a triple log, or is of an unknown
     *                     version
     */
    public static long factsOnDisk(Path log) throws IOException {
        Objects.requireNonNull(log, "log must not be null");
        if (!Files.isRegularFile(log)) {
            return 0;
        }
        byte[] header = new byte[HEADER_BYTES];
        long size;
        try (var in = Files.newInputStream(log)) {
            if (in.readNBytes(header, 0, HEADER_BYTES) < HEADER_BYTES) {
                return 0;
            }
            size = Files.size(log);
        }
        int version = versionOf(ByteBuffer.wrap(header), log, "triple log");
        return (size - HEADER_BYTES) / recordBytes(version);
    }

    /**
     * Rewrites a log written before records carried the day they were stated, once.
     *
     * <p>Every intact record is carried across, in order and otherwise byte for byte, with
     * {@link TemporalFilter#UNDATED} as its stated-on day and a checksum over the new layout.
     * Damage with nothing intact after it is dropped, as opening cuts it off in any version.
     * Damage with an intact record after it refuses the upgrade and leaves the file as it was,
     * for the reason {@link #corrupt} gives: the facts after it may have been acknowledged, and
     * neither dropping them nor carrying damage into a freshly checksummed record is honest.</p>
     *
     * <p>Swapped in by a forced rename, so a crash leaves either the old log whole or the new one
     * whole, and the next open finishes the job or finds nothing to do.</p>
     *
     * @param logPath The triple log
     * @throws IOException if it cannot be read, holds a corrupt record, or cannot be rewritten
     */
    private static void upgradeIfEarlierVersion(Path logPath) throws IOException {
        if (!Files.exists(logPath) || Files.size(logPath) < HEADER_BYTES) {
            return;
        }
        byte[] all = Files.readAllBytes(logPath);
        if (versionOf(ByteBuffer.wrap(all), logPath, "triple log") != VERSION_WITHOUT_STATED_ON) {
            return;
        }

        Path temp = logPath.resolveSibling(logPath.getFileName() + REWRITE_SUFFIX);
        Files.deleteIfExists(temp);
        long upgraded;
        try {
            try (DataOutputStream rewritten = openLog(temp)) {
                byte[] record = new byte[RECORD_BYTES];
                ByteBuffer view = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
                upgraded = walkLegacy(logPath, all, start -> {
                    System.arraycopy(all, start, record, 0, INTERVAL_END);
                    view.putInt(INTERVAL_END, TemporalFilter.UNDATED);
                    System.arraycopy(all, start + INTERVAL_END, record,
                        INTERVAL_END + Integer.BYTES, PROVENANCE_BYTES);
                    sealRecord(record);
                    rewritten.write(record);
                });
            }
            Durability.DEVICE.replaceDurably(temp, logPath);
        } finally {
            Files.deleteIfExists(temp);
        }
        LOGGER.warn("upgraded {} to triple log version {}: {} fact(s) carried across with no "
            + "stated day, so the order they were staged in is what orders them for supersession",
            logPath, VERSION, upgraded);
    }

    /** Receives the offset of each whole, checksum-clean record of a version 1 log, in order. */
    @FunctionalInterface
    private interface LegacyRecordVisitor {

        /**
         * @param start Where the record begins in the log as read
         * @throws IOException if the visitor's own write fails
         */
        void visit(int start) throws IOException;
    }

    /**
     * Walks every intact record of a version 1 log, refusing damage with an intact record after
     * it.
     *
     * <p>Shared by the upgrade, which carries each record across as it is reached, and by
     * {@link #inspectTripleLog}, which counts them, so the two cannot disagree about which
     * records an old log holds. The walk ends at damage with nothing intact after it - a partial
     * record, or whole ones a crash left unreadable - which the upgrade drops, as opening cuts
     * it off in any version.</p>
     *
     * @param logPath The log, named in any failure
     * @param all     The whole log as read
     * @param visitor Receives each record
     * @return How many intact records precede the end, or the damage the walk stopped at
     * @throws IOException if the visitor fails
     * @throws LogCorruptionException if a record's checksum fails with an intact record after it;
     *                                see {@link #corrupt}
     */
    private static long walkLegacy(Path logPath, byte[] all, LegacyRecordVisitor visitor)
            throws IOException {
        long read = 0;
        for (int start = HEADER_BYTES; start + LEGACY_RECORD_BYTES <= all.length;
             start += LEGACY_RECORD_BYTES) {
            if (!legacyRecordStartsAt(all, start)) {
                if (TailIntegrity.firstIntactRecordFrom(all, start + 1,
                        GraphStagingStore::legacyRecordStartsAt) >= 0) {
                    throw corrupt(logPath, read);
                }
                break;
            }
            visitor.visit(start);
            read++;
        }
        return read;
    }

    /**
     * Reads what a triple log holds without opening the store.
     *
     * <h3>Why this is not {@link #open}</h3>
     * <p>Opening a store creates its directory and every missing file in it, rewrites a version
     * 1 log in the current version, trims a partial trailing record and takes append handles -
     * the right things for a node taking the store over, and writes an offline checker must not
     * make. This reads the one file and walks its records with the code the node does: a current
     * log through the cursor every build's replay and every erasure's rewrite read it with, and a
     * version 1 log through the walk its upgrade copies records with. It writes nothing, and a
     * version 1 log is reported at the version it is, since that is what is on disk.</p>
     *
     * <h3>What it refuses, and what it reports</h3>
     * <p>A record whose checksum fails with an intact record somewhere after it is refused, as a
     * {@link LogCorruptionException}: the node refuses such a log in its build and its erasure
     * (and, at version 1, in its upgrade, so the store does not open at all). See
     * {@link #corrupt}. What is reported rather than refused is damage with nothing intact after
     * it - a partial record, or whole records a crash left unreadable - which the open cuts off;
     * see {@link LogInspection#hasTornTail()}. So is a file shorter than its header whose bytes
     * begin the header this build writes: a creation cut short, which the open starts
     * afresh.</p>
     *
     * @param log The triple log
     * @return How many facts matched their checksums, and where they end
     * @throws IOException if the file is absent, too short to be even a torn triple log, not a
     *                     triple log of a version this build reads, or cannot be read
     * @throws LogCorruptionException if a record's checksum fails with an intact record after it
     * @throws NullPointerException if {@code log} is null
     */
    public static LogInspection inspectTripleLog(Path log) throws IOException {
        Objects.requireNonNull(log, "log must not be null");
        byte[] all = Files.readAllBytes(log);
        if (all.length < HEADER_BYTES) {
            LogHeader.requireTornCreation(log, "triple log", all, MAGIC, VERSION);
            return LogHeader.tornCreation(VERSION, all);
        }
        int version = versionOf(ByteBuffer.wrap(all), log, "triple log");
        long records;
        if (version == VERSION_WITHOUT_STATED_ON) {
            records = walkLegacy(log, all, start -> {
                // Counted only; nothing is carried anywhere.
            });
        } else {
            Cursor cursor = new Cursor(all, log);
            while (cursor.next()) {
                // Walked only for the checksums; every fact is decoded and dropped.
            }
            records = cursor.read;
        }
        return new LogInspection(version, records,
            HEADER_BYTES + records * recordBytes(version), all.length);
    }

    /**
     * Reads what one of a staging directory's label interners holds without opening it.
     *
     * <p>Judged exactly as {@link #open} judges it: the interner's own replay and tail, and -
     * when the open would give ids up - the highest id the staged facts and entity text beside it
     * refer to, together with {@code holders}. An offline checker passing the holders the node
     * passes therefore reaches the node's verdict.</p>
     *
     * @param interner {@value #ENTITY_LABELS} or {@value #RELATION_LABELS} in a staging directory
     * @param holders  What outside the directory refers to label ids
     * @return How many labels matched their checksums, and where the file stops reading cleanly
     * @throws IOException if the interner cannot be judged; see {@link LabelInterner#inspect}
     * @throws LogCorruptionException if the node would refuse the interner for damage, or for
     *                                an id it would lose that something refers to
     * @throws IllegalArgumentException if {@code interner} is not named as one of the interners
     */
    public static LogInspection inspectLabels(Path interner, ReferencedLabels holders)
            throws IOException {
        Objects.requireNonNull(interner, "interner must not be null");
        Objects.requireNonNull(holders, "holders must not be null");
        Path name = interner.getFileName();
        boolean relationLabels = name != null && RELATION_LABELS.equals(name.toString());
        if (!relationLabels && (name == null || !ENTITY_LABELS.equals(name.toString()))) {
            throw new IllegalArgumentException("not a staging label interner: " + interner);
        }
        Path directory = interner.toAbsolutePath().getParent();
        ReferencedLabels held = everythingHolding(directory, holders);
        return LabelInterner.inspect(interner, relationLabels
            ? () -> held.reach().relations()
            : () -> held.reach().entities());
    }

    /**
     * Removes the replacements rewrites a crash cut short left beside the logs, durably.
     *
     * <p>See {@link #UNFINISHED_REWRITES}. Forced afterwards, so an erasure the node goes on to
     * discharge is not discharged over a name a power loss could bring back.</p>
     *
     * @param directory The staging directory
     * @throws IOException if one cannot be removed, or the removal forced
     */
    private static void removeUnfinishedRewrites(Path directory) throws IOException {
        boolean removed = false;
        for (String name : UNFINISHED_REWRITES) {
            Path leftover = directory.resolve(name);
            if (Files.deleteIfExists(leftover)) {
                LOGGER.warn("removed {}, a rewrite of a staging log that a crash cut short; the "
                    + "log itself is whole, and what the rewrite was for is applied again if it "
                    + "is still owed", leftover);
                removed = true;
            }
        }
        if (removed) {
            Durability.DEVICE.fsyncDirectory(directory);
        }
    }

    /**
     * Everything that holds a staging directory's label ids: its own logs, and {@code holders}.
     *
     * <p>Read at most once however many interners ask, and not at all when none does.</p>
     *
     * @param directory The staging directory
     * @param holders   What outside it refers to label ids
     * @return The holders
     */
    private static ReferencedLabels everythingHolding(Path directory, ReferencedLabels holders) {
        ReferencedLabels all = ReferencedLabels.all(stagedIn(directory), holders);
        return new ReferencedLabels() {
            private ReferencedLabels.Reach reach;

            @Override
            public synchronized ReferencedLabels.Reach reach() throws IOException {
                if (reach == null) {
                    reach = all.reach();
                }
                return reach;
            }
        };
    }

    /**
     * The staged facts and entity text in a staging directory, as holders of label ids.
     *
     * <p>A log damaged other than at its tail is refused here as its own replay refuses it:
     * whether it refers to an id cannot be told from the records before the damage.</p>
     *
     * @param directory The staging directory
     * @return The holder
     */
    private static ReferencedLabels stagedIn(Path directory) {
        return () -> {
            Path text = directory.resolve(ENTITY_TEXT_LOG);
            return highestInTripleLog(directory.resolve(TRIPLE_LOG))
                .higher(new ReferencedLabels.Reach(
                    new ReferencedLabels.Reference(EntityTextLog.highestEntityId(text),
                        "the entity text in " + text),
                    ReferencedLabels.Reference.NONE));
        };
    }

    /**
     * The highest entity id and relation id any staged fact names.
     *
     * @param log The triple log; need not exist
     * @return The highest ids, held by the log
     * @throws IOException if the log is not a triple log this build reads
     * @throws LogCorruptionException if a record's checksum fails with an intact record after it
     */
    private static ReferencedLabels.Reach highestInTripleLog(Path log) throws IOException {
        if (!Files.isRegularFile(log) || Files.size(log) < HEADER_BYTES) {
            // Absent, or a creation cut short: no fact was ever staged in it. Anything else that
            // short is refused when the store opens the log.
            return ReferencedLabels.Reach.NONE;
        }
        byte[] all = Files.readAllBytes(log);
        int version = versionOf(ByteBuffer.wrap(all), log, "triple log");
        int[] highest = {-1, -1};
        if (version == VERSION_WITHOUT_STATED_ON) {
            ByteBuffer view = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            walkLegacy(log, all, start -> {
                highest[0] = Math.max(highest[0],
                    Math.max(view.getInt(start), view.getInt(start + 2 * Integer.BYTES)));
                highest[1] = Math.max(highest[1], view.getInt(start + Integer.BYTES));
            });
        } else {
            Cursor cursor = new Cursor(all, log);
            while (cursor.next()) {
                highest[0] = Math.max(highest[0], Math.max(cursor.subject, cursor.object));
                highest[1] = Math.max(highest[1], cursor.relation);
            }
        }
        String heldBy = "the staged facts in " + log;
        return new ReferencedLabels.Reach(new ReferencedLabels.Reference(highest[0], heldBy),
            new ReferencedLabels.Reference(highest[1], heldBy));
    }

    /**
     * Writes the checksum of a current-version record over everything before it.
     *
     * @param record A {@value #RECORD_BYTES}-byte record whose payload is filled in
     */
    private static void sealRecord(byte[] record) {
        CRC32 crc = new CRC32();
        crc.update(record, 0, RECORD_PAYLOAD_BYTES);
        ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(RECORD_PAYLOAD_BYTES, (int) crc.getValue());
    }

    /**
     * The id an entity label was given, assigning one if it is new.
     *
     * @param label The entity label
     * @return Its id
     * @throws IOException if a new assignment cannot be recorded
     */
    public int entityIdOf(String label) throws IOException {
        int id = entities.intern(label);
        unsynced = true;
        return id;
    }

    /**
     * The id a relation label was given, assigning one if it is new.
     *
     * @param label The relation label
     * @return Its id
     * @throws IOException if a new assignment cannot be recorded
     */
    public int relationIdOf(String label) throws IOException {
        int id = relations.intern(label);
        unsynced = true;
        return id;
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
        unsynced = true;
        // After the write, under the lock a publish clears the record under: a build that read
        // the text log before this snippet reached it sees the count move and keeps the marker.
        synchronized (appendLock) {
            recordChange();
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
     * Whether staging holds changes no published graph has, and since when.
     *
     * @param changeCount   Changes made since this store opened; see {@link #changeCount()}
     * @param pending       Whether any change is unpublished
     * @param pendingSince  When the oldest unpublished change was made, empty when none is
     * @param lastChangedAt When the newest unpublished change was made, empty when none is
     */
    public record UnpublishedChanges(long changeCount, boolean pending,
                                     Optional<Instant> pendingSince,
                                     Optional<Instant> lastChangedAt) {

        /**
         * Validates the record.
         *
         * @throws NullPointerException if an optional is null
         */
        public UnpublishedChanges {
            Objects.requireNonNull(pendingSince, "pendingSince must not be null");
            Objects.requireNonNull(lastChangedAt, "lastChangedAt must not be null");
        }
    }

    /**
     * What staging has changed that no published graph has.
     *
     * <p>Never waits for an append, a sync or an erasure rewrite.</p>
     *
     * @return The record, read together
     */
    public UnpublishedChanges unpublishedChanges() {
        synchronized (changeLock) {
            return new UnpublishedChanges(changes, pending, Optional.ofNullable(pendingSince),
                Optional.ofNullable(lastChangedAt));
        }
    }

    /**
     * How many changes this store has recorded since it opened.
     *
     * <p>A build reads this before it reads staging and hands it to {@link #markPublished(long)}
     * after it has published. Only equality with a later reading means anything: it restarts at
     * zero with every open.</p>
     *
     * @return The count
     */
    public long changeCount() {
        synchronized (changeLock) {
            return changes;
        }
    }

    /**
     * Records that a graph built from staging as it stood at {@code asOfChangeCount} has been
     * published, clearing the record of unpublished changes if nothing has changed since.
     *
     * <p>Held under the lock appends and erasures record their changes under, so a change is
     * either counted before this compares - and the marker stays - or made after it, and puts
     * the marker back.</p>
     *
     * @param asOfChangeCount {@link #changeCount()} as the build read it, before reading staging
     * @return true if the record was cleared; false if something changed while the build ran
     * @throws IOException if the marker cannot be removed; the record is then left pending
     */
    public boolean markPublished(long asOfChangeCount) throws IOException {
        synchronized (appendLock) {
            synchronized (changeLock) {
                if (changes != asOfChangeCount) {
                    return false;
                }
            }
            removeMarker();
            return true;
        }
    }

    /**
     * Records that the published graph lacks something staging holds, found by a caller rather
     * than by a change this store made - a graph older than its staging logs.
     *
     * @throws IOException if the marker cannot be written
     */
    public void markUnpublished() throws IOException {
        synchronized (appendLock) {
            requireOpen();
            ensureMarkerDurably();
            recordChange();
        }
    }

    /**
     * When the unpublished changes a staging directory records began, without opening it.
     *
     * @param directory A staging directory
     * @return When its marker was written, or empty when it has none
     */
    public static Optional<Instant> unpublishedOnDisk(Path directory) {
        return modifiedAt(directory.resolve(UNPUBLISHED_MARKER));
    }

    /**
     * When a staging directory's facts or text were last written, without opening it.
     *
     * @param directory A staging directory
     * @return The newest modification among its logs and its marker, or empty when none exists
     */
    public static Optional<Instant> lastChangedOnDisk(Path directory) {
        Instant newest = null;
        for (String name : List.of(TRIPLE_LOG, ENTITY_TEXT_LOG, UNPUBLISHED_MARKER)) {
            Optional<Instant> modified = modifiedAt(directory.resolve(name));
            if (modified.isPresent() && (newest == null || modified.get().isAfter(newest))) {
                newest = modified.get();
            }
        }
        return Optional.ofNullable(newest);
    }

    /**
     * When a file was last modified, or empty when it cannot be read.
     *
     * @param file The file
     * @return Its modification time
     */
    private static Optional<Instant> modifiedAt(Path file) {
        try {
            return Optional.of(Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Counts one change and makes sure the marker says so, holding {@link #appendLock}.
     *
     * <p>A marker that cannot be written does not fail the change, which has already been
     * made: it is logged, the change is still recorded in memory, and the next change tries
     * again.</p>
     */
    private void recordChange() {
        Instant now = clock.instant();
        synchronized (changeLock) {
            changes++;
            if (!pending) {
                pendingSince = now;
            }
            pending = true;
            lastChangedAt = now;
        }
        if (!markerOnDisk) {
            try {
                createMarker();
            } catch (IOException e) {
                LOGGER.error("could not record at {} that staging holds changes no published "
                    + "graph has; a restart before the next build will not know the graph is "
                    + "behind: {}", directory.resolve(UNPUBLISHED_MARKER), e.toString());
            }
        }
    }

    /**
     * Writes the marker and forces its name, holding {@link #appendLock}.
     *
     * @return true if this call created it
     * @throws IOException if it cannot be written or forced
     */
    private boolean ensureMarkerDurably() throws IOException {
        if (markerOnDisk) {
            return false;
        }
        createMarker();
        Durability.DEVICE.fsyncDirectory(directory);
        return true;
    }

    /**
     * Writes the marker, holding {@link #appendLock}. Its name reaches the device with the next
     * {@link #sync()}, which forces the directory.
     *
     * @throws IOException if it cannot be written
     */
    private void createMarker() throws IOException {
        try {
            Files.createFile(directory.resolve(UNPUBLISHED_MARKER));
        } catch (FileAlreadyExistsException alreadyThere) {
            // Left by a publish that could not remove it; it says what it has to.
        }
        markerOnDisk = true;
    }

    /**
     * Clears the record of unpublished changes, holding {@link #appendLock}.
     *
     * <p>Not forced: a removal a crash undoes costs one rebuild of a graph that was current,
     * never a stale graph taken for a current one.</p>
     *
     * @throws IOException if the marker cannot be removed; the record is then left pending
     */
    private void removeMarker() throws IOException {
        Files.deleteIfExists(directory.resolve(UNPUBLISHED_MARKER));
        markerOnDisk = false;
        synchronized (changeLock) {
            pending = false;
            pendingSince = null;
            lastChangedAt = null;
        }
    }

    /**
     * Hands everything staged so far to the operating system.
     *
     * <p>Enough for a build, which reads the files, and for a process that dies; not enough for
     * a machine that does. See {@link #sync()} for that.</p>
     *
     * @throws IOException if the write fails
     */
    public void flush() throws IOException {
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            out.flush();
        }
        text.flush();
    }

    /**
     * Flushes everything staged so far and forces it to the device.
     *
     * <p>What a caller needs before it records the documents these facts came from as handled;
     * see "Buffered, flushed and forced". A store with nothing appended since its last sync
     * returns without touching the device, so calling this for every resident namespace costs
     * only the ones that were written to.</p>
     *
     * <p>Held under the append lock, which is the one an erasure rewrite holds while it swaps
     * the logs, so the files forced are the ones the appends went into. Appends wait for the
     * length of the force.</p>
     *
     * <p>The label files before the triple log: a fact that survives a power loss while the
     * assignment naming its subject does not would have that id handed to a different label at
     * the next open.</p>
     *
     * @throws IOException if a buffer cannot be written or a file cannot be forced
     */
    public void sync() throws IOException {
        synchronized (appendLock) {
            if (closed || !unsynced) {
                return;
            }
            unsynced = false;
            try {
                out.flush();
                text.flush();
                force();
            } catch (IOException | RuntimeException e) {
                // Still owed: a failed force leaves the store exactly as unforced as before.
                unsynced = true;
                throw e;
            }
        }
    }

    /**
     * Forces every staging file and the directory naming them to the device.
     *
     * @throws IOException if a file cannot be forced
     */
    private void force() throws IOException {
        for (String name : List.of(ENTITY_LABELS, RELATION_LABELS, TRIPLE_LOG, ENTITY_TEXT_LOG)) {
            Path file = directory.resolve(name);
            if (Files.exists(file)) {
                Durability.DEVICE.fsyncFile(file);
            }
        }
        // The names too: a log created by this store's open is a directory entry, and a file whose
        // contents are on the platter under a name that is not is a file the next open cannot find.
        Durability.DEVICE.fsyncDirectory(directory);
    }

    /**
     * Flushes, forces and closes.
     *
     * <p>Forced as well as flushed, because nothing forces a store after it is closed: an
     * evicted namespace's staged facts would otherwise sit in the page cache with the registry
     * already saying their documents were extracted. Every file is closed whether or not the
     * force succeeds, and the first failure is what is thrown.</p>
     *
     * @throws IOException if a buffer cannot be written, a file cannot be closed, or the files
     *                     cannot be forced
     */
    @Override
    public void close() throws IOException {
        boolean force;
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            closed = true;
            force = unsynced;
            unsynced = false;
            out.flush();
            out.close();
        }
        IOException failure = null;
        for (AutoCloseable closeable : List.<AutoCloseable>of(text, entities, relations)) {
            try {
                closeable.close();
            } catch (Exception e) {
                failure = firstOf(failure, e);
            }
        }
        if (force) {
            try {
                force();
            } catch (IOException e) {
                failure = firstOf(failure, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Keeps the first failure and suppresses the rest onto it.
     *
     * @param first The failure kept so far, or null
     * @param next  Another one
     * @return The failure to throw
     */
    private static IOException firstOf(IOException first, Exception next) {
        if (first == null) {
            return next instanceof IOException io ? io : new IOException(next);
        }
        first.addSuppressed(next);
        return first;
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
     * Cuts off what a crash left unreadable at the end of the log, and reports how many records
     * remain.
     *
     * <h3>What is cut</h3>
     * <p>Everything after the last record that matches its checksum: a partial record, and whole
     * records whose bytes never reached the device. Appends are forced only by {@link #sync()},
     * and a crash before a force finished can leave any record written since the last one that
     * did as zeros or garbage of full length, because the pages behind it are written back in no
     * particular order. None of those records was acknowledged: the documents their facts came
     * from are recorded as handled only once a sync has returned, and are extracted again. Left
     * in place, the next append would put every later fact behind damage the replay refuses.</p>
     *
     * <h3>What is not</h3>
     * <p>A record that matches its checksum is never cut, and neither is anything before it. So a
     * log with an intact record anywhere after its first damage - aligned or not, found by a scan
     * over byte offsets - is left exactly as it is, for the replay to refuse. Rot in the middle of
     * the log, and the record a write that failed part way left with appends after it, both look
     * like that, and cutting either would destroy facts that may have been acknowledged.</p>
     *
     * <p>Read from the end backwards, so an intact log costs the read of its last record rather
     * than of the whole file.</p>
     *
     * @param logPath The triple log, at the current version
     * @return How many records the log holds once the open is done with it
     * @throws IOException if the file cannot be read, trimmed or forced
     */
    private static long trimTornTail(Path logPath) throws IOException {
        if (!Files.exists(logPath)) {
            return 0;
        }
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            long size = channel.size();
            if (size < HEADER_BYTES) {
                return 0;
            }
            long whole = HEADER_BYTES + (size - HEADER_BYTES) / RECORD_BYTES * RECORD_BYTES;
            long intact = endOfLastIntactRecord(channel, whole);
            if (intact == size) {
                return (size - HEADER_BYTES) / RECORD_BYTES;
            }

            // The first byte of the tail is the start of a record that failed its checksum, or of
            // a partial one, so an intact record that follows it starts at 1 or later.
            byte[] tail = readAt(channel, intact, Math.toIntExact(size - intact));
            int following = TailIntegrity.firstIntactRecordFrom(tail, 1,
                (bytes, offset) -> wholeRecordAt(bytes, offset, RECORD_BYTES));
            if (following >= 0) {
                LOGGER.error("triple log {} holds an unreadable record at byte {} and an intact "
                    + "one after it, at byte {}; left as it is, and every build and erasure will "
                    + "refuse it until it is recovered", logPath, intact, intact + following);
                return (whole - HEADER_BYTES) / RECORD_BYTES;
            }

            channel.truncate(intact);
            // Forced before anything is appended: records written after a cut the device never
            // saw could be read back behind the garbage the cut removed.
            channel.force(true);
            LOGGER.warn("trimming {} from {} to {} bytes: {} whole record(s) and {} byte(s) of a "
                + "partial one at the end did not match their checksums, with nothing intact "
                + "after them - facts a crash caught before they were forced", logPath, size,
                intact, (whole - intact) / RECORD_BYTES, size - whole);
            return (intact - HEADER_BYTES) / RECORD_BYTES;
        }
    }

    /** Records read per step of the backward scan. */
    private static final int RECORDS_PER_READ = 1024;

    /**
     * Where the last whole record that matches its checksum ends, scanning back from the end.
     *
     * @param channel The log
     * @param whole   Where its whole records end
     * @return The end of that record, or the end of the header when none matches
     * @throws IOException if the log cannot be read
     */
    private static long endOfLastIntactRecord(FileChannel channel, long whole)
            throws IOException {
        long end = whole;
        while (end > HEADER_BYTES) {
            int records = (int) Math.min(RECORDS_PER_READ, (end - HEADER_BYTES) / RECORD_BYTES);
            long from = end - (long) records * RECORD_BYTES;
            byte[] chunk = readAt(channel, from, records * RECORD_BYTES);
            for (int record = records - 1; record >= 0; record--) {
                if (wholeRecordAt(chunk, record * RECORD_BYTES, RECORD_BYTES)) {
                    return from + (long) (record + 1) * RECORD_BYTES;
                }
            }
            end = from;
        }
        return HEADER_BYTES;
    }

    /**
     * Reads {@code length} bytes of a file at {@code position}.
     *
     * @param channel  The file
     * @param position Where to start
     * @param length   How many bytes, all of which lie within the file
     * @return The bytes
     * @throws IOException if they cannot be read
     */
    private static byte[] readAt(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("unexpected end of file reading " + length + " bytes at "
                    + position);
            }
        }
        return buffer.array();
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

    /** Receives each fact of a {@link #forEachStaged} replay, with its ids resolved. */
    @FunctionalInterface
    public interface StagedFactVisitor {

        /**
         * @param subject    subject entity id
         * @param relation   relation id
         * @param object     object entity id
         * @param validFrom  first day the fact held, or {@link TemporalFilter#UNDATED}
         * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN}
         * @param statedOn   the day the statement was made, or {@link TemporalFilter#UNDATED}
         *                   for a fact carried across from a log that did not record it
         * @param provenance where the fact came from
         */
        void visit(int subject, int relation, int object, int validFrom, int validTo,
                   int statedOn, Provenance provenance);
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
