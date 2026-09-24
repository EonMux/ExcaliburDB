package com.eonmux.excalibur.graph.erasure;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogHeader;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.file.TailIntegrity;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable record of erasure requests that have been accepted but not yet applied to the store.
 *
 * <h2>Why this file has to exist</h2>
 * <p>{@link EdgeStoreCompactor} can remove erased edges, but only if something remembers which
 * removals are owed. Accepting a request in memory and compacting later means a restart in between
 * drops the obligation silently: nothing is corrupt, nothing logs an error, and the data the
 * request was meant to destroy stays on disk indefinitely. An erasure obligation carries a deadline
 * that survives the process, so the record of it has to as well.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * [magic : int64][version : int32][reserved : int32]
 * repeated:
 *   [length : int32][body : length bytes][crc32 of the body : int32]
 *
 * submit body:      [0x01][id : int64][epochSecond : int64][nano : int32][scope]
 * complete body:    [0x02][count : int32][id : int64] * count
 * completed-at body:[0x03][epochSecond : int64][nano : int32][count : int32][id : int64] * count
 *
 * scope: [0x01][entityId : int64]
 *      | [0x02][sourceType ordinal : int8][sourceId : int64]
 *      | [0x03][extractorVersion : int32]
 * </pre>
 *
 * <p>Append-only and replayed to the end on open, exactly like
 * {@link com.eonmux.excalibur.graph.staging.LabelInterner}: a record whose length is implausible, whose
 * body runs past the end of the file, or whose checksum does not match ends the replay. When
 * nothing readable follows, that is a process killed mid-append; the records before it are still
 * precisely what was promised, and failing the open instead would leave an operator with a graph
 * that will not start and a compliance deadline running.</p>
 *
 * <p>When something readable does follow, the file is not torn but damaged, and the two must not
 * be confused. {@link #submit} writes the body and then flushes, so a flush that fails for a
 * reason the file outlives leaves a partial record; the next submit appends past it and hands
 * back an id, which is a promise that the erasure was accepted. Replaying to the damage and
 * truncating there would revoke every one of those promises at once, leaving nothing outstanding
 * and nothing logged. That case fails the open instead - see
 * {@link com.eonmux.excalibur.graph.file.TailIntegrity}.</p>
 *
 * <p>Completion is recorded by appending a record rather than by rewriting the file. Rewriting
 * would put every outstanding request through a window in which the file is neither the old one nor
 * the new one, and a crash inside that window is exactly the case this class exists to survive.</p>
 *
 * <h2>What a completed request leaves behind</h2>
 * <p>A completion carries the time it was recorded, so {@link #status} can say not only that a
 * request was discharged but when - which is what a caller under an obligation reports on. What a
 * request is about is only ever an id: an entity id, a source's provenance id or an extractor
 * version. The name an entity was resolved from is never written here, before or after the
 * request is discharged, so reading a status back cannot return the thing that was erased.
 * The {@value #RETAINED_COMPLETIONS} most recently completed requests keep their scope and
 * timestamps in memory; see {@link #RETAINED_COMPLETIONS}.</p>
 *
 * <h2>What a valid checksum obliges us to understand</h2>
 * <p>Tolerance stops at the torn tail. A record that checksums correctly but carries a record type,
 * scope discriminator or source type this build does not know is not a truncated write - it is a
 * file written by a newer build, and guessing at its meaning would erase edges the request never
 * named or skip edges it did. Those open with an {@link IOException} instead.</p>
 *
 * <h2>Durability</h2>
 * <p>{@link #submit} forces the record to disk before returning the id, so an id a caller has been
 * given is an obligation that has survived power loss. {@link #complete} only flushes: losing a
 * completion costs a repeated erasure on the next start, and erasure is idempotent, whereas losing
 * a submission costs the erasure itself.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Every operation takes {@code appendLock}, because a request's id is
 * decided by the same critical section that writes the record fixing it, and the in-memory view of
 * what is outstanding must not be read while a record that changes it is half-written.</p>
 *
 * @author ExcaliburDB
 */
public final class ErasureLog implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErasureLog.class);

    /** Identifies the file as an ExcaliburDB erasure log: ASCII {@code EXCLERAS}. */
    public static final long MAGIC = 0x4558434C45524153L;

    /** Format version written by this build. */
    public static final int VERSION = 1;

    /** Bytes before the first record. */
    public static final int HEADER_BYTES = 16;

    /**
     * Longest record body this log will read or write.
     *
     * <p>A bound the replay can trust before it allocates. Without one, a torn length field reading
     * as two gigabytes would be answered with a two gigabyte allocation rather than with the
     * "stop here, the tail is torn" that it means.
     */
    public static final int MAX_RECORD_BYTES = 1 << 16;

    /** First id handed out, so that zero is never a request id and cannot be mistaken for one. */
    private static final long FIRST_ID = 1L;

    private static final byte RECORD_SUBMIT = 0x01;

    /** A completion as written before completions carried a time; read, never written. */
    private static final byte RECORD_COMPLETE = 0x02;

    /** A completion and when it was recorded. */
    private static final byte RECORD_COMPLETE_AT = 0x03;

    /**
     * Completed requests whose scope and timestamps are kept for {@link #status}.
     *
     * <p>Bounded because the log is append-only and never forgets a completion, so what is
     * remembered about completed requests would otherwise grow with every erasure the namespace
     * ever discharged. The most recently completed are kept; an older one is still known to be
     * discharged - its id is below the next one to be handed out and nothing owes it - but is
     * reported without its scope or times.</p>
     */
    public static final int RETAINED_COMPLETIONS = 1024;

    private static final byte SCOPE_ENTITY = 0x01;
    private static final byte SCOPE_SOURCE = 0x02;
    private static final byte SCOPE_EXTRACTOR_VERSION = 0x03;

    /**
     * Ids per completion record.
     *
     * <p>Chosen so a full record stays well inside {@link #MAX_RECORD_BYTES}. Splitting a large
     * batch also bounds what a torn tail costs: only the last chunk is lost, and the completions
     * before it still stop their requests being re-applied.
     */
    private static final int COMPLETIONS_PER_RECORD = 4096;

    /** Stands in for the file name while probing candidate offsets, whose errors are discarded. */
    private static final Path SCANNING = Path.of("<scan>");

    private final Path path;
    private final FileChannel channel;
    private final DataOutputStream out;
    private final Object appendLock = new Object();

    /** Outstanding requests in id order. Guarded by {@link #appendLock}. */
    private final Map<Long, ErasureRequest> outstanding = new LinkedHashMap<>();

    /**
     * The most recently completed requests, oldest completion first, at most
     * {@link #RETAINED_COMPLETIONS}. Guarded by {@link #appendLock}.
     */
    private final Map<Long, RequestStatus> completed = retainedCompletions();

    /** Next id to hand out. Guarded by {@link #appendLock}. */
    private long nextId;

    private boolean closed;

    private ErasureLog(Path path, FileChannel channel, DataOutputStream out, long nextId) {
        this.path = path;
        this.channel = channel;
        this.out = out;
        this.nextId = nextId;
    }

    /**
     * Opens the log at {@code file}, replaying every request a previous run recorded.
     *
     * <p>A torn tail is truncated away before the file is opened for appending. Leaving it in place
     * and appending past it is not survivable for a log that is read back: the next open stops at
     * the same torn record, so every request appended after it would be invisible - a submitted
     * erasure silently forgotten by a file that looks perfectly healthy. Truncating costs the
     * partial record, which was never
     * acknowledged to anyone because {@link #submit} forces before it returns.
     *
     * @param file The log file; created, with its parent directories, if absent
     * @return The log, holding whatever was outstanding when the last run ended
     * @throws IOException if the file is not an erasure log, was written by an unsupported version,
     *                     holds an intact record this build cannot interpret, or cannot be opened
     * @throws NullPointerException if {@code file} is null
     */
    public static ErasureLog open(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Replay replay = replay(file);
        boolean exists = Files.exists(file);
        boolean shortOfHeader = exists && Files.size(file) < HEADER_BYTES;

        // A file too short to hold a header is either this log killed inside its own header write,
        // or it is something else entirely. Overwriting it because it is short is how a mistyped
        // path destroys whatever was actually there - and destroying the wrong thing is the exact
        // failure this class exists to make impossible. Only bytes that are a prefix of this log's
        // own header are treated as a torn header; see LogHeader.
        if (shortOfHeader) {
            LogHeader.requireTornCreation(file, "erasure log", Files.readAllBytes(file), MAGIC,
                VERSION);
        }

        boolean fresh = !exists || shortOfHeader;
        long keep = fresh ? 0L : replay.validBytes();
        if (Files.exists(file) && Files.size(file) > keep) {
            // Only a tail may be dropped. Records following damage were written by a submit that
            // appended past it and returned an id, so whoever asked for those erasures was told
            // they were accepted; truncating to the damage would discharge them by forgetting
            // them, leaving no outstanding count and no error to notice.
            if (!fresh) {
                TailIntegrity.requireTornTail(file, "erasure log", replay.contents(), keep,
                    ErasureLog::recordStartsAt);
            }
            long torn = Files.size(file) - keep;
            try (FileChannel truncating = FileChannel.open(file, StandardOpenOption.WRITE)) {
                truncating.truncate(keep);
                truncating.force(true);
            }
            LOGGER.warn("erasure log {} ended in {} torn bytes; dropped them and kept the {} "
                + "outstanding requests before them", file, torn, replay.outstanding().size());
        }

        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(Channels.newOutputStream(channel)));
        if (fresh) {
            out.writeLong(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(0);
            out.flush();
            channel.force(true);
        }

        ErasureLog log = new ErasureLog(file, channel, out, replay.nextId());
        log.outstanding.putAll(replay.outstanding());
        log.completed.putAll(replay.completed());
        LOGGER.debug("opened erasure log {} with {} outstanding requests",
            file, log.outstanding.size());
        return log;
    }

    /**
     * What a log owes, as read from the file without taking it over.
     *
     * <p>Two numbers, because two questions decide whether a sweep should spend anything on a
     * namespace: is anything owed at all, and has the longest-waiting obligation passed the
     * deadline the deployment promised. Neither needs the store the compaction would rewrite.</p>
     *
     * @param count             How many requests are outstanding
     * @param oldestRequestedAt When the longest-waiting of them arrived, empty when none is
     */
    public record Pending(int count, Optional<Instant> oldestRequestedAt) {

        /** What a log that was never written, or that owes nothing, reports. */
        public static final Pending NONE = new Pending(0, Optional.empty());

        public Pending {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative, got " + count);
            }
            Objects.requireNonNull(oldestRequestedAt, "oldestRequestedAt must not be null");
            if (count == 0 && oldestRequestedAt.isPresent()) {
                throw new IllegalArgumentException(
                    "nothing is outstanding, so nothing can be the oldest outstanding request");
            }
            if (count > 0 && oldestRequestedAt.isEmpty()) {
                throw new IllegalArgumentException(
                    count + " request(s) are outstanding, so one of them is the oldest");
            }
        }

        /**
         * Whether the oldest outstanding request has waited out {@code maxLatency}.
         *
         * <p>The same test {@link CompactionPolicy#standard} applies to its
         * {@code oldestOutstanding}, and deliberately only that one. The policy's other two
         * conditions - the tombstone ratio and the segment count - are questions about a store,
         * and a caller holding one of these has not opened one. This is the condition that is an
         * obligation rather than an optimisation, and it is the one that can be answered from a
         * small append-only file.</p>
         *
         * @param now        The clock the wait is measured against
         * @param maxLatency How long an obligation may remain unapplied
         * @return true when something is outstanding and its deadline has arrived
         */
        public boolean dueAt(Instant now, Duration maxLatency) {
            Objects.requireNonNull(now, "now must not be null");
            Objects.requireNonNull(maxLatency, "maxLatency must not be null");
            return oldestRequestedAt
                .map(requestedAt -> !Duration.between(requestedAt, now).minus(maxLatency)
                    .isNegative())
                .orElse(false);
        }
    }

    /**
     * Where one request stands.
     *
     * <p>Two states, because the log records two events. A request is pending from the moment
     * {@link #submit} returns its id until a compaction has rewritten what it names out of the
     * store and recorded so; from then on it is discharged. There is no state in between worth
     * reporting: the log is only told of a completion after the erasure has actually happened.</p>
     */
    public enum State {
        /** Accepted and durable, and not yet applied. */
        PENDING,
        /** Applied, and recorded as such. */
        DISCHARGED
    }

    /**
     * What the log knows about one request.
     *
     * <p>Scope and timestamps are absent only for a request completed so long ago that it has
     * fallen out of the {@value #RETAINED_COMPLETIONS} completions kept in memory, or - for
     * {@code completedAt} alone - one completed by a build that did not yet record when.</p>
     *
     * @param id          The request id
     * @param state       Whether it is still owed
     * @param scope       What it erases, when known
     * @param requestedAt When it was accepted, when known
     * @param completedAt When its completion was recorded, when known; always empty while pending
     */
    public record RequestStatus(long id, State state, Optional<ErasureScope> scope,
                                Optional<Instant> requestedAt, Optional<Instant> completedAt) {

        /**
         * @throws NullPointerException     if any part is null
         * @throws IllegalArgumentException if a pending request carries a completion time
         */
        public RequestStatus {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            Objects.requireNonNull(completedAt, "completedAt must not be null");
            if (state == State.PENDING && completedAt.isPresent()) {
                throw new IllegalArgumentException("a pending request has not been completed");
            }
        }

        /** A request still owed. */
        static RequestStatus pending(ErasureRequest request) {
            return new RequestStatus(request.id(), State.PENDING, Optional.of(request.scope()),
                Optional.of(request.requestedAt()), Optional.empty());
        }

        /** A request discharged at {@code completedAt}, or at an unrecorded time when null. */
        static RequestStatus discharged(ErasureRequest request, Instant completedAt) {
            return new RequestStatus(request.id(), State.DISCHARGED,
                Optional.of(request.scope()), Optional.of(request.requestedAt()),
                Optional.ofNullable(completedAt));
        }

        /** A request known to be discharged, of which nothing else is remembered. */
        static RequestStatus dischargedLongAgo(long id) {
            return new RequestStatus(id, State.DISCHARGED, Optional.empty(), Optional.empty(),
                Optional.empty());
        }
    }

    /**
     * Where one request of a log stands, read from the file without taking it over.
     *
     * <p>For a namespace nothing has open, as {@link #inspect} is: the same single read and
     * replay, refusing everything {@link #open} refuses, and answering exactly what
     * {@link #status} would answer had the log been opened.</p>
     *
     * @param file The log file; need not exist
     * @param id   The request id
     * @return Its status, or empty when this log never handed that id out
     * @throws IOException if the file exists and is not an erasure log this build can read
     * @throws LogCorruptionException if damage has an intact record behind it
     * @throws NullPointerException if {@code file} is null
     */
    public static Optional<RequestStatus> statusOf(Path file, long id) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (Files.size(file) < HEADER_BYTES) {
            // A torn creation has handed out nothing; anything else that short is refused as
            // the open refuses it.
            inspectLog(file);
            return Optional.empty();
        }
        Replay replay = replay(file);
        TailIntegrity.requireTornTail(file, "erasure log", replay.contents(),
            replay.validBytes(), ErasureLog::recordStartsAt);
        return statusIn(id, replay.outstanding(), replay.completed(), replay.nextId());
    }

    /**
     * Where one request stands.
     *
     * @param id The request id
     * @return Its status, or empty when this log never handed that id out
     */
    public Optional<RequestStatus> status(long id) {
        synchronized (appendLock) {
            return statusIn(id, outstanding, completed, nextId);
        }
    }

    /**
     * Answers {@link #status} from a log's state, whether replayed from the file or held open.
     *
     * @param id        The request id
     * @param owed      What is outstanding
     * @param done      The retained completions
     * @param nextId    The next id the log would hand out
     * @return The status, or empty for an id never handed out
     */
    private static Optional<RequestStatus> statusIn(long id, Map<Long, ErasureRequest> owed,
                                                    Map<Long, RequestStatus> done, long nextId) {
        if (id < FIRST_ID || id >= nextId) {
            return Optional.empty();
        }
        ErasureRequest pending = owed.get(id);
        if (pending != null) {
            return Optional.of(RequestStatus.pending(pending));
        }
        // Handed out and owed by nothing, so completed. An id lost with a torn tail cannot land
        // here: submit forces before it returns an id, so the replay's next id never passes a
        // submission that was not durable.
        RequestStatus retained = done.get(id);
        return Optional.of(retained != null ? retained : RequestStatus.dischargedLongAgo(id));
    }

    /** A map of completions that forgets the oldest once it holds more than it retains. */
    private static Map<Long, RequestStatus> retainedCompletions() {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, RequestStatus> eldest) {
                return size() > RETAINED_COMPLETIONS;
            }
        };
    }

    /**
     * Reads what a log owes without opening it.
     *
     * <h3>Why this is not {@link #open}</h3>
     * <p>Opening is not a question. It truncates a torn tail, takes an append handle, and hands
     * back a resource whose lifetime somebody now owns - and for a graph tenant it is reached
     * only by opening the tenant, which is thirteen memory mappings for a store this caller has
     * no intention of reading. The sweep that enforces the erasure deadline has to ask this of
     * every namespace on disk, including the ones eviction closed precisely to reclaim those
     * mappings. So it asks the file.</p>
     *
     * <h3>What it costs</h3>
     * <p>One read of the whole log and one pass over its records: no mappings, no file handle
     * left behind, and not a byte written. The log is a fixed-size record per accepted request
     * and one per batch of completions, so it is small for any namespace whose obligations are
     * actually being discharged - and a namespace whose log has grown is exactly one this should
     * be reporting on.</p>
     *
     * <h3>What it refuses</h3>
     * <p>Everything {@link #open} refuses, on the same terms. A log written by a newer build, or
     * one damaged with intact records after the damage, throws here rather than reporting the
     * requests it happened to reach. Reporting a subset would be worse than failing: the caller
     * is deciding whether a namespace owes anything, and a short count reads as "swept clean"
     * while the obligations behind the damage stay on disk with nothing left to notice them.</p>
     *
     * @param file The log file; need not exist
     * @return What it owes, and when the longest-waiting request arrived
     * @throws IOException if the file exists and is not an erasure log this build can read
     * @throws NullPointerException if {@code file} is null
     */
    public static Pending inspect(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file) || Files.size(file) < HEADER_BYTES) {
            return Pending.NONE;
        }
        return examine(file).pending();
    }

    /**
     * What a log owes, together with what its records are, as read from the file.
     *
     * @param pending What is outstanding, exactly as {@link #inspect} reports it
     * @param log     How many records were replayed, and whether the file ends in a torn tail
     *                the open would cut off
     */
    public record Inspection(Pending pending, LogInspection log) {

        /** @throws NullPointerException if either part is null */
        public Inspection {
            Objects.requireNonNull(pending, "pending must not be null");
            Objects.requireNonNull(log, "log must not be null");
        }
    }

    /**
     * Reads a log's records without opening it, for a caller judging the file rather than
     * scheduling a sweep.
     *
     * <p>{@link #inspect} answers what is owed, which is all a sweep needs, and so says nothing
     * about a torn tail: the obligations before it stand either way. An offline checker needs the
     * rest - how many records it proved, and whether the file ends in bytes the next open cuts
     * off - because a file that will change when the node next touches it is not one it can call
     * sound. This is the same single read and the same replay, refusing everything
     * {@link #inspect} refuses, with damage that has an intact record behind it raised as a
     * {@link LogCorruptionException}.</p>
     *
     * <p>A file shorter than its header is judged as {@link #open} judges it. Bytes that are a
     * prefix of this log's own header are a header write a crash cut short, which the open
     * replaces with a fresh header; they are reported as a torn tail with nothing before it.
     * Anything else that short is refused, as the open refuses it.</p>
     *
     * @param file The log file
     * @return What it owes and what its records are
     * @throws IOException if the file is absent, too short to be even a torn one of these logs,
     *                     not an erasure log of a version this build reads, holds an intact
     *                     record this build cannot interpret, or cannot be read
     * @throws LogCorruptionException if damage has an intact record behind it
     * @throws NullPointerException if {@code file} is null
     */
    public static Inspection inspectLog(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (Files.size(file) < HEADER_BYTES) {
            byte[] contents = Files.readAllBytes(file);
            LogHeader.requireTornCreation(file, "erasure log", contents, MAGIC, VERSION);
            return new Inspection(Pending.NONE, LogHeader.tornCreation(VERSION, contents));
        }
        return examine(file);
    }

    /**
     * The highest entity id an outstanding request names, read from the file without opening it.
     *
     * <p>An outstanding entity-scoped request holds an entity id and nothing else: the name it was
     * resolved from is not recorded, because recording it would put the name the request exists
     * to destroy into one more file. So if that id were given to another label before the request
     * was applied, the request would erase the other entity. The staging store asks this before
     * it lets a label interner give up an id; see
     * {@link com.eonmux.excalibur.graph.staging.ReferencedLabels#owedIn}.</p>
     *
     * @param file The log file; need not exist
     * @return The id, or -1 when no outstanding request names an entity
     * @throws IOException if the file exists and is not an erasure log this build can read
     * @throws LogCorruptionException if damage has an intact record behind it
     * @throws NullPointerException if {@code file} is null
     */
    public static long highestEntityOwed(Path file) throws IOException {
        return entitiesOwed(file).stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    /**
     * The entity id each outstanding entity-scoped request names, read from the file without
     * opening it.
     *
     * <p>For an offline checker asking whether any of them names an id nothing was ever given,
     * which the node discharges when it next opens the namespace; see
     * {@link GraphErasureCoordinator#dischargeUnassigned}.</p>
     *
     * @param file The log file; need not exist
     * @return The ids, in the order the requests were accepted; an id named twice appears twice
     * @throws IOException if the file exists and is not an erasure log this build can read
     * @throws LogCorruptionException if damage has an intact record behind it
     * @throws NullPointerException if {@code file} is null
     */
    public static List<Long> entitiesOwed(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file)) {
            return List.of();
        }
        if (Files.size(file) < HEADER_BYTES) {
            // A torn creation owes nothing; anything else that short is refused as the open
            // refuses it.
            inspectLog(file);
            return List.of();
        }
        Replay replay = replay(file);
        TailIntegrity.requireTornTail(file, "erasure log", replay.contents(),
            replay.validBytes(), ErasureLog::recordStartsAt);
        return replay.outstanding().values().stream()
            .map(ErasureRequest::scope)
            .filter(ErasureScope.Entity.class::isInstance)
            .map(scope -> ((ErasureScope.Entity) scope).entityId())
            .toList();
    }

    /**
     * Replays a log that holds at least a header, and judges its tail as {@link #open} does.
     *
     * @param file The log file
     * @return What it owes and what its records are
     * @throws IOException if the file cannot be read or replayed
     * @throws LogCorruptionException if damage has an intact record behind it
     */
    private static Inspection examine(Path file) throws IOException {
        Replay replay = replay(file);
        // The same distinction open() draws, drawn read-only: a tail that is merely torn is the
        // ordinary outcome of a process killed mid-append and the records before it stand, while
        // records following damage were acknowledged to a caller and must not be silently
        // dropped from the count.
        TailIntegrity.requireTornTail(file, "erasure log", replay.contents(),
            replay.validBytes(), ErasureLog::recordStartsAt);

        Optional<Instant> oldest = replay.outstanding().values().stream()
            .map(ErasureRequest::requestedAt)
            .min(Instant::compareTo);
        return new Inspection(new Pending(replay.outstanding().size(), oldest),
            new LogInspection(VERSION, replay.records(), replay.validBytes(),
                replay.contents().length));
    }

    /**
     * Records a new erasure request.
     *
     * <p>The record is on disk before the id comes back. A caller that has an id may tell whoever
     * asked for the erasure that it was accepted, and that promise has to hold across a power cut
     * on the next instruction.
     *
     * @param scope       What to erase
     * @param requestedAt When the request arrived; the clock the deadline is measured from, so it
     *                    is the caller's rather than one taken here
     * @return The request id, unique for the life of the file
     * @throws IOException if the record cannot be written or forced to disk
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if the log is closed
     */
    public long submit(ErasureScope scope, Instant requestedAt) throws IOException {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");

        synchronized (appendLock) {
            requireOpen();
            long id = nextId;
            append(encodeSubmit(id, scope, requestedAt));
            out.flush();
            channel.force(true);

            // In-memory state is updated only once the record is durable, so a failed write leaves
            // the log claiming exactly what the file claims.
            nextId = id + 1;
            outstanding.put(id, new ErasureRequest(id, scope, requestedAt));
            LOGGER.info("erasure request {} accepted for {}", id, scope);
            return id;
        }
    }

    /**
     * Every request that has been submitted and not yet completed.
     *
     * @return A snapshot in id order, oldest first; empty if nothing is owed
     */
    public List<ErasureRequest> outstanding() {
        synchronized (appendLock) {
            return List.copyOf(outstanding.values());
        }
    }

    /**
     * Records that these requests have been satisfied, so a restart does not apply them again.
     *
     * <p>Appends a completion record rather than rewriting the file. Ids that are not outstanding -
     * already completed, or lost with a torn tail before they were ever replayed - are dropped
     * rather than written, because a completion for a request nobody is waiting on only grows the
     * file.
     *
     * <p>Call this only after the erasure has actually happened. A crash between the rewrite and
     * this call repeats an erasure, which removes nothing that is still there; a crash the other
     * way round loses the obligation while the data is still on disk.
     *
     * @param requestIds The ids to mark satisfied; unknown ids are ignored
     * @throws IOException if the record cannot be written
     * @throws NullPointerException if {@code requestIds} or any element is null
     * @throws IllegalStateException if the log is closed
     */
    public void complete(Collection<Long> requestIds) throws IOException {
        complete(requestIds, Instant.now());
    }

    /**
     * Records that these requests were satisfied at {@code completedAt}.
     *
     * <p>As {@link #complete(Collection)}, with the time taken from the caller's clock - the one
     * the requests' deadlines were measured against - rather than from this process's.</p>
     *
     * @param requestIds  The ids to mark satisfied; unknown ids are ignored
     * @param completedAt When the erasure they name was applied
     * @throws IOException if the record cannot be written
     * @throws NullPointerException if an argument or any element is null
     * @throws IllegalStateException if the log is closed
     */
    public void complete(Collection<Long> requestIds, Instant completedAt) throws IOException {
        Objects.requireNonNull(requestIds, "requestIds must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");

        synchronized (appendLock) {
            requireOpen();
            List<Long> known = new ArrayList<>(requestIds.size());
            for (Long id : requestIds) {
                Objects.requireNonNull(id, "requestIds must not contain null");
                if (outstanding.containsKey(id)) {
                    known.add(id);
                }
            }
            if (known.isEmpty()) {
                return;
            }

            for (int from = 0; from < known.size(); from += COMPLETIONS_PER_RECORD) {
                List<Long> chunk = known.subList(from,
                    Math.min(from + COMPLETIONS_PER_RECORD, known.size()));
                append(encodeComplete(chunk, completedAt));
                // Flushed per chunk so a failure part way through leaves whole records behind
                // rather than a body split across the buffer boundary.
                out.flush();
                for (Long id : chunk) {
                    completed.put(id, RequestStatus.discharged(outstanding.remove(id),
                        completedAt));
                }
            }
            LOGGER.info("erasure requests {} marked satisfied; {} still outstanding",
                known, outstanding.size());
        }
    }

    /**
     * How many requests are outstanding.
     *
     * @return The count, which is the size of {@link #outstanding()}
     */
    public int outstandingCount() {
        synchronized (appendLock) {
            return outstanding.size();
        }
    }

    /**
     * What this open log owes, in the shape {@link #inspect} reads from a closed one.
     *
     * <p>For a caller that has to report on namespaces some of which are open and some not, and
     * wants one answer for both: an open log is asked, because it holds the obligations in
     * memory and its file may be mid-append, and a closed one is inspected.</p>
     *
     * @return How many requests are outstanding, and when the longest-waiting arrived
     */
    public Pending pending() {
        synchronized (appendLock) {
            Optional<Instant> oldest = outstanding.values().stream()
                .map(ErasureRequest::requestedAt)
                .min(Instant::compareTo);
            return new Pending(outstanding.size(), oldest);
        }
    }

    /**
     * Forces everything written so far to disk.
     *
     * <p>Worth calling after {@link #complete}, which deliberately does not force: it turns a
     * repeated erasure after a crash into no erasure at all, at the cost of one sync.
     *
     * @throws IOException if the write or the sync fails
     */
    public void flush() throws IOException {
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            out.flush();
            channel.force(true);
        }
    }

    /**
     * Flushes and closes the log. Idempotent.
     *
     * @throws IOException if the final write fails
     */
    @Override
    public void close() throws IOException {
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            closed = true;
            out.flush();
            channel.force(true);
            out.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("erasure log is closed: " + path);
        }
    }

    /** Writes one framed record. Caller holds {@link #appendLock}. */
    private void append(byte[] body) throws IOException {
        if (body.length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("record is " + body.length + " bytes, above the "
                + MAX_RECORD_BYTES + " byte limit");
        }
        CRC32 crc = new CRC32();
        crc.update(body);
        out.writeInt(body.length);
        out.write(body);
        out.writeInt((int) crc.getValue());
    }

    private static byte[] encodeSubmit(long id, ErasureScope scope, Instant requestedAt) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        DataOutputStream body = new DataOutputStream(bytes);
        try {
            body.writeByte(RECORD_SUBMIT);
            body.writeLong(id);
            // Seconds and nanos separately rather than millis: the timestamp is what a deadline is
            // measured from, and rounding a caller's instant is not this class's decision to make.
            body.writeLong(requestedAt.getEpochSecond());
            body.writeInt(requestedAt.getNano());
            switch (scope) {
                case ErasureScope.Entity entity -> {
                    body.writeByte(SCOPE_ENTITY);
                    body.writeLong(entity.entityId());
                }
                case ErasureScope.Source source -> {
                    body.writeByte(SCOPE_SOURCE);
                    body.writeByte(source.sourceType().toByte());
                    body.writeLong(source.sourceId());
                }
                case ErasureScope.ExtractorVersion version -> {
                    body.writeByte(SCOPE_EXTRACTOR_VERSION);
                    body.writeInt(version.version());
                }
            }
            body.flush();
        } catch (IOException e) {
            // A ByteArrayOutputStream performs no IO and cannot fail. Wrapping rather than
            // declaring keeps the impossible case out of every caller's signature.
            throw new UncheckedIOException("encoding an erasure submit record failed", e);
        }
        return bytes.toByteArray();
    }

    private static byte[] encodeComplete(List<Long> ids, Instant completedAt) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(20 + ids.size() * Long.BYTES);
        DataOutputStream body = new DataOutputStream(bytes);
        try {
            body.writeByte(RECORD_COMPLETE_AT);
            body.writeLong(completedAt.getEpochSecond());
            body.writeInt(completedAt.getNano());
            body.writeInt(ids.size());
            for (Long id : ids) {
                body.writeLong(id);
            }
            body.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("encoding an erasure completion record failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * What a replay recovered.
     *
     * @param outstanding Requests submitted and not completed, in id order
     * @param completed   The most recently completed requests, as {@link #completed} keeps them
     * @param nextId      One past the highest id ever submitted, so a completed request's id is
     *                    never handed out again and an audit of the file stays unambiguous
     * @param validBytes  Length of the file up to the end of the last intact record
     * @param records     How many intact records were replayed, submissions and completions both
     * @param contents    The whole file as read, so the tail can be examined without re-reading it
     */
    private record Replay(Map<Long, ErasureRequest> outstanding,
                          Map<Long, RequestStatus> completed, long nextId, long validBytes,
                          long records, byte[] contents) {
    }

    /**
     * Whether a complete, checksum-clean record this build understands begins at {@code offset}.
     *
     * <p>The body has to parse as well as checksum. A length and checksum that agree by accident
     * are improbable but not impossible over a byte-by-byte scan, and calling such an accident
     * corruption would refuse to open a log that was merely torn.
     *
     * @param contents The whole file
     * @param offset   Where to look
     * @return true when a record starts exactly there
     */
    private static boolean recordStartsAt(byte[] contents, int offset) {
        if (offset + Integer.BYTES > contents.length) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(contents, offset, contents.length - offset);
        int length = buffer.getInt();
        if (length <= 0 || length > MAX_RECORD_BYTES
            || buffer.remaining() < length + Integer.BYTES) {
            return false;
        }
        byte[] body = new byte[length];
        buffer.get(body);
        CRC32 crc = new CRC32();
        crc.update(body);
        if (buffer.getInt() != (int) crc.getValue()) {
            return false;
        }
        try {
            applyRecord(SCANNING, body, new LinkedHashMap<>(), new LinkedHashMap<>());
            return true;
        } catch (IOException e) {
            // An intact record this build cannot interpret. Reported by the replay itself when it
            // reaches the record legitimately; here it only means the scan has not found a frame
            // it can vouch for.
            return false;
        }
    }

    /**
     * Replays the file, stopping at the first record that does not read cleanly.
     *
     * @param path The file
     * @return What survived, and where the intact records end
     * @throws IOException if the file exists but is not an erasure log this build can read
     */
    private static Replay replay(Path path) throws IOException {
        Map<Long, ErasureRequest> outstanding = new LinkedHashMap<>();
        Map<Long, RequestStatus> completed = retainedCompletions();
        if (!Files.exists(path) || Files.size(path) < HEADER_BYTES) {
            return new Replay(outstanding, completed, FIRST_ID, HEADER_BYTES, 0, new byte[0]);
        }

        byte[] contents = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(contents);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not an erasure log (magic 0x"
                + Long.toHexString(magic) + "): " + path);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported erasure log version " + version + ": " + path);
        }
        buffer.getInt();

        long nextId = FIRST_ID;
        int validBytes = HEADER_BYTES;
        long records = 0;
        while (true) {
            if (buffer.remaining() < Integer.BYTES) {
                break;
            }
            int length = buffer.getInt();
            if (length <= 0 || length > MAX_RECORD_BYTES
                || buffer.remaining() < length + Integer.BYTES) {
                break;
            }
            byte[] body = new byte[length];
            buffer.get(body);
            int stored = buffer.getInt();
            CRC32 crc = new CRC32();
            crc.update(body);
            if (stored != (int) crc.getValue()) {
                LOGGER.warn("erasure log {} has a corrupt record after {} outstanding requests; "
                    + "keeping the requests before it", path, outstanding.size());
                break;
            }

            nextId = Math.max(nextId, applyRecord(path, body, outstanding, completed));
            validBytes = buffer.position();
            records++;
        }
        return new Replay(outstanding, completed, nextId, validBytes, records, contents);
    }

    /**
     * Applies one intact record to the replay state.
     *
     * @param path        The file, for error messages
     * @param body        The record body, already checksummed
     * @param outstanding The map being rebuilt
     * @param completed   The retained completions being rebuilt
     * @return One past the id this record submitted, or {@link #FIRST_ID} for a completion
     * @throws IOException if the record is intact but this build cannot interpret it
     */
    private static long applyRecord(Path path, byte[] body,
                                    Map<Long, ErasureRequest> outstanding,
                                    Map<Long, RequestStatus> completed) throws IOException {
        ByteBuffer record = ByteBuffer.wrap(body);
        try {
            byte type = record.get();
            switch (type) {
                case RECORD_SUBMIT -> {
                    long id = record.getLong();
                    Instant requestedAt = Instant.ofEpochSecond(record.getLong(), record.getInt());
                    outstanding.put(id,
                        new ErasureRequest(id, decodeScope(path, record), requestedAt));
                    return id + 1;
                }
                case RECORD_COMPLETE -> {
                    applyCompletions(record, null, outstanding, completed);
                    return FIRST_ID;
                }
                case RECORD_COMPLETE_AT -> {
                    Instant completedAt = Instant.ofEpochSecond(record.getLong(), record.getInt());
                    applyCompletions(record, completedAt, outstanding, completed);
                    return FIRST_ID;
                }
                default -> throw new IOException("erasure log " + path + " holds an intact record "
                    + "of unknown type " + type + "; it was written by a newer build and cannot "
                    + "be applied safely");
            }
        } catch (BufferUnderflowException e) {
            // A body shorter than its own type says, yet checksumming correctly. Not reachable by
            // truncation, which breaks the checksum, so this is a hand-edited file; reported as a
            // bad log rather than escaping the replay as a raw runtime exception.
            throw new IOException("erasure log " + path + " holds a record shorter than its own "
                + "type requires", e);
        }
    }

    /**
     * Moves the requests a completion record names from outstanding to completed.
     *
     * @param record      The record, positioned at its count
     * @param completedAt When the completion was recorded, or null for a record that did not say
     * @param outstanding The requests still owed
     * @param completed   The retained completions
     */
    private static void applyCompletions(ByteBuffer record, Instant completedAt,
                                         Map<Long, ErasureRequest> outstanding,
                                         Map<Long, RequestStatus> completed) {
        int count = record.getInt();
        for (int i = 0; i < count; i++) {
            ErasureRequest request = outstanding.remove(record.getLong());
            if (request != null) {
                completed.put(request.id(), RequestStatus.discharged(request, completedAt));
            }
        }
    }

    private static ErasureScope decodeScope(Path path, ByteBuffer record) throws IOException {
        byte discriminator = record.get();
        return switch (discriminator) {
            case SCOPE_ENTITY -> new ErasureScope.Entity(record.getLong());
            case SCOPE_SOURCE -> {
                SourceType type = decodeSourceType(path, record.get());
                yield new ErasureScope.Source(type, record.getLong());
            }
            case SCOPE_EXTRACTOR_VERSION -> new ErasureScope.ExtractorVersion(record.getInt());
            default -> throw new IOException("erasure log " + path + " holds an intact request of "
                + "unknown scope " + discriminator + "; it was written by a newer build and "
                + "applying it would erase the wrong edges");
        };
    }

    /**
     * Decodes a stored source type, refusing one this build does not know.
     *
     * <p>Deliberately not {@link SourceType#fromByte}, which answers UNKNOWN so that one edge of
     * uncertain origin cannot fail a whole traversal. Here the byte selects which edges get
     * destroyed, and answering UNKNOWN would run the request against a source type nobody asked
     * for while leaving the one they did ask for on disk.
     */
    private static SourceType decodeSourceType(Path path, byte stored) throws IOException {
        int ordinal = stored & 0xFF;
        SourceType[] all = SourceType.values();
        if (ordinal >= all.length) {
            throw new IOException("erasure log " + path + " names source type ordinal " + ordinal
                + ", which this build does not know; it was written by a newer build");
        }
        return all[ordinal];
    }

    @Override
    public String toString() {
        return "ErasureLog[" + path + ", " + outstandingCount() + " outstanding]";
    }
}
