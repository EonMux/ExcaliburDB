package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogHeader;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.file.TailIntegrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;
import java.util.zip.CRC32;

/**
 * Assigns a stable integer id to every distinct label it is shown, and remembers the assignment.
 *
 * <h2>Why the ids have to be stable</h2>
 * <p>The edge store addresses entities by integer, and so does everything built beside it: the
 * lexical index over entity labels stores that integer as its document id, and a serialized
 * subgraph names entities by it. The store is rebuilt whenever the graph is rebuilt, and if the
 * rebuild reassigned ids then every artifact that was not rebuilt in the same breath would point at
 * the wrong entity - silently, because an integer is always a valid integer. Ids are therefore
 * handed out once, in the order labels are first seen, and never reused or renumbered.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * [magic : int64][version : int32][reserved : int32]
 * repeated, id = ordinal:
 *   [length : int32][label : UTF-8][crc32 of the label bytes : int32]
 * </pre>
 *
 * <h2>Durable before anyone holds it</h2>
 * <p>{@link #intern} forces a new label's record to the device before it returns the id, and
 * {@link #labels}, {@link #labelOf} and {@link #size} show only labels that are forced. The ids
 * go on to be written into files this class does not control - the triple log, the entity text
 * log, a published graph, an erasure request - and the operating system writes those back in
 * whatever order it likes. Were an id handed out ahead of its record, a power cut could keep a
 * fact naming the id and lose the record that says who the id is, and the next new label would
 * be given it. Forcing first is what makes that ordering impossible rather than unlikely. Forces
 * are shared: a thread that finds a force already running waits for it, and the next force
 * covers every record written in the meantime, so concurrent ingestion pays one device flush per
 * batch of new labels rather than one each.</p>
 *
 * <h2>What an open may cut off</h2>
 * <p>The file is read to the end on open. A record whose checksum does not match, or whose length
 * runs past the end of the file, ends the scan. When nothing readable follows it, it is the last
 * record, and a crash during {@link #intern} leaves exactly that - a record that never finished
 * being forced, whose id was therefore never returned. Cutting it off is what lets ingestion resume
 * after a crash instead of requiring the graph to be rebuilt from nothing.</p>
 *
 * <p>A record that was forced and then damaged looks the same, though, and its id may be held
 * anywhere. So before cutting anything off, the open asks the holders it is given for the highest
 * id they refer to, and refuses when one of them refers to an id the interner is about to lose; see
 * {@link ReferencedLabels}. Handing that id to a new label would serve one entity under another's
 * name, the one outcome this class exists to prevent.</p>
 *
 * <p>A file that is absent, or shorter than its header and the start of it, is a new interner: the
 * header is forced before any id is handed out, so neither ever held one. The holders are not
 * asked, because a graph published from somewhere else is served beside a staging directory that
 * starts empty, and its ids were never this interner's to lose.</p>
 *
 * <p>That tolerance holds only while nothing readable follows the damage. Labels lost from the
 * middle of the file would have their ids handed out again to different labels, while every
 * artifact built from an earlier state still maps those ids to the labels that were lost, so that
 * case fails the open whatever the holders say; see {@link TailIntegrity}.</p>
 *
 * <h2>Erasure</h2>
 * <p>{@link #tombstone} is the only way a label leaves this file. It rewrites the file in place of
 * appending, because an erased name that is still on disk has not been erased, and it keeps every
 * ordinal where it was, because an erasure may not renumber a published graph.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Lookups of forced labels are lock-free. Writing a new record takes a
 * lock, because the id an appended record gets is its position in the file and the two have to
 * agree; forcing takes another, held across the device flush, so records keep being written while
 * a force runs.</p>
 *
 * @author ExcaliburDB
 */
public final class LabelInterner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LabelInterner.class);

    /** Identifies the file as an ExcaliburDB label interner: ASCII {@code EXCLBLBL}. */
    public static final long MAGIC = 0x4558434C424C424CL;

    /** Format version written by this build. */
    public static final int VERSION = 1;

    /** Bytes before the first record. */
    public static final int HEADER_BYTES = LogHeader.BYTES;

    /**
     * Longest label this interner will store.
     *
     * <p>An entity name is a name. A value far past this is a extraction that went wrong, and
     * storing it would put a megabyte of prose where an id's label belongs.
     */
    public static final int MAX_LABEL_BYTES = 4096;

    /** Suffix of the whole-file replacement a tombstoning rewrite builds before swapping it in. */
    private static final String REWRITE_SUFFIX = ".rewriting";

    /** What this log is called in messages. */
    private static final String WHAT = "label interner";

    /**
     * Answers the highest id something outside the interner holds.
     *
     * <p>Asked only when an open is about to cut a damaged last record off, so a clean open reads
     * nothing else.</p>
     */
    @FunctionalInterface
    public interface Holders {

        /**
         * @return The highest id held, and what holds it
         * @throws IOException if a holder cannot be read
         */
        ReferencedLabels.Reference highest() throws IOException;
    }

    /** Names no holder. */
    public static final Holders NO_HOLDERS = () -> ReferencedLabels.Reference.NONE;

    private final Path path;

    /** Forced labels only; what a lookup outside the lock may answer with. */
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();

    /** Every label written, in id order. Guarded by {@link #appendLock}. */
    private final List<String> labels = new ArrayList<>();

    /** Labels written and not yet forced, by id. Guarded by {@link #appendLock}. */
    private final Map<String, Integer> unforced = new HashMap<>();

    private final Object appendLock = new Object();

    /** Held across a device flush; taken before {@link #appendLock}, never after it. */
    private final ReentrantLock forceLock = new ReentrantLock();

    /** Guarded by {@link #appendLock}; replaced by a tombstoning rewrite. */
    private FileChannel channel;

    /** How many ids, counting from zero, are forced to the device. */
    private volatile int forced;

    /**
     * The write or force that failed, after which no further id is handed out. Guarded by
     * {@link #appendLock}.
     *
     * <p>A failed write may leave part of a record in the file, and a failed force leaves the
     * device's copy unknown. Appending past either would put an intact record behind damage, and
     * an id returned after it would rest on a record nobody can vouch for, so the interner stops
     * assigning until it is opened again and the file is judged afresh.</p>
     */
    private IOException failure;

    private volatile boolean closed;

    private LabelInterner(Path path, FileChannel channel, List<String> existing) {
        this.path = path;
        this.channel = channel;
        for (String label : existing) {
            ids.put(label, labels.size());
            labels.add(label);
        }
        this.forced = labels.size();
    }

    /**
     * Opens the interner at {@code path}, with nothing outside it holding its ids.
     *
     * @param path The file to read and append to; created if absent
     * @return The interner
     * @throws IOException if the file cannot be read or opened for appending
     * @see #open(Path, Holders)
     */
    public static LabelInterner open(Path path) throws IOException {
        return open(path, NO_HOLDERS);
    }

    /**
     * Opens the interner at {@code path}, reading back every assignment it already holds.
     *
     * @param path    The file to read and append to; created if absent
     * @param holders What holds this interner's ids elsewhere, asked before any is given up
     * @return The interner
     * @throws IOException if the file cannot be read or opened for appending, is too short to
     *                     hold a header and is not a torn creation of one, or is not an interner
     *                     this build reads
     * @throws LogCorruptionException if damage has an intact label behind it, or the open would
     *                                lose an id a holder refers to
     */
    public static LabelInterner open(Path path, Holders holders) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(holders, "holders must not be null");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean fresh = LogHeader.requireCreatable(path, WHAT, MAGIC, VERSION);
        List<String> existing = List.of();
        if (fresh) {
            if (Files.exists(path)) {
                // A header write cut short. Truncated so the header lands at the start of the
                // file, where the next open reads it, rather than after the fragment.
                LOGGER.warn("starting {} afresh: its {} bytes are a header write a crash cut short",
                    path, Files.size(path));
                truncate(path, 0);
            }
        } else {
            Replay replay = readChecked(path);
            existing = replay.labels();
            long torn = replay.contents().length - replay.validBytes();
            if (torn > 0) {
                // Truncated to the last record that read cleanly, before anything is appended.
                // Left in place, every record written after it would sit behind a record the
                // next replay stops at, and those labels would be read back as absent.
                requireUnheld(path, existing.size(), torn, holders);
                LOGGER.warn("truncating {} from {} to {} bytes: the tail was not readable, and "
                    + "no id it could have held is referred to", path, replay.contents().length,
                    replay.validBytes());
                truncate(path, replay.validBytes());
            }
        }

        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        try {
            if (fresh) {
                writeFully(channel, LogHeader.of(MAGIC, VERSION));
                channel.force(true);
                // The name as well as the bytes: labels forced into a file whose directory entry
                // is lost are lost with it, while the ids they were given live on elsewhere.
                if (parent != null) {
                    Durability.DEVICE.fsyncDirectory(parent);
                }
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        LOGGER.debug("opened label interner {} holding {} labels", path, existing.size());
        return new LabelInterner(path, channel, existing);
    }

    /**
     * Reads what an interner holds without opening it, with nothing outside it holding its ids.
     *
     * @param path The interner's file
     * @return How many labels matched their checksums, and where the file stops reading cleanly
     * @throws IOException if the file cannot be judged; see {@link #inspect(Path, Holders)}
     */
    public static LogInspection inspect(Path path) throws IOException {
        return inspect(path, NO_HOLDERS);
    }

    /**
     * Reads what an interner holds without opening it.
     *
     * <h3>Why this is not {@link #open}</h3>
     * <p>Opening creates the file and its directory when they are missing, cuts a torn tail off
     * the end and takes an append handle - the right things for a node taking the interner over,
     * and writes an offline checker must not make. This reads the file once, walks it with the
     * replay {@link #open} uses, draws the same line between a torn tail and damage, asks the
     * same holders the same question, and writes nothing.</p>
     *
     * <h3>What it refuses</h3>
     * <p>Everything {@link #open} refuses, on the same terms: a file that is not an interner, a
     * version this build does not read, a record that fails its checksum with an intact record
     * somewhere after it, and a damaged last record whose id a holder refers to - the last two as
     * a {@link LogCorruptionException}, so that damage can be told apart from a file that merely
     * could not be read. Counting the labels before such damage would be a wrong answer rather
     * than a partial one: the open refuses the file, and a count that read as sound would say the
     * node will start when it will not.</p>
     *
     * <p>A damaged last record nothing refers to is reported rather than refused, because the
     * open treats it as a write cut short and trims it; see {@link LogInspection#hasTornTail()}.
     * So is a file shorter than its header whose bytes begin the header this build writes: a
     * creation cut short, which the open starts afresh.</p>
     *
     * @param path    The interner's file
     * @param holders What holds this interner's ids elsewhere
     * @return How many labels matched their checksums, and where the file stops reading cleanly
     * @throws IOException if the file is absent, too short to be even a torn interner, not an
     *                     interner of a version this build reads, or cannot be read
     * @throws LogCorruptionException if damage has an intact label behind it, or the open would
     *                                lose an id a holder refers to
     * @throws NullPointerException if an argument is null
     */
    public static LogInspection inspect(Path path, Holders holders) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(holders, "holders must not be null");
        byte[] contents = Files.readAllBytes(path);
        if (contents.length < HEADER_BYTES) {
            LogHeader.requireTornCreation(path, WHAT, contents, MAGIC, VERSION);
            return LogHeader.tornCreation(VERSION, contents);
        }
        Replay replay = readChecked(path);
        long torn = replay.contents().length - replay.validBytes();
        if (torn > 0) {
            requireUnheld(path, replay.labels().size(), torn, holders);
        }
        return new LogInspection(VERSION, replay.labels().size(), replay.validBytes(),
            replay.contents().length);
    }

    /**
     * Refuses an open that would lose an id something refers to.
     *
     * @param path    The interner, named in the failure
     * @param kept    How many labels the open keeps; the next new label gets this id
     * @param torn    How many bytes the open cuts off
     * @param holders What holds the interner's ids elsewhere
     * @throws IOException if a holder cannot be read
     * @throws LogCorruptionException if a holder refers to an id at or past {@code kept}
     */
    private static void requireUnheld(Path path, int kept, long torn, Holders holders)
            throws IOException {
        ReferencedLabels.Reference held = holders.highest();
        if (held.highestId() < kept) {
            return;
        }
        throw new LogCorruptionException(WHAT + " " + path + " holds " + kept + " intact labels "
            + "and then " + torn + " bytes that do not read as one, but id " + held.highestId()
            + " is held by " + held.heldBy() + ". A label is forced to the device before its id "
            + "is handed out, so this is not a write a crash cut short: a label whose id is in use "
            + "was lost after it was written. Cutting it off would give id " + kept + " to the next "
            + "new label, and " + held.heldBy() + " would go on to describe that label as the one "
            + "that was lost - one entity served under another's name; this build refuses the "
            + "interner instead. "
            + "Restore the staging directory from a backup, or rebuild it from the source "
            + "documents, before restarting.");
    }

    /**
     * The id for {@code label}, assigning and recording one if it is new.
     *
     * <p>A new label's record is forced to the device before its id is returned; see "Durable
     * before anyone holds it".</p>
     *
     * @param label The label
     * @return Its id, stable for the life of the file
     * @throws IllegalArgumentException if the label is null, blank or longer than
     *                                  {@link #MAX_LABEL_BYTES} when encoded
     * @throws IOException if a new assignment cannot be recorded and forced, or an earlier one
     *                     could not be and the interner has stopped assigning
     * @throws IllegalStateException if the interner is closed
     */
    public int intern(String label) throws IOException {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be null or blank");
        }
        Integer known = ids.get(label);
        if (known != null) {
            return known;
        }

        byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LABEL_BYTES) {
            throw new IllegalArgumentException(
                "label is " + bytes.length + " bytes, above the " + MAX_LABEL_BYTES + " byte limit");
        }

        int id;
        synchronized (appendLock) {
            requireWritable();
            // Re-checked under the lock: two threads can both miss the map, and only one of them
            // may write the record that fixes the id.
            known = ids.get(label);
            if (known != null) {
                return known;
            }
            Integer written = unforced.get(label);
            if (written != null) {
                id = written;
            } else {
                id = labels.size();
                try {
                    writeFully(channel, encode(bytes));
                } catch (IOException e) {
                    failure = e;
                    throw e;
                }
                labels.add(label);
                unforced.put(label, id);
            }
        }
        awaitForced(id);
        return id;
    }

    /**
     * Returns once the record for {@code id} is on the device, forcing it if nobody else is.
     *
     * @param id An id whose record has been written
     * @throws IOException if the force fails
     */
    private void awaitForced(int id) throws IOException {
        if (forced > id) {
            return;
        }
        forceLock.lock();
        try {
            // A force that finished while this thread waited may already have covered the id.
            if (forced > id) {
                return;
            }
            FileChannel target;
            int written;
            synchronized (appendLock) {
                requireWritable();
                target = channel;
                written = labels.size();
            }
            try {
                target.force(false);
            } catch (IOException e) {
                synchronized (appendLock) {
                    failure = e;
                }
                throw e;
            }
            synchronized (appendLock) {
                markForced(written);
            }
        } finally {
            forceLock.unlock();
        }
    }

    /**
     * Makes every label below {@code count} visible as forced. Called under {@link #appendLock}.
     *
     * @param count How many ids, counting from zero, are on the device
     */
    private void markForced(int count) {
        for (int id = forced; id < count; id++) {
            String label = labels.get(id);
            unforced.remove(label);
            ids.put(label, id);
        }
        if (count > forced) {
            forced = count;
        }
    }

    /**
     * The placeholder an erased id's label is replaced by.
     *
     * <p>Carries the id so that two erased labels stay distinct strings. Were they identical, the
     * label-to-id map would hold one entry for both and {@link #size()} would fall below the
     * number of ids assigned - and that count is the entity count an edge store is built with, so
     * shrinking it would size the store's offset array below ids that are still in use.</p>
     *
     * @param id The erased id
     * @return A non-blank label that names nobody
     */
    public static String erasedLabel(int id) {
        return "(erased " + id + ")";
    }

    /**
     * Replaces the labels of erased ids with {@link #erasedLabel}, keeping every ordinal in place.
     *
     * <h3>Why a rewrite rather than an append</h3>
     * <p>The file is the only record of what each id is called, and an id's label is its ordinal in
     * the file. An erased name cannot be retracted by appending anything, because the bytes of the
     * name would still be on disk - which is the whole distinction between a tombstone and an
     * erasure. So the file is rewritten with the same records in the same order, and only the
     * erased ones carrying different bytes.</p>
     *
     * <p>Ids therefore do not move, which they must not: the published edge store, the lexical
     * index over labels and any serialized subgraph all address entities by these integers, and
     * none of them is rebuilt by an erasure.</p>
     *
     * <p>The erased label is also dropped from the lookup map, so it survives neither on disk nor
     * in this process, and re-ingesting the same name afterwards assigns a fresh id rather than
     * reviving the one that was erased.</p>
     *
     * <p>Only ids that have been handed out are offered to {@code erased}. The rewrite is forced,
     * so it leaves every label written so far on the device.</p>
     *
     * @param erased Answers true for each id whose label must go
     * @return How many labels were replaced
     * @throws IOException if the rewritten file cannot be written, forced or moved into place
     * @throws NullPointerException if {@code erased} is null
     * @throws IllegalStateException if the interner is closed
     */
    public int tombstone(IntPredicate erased) throws IOException {
        Objects.requireNonNull(erased, "erased must not be null");

        forceLock.lock();
        try {
            synchronized (appendLock) {
                requireWritable();
                List<Integer> victims = new ArrayList<>();
                for (int id = 0; id < forced; id++) {
                    if (erased.test(id)) {
                        victims.add(id);
                    }
                }
                if (victims.isEmpty()) {
                    return 0;
                }

                List<String> rewritten = new ArrayList<>(labels);
                for (int id : victims) {
                    rewritten.set(id, erasedLabel(id));
                }
                rewriteFile(rewritten);

                markForced(labels.size());
                for (int id : victims) {
                    ids.remove(labels.get(id));
                    labels.set(id, rewritten.get(id));
                    ids.put(rewritten.get(id), id);
                }
                LOGGER.info("tombstoned {} label(s) in {}; {} ids unchanged",
                    victims.size(), path, labels.size());
                return victims.size();
            }
        } finally {
            forceLock.unlock();
        }
    }

    /**
     * Writes {@code replacement} over the file as a whole, durably. Called under both locks.
     *
     * <p>Built beside the real file and moved onto it, so a failure part way leaves the previous
     * labels rather than a file holding half of each. Forced before the move because the point of
     * the rewrite is that the erased bytes are gone: a rename that outran its own contents would
     * leave the erased name readable on the platter after the erasure was reported discharged.</p>
     *
     * <p>The append handle is reopened whatever happens, since the move replaces the file it was
     * writing to and an interner left holding the old one would accept labels nothing reads.</p>
     *
     * @param replacement Every label, in id order
     * @throws IOException if the replacement cannot be written or swapped in
     */
    private void rewriteFile(List<String> replacement) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + REWRITE_SUFFIX);
        Files.deleteIfExists(temp);
        channel.close();
        try {
            try (OutputStream rewritten = new BufferedOutputStream(Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                rewritten.write(LogHeader.of(MAGIC, VERSION));
                for (String label : replacement) {
                    rewritten.write(encode(label.getBytes(StandardCharsets.UTF_8)));
                }
            }
            Durability.DEVICE.replaceDurably(temp, path);
        } finally {
            Files.deleteIfExists(temp);
            try {
                channel = FileChannel.open(path, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            } catch (IOException e) {
                failure = e;
                throw e;
            }
        }
    }

    /**
     * The label an id was assigned to.
     *
     * @param id The id
     * @return The label, or null when no such id has been handed out
     */
    public String labelOf(int id) {
        synchronized (appendLock) {
            return id >= 0 && id < forced ? labels.get(id) : null;
        }
    }

    /**
     * Every label in id order.
     *
     * @return A snapshot of every label handed out; index is the id
     */
    public List<String> labels() {
        synchronized (appendLock) {
            return List.copyOf(labels.subList(0, forced));
        }
    }

    /**
     * How many ids have been handed out.
     *
     * @return The count; ids are {@code 0..size()-1}
     */
    public int size() {
        return forced;
    }

    @Override
    public void close() throws IOException {
        forceLock.lock();
        try {
            synchronized (appendLock) {
                if (closed) {
                    return;
                }
                closed = true;
                channel.close();
            }
        } finally {
            forceLock.unlock();
        }
    }

    /**
     * Refuses to assign when the interner is closed or an earlier write or force failed.
     * Called under {@link #appendLock}.
     *
     * @throws IOException if an earlier write or force failed
     * @throws IllegalStateException if the interner is closed
     */
    private void requireWritable() throws IOException {
        if (closed) {
            throw new IllegalStateException("label interner is closed: " + path);
        }
        if (failure != null) {
            throw new IOException("label interner " + path + " stopped assigning ids after a "
                + "write or force failed; reopen it to judge what reached the file", failure);
        }
    }

    /**
     * One record as it is written: length, label, checksum.
     *
     * @param bytes The label's UTF-8 bytes
     * @return The record
     */
    private static byte[] encode(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return ByteBuffer.allocate(Integer.BYTES + bytes.length + Integer.BYTES)
            .putInt(bytes.length)
            .put(bytes)
            .putInt((int) crc.getValue())
            .array();
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Cuts the file to {@code length} and forces the cut, before anything is appended.
     *
     * @param path   The file
     * @param length Its new length
     * @throws IOException if it cannot be truncated or forced
     */
    private static void truncate(Path path, long length) throws IOException {
        try (FileChannel truncating = FileChannel.open(path, StandardOpenOption.WRITE)) {
            truncating.truncate(length);
            truncating.force(true);
        }
    }

    /**
     * Reads back the assignments a previous run recorded.
     *
     * <p>Stops at the first record that does not read cleanly rather than failing; what that
     * stop means is for {@link #readChecked} and the open to decide.
     *
     * @param path The file, holding at least a header
     * @return The labels, in id order
     * @throws IOException if the file cannot be read, or is not an interner this build reads
     */
    private static Replay readExisting(Path path) throws IOException {
        List<String> labels = new ArrayList<>();
        byte[] all = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(all);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not a label interner file (magic 0x"
                + Long.toHexString(magic) + "): " + path);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported label interner version " + version + ": " + path);
        }
        buffer.getInt();

        long validBytes = buffer.position();
        while (true) {
            try {
                if (buffer.remaining() < Integer.BYTES) {
                    break;
                }
                int length = buffer.getInt();
                if (length <= 0 || length > MAX_LABEL_BYTES
                    || buffer.remaining() < length + Integer.BYTES) {
                    break;
                }
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                int stored = buffer.getInt();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (stored != (int) crc.getValue()) {
                    LOGGER.warn("label interner {} has a corrupt record at label {}", path,
                        labels.size());
                    break;
                }
                labels.add(new String(bytes, StandardCharsets.UTF_8));
                validBytes = buffer.position();
            } catch (RuntimeException e) {
                break;
            }
        }
        return new Replay(labels, validBytes, all);
    }

    /**
     * Reads back the assignments a previous run recorded, refusing damage that is not a tail.
     *
     * <p>The one judgement {@link #open} and {@link #inspect} share. Labels lost from the middle
     * of the file would be reassigned to whatever is interned next, while the published
     * {@code entities.idx} still maps those ids to the labels that were lost - one entity served
     * under another's name, reported by nothing - so unreadable bytes are accepted only when
     * nothing readable follows them.</p>
     *
     * @param path The file, holding at least a header
     * @return The labels, in id order, and where they end
     * @throws IOException if the file cannot be read or is not an interner this build reads
     * @throws LogCorruptionException if damage has an intact label behind it
     */
    private static Replay readChecked(Path path) throws IOException {
        Replay replay = readExisting(path);
        TailIntegrity.requireTornTail(path, WHAT, replay.contents(),
            replay.validBytes(), LabelInterner::recordStartsAt);
        return replay;
    }

    /**
     * What a replay recovered, and how far into the file it got.
     *
     * @param labels     the labels read, in id order
     * @param validBytes byte offset just past the last record that read cleanly
     * @param contents   the whole file as read, so the tail can be examined without re-reading it
     */
    private record Replay(List<String> labels, long validBytes, byte[] contents) {
    }

    /**
     * Whether a complete, checksum-clean label record begins at {@code offset}.
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
        if (length <= 0 || length > MAX_LABEL_BYTES
            || buffer.remaining() < length + Integer.BYTES) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(contents, offset + Integer.BYTES, length);
        buffer.position(buffer.position() + length);
        return buffer.getInt() == (int) crc.getValue();
    }

    @Override
    public String toString() {
        return "LabelInterner[" + path + ", " + size() + " labels]";
    }
}
